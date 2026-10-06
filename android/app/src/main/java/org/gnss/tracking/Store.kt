package org.gnss.tracking

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "installation")
data class Installation(
    @PrimaryKey val key: Int = 1,
    val deviceId: String = Protocol.newId(),
    val sequence: Long = 0,
    val partyId: String = "Party",
    val partyName: String = "Field team",
    val endpoint: String = "",
    val localInterval: Int = 10,
    val authority: String? = null,
    val version: Long = 0,
    val overrideSeconds: Int? = null,
    val tracking: Boolean = false,
    val lastAck: String? = null,
    val configError: String? = null,
    val operationalError: String? = null,
    val endpointGeneration: Long = 0,
    val deliveryPaused: Boolean = false,
) {
    fun config() = ConfigState(authority, version, overrideSeconds, localInterval)
}

@Entity(
    tableName = "outbox",
    indices =
        [
            Index(value = ["messageId"], unique = true),
            Index(value = ["deliveredAt", "quarantined", "sequence"]),
        ],
)
data class Outbound(
    @PrimaryKey val sequence: Long,
    val messageId: String,
    val type: String,
    val capturedMillis: Long,
    val json: String,
    val deliveredAt: String? = null,
    val quarantined: Boolean = false,
    val attempts: Int = 0,
    val nextAttemptMillis: Long = 0,
    val error: String? = null,
)

@Dao
interface TrackingDao {
    @Query("SELECT * FROM installation WHERE `key`=1") suspend fun state(): Installation?

    @Query("SELECT * FROM installation WHERE `key`=1") fun observeState(): Flow<Installation?>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun initialize(state: Installation)

    @Update suspend fun update(state: Installation)

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: Outbound)

    @Update suspend fun updateRow(row: Outbound)

    @Query("SELECT * FROM outbox WHERE sequence=:sequence")
    suspend fun row(sequence: Long): Outbound?

    @Query("SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL") fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL AND quarantined=1")
    fun blockedCount(): Flow<Int>

    @Query(
        "SELECT error FROM outbox WHERE deliveredAt IS NULL AND quarantined=1 ORDER BY sequence LIMIT 1"
    )
    fun blockedError(): Flow<String?>

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='sos' AND nextAttemptMillis<=:now ORDER BY sequence LIMIT 1"
    )
    suspend fun sos(now: Long): Outbound?

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type!='sos' ORDER BY sequence DESC LIMIT 1"
    )
    suspend fun newest(): Outbound?

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND nextAttemptMillis<=:now ORDER BY sequence LIMIT 1"
    )
    suspend fun oldest(now: Long): Outbound?

    @Query(
        "UPDATE outbox SET nextAttemptMillis=0, quarantined=0, error=NULL WHERE deliveredAt IS NULL"
    )
    suspend fun releasePending()

    @Query("UPDATE outbox SET nextAttemptMillis=0 WHERE deliveredAt IS NULL AND quarantined=0")
    suspend fun resetTransientRetryTiming()

    @Query("SELECT * FROM outbox ORDER BY sequence") suspend fun all(): List<Outbound>
}

@Database(entities = [Installation::class, Outbound::class], version = 1, exportSchema = true)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun dao(): TrackingDao

    companion object {
        fun open(context: Context) =
            Room.databaseBuilder(context, TrackingDatabase::class.java, "tracking.db").build()
    }
}

interface MessageStore {
    suspend fun state(): Installation

    suspend fun snapshot(now: Long, fix: Fix?, health: Health): Outbound

    suspend fun next(now: Long, currentAfter: Long = 0): Outbound?

    suspend fun newest(): Outbound?

    suspend fun resetTransientRetryTiming()

    suspend fun accept(row: Outbound, receipt: Receipt, generation: Long)

    suspend fun fail(
        row: Outbound,
        error: String,
        nextAttempt: Long,
        quarantine: Boolean,
        generation: Long,
    )

    suspend fun pauseReceiver(error: String, generation: Long = 0)
}

class Repository(val db: TrackingDatabase, private val clock: Clock = SystemClock) : MessageStore {
    val dao = db.dao()

    override suspend fun state(): Installation =
        db.withTransaction { dao.state() ?: Installation().also { dao.initialize(it) } }

    suspend fun edit(transform: (Installation) -> Installation) =
        db.withTransaction {
            val old = state()
            val updated = transform(old)
            require(updated.deviceId == old.deviceId && updated.sequence == old.sequence) {
                "Identity is immutable"
            }
            dao.update(updated)
        }

    override suspend fun snapshot(now: Long, fix: Fix?, health: Health): Outbound =
        db.withTransaction {
            val state = state()
            check(state.sequence < MAX_WIRE_INTEGER) {
                "Sequence exhausted; new enrollment identity required"
            }
            val sequence = state.sequence + 1
            val id = Protocol.newId()
            val message =
                Message(
                    type = if (fix == null) "status" else "location",
                    device_id = state.deviceId,
                    party = Party(state.partyId, state.partyName),
                    message_id = id,
                    sequence = sequence,
                    captured_at = utc(now),
                    config_state = state.config(),
                    health = health,
                    fix = fix,
                )
            val row = Outbound(sequence, id, message.type, now, Protocol.encode(message))
            dao.update(state.copy(sequence = sequence))
            dao.insert(row)
            row // Returned only after transaction commits. Transport never sees unsaved data.
        }

    override suspend fun newest() = dao.newest()

    override suspend fun resetTransientRetryTiming() = dao.resetTransientRetryTiming()

    override suspend fun next(now: Long, currentAfter: Long): Outbound? {
        dao.sos(now)?.let {
            return it
        }
        val current = dao.newest()
        if (current != null && current.sequence > currentAfter && current.nextAttemptMillis <= now)
            return current
        return dao.oldest(now)
    }

    override suspend fun accept(row: Outbound, receipt: Receipt, generation: Long) =
        db.withTransaction {
            val current = dao.row(row.sequence) ?: error("Missing durable message")
            require(
                receipt.message == current.messageId &&
                    receipt.sequence == current.sequence &&
                    receipt.device == Protocol.decodeMessage(current.json).device_id
            ) {
                "ACK identity mismatch"
            }
            if (current.deliveredAt != null) return@withTransaction
            val state = state()
            // Ignore late replies from a receiver edited while the request was in flight.
            if (state.endpointGeneration != generation) return@withTransaction
            var updated = state.copy(lastAck = utc(clock.wallMillis()), operationalError = null)
            when (val decision = receipt.config?.let { applyConfig(state.config(), it) }) {
                is ConfigDecision.Applied ->
                    updated =
                        updated.copy(
                            authority = decision.state.authority_id,
                            version = decision.state.version,
                            overrideSeconds = decision.state.reporting_interval_override_s,
                            configError = null,
                        )
                is ConfigDecision.Error -> updated = updated.copy(configError = decision.reason)
                ConfigDecision.NoChange -> {
                    // Only an equal, matching snapshot resolves a previous visible error.
                    if (receipt.config?.version == state.version)
                        updated = updated.copy(configError = null)
                }
                null ->
                    updated =
                        updated.copy(
                            configError = receipt.configError ?: "Invalid Command configuration"
                        )
            }
            dao.update(updated)
            dao.updateRow(current.copy(deliveredAt = receipt.receivedAt, error = null))
        }

    override suspend fun fail(
        row: Outbound,
        error: String,
        nextAttempt: Long,
        quarantine: Boolean,
        generation: Long,
    ) =
        db.withTransaction {
            if (state().endpointGeneration != generation) return@withTransaction
            val current = dao.row(row.sequence) ?: error("Missing durable message")
            if (current.deliveredAt == null)
                dao.updateRow(
                    current.copy(
                        attempts = (current.attempts + 1).coerceAtMost(Int.MAX_VALUE - 1),
                        nextAttemptMillis = nextAttempt,
                        quarantined = quarantine,
                        error = error,
                    )
                )
            dao.update(state().copy(operationalError = error))
        }

    override suspend fun pauseReceiver(error: String, generation: Long) {
        edit {
            if (it.endpointGeneration == generation)
                it.copy(deliveryPaused = true, operationalError = error)
            else it
        }
    }

    suspend fun retryDelivery() =
        db.withTransaction {
            val old = state()
            dao.update(
                old.copy(
                    deliveryPaused = false,
                    operationalError = null,
                    endpointGeneration = old.endpointGeneration + 1,
                )
            )
            dao.releasePending() // Operator action only; identity and JSON remain unchanged.
        }

    suspend fun settings(
        partyId: String,
        partyName: String,
        endpoint: String,
        localInterval: Int,
        reenroll: Boolean = false,
    ) =
        db.withTransaction {
            require(validLabel(partyId) && validLabel(partyName)) {
                "Party labels must contain 1–80 characters"
            }
            require(validInterval(localInterval)) {
                "Reporting interval must be 10–86400 seconds, in steps of 10"
            }
            if (endpoint.isNotEmpty()) Endpoint.validate(endpoint)
            val old = state()
            val receiverChanged = old.endpoint != endpoint || reenroll
            dao.update(
                old.copy(
                    partyId = partyId,
                    partyName = partyName,
                    endpoint = endpoint,
                    localInterval = localInterval,
                    authority = if (reenroll) null else old.authority,
                    version = if (reenroll) 0 else old.version,
                    overrideSeconds = if (reenroll) null else old.overrideSeconds,
                    configError = if (reenroll) null else old.configError,
                    endpointGeneration = old.endpointGeneration + if (receiverChanged) 1 else 0,
                    operationalError = null,
                    deliveryPaused = if (receiverChanged) false else old.deliveryPaused,
                )
            )
            if (receiverChanged) dao.releasePending()
        }
}
