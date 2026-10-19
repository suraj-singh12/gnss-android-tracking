package org.gnss.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run with test-tools/integration/run.sh. Only the Wi-Fi Network adapter is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CommandIntegrationTest {
    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val base = Instant.parse("2026-10-06T12:00:00.000Z").toEpochMilli()
    private val clock = FakeClock(base)
    private lateinit var bridge: Bridge
    private val phones = mutableListOf<Phone>()
    private lateinit var context: Context
    private val http =
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Run test-tools/integration/run.sh for real Command integration",
            !System.getenv("GNSS_COMMAND_BRIDGE").isNullOrEmpty(),
        )
        context = ApplicationProvider.getApplicationContext()
        bridge = Bridge(temporary.newFile("command.sqlite"))
        bridge.start()
    }

    @After
    fun cleanup() {
        phones.forEach { it.close() }
        if (::bridge.isInitialized) bridge.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun request(url: String, body: String? = null): Response {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use {
            return Response(
                it.code,
                it.body!!.string(),
                it.header("Retry-After"),
                it.header("Content-Type")?.substringBefore(';') == "application/json",
            )
        }
    }

    private fun control(path: String, json: String, expected: Int = 200) {
        val response = request(bridge.local + "/local/" + path, json)
        assertEquals(response.body, expected, response.code)
    }

    private fun recording(action: String, confirmed: Boolean = false, expected: Int = 200) =
        control("recording", """{"action":"$action","confirmed":$confirmed}""", expected)

    private fun override(phone: Phone, seconds: Int?) =
        control(
            "override",
            """{"device_id":"${phone.id}","reporting_interval_override_s":$seconds}""",
        )

    private fun at(seconds: Long) {
        clock.now = base + seconds * 1000
        bridge.rpc("clock", clock.now)
    }

    private fun atMillis(value: Long) {
        clock.now = value
        bridge.rpc("clock", value)
    }

    private fun inspect() = bridge.rpc("inspect")

    private fun state() = inspect()["state"].asJsonObject

    private fun raw() = inspect()["raw"].asJsonArray

    private fun device(phone: Phone) = state()["devices"].asJsonObject[phone.id].asJsonObject

    private fun points(): List<JsonObject> = state()["points"].asJsonArray.map { it.asJsonObject }

    private fun distance(phone: Phone) = device(phone)["total_m"].asDouble

    private fun sequences() = points().map { it["sequence"].asLong }

    private fun fix(
        time: Long,
        x: Double,
        y: Double = 0.0,
        accuracy: Double? = 1.0,
        age: Long = 0,
        altitude: Double? = null,
    ) =
        Fix(
            utc(time - age),
            age,
            Math.toDegrees(y / 6371008.8),
            Math.toDegrees(x / 6371008.8),
            accuracy,
            altitude,
            null,
            null,
            null,
        )

    private suspend fun phone(): Phone =
        Phone("phone-${phones.size}.db").also {
            phones.add(it)
            it.open()
            it.repository.settings(
                "Alpha ${phones.size}",
                "Field team ${phones.size}",
                bridge.phone,
                10,
            )
            it.id = it.repository.state().deviceId
        }

    private inner class Phone(private val name: String) {
        lateinit var db: TrackingDatabase
        lateinit var repository: Repository
        lateinit var sender: Sender
        lateinit var id: String
        val sent = mutableListOf<String>()
        val responses = mutableListOf<Response>()
        val sosEvidence = mutableListOf<DiagnosticEntry>()

        fun recordSos(kind: DiagnosticEvent, e: SosEvidence) {
            sosEvidence.add(DiagnosticEntry(kind, utc(clock.now), clock.now, null, sos = e))
        }

        var latest: Fix? = null
        var captures = 0
        var fault: ((Response) -> Response)? = null
        var adapterFailure: Response? = null
        var adapterOffline: Boolean = false
        val transport = Transport { endpoint, json ->
            // Prove save-before-send with the real durable row, on every attempt.
            val m = Protocol.decodeMessage(json)
            assertEquals(json, db.dao().row(m.sequence)!!.json)
            sent.add(json)
            if (adapterOffline) throw java.io.IOException("Injected Wi-Fi interruption")
            val response = adapterFailure ?: request(Endpoint.messages(endpoint).toString(), json)
            responses.add(response)
            fault?.invoke(response) ?: response
        }

        fun open() {
            db =
                Room.databaseBuilder(context, TrackingDatabase::class.java, name)
                    .addCallback(TrackingDatabase.SUMMARY_CALLBACK)
                    .build()
            repository = Repository(db, clock)
            sender =
                Sender(repository, transport, clock, ::recordSos) {
                    captures++
                    repository.snapshot(
                        clock.now,
                        latest,
                        Health(gnss_status = if (latest == null) "no_fix" else "fix"),
                    )
                }
        }

        fun reopen() {
            db.close()
            open()
        }

        fun close() {
            if (::db.isInitialized) db.close()
            context.deleteDatabase(name)
        }

        suspend fun snapshot(
            x: Double? = null,
            y: Double = 0.0,
            accuracy: Double? = 1.0,
            captured: Long = clock.now,
            age: Long = 0,
            altitude: Double? = null,
        ): Outbound {
            latest = x?.let { fix(captured, it, y, accuracy, age, altitude) }
            return repository.snapshot(
                captured,
                latest,
                Health(
                    battery_percent = 85,
                    wifi_connected = true,
                    gnss_status = if (latest == null) "no_fix" else "fix",
                ),
            )
        }

        suspend fun deliver(row: Outbound) {
            val response = transport.post(repository.state().endpoint, row.json)
            assertEquals(response.body, 200, response.code)
            repository.accept(
                row,
                Protocol.receipt(response.body, Protocol.decodeMessage(row.json)),
                repository.state().endpointGeneration,
            )
        }

        suspend fun delivered(row: Outbound) {
            assertNotNull(db.dao().row(row.sequence)!!.deliveredAt)
            assertEquals(row.json, db.dao().row(row.sequence)!!.json)
        }
    }

    private inner class Bridge(private val database: File) : AutoCloseable {
        private lateinit var process: Process
        private lateinit var reader: java.io.BufferedReader
        private lateinit var writer: java.io.BufferedWriter
        private val reads = Executors.newSingleThreadExecutor()
        lateinit var phone: String
        lateinit var local: String
        private val log = File(database.parentFile, "bridge.log")

        fun start() {
            process =
                ProcessBuilder(
                        System.getenv("GNSS_COMMAND_BRIDGE"),
                        "-test.run=^TestAndroidBridge$",
                        "-test.timeout=600s",
                    )
                    .redirectError(ProcessBuilder.Redirect.appendTo(log))
                    .apply {
                        environment()["GNSS_BRIDGE_DB"] = database.absolutePath
                        // Go 1.24 sees host CPU count in some quota-limited containers.
                        environment()["GOMAXPROCS"] = "2"
                    }
                    .start()
            reader = process.inputStream.bufferedReader()
            writer = process.outputStream.bufferedWriter()
            val ready = read()
            phone = ready["phone"].asString
            local = ready["local"].asString
            rpc("clock", clock.now)
        }

        private fun read(): JsonObject {
            val line = reads.submit<String> { reader.readLine() }.get(180, TimeUnit.SECONDS)
            check(line != null) { "Command bridge exited: ${log.readText()}" }
            // Test-only SQLite/state inspection can exceed a phone protocol response limit.
            return com.google.gson.JsonParser.parseString(line).asJsonObject
        }

        fun rpc(action: String, now: Long = 0): JsonObject {
            writer.write("""{"action":"$action","now":$now}""")
            writer.newLine()
            writer.flush()
            return read()
        }

        fun stop() {
            if (!::process.isInitialized) return
            if (process.isAlive) {
                writer.write("""{"action":"close"}""")
                writer.newLine()
                writer.flush()
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Command bridge failed: ${log.readText()}"
            }
            reader.close()
            writer.close()
        }

        fun restart() {
            stop()
            start()
        }

        override fun close() {
            try {
                stop()
            } finally {
                reads.shutdownNow()
            }
        }
    }

    @Test
    fun normalLocationStatusAndFirstAuthorityEnrollment() = runBlocking {
        val p = phone()
        assertEquals(ConfigState(), p.repository.state().config())
        at(1)
        val row = p.snapshot(0.0)
        p.sender.step()
        p.delivered(row)
        assertEquals(1, p.sent.size)
        assertEquals(0, p.captures)
        assertEquals(1, p.db.dao().all().size)
        assertEquals(1, raw().size())
        assertEquals(Protocol.parse(row.json), raw().first().asJsonObject["message"])
        val ack = Protocol.parse(p.responses.last().body)
        assertEquals(p.id, ack["device_id"].asString)
        assertEquals(row.messageId, ack["message_id"].asString)
        assertEquals(row.sequence, ack["sequence"].asLong)
        assertEquals("stored", ack["result"].asString)
        val authority = state()["authority_id"].asString
        assertEquals(authority, p.repository.state().authority)
        p.reopen()
        assertEquals(authority, p.repository.state().authority)
        assertEquals(p.id, p.repository.state().deviceId)
        at(11)
        val status = p.snapshot()
        assertEquals(authority, Protocol.decodeMessage(status.json).config_state.authority_id)
        p.sender.step()
        p.delivered(status)
        val d = device(p)
        assertEquals("Alpha 1", d["snapshot"].asJsonObject["party"].asJsonObject["id"].asString)
        assertEquals(
            "Field team 1",
            d["snapshot"].asJsonObject["party"].asJsonObject["name"].asString,
        )
        assertEquals(
            utc(base + 1000),
            d["location"].asJsonObject["fix"].asJsonObject["observed_at"].asString,
        )
        assertEquals(utc(base + 11000), d["snapshot"].asJsonObject["captured_at"].asString)
        assertEquals("no_fix", d["gnss_condition"].asString)
        assertTrue(d["config_converged"].asBoolean)
        assertEquals(2, raw().size())
    }

    @Test
    fun remoteOverrideLocalEditClearAndImmutableQueuedConfig() = runBlocking {
        val p = phone()
        at(1)
        p.snapshot()
        p.sender.step()
        at(2)
        val queued = p.snapshot()
        override(p, 30)
        assertFalse(device(p)["config_converged"].asBoolean)
        p.sender.step()
        p.delivered(queued)
        assertEquals(30, p.repository.state().config().effective_reporting_interval_s)
        assertEquals(
            10,
            Protocol.decodeMessage(queued.json).config_state.effective_reporting_interval_s,
        )
        at(3)
        val echo = p.snapshot()
        p.sender.step()
        val c = Protocol.decodeMessage(echo.json).config_state
        assertEquals(1L, c.version)
        assertEquals(30, c.reporting_interval_override_s)
        assertEquals(30, c.effective_reporting_interval_s)
        assertTrue(device(p)["config_converged"].asBoolean)
        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 20)
        at(4)
        val edited = p.snapshot()
        p.sender.step()
        assertEquals(
            20,
            Protocol.decodeMessage(edited.json).config_state.local_reporting_interval_s,
        )
        assertEquals(30, p.repository.state().config().effective_reporting_interval_s)
        override(p, null)
        at(5)
        p.snapshot()
        p.sender.step()
        assertEquals(20, p.repository.state().config().effective_reporting_interval_s)
        at(6)
        val clear = p.snapshot()
        p.sender.step()
        assertEquals(2L, Protocol.decodeMessage(clear.json).config_state.version)
        assertNull(Protocol.decodeMessage(clear.json).config_state.reporting_interval_override_s)
        assertTrue(device(p)["config_converged"].asBoolean)
        assertEquals(queued.json, p.db.dao().row(queued.sequence)!!.json)
    }

    @Test
    fun fiveSecondLocalAndOverrideConvergeAndClearToFifteen() = runBlocking {
        val p = phone()
        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 5)
        at(1)
        val first = p.snapshot()
        p.sender.step()
        assertEquals(
            5,
            Protocol.decodeMessage(first.json).config_state.effective_reporting_interval_s,
        )
        for (invalid in listOf(1, 6, 86405)) {
            assertTrue(
                runCatching {
                        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, invalid)
                    }
                    .isFailure
            )
            val response =
                request(
                    bridge.local + "/local/override",
                    """{"device_id":"${p.id}","reporting_interval_override_s":$invalid}""",
                )
            assertEquals(400, response.code)
        }
        override(p, 5)
        at(6)
        p.snapshot()
        p.sender.step()
        assertEquals(5, p.repository.state().overrideSeconds)
        assertEquals(5, p.repository.state().config().effective_reporting_interval_s)
        p.reopen()
        assertEquals(5, p.repository.state().overrideSeconds)
        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 15)
        at(11)
        val echo = p.snapshot()
        p.sender.step()
        val config = Protocol.decodeMessage(echo.json).config_state
        assertEquals(15, config.local_reporting_interval_s)
        assertEquals(5, config.effective_reporting_interval_s)
        assertTrue(device(p)["config_converged"].asBoolean)
        override(p, null)
        at(16)
        p.snapshot()
        p.sender.step()
        assertEquals(15, p.repository.state().config().effective_reporting_interval_s)
        at(31)
        val clear = p.snapshot()
        p.sender.step()
        assertEquals(
            15,
            Protocol.decodeMessage(clear.json).config_state.effective_reporting_interval_s,
        )
        assertTrue(device(p)["config_converged"].asBoolean)
    }

    @Test
    fun lostResponseRetriesExactlyOnceWithOriginalReceiptAndDistance() = runBlocking {
        recording("start")
        val p = phone()
        at(1)
        p.snapshot(0.0)
        p.sender.step()
        at(11)
        val row = p.snapshot(10.0)
        bridge.rpc("drop")
        p.sender.step()
        assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
        assertEquals(2, raw().size())
        val first = raw().last().asJsonObject["received_at"].asString
        val geometry = points()
        assertEquals(10.0, distance(p), 1e-5)
        atMillis(p.db.dao().row(row.sequence)!!.nextAttemptMillis)
        p.sender.step()
        p.delivered(row)
        assertEquals(listOf(row.json, row.json), p.sent.takeLast(2))
        val ack = Protocol.parse(p.responses.last().body)
        assertEquals("duplicate", ack["result"].asString)
        assertEquals(first, ack["received_at"].asString)
        assertEquals(2, raw().size())
        assertEquals(geometry, points())
        assertEquals(10.0, distance(p), 1e-5)
        assertEquals(2L, p.repository.state().sequence)
    }

    @Test
    fun currentFirstReconnectReconcilesHistoricalBacklog() = runBlocking {
        recording("start")
        val p = phone()
        val rows =
            (1L..4L).map {
                at(it * 10)
                p.snapshot((it - 1) * 10.0)
            }
        // A disconnect fails D before Command sees it; restore before retry deadline.
        p.adapterOffline = true
        p.sender.step()
        p.adapterOffline = false
        p.sender.connectivityRestored()
        p.sender.step()
        assertEquals(
            rows.last().messageId,
            device(p)["location"].asJsonObject["message_id"].asString,
        )
        repeat(3) {
            p.sender.step()
            assertEquals(
                rows.last().messageId,
                device(p)["location"].asJsonObject["message_id"].asString,
            )
        }
        assertEquals(
            listOf(4L, 1L, 2L, 3L),
            p.sent.drop(1).map { Protocol.decodeMessage(it).sequence },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), sequences())
        assertEquals(30.0, distance(p), 1e-5)
        assertEquals(4, raw().size())
        assertEquals(0, p.captures)
        rows.forEach { p.delivered(it) }
    }

    @Test
    fun staleReconnectCapturesCurrentAndSosKeepsReservedPriority() = runBlocking {
        recording("start")
        val p = phone()
        at(1)
        val old = p.snapshot(0.0)
        at(50)
        p.latest = fix(clock.now, 50.0)
        p.sender.connectivityRestored()
        p.sender.step()
        p.sender.step()
        assertEquals(listOf(2L, 1L), p.sent.map { Protocol.decodeMessage(it).sequence })
        assertEquals(1, p.captures)
        p.delivered(old)
        val latest = LatestLocation(clock)
        val sos =
            SosEngine(p.repository, latest, { Health() }, clock).activate(SosTrigger.SCREEN).row
        at(51)
        val current = p.snapshot(51.0)
        p.sender.connectivityRestored()
        p.sender.step()
        p.sender.step()
        assertEquals(listOf(sos.json, current.json), p.sent.takeLast(2))
        p.delivered(sos)
        assertFalse(points().any { it["message_id"].asString == sos.messageId })
    }

    @Test
    fun sosEngineOfflineBacklogLostAckOperatorAckAndBothRestarts() = runBlocking {
        recording("start")
        val p = phone()
        at(1)
        val reports = (1..100).map { p.snapshot(it.toDouble(), captured = base - it * 1000L) }
        val latest = LatestLocation(clock)
        latest.enabled = true
        latest.update(Observation(fix(clock.now, 20.0), clock.now))
        at(50) // measurement is stale, but still an actual last-known observation
        val engine =
            SosEngine(p.repository, latest, { Health(wifi_connected = false) }, clock, p::recordSos)
        val sos = engine.activate(SosTrigger.SCREEN).row
        assertFalse(engine.activate(SosTrigger.VOLUME_UP).created)
        val original = Protocol.decodeMessage(sos.json)
        assertEquals(49000L, original.fix!!.fix_age_ms)
        assertEquals("unknown", original.health.gnss_status)
        p.adapterFailure = Response(503, "offline")
        p.sender.step()
        assertNull(p.db.dao().row(sos.sequence)!!.deliveredAt)
        assertEquals(0, raw().size())
        p.reopen()
        assertEquals(sos.json, p.db.dao().row(sos.sequence)!!.json)
        p.adapterFailure = null
        at(100)
        p.sender.connectivityRestored()
        bridge.rpc("drop")
        p.sender.step() // real Command commits SOS, physical-adapter response is lost
        assertNull(p.db.dao().row(sos.sequence)!!.deliveredAt)
        assertEquals(sos.json, p.sent.last())
        assertEquals(1, raw().size())
        var alerts = state()["sos_alerts"].asJsonArray
        assertEquals(1, alerts.size())
        val firstReceipt = alerts[0].asJsonObject["received_at"].asString
        assertEquals(original.sos!!.triggered_at, alerts[0].asJsonObject["triggered_at"].asString)
        assertEquals("last_known", alerts[0].asJsonObject["location_freshness"].asString)
        assertTrue(points().isEmpty())
        // Remote interval does not change event identity or pending SOS delivery.
        override(p, 5)
        // The offline failure was attempt 1; lost ACK was attempt 2. Persisted
        // SOS backoff is now 2 seconds plus bounded jitter, including after Room/Sender recreation.
        atMillis(p.db.dao().row(sos.sequence)!!.nextAttemptMillis)
        p.sender.step()
        p.delivered(sos)
        assertEquals("duplicate", Protocol.parse(p.responses.last().body)["result"].asString)
        assertEquals(firstReceipt, p.db.dao().row(sos.sequence)!!.deliveredAt)
        assertEquals(5, p.repository.state().config().effective_reporting_interval_s)
        recording("stop")
        recording("resume")
        recording("clear", true)
        assertEquals(1, state()["sos_alerts"].asJsonArray.size())
        control("sos/acknowledge", """{"device_id":"${p.id}","event_id":"${sos.messageId}"}""")
        val acknowledged =
            state()["sos_alerts"].asJsonArray[0].asJsonObject["operator_acknowledged_at"].asString
        at(110)
        control("sos/acknowledge", """{"device_id":"${p.id}","event_id":"${sos.messageId}"}""")
        assertEquals(
            acknowledged,
            state()["sos_alerts"].asJsonArray[0].asJsonObject["operator_acknowledged_at"].asString,
        )
        val second =
            SosEngine(p.repository, latest, { Health() }, clock).activate(SosTrigger.VOLUME_UP).row
        p.sender.step()
        p.delivered(second)
        assertEquals(2, state()["sos_alerts"].asJsonArray.size())
        bridge.restart()
        p.reopen()
        alerts = state()["sos_alerts"].asJsonArray
        assertEquals(2, alerts.size())
        assertEquals(acknowledged, alerts[0].asJsonObject["operator_acknowledged_at"].asString)
        assertTrue(alerts[1].asJsonObject["operator_acknowledged_at"].isJsonNull)
        assertEquals(firstReceipt, alerts[0].asJsonObject["received_at"].asString)
        assertEquals(sos.json, p.db.dao().row(sos.sequence)!!.json)
        val field = bridge.rpc("field_report")["report"].asJsonObject
        val ref = sosReference(sos.messageId)
        assertTrue(
            field["events"].asJsonArray.any { it.asJsonObject["sos_event_ref"]?.asString == ref }
        )
        for (scenario in
            listOf("SOS retries deduplicated", "Operator ACK persisted", "SOS survived restart")) {
            assertTrue(
                field["assessments"].asJsonArray.any {
                    val v = it.asJsonObject
                    v["scenario"].asString == scenario &&
                        v["result"].asString == "PASS" &&
                        v["evidence"]?.asJsonObject?.get("event_ref")?.asString == ref
                }
            )
        }
        @Suppress("UNCHECKED_CAST")
        val androidVerdicts =
            sosReport(p.sosEvidence, 0, 0)["assessments"] as List<Map<String, Any>>
        for (scenario in
            listOf("SOS saved locally", "SOS preempted backlog", "SOS delivered after reconnect")) {
            assertTrue(
                androidVerdicts.any {
                    it["event_ref"] == ref && it["scenario"] == scenario && it["result"] == "PASS"
                }
            )
        }
        // Browser reload gets the same authoritative state from the real local handler.
        val view = Protocol.parse(request(bridge.local + "/local/state").body)
        assertEquals(alerts, view["sos_alerts"])
        assertNull(state()["recording"].takeUnless { it.isJsonNull })
        p.sender.connectivityRestored()
        p.latest = fix(clock.now, 30.0)
        p.sender.step() // current report after the two emergencies
        assertEquals("location", Protocol.decodeMessage(p.sent.last()).type)
        assertTrue(reports.all { p.db.dao().row(it.sequence)!!.json == it.json })
    }

    @Test
    fun interruptedHttp408RetainsSavedSOSAndRetriesTheOriginalIdentity() = runBlocking {
        val p = phone()
        at(1)
        val row =
            SosEngine(p.repository, LatestLocation(clock), { Health() }, clock)
                .activate(SosTrigger.SCREEN)
                .row
        // Actual Command stores the SOS; an intermediary returns a retryable 408.
        p.fault = { it.copy(code = 408) }
        p.sender.step()
        val pending = p.db.dao().row(row.sequence)!!
        assertNull(pending.deliveredAt)
        assertFalse(pending.quarantined)
        assertEquals(row.json, pending.json)
        assertEquals(0L, p.sender.syncProgress.ackedRecords)
        assertEquals(1, raw().size())
        atMillis(pending.nextAttemptMillis)
        p.fault = null
        p.sender.step()
        p.delivered(row)
        assertEquals(listOf(row.json, row.json), p.sent)
        assertEquals("duplicate", Protocol.parse(p.responses.last().body)["result"].asString)
        assertEquals(1, raw().size())
    }

    @Test
    fun sosWrongAckDoesNotClaimReceiptAndDuplicateCannotResetHumanAck() = runBlocking {
        val p = phone()
        at(1)
        val engine = SosEngine(p.repository, LatestLocation(clock), { Health() }, clock)
        val sos = engine.activate(SosTrigger.SCREEN).row
        p.fault = { response ->
            val body = Protocol.parse(response.body)
            body.addProperty("message_id", Protocol.newId())
            response.copy(body = body.toString())
        }
        p.sender.step()
        assertNull(p.db.dao().row(sos.sequence)!!.deliveredAt)
        control("sos/acknowledge", """{"device_id":"${p.id}","event_id":"${sos.messageId}"}""")
        val acknowledged =
            state()["sos_alerts"].asJsonArray[0].asJsonObject["operator_acknowledged_at"].asString
        atMillis(p.db.dao().row(sos.sequence)!!.nextAttemptMillis)
        p.fault = null
        p.sender.step()
        p.delivered(sos)
        assertEquals(1, raw().size())
        assertEquals(
            acknowledged,
            state()["sos_alerts"].asJsonArray[0].asJsonObject["operator_acknowledged_at"].asString,
        )
        assertEquals("duplicate", Protocol.parse(p.responses.last().body)["result"].asString)
    }

    @Test
    fun arrivalPermutationsHaveIdenticalFinalTruth() = runBlocking {
        recording("start")
        val p = phone()
        val rows =
            listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0).mapIndexed { i, xy ->
                at(i * 10L + 1)
                p.snapshot(xy.first, xy.second)
            }
        at(200) // Backlog delay must not invalidate historically fresh captures.
        val db = temporary.newFile("empty-recording.sqlite")
        // Preserve identical window IDs by starting each permutation from the
        // same empty recording database. Stop process before copying SQLite.
        bridge.stop()
        File(temporary.root, "command.sqlite").copyTo(db, overwrite = true)
        var expected: List<JsonObject>? = null
        for (order in
            listOf(
                listOf(0, 1, 2, 3),
                listOf(3, 0, 1, 2),
                listOf(2, 0, 3, 1),
                listOf(3, 0, 1, 1, 2),
            )) {
            db.copyTo(File(temporary.root, "command.sqlite"), overwrite = true)
            bridge.start()
            p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 10)
            for (i in order) p.deliver(rows[i])
            val actual = points()
            assertEquals(rows.map { it.messageId }, actual.map { it["message_id"].asString })
            assertEquals(listOf(1L, 2L, 3L, 4L), sequences())
            assertEquals(30.0, distance(p), 1e-5)
            assertEquals(4, raw().size())
            assertEquals("stale", device(p)["gnss_condition"].asString)
            if (expected == null) expected = actual else assertEquals(expected, actual)
            bridge.stop()
        }
        bridge.start()
    }

    @Test
    fun badGnssRemainsRawAndRealGeometrySurvivesQualityGaps() = runBlocking {
        recording("start")
        val p = phone()
        val xy =
            listOf(
                0.0 to 0.0,
                1.0 to 0.0,
                10.0 to 0.0,
                15.0 to 0.0,
                16.0 to 0.0,
                17.0 to 0.0,
                10000.0 to 0.0,
                20.0 to 0.0,
                30.0 to 0.0,
                40.0 to 0.0,
                40.0 to 10.0,
                50.0 to 10.0,
                40.0 to 10.0,
            )
        val rows =
            xy.mapIndexed { i, v ->
                at(i * 50L + 50)
                val row =
                    p.snapshot(
                        v.first,
                        v.second,
                        accuracy =
                            when (i) {
                                3 -> 80.0
                                4 -> null
                                else -> 3.0
                            },
                        age = if (i == 5) 40000 else 0,
                        altitude = i * 1000.0,
                    )
                p.sender.step()
                p.delivered(row)
                row
            }
        assertEquals(rows.size, raw().size())
        val decisions =
            state()["decisions"].asJsonArray.associate {
                it.asJsonObject["message_id"].asString to it.asJsonObject["reason"].asString
            }
        for ((i, reason) in
            mapOf(
                1 to "below_movement_threshold",
                3 to "poor_accuracy",
                4 to "unknown_accuracy",
                5 to "stale_at_capture",
                6 to "implausible_speed",
            )) assertEquals(reason, decisions[rows[i].messageId])
        assertEquals(listOf(1L, 3L, 8L, 9L, 10L, 11L, 12L, 13L), sequences())
        val accepted = points()
        assertNotEquals(accepted[1]["segment_id"], accepted[2]["segment_id"])
        assertEquals(accepted[1]["cumulative_m"], accepted[2]["cumulative_m"])
        assertEquals(60.0, distance(p), 1e-5) // Vertical telemetry adds no distance.
    }

    @Test
    fun recordingStopResumeClearAndLateBacklogCannotResurrect() = runBlocking {
        recording("start")
        val p = phone()
        at(1)
        p.snapshot(0.0)
        p.sender.step()
        override(p, 30)
        at(11)
        p.snapshot(10.0)
        p.sender.step()
        at(20)
        recording("stop")
        val stopped = points()
        at(30)
        val live = p.snapshot(1000.0)
        p.sender.step()
        assertEquals(live.messageId, device(p)["location"].asJsonObject["message_id"].asString)
        assertEquals(stopped, points())
        assertEquals(10.0, distance(p), 1e-5)
        at(40)
        recording("resume")
        at(41)
        p.snapshot(2000.0)
        p.sender.step()
        at(51)
        p.snapshot(2010.0)
        p.sender.step()
        assertEquals(20.0, distance(p), 1e-5)
        assertEquals(2, points().map { it["segment_id"].asString }.toSet().size)
        assertEquals(points()[1]["cumulative_m"], points()[2]["cumulative_m"])
        recording("clear", expected = 400)
        assertEquals(4, points().size)
        recording("clear", confirmed = true)
        assertTrue(state()["recording"].isJsonNull)
        assertTrue(points().isEmpty())
        assertEquals(0.0, distance(p), 0.0)
        assertEquals(5, raw().size())
        assertEquals(
            30,
            device(p)["desired_config"].asJsonObject["reporting_interval_override_s"].asInt,
        )
        val backlog = p.snapshot(5.0, captured = base + 5000)
        p.deliver(backlog)
        assertTrue(points().isEmpty())
        assertEquals(6, raw().size())
        bridge.restart()
        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 10)
        p.deliver(backlog)
        assertTrue(points().isEmpty())
        assertTrue(state()["recording"].isJsonNull)
        at(60)
        recording("start")
        p.deliver(backlog)
        assertTrue(points().isEmpty())
        at(61)
        p.snapshot(3000.0)
        p.sender.step()
        assertEquals(1, points().size)
        assertEquals(0.0, distance(p), 0.0)
    }

    @Test
    fun fiveIndependentDevicesAndLateJoinDuringRecording() = runBlocking {
        recording("start")
        for (i in 1..5) {
            val p = phone()
            at(i * 30L)
            p.snapshot(0.0, i * 100.0)
            p.sender.step()
            override(p, i * 10 + 20)
            at(i * 30L + 10)
            p.snapshot(i * 10.0, i * 100.0)
            p.sender.step()
            at(i * 30L + 11)
            p.snapshot()
            p.sender.step()
            assertEquals(3L, p.repository.state().sequence)
            assertEquals(i * 10 + 20, p.repository.state().overrideSeconds)
            assertTrue(device(p)["config_converged"].asBoolean)
            assertEquals(i * 10.0, distance(p), 1e-5)
        }
        assertEquals(5, state()["devices"].asJsonObject.size())
        assertEquals(5, phones.map { it.id }.toSet().size)
        assertEquals(15, raw().size())
        assertEquals(10, points().size)
        assertEquals(5, points().map { it["segment_id"].asString }.toSet().size)
    }

    @Test
    fun commandProcessRestartPreservesDedupeConfigRecordingAndDistance() = runBlocking {
        recording("start")
        val p = phone()
        at(1)
        p.snapshot(0.0)
        p.sender.step()
        override(p, 30)
        at(11)
        val pending = p.snapshot(10.0)
        bridge.rpc("drop")
        p.sender.step()
        val before = state()
        val receipt = raw().last().asJsonObject["received_at"].asString
        bridge.restart()
        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 10)
        p.sender.connectivityRestored()
        p.sender.step()
        p.delivered(pending)
        assertEquals(pending.json, p.sent.last())
        val ack = Protocol.parse(p.responses.last().body)
        assertEquals("duplicate", ack["result"].asString)
        assertEquals(receipt, ack["received_at"].asString)
        assertEquals(before["authority_id"], state()["authority_id"])
        assertEquals(before["recording"], state()["recording"])
        assertEquals(before["points"], state()["points"])
        assertEquals(1L, p.repository.state().version)
        assertEquals(30, p.repository.state().overrideSeconds)
        assertEquals(2, raw().size())
        assertEquals(10.0, distance(p), 1e-5)
        at(21)
        p.snapshot(20.0)
        p.sender.step()
        assertEquals(20.0, distance(p), 1e-5)
        assertEquals(3, raw().size())
    }

    @Test
    fun androidRoomRestorationRetainsIdentitySequenceConfigAndExactRetry() = runBlocking {
        val p = phone()
        at(1)
        p.snapshot()
        p.sender.step()
        override(p, 30)
        at(2)
        p.snapshot()
        p.sender.step()
        p.repository.settings("Alpha 1", "Field team 1", bridge.phone, 20)
        at(3)
        val pending = p.snapshot(10.0)
        bridge.rpc("drop")
        p.sender.step()
        val before = p.repository.state()
        val durable = p.db.dao().row(pending.sequence)
        p.reopen()
        assertEquals(before, p.repository.state())
        assertEquals(durable, p.db.dao().row(pending.sequence))
        assertEquals(p.id, p.repository.state().deviceId)
        assertEquals(20, p.repository.state().localInterval)
        assertEquals(30, p.repository.state().overrideSeconds)
        atMillis(durable!!.nextAttemptMillis)
        p.sender.step()
        p.delivered(pending)
        assertEquals(listOf(pending.json, pending.json), p.sent.takeLast(2))
        assertEquals("duplicate", Protocol.parse(p.responses.last().body)["result"].asString)
        at(5)
        val next = p.snapshot()
        p.sender.step()
        assertEquals(pending.sequence + 1, next.sequence)
        assertEquals(4, raw().size())
    }

    @Test
    fun adaptiveAgainstDenseReferencesUsesRealRoomSenderAndCommandQualification(): Unit =
        runBlocking {
            recording("start")
            val reports = mutableListOf<Map<String, Any>>()
            for ((caseName, trace) in AdaptiveTraces.cases()) {
                at(0)
                val dense = phone()
                val adaptive = phone()
                val denseSession =
                    kotlinx.coroutines.CompletableDeferred(dense.repository.beginTracking())
                val adaptiveSession =
                    kotlinx.coroutines.CompletableDeferred(adaptive.repository.beginTracking())
                val policy = PersistencePolicy(enabled = true, changes = 1, effectiveAt = utc(base))
                val sampler = AdaptivePersistence()
                val original = trace.map { AdaptiveTraces.value(it, base) }
                val retained = mutableListOf<CapturedObservation>()
                trace.zip(original).forEach { (sample, value) ->
                    retained.addAll(sampler.select(value, policy))
                    if (sample.boundary) retained.addAll(sampler.boundary("lifecycle_or_sos"))
                }
                retained.addAll(sampler.boundary("stop"))
                assertTrue(original.containsAll(retained))
                assertEquals(original.first(), retained.first())
                assertEquals(original.last(), retained.last())
                val meaningful =
                    trace.indices.filter { i ->
                        i > 0 && (trace[i].bearing != trace[i - 1].bearing || trace[i].boundary)
                    }
                val retainedIds = retained.map { it.id }.toSet()
                val missed =
                    meaningful.count {
                        original[it].id !in retainedIds || original[it - 1].id !in retainedIds
                    }
                // A gradual curve is evaluated geometrically; a change under 10 degrees
                // is not counted as a discrete meaningful turn.
                val discreteMissed =
                    meaningful
                        .filter { i ->
                            trace[i].boundary ||
                                (trace[i].bearing != null &&
                                    trace[i - 1].bearing != null &&
                                    kotlin.math.abs(
                                        (trace[i].bearing!! - trace[i - 1].bearing!! + 540) % 360 -
                                            180
                                    ) >= 10)
                        }
                        .count {
                            original[it].id !in retainedIds ||
                                (!trace[it].boundary && original[it - 1].id !in retainedIds)
                        }
                assertEquals("$caseName missed turn/boundary", 0, discreteMissed)
                for ((p, values, session) in
                    listOf(
                        Triple(dense, original, denseSession),
                        Triple(adaptive, retained, adaptiveSession),
                    )) {
                    val replayClock =
                        object : Clock {
                            override fun wallMillis() = clock.wallMillis()

                            override fun elapsedMillis() = 100000 + clock.now - base
                        }
                    val writer =
                        ObservationPersistence(
                            p.repository::saveObservation,
                            clock = replayClock,
                            policy = { policy.copy(enabled = p === adaptive) },
                        )
                    for ((sample, v) in trace.zip(original)) {
                        clock.now = v.capturedMillis
                        assertTrue(
                            writer.submit(
                                Observation(v.fix, v.elapsedNanos / 1000000, v.elapsedNanos),
                                v.health,
                                v.generation,
                                session,
                            )
                        )
                        if (sample.boundary) writer.retainBoundary("lifecycle_or_sos")
                        while (writer.state.awaitingCommit >= 128) kotlinx.coroutines.delay(1)
                    }
                    writer.finish()
                    assertEquals(
                        values.map { it.fix },
                        p.db.dao().all().map { Protocol.decodeMessage(it.json).fix },
                    )
                    assertEquals(values.size.toLong(), writer.state.committedObservations)
                    p.repository.endTracking()
                    p.reopen() // File-backed Room/process object restoration before transfer.
                    at(trace.last().second.toLong() + 1)
                    // Actual negotiated Sender uses the accepted #22 transport path.
                    // Other bridge tests retain explicit older-peer fallback coverage.
                    val adapter =
                        object : Transport {
                            override suspend fun post(endpoint: String, immutableJson: String) =
                                p.transport.post(endpoint, immutableJson)

                            override suspend fun capabilities(endpoint: String) =
                                request(endpoint + "/api/v1/capabilities")

                            override suspend fun history(endpoint: String, json: String): Response {
                                for (entry in Protocol.parse(json).getAsJsonArray("messages")) {
                                    val message = Protocol.decodeMessage(entry.toString())
                                    assertEquals(
                                        message,
                                        Protocol.decodeMessage(
                                            p.db.dao().row(message.sequence)!!.json
                                        ),
                                    )
                                }
                                return request(endpoint + "/api/v1/history", json)
                            }
                        }
                    p.sender =
                        Sender(p.repository, adapter, clock) { error("No synthetic current fix") }
                    repeat(values.size + 5) { p.sender.step(retainedOnly = true) }
                    assertTrue("$caseName pending", p.db.dao().all().all { it.deliveredAt != null })
                    p.deliver(
                        p.db.dao().all().first()
                    ) // Duplicate remains exactly one raw identity.
                }
                fun qualified(p: Phone) = points().filter { it["device_id"].asString == p.id }
                // Independent metric: these fixtures lie near the equator. Project
                // Command's qualified coordinates to that local metre plane and use
                // JDK line geometry, not the selector's distance/deviation functions.
                fun referencePoint(point: JsonObject): Pair<Double, Double> {
                    val fix = point["fix"].asJsonObject
                    return Math.toRadians(fix["longitude"].asDouble) * 6371008.8 to
                        Math.toRadians(fix["latitude"].asDouble) * 6371008.8
                }
                // Android's compile classpath omits AWT; load the independent host-JDK
                // geometry method reflectively in this JVM-only integration harness.
                val segmentDistance =
                    Class.forName("java.awt.geom.Line2D")
                        .getMethod("ptSegDist", *Array<Class<*>>(6) { java.lang.Double.TYPE })
                val densePoints = qualified(dense).map(::referencePoint)
                val sparse = qualified(adaptive)
                val sparsePoints = sparse.map(::referencePoint)
                val edges =
                    sparse.indices.drop(1).filter {
                        sparse[it - 1]["segment_id"] == sparse[it]["segment_id"]
                    }
                val maxDeviation =
                    if (densePoints.isEmpty()) 0.0
                    else
                        densePoints.maxOf { point ->
                            val endpoint =
                                sparsePoints.minOfOrNull {
                                    kotlin.math.hypot(
                                        point.first - it.first,
                                        point.second - it.second,
                                    )
                                } ?: Double.POSITIVE_INFINITY
                            minOf(
                                endpoint,
                                edges.minOfOrNull { i ->
                                    val a = sparsePoints[i - 1]
                                    val b = sparsePoints[i]
                                    segmentDistance.invoke(
                                        null,
                                        a.first,
                                        a.second,
                                        b.first,
                                        b.second,
                                        point.first,
                                        point.second,
                                    ) as Double
                                } ?: endpoint,
                            )
                        }
                val denseDistance = distance(dense)
                val adaptiveDistance = distance(adaptive)
                val distanceError = kotlin.math.abs(denseDistance - adaptiveDistance)
                val intervals =
                    retained.zipWithNext { a, b -> (b.elapsedNanos - a.elapsedNanos) / 1000000 }
                val denseBytes = dense.db.dao().all().sumOf { it.json.toByteArray().size.toLong() }
                val adaptiveBytes =
                    adaptive.db.dao().all().sumOf { it.json.toByteArray().size.toLong() }
                reports.add(
                    linkedMapOf(
                        "case" to caseName,
                        "original" to original.size,
                        "retained" to retained.size,
                        "max_interval_ms" to intervals.max(),
                        "min_interval_ms" to intervals.min(),
                        "max_qualified_route_deviation_m" to maxDeviation,
                        "missed_meaningful_turns" to discreteMissed,
                        "unretained_small_heading_changes" to missed,
                        "dense_qualified_m" to denseDistance,
                        "adaptive_qualified_m" to adaptiveDistance,
                        "distance_error_m" to distanceError,
                        "dense_payload_bytes" to denseBytes,
                        "adaptive_payload_bytes" to adaptiveBytes,
                    )
                )
                println("ADAPTIVE_CASE " + com.google.gson.Gson().toJson(reports.last()))
                System.getenv("GNSS_ADAPTIVE_REPORT")?.let {
                    File(it)
                        .writeText(
                            com.google.gson
                                .GsonBuilder()
                                .setPrettyPrinting()
                                .create()
                                .toJson(reports)
                        )
                }
                assertTrue("$caseName max route deviation $maxDeviation", maxDeviation <= 2.1)
                assertTrue(
                    "$caseName distance error $distanceError / $denseDistance",
                    distanceError <= maxOf(2.1, denseDistance * 0.01),
                )
                // Cold rebuild uses the unchanged Command oracle, including all eight filters.
                val reference = bridge.rpc("reference")
                assertEquals(reference["decisions"], state()["decisions"])
            }
            val json = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(reports)
            println("ADAPTIVE_ACCURACY_REPORT " + json)
            System.getenv("GNSS_ADAPTIVE_REPORT")?.let {
                File(it).apply {
                    parentFile?.mkdirs()
                    writeText(json)
                }
            }
        }

    @Test
    fun activeAdaptiveLiveCommitDoesNotChaseCallbacksAndSosUsesLatest() = runBlocking {
        at(0)
        recording("start")
        val p = phone()
        val session = kotlinx.coroutines.CompletableDeferred(p.repository.beginTracking())
        val policy = PersistencePolicy(enabled = true, changes = 1, effectiveAt = utc(base))
        val writer =
            ObservationPersistence(
                p.repository::saveObservation,
                clock = clock,
                policy = { policy },
            )
        val latest = LatestLocation(clock).apply { enabled = true }
        suspend fun committed() =
            kotlinx.coroutines.withTimeout(10000) {
                while (writer.state.awaitingCommit > 0) kotlinx.coroutines.delay(1)
            }
        fun feed(second: Long) {
            at(second)
            val observation =
                Observation(
                    fix(clock.now, second * 3.0).copy(speed_mps = 3.0, bearing_deg = 90.0),
                    clock.now,
                )
            assertTrue(writer.submit(observation, Health(gnss_status = "fix"), "active", session))
            latest.update(observation)
        }
        for (n in 0L..13L) feed(n)
        committed()
        p.sender =
            Sender(p.repository, p.transport, clock, prepareLive = writer::prepareLive) {
                error("No invented LIVE fix")
            }
        p.sender.step() // Promote actual callback 13; no backlog HTTP before commit.
        assertFalse(p.sender.attemptedLastStep)
        assertTrue(p.sent.isEmpty())
        committed()
        feed(14) // A newer unsaved callback must not move the outstanding commit goal.
        p.sender.step()
        assertEquals(utc(base + 13000), Protocol.decodeMessage(p.sent.single()).fix!!.observed_at)
        assertTrue(writer.samplingState.boundaryCandidate)
        val sos =
            SosEngine(
                p.repository,
                latest,
                { Health(gnss_status = "fix") },
                clock,
                beforeSave = { writer.retainBoundary("sos_boundary") },
            )
        val event = sos.activate(SosTrigger.SCREEN)
        p.sender.step()
        assertEquals("sos", Protocol.decodeMessage(p.sent.last()).type)
        assertEquals(utc(base + 14000), Protocol.decodeMessage(p.sent.last()).fix!!.observed_at)
        p.delivered(event.row)
        writer.finish()
        p.repository.endTracking()
        while (p.db.dao().hasPendingHistory()) p.sender.step(retainedOnly = true)
        assertEquals(5L, writer.state.committedObservations)
        assertEquals(6, raw().size()) // Five retained originals and one independent SOS.
        assertTrue(p.db.dao().all().all { it.deliveredAt != null })
    }

    @Test
    fun invalidAcksStorageFailureConflictsAndRetryAfterAreSafe() = runBlocking {
        val p = phone()
        at(1)
        val row = p.snapshot()
        bridge.rpc("fail_storage")
        p.sender.step()
        assertEquals(503, p.responses.last().code)
        assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
        assertEquals(0, raw().size())
        bridge.rpc("restore_storage")
        atMillis(p.db.dao().row(row.sequence)!!.nextAttemptMillis)
        p.fault = { response ->
            val ack = Protocol.parse(response.body)
            ack.addProperty("sequence", 999)
            response.copy(body = ack.toString())
        }
        p.sender.step()
        assertEquals(1, raw().size())
        assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
        p.fault = null
        atMillis(p.db.dao().row(row.sequence)!!.nextAttemptMillis)
        p.sender.step()
        p.delivered(row)
        assertEquals("duplicate", Protocol.parse(p.responses.last().body)["result"].asString)
        val conflict = Protocol.decodeMessage(row.json).copy(party = Party("Changed", "Changed"))
        val response =
            request(Endpoint.messages(bridge.phone).toString(), Protocol.encode(conflict))
        assertEquals(409, response.code)
        assertEquals(1, raw().size())
        at(5)
        val busy = p.snapshot()
        p.adapterFailure =
            Response(429, "", "60") // Network adapter fault only; Command emits no 429 itself.
        p.sender.step()
        p.adapterFailure = null
        at(64)
        p.sender.step()
        assertNull(p.db.dao().row(busy.sequence)!!.deliveredAt)
        assertEquals(4, p.sent.size)
        atMillis(p.db.dao().row(busy.sequence)!!.nextAttemptMillis)
        p.sender.step()
        // Busy packet has become backlog; real Sender saves current first.
        assertEquals(1, p.captures)
        p.sender.step()
        p.delivered(busy)
        for (path in
            listOf("/local/state", "/local/override", "/local/recording", "/")) assertEquals(
            404,
            request(bridge.phone + path, if (path == "/local/state") null else "{}").code,
        )
    }

    @Test
    fun invalidReceiptFieldsNeverDeliverOrEnroll() = runBlocking {
        val p = phone()
        at(1)
        val row = p.snapshot()
        val mutations =
            listOf<(JsonObject) -> Unit>(
                { it.addProperty("protocol_version", 2) },
                { it.addProperty("device_id", Protocol.newId()) },
                { it.addProperty("message_id", Protocol.newId()) },
                { it.addProperty("sequence", 1.0) },
                { it.addProperty("result", "ok") },
                { it.addProperty("received_at", "2026-10-06T12:00:00Z") },
                { it.remove("config") },
            )
        for (mutate in mutations) {
            p.fault = { response ->
                val ack = Protocol.parse(response.body)
                mutate(ack)
                response.copy(body = ack.toString())
            }
            p.sender.connectivityRestored()
            atMillis(maxOf(clock.now, p.db.dao().row(row.sequence)!!.nextAttemptMillis))
            p.sender.step(retainedOnly = true)
            assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
            assertNull(p.repository.state().authority)
            assertEquals(1, p.db.dao().all().size)
            assertEquals(1, raw().size())
        }
        p.fault = { it.copy(json = false) }
        p.sender.connectivityRestored()
        atMillis(maxOf(clock.now, p.db.dao().row(row.sequence)!!.nextAttemptMillis))
        p.sender.step(retainedOnly = true)
        assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
        p.fault = null
        p.sender.connectivityRestored()
        atMillis(maxOf(clock.now, p.db.dao().row(row.sequence)!!.nextAttemptMillis))
        p.sender.step(retainedOnly = true)
        p.delivered(row)
        assertEquals("duplicate", Protocol.parse(p.responses.last().body)["result"].asString)
        assertEquals(1, p.sent.toSet().size)
    }

    @Test
    fun maximumWireSequenceIsEchoedExactlyAndCannotWrap() = runBlocking {
        val p = phone()
        p.db.dao().update(p.repository.state().copy(sequence = MAX_WIRE_INTEGER - 1))
        at(1)
        val row = p.snapshot()
        p.sender.step()
        p.delivered(row)
        assertEquals(MAX_WIRE_INTEGER, row.sequence)
        assertEquals(MAX_WIRE_INTEGER, Protocol.parse(p.responses.last().body)["sequence"].asLong)
        assertTrue(runCatching { p.snapshot() }.isFailure)
        assertEquals(MAX_WIRE_INTEGER, p.repository.state().sequence)
        assertEquals(1, p.db.dao().all().size)
        assertEquals(Protocol.parse(row.json), raw().first().asJsonObject["message"])
    }

    @Test
    fun elapsedFixAgeAndClockAnomaliesRemainHonest() = runBlocking {
        recording("start")
        val p = phone()
        val latest = LatestLocation(clock)
        latest.enabled = true
        at(1)
        latest.update(Observation(fix(clock.now, 0.0), clock.now))
        at(11)
        val fresh = latest.currentFix()!!
        assertEquals(10000L, fresh.fix_age_ms)
        val first = p.repository.snapshot(clock.now, fresh, Health(gnss_status = "fix"))
        p.sender.step()
        p.delivered(first)
        at(100)
        assertNull(latest.currentFix())
        val mismatch = p.snapshot(10.0)
        val m = Protocol.decodeMessage(mismatch.json)
        // New simulated observation with UTC/elapsed disagreement, using production encode.
        val anomalous =
            p.repository.snapshot(
                clock.now,
                m.fix!!.copy(observed_at = utc(clock.now - 20000), fix_age_ms = 0),
                Health(gnss_status = "fix"),
            )
        p.sender.step() // Oldest history retains its fair opportunity.
        p.sender.step()
        p.delivered(anomalous)
        p.sender.step()
        at(110)
        val future = p.snapshot(20.0, captured = clock.now + 10000)
        p.deliver(future)
        val decisions =
            state()["decisions"].asJsonArray.associate {
                it.asJsonObject["message_id"].asString to it.asJsonObject["reason"].asString
            }
        assertEquals("clock_anomaly", decisions[anomalous.messageId])
        assertEquals("future_capture", decisions[future.messageId])
        // The future report remains raw/explainable but cannot replace the real current fix.
        assertEquals("valid", device(p)["gnss_condition"].asString)
        assertTrue(device(p)["current_position"].asBoolean)
        assertEquals(
            utc(base + 100000),
            device(p)["location"].asJsonObject["fix"].asJsonObject["observed_at"].asString,
        )
        assertEquals(4, raw().size())
        assertFalse(
            points().any {
                it["message_id"].asString in listOf(anomalous.messageId, future.messageId)
            }
        )
        assertEquals(
            utc(base + 1000),
            raw()
                .first()
                .asJsonObject["message"]
                .asJsonObject["fix"]
                .asJsonObject["observed_at"]
                .asString,
        )
        assertEquals(utc(base + 11000), raw().first().asJsonObject["received_at"].asString)
    }

    @Test
    fun statusFloodCannotStarveOfflineRectangleHistoryAndRestart() = runBlocking {
        val p = phone()
        recording("start")
        // Hours of existing routine backlog precede this walk.
        repeat(100) { p.snapshot(captured = base - (100 - it) * 5000L) }
        val route = listOf(0.0 to 0.0, 20.0 to 0.0, 20.0 to 20.0, 0.0 to 20.0, 0.0 to 0.0)
        val saved =
            route.mapIndexed { i, xy ->
                at(i * 5L)
                p.snapshot(xy.first, xy.second)
            }
        var lost = true
        p.fault = { response ->
            if (lost) {
                lost = false
                throw java.io.IOException("lost stored ACK")
            }
            response
        }
        p.sender.connectivityRestored()
        p.sender.step()
        assertEquals(saved.last().json, p.sent.first())
        assertTrue(device(p)["current_position"].asBoolean)
        assertEquals(
            saved.last().messageId,
            device(p)["location"].asJsonObject["message_id"].asString,
        )
        assertEquals(1, points().size)
        p.reopen() // Retry survives phone repository and Command process restart.
        bridge.restart()
        p.repository.settings("Alpha", "Field team", bridge.phone, 10)
        at(21)
        p.sender.connectivityRestored()
        p.sender.step()
        assertEquals(
            "duplicate",
            com.google.gson.JsonParser.parseString(p.responses.last().body)
                .asJsonObject["result"]
                .asString,
        )
        repeat(4) { p.sender.step() }
        assertEquals(
            listOf(saved.last().sequence, saved.last().sequence) +
                saved.dropLast(1).map { it.sequence },
            p.sent.map { Protocol.decodeMessage(it).sequence },
        )
        assertEquals(5, raw().size())
        assertEquals(saved.map { it.messageId }, points().map { it["message_id"].asString })
        assertEquals(80.0, distance(p), 0.00001)
        // Status envelopes are retained, delivered afterward and cannot change geometry.
        repeat(100) { p.sender.step() }
        assertEquals(105, raw().size())
        assertEquals(80.0, distance(p), 0.00001)
        saved.forEach { p.delivered(it) }
        assertTrue(p.db.dao().all().all { it.deliveredAt != null })
    }

    @Test
    fun continuousCurrentReportsStillGrantHistoryDelivery() = runBlocking {
        val p = phone()
        recording("start")
        val old =
            (0..4).map { i ->
                at(i * 5L)
                p.snapshot(i * 10.0)
            }
        p.sender.step()
        repeat(4) { i ->
            at(25L + i * 5)
            p.snapshot(50.0 + i * 10)
            p.sender.step()
            p.sender.step()
        }
        old.forEach { p.delivered(it) }
        assertEquals(old.last().sequence, Protocol.decodeMessage(p.sent.first()).sequence)
        assertEquals(old.first().sequence, Protocol.decodeMessage(p.sent[1]).sequence)
        assertEquals(9, raw().size())
    }

    @Test
    fun nativeResolutionHistoryReconstructsIntermediateTurnsBetweenThirtySecondReports() =
        runBlocking {
            val p = phone()
            p.repository.settings("Alpha", "Field", bridge.phone, 30)
            recording("start")
            val route =
                (0..20).map { i ->
                    when {
                        i <= 5 -> i * 5.0 to 0.0
                        i <= 10 -> 25.0 to (i - 5) * 5.0
                        i <= 15 -> (15 - i) * 5.0 to 25.0
                        else -> 0.0 to (20 - i) * 5.0
                    }
                }
            val history =
                route.mapIndexed { i, xy ->
                    at(i.toLong())
                    val f = fix(clock.now, xy.first, xy.second)
                    p.repository.saveObservation(
                        CapturedObservation(
                            Protocol.newId(),
                            clock.now,
                            f,
                            Health(gnss_status = "fix"),
                            null,
                        )
                    )
                }
            at(30)
            // Current delivery reuses the last original observation, with no extra snapshot.
            val live = history.last()
            p.reopen()
            p.sender.step()
            assertEquals(live.json, p.sent.first())
            assertEquals(0, p.captures)
            repeat(history.size - 1) { p.sender.step() }
            assertEquals(history.dropLast(1).map { it.json }, p.sent.drop(1))
            assertEquals(21, raw().size())
            assertEquals(21, points().size)
            assertEquals(100.0, distance(p), 0.0001)
            // Live and history share one immutable identity; raw/track counts match exactly.
            assertEquals(21, device(p)["field_evidence"].asJsonObject["raw_fixes"].asInt)
            val policy = state()["policy"].asJsonObject.deepCopy()
            policy.addProperty("maximum_accuracy_m", 50)
            control("quality", policy.toString())
            assertEquals(21, points().size)
            assertEquals(100.0, distance(p), 0.0001)
            assertEquals(21, raw().size())
            assertTrue(p.db.dao().all().all { it.deliveredAt != null })
        }

    @Test
    fun shortReconnectUsesNewerNativeObservationBeforeRecentPendingLiveSnapshot() = runBlocking {
        val p = phone()
        p.repository.settings("Alpha", "Field", bridge.phone, 30)
        at(0)
        val old = p.snapshot(0.0)
        // Command commits it but Android has not accepted its response.
        request(Endpoint.messages(bridge.phone).toString(), old.json)
        at(15)
        p.latest = fix(clock.now, 15.0)
        val intermediate =
            p.repository.saveObservation(
                CapturedObservation(
                    Protocol.newId(),
                    clock.now,
                    p.latest!!,
                    Health(gnss_status = "fix"),
                    null,
                )
            )
        p.sender.connectivityRestored()
        p.sender.step()
        val first = Protocol.decodeMessage(p.sent.first())
        assertEquals(p.latest!!.observed_at, first.fix!!.observed_at)
        assertNotEquals(old.messageId, first.message_id)
        assertEquals(0, p.captures)
        p.sender.step()
        p.sender.step()
        p.delivered(old)
        p.delivered(intermediate)
        assertEquals(old.json, p.sent[1]) // Original uncertain commit is still retried immutably.
        assertEquals(
            "duplicate",
            com.google.gson.JsonParser.parseString(p.responses[1].body)
                .asJsonObject["result"]
                .asString,
        )
        assertEquals(2, raw().size())
    }

    @Test
    fun nativeBatchACKLossPreservesEveryIdentityAndIntermediateTurn() = runBlocking {
        val p = phone()
        p.repository.settings("Alpha", "Field", bridge.phone, 30)
        recording("start")
        val session = p.repository.beginTracking()
        val rows =
            (0..20).map { n ->
                at(n.toLong())
                val xy =
                    when {
                        n <= 5 -> n * 5.0 to 0.0
                        n <= 10 -> 25.0 to (n - 5) * 5.0
                        n <= 15 -> (15 - n) * 5.0 to 25.0
                        else -> 0.0 to (20 - n) * 5.0
                    }
                p.repository.saveObservation(
                    CapturedObservation(
                        Protocol.newId(),
                        clock.now,
                        fix(clock.now, xy.first, xy.second),
                        Health(gnss_status = "fix"),
                        null,
                        clock.now * 1000000,
                        kotlinx.coroutines.CompletableDeferred(session),
                    )
                )
            }
        at(30)
        val capabilities = request(bridge.phone + "/api/v1/capabilities")
        val attempts = mutableListOf<String>()
        val adapter =
            object : Transport {
                override suspend fun post(endpoint: String, immutableJson: String) =
                    p.transport.post(endpoint, immutableJson)

                override suspend fun capabilities(endpoint: String) = capabilities

                override suspend fun history(endpoint: String, json: String): Response {
                    attempts.add(json)
                    for (m in Protocol.parse(json).getAsJsonArray("messages")) {
                        val parsed = Protocol.decodeMessage(m.toString())
                        assertEquals(
                            parsed,
                            p.db.dao().row(parsed.sequence)!!.let {
                                Protocol.decodeMessage(it.json)
                            },
                        )
                    }
                    return request(endpoint + "/api/v1/history", json)
                }
            }
        val sender =
            Sender(p.repository, adapter, clock) {
                p.repository.snapshot(clock.now, null, Health())
            }
        sender.step()
        assertEquals(rows.last().json, p.sent.first())
        assertEquals(0.0, device(p)["total_m"].asDouble, 0.001)
        bridge.rpc("drop")
        sender.step() // Real atomic store, lost physical-adapter response.
        assertNull(p.db.dao().row(rows.first().sequence)!!.deliveredAt)
        atMillis(p.db.dao().row(rows.first().sequence)!!.nextAttemptMillis)
        sender.step()
        assertEquals(attempts[0], attempts[1])
        repeat(30) { sender.step() }
        assertTrue(p.db.dao().all().all { it.deliveredAt != null })
        assertEquals(21, raw().size())
        assertEquals(21, points().size)
        assertEquals(100.0, distance(p), 0.001)
        assertEquals(
            rows.map { it.messageId }.toSet(),
            raw().map { it.asJsonObject["message"].asJsonObject["message_id"].asString }.toSet(),
        )
        assertTrue(attempts.size < rows.size)
        val progress = p.repository.snapshot(clock.now, null, Health(gnss_status = "fix"))
        p.deliver(progress)
        assertEquals("synchronized", device(p)["history"].asJsonObject["condition"].asString)
    }

    @Test
    fun sosGetsNextRequestAfterInFlightNativeHistoryAndSurvivesDuplicateRetry() = runBlocking {
        val p = phone()
        val session = p.repository.beginTracking()
        val history =
            (0..5).map { n ->
                at(n.toLong())
                p.repository.saveObservation(
                    CapturedObservation(
                        Protocol.newId(),
                        clock.now,
                        fix(clock.now, n * 5.0),
                        Health(gnss_status = "fix"),
                        null,
                        clock.now * 1000000,
                        kotlinx.coroutines.CompletableDeferred(session),
                    )
                )
            }
        at(100)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val roles = mutableListOf<String>()
        val adapter =
            object : Transport {
                override suspend fun post(endpoint: String, immutableJson: String): Response {
                    roles.add("sos")
                    return p.transport.post(endpoint, immutableJson)
                }

                override suspend fun capabilities(endpoint: String) =
                    request(endpoint + "/api/v1/capabilities")

                override suspend fun history(endpoint: String, json: String): Response {
                    roles.add("history")
                    started.complete(Unit)
                    release.await()
                    return request(endpoint + "/api/v1/history", json)
                }
            }
        val sender =
            Sender(p.repository, adapter, clock, p::recordSos) {
                error("Native history needs no synthetic gate")
            }
        val inFlight = async(kotlinx.coroutines.Dispatchers.Default) { sender.step() }
        started.await()
        val sos =
            SosEngine(
                    p.repository,
                    LatestLocation(clock),
                    { Health(gnss_status = "no_fix") },
                    clock,
                )
                .activate(SosTrigger.SCREEN)
                .row
        at(105) // A bounded in-flight operation is allowed to finish before SOS.
        release.complete(Unit)
        inFlight.await()
        assertEquals(listOf("history"), roles)
        sender.step()
        assertEquals(listOf("history", "sos"), roles)
        p.delivered(sos)
        p.delivered(history.first())
        assertNull(Protocol.decodeMessage(sos.json).fix)
        bridge.restart()
        p.reopen()
        // The hermetic process restart chooses a new localhost port. Real field address is stable.
        p.repository.settings("Alpha", "Field", bridge.phone, 10)
        at(106)
        val duplicate = p.transport.post(p.repository.state().endpoint, sos.json)
        assertEquals("duplicate", Protocol.parse(duplicate.body)["result"].asString)
        assertEquals(2, raw().size())
        assertEquals(1, state()["sos_alerts"].asJsonArray.size())
        assertEquals(0, points().size)
        assertEquals(0.0, distance(p), 0.001)
    }

    @Test
    fun nativeTwoOutageRecoveryEqualsContinuousGeometryBeforeAndAfterQualityEdit() = runBlocking {
        val continuous = phone()
        val interrupted = phone()
        for (p in listOf(continuous, interrupted)) p.repository.settings(
            "Field",
            "Comparison",
            bridge.phone,
            30,
        )
        val sessions =
            listOf(continuous, interrupted).associateWith { it.repository.beginTracking() }
        recording("start")
        val originals = mutableMapOf<Phone, MutableList<Outbound>>()
        val wire = mutableListOf<Long>()
        val adapter =
            object : Transport {
                override suspend fun post(endpoint: String, immutableJson: String): Response {
                    wire.add(
                        Protocol.decodeMessage(immutableJson).observation!!.observation_sequence
                    )
                    return interrupted.transport.post(endpoint, immutableJson)
                }

                override suspend fun capabilities(endpoint: String) =
                    request(endpoint + "/api/v1/capabilities")

                override suspend fun history(endpoint: String, json: String): Response {
                    val rows =
                        Protocol.parse(json).getAsJsonArray("messages").map {
                            Protocol.decodeMessage(it.toString())
                        }
                    val order = rows.map { it.observation!!.observation_sequence }
                    assertTrue(order.zipWithNext().all { (a, b) -> b == a + 1 })
                    for (m in rows) assertEquals(
                        m,
                        Protocol.decodeMessage(interrupted.db.dao().row(m.sequence)!!.json),
                    )
                    wire.addAll(order)
                    return request(endpoint + "/api/v1/history", json)
                }
            }
        val sender =
            Sender(
                interrupted.repository,
                adapter,
                clock,
                capture = { error("No synthetic current ACK gate") },
            )
        for (n in 1..100) {
            at(n.toLong())
            val (x, y) =
                when {
                    n <= 25 -> n * 5.0 to 0.0
                    n <= 50 -> 125.0 to (n - 25) * 5.0
                    n <= 75 -> (75 - n) * 5.0 to 125.0
                    else -> 0.0 to (100 - n) * 5.0
                }
            for (p in listOf(continuous, interrupted)) {
                val row =
                    p.repository.saveObservation(
                        CapturedObservation(
                            Protocol.newId(),
                            clock.now,
                            fix(clock.now, x, y, if (n == 60) 50.0 else 1.0),
                            Health(gnss_status = "fix"),
                            null,
                            clock.now * 1000000,
                            kotlinx.coroutines.CompletableDeferred(sessions.getValue(p)),
                        )
                    )
                originals.getOrPut(p) { mutableListOf() }.add(row)
                if (p == continuous || n <= 40 || n == 50 || n == 51) p.deliver(row)
            }
            if (n == 80) {
                sender.connectivityRestored()
                sender.step()
                assertEquals(listOf(80L), wire)
            }
        }
        var previous =
            device(interrupted)["location"].asJsonObject["fix"].asJsonObject["observed_at"].asString
        repeat(100) {
            sender.step()
            val current =
                device(interrupted)["location"]
                    .asJsonObject["fix"]
                    .asJsonObject["observed_at"]
                    .asString
            assertTrue("Backlog regressed live position", current >= previous)
            previous = current
        }
        assertEquals(listOf(80L) + (41L..49L) + (52L..79L) + (81L..100L), wire)
        assertEquals(200, raw().size())
        for ((p, rows) in originals) {
            assertTrue(p.db.dao().all().all { it.deliveredAt != null })
            for (point in points().filter { it["device_id"].asString == p.id }) assertEquals(
                rows[point["sequence"].asInt - 1].messageId,
                point["message_id"].asString,
            )
        }
        fun geometry(p: Phone): List<JsonObject> {
            val segments = mutableMapOf<String, Int>()
            return points()
                .filter { it["device_id"].asString == p.id }
                .map { point ->
                    point.deepCopy().apply {
                        remove("device_id")
                        remove("message_id")
                        addProperty(
                            "segment_id",
                            segments.getOrPut(point["segment_id"].asString) { segments.size },
                        )
                    }
                }
        }
        assertEquals(geometry(continuous), geometry(interrupted))
        assertTrue(geometry(continuous).size < 100) // Poor accuracy stayed raw, excluded by policy.
        val policy = state()["policy"].asJsonObject.deepCopy()
        policy.addProperty("maximum_accuracy_m", 100)
        control("quality", policy.toString())
        assertEquals(geometry(continuous), geometry(interrupted))
        // Accuracy acceptance and the independent uncertainty movement threshold are separate
        // rules.
        val enabled = policy.getAsJsonObject("enabled") ?: JsonObject()
        enabled.addProperty("uncertainty_multiplier", false)
        policy.add("enabled", enabled)
        control("quality", policy.toString())
        assertEquals(100, geometry(continuous).size)
        assertEquals(geometry(continuous), geometry(interrupted))
        assertEquals(distance(continuous), distance(interrupted), 0.000001)
        assertEquals(495.0, distance(interrupted), 0.001)
        assertEquals(200, raw().size())
        val progress = interrupted.repository.snapshot(clock.now, null, Health(gnss_status = "fix"))
        interrupted.deliver(progress)
        assertEquals(
            "synchronized",
            device(interrupted)["history"].asJsonObject["condition"].asString,
        )
    }

    @Test
    fun completeOneDayOfflineRetainedSessionsAcrossFivePhones() = runBlocking {
        offlineRetainedHistory(1)
    }

    @Test
    fun completeTwoDayOfflineRetainedSessionsAcrossFivePhones() = runBlocking {
        offlineRetainedHistory(2)
    }

    @Test
    fun completeSevenDayOfflineRetainedSessionsAcrossFivePhones() = runBlocking {
        offlineRetainedHistory(7)
    }

    private suspend fun offlineRetainedHistory(days: Int) {
        val devices = (0..4).map { phone() }
        val saved = mutableMapOf<Phone, MutableList<Outbound>>()
        // Capture only in the real file-backed Room outboxes; Command is stopped.
        bridge.stop()
        for ((index, p) in devices.withIndex()) {
            for (sessionIndex in 0..1) {
                clock.now = base + (sessionIndex * 3600L + index * 11) * 1000
                val session = p.repository.beginTracking(resumeExisting = false)
                for (n in 0 until 12 + index * 3) {
                    clock.now += 1000
                    val row =
                        p.repository.saveObservation(
                            CapturedObservation(
                                Protocol.newId(),
                                clock.now,
                                fix(clock.now, n * 5.0, index * 100.0, if (n == 5) 100.0 else 1.0),
                                Health(gnss_status = "fix"),
                                null,
                                clock.now * 1000000,
                                kotlinx.coroutines.CompletableDeferred(session),
                            )
                        )
                    saved.getOrPut(p) { mutableListOf() }.add(row)
                }
                p.repository.endTracking()
            }
            p.reopen()
        }
        clock.now = base + days * 86400000L
        bridge.start()
        for (p in devices) p.repository.settings("Field", "Offline history", bridge.phone, 30)
        control("recording", """{"action":"start","mode":"from_beginning"}""")
        assertEquals(0, state()["devices"].asJsonObject.size())
        // One late device initially reports no fix; queue inventory is not geometry.
        devices
            .last()
            .deliver(
                devices.last().repository.snapshot(clock.now, null, Health(gnss_status = "no_fix"))
            )
        bridge.rpc("fail_projection")
        // Deliberately ACK newest observations before oldest blocks (same bytes/IDs).
        for (p in devices) p.deliver(saved.getValue(p).last())
        assertTrue(state()["projection_pending"].asBoolean)
        assertTrue(state()["projection_error"].asString.isNotEmpty())
        assertEquals(0, points().size)
        bridge.restart() // Durable ACK survives failure and restart before projection finishes.
        bridge.rpc("restore_projection")
        for (p in devices) p.repository.settings("Field", "Offline history", bridge.phone, 30)
        val policy = state()["policy"].asJsonObject.deepCopy()
        policy.addProperty("maximum_accuracy_m", 50)
        control("quality", policy.toString())
        val began = System.nanoTime()
        for (p in devices) {
            val adapter =
                object : Transport {
                    override suspend fun post(endpoint: String, immutableJson: String) =
                        p.transport.post(endpoint, immutableJson)

                    override suspend fun capabilities(endpoint: String) =
                        request(endpoint + "/api/v1/capabilities")

                    override suspend fun history(endpoint: String, json: String): Response {
                        for (m in Protocol.parse(json).getAsJsonArray("messages")) {
                            val parsed = Protocol.decodeMessage(m.toString())
                            assertEquals(
                                parsed,
                                Protocol.decodeMessage(p.db.dao().row(parsed.sequence)!!.json),
                            )
                        }
                        return request(endpoint + "/api/v1/history", json)
                    }
                }
            val sender =
                Sender(
                    p.repository,
                    adapter,
                    clock,
                    capture = { error("History needs no synthetic fix") },
                )
            sender.connectivityRestored()
            repeat(saved.getValue(p).size + 5) { sender.step() }
            assertTrue(p.db.dao().all().all { it.deliveredAt != null })
            for (row in saved.getValue(p)) assertEquals(
                row.json,
                p.db.dao().row(row.sequence)!!.json,
            )
            p.deliver(saved.getValue(p).first()) // Exact immutable duplicate; no extra edge.
        }
        val actual = state()
        val reference = bridge.rpc("reference")
        fun logical(point: JsonObject) =
            point.deepCopy().apply {
                remove("x_m")
                remove("y_m")
                remove("dot")
            }
        assertEquals(
            reference["points"].asJsonArray.map { logical(it.asJsonObject) },
            actual["points"].asJsonArray.map { logical(it.asJsonObject) },
        )
        assertEquals(reference["decisions"], actual["decisions"])
        for (p in devices) {
            val reconstruction =
                actual["devices"].asJsonObject[p.id].asJsonObject["reconstruction"].asJsonObject
            assertEquals(saved.getValue(p).size, reconstruction["eligible"].asInt)
            assertEquals(saved.getValue(p).size, reconstruction["projected"].asInt)
            assertEquals(
                2,
                actual["devices"]
                    .asJsonObject[p.id]
                    .asJsonObject["history_sessions"]
                    .asJsonObject
                    .size(),
            )
            assertTrue(reconstruction["qualified_segments"].asInt >= 2)
            assertEquals("completed", reconstruction["projection"].asString)
        }
        assertEquals(
            saved.values.flatten().map { it.messageId }.toSet(),
            raw()
                .filter { it.asJsonObject["message"].asJsonObject["type"].asString == "location" }
                .map { it.asJsonObject["message"].asJsonObject["message_id"].asString }
                .toSet(),
        )
        println(
            "OFFLINE_HISTORY days=$days observations=${saved.values.sumOf { it.size }} devices=5 sessions=10 receipt_and_reconstruction_ms=${(System.nanoTime() - began) / 1000000}"
        )
        bridge.restart()
        val restarted = state()
        assertEquals(actual["points"], restarted["points"])
        assertEquals(actual["decisions"], restarted["decisions"])
    }

    @Test
    fun trackingOffTwentyPhonesAndLateJoinResumeOriginalRowsAfterLostResponses() = runBlocking {
        control("recording", """{"action":"start","mode":"from_beginning"}""")
        val fleet = (1..21).map { phone() }
        val saved =
            fleet.associateWith { p ->
                val session = p.repository.beginTracking()
                val rows =
                    (1..96).map { n ->
                        p.repository.saveObservation(
                            CapturedObservation(
                                Protocol.newId(),
                                base + n * 1000L,
                                fix(base + n * 1000L, n * 5.0),
                                Health(gnss_status = "fix"),
                                null,
                                n * 1000000000L,
                                kotlinx.coroutines.CompletableDeferred(session),
                            )
                        )
                    }
                p.repository.endTracking()
                p.reopen()
                rows
            }
        at(7 * 86400L)
        val allStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lateJoined = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        fun sender(p: Phone, firstWave: Boolean): Sender {
            var first = true
            val adapter =
                object : Transport {
                    override suspend fun capabilities(endpoint: String) =
                        request(endpoint + "/api/v1/capabilities")

                    override suspend fun post(endpoint: String, immutableJson: String) =
                        p.transport.post(endpoint, immutableJson)

                    override suspend fun history(endpoint: String, json: String): Response {
                        val response = request(endpoint + "/api/v1/history", json)
                        attempts.incrementAndGet()
                        if (first && firstWave) {
                            first = false
                            if (started.incrementAndGet() == 20) allStarted.complete(Unit)
                            kotlinx.coroutines.withTimeout(120000) { lateJoined.await() }
                            // Response lost after production SQLite commit. No phone receipt can be
                            // saved.
                            throw java.io.IOException("Injected lost response after durable commit")
                        }
                        return response
                    }
                }
            return Sender(p.repository, adapter, clock) {
                error("Tracking OFF collected a new snapshot")
            }
        }
        val first =
            fleet.take(20).map { p ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    sender(p, true).step(retainedOnly = true)
                }
            }
        kotlinx.coroutines.withTimeout(120000) { allStarted.await() }
        sender(fleet.last(), false).step(retainedOnly = true)
        lateJoined.complete(Unit)
        first.forEach { it.await() }
        bridge.restart()
        for (p in fleet) {
            p.reopen()
            p.repository.settings("Field", "Saved history", bridge.phone, 30)
        }
        fleet
            .map { p ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    val sender = sender(p, false)
                    sender.connectivityRestored()
                    repeat(110) { sender.step(retainedOnly = true) }
                    assertFalse(p.repository.state().tracking)
                    assertEquals(0, p.captures)
                    assertEquals(
                        saved.getValue(p).map { it.json },
                        p.db.dao().all().map { it.json },
                    )
                    assertTrue(p.db.dao().all().all { it.deliveredAt != null })
                }
            }
            .forEach { it.await() }
        val actual = state()
        val reference = bridge.rpc("reference")
        fun logical(point: JsonObject) =
            point.deepCopy().apply {
                remove("x_m")
                remove("y_m")
                remove("dot")
            }
        assertEquals(
            reference["points"].asJsonArray.map { logical(it.asJsonObject) },
            actual["points"].asJsonArray.map { logical(it.asJsonObject) },
        )
        assertEquals(reference["decisions"], actual["decisions"])
        assertEquals(
            saved.values.flatten().map { it.messageId }.toSet(),
            raw().map { it.asJsonObject["message"].asJsonObject["message_id"].asString }.toSet(),
        )
        println(
            "SYNC_OFF devices=20+1 observations=${saved.values.sumOf { it.size }} requests=${attempts.get()} new_fixes=0"
        )
    }

    @Test
    fun substantialTrackingOffNormalAndManualUseOriginalRowsAndMeasureRamp() = runBlocking {
        val measurements = mutableListOf<Pair<Int, Long>>()
        for (manual in listOf(false, true)) {
            val p = phone()
            at(0)
            val session = p.repository.beginTracking()
            val originals =
                (1..1200).map { n ->
                    at(n.toLong())
                    p.repository.saveObservation(
                        CapturedObservation(
                            "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}",
                            clock.now,
                            fix(clock.now, n * 5.0),
                            Health(gnss_status = "fix"),
                            null,
                            n * 1000000000L,
                            kotlinx.coroutines.CompletableDeferred(session),
                        )
                    )
                }
            p.repository.endTracking()
            at(7 * 86400L)
            var requests = 0
            var bytes = 0L
            var httpNanos = 0L
            var selectionNanos = 0L
            var roomAckNanos = 0L
            val receiptBefore = bridge.rpc("receipt_timing")["nanos"].asLong
            fun timedRequest(url: String, body: String? = null): Response {
                val began = System.nanoTime()
                try {
                    return request(url, body)
                } finally {
                    httpNanos += System.nanoTime() - began
                }
            }
            val measuredStore =
                object : MessageStore by p.repository {
                    override suspend fun historicalBounded(
                        now: Long,
                        target: Int,
                        maximumRecords: Int,
                    ): List<Outbound> {
                        val began = System.nanoTime()
                        try {
                            return p.repository.historicalBounded(now, target, maximumRecords)
                        } finally {
                            selectionNanos += System.nanoTime() - began
                        }
                    }

                    override suspend fun applyBatch(
                        rows: List<Outbound>,
                        receipts: List<Receipt>,
                        generation: Long,
                    ): Boolean {
                        val began = System.nanoTime()
                        try {
                            return p.repository.applyBatch(rows, receipts, generation)
                        } finally {
                            roomAckNanos += System.nanoTime() - began
                        }
                    }
                }
            val transport =
                object : Transport {
                    override suspend fun capabilities(endpoint: String) =
                        timedRequest(endpoint + "/api/v1/capabilities")

                    override suspend fun history(endpoint: String, json: String): Response {
                        requests++
                        bytes += json.toByteArray().size
                        return timedRequest(endpoint + "/api/v1/history", json)
                    }

                    override suspend fun post(endpoint: String, immutableJson: String): Response {
                        requests++
                        bytes += immutableJson.toByteArray().size
                        return timedRequest(endpoint + "/api/v1/messages", immutableJson)
                    }
                }
            val elapsedOrigin = System.nanoTime()
            val measuredClock =
                object : Clock {
                    override fun wallMillis() = clock.wallMillis()

                    override fun elapsedMillis() = (System.nanoTime() - elapsedOrigin) / 1000000
                }
            val sender =
                Sender(measuredStore, transport, measuredClock) {
                    error("Tracking OFF must acquire zero fixes")
                }
            if (manual) sender.requestDrain()
            val began = System.nanoTime()
            repeat(300) {
                if (p.repository.hasPendingHistory() == true) sender.step(retainedOnly = true)
            }
            sender.step(retainedOnly = true) // completion transition
            val elapsed = (System.nanoTime() - began) / 1000000
            val receiptNanos = bridge.rpc("receipt_timing")["nanos"].asLong - receiptBefore
            assertEquals(false, p.repository.hasPendingHistory())
            assertEquals(1200L, sender.syncProgress.ackedRecords)
            assertFalse(sender.aggressive)
            assertFalse(p.repository.state().tracking)
            assertEquals(0, p.captures)
            val saved = p.repository.dao.all()
            assertEquals(originals.map { it.json }, saved.map { it.json })
            assertTrue(saved.all { it.deliveredAt != null })
            val commandIds =
                raw()
                    .filter {
                        it.asJsonObject["message"].asJsonObject["device_id"].asString == p.id
                    }
                    .map { it.asJsonObject["message"].asJsonObject["message_id"].asString }
                    .toSet()
            assertEquals(originals.map { it.messageId }.toSet(), commandIds)
            println(
                "ISSUE22_ROOM_AB manual=$manual observations=1200 sourceBytes=${originals.sumOf { it.json.toByteArray().size }} requests=$requests requestBytes=$bytes transferAndRoomACKMillis=$elapsed zeroNewFixes=true measuredSenderRTT=true httpMillis=${httpNanos / 1000000.0} commandHandlerMillis=${receiptNanos / 1000000.0} selectionMillis=${selectionNanos / 1000000.0} roomAckMillis=${roomAckNanos / 1000000.0}"
            )
            measurements.add(requests to elapsed)
        }
        // A distinct faster discovery policy, without adding artificial automatic delays.
        assertTrue(measurements[1].first < measurements[0].first)
    }

    @Test
    fun freshSosAndLiveArriveDuringRealHistoryHttpWithDurableRoomTimings() = runBlocking {
        val p = phone()
        at(0)
        val session = p.repository.beginTracking()
        repeat(320) { n ->
            at(n.toLong())
            p.repository.saveObservation(
                CapturedObservation(
                    Protocol.newId(),
                    clock.now,
                    fix(clock.now, n * 5.0),
                    Health(gnss_status = "fix"),
                    null,
                    (n + 1) * 1000000000L,
                    kotlinx.coroutines.CompletableDeferred(session),
                )
            )
        }
        val transport =
            object : Transport {
                override suspend fun capabilities(endpoint: String) =
                    request(endpoint + "/api/v1/capabilities")

                override suspend fun history(endpoint: String, json: String) =
                    request(endpoint + "/api/v1/history", json)

                override suspend fun post(endpoint: String, immutableJson: String) =
                    request(endpoint + "/api/v1/messages", immutableJson)
            }
        val sender =
            Sender(p.repository, transport, clock) { error("Must use original native fixes") }
        val saved = mutableListOf<Long>()
        val sosAck = mutableListOf<Long>()
        val liveAck = mutableListOf<Long>()
        repeat(8) { event ->
            at(7 * 86400L + event * 10)
            val arrivals = bridge.rpc("history_arrivals")["count"].asLong
            bridge.rpc("delay_history", 250)
            val history =
                async(kotlinx.coroutines.Dispatchers.IO) { sender.step(retainedOnly = true) }
            while (bridge.rpc("history_arrivals")["count"].asLong == arrivals) kotlinx.coroutines
                .delay(1)
            val activation = System.nanoTime()
            val sos =
                SosEngine(
                        p.repository,
                        LatestLocation(clock),
                        { Health(gnss_status = "no_fix") },
                        clock,
                    )
                    .activate(SosTrigger.SCREEN)
                    .row
            val durable = System.nanoTime()
            saved.add((durable - activation) / 1000000)
            val fresh =
                p.repository.saveObservation(
                    CapturedObservation(
                        Protocol.newId(),
                        clock.now,
                        fix(clock.now, 1700.0 + event * 5),
                        Health(gnss_status = "fix"),
                        null,
                        clock.now * 1000000,
                        kotlinx.coroutines.CompletableDeferred(session),
                    )
                )
            val liveSaved = System.nanoTime()
            history.await()
            sender.step()
            p.delivered(sos)
            sosAck.add((System.nanoTime() - durable) / 1000000)
            sender.step()
            p.delivered(fresh)
            liveAck.add((System.nanoTime() - liveSaved) / 1000000)
        }
        fun stats(name: String, values: List<Long>) {
            val v = values.sorted()
            println(
                "ISSUE22_ROOM_PRIORITY $name n=${v.size} p50ms=${v[v.size / 2]} p95ms=${v.last()} p99ms=${v.last()} worstms=${v.last()}"
            )
        }
        stats("activation_to_Room", saved)
        stats("Room_to_SOS_ACK_applied", sosAck)
        stats("fresh_fix_Room_to_Command_ACK", liveAck)
        assertEquals(8, state()["sos_alerts"].asJsonArray.size())
        assertTrue(saved.all { it < 5000 } && sosAck.all { it < 5000 } && liveAck.all { it < 5000 })
        assertEquals(0, p.captures)
    }
}
