package org.gnss.tracking

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "command_journal")
data class CommandRecord(
    @PrimaryKey val id: String,
    val authority: String,
    val device: String,
    val enrollment: String,
    val operation: String,
    val ordering: Long,
    val received: Long,
    val trusted: Boolean = true,
    val state: String = "received",
    val reason: String = "Received durably; authenticated",
    val effective: String = "",
    val attempted: Boolean = false,
    val revision: Long = 1,
    val confirmed: Boolean = false,
    val events: String = "",
    val syncThrough: Long = 0,
    val version: Long = 1,
    val created: Long = 0,
    val expires: Long = 0,
    val priority: String = "normal",
    val requested: String = "",
    val attemptedAt: Long = 0,
    val completedAt: Long = 0,
    val confirmedAt: Long = 0,
) {
    fun result() = CommandResult(id, revision, state, reason, effective, received, attempted)

    fun event(at: Long, text: String) =
        copy(
            events =
                (events.lines().takeLast(23) + "${utc(at)} · $text")
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
        )
}

@Entity(tableName = "command_watermark")
data class CommandWatermark(@PrimaryKey val token: String, val ordering: Long)

data class CommandResult(
    val id: String,
    val revision: Long,
    val state: String,
    val reason: String,
    val effective: String,
    val received: Long,
    val attempted: Boolean,
)

data class ControlCredential(
    val authority: String,
    val device: String,
    val enrollment: String,
    val key: String,
)

data class ControlEnvelope(
    val device: String,
    val enrollment: String,
    val data: String,
    val mac: String,
)

object ControlCrypto {
    private val hex = "0123456789abcdef"

    fun bytesHex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun hash(value: String) =
        bytesHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    fun mac(
        key: String,
        direction: String,
        device: String,
        enrollment: String,
        data: String,
    ): String {
        require(key.matches(Regex("[0-9a-f]{64}")))
        val bytes = ByteArray(32) { key.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        return bytesHex(
            mac.doFinal(
                "gnss-control-v1\n$direction\n$device\n$enrollment\n$data"
                    .toByteArray(Charsets.UTF_8)
            )
        )
    }

    fun parseCredential(value: String): ControlCredential {
        val parts = value.trim().split(':')
        require(parts.size == 5 && parts[0] == "gnss1") {
            "Use the credential shown by the local Command console"
        }
        parts.subList(1, 4).forEach { require(uuid(it)) { "Invalid credential identity" } }
        require(parts[4].matches(Regex("[0-9a-f]{64}"))) { "Invalid credential key" }
        return ControlCredential(parts[1], parts[2], parts[3], parts[4])
    }

    fun uuid(s: String) =
        s.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
}

// Android Keystore AES-GCM protects the manually provisioned per-phone PSK at
// rest. Neither key nor encrypted credential belongs in Room or diagnostic ZIPs.
class ControlKeyStore(context: android.content.Context) {
    private val prefs =
        context.getSharedPreferences("command-credential", android.content.Context.MODE_PRIVATE)

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("gnss-command-v1", null) as? javax.crypto.SecretKey)?.let {
            return it
        }
        val generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                    "gnss-command-v1",
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes("GCM")
                .setEncryptionPaddings("NoPadding")
                .build()
        )
        return generator.generateKey()
    }

    fun save(value: ControlCredential) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(Gson().toJson(value).toByteArray(Charsets.UTF_8))
        check(
            prefs
                .edit()
                .putString(
                    "sealed",
                    java.util.Base64.getEncoder().encodeToString(cipher.iv + bytes),
                )
                .commit()
        )
    }

    fun load(): ControlCredential? =
        runCatching {
                val bytes =
                    java.util.Base64.getDecoder()
                        .decode(prefs.getString("sealed", null) ?: return null)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    key(),
                    javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                Gson()
                    .fromJson(
                        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8),
                        ControlCredential::class.java,
                    )
            }
            .getOrNull()
}

class CommandControl(
    private val repository: Repository,
    private val transport: Transport,
    private val credential: () -> ControlCredential?,
    private val adaptive: (Boolean, String) -> PersistencePolicy,
    private val sync: suspend () -> Unit,
    private val tracking: suspend (Boolean) -> Pair<String, String>,
    private val adaptiveFallback: () -> String? = { null },
    private val clock: Clock = SystemClock,
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private var next = 0L
    private var failures = 0
    private val dao
        get() = repository.dao

    private val availabilityState =
        kotlinx.coroutines.flow.MutableStateFlow(
            "Remote control unavailable: explicitly enroll in Settings"
        )
    val updates: kotlinx.coroutines.flow.StateFlow<String>
        get() = availabilityState

    var availability: String
        get() = availabilityState.value
        private set(value) {
            availabilityState.value = value
        }

    suspend fun bind(value: ControlCredential, save: () -> Unit) =
        mutex.withLock {
            require(repository.state().deviceId == value.device) {
                "Credential targets a different phone; compare its identity in Command"
            }
            save()
            repository.edit {
                it.copy(
                    controlRequired = true,
                    controlAuthority = value.authority,
                    controlEnrollment = value.enrollment,
                    authority = value.authority,
                    version = 0,
                    overrideSeconds = null,
                    configError = null,
                    endpointGeneration = it.endpointGeneration + 1,
                )
            }
            next = 0
            availability = "Enrolled; awaiting authenticated Command"
        }

    suspend fun disable() =
        mutex.withLock {
            repository.edit {
                it.copy(
                    controlAuthority = null,
                    controlEnrollment = null,
                    endpointGeneration = it.endpointGeneration + 1,
                )
            }
            availability = "Remote control disabled locally; secure configuration still required"
        }

    suspend fun changeSettings(block: suspend () -> Unit) = mutex.withLock { block() }

    suspend fun step() {
        var flushStopResult = false
        stepOnce(false) { flushStopResult = true }
        // Stop may end the only legal sending lifetime while the Activity is
        // hidden. Use this same bounded conversation once more before returning,
        // rather than waiting for a scheduler that was just stopped. No wake-up,
        // listener, service or continuing timer is created.
        if (flushStopResult) stepOnce(true) {}
    }

    private suspend fun stepOnce(immediate: Boolean, stopped: () -> Unit) =
        mutex.withLock {
            if (!immediate && clock.elapsedMillis() < next) return@withLock
            val state = repository.state()
            val key = credential()
            if (key == null) {
                availability = "Remote control unavailable: secure credential missing or unreadable"
                return@withLock
            }
            if (
                state.controlAuthority != key.authority ||
                    state.controlEnrollment != key.enrollment ||
                    state.deviceId != key.device ||
                    state.endpoint.isBlank() ||
                    !transport.wifiAvailable()
            ) {
                availability =
                    if (!transport.wifiAvailable()) "Offline: waiting for Wi-Fi; results retained"
                    else "Remote control unavailable: local authorization or endpoint required"
                return@withLock
            }
            // A high priority emergency must retain the next network opportunity.
            if (dao.pendingSosExists()) return@withLock
            next = clock.elapsedMillis() + 5000
            var receivedEnvelope = false
            var sentResults = emptyList<CommandRecord>()
            try {
                refreshSync(key.enrollment)
                val pending = dao.pendingCommands(key.enrollment)
                sentResults = pending
                for (row in pending) dao.updateCommand(
                    row.event(clock.wallMillis(), "Result send attempted; custody unconfirmed")
                )
                val nonce =
                    ControlCrypto.bytesHex(
                        ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    )
                val request =
                    gson.toJson(
                        mapOf(
                            "version" to 1,
                            "authority" to key.authority,
                            "nonce" to nonce,
                            "at" to clock.wallMillis(),
                            "results" to pending.map { it.result() },
                            "results_only" to immediate,
                        )
                    )
                val data =
                    java.util.Base64.getEncoder()
                        .encodeToString(request.toByteArray(Charsets.UTF_8))
                val env =
                    ControlEnvelope(
                        key.device,
                        key.enrollment,
                        data,
                        ControlCrypto.mac(key.key, "phone", key.device, key.enrollment, data),
                    )
                val response =
                    transport.control(state.endpoint, gson.toJson(env)) ?: return@withLock
                if (response.code != 200 || !response.json) {
                    availability =
                        "Remote control unavailable: authentication denied or unsupported receiver (${response.code})"
                    throw IllegalStateException("unavailable")
                }
                receivedEnvelope = true
                val outer = Protocol.parse(response.body)
                val reply = gson.fromJson(outer, ControlEnvelope::class.java)
                val expected =
                    ControlCrypto.mac(key.key, "command", key.device, key.enrollment, reply.data)
                if (
                    reply.device != key.device ||
                        reply.enrollment != key.enrollment ||
                        !MessageDigest.isEqual(expected.toByteArray(), reply.mac.toByteArray())
                ) {
                    denied("invalid_signature")
                    throw IllegalArgumentException("invalid signature")
                }
                val body =
                    Protocol.parse(
                        String(java.util.Base64.getDecoder().decode(reply.data), Charsets.UTF_8)
                    )
                if (
                    body["version"].toString() != "1" ||
                        body["authority"].asString != key.authority ||
                        body["nonce"].asString != nonce ||
                        kotlin.math.abs(body["at"].asLong - clock.wallMillis()) > 60000
                ) {
                    denied("authority_or_freshness_mismatch")
                    throw IllegalArgumentException("invalid authority or freshness")
                }
                val commands = body["commands"].asJsonArray
                require(commands.size() <= 8)
                require(body["confirmed"].isJsonObject)
                receivedEnvelope = false // Later storage/effect failures are not forged responses.
                // Endpoint edits/re-enrollment invalidate in-flight authenticated replies.
                if (repository.state().endpointGeneration != state.endpointGeneration)
                    return@withLock
                repository.db.withTransaction {
                    val confirmations = body["confirmed"].asJsonObject
                    pending.forEach { row ->
                        if (
                            confirmations[row.id]?.asString ==
                                ControlCrypto.hash(gson.toJson(row.result()))
                        ) {
                            val current = dao.command(row.id)
                            if (current?.revision == row.revision)
                                dao.updateCommand(
                                    current
                                        .copy(confirmed = true, confirmedAt = clock.wallMillis())
                                        .event(
                                            clock.wallMillis(),
                                            "Result delivery confirmed by authenticated Command",
                                        )
                                )
                        }
                    }
                }
                for (command in commands) {
                    try {
                        receive(command.asJsonObject, key, body["at"].asLong)
                        if (command.asJsonObject["operation"]?.asString == "tracking_stop")
                            stopped()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: IllegalArgumentException) {
                        denied("invalid_instruction_metadata")
                    }
                }
                dao.pruneCommands()
                failures = 0
                availability = "Authenticated Command · ${utc(clock.wallMillis())}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (receivedEnvelope) denied("invalid_or_untrusted_control_response")
                for (row in sentResults) {
                    val current = dao.command(row.id)
                    if (current != null && !current.confirmed)
                        dao.updateCommand(
                            current.event(
                                clock.wallMillis(),
                                "Result delivery uncertain; retry retained",
                            )
                        )
                }
                failures = (failures + 1).coerceAtMost(6)
                next = clock.elapsedMillis() + (1000L shl failures).coerceAtMost(30000)
                if (!availability.startsWith("Remote control unavailable"))
                    availability = "Command unreachable or unverified; results retained for retry"
            }
        }

    private suspend fun denied(reason: String) {
        // Security events have local IDs; no attacker payload, signature or key is retained.
        if (
            (dao.commandHistory()
                .firstOrNull { !it.trusted }
                ?.let { clock.wallMillis() - it.received < 5000 }) == true
        )
            return
        val state = repository.state()
        dao.insertCommand(
            CommandRecord(
                Protocol.newId(),
                state.controlAuthority ?: "unknown",
                state.deviceId,
                state.controlEnrollment ?: "",
                "security_denied",
                0,
                clock.wallMillis(),
                trusted = false,
                state = "rejected",
                reason = reason,
                events = "${utc(clock.wallMillis())} · Rejected: $reason",
            )
        )
        dao.pruneCommands()
    }

    private suspend fun receive(ins: JsonObject, key: ControlCredential, serverAt: Long) {
        val metadataValid =
            runCatching {
                    for (name in listOf("id", "authority", "device", "enrollment")) require(
                        ControlCrypto.uuid(ins[name].asString)
                    )
                    require(ins["operation"].asString.matches(Regex("[a-z_]{1,40}")))
                    for (name in listOf("version", "ordering", "created", "expires")) {
                        val n = ins[name]
                        require(
                            n.asJsonPrimitive.isNumber &&
                                n.asString.matches(Regex("[1-9][0-9]*")) &&
                                n.asLong in 1..MAX_WIRE_INTEGER
                        )
                    }
                    require(ins["payload"].isJsonObject)
                    require(
                        ins["disposition"].asString in
                            listOf(
                                "queued",
                                "sent_unknown",
                                "received",
                                "cancel_requested_unknown",
                                "cancelled",
                            )
                    )
                }
                .isSuccess
        if (!metadataValid) {
            denied("malformed_instruction_metadata")
            return
        }
        val id = ins["id"].asString
        require(ControlCrypto.uuid(id))
        if (
            ins["authority"].asString != key.authority ||
                ins["device"].asString != key.device ||
                ins["enrollment"].asString != key.enrollment
        ) {
            denied("invalid_authority_or_target")
            return
        }
        val ordering = ins["ordering"].asLong
        val operation = ins["operation"].asString
        require(
            operation in
                listOf("reporting", "adaptive", "sync", "tracking_start", "tracking_stop") ||
                operation.length <= 40
        )
        val old = dao.command(id)
        if (old != null && !(old.state == "received" && !old.attempted)) {
            dao.updateCommand(
                old.event(clock.wallMillis(), "Duplicate ignored; persisted result retained")
            )
            // Crash between attempt and durable result: do not repeat a side effect.
            if (old.state == "received")
                finish(
                    old,
                    "unknown",
                    "Process interrupted after durable receipt; no repeated effect; issue a new instruction if needed",
                )
            return
        }
        if (old == null && dao.unresolvedCommands() >= 512) {
            denied("command_storage_pressure")
            return
        }
        var row =
            old
                ?: CommandRecord(
                        id,
                        key.authority,
                        key.device,
                        key.enrollment,
                        operation,
                        ordering,
                        clock.wallMillis(),
                        version = ins["version"].asLong,
                        created = ins["created"].asLong,
                        expires = ins["expires"].asLong,
                        priority = "normal",
                        requested = safeRequested(operation, ins["payload"].asJsonObject),
                    )
                    .event(clock.wallMillis(), "Received durably · Authenticated")
        repository.db.withTransaction {
            if (old == null) dao.insertCommand(row)
            else {
                row =
                    row.event(
                        clock.wallMillis(),
                        "Resuming durable receipt; no prior execution attempt",
                    )
                dao.updateCommand(row)
            }
        }
        if (ins["version"].asLong != 1L) {
            finish(row, "unsupported", "Unsupported operation version")
            return
        }
        if (
            ins["expires"].asLong <= maxOf(clock.wallMillis(), serverAt) ||
                ins["created"].asLong > clock.wallMillis() + 60000
        ) {
            finish(row, "expired", "Expired command; no execution")
            return
        }
        if (ins["disposition"].asString in listOf("cancelled", "cancel_requested_unknown")) {
            finish(row, "cancelled", "Cancelled before execution")
            return
        }
        if (
            operation !in listOf("reporting", "adaptive", "sync", "tracking_start", "tracking_stop")
        ) {
            finish(row, "unsupported", "Unsupported instruction")
            return
        }
        val validPayload =
            runCatching {
                    val payload = ins["payload"].asJsonObject
                    when (operation) {
                        "reporting" -> {
                            require(payload.keySet() == setOf("seconds", "revision"))
                            require(payload["revision"].asLong > 0)
                            val n = payload["seconds"]
                            require(
                                n.isJsonNull ||
                                    (n.asString.matches(Regex("[0-9]+")) && validInterval(n.asInt))
                            )
                        }
                        "adaptive" ->
                            require(
                                payload.keySet() == setOf("enabled") &&
                                    payload["enabled"].asJsonPrimitive.isBoolean
                            )
                        else -> require(payload.keySet().isEmpty())
                    }
                }
                .isSuccess
        if (!validPayload) {
            finish(row, "rejected", "Invalid payload for supported operation version")
            return
        }
        val currentBinding = repository.state()
        if (
            currentBinding.controlAuthority != key.authority ||
                currentBinding.controlEnrollment != key.enrollment
        ) {
            finish(row, "rejected", "Local authorization changed before execution")
            return
        }
        val token =
            key.enrollment + ":" + if (operation.startsWith("tracking_")) "tracking" else operation
        if (ordering <= (dao.commandWatermark(token) ?: 0)) {
            finish(row, "superseded", "Stale ordering; newer instruction already received")
            return
        }
        try {
            repository.db.withTransaction {
                dao.commandWatermark(CommandWatermark(token, ordering))
                row =
                    row.copy(attempted = true, attemptedAt = clock.wallMillis())
                        .event(clock.wallMillis(), "Execution attempted")
                dao.updateCommand(row)
            }
        } catch (e: Exception) {
            dao.updateCommand(
                (dao.command(id) ?: row).event(
                    clock.wallMillis(),
                    "Execution staging storage failure; no effect attempted",
                )
            )
            throw e
        }
        try {
            when (operation) {
                "reporting" ->
                    repository.db.withTransaction {
                        val payload = ins["payload"].asJsonObject
                        val revision = payload["revision"].asLong
                        val seconds =
                            payload["seconds"].let { if (it.isJsonNull) null else it.asInt }
                        require(seconds == null || validInterval(seconds))
                        val state = repository.state()
                        if (revision <= state.version)
                            finish(
                                row,
                                "superseded",
                                "Configuration revision already effective or newer",
                            )
                        else {
                            repository.edit {
                                it.copy(
                                    authority = key.authority,
                                    version = revision,
                                    overrideSeconds = seconds,
                                    configError = null,
                                )
                            }
                            finish(
                                row,
                                "applied",
                                "Reporting interval applied prospectively",
                                "${seconds ?: state.localInterval} seconds; revision $revision; local ${state.localInterval}",
                            )
                        }
                    }
                "adaptive" -> {
                    val payload = ins["payload"].asJsonObject
                    require(
                        payload.keySet() == setOf("enabled") &&
                            payload["enabled"].asJsonPrimitive.isBoolean
                    )
                    val effective = adaptive(payload["enabled"].asBoolean, utc(clock.wallMillis()))
                    if (
                        effective.error != null || (effective.enabled && adaptiveFallback() != null)
                    ) {
                        finish(
                            row,
                            "blocked",
                            "Policy saved but dense safety fallback is active; inspect GNSS Diagnostics",
                            "Dense OFF; requested adaptive ${effective.enabled}",
                        )
                    } else
                        finish(
                            row,
                            "applied",
                            "Adaptive policy changed for future measurements",
                            if (effective.enabled) "ON; policy revision ${effective.revision}"
                            else "OFF; dense persistence",
                        )
                }
                "sync" -> {
                    row = row.copy(syncThrough = repository.state().sequence)
                    dao.updateCommand(row)
                    sync()
                    finish(
                        row,
                        "sync_started",
                        "Sync initiated through existing Sender; receipt is not completion",
                        "Saved history through sequence ${row.syncThrough}",
                    )
                }
                "tracking_start",
                "tracking_stop" -> {
                    val result = tracking(operation == "tracking_start")
                    finish(row, result.first, result.second)
                }
                else -> finish(row, "unsupported", "Unsupported instruction")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            finish(
                row,
                "failed",
                "Execution failed; inspect OS permissions, storage and local effective settings",
            )
        }
    }

    private fun safeRequested(operation: String, payload: JsonObject): String =
        runCatching {
                when (operation) {
                    "reporting" ->
                        "seconds=" +
                            (if (payload["seconds"].isJsonNull) "local default"
                            else payload["seconds"].asLong.toString()) +
                            "; revision=" +
                            payload["revision"].asLong
                    "adaptive" ->
                        if (payload["enabled"].asJsonPrimitive.isBoolean)
                            "enabled=" + payload["enabled"].asBoolean
                        else "invalid payload"
                    "sync",
                    "tracking_start",
                    "tracking_stop" -> "operation request"
                    else -> "unsupported; raw payload omitted"
                }
            }
            .getOrDefault("invalid payload; raw content omitted")

    private suspend fun finish(
        row: CommandRecord,
        state: String,
        reason: String,
        effective: String = "",
    ) {
        dao.updateCommand(
            row.copy(
                    state = state,
                    completedAt = clock.wallMillis(),
                    reason = reason,
                    effective = effective,
                    revision = row.revision + 1,
                    confirmed = false,
                    confirmedAt = 0,
                )
                .event(clock.wallMillis(), "$state · $reason · Result pending delivery")
        )
    }

    private suspend fun refreshSync(enrollment: String) {
        // Include confirmed starts too: progress is a new result revision requiring
        // its own authenticated custody confirmation.
        for (row in dao.activeSyncCommands(enrollment)) {
            val remaining = dao.syncRemaining(row.syncThrough)
            val value = "$remaining unresolved saved GNSS rows through sequence ${row.syncThrough}"
            if (remaining == 0L)
                finish(
                    row,
                    "sync_complete",
                    "All saved GNSS in the frozen sync scope durably acknowledged; Command reconstruction is separate",
                    value,
                )
            else if (row.effective != value)
                finish(
                    row,
                    "sync_partial",
                    "Delivery in progress or unresolved; originals retained",
                    value,
                )
        }
    }
}
