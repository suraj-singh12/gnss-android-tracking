package org.gnss.tracking

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class IncidentRecorderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val generation = UUID.randomUUID().toString()
    private val build = DiagnosticBuild("0.1", 1, 28)

    private fun sample(
        t: Long,
        age: Long? = 0,
        callbacks: Long = t / 1000 + 1,
        gen: String = generation,
    ) =
        GnssDiagnostic(
            utc(t),
            t,
            gen,
            "user_start",
            8,
            SourceState(
                registered = true,
                statusRegistered = true,
                registeredAt = 1,
                lastLocationCallback = t - (age ?: 0),
                lastGnssCallback = t,
                locationCallbacks = callbacks,
                gnssCallbacks = callbacks,
                engineRunning = true,
                lastObservationAccepted = true,
                lastMeasurementAgeMs = age,
                lastAccuracyM = 3.2,
                satellitesTotal = 12,
                satellitesUsed = 8,
                providerEnabled = true,
                currentFixAvailable = age != null && age <= 30000,
            ),
            true,
            age,
            0,
            age,
            age != null && age <= 30000,
            false,
            true,
            0,
            PowerState(true, true, false, 0, false, false, null, null, 125, 0),
            0,
            0,
            0,
            null,
            5,
            true,
            0,
            0,
            false,
            false,
        )

    @Test
    fun pendingTransitionsPersistImmediatelyAndExportLongAfterward() {
        val j = journal()
        listOf<Int?>(null, 0, 1, 4, 1, 0, null).forEachIndexed { index, count ->
            j.append(
                sample(index * 1000L)
                    .copy(
                        pendingOutbox = count,
                        pendingOutboxError = if (index == 6) true else null,
                    )
            )
        }
        val saved =
            entries("timeline")
                .filter { it.event == DiagnosticEvent.STATE_CHANGED }
                .mapNotNull { it.snapshot }
                .map { it.pendingOutbox }
        assertEquals(listOf<Int?>(null, 0, 1, 4, 1, 0, null), saved)
        j.append(sample(3600000).copy(pendingOutbox = 0))
        val exported = zip(j).values.joinToString()
        assertTrue(exported.contains("pendingOutbox"))
        assertTrue(exported.contains("pendingOutboxError"))
    }

    private fun journal(limits: DiagnosticJournal.Limits = DiagnosticJournal.Limits()) =
        DiagnosticJournal(File(temporary.root, "diagnostics"), limits)

    private fun incidents() =
        File(temporary.root, "diagnostics")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }

    private fun entries(prefix: String) =
        File(temporary.root, "diagnostics")
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith(prefix) && it.extension == "jsonl" }
            .flatMap { it.readLines().asSequence() }
            .map { Gson().fromJson(it, DiagnosticEntry::class.java) }
            .toList()

    private fun summary(dir: File = incidents().last()) =
        Gson().fromJson(File(dir, "summary.json").readText(), IncidentSummary::class.java)

    private fun zip(j: DiagnosticJournal): Map<String, String> {
        val out = ByteArrayOutputStream()
        j.export(out, build)
        val files = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                files[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return files
    }

    @Test
    fun healthyTimelineIsCoarseButPolicyChangesAreImmediate() {
        val j = journal()
        for (t in 1000L..61000L step 1000) j.append(sample(t))
        assertTrue(incidents().isEmpty())
        assertEquals(3, entries("timeline").count { it.event == DiagnosticEvent.SAMPLE })
        j.append(sample(62000).let { it.copy(power = it.power.copy(interactive = true)) })
        assertEquals(DiagnosticEvent.STATE_CHANGED, entries("timeline").last().event)
    }

    @Test
    fun eventWritesImmediatelyWithoutWaitingForHeartbeat() {
        val j = journal()
        j.append(sample(1000))
        j.event(DiagnosticEntry(DiagnosticEvent.ACTIVITY_BACKGROUND, utc(1001), 1001, generation))
        assertEquals(DiagnosticEvent.ACTIVITY_BACKGROUND, entries("timeline").last().event)
        assertTrue(incidents().isEmpty())
    }

    @Test
    fun thresholdIsStrictAndPreFaultContextSurvives() {
        val j = journal()
        for (t in 1000L..301000L step 5000) j.append(sample(t))
        j.append(sample(331000, 30000, 302))
        assertTrue(incidents().isEmpty())
        j.append(sample(332000, 31000, 302))
        assertEquals(DiagnosticEvent.FIX_BECAME_STALE, summary().trigger)
        assertTrue(
            entries("pre").any { it.elapsed == 301000L && it.snapshot?.fixAvailable == true }
        )
        assertTrue(entries("events").any { it.event == DiagnosticEvent.FIX_BECAME_STALE })
    }

    @Test
    fun staleIncidentStaysActiveAndNeverInventsFreshFix() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        for (t in 33000L..200000L step 1000) j.append(sample(t, t - 1000, 2))
        assertNull(summary().recoveredAt)
        assertFalse(summary().finalized)
        assertTrue(entries("events").count { it.event == DiagnosticEvent.SAMPLE } > 80)
        assertFalse(entries("events").any { it.event == DiagnosticEvent.GNSS_RECOVERED })
    }

    @Test
    fun nativeFreshCallbackRecoversAtItsExactTimestampEvenIfGnssStatusRemainsSilent() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(40000, 39000, 2))
        val callback = sample(100123).source.copy(lastGnssCallback = 1000)
        j.event(
            DiagnosticEntry(
                DiagnosticEvent.LOCATION_CALLBACK,
                utc(100123),
                100123,
                generation,
                source = callback,
            )
        )
        assertEquals(100123L, summary().recoveredElapsed)
        assertEquals(69123L, summary().staleDurationMs)
        assertEquals(101L, summary().after!!.source.locationCallbacks)
        assertEquals(
            1,
            entries("events").count { it.event == DiagnosticEvent.FIRST_LOCATION_AFTER_STALE },
        )
        assertEquals(1, entries("events").count { it.event == DiagnosticEvent.GNSS_RECOVERED })
        j.append(sample(101000).copy(source = callback.copy(lastLocationCallback = 100123)))
        assertNotNull(summary().recoveredAt)
    }

    @Test
    fun rejectedOrOldCallbackAndSatelliteEventsCannotRecover() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        for (source in
            listOf(
                sample(33000).source.copy(lastObservationAccepted = false),
                sample(34000, 33000).source,
            )) j.event(
            DiagnosticEntry(
                DiagnosticEvent.LOCATION_CALLBACK,
                utc(35000),
                35000,
                generation,
                source = source,
            )
        )
        j.event(DiagnosticEntry(DiagnosticEvent.GNSS_FIRST_FIX, utc(36000), 36000, generation))
        assertNull(summary().recoveredAt)
    }

    @Test
    fun postContextFinalizesAndMuchLaterExportRetainsIncident() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        j.append(sample(40000))
        j.append(sample(339999))
        assertFalse(summary().finalized)
        j.append(sample(340000))
        assertTrue(summary().finalized)
        j.append(sample(7200000))
        val files = zip(j)
        assertTrue(files.keys.any { it.endsWith("summary.json") })
        assertTrue(files.values.any { it.contains("FIRST_LOCATION_AFTER_STALE") })
        assertTrue(entries("events").any { it.elapsed == 340000L })
    }

    @Test
    fun relapseRetainsEvidenceAndRestartsPostWindow() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        j.append(sample(40000))
        j.append(sample(72000, 32000, 41))
        assertNull(summary().recoveredAt)
        j.append(sample(80000))
        assertEquals(80000L, summary().recoveredElapsed)
        assertEquals(19000L, summary().staleDurationMs)
        assertEquals(1, incidents().size)
    }

    @Test
    fun independentLocationAndStatusSilenceAreVisibleWithoutRecoveryAction() {
        val j = journal()
        j.append(
            sample(1000, age = null)
                .copy(
                    source =
                        SourceState(registered = true, statusRegistered = true, registeredAt = 1000)
                )
        )
        j.append(
            sample(301000, age = null)
                .copy(
                    source =
                        SourceState(
                            registered = true,
                            statusRegistered = true,
                            registeredAt = 1000,
                            lastGnssCallback = 301000,
                        )
                )
        )
        assertEquals(DiagnosticEvent.CALLBACK_SILENCE, summary().trigger)
        assertFalse(summary().before!!.fixAvailable)
        assertEquals(0, summary().before!!.source.registrations)
    }

    @Test
    fun providerPermissionRegistrationAndLoopFailuresFreezeEvidence() {
        val j = journal()
        j.append(sample(1000))
        j.event(DiagnosticEntry(DiagnosticEvent.REGISTRATION_FAILED, utc(2000), 2000, generation))
        j.append(
            sample(3000)
                .copy(
                    providerEnabled = false,
                    power = sample(3000).power.copy(precisePermission = false),
                    loopError = "failure",
                )
        )
        val events = entries("events").map { it.event }
        assertTrue(
            events.containsAll(
                listOf(
                    DiagnosticEvent.REGISTRATION_FAILED,
                    DiagnosticEvent.PROVIDER_DISABLED,
                    DiagnosticEvent.PERMISSION_LOST,
                    DiagnosticEvent.LOOP_FAILURE,
                )
            )
        )
    }

    @Test
    fun multipleIncidentsRotateByCountAndPreserveRecentOnes() {
        val j = journal(DiagnosticJournal.Limits(contextMs = 10, maxIncidents = 2))
        for (i in 0..3) {
            val t = 100000L * i + 1000
            j.append(sample(t))
            j.append(sample(t + 31000, 31000, 2))
            j.append(sample(t + 32000))
            j.append(sample(t + 32010))
        }
        assertEquals(2, incidents().size)
        assertTrue(incidents().all { summary(it).finalized })
    }

    @Test
    fun byteBoundsKeepStartSummaryAndPreContextAndReportTruncation() {
        val limits =
            DiagnosticJournal.Limits(
                chunkBytes = 4000,
                timelineBytes = 8000,
                incidentBytes = 8000,
                totalBytes = 1024 * 1024,
            )
        val j = journal(limits)
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        for (t in 34000L..100000L step 2000) j.append(sample(t, t - 1000, 2))
        assertTrue(summary().truncated)
        assertTrue(File(incidents().single(), "pre.jsonl").exists())
        assertTrue(
            incidents()
                .single()
                .listFiles()!!
                .filter { it.name.startsWith("events-") }
                .sumOf { it.length() } <= limits.incidentBytes
        )
        assertTrue(
            File(temporary.root, "diagnostics")
                .listFiles()!!
                .filter { it.name.startsWith("timeline-") }
                .sumOf { it.length() } <= limits.timelineBytes
        )
    }

    @Test
    fun ageRetentionIsEnforcedOnReopenAndExport() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        incidents().single().setLastModified(1)
        zip(journal())
        assertTrue(incidents().isEmpty())
    }

    @Test
    fun corruptAndTornRecordsAreRemovedButCompleteEvidenceSurvives() {
        val j = journal()
        j.append(sample(1000))
        j.event(DiagnosticEntry(DiagnosticEvent.ACTIVITY_BACKGROUND, utc(2000), 2000, generation))
        val file =
            File(temporary.root, "diagnostics").listFiles()!!.single { it.extension == "jsonl" }
        file.appendText("invalid\n{\"event\":")
        journal().append(sample(3000))
        assertTrue(entries("timeline").any { it.event == DiagnosticEvent.ACTIVITY_BACKGROUND })
        assertFalse(file.readText().contains("invalid"))
    }

    @Test
    fun serviceGenerationAndProcessInterruptionAreHonestAndCountersArePerGeneration() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        val old = incidents().single()
        j.append(
            sample(33000, gen = UUID.randomUUID().toString()).copy(startReason = "sticky_restart")
        )
        assertTrue(summary(old).interrupted)
        assertEquals(2, incidents().size)
        val reopened = journal()
        reopened.append(sample(40000).copy(startReason = "sticky_restart"))
        assertTrue(incidents().any { summary(it).interrupted })
        assertTrue(zip(reopened).values.any { it.contains("sticky_restart") })
    }

    @Test
    fun callbackCountersInExportNeverRegressWithinOneSourceGeneration() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        for (i in 3L..8L) j.event(
            DiagnosticEntry(
                DiagnosticEvent.LOCATION_CALLBACK,
                utc(40000 + i),
                40000 + i,
                generation,
                source = sample(40000).source.copy(locationCallbacks = i),
            )
        )
        val counters =
            entries("events")
                .filter { it.event == DiagnosticEvent.LOCATION_CALLBACK }
                .map { it.source!!.locationCallbacks }
        assertEquals((3L..8L).toList(), counters)
    }

    @Test
    fun exportedTypedSchemaExcludesSensitiveDataEvenFromCorruptInjectedFields() {
        val secret = "http://192.168.4.55/party-secret/latitude"
        val j = journal()
        j.append(
            sample(1000)
                .copy(
                    loopError = secret,
                    source = sample(1000).source.copy(error = secret, lastRejection = secret),
                )
        )
        val file =
            File(temporary.root, "diagnostics").listFiles()!!.first { it.extension == "jsonl" }
        file.appendText(
            "{\"event\":\"ACTIVITY_VISIBLE\",\"at\":\"$secret\",\"elapsed\":2,\"latitude\":28.1,\"device_id\":\"private-device\",\"party\":\"private-party\",\"endpoint\":\"$secret\"}\n"
        )
        File(File(temporary.root, "diagnostics"), "tracking.db").writeText(secret)
        val exported = zip(j).values.joinToString()
        for (forbidden in
            listOf(
                secret,
                "latitude",
                "longitude",
                "altitude",
                "device_id",
                "private-device",
                "private-party",
                "endpoint",
                "ssid",
                "password",
                "party-secret",
            )) assertFalse(forbidden, exported.contains(forbidden))
        assertFalse(zip(j).keys.any { it.endsWith(".db") })
    }

    @Test
    fun blockedDiagnosticWorkerCannotBlockProducerAndOverflowIsReported() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {
            entered.countDown()
            release.await()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val recorder = DiagnosticRecorder(journal(), FakeClock(1000), scope, capacity = 2)
        try {
            // These return while the only IO thread is blocked; no timers or lucky disk speed
            // needed.
            repeat(1000) { recorder.event(DiagnosticEvent.LOCATION_CALLBACK, generation) }
            recorder.sample(sample(1000))
            assertEquals(1L, release.count)
            release.countDown()
            val file =
                withTimeout(10000) { recorder.export(File(temporary.root, "bundle.zip"), build) }
            assertTrue(file.length() > 0)
            assertTrue(entries("timeline").any { it.event == DiagnosticEvent.RECORDS_DROPPED })
        } finally {
            release.countDown()
            recorder.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun ioFailureDoesNotCancelWorkerAndExportFailsExplicitlyRatherThanHanging() = runBlocking {
        val path = File(temporary.root, "not-a-directory").apply { writeText("disk failure") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recorder = DiagnosticRecorder(DiagnosticJournal(path), FakeClock(1000), scope)
        try {
            recorder.sample(sample(1000))
            assertTrue(
                withTimeout(10000) {
                    runCatching { recorder.export(File(temporary.root, "failed.zip"), build) }
                        .isFailure
                }
            )
            assertTrue(recorder.ioFailures > 0)
            assertTrue(scope.isActive)
            path.delete()
            path.mkdirs()
            assertTrue(
                withTimeout(10000) { recorder.export(File(temporary.root, "recovered.zip"), build) }
                    .exists()
            )
        } finally {
            recorder.close()
            scope.cancel()
        }
    }

    @Test
    fun stickyEventThenFirstNewGenerationSampleCreatesOnlyOneIncident() {
        val j = journal()
        j.append(sample(1000))
        val next = UUID.randomUUID().toString()
        j.event(
            DiagnosticEntry(
                DiagnosticEvent.SERVICE_START,
                utc(2000),
                2000,
                next,
                startReason = ServiceStartReason.STICKY_RESTART,
            )
        )
        j.append(sample(2001, gen = next).copy(startReason = "sticky_restart"))
        assertEquals(1, incidents().size)
        assertEquals(next, summary().serviceGeneration)
        assertFalse(summary().interrupted)
    }

    @Test
    fun nativeCallbackCannotRecoverWhileProviderIsOff() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        j.event(
            DiagnosticEntry(
                DiagnosticEvent.LOCATION_CALLBACK,
                utc(33000),
                33000,
                generation,
                source =
                    sample(33000).source.copy(providerEnabled = false, currentFixAvailable = false),
            )
        )
        assertNull(summary().recoveredAt)
    }

    @Test
    fun corruptSummaryCannotBlockExportOfPreservedEvents() {
        val j = journal()
        j.append(sample(1000))
        j.append(sample(32000, 31000, 2))
        File(incidents().single(), "summary.json")
            .writeText("{\"trigger\":\"FIX_BECAME_STALE\",\"before\":{}}")
        val exported = zip(journal())
        assertTrue(exported.values.any { it.contains("FIX_BECAME_STALE") })
        assertFalse(exported.keys.any { it.endsWith("summary.json") })
    }

    @Test
    fun totalByteBudgetRotatesOldIncidentsBeforeAffectingCurrentEvidence() {
        val limits =
            DiagnosticJournal.Limits(
                contextMs = 10,
                chunkBytes = 4000,
                incidentBytes = 8000,
                timelineBytes = 8000,
                totalBytes = 40000,
            )
        val j = journal(limits)
        for (i in 0..4) {
            val t = 100000L * i + 1000
            j.append(sample(t))
            j.append(sample(t + 31000, 31000, 2))
            j.append(sample(t + 32000))
            j.append(sample(t + 32010))
            assertTrue(
                File(temporary.root, "diagnostics")
                    .walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() } <= limits.totalBytes
            )
        }
        assertTrue(incidents().size < 5)
        assertTrue(entries("events").isNotEmpty())
    }
}
