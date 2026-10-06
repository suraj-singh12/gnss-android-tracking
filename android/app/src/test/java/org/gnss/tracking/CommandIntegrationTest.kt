package org.gnss.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        var latest: Fix? = null
        var captures = 0
        var fault: ((Response) -> Response)? = null
        var adapterFailure: Response? = null
        val transport = Transport { endpoint, json ->
            // Prove save-before-send with the real durable row, on every attempt.
            val m = Protocol.decodeMessage(json)
            assertEquals(json, db.dao().row(m.sequence)!!.json)
            sent.add(json)
            val response = adapterFailure ?: request(Endpoint.messages(endpoint).toString(), json)
            responses.add(response)
            fault?.invoke(response) ?: response
        }

        fun open() {
            db = Room.databaseBuilder(context, TrackingDatabase::class.java, name).build()
            repository = Repository(db, clock)
            sender =
                Sender(repository, transport, clock) {
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
                        "-test.timeout=120s",
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
            val line = reads.submit<String> { reader.readLine() }.get(60, TimeUnit.SECONDS)
            check(line != null) { "Command bridge exited: ${log.readText()}" }
            return Protocol.parse(line)
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
        at(12)
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
        p.adapterFailure = Response(503, "")
        p.sender.step()
        p.adapterFailure = null
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
        // Seed only the already-frozen reservation; no SOS trigger/workflow added.
        val s = p.repository.state()
        val id = Protocol.newId()
        val message =
            Message(
                type = "sos",
                device_id = p.id,
                party = Party(s.partyId, s.partyName),
                message_id = id,
                sequence = s.sequence + 1,
                captured_at = utc(clock.now),
                config_state = s.config(),
                health = Health(),
                fix = null,
                sos = Sos(id, utc(clock.now)),
            )
        val sos = Outbound(message.sequence, id, "sos", clock.now, Protocol.encode(message))
        p.db.dao().update(s.copy(sequence = sos.sequence))
        p.db.dao().insert(sos)
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
        at(4)
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
        at(2)
        p.fault = { response ->
            val ack = Protocol.parse(response.body)
            ack.addProperty("sequence", 999)
            response.copy(body = ack.toString())
        }
        p.sender.step()
        assertEquals(1, raw().size())
        assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
        p.fault = null
        at(4)
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
        at(65)
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
            p.sender.step()
            assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
            assertNull(p.repository.state().authority)
            assertEquals(1, p.db.dao().all().size)
            assertEquals(1, raw().size())
        }
        p.fault = { it.copy(json = false) }
        p.sender.connectivityRestored()
        p.sender.step()
        assertNull(p.db.dao().row(row.sequence)!!.deliveredAt)
        p.fault = null
        p.sender.connectivityRestored()
        p.sender.step()
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
        assertEquals("clock_anomaly", device(p)["gnss_condition"].asString)
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
}
