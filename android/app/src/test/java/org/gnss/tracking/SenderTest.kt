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

    override suspend fun next(now: Long, currentAfter: Long): Outbound? {
        val eligible =
            rows.filter { it.deliveredAt == null && !it.quarantined && it.nextAttemptMillis <= now }
        return eligible.filter { it.type == "sos" }.minByOrNull { it.sequence }
            ?: newest()?.takeIf { it.sequence > currentAfter && it.nextAttemptMillis <= now }
            ?: eligible.minByOrNull { it.sequence }
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
}
