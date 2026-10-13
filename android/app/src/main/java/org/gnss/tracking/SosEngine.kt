package org.gnss.tracking

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SosTrigger {
    SCREEN,
    VOLUME_UP,
}

data class SosSave(val row: Outbound, val created: Boolean)

// Only this engine creates SOS semantics. Adapters never allocate a sequence or transmit.
class SosEngine(
    private val repository: Repository,
    private val latest: LatestLocation,
    private val health: () -> Health,
    private val clock: Clock = SystemClock,
    private val evidence: (DiagnosticEvent, SosEvidence) -> Unit = { _, _ -> },
    private val beforeSave: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    private fun record(kind: DiagnosticEvent, value: SosEvidence) {
        runCatching { evidence(kind, value) }
    }

    suspend fun activate(
        trigger: SosTrigger,
        triggered: Long = clock.wallMillis(),
        elapsed: Long = clock.elapsedMillis(),
    ): SosSave {
        val captured = clock.wallMillis()
        val id = Protocol.newId()
        val ref = sosReference(id)
        record(DiagnosticEvent.SOS_TRIGGER_DETECTED, SosEvidence(ref, trigger))
        // Snapshot immediately; never wait for GNSS, the sender or a reporting interval.
        val fix = latest.sosFix()
        val snapshot =
            runCatching(health).getOrDefault(Health()).copy(gnss_status = latest.status())
        beforeSave() // App startup inventory completes before a new event can be saved.
        return mutex.withLock {
            try {
                val saved = repository.saveSos(id, triggered, captured, fix, snapshot)
                if (saved.created) {
                    record(
                        DiagnosticEvent.SOS_SAVED_LOCALLY,
                        SosEvidence(
                            ref,
                            trigger,
                            durationMs = (clock.elapsedMillis() - elapsed).coerceAtLeast(0),
                        ),
                    )
                    record(DiagnosticEvent.SOS_PRIORITY_PLACED, SosEvidence(ref, trigger))
                    if (snapshot.wifi_connected == false)
                        record(DiagnosticEvent.SOS_NETWORK_UNAVAILABLE, SosEvidence(ref, trigger))
                } else
                    record(
                        DiagnosticEvent.SOS_DEBOUNCED,
                        SosEvidence(sosReference(saved.row.messageId), trigger),
                    )
                saved
            } catch (e: Exception) {
                record(DiagnosticEvent.SOS_SAVE_FAILED, SosEvidence(ref, trigger))
                throw e
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 3000L
    }
}

// Bounded opaque correlation key, shared with Command exports. No device/Party/position.
fun sosReference(id: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

data class SosEvidence(
    val eventRef: String? = null,
    val trigger: SosTrigger? = null,
    val durationMs: Long? = null,
    val count: Int? = null,
    val competingReports: Int? = null,
)

// Three distinct short press/release pairs in 1.5 s. Holds/autorepeat and mixed keys reset.
// The Activity opts in explicitly and still passes every key to ordinary volume handling.
class TripleVolumeUp(private val evidence: (Int) -> Unit = {}) {
    private fun record(count: Int) {
        runCatching { evidence(count) }
    }

    private var started = 0L
    private var down: Long? = null
    private var count = 0

    fun reset() {
        down = null
        count = 0
        started = 0
    }

    fun key(
        up: Boolean,
        pressed: Boolean,
        repeat: Int,
        now: Long,
        canceled: Boolean = false,
    ): Boolean {
        if (!up || canceled || repeat != 0) {
            reset()
            record(0)
            return false
        }
        if (pressed) {
            if (down != null) {
                reset()
                record(0)
                return false
            }
            if (count == 0 || now - started !in 0..1500) {
                count = 0
                started = now
            }
            down = now
            return false
        }
        val at = down ?: return false
        down = null
        if (now - at !in 0..500 || now - started !in 0..1500) {
            reset()
            record(0)
            return false
        }
        count++
        record(count)
        if (count != 3) return false
        reset()
        return true
    }
}

fun Outbound.sosDescription(wifi: Boolean?, active: Boolean): String =
    when {
        deliveredAt != null -> "Received by Command at $deliveredAt"
        quarantined ->
            "Saved on phone — delivery rejected; correct the error and Retry saved messages"
        !active -> "Saved on phone — open app or Start Tracking to send"
        wifi == false -> "Saved on phone — waiting for Wi-Fi"
        wifi == null -> "Saved on phone — Wi-Fi status unavailable; awaiting Command receipt"
        attempts > 0 -> "Saved on phone — retrying; Command receipt not confirmed"
        else -> "Saved on phone — awaiting Command receipt"
    }
