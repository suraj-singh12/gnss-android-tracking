package org.gnss.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CommandControlTest {
    private lateinit var db: TrackingDatabase
    private lateinit var repository: Repository
    private lateinit var key: ControlCredential
    private lateinit var engine: CommandControl
    private val clock = FakeClock(1791288000000)
    private val gson = Gson()
    private var instructions = emptyList<Map<String, Any?>>()
    private var forged = false
    private var syncCount = 0
    private var adaptiveCount = 0
    private var trackingCalls = 0
    private var failAdaptive = false
    private var storedResults = 0
    private var capturedRequest = ""
    private var capturedResponse = ""
    private var replay = false
    private var alertAttempts = 0
    private var alertStops = 0
    private var outputBlocked = false
    private val output =
        object : PhoneAlertOutput {
            override fun attempt(
                row: CommandRecord,
                spec: PhoneAlertSpec,
                remaining: Long,
            ): Pair<String, String> {
                assertTrue(runBlocking { repository.dao.command(row.id)?.attempted == true })
                alertAttempts++
                return if (outputBlocked) "alert_blocked" to "Notification permission denied"
                else
                    "output_attempted" to
                        "Notification/sound/vibration attempted; actual output unknown"
            }

            override fun stop(id: String) {
                alertStops++
            }
        }

    private val transport =
        object : Transport {
            override suspend fun post(endpoint: String, immutableJson: String) = error("unused")

            override suspend fun control(endpoint: String, json: String): Response {
                capturedRequest = json
                val request = gson.fromJson(json, ControlEnvelope::class.java)
                assertEquals(
                    ControlCrypto.mac(key.key, "phone", key.device, key.enrollment, request.data),
                    request.mac,
                )
                val body =
                    Protocol.parse(String(java.util.Base64.getDecoder().decode(request.data)))
                val confirmations = mutableMapOf<String, String>()
                for (result in body["results"].asJsonArray) {
                    storedResults++
                    confirmations[result.asJsonObject["id"].asString] =
                        ControlCrypto.hash(result.toString())
                }
                val raw =
                    gson.toJson(
                        mapOf(
                            "version" to 1,
                            "authority" to key.authority,
                            "nonce" to body["nonce"].asString,
                            "at" to clock.now,
                            "commands" to
                                (if (body["results_only"]?.asBoolean == true)
                                    emptyList<Map<String, Any?>>()
                                else instructions),
                            "confirmed" to confirmations,
                        )
                    )
                val data = java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
                val value =
                    gson.toJson(
                        ControlEnvelope(
                            key.device,
                            key.enrollment,
                            data,
                            if (forged) "invalid"
                            else
                                ControlCrypto.mac(
                                    key.key,
                                    "command",
                                    key.device,
                                    key.enrollment,
                                    data,
                                ),
                        )
                    )
                if (replay) return Response(200, capturedResponse)
                capturedResponse = value
                return Response(200, value)
            }
        }

    @Before
    fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        repository = Repository(db, clock)
        repository.settings("test", "test", "http://192.168.1.2:8080", 10)
        key =
            ControlCredential(
                Protocol.newId(),
                repository.state().deviceId,
                Protocol.newId(),
                "01".repeat(32),
            )
        engine =
            CommandControl(
                repository,
                transport,
                { key },
                adaptive = { enabled, at ->
                    adaptiveCount++
                    if (failAdaptive) error("storage failure")
                    PersistencePolicy(enabled = enabled, effectiveAt = at)
                },
                sync = { syncCount++ },
                tracking = { start ->
                    trackingCalls++
                    if (start) "blocked" to "OS background restriction"
                    else "applied" to "Tracking stopped and verified"
                },
                clock = clock,
                alertOutput = output,
            )
        engine.bind(key) {}
    }

    @After
    fun cleanup() {
        db.close()
    }

    private fun ins(
        op: String = "adaptive",
        order: Long = 1,
        version: Int = 1,
        expires: Long = clock.now + 300000,
        payload: Map<String, Any?> = mapOf("enabled" to true),
        disposition: String = "queued",
    ) =
        mapOf(
            "id" to Protocol.newId(),
            "authority" to key.authority,
            "device" to key.device,
            "enrollment" to key.enrollment,
            "operation" to op,
            "ordering" to order,
            "version" to version,
            "created" to clock.now,
            "expires" to expires,
            "payload" to payload,
            "disposition" to disposition,
        )

    private suspend fun poll() {
        clock.now += 6000
        engine.step()
    }

    private fun alert(
        mode: String = "urgent",
        expires: Long = clock.now + 300000,
        disposition: String = "queued",
    ) =
        ins(
            "alert",
            1,
            expires = expires,
            payload =
                mapOf(
                    "mode" to mode,
                    "message" to "Check in",
                    "duration_s" to if (mode == "normal") 5 else 60,
                    "repeat_s" to if (mode == "normal") 5 else 10,
                ),
            disposition = disposition,
        )

    @Test
    fun alertLocalButtonPersistsOnceAndStopsDuplicateReplay() = runBlocking {
        val command = alert()
        instructions = listOf(command)
        poll()
        val id = command["id"] as String
        assertEquals("output_attempted", repository.dao.command(id)!!.state)
        assertEquals(1, alertAttempts)
        engine.acknowledgeAlert(id)
        engine.acknowledgeAlert(id)
        val ack = repository.dao.command(id)!!
        assertEquals("recipient_acknowledged", ack.state)
        val revision = ack.revision
        assertEquals(1, ack.events.lines().count { it.contains("recipient_acknowledged") })
        repeat(4) { poll() }
        assertEquals(1, alertAttempts)
        assertEquals(revision, repository.dao.command(id)!!.revision)
        assertTrue(repository.dao.command(id)!!.confirmed)
    }

    @Test
    fun urgentRepeatsBoundedActualExpiryAndAuthenticatedCancel() = runBlocking {
        val command = alert()
        instructions = listOf(command)
        poll()
        clock.now += 10001
        engine.tickAlerts()
        assertEquals(2, alertAttempts)
        instructions = listOf(command + ("disposition" to "cancel_requested_unknown"))
        poll()
        assertEquals("cancelled", repository.dao.command(command["id"] as String)!!.state)
        clock.now += 100000
        engine.tickAlerts()
        assertEquals(2, alertAttempts)
        assertTrue(alertStops > 0)
    }

    @Test
    fun normalOneAttemptBlockedLocalAckAndLateExpiry() = runBlocking {
        val normal = alert("normal")
        instructions = listOf(normal)
        poll()
        repeat(3) {
            clock.now += 10000
            engine.tickAlerts()
        }
        assertEquals(1, alertAttempts)
        assertEquals("output_attempted", repository.dao.command(normal["id"] as String)!!.state)
        engine.acknowledgeAlert(normal["id"] as String)
        outputBlocked = true
        val blocked = alert().toMutableMap().apply { this["ordering"] = 2 }
        instructions = listOf(blocked)
        poll()
        assertEquals("alert_blocked", repository.dao.command(blocked["id"] as String)!!.state)
        engine.acknowledgeAlert(blocked["id"] as String)
        assertEquals(
            "recipient_acknowledged",
            repository.dao.command(blocked["id"] as String)!!.state,
        )
        val expired = alert(expires = clock.now)
        instructions = listOf(expired)
        poll()
        assertEquals("expired", repository.dao.command(expired["id"] as String)!!.state)
        assertEquals(2, alertAttempts)
    }

    @Test
    fun processRestartNeverRestartsOutputAndDurationIsAuthoritative() = runBlocking {
        val command = alert()
        instructions = listOf(command)
        poll()
        clock.now += 60001
        engine.tickAlerts()
        assertEquals("expired", repository.dao.command(command["id"] as String)!!.state)
        assertEquals(1, alertAttempts)
        val active = alert().toMutableMap().apply { this["ordering"] = 2 }
        instructions = listOf(active)
        poll()
        engine =
            CommandControl(
                repository,
                transport,
                { key },
                { _, _ -> PersistencePolicy() },
                {},
                { "blocked" to "test" },
                clock = clock,
                alertOutput = output,
            )
        engine.tickAlerts()
        assertEquals("unknown", repository.dao.command(active["id"] as String)!!.state)
        poll()
        assertEquals(2, alertAttempts)
    }

    @Test
    fun alertAuthenticationTargetReplayAndLocalRevocation() = runBlocking {
        val command = alert()
        instructions = listOf(command)
        forged = true
        poll()
        assertEquals(0, alertAttempts)
        forged = false
        instructions = listOf(command + ("device" to Protocol.newId()))
        poll()
        assertEquals(0, alertAttempts)
        instructions = listOf(command)
        poll()
        assertEquals(1, alertAttempts)
        replay = true
        poll()
        assertEquals(1, alertAttempts)
        engine.disable()
        assertEquals("unknown", repository.dao.command(command["id"] as String)!!.state)
        assertTrue(alertStops > 0)
    }

    @Test
    fun sharedAlertPayloadFixtures() {
        val fixture =
            Protocol.parse(
                javaClass.classLoader!!.getResource("control-v1/alerts.json")!!.readText()
            )
        assertEquals("normal", PhoneAlertSpec.parse(fixture["normal"].asJsonObject).mode)
        assertEquals(60, PhoneAlertSpec.parse(fixture["urgent"].asJsonObject).duration_s)
        assertTrue(
            runCatching { PhoneAlertSpec.parse(fixture["invalid_duration"].asJsonObject) }.isFailure
        )
    }

    @Test
    fun roomFiveToSixMigrationPreservesInstallationAndOriginalRows() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "command-migration-${Protocol.newId()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema =
            Protocol.parse(
                    javaClass.classLoader!!
                        .getResource("org.gnss.tracking.TrackingDatabase/5.json")!!
                        .readText(),
                    maximumBytes = 200000,
                )["database"]
                .asJsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (entity in schema["entities"].asJsonArray) {
                val value = entity.asJsonObject
                old.execSQL(
                    value["createSql"]
                        .asString
                        .replace("${'$'}{TABLE_NAME}", value["tableName"].asString)
                )
                for (index in (value["indices"]?.asJsonArray ?: com.google.gson.JsonArray())) old
                    .execSQL(
                        index.asJsonObject["createSql"]
                            .asString
                            .replace("${'$'}{TABLE_NAME}", value["tableName"].asString)
                    )
            }
            old.execSQL(
                "INSERT INTO installation (`key`,deviceId,sequence,partyId,partyName,endpoint,localInterval,version,tracking,endpointGeneration,deliveryPaused) VALUES(1,?,1,'legacy','legacy','http://192.168.1.2',15,0,0,0,0)",
                arrayOf(key.device),
            )
            old.execSQL(
                "INSERT INTO outbox (sequence,messageId,type,capturedMillis,json,quarantined,attempts,nextAttemptMillis,historical) VALUES(1,?,'status',1,'immutable original',0,0,0,0)",
                arrayOf(Protocol.newId()),
            )
            old.version = 5
        }
        val migrated =
            Room.databaseBuilder(context, TrackingDatabase::class.java, name)
                .addMigrations(TrackingDatabase.MIGRATION_5_6)
                .build()
        try {
            val restored = Repository(migrated, clock)
            assertEquals(key.device, restored.state().deviceId)
            assertEquals(15, restored.state().localInterval)
            assertTrue(restored.state().controlRequired)
            assertEquals("immutable original", restored.dao.row(1)!!.json)
            assertTrue(restored.dao.commandHistory().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun remoteStopFlushesResultBeforeTheSendingLifetimeEnds() = runBlocking {
        val stop = ins("tracking_stop", 1, payload = emptyMap())
        instructions = listOf(stop)
        poll()
        val row = repository.dao.command(stop["id"] as String)!!
        assertEquals("applied", row.state)
        assertEquals(1, trackingCalls)
        assertTrue("Stop result must reconcile without another scheduler iteration", row.confirmed)
        assertTrue(row.confirmedAt > 0)
    }

    @Test
    fun sharedAuthenticationVector() {
        val vector =
            Protocol.parse(
                javaClass.classLoader!!
                    .getResource("control-v1/authentication-vector.json")!!
                    .readText()
            )
        assertEquals(
            vector["mac"].asString,
            ControlCrypto.mac(
                vector["test_only_key"].asString,
                vector["direction"].asString,
                vector["device"].asString,
                vector["enrollment"].asString,
                vector["data"].asString,
            ),
        )
    }

    @Test
    fun durableDuplicateOrderingExpiryBlockedAndLostCustody() = runBlocking {
        val command = ins()
        instructions = listOf(command)
        poll()
        assertEquals(1, adaptiveCount)
        val id = command["id"] as String
        assertEquals("applied", repository.dao.command(id)!!.state)
        poll()
        assertEquals(1, adaptiveCount)
        assertTrue(repository.dao.command(id)!!.confirmed)
        assertTrue(repository.dao.command(id)!!.events.contains("Duplicate ignored"))
        instructions = listOf(ins(order = 3))
        poll()
        assertEquals(2, adaptiveCount)
        val stale = ins(order = 2)
        instructions = listOf(stale)
        poll()
        assertEquals("superseded", repository.dao.command(stale["id"] as String)!!.state)
        assertEquals(2, adaptiveCount)
        val expired = ins(order = 4, expires = clock.now)
        instructions = listOf(expired)
        poll()
        assertEquals("expired", repository.dao.command(expired["id"] as String)!!.state)
        val unsupported = ins(order = 5, version = 2)
        instructions = listOf(unsupported)
        poll()
        assertEquals("unsupported", repository.dao.command(unsupported["id"] as String)!!.state)
        val cancelled = ins(order = 6, disposition = "cancel_requested_unknown")
        instructions = listOf(cancelled)
        poll()
        assertEquals("cancelled", repository.dao.command(cancelled["id"] as String)!!.state)
        val blocked = ins("tracking_start", 7, payload = emptyMap())
        instructions = listOf(blocked)
        poll()
        assertEquals("blocked", repository.dao.command(blocked["id"] as String)!!.state)
        failAdaptive = true
        val failed = ins(order = 8)
        instructions = listOf(failed)
        poll()
        assertEquals("failed", repository.dao.command(failed["id"] as String)!!.state)
        assertTrue(repository.dao.commandHistory().all { it.events.contains("Received durably") })
    }

    @Test
    fun authenticatedWrongTargetAndInvalidPayloadAreDenied() = runBlocking {
        val wrong = ins().toMutableMap().apply { this["authority"] = Protocol.newId() }
        instructions = listOf(wrong)
        poll()
        assertEquals(0, adaptiveCount)
        assertTrue(
            repository.dao.commandHistory().any { it.reason == "invalid_authority_or_target" }
        )
        val invalid = ins(order = 2, payload = mapOf("enabled" to "not boolean"))
        instructions = listOf(invalid)
        poll()
        assertEquals("rejected", repository.dao.command(invalid["id"] as String)!!.state)
        assertFalse(repository.dao.command(invalid["id"] as String)!!.attempted)
        assertEquals(0, adaptiveCount)
    }

    @Test
    fun forgedCommandAndResponseReplayHaveZeroEffectsAndSanitizedAudit() = runBlocking {
        instructions = listOf(ins())
        forged = true
        poll()
        assertEquals(0, adaptiveCount)
        assertTrue(repository.dao.commandHistory().all { !it.trusted && it.state == "rejected" })
        forged = false
        poll()
        assertEquals(1, adaptiveCount)
        instructions = listOf(ins(order = 2))
        replay = true
        poll()
        assertEquals(1, adaptiveCount)
        val directory = java.nio.file.Files.createTempDirectory("control-diagnostics").toFile()
        try {
            val output = java.io.ByteArrayOutputStream()
            DiagnosticJournal(directory)
                .export(
                    output,
                    DiagnosticBuild("test", 1, 28, "source"),
                    commands = repository.dao.commandHistory(),
                )
            val contents =
                java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                    buildString {
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            append(entry.name)
                            append(String(zip.readBytes()))
                        }
                    }
                }
            assertTrue(contents.contains("command-instructions.json"))
            assertTrue(contents.contains("rejected"))
            assertFalse(contents.contains(key.key))
            assertFalse(contents.contains(capturedRequest))
            assertFalse(contents.contains("gnss1:"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun firstContactCannotCaptureAuthorityOrReportingSettings() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = Room.inMemoryDatabaseBuilder(context, TrackingDatabase::class.java).build()
        try {
            val phone = Repository(fresh, clock)
            assertTrue(phone.state().controlRequired)
            val row = phone.snapshot(clock.now, null, Health())
            phone.accept(
                row,
                Receipt(
                    phone.state().deviceId,
                    row.messageId,
                    row.sequence,
                    utc(clock.now),
                    RemoteConfig(Protocol.newId(), 1, 5),
                    null,
                ),
                0,
            )
            assertNull(phone.state().authority)
            assertEquals(0L, phone.state().version)
            assertEquals(10, phone.state().config().effective_reporting_interval_s)
            assertNotNull(phone.dao.row(row.sequence)!!.deliveredAt)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun secureEnrollmentCannotDowngradeViaLegacyAckOrLocalDisable() = runBlocking {
        val row = repository.snapshot(clock.now, null, Health())
        val msg = Protocol.decodeMessage(row.json)
        val ack =
            """{"protocol_version":1,"device_id":"${key.device}","message_id":"${row.messageId}","sequence":${row.sequence},"result":"stored","received_at":"${utc(clock.now)}","config":{"authority_id":"${key.authority}","version":999,"reporting_interval_override_s":5}}"""
        repository.accept(row, Protocol.receipt(ack, msg), repository.state().endpointGeneration)
        assertEquals(10, repository.state().config().effective_reporting_interval_s)
        assertEquals(0L, repository.state().version)
        repository.edit { it.copy(controlAuthority = null, controlEnrollment = null) }
        assertTrue(repository.state().controlRequired)
    }

    @Test
    fun receivedWithoutAttemptResumesOnceEvenAfterReceiptCustody() = runBlocking {
        val command = ins("sync", 1, payload = emptyMap(), disposition = "received")
        val id = command["id"] as String
        repository.dao.insertCommand(
            CommandRecord(
                id,
                key.authority,
                key.device,
                key.enrollment,
                "sync",
                1,
                clock.now,
                confirmed = true,
            )
        )
        instructions = listOf(command)
        poll()
        assertEquals(1, syncCount)
        assertTrue(repository.dao.command(id)!!.attempted)
        assertEquals("sync_started", repository.dao.command(id)!!.state)
        assertFalse(repository.dao.command(id)!!.confirmed)
        poll()
        assertEquals(1, syncCount)
    }

    @Test
    fun interruptedAttemptRemainsUnknownAndDoesNotRepeatEffect() = runBlocking {
        val command = ins("sync", 1, payload = emptyMap())
        val id = command["id"] as String
        repository.dao.insertCommand(
            CommandRecord(
                id,
                key.authority,
                key.device,
                key.enrollment,
                "sync",
                1,
                clock.now,
                attempted = true,
            )
        )
        instructions = listOf(command)
        poll()
        assertEquals(0, syncCount)
        assertEquals("unknown", repository.dao.command(id)!!.state)
    }
}
