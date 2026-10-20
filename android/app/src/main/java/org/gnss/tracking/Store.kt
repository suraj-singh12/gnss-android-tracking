package org.gnss.tracking

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Stable persisted retry reason used by existing outboxes; never localize this value.
internal const val RATE_LIMIT_RETRY_ERROR = "Command HTTP 429"
// Seek delayed rows only: ready records must not be rewritten on every resume.
internal const val RESET_TRANSIENT_RETRY_SQL =
    "UPDATE outbox SET nextAttemptMillis=0 WHERE deliveredAt IS NULL AND quarantined=0 AND nextAttemptMillis>0 AND (error IS NULL OR error!='" +
        RATE_LIMIT_RETRY_ERROR +
        "')"

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
    val trackingSession: String? = null,
    val sessionStartedMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val observationSequence: Long = 0,
    @ColumnInfo(defaultValue = "0") val knownCollectionLoss: Long = 0,
    @ColumnInfo(defaultValue = "1") val controlRequired: Boolean = true,
    val controlAuthority: String? = null,
    val controlEnrollment: String? = null,
) {
    fun config() = ConfigState(authority, version, overrideSeconds, localInterval)
}

@Entity(
    tableName = "outbox",
    indices =
        [
            Index(value = ["messageId"], unique = true),
            Index(value = ["deliveredAt", "quarantined", "sequence"]),
            Index(value = ["deliveredAt", "quarantined", "nextAttemptMillis"]),
            Index(value = ["observedMillis", "sequence"]),
            Index(value = ["trackingSession", "measurementKey"], unique = true),
            Index(value = ["deliveredAt", "type", "observedMillis"]),
            Index(
                value =
                    ["deliveredAt", "quarantined", "type", "trackingSession", "observationSequence"]
            ),
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
    @ColumnInfo(defaultValue = "0") val historical: Boolean = false,
    val observedMillis: Long? = null,
    val trackingSession: String? = null,
    val observationSequence: Long? = null,
    val measurementKey: String? = null,
)

@Entity(tableName = "tracking_sessions")
data class SessionRecord(
    @PrimaryKey val id: String,
    val startedMillis: Long,
    val endedMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val knownCollectionLoss: Long = 0,
    val sequence: Long = 0,
)

// One Room projection of the existing outbox; null lives in the observer, not SQL.
data class QueueCounts(
    val pending: Int,
    val gnss: Int,
    val sos: Int,
    val routine: Int,
    val blocked: Int,
    val deliveredGnss: Int,
    val deliveredSos: Int,
    val deliveredRoutine: Int,
)

@Entity(tableName = "outbox_summary")
data class OutboxSummary(@PrimaryKey val key: Int = 1, @Embedded val counts: QueueCounts)

@Dao
interface TrackingDao {
    @Query("SELECT * FROM command_journal ORDER BY confirmed ASC,received DESC LIMIT 1012")
    fun observeCommands(): Flow<List<CommandRecord>>

    @Query("SELECT * FROM command_journal ORDER BY confirmed ASC,received DESC LIMIT 1012")
    suspend fun commandHistory(): List<CommandRecord>

    @Query("SELECT * FROM command_journal WHERE id=:id")
    suspend fun command(id: String): CommandRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCommand(row: CommandRecord)

    @Update suspend fun updateCommand(row: CommandRecord)

    @Query(
        "SELECT * FROM command_journal WHERE enrollment=:enrollment AND confirmed=0 AND trusted=1 ORDER BY received LIMIT 32"
    )
    suspend fun pendingCommands(enrollment: String): List<CommandRecord>

    @Query(
        "SELECT * FROM command_journal WHERE enrollment=:enrollment AND state IN ('sync_started','sync_partial')"
    )
    suspend fun activeSyncCommands(enrollment: String): List<CommandRecord>

    @Query(
        "SELECT * FROM command_journal WHERE operation='alert' AND state IN ('output_attempted','alert_blocked')"
    )
    suspend fun activeAlerts(): List<CommandRecord>

    @Query("SELECT ordering FROM command_watermark WHERE token=:token")
    suspend fun commandWatermark(token: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun commandWatermark(row: CommandWatermark)

    @Query(
        "DELETE FROM command_journal WHERE id IN (SELECT id FROM command_journal WHERE (confirmed=1 AND state NOT IN ('received','output_attempted','alert_blocked')) OR trusted=0 ORDER BY received DESC LIMIT -1 OFFSET 500)"
    )
    suspend fun pruneCommands()

    @Query(
        "SELECT COUNT(*) FROM command_journal WHERE (confirmed=0 OR state IN ('received','output_attempted','alert_blocked')) AND trusted=1"
    )
    suspend fun unresolvedCommands(): Int

    @Query(
        "SELECT COUNT(*) FROM outbox WHERE type='location' AND sequence<=:through AND deliveredAt IS NULL"
    )
    suspend fun syncRemaining(through: Long): Long

    @Query("SELECT * FROM installation WHERE `key`=1") suspend fun state(): Installation?

    @Query("SELECT * FROM tracking_sessions WHERE id=:id")
    suspend fun session(id: String): SessionRecord?

    @Insert suspend fun insertSession(row: SessionRecord)

    @Update suspend fun updateSession(row: SessionRecord)

    @Query("SELECT * FROM installation WHERE `key`=1") fun observeState(): Flow<Installation?>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun initialize(state: Installation)

    @Update suspend fun update(state: Installation)

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: Outbound)

    @Update suspend fun updateRow(row: Outbound)

    // Two bounded seeks include unresolved/quarantined evidence without scanning history.
    @Query(
        "SELECT MIN(capturedMillis) FROM outbox WHERE sequence IN (SELECT MIN(sequence) FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 UNION ALL SELECT MIN(sequence) FROM outbox WHERE deliveredAt IS NULL AND quarantined=1)"
    )
    suspend fun oldestPendingCapturedMillis(): Long?

    @Query("SELECT * FROM outbox WHERE sequence=:sequence")
    suspend fun row(sequence: Long): Outbound?

    @Query("SELECT * FROM outbox WHERE messageId=:id") suspend fun byMessage(id: String): Outbound?

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE observationSequence IS NOT NULL LIMIT 1)")
    suspend fun nativeExists(): Boolean

    @Query(
        "SELECT * FROM outbox WHERE trackingSession=:session AND measurementKey=:measurement LIMIT 1"
    )
    suspend fun measurement(session: String, measurement: String): Outbound?

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='location' AND (observationSequence IS NULL OR sequence=(SELECT sequence FROM outbox WHERE observationSequence IS NOT NULL AND observedMillis>=:since AND observedMillis<=:until ORDER BY observedMillis DESC,sequence DESC LIMIT 1)) AND nextAttemptMillis<=:now AND observedMillis>=:since AND observedMillis<=:until ORDER BY capturedMillis DESC,sequence DESC LIMIT 1"
    )
    suspend fun newestNative(now: Long, since: Long, until: Long): Outbound?

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND nextAttemptMillis<=:now ORDER BY sequence LIMIT :limit"
    )
    suspend fun outstanding(now: Long, limit: Int): List<Outbound>

    @Query(
        "SELECT * FROM outbox WHERE trackingSession=:session AND deliveredAt IS NULL ORDER BY observationSequence"
    )
    suspend fun sessionPending(session: String): List<Outbound>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox WHERE deliveredAt IS NULL AND type='location' AND quarantined=0 LIMIT 1)"
    )
    suspend fun hasPendingHistory(): Boolean

    @Query(
        "SELECT COUNT(*) FROM outbox WHERE trackingSession=:session AND deliveredAt IS NULL AND quarantined=0"
    )
    suspend fun sessionPendingCount(session: String): Long

    @Query(
        "SELECT MIN(observationSequence) FROM outbox WHERE trackingSession=:session AND deliveredAt IS NULL AND quarantined=0"
    )
    suspend fun firstPendingSequence(session: String): Long?

    @Query(
        "SELECT observationSequence FROM outbox WHERE trackingSession=:session AND deliveredAt IS NULL AND quarantined=1 AND observationSequence IS NOT NULL ORDER BY observationSequence LIMIT 256"
    )
    suspend fun unresolvedSequences(session: String): List<Long>

    @Query(
        "SELECT COUNT(*) FROM outbox WHERE trackingSession=:session AND deliveredAt IS NULL AND quarantined=1"
    )
    suspend fun unresolvedCount(session: String): Long

    @Query(
        "SELECT * FROM outbox WHERE observationSequence IS NOT NULL AND deliveredAt IS NOT NULL ORDER BY sequence"
    )
    suspend fun deliveredObservations(): List<Outbound>

    @Query("SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL") fun pendingCount(): Flow<Int>

    @Query(
        """SELECT CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT pending FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL) END AS pending,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT gnss FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL AND type='location') END AS gnss,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT sos FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL AND type='sos') END AS sos,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT routine FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL AND type='status') END AS routine,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT blocked FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NULL AND quarantined=1) END AS blocked,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT deliveredGnss FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NOT NULL AND type='location') END AS deliveredGnss,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT deliveredSos FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NOT NULL AND type='sos') END AS deliveredSos,
CASE WHEN EXISTS(SELECT 1 FROM outbox_summary WHERE `key`=1) THEN (SELECT deliveredRoutine FROM outbox_summary WHERE `key`=1) ELSE (SELECT COUNT(*) FROM outbox WHERE deliveredAt IS NOT NULL AND type='status') END AS deliveredRoutine"""
    )
    fun queueCounts(): Flow<QueueCounts>

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
        "SELECT EXISTS(SELECT 1 FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='sos' LIMIT 1)"
    )
    suspend fun pendingSosExists(): Boolean

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type!='sos' AND historical=0 ORDER BY sequence DESC LIMIT 1"
    )
    suspend fun newest(): Outbound?

    @Query(
        "UPDATE outbox SET nextAttemptMillis=0, quarantined=0, error=NULL WHERE deliveredAt IS NULL"
    )
    suspend fun releasePending()

    @Query(RESET_TRANSIENT_RETRY_SQL) suspend fun resetTransientRetryTiming()

    @Query("SELECT * FROM outbox WHERE type='sos' ORDER BY sequence DESC LIMIT 1")
    suspend fun latestSos(): Outbound?

    @Query("SELECT * FROM outbox WHERE type='sos' ORDER BY sequence")
    suspend fun sosHistory(): List<Outbound>

    @Query("SELECT * FROM outbox WHERE type='sos' ORDER BY sequence DESC")
    fun observeSos(): Flow<List<Outbound>>

    @Query(
        "SELECT outbox.* FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='location' ORDER BY COALESCE((SELECT rowid FROM tracking_sessions WHERE id=outbox.trackingSession),0), COALESCE(observationSequence,sequence) LIMIT :limit"
    )
    suspend fun pendingLocations(limit: Int): List<Outbound>

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='location' AND trackingSession IS NULL ORDER BY sequence LIMIT 1"
    )
    suspend fun legacyHistory(): Outbound?

    @Query(
        "SELECT id FROM tracking_sessions WHERE EXISTS(SELECT 1 FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='location' AND trackingSession=tracking_sessions.id) ORDER BY rowid LIMIT 1"
    )
    suspend fun oldestPendingSession(): String?

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='location' AND trackingSession=:session ORDER BY observationSequence LIMIT :limit"
    )
    suspend fun pendingSessionBlock(session: String, limit: Int): List<Outbound>

    @Query(
        "SELECT * FROM outbox WHERE deliveredAt IS NULL AND quarantined=0 AND type='location' AND trackingSession=:session AND observationSequence>:after ORDER BY observationSequence LIMIT :limit"
    )
    suspend fun pendingSessionPage(session: String, after: Long, limit: Int): List<Outbound>

    @Query("SELECT * FROM outbox ORDER BY sequence") suspend fun all(): List<Outbound>
}

@Database(
    entities =
        [
            Installation::class,
            Outbound::class,
            SessionRecord::class,
            OutboxSummary::class,
            CommandRecord::class,
            CommandWatermark::class,
        ],
    version = 6,
    exportSchema = true,
)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun dao(): TrackingDao

    companion object {
        fun open(context: Context) =
            Room.databaseBuilder(context, TrackingDatabase::class.java, "tracking.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .addCallback(SUMMARY_CALLBACK)
                .build()

        private fun initializeSummary(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS outbox_summary (`key` INTEGER NOT NULL PRIMARY KEY,pending INTEGER NOT NULL,gnss INTEGER NOT NULL,sos INTEGER NOT NULL,routine INTEGER NOT NULL,blocked INTEGER NOT NULL,deliveredGnss INTEGER NOT NULL,deliveredSos INTEGER NOT NULL,deliveredRoutine INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO outbox_summary (`key`,pending,gnss,sos,routine,blocked,deliveredGnss,deliveredSos,deliveredRoutine) SELECT 1,COUNT(CASE WHEN deliveredAt IS NULL THEN 1 END),COUNT(CASE WHEN deliveredAt IS NULL AND type='location' THEN 1 END),COUNT(CASE WHEN deliveredAt IS NULL AND type='sos' THEN 1 END),COUNT(CASE WHEN deliveredAt IS NULL AND type='status' THEN 1 END),COUNT(CASE WHEN deliveredAt IS NULL AND quarantined=1 THEN 1 END),COUNT(CASE WHEN deliveredAt IS NOT NULL AND type='location' THEN 1 END),COUNT(CASE WHEN deliveredAt IS NOT NULL AND type='sos' THEN 1 END),COUNT(CASE WHEN deliveredAt IS NOT NULL AND type='status' THEN 1 END) FROM outbox"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS outbox_summary_insert AFTER INSERT ON outbox BEGIN UPDATE outbox_summary SET pending=pending + CASE WHEN NEW.deliveredAt IS NULL THEN 1 ELSE 0 END,gnss=gnss + CASE WHEN NEW.deliveredAt IS NULL AND NEW.type='location' THEN 1 ELSE 0 END,sos=sos + CASE WHEN NEW.deliveredAt IS NULL AND NEW.type='sos' THEN 1 ELSE 0 END,routine=routine + CASE WHEN NEW.deliveredAt IS NULL AND NEW.type='status' THEN 1 ELSE 0 END,blocked=blocked + CASE WHEN NEW.deliveredAt IS NULL AND NEW.quarantined=1 THEN 1 ELSE 0 END,deliveredGnss=deliveredGnss + CASE WHEN NEW.deliveredAt IS NOT NULL AND NEW.type='location' THEN 1 ELSE 0 END,deliveredSos=deliveredSos + CASE WHEN NEW.deliveredAt IS NOT NULL AND NEW.type='sos' THEN 1 ELSE 0 END,deliveredRoutine=deliveredRoutine + CASE WHEN NEW.deliveredAt IS NOT NULL AND NEW.type='status' THEN 1 ELSE 0 END WHERE `key`=1; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS outbox_summary_update AFTER UPDATE ON outbox BEGIN UPDATE outbox_summary SET pending=pending + CASE WHEN NEW.deliveredAt IS NULL THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NULL THEN 1 ELSE 0 END,gnss=gnss + CASE WHEN NEW.deliveredAt IS NULL AND NEW.type='location' THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NULL AND OLD.type='location' THEN 1 ELSE 0 END,sos=sos + CASE WHEN NEW.deliveredAt IS NULL AND NEW.type='sos' THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NULL AND OLD.type='sos' THEN 1 ELSE 0 END,routine=routine + CASE WHEN NEW.deliveredAt IS NULL AND NEW.type='status' THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NULL AND OLD.type='status' THEN 1 ELSE 0 END,blocked=blocked + CASE WHEN NEW.deliveredAt IS NULL AND NEW.quarantined=1 THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NULL AND OLD.quarantined=1 THEN 1 ELSE 0 END,deliveredGnss=deliveredGnss + CASE WHEN NEW.deliveredAt IS NOT NULL AND NEW.type='location' THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NOT NULL AND OLD.type='location' THEN 1 ELSE 0 END,deliveredSos=deliveredSos + CASE WHEN NEW.deliveredAt IS NOT NULL AND NEW.type='sos' THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NOT NULL AND OLD.type='sos' THEN 1 ELSE 0 END,deliveredRoutine=deliveredRoutine + CASE WHEN NEW.deliveredAt IS NOT NULL AND NEW.type='status' THEN 1 ELSE 0 END - CASE WHEN OLD.deliveredAt IS NOT NULL AND OLD.type='status' THEN 1 ELSE 0 END WHERE `key`=1; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS outbox_summary_delete AFTER DELETE ON outbox BEGIN UPDATE outbox_summary SET pending=pending - CASE WHEN OLD.deliveredAt IS NULL THEN 1 ELSE 0 END,gnss=gnss - CASE WHEN OLD.deliveredAt IS NULL AND OLD.type='location' THEN 1 ELSE 0 END,sos=sos - CASE WHEN OLD.deliveredAt IS NULL AND OLD.type='sos' THEN 1 ELSE 0 END,routine=routine - CASE WHEN OLD.deliveredAt IS NULL AND OLD.type='status' THEN 1 ELSE 0 END,blocked=blocked - CASE WHEN OLD.deliveredAt IS NULL AND OLD.quarantined=1 THEN 1 ELSE 0 END,deliveredGnss=deliveredGnss - CASE WHEN OLD.deliveredAt IS NOT NULL AND OLD.type='location' THEN 1 ELSE 0 END,deliveredSos=deliveredSos - CASE WHEN OLD.deliveredAt IS NOT NULL AND OLD.type='sos' THEN 1 ELSE 0 END,deliveredRoutine=deliveredRoutine - CASE WHEN OLD.deliveredAt IS NOT NULL AND OLD.type='status' THEN 1 ELSE 0 END WHERE `key`=1; END"
            )
        }

        val SUMMARY_CALLBACK =
            object : Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    initializeSummary(db)
                }
            }
        val MIGRATION_5_6 =
            object : androidx.room.migration.Migration(5, 6) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE installation ADD COLUMN controlRequired INTEGER NOT NULL DEFAULT 1"
                    )
                    db.execSQL("ALTER TABLE installation ADD COLUMN controlAuthority TEXT")
                    db.execSQL("ALTER TABLE installation ADD COLUMN controlEnrollment TEXT")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS command_journal (id TEXT NOT NULL PRIMARY KEY,authority TEXT NOT NULL,device TEXT NOT NULL,enrollment TEXT NOT NULL,operation TEXT NOT NULL,ordering INTEGER NOT NULL,received INTEGER NOT NULL,trusted INTEGER NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,effective TEXT NOT NULL,attempted INTEGER NOT NULL,revision INTEGER NOT NULL,confirmed INTEGER NOT NULL,events TEXT NOT NULL,syncThrough INTEGER NOT NULL,version INTEGER NOT NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL,priority TEXT NOT NULL,requested TEXT NOT NULL,attemptedAt INTEGER NOT NULL,completedAt INTEGER NOT NULL,confirmedAt INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS command_watermark (token TEXT NOT NULL PRIMARY KEY,ordering INTEGER NOT NULL)"
                    )
                }
            }
        val MIGRATION_4_5 =
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX index_outbox_deliveredAt_quarantined_nextAttemptMillis ON outbox(deliveredAt,quarantined,nextAttemptMillis)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_outbox_observedMillis_sequence ON outbox(observedMillis,sequence)"
                    )
                }
            }

        val MIGRATION_3_4 =
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    initializeSummary(db)
                }
            }

        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX index_outbox_deliveredAt_quarantined_type_trackingSession_observationSequence ON outbox(deliveredAt,quarantined,type,trackingSession,observationSequence)"
                    )
                }
            }

        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE outbox ADD COLUMN historical INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("ALTER TABLE outbox ADD COLUMN observedMillis INTEGER")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS tracking_sessions (id TEXT NOT NULL PRIMARY KEY,startedMillis INTEGER NOT NULL,endedMillis INTEGER,knownCollectionLoss INTEGER NOT NULL DEFAULT 0,sequence INTEGER NOT NULL)"
                    )
                    for (q in
                        listOf(
                            "ALTER TABLE installation ADD COLUMN trackingSession TEXT",
                            "ALTER TABLE installation ADD COLUMN sessionStartedMillis INTEGER",
                            "ALTER TABLE installation ADD COLUMN observationSequence INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE installation ADD COLUMN knownCollectionLoss INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE outbox ADD COLUMN trackingSession TEXT",
                            "ALTER TABLE outbox ADD COLUMN observationSequence INTEGER",
                            "ALTER TABLE outbox ADD COLUMN measurementKey TEXT",
                        )) db.execSQL(q)
                    db.execSQL(
                        "CREATE UNIQUE INDEX index_outbox_trackingSession_measurementKey ON outbox(trackingSession,measurementKey)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_outbox_deliveredAt_type_observedMillis ON outbox(deliveredAt,type,observedMillis)"
                    )
                    // Populate sort metadata without changing a single existing envelope.
                    db.query("SELECT sequence,json FROM outbox WHERE type='location'").use { rows ->
                        while (rows.moveToNext()) {
                            val observed =
                                runCatching {
                                        java.time.Instant.parse(
                                                Protocol.decodeMessage(rows.getString(1))
                                                    .fix!!
                                                    .observed_at
                                            )
                                            .toEpochMilli()
                                    }
                                    .getOrNull()
                            if (observed != null)
                                db.execSQL(
                                    "UPDATE outbox SET observedMillis=? WHERE sequence=?",
                                    arrayOf(observed, rows.getLong(0)),
                                )
                        }
                    }
                }
            }
    }
}

interface MessageStore {
    suspend fun state(): Installation

    suspend fun snapshot(now: Long, fix: Fix?, health: Health): Outbound

    suspend fun sos(now: Long): Outbound?

    suspend fun newest(): Outbound?

    suspend fun live(now: Long): Outbound? =
        newest()?.takeIf {
            it.type == "location" &&
                it.nextAttemptMillis <= now &&
                now - it.capturedMillis in 0..30000
        }

    suspend fun historical(now: Long, target: Int): List<Outbound>

    suspend fun historicalBounded(now: Long, target: Int, maximumRecords: Int): List<Outbound> =
        historical(now, target).take(maximumRecords)

    suspend fun routine(now: Long): Outbound? = null

    suspend fun routineBacklog(now: Long): Outbound? = routine(now)

    suspend fun hasNativeObservations(): Boolean = false

    suspend fun acceptBatch(rows: List<Outbound>, receipts: List<Receipt>, generation: Long) {
        rows.zip(receipts).forEach { (r, a) -> accept(r, a, generation) }
    }

    // Production overrides this inside the same Room transaction as the ACK writes.
    suspend fun applyBatch(
        rows: List<Outbound>,
        receipts: List<Receipt>,
        generation: Long,
    ): Boolean {
        if (state().endpointGeneration != generation) return false
        acceptBatch(rows, receipts, generation)
        return state().endpointGeneration == generation
    }

    suspend fun hasPendingHistory(): Boolean? = null

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
        saveSnapshot(now, fix, health, Protocol.newId(), false)

    suspend fun beginTracking(
        startedMillis: Long = clock.wallMillis(),
        resumeExisting: Boolean = true,
    ): TrackingSession =
        db.withTransaction {
            val old = state()
            if (resumeExisting && old.tracking && old.trackingSession != null)
                return@withTransaction TrackingSession(
                    old.trackingSession,
                    old.sessionStartedMillis!!,
                )
            old.trackingSession?.let { id ->
                dao.session(id)
                    ?.takeIf { it.endedMillis == null }
                    ?.let { dao.updateSession(it.copy(endedMillis = startedMillis)) }
            }
            val session = TrackingSession(Protocol.newId(), startedMillis)
            dao.insertSession(SessionRecord(session.id, session.startedMillis))
            dao.update(
                old.copy(
                    tracking = true,
                    trackingSession = session.id,
                    sessionStartedMillis = session.startedMillis,
                    observationSequence = 0,
                    knownCollectionLoss = 0,
                )
            )
            session
        }

    suspend fun endTracking() {
        db.withTransaction {
            val old = state()
            old.trackingSession?.let {
                dao.session(it)?.let { v ->
                    dao.updateSession(v.copy(endedMillis = clock.wallMillis()))
                }
            }
            dao.update(old.copy(tracking = false))
        }
    }

    suspend fun noteCollectionLoss(
        session: kotlinx.coroutines.Deferred<TrackingSession>? = null,
        amount: Long = 1,
    ) {
        require(amount > 0)
        val id = session?.await()?.id ?: state().trackingSession
        db.withTransaction {
            val old = state()
            if (id != null)
                dao.session(id)?.let { row ->
                    dao.updateSession(
                        row.copy(
                            knownCollectionLoss =
                                (row.knownCollectionLoss +
                                    amount.coerceAtMost(MAX_WIRE_INTEGER - row.knownCollectionLoss))
                        )
                    )
                }
            if (id == old.trackingSession)
                dao.update(
                    old.copy(
                        knownCollectionLoss =
                            (old.knownCollectionLoss +
                                amount.coerceAtMost(MAX_WIRE_INTEGER - old.knownCollectionLoss))
                    )
                )
        }
    }

    suspend fun saveObservation(value: CapturedObservation): Outbound {
        val session =
            value.session?.await()
                ?: state().let {
                    if (it.trackingSession == null) beginTracking()
                    else TrackingSession(it.trackingSession, it.sessionStartedMillis!!)
                }
        return db.withTransaction {
            val key = "${value.elapsedNanos}/${value.fix.observed_at}"
            dao.measurement(session.id, key)?.let {
                return@withTransaction it
            }
            val old = state()
            val sessionRow = dao.session(session.id) ?: error("Missing durable tracking session")
            val n = sessionRow.sequence + 1
            check(n <= MAX_WIRE_INTEGER)
            val identity =
                ObservationIdentity(
                    value.id,
                    session.id,
                    utc(session.startedMillis),
                    n,
                    value.elapsedNanos / 1000000,
                )
            val row =
                saveSnapshot(
                    value.capturedMillis,
                    value.fix,
                    value.health,
                    value.id,
                    true,
                    identity,
                    key,
                )
            dao.updateSession(sessionRow.copy(sequence = n))
            if (old.trackingSession == session.id) dao.update(state().copy(observationSequence = n))
            row
        }
    }

    private suspend fun saveSnapshot(
        now: Long,
        fix: Fix?,
        health: Health,
        id: String,
        historical: Boolean,
        observation: ObservationIdentity? = null,
        measurementKey: String? = null,
    ): Outbound =
        db.withTransaction {
            // Retry of an uncertain commit uses the original callback identity.
            dao.byMessage(id)?.let { existing ->
                require(
                    existing.historical == historical &&
                        Protocol.decodeMessage(existing.json).fix == fix
                ) {
                    "Observation identity conflict"
                }
                return@withTransaction existing
            }
            val state = state()
            check(state.sequence < MAX_WIRE_INTEGER) {
                "Sequence exhausted; new enrollment identity required"
            }
            val sequence = state.sequence + 1
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
                    observation = observation,
                    history_progress = if (observation == null) historyProgress(state) else null,
                )
            val row =
                Outbound(
                    sequence,
                    id,
                    message.type,
                    now,
                    Protocol.encode(message),
                    historical = historical,
                    observedMillis =
                        fix?.let { java.time.Instant.parse(it.observed_at).toEpochMilli() },
                    trackingSession = observation?.tracking_session_id,
                    observationSequence = observation?.observation_sequence,
                    measurementKey = measurementKey,
                )
            dao.update(state.copy(sequence = sequence))
            dao.insert(row)
            row // Returned only after transaction commits. Transport never sees unsaved data.
        }

    // One transaction owns debounce, identity allocation and the immutable emergency.
    // There is no SOS table or second outbox. Delivered emergencies remain retained.
    suspend fun saveSos(
        id: String,
        triggered: Long,
        now: Long,
        fix: Fix?,
        health: Health,
    ): SosSave =
        db.withTransaction {
            val previous = dao.latestSos()
            if (previous != null) {
                val at =
                    java.time.Instant.parse(
                            Protocol.decodeMessage(previous.json).sos!!.triggered_at
                        )
                        .toEpochMilli()
                // Async adapter handoffs can commit in reverse trigger order.
                if (triggered - at in (-SosEngine.DEBOUNCE_MS + 1) until SosEngine.DEBOUNCE_MS)
                    return@withTransaction SosSave(previous, false)
            }
            val state = state()
            check(state.sequence < MAX_WIRE_INTEGER) {
                "Sequence exhausted; new enrollment required"
            }
            val sequence = state.sequence + 1
            val message =
                Message(
                    type = "sos",
                    device_id = state.deviceId,
                    party = Party(state.partyId, state.partyName),
                    message_id = id,
                    sequence = sequence,
                    captured_at = utc(now),
                    config_state = state.config(),
                    health = health,
                    fix = fix,
                    sos = Sos(id, utc(triggered)),
                )
            val row = Outbound(sequence, id, "sos", now, Protocol.encode(message))
            dao.update(state.copy(sequence = sequence))
            dao.insert(row)
            SosSave(row, true)
        }

    private suspend fun historyProgress(state: Installation): HistoryProgress? {
        val session = state.trackingSession ?: return null
        return HistoryProgress(
            session,
            utc(state.sessionStartedMillis!!),
            state.observationSequence,
            dao.firstPendingSequence(session),
            dao.sessionPendingCount(session),
            dao.unresolvedSequences(session),
            state.knownCollectionLoss,
            utc(clock.wallMillis()),
            dao.unresolvedCount(session),
        )
    }

    override suspend fun hasNativeObservations() = dao.nativeExists()

    override suspend fun live(now: Long) = dao.newestNative(now, now - 30000, now + 5000)

    override suspend fun routine(now: Long) =
        dao.newest()?.takeIf { it.type == "status" && it.nextAttemptMillis <= now }

    override suspend fun routineBacklog(now: Long) =
        dao.outstanding(now, 128).firstOrNull { it.type == "status" }

    override suspend fun historical(now: Long, target: Int): List<Outbound> =
        historicalBounded(now, target, 128)

    override suspend fun historicalBounded(
        now: Long,
        target: Int,
        maximumRecords: Int,
    ): List<Outbound> {
        dao.legacyHistory()?.let {
            return if (it.nextAttemptMillis <= now) listOf(it) else emptyList()
        }
        val session = dao.oldestPendingSession() ?: return emptyList()
        val result = mutableListOf<Outbound>()
        var bytes = 40
        var expected: Long? = null
        var after = 0L
        val count = maximumRecords.coerceIn(1, 4096)
        while (result.size < count) {
            // Never fetch thousands of large envelopes just to select a small
            // encoded payload: at most 64 individual-v1-sized rows per page.
            val page =
                dao.pendingSessionPage(
                    session,
                    after,
                    if (target == 0) 1 else minOf(64, count - result.size),
                )
            if (page.isEmpty()) break
            for (row in page) {
                val sequence =
                    row.observationSequence ?: return if (result.isEmpty()) listOf(row) else result
                if (row.nextAttemptMillis > now || expected != null && sequence != expected)
                    return result
                val size = row.json.toByteArray(Charsets.UTF_8).size + 1
                if (result.isNotEmpty() && (target == 0 || bytes + size > target)) return result
                result.add(row)
                bytes += size
                expected = sequence + 1
                after = sequence
                if (target == 0 || result.size >= count) return result
            }
        }
        return result
    }

    override suspend fun acceptBatch(
        rows: List<Outbound>,
        receipts: List<Receipt>,
        generation: Long,
    ) {
        applyBatch(rows, receipts, generation)
    }

    override suspend fun hasPendingHistory() = dao.hasPendingHistory()

    override suspend fun applyBatch(
        rows: List<Outbound>,
        receipts: List<Receipt>,
        generation: Long,
    ): Boolean =
        db.withTransaction {
            require(rows.size == receipts.size)
            val initial = state()
            if (initial.endpointGeneration != generation) return@withTransaction false
            var state = initial
            for ((row, receipt) in rows.zip(receipts)) {
                val current = dao.row(row.sequence) ?: error("Missing durable message")
                require(
                    receipt.message == current.messageId &&
                        receipt.sequence == current.sequence &&
                        receipt.device == state.deviceId
                ) {
                    "ACK identity mismatch"
                }
                if (current.deliveredAt != null) continue
                var updated = state.copy(lastAck = utc(clock.wallMillis()), operationalError = null)
                when (
                    val decision =
                        if (state.controlRequired) ConfigDecision.NoChange
                        else receipt.config?.let { applyConfig(state.config(), it) }
                ) {
                    is ConfigDecision.Applied ->
                        updated =
                            updated.copy(
                                authority = decision.state.authority_id,
                                version = decision.state.version,
                                overrideSeconds = decision.state.reporting_interval_override_s,
                                configError = null,
                            )
                    is ConfigDecision.Error -> updated = updated.copy(configError = decision.reason)
                    ConfigDecision.NoChange ->
                        if (receipt.config?.version == state.version)
                            updated = updated.copy(configError = null)
                    null ->
                        updated =
                            updated.copy(
                                configError = receipt.configError ?: "Invalid Command configuration"
                            )
                }
                dao.updateRow(current.copy(deliveredAt = receipt.receivedAt, error = null))
                state = updated
            }
            if (state != initial) dao.update(state)
            true
        }

    override suspend fun newest() = dao.newest()

    override suspend fun resetTransientRetryTiming() = dao.resetTransientRetryTiming()

    override suspend fun sos(now: Long) = dao.sos(now)

    override suspend fun accept(row: Outbound, receipt: Receipt, generation: Long) {
        acceptBatch(listOf(row), listOf(receipt), generation)
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
                "Reporting interval must be 5–86400 seconds, in steps of 5"
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
                    controlAuthority = if (reenroll) null else old.controlAuthority,
                    controlEnrollment = if (reenroll) null else old.controlEnrollment,
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
