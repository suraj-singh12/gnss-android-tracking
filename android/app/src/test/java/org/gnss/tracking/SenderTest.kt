package org.gnss.tracking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeClock(var now: Long = 100000) : Clock {
    override fun wallMillis() = now

    override fun elapsedMillis() = now
}

class MemoryStore : MessageStore {
    var installation = Installation(endpoint = "http://192.168.1.2")
    val rows = mutableListOf<Outbound>()
    val delivered = mutableListOf<Long>()

    override suspend fun state() = installation

    override suspend fun snapshot(now: Long, fix: Fix?, health: Health): Outbound {
        installation = installation.copy(sequence = installation.sequence + 1)
        val message =
            Message(
                type = if (fix == null) "status" else "location",
                device_id = installation.deviceId,
                party = Party("A", "B"),
                message_id = Protocol.newId(),
                sequence = installation.sequence,
                captured_at = utc(now),
                config_state = installation.config(),
                health = health,
                fix = fix,
            )
        return Outbound(
                message.sequence,
                message.message_id,
                message.type,
                now,
                Protocol.encode(message),
            )
            .also { rows.add(it) }
    }

    override suspend fun newest() =
        rows
            .filter { it.deliveredAt == null && !it.quarantined && it.type != "sos" }
            .maxByOrNull { it.sequence }

    private fun eligible(now: Long) =
        rows.filter { it.deliveredAt == null && !it.quarantined && it.nextAttemptMillis <= now }

    override suspend fun sos(now: Long) =
        eligible(now).filter { it.type == "sos" }.minByOrNull { it.sequence }

    override suspend fun historical(now: Long, target: Int) =
        eligible(now).filter { it.type == "location" }.sortedBy { it.sequence }.take(1)

    override suspend fun routine(now: Long) =
        eligible(now).filter { it.type == "status" }.maxByOrNull { it.sequence }

    override suspend fun routineBacklog(now: Long) =
        eligible(now).filter { it.type == "status" }.minByOrNull { it.sequence }

    override suspend fun resetTransientRetryTiming() {
        for (index in rows.indices) {
            val row = rows[index]
            if (row.deliveredAt == null && !row.quarantined)
                rows[index] = row.copy(nextAttemptMillis = 0)
        }
    }

    override suspend fun accept(row: Outbound, receipt: Receipt, generation: Long) {
        delivered.add(row.sequence)
        rows[rows.indexOfFirst { it.sequence == row.sequence }] =
            row.copy(deliveredAt = receipt.receivedAt)
    }

    override suspend fun pauseReceiver(error: String, generation: Long) {
        installation = installation.copy(deliveryPaused = true)
    }

    override suspend fun fail(
        row: Outbound,
        error: String,
        nextAttempt: Long,
        quarantine: Boolean,
        generation: Long,
    ) {
        rows[rows.indexOfFirst { it.sequence == row.sequence }] =
            row.copy(nextAttemptMillis = nextAttempt, quarantined = quarantine, error = error)
    }
}

class SenderTest {
    @Test
    fun fixAgeUsesElapsedTimeAcrossWallClockEdits() {
        var wall = 100000L
        var elapsed = 1000L
        val clock =
            object : Clock {
                override fun wallMillis() = wall

                override fun elapsedMillis() = elapsed
            }
        val latest = LatestLocation(clock)
        latest.enabled = true
        val measured = Fix(utc(wall), 0, 28.0, 77.0, 3.0, null, null, null, null)
        latest.update(Observation(measured, elapsed))
        wall += 3600000
        elapsed += 1000
        assertEquals(1000L, latest.currentFix()!!.fix_age_ms)
        assertEquals(measured.observed_at, latest.currentFix()!!.observed_at)
        assertNotNull(latest.clockWarning())
        wall -= 7200000
        assertEquals(1000L, latest.age())
        elapsed += 30001
        assertNull(latest.currentFix())
        assertEquals("unknown", latest.status())
    }

    private fun ack(json: String, result: String = "stored"): Response {
        val message = Protocol.decodeMessage(json)
        return Response(
            200,
            """{"protocol_version":1,"device_id":"${message.device_id}","message_id":"${message.message_id}","sequence":${message.sequence},"result":"$result","received_at":"2026-10-06T12:00:01.000Z","config":{"authority_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","version":0,"reporting_interval_override_s":null}}""",
        )
    }

    @Test
    fun currentFirstThenOldestWhileNewReportsInterruptBacklog() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        repeat(5) { store.snapshot(clock.now, null, Health()) }
        val sent = mutableListOf<Long>()
        val sender =
            Sender(
                store,
                Transport { _, json ->
                    sent.add(Protocol.decodeMessage(json).sequence)
                    ack(json)
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        sender.step()
        clock.now += 10000
        store.snapshot(clock.now, null, Health())
        repeat(4) { sender.step() }
        assertEquals(listOf(5L, 1L, 6L, 2L, 3L, 4L), sent)
    }

    @Test
    fun responseLossRetriesExactDurableIdentity() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        val row = store.snapshot(clock.now, null, Health())
        val payloads = mutableListOf<String>()
        val sender =
            Sender(
                store,
                Transport { _, json ->
                    assertTrue(store.rows.any { it.json == json })
                    payloads.add(json)
                    if (payloads.size == 1)
                        throw java.io.IOException("response dropped after store")
                    ack(json, "duplicate")
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        sender.step()
        assertEquals(1, payloads.size)
        assertTrue(store.delivered.isEmpty())
        clock.now += 1000
        sender.step()
        assertEquals(listOf(row.json, row.json), payloads)
        assertEquals(listOf(1L), store.delivered)
        assertEquals(1L, store.installation.sequence)
    }

    @Test
    fun staleRecoverySavesCurrentBeforeBacklog() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        store.snapshot(0, null, Health())
        store.snapshot(1, null, Health())
        val sender =
            Sender(store, Transport { _, json -> ack(json) }, clock) {
                store.snapshot(clock.now, null, Health())
            }
        repeat(3) { sender.step() }
        assertEquals(listOf(3L, 1L, 2L), store.delivered)
    }

    @Test
    fun malformedAckKeepsPendingAndPermanentReceiverFailureDoesNotHotLoop() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        store.snapshot(clock.now, null, Health())
        var requests = 0
        val sender =
            Sender(
                store,
                Transport { _, _ ->
                    requests++
                    Response(200, "{}")
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        sender.step()
        assertEquals(1, requests)
        assertTrue(store.delivered.isEmpty())
        val blocked =
            Sender(
                store,
                Transport { _, _ ->
                    requests++
                    Response(426, "")
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        clock.now += 1000
        blocked.step()
        clock.now += 60000
        store.snapshot(clock.now, null, Health())
        blocked.step()
        assertEquals(2, requests)
        assertTrue(store.rows.first().quarantined)
    }

    @Test
    fun unavailableAndStaleGnssNeverAppearFresh() {
        val clock = FakeClock()
        val latest = LatestLocation(clock)
        assertEquals("unknown", latest.status())
        assertNull(latest.currentFix())
        latest.enabled = true
        assertEquals("no_fix", latest.status())
        val fix = Fix(utc(1), 0, 28.0, 77.0, null, null, null, null, null)
        latest.update(Observation(fix, clock.now - 500))
        assertEquals(500L, latest.currentFix()!!.fix_age_ms)
        clock.now += 31000
        assertNull(latest.currentFix())
        assertEquals("unknown", latest.status())
        latest.enabled = false
        assertEquals("disabled", latest.status())
        latest.update(Observation(fix, clock.now + 100))
        assertEquals(31500L, latest.age())
    }

    @Test
    fun reservedSosPrecedesCurrentAndBacklog() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        val fixture = javaClass.classLoader!!.getResource("sos-no-fix.json")!!.readText()
        val message = Protocol.decodeMessage(fixture)
        store.rows.add(Outbound(message.sequence, message.message_id, "sos", clock.now, fixture))
        store.installation = store.installation.copy(sequence = message.sequence)
        val current = store.snapshot(clock.now, null, Health())
        val sender =
            Sender(store, Transport { _, json -> ack(json) }, clock) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        sender.step()
        assertEquals(listOf(message.sequence, current.sequence), store.delivered)
    }

    @Test
    fun retryAfterAndRestartedEndpointPauseAreHonored() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        var attempts = 0
        store.snapshot(clock.now, null, Health())
        val sender =
            Sender(
                store,
                Transport { _, _ ->
                    attempts++
                    Response(429, "", "60")
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        clock.now += 30000
        store.snapshot(clock.now, null, Health())
        sender.step()
        assertEquals(1, attempts)
        clock.now += 30000
        sender.step()
        assertEquals(2, attempts)
        store.pauseReceiver("Incompatible receiver")
        val restored =
            Sender(
                store,
                Transport { _, json ->
                    attempts++
                    ack(json)
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        restored.step()
        assertEquals(2, attempts)
    }

    @Test
    fun lateErrorFromPreviousEndpointDoesNotPauseNewReceiver() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        store.snapshot(clock.now, null, Health())
        var requests = 0
        val sender =
            Sender(
                store,
                Transport { _, json ->
                    requests++
                    if (requests == 1) {
                        store.installation = store.installation.copy(endpointGeneration = 1)
                        Response(426, "")
                    } else ack(json)
                },
                clock,
            ) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        assertFalse(store.installation.deliveryPaused)
        assertFalse(store.rows.first().quarantined)
        sender.step()
        assertEquals(listOf(1L), store.delivered)
    }

    @Test
    fun deliveryEvidenceCannotBreakSendingAndHasNoRawIdentity() = runBlocking {
        val store = MemoryStore()
        val clock = FakeClock()
        val row = store.snapshot(clock.now, null, Health())
        val seen = mutableListOf<ReportEvidence>()
        val sender =
            Sender(
                store,
                Transport { _, json -> ack(json) },
                clock,
                deliveryEvidence = { _, report ->
                    seen.add(report)
                    throw IllegalStateException("disk failed")
                },
            ) {
                store.snapshot(clock.now, null, Health())
            }
        sender.step()
        assertEquals(listOf(row.sequence), store.delivered)
        assertEquals(2, seen.size)
        assertEquals(reportReference(row.messageId), seen.first().reference)
        assertFalse(com.google.gson.Gson().toJson(seen).contains(row.messageId))
        assertNotNull(sender.diagnostics.lastAckElapsed)
    }

    @Test
    fun effectiveIntervalChangesRescheduleLiveWithoutWaitingForOldCadence() = runBlocking {
        val clock = FakeClock()
        val store = MemoryStore()
        store.installation = store.installation.copy(localInterval = 30)
        val liveTimes = mutableListOf<Long>()
        val transport =
            object : Transport {
                override suspend fun post(endpoint: String, immutableJson: String) =
                    ack(immutableJson)

                override suspend fun postRole(
                    endpoint: String,
                    json: String,
                    role: String,
                ): Response {
                    if (role == "live") liveTimes.add(clock.now)
                    return ack(json)
                }
            }
        val sender = Sender(store, transport, clock) { error("No synthetic current report") }
        suspend fun current() =
            store.snapshot(
                clock.now,
                Fix(utc(clock.now), 0, 28.0, 77.0, 1.0, null, null, null, null),
                Health(gnss_status = "fix"),
            )
        current()
        sender.step()
        sender.step() // Empty historical opportunity.
        clock.now += 5000
        store.installation = store.installation.copy(localInterval = 5)
        current()
        sender.step()
        sender.step()
        clock.now += 5000
        store.installation = store.installation.copy(localInterval = 30)
        current()
        sender.step() // This observation may synchronize historically, not at 5 s live cadence.
        clock.now += 25000
        current()
        sender.step()
        assertEquals(listOf(100000L, 105000L, 135000L), liveTimes)
    }
}
