package org.gnss.tracking

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val message
        get() = Protocol.decodeMessage(fixture("location-normal.json"))

    @Test
    fun allWireFixturesRoundTrip() {
        val names =
            java.io.File("../../protocol/fixtures").listFiles()!!.filter {
                it.extension == "json" && !it.name.startsWith("scenario-")
            }
        assertTrue(names.isNotEmpty())
        for (f in names) {
            val json = f.readText()
            if (f.name.startsWith("ack-")) {
                val tree = Protocol.parse(json)
                val expected =
                    message.copy(
                        device_id = tree["device_id"].asString,
                        message_id = tree["message_id"].asString,
                        sequence = tree["sequence"].asLong,
                    )
                val result = Protocol.receipt(json, expected)
                assertNull(result.configError)
            } else
                assertEquals(
                    f.name,
                    Protocol.parse(json),
                    Protocol.parse(Protocol.encode(Protocol.decodeMessage(json))),
                )
        }
    }

    @Test
    fun nullableFieldsRemainPresentAndSosIsReserved() {
        val status = Protocol.decodeMessage(fixture("status-no-fix.json"))
        val tree = Protocol.parse(Protocol.encode(status))
        assertTrue(tree["fix"].isJsonNull)
        assertFalse(tree.has("sos"))
        assertTrue(tree["health"].asJsonObject.has("wifi_rssi_dbm"))
        val sos = Protocol.decodeMessage(fixture("sos-no-fix.json"))
        assertEquals(sos.message_id, sos.sos!!.event_id)
        assertNull(sos.fix)
    }

    @Test
    fun malformedOrMismatchedReceiptNeverAccepted() {
        val ack = fixture("ack-stored.json")
        for (mutate in
            listOf<(com.google.gson.JsonObject) -> Unit>(
                { it.addProperty("sequence", 2) },
                { it.addProperty("sequence", 1.0) },
                {
                    it.add(
                        "sequence",
                        com.google.gson.JsonParser.parseString("18446744073709551617"),
                    )
                },
                { it.addProperty("protocol_version", 2) },
                { it.addProperty("message_id", Protocol.newId()) },
                { it.addProperty("device_id", Protocol.newId()) },
                { it.addProperty("result", "ok") },
                { it.remove("received_at") },
                { it.remove("config") },
                { it.addProperty("received_at", "2026-02-30T12:00:00.000Z") },
            )) {
            val tree = Protocol.parse(ack)
            mutate(tree)
            assertTrue(runCatching { Protocol.receipt(tree.toString(), message) }.isFailure)
        }
        assertTrue(
            runCatching {
                    Protocol.receipt(
                        ack.replace("\"sequence\": 1", "\"sequence\": 1, \"sequence\": 1"),
                        message,
                    )
                }
                .isFailure
        )
        assertEquals(
            message.message_id,
            Protocol.receipt(fixture("ack-duplicate.json"), message).message,
        )
    }

    @Test
    fun invalidConfigDoesNotUndoValidReceipt() {
        val ack = Protocol.parse(fixture("ack-stored.json"))
        ack["config"].asJsonObject.remove("reporting_interval_override_s")
        val receipt = Protocol.receipt(ack.toString(), message)
        assertNull(receipt.config)
        assertNotNull(receipt.configError)
    }

    @Test
    fun rejectRequestDrift() {
        val json = Protocol.parse(fixture("location-normal.json"))
        json["fix"].asJsonObject.remove("speed_mps")
        assertTrue(runCatching { Protocol.decodeMessage(json.toString()) }.isFailure)
        assertTrue(
            runCatching {
                    Protocol.encode(message.copy(fix = message.fix!!.copy(latitude = Double.NaN)))
                }
                .isFailure
        )
    }

    @Test
    fun localAndRemoteIntervals() {
        for (v in listOf(0, 9, 15, 86410, Int.MAX_VALUE)) assertFalse(validInterval(v))
        for (v in listOf(10, 20, 30, 60, 86400)) assertTrue(validInterval(v))
        val authority = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val initial = ConfigState(local_reporting_interval_s = 20)
        val enrolled =
            (applyConfig(initial, RemoteConfig(authority, 0, null)) as ConfigDecision.Applied).state
        val remote =
            (applyConfig(enrolled, RemoteConfig(authority, 1, 30)) as ConfigDecision.Applied).state
        assertEquals(30, remote.effective_reporting_interval_s)
        assertEquals(ConfigDecision.NoChange, applyConfig(remote, RemoteConfig(authority, 0, null)))
        assertEquals(ConfigDecision.NoChange, applyConfig(remote, RemoteConfig(authority, 1, 30)))
        assertTrue(applyConfig(remote, RemoteConfig(authority, 1, 60)) is ConfigDecision.Error)
        assertTrue(
            applyConfig(remote, RemoteConfig(Protocol.newId(), 2, null)) is ConfigDecision.Error
        )
        val clear =
            (applyConfig(
                    remote.copy(local_reporting_interval_s = 60),
                    RemoteConfig(authority, 2, null),
                )
                    as ConfigDecision.Applied)
                .state
        assertEquals(60, clear.effective_reporting_interval_s)
    }

    @Test
    fun retryClassification() {
        assertEquals(
            listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (1..7).map { classify(null, it).delayMillis },
        )
        for (code in listOf(null, 200, 429, 500, 503)) assertFalse(classify(code, 1).quarantine)
        for (code in listOf(400, 404, 405, 409, 413, 415, 426, 302)) assertTrue(
            classify(code, 1).quarantine
        )
        assertEquals(120000, classify(429, 1, "120").delayMillis)
        assertEquals(1000, classify(429, 1, "invalid").delayMillis)
    }
}
