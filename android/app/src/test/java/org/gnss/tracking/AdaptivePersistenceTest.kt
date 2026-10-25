package org.gnss.tracking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AdaptivePersistenceTest {
    private val on = PersistencePolicy(enabled = true, changes = 1, effectiveAt = utc(100000))

    private fun value(
        n: Int,
        speed: Double? = 2.0,
        bearing: Double? = 90.0,
        accuracy: Double? = 1.0,
        x: Double = n * 2.0,
    ): CapturedObservation =
        CapturedObservation(
            "fix-$n",
            100000 + n * 1000L,
            Fix(
                utc(100000 + n * 1000L),
                0,
                0.0,
                Math.toDegrees(x / 6371008.8),
                accuracy,
                null,
                null,
                speed,
                bearing,
            ),
            Health(gnss_status = "fix"),
            "generation",
            (100000 + n * 1000L) * 1000000,
        )

    @Test
    fun absentUpgradeUnknownAndExplicitOptInSurviveRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("policy-test", 0)
        prefs.edit().clear().commit()
        assertFalse(LocalPersistencePolicy(prefs).state.enabled)
        val store = LocalPersistencePolicy(prefs)
        store.setEnabled(true, utc(100000))
        // Independently read the committed XML, not SharedPreferences' in-process
        // object cache. This proves the restart input exists on disk; OS kill/FGS
        // scheduling remains a separate physical acceptance concern.
        val file = java.io.File(app.applicationInfo.dataDir, "shared_prefs/policy-test.xml")
        assertTrue(file.exists())
        val parser = android.util.Xml.newPullParser()
        file.reader().use { input ->
            parser.setInput(input)
            var raw: String? = null
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (
                    parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        parser.name == "string" &&
                        parser.getAttributeValue(null, "name") == "effective"
                )
                    raw = parser.nextText()
                parser.next()
            }
            assertNotNull(raw)
            assertEquals(
                store.state,
                com.google.gson.Gson().fromJson(raw, PersistencePolicy::class.java),
            )
        }
        assertEquals(store.state, LocalPersistencePolicy(prefs).state)
        store.setEnabled(false, utc(101000))
        assertFalse(LocalPersistencePolicy(prefs).state.enabled)
        assertEquals(2L, store.state.changes)
        prefs.edit().putString("effective", "{\"schema\":99,\"enabled\":true}").commit()
        assertFalse(LocalPersistencePolicy(prefs).state.enabled)
        assertNotNull(LocalPersistencePolicy(prefs).state.error)
        prefs
            .edit()
            .putString(
                "effective",
                """{"schema":1,"revision":1,"enabled":false,"enabled":true,"changes":1,"effectiveAt":"1970-01-01T00:01:40.000Z"}""",
            )
            .commit()
        assertFalse(LocalPersistencePolicy(prefs).state.enabled)
        assertNotNull(LocalPersistencePolicy(prefs).state.error)
    }

    @Test
    fun failedPolicyCommitNeverPublishesAdaptiveConsent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("failed-policy-test", 0)
        val failing =
            object : android.content.SharedPreferences by prefs {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val delegate = prefs.edit()
                    return object : android.content.SharedPreferences.Editor by delegate {
                        override fun putString(
                            key: String?,
                            value: String?,
                        ): android.content.SharedPreferences.Editor = this

                        override fun commit() = false
                    }
                }
            }
        val policy = LocalPersistencePolicy(failing)
        try {
            policy.setEnabled(true, utc(100000))
            fail("commit must fail")
        } catch (_: IllegalStateException) {}
        assertFalse(policy.state.enabled)
        assertNotNull(policy.state.error)
        assertFalse(LocalPersistencePolicy(prefs).state.enabled)
    }

    @Test
    fun rejectedDisableRetainsSavedConsentButUsesDenseFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("failed-disable-test", 0)
        LocalPersistencePolicy(prefs).setEnabled(true, utc(100000))
        val failing = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : android.content.SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor = this
                    override fun commit() = false
                }
            }
        }
        val policy = LocalPersistencePolicy(failing)
        try {
            policy.setEnabled(false, utc(101000))
            fail("commit must fail")
        } catch (_: IllegalStateException) {}
        assertTrue(policy.state.enabled)
        assertTrue(LocalPersistencePolicy(prefs).state.enabled)
        assertNotNull(policy.state.error)
        assertFalse(SamplingState(policy = policy.state).effectiveAdaptive)
        assertEquals(1L, policy.state.changes)
    }

    @Test
    fun denseDefaultRetainsEveryOriginalAndSwitchOffIsProspective() {
        val sampler = AdaptivePersistence()
        val input = (0..60).map { value(it) }
        assertEquals(input, input.flatMap { sampler.select(it, PersistencePolicy()) })
        val adaptive = AdaptivePersistence()
        val retained = input.take(30).flatMap { adaptive.select(it, on) }
        assertTrue(retained.size < 30)
        val dense =
            input.drop(30).flatMap { adaptive.select(it, on.copy(enabled = false, changes = 2)) }
        assertTrue(dense.containsAll(input.drop(30)))
        assertEquals(input.drop(30), dense.filter { it.capturedMillis >= input[30].capturedMillis })
    }

    @Test
    fun targetsAndHysteresisRetainOnlyOriginalsWithBoundedIntervals() {
        for (stationary in listOf(false, true)) {
            val sampler = AdaptivePersistence()
            val input =
                (0..3600).map {
                    value(
                        it,
                        if (stationary) 0.0 else 2.0,
                        x = if (stationary) (it % 3 - 1) * 0.2 else it * 2.0,
                    )
                }
            val retained = input.flatMap { sampler.select(it, on) } + sampler.boundary("stop")
            assertTrue(input.containsAll(retained))
            assertEquals(retained.size, retained.map { it.id }.distinct().size)
            assertEquals(input.first(), retained.first())
            assertEquals(input.last(), retained.last())
            val intervals =
                retained.zipWithNext { a, b -> (b.elapsedNanos - a.elapsedNanos) / 1000000 }
            assertTrue(intervals.max() <= if (stationary) 30000 else 5000)
            assertTrue(retained.size < if (stationary) 140 else 730)
            assertEquals(
                input.size.toLong(),
                sampler.state.selected + sampler.state.intentionallyNotSelected,
            )
        }
    }

    @Test
    fun turnReversalRapidSpeedAndBoundaryPromotePreviousAndCurrentOriginals() {
        for (changed in
            listOf(value(4, bearing = 180.0), value(4, bearing = 270.0), value(4, speed = 7.0))) {
            val sampler = AdaptivePersistence()
            (0..3).forEach { sampler.select(value(it), on) }
            assertEquals(listOf(value(3), changed), sampler.select(changed, on))
        }
        val recreated = AdaptivePersistence()
        (0..3).forEach { recreated.select(value(it), on) }
        val restarted = value(4).copy(generation = "recreated-service")
        assertEquals(listOf(value(3), restarted), recreated.select(restarted, on))
        for (boundary in listOf("stop", "sos", "provider_loss", "reconnect")) {
            val sampler = AdaptivePersistence()
            (0..3).forEach { sampler.select(value(it), on) }
            assertEquals(listOf(value(3)), sampler.boundary(boundary))
            assertTrue(sampler.boundary(boundary).isEmpty())
        }
    }

    @Test
    fun weakMissingReorderedClockAnomalyRecoveryAndFailureAreConservative() {
        for (input in
            listOf(
                (0..10).map { value(it, speed = null) },
                (0..10).map { value(it, bearing = null) },
                (0..10).map { value(it, accuracy = 50.0) },
            )) {
            val sampler = AdaptivePersistence()
            assertEquals(input, input.flatMap { sampler.select(it, on) })
        }
        val sampler = AdaptivePersistence()
        (0..3).forEach { sampler.select(value(it), on) }
        val jump = value(4).copy(fix = value(4).fix.copy(observed_at = utc(500000)))
        assertEquals(listOf(value(3), jump), sampler.select(jump, on))
        val older = value(2)
        assertEquals(listOf(older), sampler.select(older, on))
        assertEquals(listOf(value(20)), sampler.select(value(20), on))
        assertEquals(1L, sampler.state.callbackGaps)
        sampler.failSafe()
        val next = (21..30).map { value(it) }
        assertEquals(next, next.flatMap { sampler.select(it, on) })
        assertFalse(sampler.state.effectiveAdaptive)
        assertEquals(listOf(value(31)), AdaptivePersistence().select(value(31), on))
    }

    @Test
    fun reorderedMeasurementTimeDoesNotInventCallbackGap() {
        val selector = AdaptivePersistence()
        selector.select(value(0), on)
        val delayed =
            value(-4).copy(capturedMillis = 101000, fix = value(-4).fix.copy(fix_age_ms = 5000))
        selector.select(delayed, on)
        selector.select(value(2), on)
        assertEquals(0L, selector.state.callbackGaps)
    }

    @Test
    fun gapWithoutPendingCandidateResetsStationaryConfidence() {
        val selector = AdaptivePersistence()
        (0..60).forEach { selector.select(value(it, speed = 0.0, x = 0.0), on) }
        assertFalse(selector.state.boundaryCandidate) // Last pre-gap fix is already selected.
        val recovery = (361..391).flatMap { selector.select(value(it, speed = 0.0, x = 0.0), on) }
        assertEquals(value(361, speed = 0.0, x = 0.0), recovery.first())
        assertTrue(recovery.size >= 7)
        assertTrue(recovery.zipWithNext { a, b -> (b.elapsedNanos - a.elapsedNanos) / 1000000 }.max() <= 5000)
        assertEquals(1L, selector.state.callbackGaps)
    }

    @Test
    fun silenceFlushesLastAvailableFixWithoutInventingRecovery() {
        val sampler = AdaptivePersistence()
        (0..3).forEach { sampler.select(value(it), on) }
        assertTrue(sampler.quiet(105999).isEmpty())
        assertEquals(listOf(value(3)), sampler.quiet(106000))
        assertTrue(sampler.quiet(200000).isEmpty())
        assertEquals(listOf(value(120)), sampler.select(value(120), on))
    }

    @Test
    fun failedWritesKeepIdentityAndForceFutureDenseFallback() = runBlocking {
        val clock = FakeClock(100000)
        val saved = mutableListOf<CapturedObservation>()
        val failed = CompletableDeferred<Unit>()
        var first = true
        val writer =
            ObservationPersistence(
                save = { v ->
                    if (first) {
                        first = false
                        failed.complete(Unit)
                        throw IllegalStateException("disk full")
                    }
                    saved.add(v)
                    Outbound(saved.size.toLong(), v.id, "location", v.capturedMillis, "saved")
                },
                policy = { on },
                clock = clock,
            )
        assertTrue(writer.submit(Observation(value(0).fix, clock.now), Health(), null))
        failed.await()
        // Let the failure handler publish fallback before the next callback.
        withTimeout(2000) { while (writer.samplingState.fallback == null) yield() }
        for (n in 1..10) {
            clock.now += 1000
            writer.submit(Observation(value(n).fix, clock.now), Health(), null)
        }
        writer.finish()
        assertEquals(11, saved.size)
        assertEquals(11L, writer.state.committedObservations)
        assertFalse(writer.samplingState.effectiveAdaptive)
    }

    @Test
    fun failedEvaluationStillCommitsOriginalAndReportsDenseFallback() = runBlocking {
        val saved = mutableListOf<CapturedObservation>()
        val writer =
            ObservationPersistence(
                save = { v ->
                    saved.add(v)
                    Outbound(1, v.id, "location", v.capturedMillis, "saved")
                },
                clock = FakeClock(100000),
                policy = { error("unavailable policy") },
            )
        assertTrue(writer.submit(Observation(value(0).fix, 100000), Health(), null))
        writer.finish()
        assertEquals(value(0).fix, saved.single().fix)
        assertEquals(1L, writer.samplingState.candidates)
        assertEquals(1L, writer.state.committedObservations)
        assertNotNull(writer.samplingState.fallback)
        assertFalse(writer.samplingState.effectiveAdaptive)
    }

    @Test
    fun livePromotionCommitsOneGoalDespiteContinuousNewCandidates() = runBlocking {
        val clock = FakeClock(100000)
        val gate = CompletableDeferred<Unit>()
        val writer =
            ObservationPersistence(
                save = { v ->
                    gate.await()
                    Outbound(1, v.id, "location", v.capturedMillis, "saved")
                },
                clock = clock,
                policy = { on },
            )
        for (n in 0..3) {
            clock.now = 100000 + n * 1000L
            writer.submit(Observation(value(n).fix, clock.now), Health(), null)
        }
        assertFalse(writer.prepareLive())
        clock.now += 1000
        writer.submit(Observation(value(4).fix, clock.now), Health(), null)
        assertFalse(writer.prepareLive())
        gate.complete(Unit)
        withTimeout(5000) { while (writer.state.awaitingCommit > 0) delay(1) }
        assertTrue(writer.prepareLive())
        // The newer callback is still a candidate, not another moving commit goal.
        assertTrue(writer.samplingState.boundaryCandidate)
        writer.finish()
    }

    @Test
    fun continuousSevenDayReplayCountsOneTwoAndSevenDayFleetVolumes() {
        val reports = mutableListOf<Map<String, Any>>()
        for (mix in listOf("stationary", "walking", "mixed")) {
            val sampler = AdaptivePersistence()
            var retained = 0L
            for (n in 0..7 * 86400) {
                val moving = mix == "walking" || (mix == "mixed" && n % 14400 >= 10800)
                val x =
                    when (mix) {
                        "walking" -> n * 2.0
                        "mixed" -> (n / 14400 * 3600 + (n % 14400 - 10800).coerceAtLeast(0)) * 2.0
                        else -> (n % 3 - 1) * 0.2
                    }
                retained +=
                    sampler.select(value(n, speed = if (moving) 2.0 else 0.0, x = x), on).size
                if (n in listOf(86400, 172800, 604800)) {
                    val savedWithStop = retained + if (sampler.state.boundaryCandidate) 1 else 0
                    for (fleet in listOf(1, 5, 20, 21)) reports.add(
                        linkedMapOf(
                            "mix" to mix,
                            "days" to n / 86400,
                            "phones" to fleet,
                            "callbacks" to (n + 1L) * fleet,
                            "retained_if_stopped" to savedWithStop * fleet,
                            "observation_reduction_percent" to
                                100.0 * (1 - savedWithStop.toDouble() / (n + 1)),
                        )
                    )
                }
            }
        }
        val json = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(reports)
        println("ADAPTIVE_VOLUME_REPORT " + json)
        System.getenv("GNSS_ADAPTIVE_REPORT")?.let {
            java.io.File(java.io.File(it).parentFile, "adaptive-volumes.json").writeText(json)
        }
    }

    @Test
    fun measuredRoomStorageRetainsEvidenceAndReportsActualBytes(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val report = mutableListOf<Map<String, Any>>()
        for (enabled in listOf(false, true)) {
            val name = "storage-$enabled.db"
            app.deleteDatabase(name)
            val db =
                androidx.room.Room.databaseBuilder(app, TrackingDatabase::class.java, name)
                    .addCallback(TrackingDatabase.SUMMARY_CALLBACK)
                    .build()
            try {
                val repo = Repository(db, FakeClock(100000))
                val session = CompletableDeferred(repo.beginTracking())
                val selector = AdaptivePersistence()
                val policy = on.copy(enabled = enabled)
                var payloadBytes = 0L
                for (n in 0..3600) {
                    val v =
                        value(n, speed = 0.0, x = (n % 3 - 1) * 0.2)
                            .copy(id = Protocol.newId(), session = session)
                    for (keep in selector.select(v, policy)) payloadBytes +=
                        repo.saveObservation(keep).json.toByteArray().size
                }
                for (keep in selector.boundary("stop")) payloadBytes +=
                    repo.saveObservation(keep).json.toByteArray().size
                val count = db.dao().all().size
                assertEquals(count, db.dao().all().count { it.deliveredAt == null })
                val sql = db.openHelper.writableDatabase
                fun pragma(key: String) =
                    sql.query("PRAGMA $key").use {
                        it.moveToFirst()
                        it.getLong(0)
                    }
                report.add(
                    linkedMapOf(
                        "adaptive" to enabled,
                        "callbacks" to 3601,
                        "retained" to count,
                        "immutable_payload_bytes" to payloadBytes,
                        "sqlite_allocated_bytes" to pragma("page_count") * pragma("page_size"),
                        "pending_preserved" to count,
                    )
                )
            } finally {
                db.close()
                app.deleteDatabase(name)
            }
        }
        val json = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report)
        println("ADAPTIVE_STORAGE_REPORT " + json)
        System.getenv("GNSS_ADAPTIVE_REPORT")?.let {
            java.io.File(java.io.File(it).parentFile, "adaptive-storage.json").writeText(json)
        }
    }
}
