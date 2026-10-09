package org.gnss.tracking

import android.app.Application
import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ObservationPersistenceTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<Application>()

    private val clock = FakeClock(100000)

    private fun observation(t: Long) =
        Observation(Fix(utc(t), 0, 28.0, 77.0, 3.0, null, null, null, null), t)

    @Test
    fun everyCallbackPersistsDespiteSlowerLiveCadenceAndReopen() = runBlocking {
        val name = "callbacks.db"
        var db = Room.databaseBuilder(app, TrackingDatabase::class.java, name).build()
        try {
            var repo = Repository(db, clock)
            repo.settings("Alpha", "Field", "http://192.168.1.2", 30)
            val writer = ObservationPersistence(repo::saveObservation, clock = clock)
            repeat(20) { i ->
                clock.now = 100000 + i * 1000L
                assertTrue(writer.submit(observation(clock.now), Health(), null))
            }
            writer.finish()
            assertEquals(20L, writer.state.saved)
            val rows = db.dao().all()
            assertEquals(20, rows.size)
            assertTrue(rows.all { it.historical })
            assertEquals(20, rows.map { it.messageId }.distinct().size)
            assertTrue(
                repo.historical(clock.now, 0).isNotEmpty()
            ) // History needs no unrelated ACK.
            val current = rows.last()
            assertEquals(current.sequence, repo.live(clock.now)!!.sequence)
            assertEquals(20, db.dao().all().size) // Live reuses its durable identity.
            repo.accept(
                current,
                Receipt(
                    repo.state().deviceId,
                    current.messageId,
                    current.sequence,
                    utc(clock.now),
                    RemoteConfig(Protocol.newId(), 0, null),
                    null,
                ),
                repo.state().endpointGeneration,
            )
            db.close()
            db = Room.databaseBuilder(app, TrackingDatabase::class.java, name).build()
            repo = Repository(db, clock)
            assertEquals(rows.map { it.json }, db.dao().all().take(20).map { it.json })
            assertEquals(30, repo.state().localInterval)
            assertEquals(20L, repo.state().sequence)
            assertEquals(rows.first().messageId, repo.historical(clock.now, 0).first().messageId)
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test
    fun slowDiskDoesNotBlockSubmitAndFailuresRetryOriginalIdentity() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val ids = java.util.concurrent.CopyOnWriteArrayList<String>()
        var first = true
        val writer =
            ObservationPersistence(
                save = { v ->
                    ids.add(v.id)
                    if (first) {
                        first = false
                        failed.complete(Unit)
                        throw IllegalStateException("disk unavailable")
                    }
                    release.await()
                    Outbound(1, v.id, "location", v.capturedMillis, "saved")
                },
                clock = clock,
            )
        assertTrue(writer.submit(observation(clock.now), Health(), null))
        failed.await()
        // Native path never waits for Room or a diagnostic writer.
        assertTrue(writer.submit(observation(clock.now), Health(), null))
        assertEquals(0L, writer.state.saved)
        assertTrue(writer.state.writeFailures > 0)
        release.complete(Unit)
        withTimeout(10000) { writer.finish() }
        assertEquals(ids[0], ids[1])
        assertEquals(2L, writer.state.saved)
        assertEquals(0L, writer.state.awaitingCommit)
    }

    @Test
    fun boundedHandoffOverflowIsExplicitAndDoesNotInventDurableSuccess() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var errors = 0
        val writer =
            ObservationPersistence(
                save = { v ->
                    entered.complete(Unit)
                    blocked.await()
                    Outbound(1, v.id, "location", v.capturedMillis, "saved")
                },
                onFailure = { errors++ },
                clock = clock,
                capacity = 1,
            )
        assertTrue(writer.submit(observation(clock.now), Health(), null))
        entered.await()
        assertTrue(writer.submit(observation(clock.now), Health(), null))
        assertFalse(writer.submit(observation(clock.now), Health(), null))
        assertEquals(1, errors)
        assertEquals(1L, writer.state.overflow)
        assertEquals(0L, writer.state.saved)
        blocked.complete(Unit)
        writer.finish()
        assertEquals(
            0L,
            writer.state.awaitingCommit,
        ) // Lost/overflow callback cannot be called durable.
    }

    @Test
    fun observationCommitRetryIsIdempotentAndRetainsEnvelope() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, TrackingDatabase::class.java).build()
        try {
            val repo = Repository(db, clock)
            val value =
                CapturedObservation(
                    Protocol.newId(),
                    clock.now,
                    observation(clock.now).fix,
                    Health(gnss_status = "fix"),
                    null,
                )
            val first = repo.saveObservation(value)
            repo.settings("Changed", "Name", "http://192.168.1.2", 20)
            assertEquals(first, repo.saveObservation(value))
            assertEquals(1, db.dao().all().size)
            assertEquals(1L, repo.state().sequence)
        } finally {
            db.close()
        }
    }

    @Test
    fun versionOneUpgradePreservesIdentityConfigAndPendingPayload() = runBlocking {
        val name = "upgrade.db"
        val path = app.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema =
            Gson()
                .fromJson(
                    listOf(
                            File("schemas/org.gnss.tracking.TrackingDatabase/1.json"),
                            File("app/schemas/org.gnss.tracking.TrackingDatabase/1.json"),
                        )
                        .first { it.exists() }
                        .readText(),
                    com.google.gson.JsonObject::class.java,
                )["database"]
                .asJsonObject
        val state =
            Installation(
                sequence = 1,
                localInterval = 30,
                authority = Protocol.newId(),
                version = 1,
                overrideSeconds = 5,
            )
        val message =
            Message(
                type = "status",
                device_id = state.deviceId,
                party = Party("A", "B"),
                message_id = Protocol.newId(),
                sequence = 1,
                captured_at = utc(clock.now),
                config_state = state.config(),
                health = Health(),
                fix = null,
            )
        val row = Outbound(1, message.message_id, "status", clock.now, Protocol.encode(message))
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (entity in schema["entities"].asJsonArray) {
                val e = entity.asJsonObject
                val table = e["tableName"].asString
                old.execSQL(e["createSql"].asString.replace("\${TABLE_NAME}", table))
                for (index in (e["indices"]?.asJsonArray ?: com.google.gson.JsonArray())) old
                    .execSQL(
                        index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", table)
                    )
                val data =
                    Gson().toJsonTree(if (table == "installation") state else row).asJsonObject
                val values = ContentValues()
                for (field in e["fields"].asJsonArray) {
                    val key = field.asJsonObject["columnName"].asString
                    val v = data[key]
                    when {
                        v == null || v.isJsonNull -> values.putNull(key)
                        v.asJsonPrimitive.isBoolean -> values.put(key, if (v.asBoolean) 1 else 0)
                        v.asJsonPrimitive.isNumber -> values.put(key, v.asLong)
                        else -> values.put(key, v.asString)
                    }
                }
                old.insertOrThrow(table, null, values)
            }
            for (query in schema["setupQueries"].asJsonArray) old.execSQL(query.asString)
            old.version = 1
        }
        val db =
            Room.databaseBuilder(app, TrackingDatabase::class.java, name)
                .addMigrations(TrackingDatabase.MIGRATION_1_2)
                .build()
        try {
            val repo = Repository(db, clock)
            assertEquals(state.deviceId, repo.state().deviceId)
            assertEquals(5, repo.state().config().effective_reporting_interval_s)
            assertEquals(row.json, db.dao().row(1)!!.json)
            assertFalse(db.dao().row(1)!!.historical)
            assertEquals(
                2L,
                repo
                    .saveObservation(
                        CapturedObservation(
                            Protocol.newId(),
                            clock.now,
                            observation(clock.now).fix,
                            Health(gnss_status = "fix"),
                            null,
                        )
                    )
                    .sequence,
            )
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test
    fun intermediateObservationsAreEligibleWithoutAnotherCurrentACK() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, TrackingDatabase::class.java).build()
        try {
            val repo = Repository(db, clock)
            val current =
                repo.snapshot(clock.now, observation(clock.now).fix, Health(gnss_status = "fix"))
            repo.accept(
                current,
                Receipt(
                    repo.state().deviceId,
                    current.messageId,
                    current.sequence,
                    utc(clock.now),
                    RemoteConfig(Protocol.newId(), 0, null),
                    null,
                ),
                0,
            )
            clock.now += 1000
            val intermediate =
                repo.saveObservation(
                    CapturedObservation(
                        Protocol.newId(),
                        clock.now,
                        observation(clock.now).fix,
                        Health(gnss_status = "fix"),
                        null,
                    )
                )
            assertEquals(intermediate.messageId, repo.historical(clock.now, 0).first().messageId)
            clock.now += 29000
            val next = repo.snapshot(clock.now, null, Health())
            assertEquals(intermediate.messageId, repo.historical(clock.now, 0).first().messageId)
            repo.accept(
                next,
                Receipt(
                    repo.state().deviceId,
                    next.messageId,
                    next.sequence,
                    utc(clock.now),
                    RemoteConfig(repo.state().authority!!, 0, null),
                    null,
                ),
                0,
            )
            assertEquals(intermediate.messageId, repo.historical(clock.now, 0).first().messageId)
            assertEquals(intermediate.messageId, repo.live(clock.now)!!.messageId)
        } finally {
            db.close()
        }
    }
}
