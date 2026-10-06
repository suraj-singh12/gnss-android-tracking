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
)

fun interface Transport {
    suspend fun post(endpoint: String, immutableJson: String): Response
}

class LanTransport(private val network: () -> Network?) : Transport {
    private var selectedNetwork: Network? = null
    private var client: OkHttpClient? = null

    override suspend fun post(endpoint: String, immutableJson: String): Response {
        val wifi = network() ?: throw java.io.IOException("Wi-Fi unavailable")
        if (selectedNetwork != wifi) {
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
                    .callTimeout(10, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build()
            selectedNetwork = wifi
        }
        val request =
            Request.Builder()
                .url(Endpoint.messages(endpoint))
                .header("Accept", "application/json")
                .post(immutableJson.toRequestBody("application/json".toMediaType()))
                .build()
        client!!.newCall(request).execute().use { response ->
            val body =
                response.body?.byteStream()?.use {
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (buffer.size() <= 65536) {
                        val count = it.read(chunk, 0, minOf(chunk.size, 65537 - buffer.size()))
                        if (count < 0) break
                        buffer.write(chunk, 0, count)
                    }
                    require(buffer.size() <= 65536) { "Response exceeds limit" }
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
            )
        }
    }
}

data class Retry(val delayMillis: Long, val quarantine: Boolean)

fun classify(code: Int?, failures: Int, retryAfter: String? = null): Retry {
    val exponential = (1000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30000)
    val delta =
        if (code == 429 && retryAfter?.matches(Regex("[0-9]+")) == true)
            retryAfter.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000)
        else null
    return Retry(
        maxOf(exponential, delta ?: 0),
        code != null && code != 200 && code != 429 && code !in 500..599,
    )
}

// Single sender, independent from reporting/GNSS. One bounded request at a time;
// selection is repeated after every response so backlog cannot monopolize it.
class Sender(
    private val store: MessageStore,
    private val transport: Transport,
    private val clock: Clock,
    private val capture: suspend () -> Outbound,
) {
    private val mutex = Mutex()
    private var recovering = true
    private var currentAfter = 0L
    private var failures = 0
    private var waitUntil = 0L
    private var generation: Long? = null
    private var endpointBlocked = false

    suspend fun connectivityRestored() =
        mutex.withLock {
            recovering = true
            if (!endpointBlocked) waitUntil = 0
        }

    suspend fun step() = mutex.withLock { stepLocked() }

    private suspend fun stepLocked() {
        val state = store.state()
        if (generation != state.endpointGeneration) {
            generation = state.endpointGeneration
            recovering = true
            failures = 0
            waitUntil = 0
            endpointBlocked = false
            currentAfter = 0
        }
        if (
            state.endpoint.isEmpty() ||
                state.deliveryPaused ||
                endpointBlocked ||
                clock.elapsedMillis() < waitUntil
        )
            return
        if (recovering) {
            val newest = store.newest()
            if (
                newest == null ||
                    clock.wallMillis() - newest.capturedMillis !in
                        0 until state.config().effective_reporting_interval_s * 1000L
            )
                capture()
        }
        val row = store.next(clock.wallMillis(), currentAfter) ?: return
        try {
            val response = transport.post(state.endpoint, row.json)
            if (store.state().endpointGeneration != state.endpointGeneration) return
            if (response.code == 200) {
                require(response.json) { "ACK Content-Type must be application/json" }
                val receipt = Protocol.receipt(response.body, Protocol.decodeMessage(row.json))
                if (store.state().endpointGeneration != state.endpointGeneration) return
                store.accept(row, receipt, state.endpointGeneration)
                if (row.type != "sos") currentAfter = maxOf(currentAfter, row.sequence)
                failures = 0
                waitUntil = 0
                recovering = false
                return
            }
            failure(
                row,
                "Command returned HTTP ${response.code}",
                response.code,
                response.retryAfter,
                state.endpointGeneration,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(row, e.message ?: "Command unavailable", null, null, state.endpointGeneration)
        }
    }

    private suspend fun failure(
        row: Outbound,
        diagnostic: String,
        code: Int?,
        after: String?,
        generation: Long,
    ) {
        if (store.state().endpointGeneration != generation) return
        failures = (failures + 1).coerceAtMost(32)
        val retry = classify(code, failures, after)
        recovering = true
        val delay = retry.delayMillis.coerceAtMost(Long.MAX_VALUE - clock.elapsedMillis())
        waitUntil = clock.elapsedMillis() + delay
        val next =
            clock.wallMillis() + retry.delayMillis.coerceAtMost(Long.MAX_VALUE - clock.wallMillis())
        store.fail(row, diagnostic, next, retry.quarantine, generation)
        // These errors affect the configured receiver, not just one message.
        if (
            code in listOf(404, 405, 415, 426) ||
                (retry.quarantine && code !in listOf(400, 409, 413))
        ) {
            endpointBlocked = true
            store.pauseReceiver(diagnostic, generation)
        }
    }
}
