package org.gnss.tracking

import android.net.Network
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface Clock {
    fun wallMillis(): Long

    fun elapsedMillis(): Long
}

object SystemClock : Clock {
    override fun wallMillis() = System.currentTimeMillis()

    override fun elapsedMillis() = android.os.SystemClock.elapsedRealtime()
}

object Endpoint {
    fun validate(base: String): URI {
        val uri = URI(base)
        require(
            uri.scheme in listOf("http", "https") &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.path in listOf("", "/") &&
                (uri.port == -1 || uri.port in 1..65535)
        ) {
            "Use a Command base URL such as http://192.168.1.10:8080"
        }
        return uri
    }

    fun messages(base: String): URL =
        URL(validate(base).toString().trimEnd('/') + "/api/v1/messages")
}

data class Response(
    val code: Int,
    val body: String,
    val retryAfter: String? = null,
    val json: Boolean = true,
    val historyOnlyThrottle: Boolean = false,
)

fun interface Transport {
    suspend fun post(endpoint: String, immutableJson: String): Response

    suspend fun postRole(endpoint: String, json: String, role: String): Response =
        post(endpoint, json)

    suspend fun capabilities(endpoint: String): Response? = null

    suspend fun history(endpoint: String, json: String): Response = post(endpoint, json)

    fun wifiAvailable(): Boolean = true
}

class WifiUnavailable : java.io.IOException("Wi-Fi unavailable")

class LanTransport(private val network: () -> Network?) : Transport {
    private var selectedNetwork: Network? = null
    private var client: OkHttpClient? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var boundGeneration = -1L

    // Network/LinkProperties callbacks never wait for an in-flight HTTP call.
    fun invalidate() {
        generation.incrementAndGet()
    }

    override fun wifiAvailable() = network() != null

    override suspend fun post(endpoint: String, immutableJson: String) =
        request(endpoint, immutableJson, "/api/v1/messages", "live")

    override suspend fun postRole(endpoint: String, json: String, role: String) =
        request(endpoint, json, "/api/v1/messages", role)

    override suspend fun capabilities(endpoint: String) =
        request(endpoint, null, "/api/v1/capabilities", "capabilities")

    override suspend fun history(endpoint: String, json: String) =
        request(endpoint, json, "/api/v1/history", "history")

    private fun request(
        endpoint: String,
        immutableJson: String?,
        path: String,
        role: String,
    ): Response {
        val wifi = network() ?: throw WifiUnavailable()
        val currentGeneration = generation.get()
        if (selectedNetwork != wifi || boundGeneration != currentGeneration) {
            client?.connectionPool?.evictAll()
            client =
                OkHttpClient.Builder()
                    .socketFactory(wifi.socketFactory)
                    .dns(
                        object : okhttp3.Dns {
                            override fun lookup(hostname: String) =
                                wifi.getAllByName(hostname).toList()
                        }
                    )
                    .callTimeout(6, TimeUnit.SECONDS)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .writeTimeout(6, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build()
            selectedNetwork = wifi
            boundGeneration = currentGeneration
        }
        val builder =
            Request.Builder()
                .url(Endpoint.validate(endpoint).toString().trimEnd('/') + path)
                .header("Accept", "application/json")
                .header("X-GNSS-Delivery-Role", role)
        val request =
            if (immutableJson == null) builder.get().build()
            else builder.post(immutableJson.toRequestBody("application/json".toMediaType())).build()

        try {
            val deadlineSeconds =
                if (role == "history")
                    (6L + (immutableJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) / 262144)
                        .coerceAtMost(14)
                else 6L
            val boundedClient =
                if (deadlineSeconds > 6)
                    client!!
                        .newBuilder()
                        .readTimeout(deadlineSeconds, TimeUnit.SECONDS)
                        .writeTimeout(deadlineSeconds, TimeUnit.SECONDS)
                        .build()
                else client!!
            val call = boundedClient.newCall(request)
            call.timeout().timeout(deadlineSeconds, TimeUnit.SECONDS)
            call.execute().use { response ->
                val body =
                    response.body?.byteStream()?.use {
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        val responseLimit = if (role == "history") 2097152 else 65536
                        while (buffer.size() <= if (role == "history") 2097152 else 65536) {
                            val count =
                                it.read(
                                    chunk,
                                    0,
                                    minOf(chunk.size, responseLimit + 1 - buffer.size()),
                                )
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                        }
                        require(buffer.size() <= responseLimit) { "Response exceeds limit" }
                        buffer.toString("UTF-8")
                    } ?: ""
                return Response(
                    response.code,
                    body,
                    response.header("Retry-After"),
                    response
                        .header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.equals("application/json", true) == true,
                    response.header("X-GNSS-Throttle-Scope") == "history",
                )
            }
        } catch (e: java.io.IOException) {
            // A route can change while retaining the same Network handle. Do not
            // reuse its old pooled sockets/DNS after an actual IO failure.
            invalidate()
            throw e
        }
    }
}

data class SyncTransferProgress(
    val attemptedBytes: Long = 0,
    val responses: Long = 0,
    val validAckRecords: Long = 0,
    val mode: String = "Automatic",
    val ackedBytes: Long = 0,
    val ackedRecords: Long = 0,
    val lastBatchRecords: Int = 0,
    val lastError: String? = null,
)

data class Retry(val delayMillis: Long, val quarantine: Boolean)

fun classify(code: Int?, failures: Int, retryAfter: String? = null): Retry {
    val exponential = (1000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30000)
    val delta =
        if (code == 429 && retryAfter?.matches(Regex("[0-9]+")) == true)
            retryAfter.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000)
        else null
    return Retry(
        maxOf(exponential, delta ?: 0),
        code != null && code != 200 && code != 408 && code != 429 && code !in 500..599,
    )
}

// Single sender, independent from reporting/GNSS. One bounded request at a time;
// selection is repeated after every response so backlog cannot monopolize it.
class Sender(
    private val store: MessageStore,
    private val transport: Transport,
    private val clock: Clock,
    private val evidence: (DiagnosticEvent, SosEvidence) -> Unit = { _, _ -> },
    private val deliveryEvidence: (DiagnosticEvent, ReportEvidence) -> Unit = { _, _ -> },
    private val capture: suspend () -> Outbound,
) {
    private fun record(kind: DiagnosticEvent, value: SosEvidence) {
        runCatching { evidence(kind, value) }
    }

    @Volatile
    var syncProgress = SyncTransferProgress()
        private set

    private val mutex = Mutex()
    private var recovering = true
    private var failures = 0
    private var waitUntil = 0L // history/routine-only backoff
    private var receiverWaitUntil = 0L
    private var historyRateUntil = 0L
    private var negotiateAfter = 0L
    private var lastHistoryAck: Long? = null
    private val drainEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var drainPaused = false
    @Volatile
    var aggressive = false
        private set

    private var generation: Long? = null
    private var endpointBlocked = false
    private var historyNext = false
    private val adaptation = BatchAdaptation()
    private var batchSupported: Boolean? = null
    private var maximumBatchBytes = 65536
    private var maximumBatchRecords = 128
    @Volatile
    var compatibilityWarning: String? = null
        private set

    private var isolationLimit = 4096
    private var nextLiveElapsed = 0L
    private var lastLiveAttemptElapsed: Long? = null
    private var appliedInterval: Int? = null
    private var lastRoutineElapsed = Long.MIN_VALUE / 2
    @Volatile
    var commandReachable: Boolean? = null
        private set

    @Volatile
    var attemptedLastStep = false
        private set

    @Volatile
    var diagnostics = SenderState()
        private set

    private fun record(kind: DiagnosticEvent, row: Outbound) {
        runCatching {
            val m = Protocol.decodeMessage(row.json)
            deliveryEvidence(
                kind,
                ReportEvidence(
                    reportReference(m.message_id),
                    m.type,
                    m.sequence,
                    m.captured_at,
                    m.fix?.observed_at,
                ),
            )
        }
    }

    suspend fun requestDrain() {
        val epoch = drainEpoch.get()
        mutex.withLock {
            if (epoch != drainEpoch.get() || aggressive) return@withLock
            aggressive = true
            drainPaused = false
            if (clock.elapsedMillis() >= maxOf(receiverWaitUntil, historyRateUntil))
                store.resetTransientRetryTiming()
            if (!endpointBlocked && clock.elapsedMillis() >= historyRateUntil) waitUntil = 0
            syncProgress = syncProgress.copy(mode = "Manual drain active")
        }
    }

    fun pauseDrain() {
        drainPaused = true
        drainEpoch.incrementAndGet()
        aggressive = false
    }

    suspend fun connectivityRestored() =
        mutex.withLock {
            // LinkProperties can repeat while the receiver is healthy. Do not
            // defeat genuine server backpressure or repeatedly rediscover it.
            if (commandReachable == true) return@withLock
            if (lastHistoryAck?.let { clock.elapsedMillis() - it > 120000 } == true)
                adaptation.resumeAfterLongGap()
            // Clear durable transient deadlines before selecting recovery work. Otherwise
            // an eligible backlog row can jump ahead of a delayed SOS/current row.
            if (clock.elapsedMillis() >= maxOf(receiverWaitUntil, historyRateUntil))
                store.resetTransientRetryTiming()
            // The same receiver/link returning does not invalidate measured safe capacity.
            batchSupported = null
            negotiateAfter = 0
            nextLiveElapsed = 0
            lastLiveAttemptElapsed = null
            lastRoutineElapsed = Long.MIN_VALUE / 2
            commandReachable = null
            recovering = true
            historyNext = false
            if (
                !endpointBlocked &&
                    clock.elapsedMillis() >= maxOf(receiverWaitUntil, historyRateUntil)
            )
                waitUntil = 0
        }

    suspend fun step(
        sosOnly: Boolean = false,
        retainedOnly: Boolean = false,
        pauseHistory: Boolean = false,
    ) =
        mutex.withLock {
            attemptedLastStep = false
            stepNative(sosOnly, retainedOnly, pauseHistory)
        }

    private suspend fun stepNative(sosOnly: Boolean, retainedOnly: Boolean, pauseHistory: Boolean) {
        val historyPaused = pauseHistory || drainPaused
        val state = store.state()
        if (generation != state.endpointGeneration) {
            generation = state.endpointGeneration
            batchSupported = null
            adaptation.reset()
            recovering = true
            historyNext = false
            nextLiveElapsed = 0
            lastLiveAttemptElapsed = null
            lastRoutineElapsed = Long.MIN_VALUE / 2
            waitUntil = 0
            endpointBlocked = false
            receiverWaitUntil = 0
            historyRateUntil = 0
            negotiateAfter = 0
            commandReachable = null
        }
        val interval = state.config().effective_reporting_interval_s
        if (appliedInterval != interval) {
            appliedInterval = interval
            nextLiveElapsed = lastLiveAttemptElapsed?.let { it + interval * 1000L } ?: 0
        }
        if (state.endpoint.isEmpty() || state.deliveryPaused || endpointBlocked) {
            aggressive = false
            syncProgress = syncProgress.copy(mode = "Unresolved receiver setup; originals retained")
            return
        }
        // SOS bypasses ordinary retry backoff, but respects its own durable deadline.
        val priority = store.sos(clock.wallMillis())
        if (sosOnly && priority == null) return
        if (
            priority == null &&
                (!transport.wifiAvailable() || clock.elapsedMillis() < receiverWaitUntil)
        ) {
            if (!transport.wifiAvailable()) commandReachable = false
            if (aggressive)
                syncProgress =
                    syncProgress.copy(
                        mode = "Manual drain waiting for Wi-Fi or receiver Retry-After"
                    )
            return
        }
        val now = clock.wallMillis()
        if (!retainedOnly && priority == null && recovering && !store.hasNativeObservations()) {
            val current = store.newest()
            if (
                current == null ||
                    now - current.capturedMillis !in
                        0 until state.config().effective_reporting_interval_s * 1000L
            )
                capture()
        }
        val live =
            if (
                !retainedOnly &&
                    priority == null &&
                    (!historyNext || clock.elapsedMillis() < waitUntil) &&
                    (recovering || clock.elapsedMillis() >= nextLiveElapsed)
            )
                store.live(now)
            else null
        val rows: List<Outbound>
        var role = "history"
        if (priority != null) {
            rows = listOf(priority)
            role = "sos"
        } else if (live != null) {
            rows = listOf(live)
            role = "live"
            historyNext = true
            lastLiveAttemptElapsed = clock.elapsedMillis()
            nextLiveElapsed = clock.elapsedMillis() + interval * 1000L
        } else {
            val history =
                if (historyPaused || clock.elapsedMillis() < waitUntil) emptyList()
                else
                    store.historicalBounded(
                        now,
                        if (batchSupported == false && clock.elapsedMillis() < negotiateAfter) 0
                        else minOf(adaptation.target, maximumBatchBytes),
                        minOf(isolationLimit, maximumBatchRecords),
                    )
            // Independently bounded status cadence gets an opportunity after history.
            val routine = store.routine(now)
            if (
                routine != null &&
                    (history.isEmpty() || (!aggressive && !recovering && !historyNext)) &&
                    clock.elapsedMillis() - lastRoutineElapsed >=
                        state.config().effective_reporting_interval_s * 1000L
            ) {
                rows = listOf(routine)
                role = "status"
            } else {
                rows =
                    if (history.isNotEmpty()) history
                    else if (historyPaused || clock.elapsedMillis() < waitUntil) emptyList()
                    else listOfNotNull(store.routineBacklog(now))
                if (history.isEmpty()) role = "status_backlog"
                historyNext = false
            }
        }
        if (rows.isEmpty()) {
            if (aggressive && store.pendingHistory() == 0L) {
                aggressive = false
                syncProgress =
                    syncProgress.copy(
                        mode =
                            "Eligible history ACKed; inspect unresolved records; reconstruction separate"
                    )
            } else if (!aggressive && !drainPaused && store.pendingHistory() == 0L) {
                syncProgress =
                    syncProgress.copy(
                        mode = "Eligible history ACKed; inspect unresolved; reconstruction separate"
                    )
            } else if (aggressive)
                syncProgress =
                    syncProgress.copy(mode = "Manual drain waiting for Wi-Fi, receiver or retry")
            return
        }
        if (role == "history")
            syncProgress =
                syncProgress.copy(
                    mode = if (aggressive) "Manual drain active" else "Automatic active"
                )
        val batchEligible = role == "history" && rows.all { it.observationSequence != null }
        if (batchEligible && (batchSupported == null || clock.elapsedMillis() >= negotiateAfter)) {
            try {
                val capability = transport.capabilities(state.endpoint)
                negotiateAfter =
                    clock.elapsedMillis() + if (capability?.code == 200) 300000 else 15000
                if (capability?.code == 429) {
                    receiverWaitUntil =
                        clock.elapsedMillis() + classify(429, 1, capability.retryAfter).delayMillis
                    return
                }
                batchSupported =
                    capability?.let {
                        it.code == 200 &&
                            it.json &&
                            Protocol.parse(it.body).let { capability ->
                                maximumBatchBytes =
                                    capability.get("maximum_batch_bytes")?.let {
                                        Protocol.whole(
                                                capability,
                                                "maximum_batch_bytes",
                                                1,
                                                9007199254740991L,
                                            )
                                            .coerceAtMost(2097152)
                                            .toInt()
                                    } ?: 65536
                                maximumBatchRecords =
                                    capability.get("maximum_batch_records")?.let {
                                        Protocol.whole(
                                                capability,
                                                "maximum_batch_records",
                                                1,
                                                9007199254740991L,
                                            )
                                            .coerceAtMost(4096)
                                            .toInt()
                                    } ?: 128
                                adaptation.maximum = maximumBatchBytes
                                capability.getAsJsonArray("historical_batch_versions")?.any { v ->
                                    v.asInt == 1
                                } == true
                            }
                    } ?: false
                compatibilityWarning =
                    if (batchSupported == true) null
                    else
                        "Command lacks negotiated history support; individual v1 delivery preserves envelopes, but session completeness requires an upgraded Command."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                commandReachable = false
                failures = (failures + 1).coerceAtMost(32)
                adaptation.reachableFailure()
                negotiateAfter = clock.elapsedMillis() + classify(null, failures).delayMillis
                waitUntil = negotiateAfter
                return
            }
        }
        // Capability discovery is itself a bounded request; newly raised SOS gets the next one.
        if (role != "sos" && store.sos(clock.wallMillis()) != null) return
        val (selected, batchPayload) =
            if (batchEligible && batchSupported == true)
                HistoryProtocol.bounded(rows, maximumBatchBytes, maximumBatchRecords)
            else rows.take(1) to null
        if (role == "sos") {
            val row = selected.single()
            record(
                DiagnosticEvent.SOS_SEND_ATTEMPT,
                SosEvidence(sosReference(row.messageId), count = row.attempts + 1),
            )
            if (store.newest() != null || store.historical(clock.wallMillis(), 0).isNotEmpty())
                record(
                    DiagnosticEvent.SOS_PREEMPTED_BACKLOG,
                    SosEvidence(sosReference(row.messageId), competingReports = 1),
                )
        }
        selected.forEach { record(DiagnosticEvent.REPORT_SEND_ATTEMPT, it) }
        attemptedLastStep = true
        diagnostics = diagnostics.copy(lastAttemptElapsed = clock.elapsedMillis())
        var receivedResponse = false
        try {
            val batch = batchPayload != null
            val payload = batchPayload ?: selected.single().json
            val started = clock.elapsedMillis()
            val payloadBytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
            syncProgress =
                syncProgress.copy(
                    attemptedBytes = syncProgress.attemptedBytes + payloadBytes,
                    lastBatchRecords = selected.size,
                )
            val response =
                if (batch) transport.history(state.endpoint, payload)
                else transport.postRole(state.endpoint, payload, role)
            receivedResponse = true
            syncProgress = syncProgress.copy(responses = syncProgress.responses + 1)
            commandReachable = true
            diagnostics = diagnostics.copy(lastHttpCode = response.code)
            if (store.state().endpointGeneration != state.endpointGeneration) return
            if (response.code == 200) {
                require(response.json) { "ACK must be JSON" }
                val receipts =
                    if (batch) HistoryProtocol.receipts(response.body, selected)
                    else
                        listOf(
                            Protocol.receipt(
                                response.body,
                                Protocol.decodeMessage(selected.single().json),
                            )
                        )
                syncProgress =
                    syncProgress.copy(
                        validAckRecords = syncProgress.validAckRecords + selected.size
                    )
                if (!store.applyBatch(selected, receipts, state.endpointGeneration)) {
                    syncProgress =
                        syncProgress.copy(
                            lastError =
                                "Stale endpoint receipt not applied; originals remain pending"
                        )
                    return
                }
                syncProgress =
                    syncProgress.copy(
                        ackedBytes = syncProgress.ackedBytes + payloadBytes,
                        ackedRecords = syncProgress.ackedRecords + selected.size,
                        lastError = null,
                    )
                selected.forEach { record(DiagnosticEvent.REPORT_ACK_ACCEPTED, it) }
                if (role == "sos")
                    record(
                        DiagnosticEvent.SOS_TRANSPORT_ACK_ACCEPTED,
                        SosEvidence(sosReference(selected.single().messageId)),
                    )
                diagnostics =
                    diagnostics.copy(
                        lastAckElapsed = clock.elapsedMillis(),
                        retryUntilElapsed = null,
                        failures = 0,
                    )
                failures = 0
                waitUntil = 0
                if (role == "status") lastRoutineElapsed = clock.elapsedMillis()
                if (role != "sos") recovering = false
                isolationLimit = maximumBatchRecords
                if (role == "history") {
                    lastHistoryAck = clock.elapsedMillis()
                    adaptation.acknowledged(
                        payloadBytes,
                        (clock.elapsedMillis() - started).coerceAtLeast(1),
                        aggressive,
                    )
                }
                return
            }
            if (batch && response.code in listOf(404, 405, 426)) {
                batchSupported = false
                negotiateAfter = clock.elapsedMillis() + 60000
                return
            }
            if (batch && response.code == 422 && response.json) {
                val error = Protocol.parse(response.body)
                if (error.get("error")?.asString == "unsupported_batch") {
                    batchSupported = false
                    negotiateAfter = clock.elapsedMillis() + 60000
                    compatibilityWarning =
                        "Historical batch version unavailable; retaining individual v1 delivery."
                    return
                }
                val index = error.get("entry_index")?.asInt ?: -1
                val id = error.get("observation_id")?.asString
                if (index in selected.indices && (id == null || id == selected[index].messageId)) {
                    store.fail(
                        selected[index],
                        "${error.get("error")?.asString}: ${error.get("message")?.asString}",
                        0,
                        true,
                        state.endpointGeneration,
                    )
                    isolationLimit = maximumBatchRecords
                    return
                }
            }
            if (response.code == 413) {
                // Transport size is not evidence of poisoned GNSS. Retain and retry;
                // a singleton batch uses individual v1 on the next attempt.
                if (batch && selected.size == 1) {
                    batchSupported = false
                    negotiateAfter = clock.elapsedMillis() + 60000
                } else if (batch) {
                    maximumBatchBytes = maxOf(1, payloadBytes.toInt() / 2)
                    isolationLimit = maxOf(1, selected.size / 2)
                }
                adaptation.reachableFailure()
                nativeFailure(
                    selected,
                    503,
                    response.retryAfter,
                    "Transport size rejected (413); original retained",
                    state,
                    role,
                )
                return
            }
            if (batch && response.code in listOf(400, 422)) {
                if (selected.size > 1) {
                    isolationLimit = maxOf(1, selected.size / 2)
                    return
                }
                store.fail(
                    selected.single(),
                    "Permanent batch validation: HTTP ${response.code}: ${response.body}",
                    0,
                    true,
                    state.endpointGeneration,
                )
                return
            }
            if (role == "history" && response.code != 429) adaptation.reachableFailure()
            nativeFailure(
                selected,
                response.code,
                response.retryAfter,
                if (response.code == 429) RATE_LIMIT_RETRY_ERROR
                else "Command HTTP ${response.code}",
                state,
                role,
                response.historyOnlyThrottle,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (role == "sos") {
                val kind =
                    if (e is WifiUnavailable) DiagnosticEvent.SOS_NETWORK_UNAVAILABLE
                    else if (receivedResponse) DiagnosticEvent.SOS_TRANSPORT_ACK_REJECTED else null
                if (kind != null)
                    record(kind, SosEvidence(sosReference(selected.single().messageId)))
            }
            if (receivedResponse && transport.wifiAvailable()) {
                if (role == "history") adaptation.reachableFailure()
            } else adaptation.reachableFailure()
            commandReachable = receivedResponse
            nativeFailure(selected, null, null, e.message ?: "ACK unavailable", state, role)
        }
    }

    private suspend fun nativeFailure(
        rows: List<Outbound>,
        code: Int?,
        after: String?,
        error: String,
        state: Installation,
        role: String,
        historyOnlyThrottle: Boolean = false,
    ) {
        syncProgress = syncProgress.copy(lastError = error)
        val count =
            if (role == "sos") rows.first().attempts + 1 else (failures + 1).coerceAtMost(32)
        val baseRetry = classify(code, count, after)
        // Positive-only jitter never violates Retry-After and desynchronizes fleet retries.
        val retry =
            baseRetry.copy(
                delayMillis =
                    (baseRetry.delayMillis +
                            kotlin.random.Random.nextLong(
                                0,
                                (baseRetry.delayMillis / 4).coerceAtLeast(1),
                            ))
                        .coerceAtLeast(baseRetry.delayMillis)
            )
        rows.forEach {
            store.fail(
                it,
                error,
                clock.wallMillis() +
                    retry.delayMillis.coerceAtMost(Long.MAX_VALUE - clock.wallMillis()),
                retry.quarantine,
                state.endpointGeneration,
            )
            record(DiagnosticEvent.REPORT_RETRY, it)
        }
        if (role == "sos")
            record(
                DiagnosticEvent.SOS_RETRY_BACKOFF,
                SosEvidence(sosReference(rows.single().messageId), durationMs = retry.delayMillis),
            )
        if (role != "sos") {
            failures = count
            waitUntil =
                clock.elapsedMillis() +
                    retry.delayMillis.coerceAtMost(Long.MAX_VALUE - clock.elapsedMillis())
            recovering = true
            if (code == 429) {
                historyRateUntil = waitUntil
                if (!historyOnlyThrottle) receiverWaitUntil = waitUntil
            }
        }
        syncProgress =
            syncProgress.copy(
                mode = "${if (aggressive) "Manual drain" else "Automatic"} waiting: $error"
            )
        diagnostics =
            diagnostics.copy(
                retryUntilElapsed =
                    clock.elapsedMillis() +
                        retry.delayMillis.coerceAtMost(Long.MAX_VALUE - clock.elapsedMillis()),
                failures = count,
            )
        if (code in listOf(404, 405, 415, 426)) {
            endpointBlocked = true
            store.pauseReceiver(error, state.endpointGeneration)
        }
    }
}
