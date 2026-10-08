package org.gnss.tracking

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.AtomicFile
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.launch

// Local diagnostics only: no coordinates, labels, endpoint, device ID or wire fields.
data class SourceState(
    val registered: Boolean = false,
    val statusRegistered: Boolean = false,
    val registrations: Int = 0,
    val registeredAt: Long? = null,
    val lastLocationCallback: Long? = null,
    val lastGnssCallback: Long? = null,
    val locationCallbacks: Long = 0,
    val gnssCallbacks: Long = 0,
    val rejectedObservations: Long = 0,
    val lastRejection: String? = null,
    val engineRunning: Boolean? = null,
    val error: String? = null,
    val satellitesTotal: Int? = null,
    val satellitesUsed: Int? = null,
    val lastObservationAccepted: Boolean? = null,
    val lastMeasurementAgeMs: Long? = null,
    val lastAccuracyM: Double? = null,
    val providerEnabled: Boolean? = null,
    val currentFixAvailable: Boolean = false,
) {
    fun callbacksQuiet(now: Long): Boolean {
        if (!registered) return false
        val since = listOfNotNull(registeredAt, lastLocationCallback, lastGnssCallback).maxOrNull()
        return since != null && now - since >= 5 * 60 * 1000L
    }
}

data class PowerState(
    val precisePermission: Boolean,
    val ignoringBatteryOptimizations: Boolean?,
    val powerSave: Boolean?,
    val locationPowerSaveMode: Int?,
    val deviceIdle: Boolean?,
    val interactive: Boolean?,
    val lowPowerStandby: Boolean?,
    val thermalStatus: Int?,
    val processImportance: Int?,
    val fineLocationAppOp: Int?,
) {
    fun warning(): String? =
        when {
            powerSave == true -> "Battery Saver is on. Turn it off for field tracking."
            lowPowerStandby == true ->
                "Low Power Standby may suspend tracking. Check Battery settings."
            thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Phone is thermally throttled. Let it cool before field tracking."
            ignoringBatteryOptimizations == false ->
                "Battery optimisation may interrupt tracking. Check Battery settings and allow unrestricted background activity."
            else -> null
        }
}

class PlatformPower(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)

    fun snapshot(): PowerState {
        // Unsupported/unavailable measurements are null, not reassuring invented values.
        fun <T> read(block: () -> T): T? = runCatching(block).getOrNull()
        val info = ActivityManager.RunningAppProcessInfo()
        val importance = read {
            ActivityManager.getMyMemoryState(info)
            info.importance
        }
        val ops = context.getSystemService(AppOpsManager::class.java)
        return PowerState(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
            read { power.isIgnoringBatteryOptimizations(context.packageName) },
            read { power.isPowerSaveMode },
            if (Build.VERSION.SDK_INT >= 28) read { power.locationPowerSaveMode } else null,
            read { power.isDeviceIdleMode },
            read { power.isInteractive },
            if (Build.VERSION.SDK_INT >= 33) read { power.isLowPowerStandbyEnabled } else null,
            if (Build.VERSION.SDK_INT >= 29) read { power.currentThermalStatus } else null,
            importance,
            read {
                if (Build.VERSION.SDK_INT >= 29)
                    ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_FINE_LOCATION,
                        Process.myUid(),
                        context.packageName,
                    )
                else
                    ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_FINE_LOCATION,
                        Process.myUid(),
                        context.packageName,
                    )
            },
        )
    }
}

data class GnssDiagnostic(
    val at: String,
    val elapsed: Long,
    val serviceGeneration: String,
    val startReason: String,
    val foregroundType: Int?,
    val source: SourceState,
    val providerEnabled: Boolean?,
    val locationCallbackAgeMs: Long?,
    val gnssCallbackAgeMs: Long?,
    val fixMeasurementAgeMs: Long?,
    val fixAvailable: Boolean,
    val callbacksQuiet: Boolean,
    val wakeLockHeld: Boolean,
    val wakeRenewals: Int,
    val power: PowerState,
    val reportingIterationAgeMs: Long?,
    val senderIterationAgeMs: Long?,
    val savedSnapshotAgeMs: Long?,
    val loopError: String?,
    val effectiveIntervalS: Int? = null,
    val wifiAvailable: Boolean? = null,
    val lastAckAgeMs: Long? = null,
    val pendingOutbox: Int? = null,
    val deliveryPaused: Boolean? = null,
    val deliveryError: Boolean? = null,
    val lastKnownAccuracyM: Double? = null,
    val pendingOutboxError: Boolean? = null,
)

// Closed event vocabulary: callers cannot enqueue protocol bodies, coordinates or error text.
enum class ServiceStartReason {
    USER_START,
    STICKY_RESTART,
}

enum class DiagnosticEvent {
    SAMPLE,
    STATE_CHANGED,
    SERVICE_CREATE,
    SERVICE_START,
    FOREGROUND_PROMOTED,
    FOREGROUND_FAILED,
    SERVICE_DESTROY,
    ACTIVITY_VISIBLE,
    ACTIVITY_RESUMED,
    ACTIVITY_BACKGROUND,
    REGISTRATION_ATTEMPT,
    REGISTRATION_SUCCESS,
    REGISTRATION_FAILED,
    STATUS_REGISTRATION_SUCCESS,
    STATUS_REGISTRATION_FAILED,
    UNREGISTER,
    PROVIDER_ENABLED,
    PROVIDER_DISABLED,
    LOCATION_CALLBACK,
    GNSS_STARTED,
    GNSS_STOPPED,
    GNSS_FIRST_FIX,
    GNSS_STATUS,
    WAKE_ACQUIRE,
    WAKE_RENEW,
    WAKE_RELEASE,
    LOOP_FAILURE,
    FIX_BECAME_STALE,
    CALLBACK_SILENCE,
    PERMISSION_LOST,
    FIRST_LOCATION_AFTER_STALE,
    GNSS_RECOVERED,
    INCIDENT_FINALIZED,
    PROCESS_INTERRUPTED,
    RECORDS_DROPPED,
    SOS_TRIGGER_DETECTED,
    SOS_KEY_EVALUATED,
    SOS_DEBOUNCED,
    SOS_SAVED_LOCALLY,
    SOS_SAVE_FAILED,
    SOS_PRIORITY_PLACED,
    SOS_PREEMPTED_BACKLOG,
    SOS_SEND_ATTEMPT,
    SOS_NETWORK_UNAVAILABLE,
    SOS_RETRY_BACKOFF,
    SOS_TRANSPORT_ACK_ACCEPTED,
    SOS_TRANSPORT_ACK_REJECTED,
    SOS_RESTORED,
}

data class DiagnosticEntry(
    val event: DiagnosticEvent,
    val at: String,
    val elapsed: Long,
    val serviceGeneration: String?,
    val snapshot: GnssDiagnostic? = null,
    val source: SourceState? = null,
    val count: Long? = null,
    val startReason: ServiceStartReason? = null,
    val sos: SosEvidence? = null,
)

data class IncidentSummary(
    val incidentId: String,
    val startedAt: String,
    val startedElapsed: Long,
    val trigger: DiagnosticEvent,
    val serviceGeneration: String?,
    val before: GnssDiagnostic?,
    var after: GnssDiagnostic? = null,
    var recoveredAt: String? = null,
    var recoveredElapsed: Long? = null,
    var staleDurationMs: Long? = null,
    var staleStartedElapsed: Long? = null,
    var recoveryReason: String? = null,
    var finalized: Boolean = false,
    var interrupted: Boolean = false,
    var truncated: Boolean = false,
)

// All IO is on the recorder's independent worker, never on a native callback or service loop.
// JSONL append+fsync preserves complete records on process death; small summaries use AtomicFile.
class DiagnosticJournal(private val directory: File, val limits: Limits = Limits()) {
    data class Limits(
        val coarseMs: Long = 30000,
        val preSampleMs: Long = 5000,
        val incidentSampleMs: Long = 2000,
        val contextMs: Long = 300000,
        val silenceMs: Long = 300000,
        val maxIncidents: Int = 6,
        val chunkBytes: Long = 256 * 1024,
        val incidentBytes: Long = 4 * 1024 * 1024,
        val timelineBytes: Long = 4 * 1024 * 1024,
        val totalBytes: Long = 32 * 1024 * 1024,
        val maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L,
    )

    private val gson = GsonBuilder().serializeNulls().create()
    private val pre = java.util.ArrayDeque<DiagnosticEntry>()
    private var preBytes = 0
    private var last: GnssDiagnostic? = null
    private var lastCoarse: Long? = null
    private var lastPre: Long? = null
    private var lastIncident: Long? = null
    private var faults = emptySet<DiagnosticEvent>()
    private var active: Pair<File, IncidentSummary>? = null
    private var initialized = false

    @Synchronized
    fun append(snapshot: GnssDiagnostic) {
        initialize()
        val s = safe(snapshot)
        val previous = last
        val generationChanged =
            previous != null && previous.serviceGeneration != s.serviceGeneration
        if (generationChanged) {
            if (active != null && active!!.second.serviceGeneration != s.serviceGeneration)
                finalizeIncident(true)
            pre.clear()
            preBytes = 0
            lastPre = null
            lastCoarse = null
            faults = emptySet()
        }
        last = s
        val conditions = mutableSetOf<DiagnosticEvent>()
        if (s.fixMeasurementAgeMs != null && s.fixMeasurementAgeMs > 30000)
            conditions += DiagnosticEvent.FIX_BECAME_STALE
        if (s.providerEnabled == false) conditions += DiagnosticEvent.PROVIDER_DISABLED
        if (!s.power.precisePermission) conditions += DiagnosticEvent.PERMISSION_LOST
        if (s.loopError != null && s.loopError != previous?.loopError)
            conditions += DiagnosticEvent.LOOP_FAILURE
        fun silent(at: Long?) = at != null && s.elapsed - at >= limits.silenceMs
        if (
            s.source.registered &&
                s.providerEnabled == true &&
                (silent(s.source.lastLocationCallback ?: s.source.registeredAt) ||
                    (s.source.statusRegistered &&
                        silent(s.source.lastGnssCallback ?: s.source.registeredAt)))
        )
            conditions += DiagnosticEvent.CALLBACK_SILENCE
        for (trigger in conditions - faults) trigger(entry(trigger, s))
        faults = conditions
        if ((generationChanged || previous == null) && s.startReason == "sticky_restart")
            trigger(entry(DiagnosticEvent.SERVICE_START, s))
        // A fresh accepted native callback is required, not an Activity or satellite event.
        if (
            active != null &&
                s.fixAvailable &&
                s.providerEnabled == true &&
                s.power.precisePermission &&
                s.source.lastObservationAccepted == true &&
                s.source.locationCallbacks >
                    (active!!.second.before?.source?.locationCallbacks ?: 0) &&
                s.source.lastLocationCallback != previous?.source?.lastLocationCallback
        )
            recover(s)
        if (
            conditions.any {
                it in
                    setOf(
                        DiagnosticEvent.FIX_BECAME_STALE,
                        DiagnosticEvent.PROVIDER_DISABLED,
                        DiagnosticEvent.PERMISSION_LOST,
                    )
            } && active?.second?.recoveredAt != null
        ) {
            // Relapse within the post-context window belongs to the same incident.
            active!!.second.apply {
                recoveredAt = null
                recoveredElapsed = null
                recoveryReason = null
            }
            summary()
        }
        val changed = previous == null || signature(previous) != signature(s)
        if (changed) write(entry(DiagnosticEvent.STATE_CHANGED, s), timeline = true)
        val sample = entry(DiagnosticEvent.SAMPLE, s)
        if (lastPre == null || s.elapsed - lastPre!! >= limits.preSampleMs) {
            remember(sample)
            lastPre = s.elapsed
        }
        if (lastCoarse == null || s.elapsed - lastCoarse!! >= limits.coarseMs) {
            timeline(sample)
            lastCoarse = s.elapsed
        }
        if (
            active != null &&
                (lastIncident == null || s.elapsed - lastIncident!! >= limits.incidentSampleMs)
        ) {
            incident(sample)
            lastIncident = s.elapsed
        }
        val recovered = active?.second?.recoveredElapsed
        if (recovered != null && s.elapsed - recovered >= limits.contextMs) finalizeIncident(false)
        prune()
    }

    @Synchronized
    fun event(value: DiagnosticEntry) {
        initialize()
        val e =
            value.copy(
                serviceGeneration = generation(value.serviceGeneration),
                source = value.source?.let(::safeSource),
                snapshot = value.snapshot?.let(::safe),
                sos = value.sos?.let(::safeSos),
            )
        // Native event carries exact callback time and updated counters, without reading
        // power/Room.
        if (e.event == DiagnosticEvent.LOCATION_CALLBACK && e.source != null) {
            last
                ?.takeIf { it.serviceGeneration == e.serviceGeneration }
                ?.let { previous ->
                    val fresh =
                        e.source.lastObservationAccepted == true &&
                            (e.source.lastMeasurementAgeMs?.let { it in 0L..30000L } == true) &&
                            e.source.currentFixAvailable &&
                            e.source.providerEnabled == true
                    val updated =
                        previous.copy(
                            at = e.at,
                            elapsed = e.elapsed,
                            source = e.source,
                            locationCallbackAgeMs = 0,
                            fixMeasurementAgeMs = e.source.lastMeasurementAgeMs,
                            fixAvailable = fresh,
                        )
                    if (
                        fresh &&
                            previous.power.precisePermission &&
                            active != null &&
                            active!!.second.recoveredAt == null
                    )
                        recover(updated)
                }
        }
        if (
            e.event == DiagnosticEvent.SERVICE_START &&
                e.startReason == ServiceStartReason.STICKY_RESTART
        ) {
            if (active != null && active!!.second.serviceGeneration != e.serviceGeneration)
                finalizeIncident(true)
            trigger(e)
        }
        when (e.event) {
            DiagnosticEvent.REGISTRATION_FAILED,
            DiagnosticEvent.STATUS_REGISTRATION_FAILED,
            DiagnosticEvent.PROVIDER_DISABLED,
            DiagnosticEvent.PERMISSION_LOST,
            DiagnosticEvent.FOREGROUND_FAILED,
            DiagnosticEvent.LOOP_FAILURE -> trigger(e)
            else -> Unit
        }
        val native =
            e.event in setOf(DiagnosticEvent.LOCATION_CALLBACK, DiagnosticEvent.GNSS_STATUS)
        write(e, timeline = !native)
        if (e.event == DiagnosticEvent.SERVICE_DESTROY) finalizeIncident(false)
        prune()
    }

    private fun signature(s: GnssDiagnostic) =
        listOf(
            s.serviceGeneration,
            s.startReason,
            s.foregroundType,
            s.providerEnabled,
            s.source.registered,
            s.source.statusRegistered,
            s.source.registrations,
            s.source.engineRunning,
            s.source.error,
            s.power,
            s.wakeLockHeld,
            s.wakeRenewals,
            s.fixAvailable,
            s.effectiveIntervalS,
            s.wifiAvailable,
            s.pendingOutbox,
            s.pendingOutboxError,
            s.deliveryPaused,
            s.deliveryError,
            s.loopError,
        )

    private fun entry(event: DiagnosticEvent, s: GnssDiagnostic) =
        DiagnosticEntry(event, s.at, s.elapsed, s.serviceGeneration, snapshot = s)

    private fun trigger(e: DiagnosticEntry) {
        if (active == null) {
            val id = "incident-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
            val dir = File(directory, id).apply { check(mkdirs()) }
            active = dir to IncidentSummary(id, e.at, e.elapsed, e.event, e.serviceGeneration, last)
            active!!.second.staleStartedElapsed =
                last
                    ?.fixMeasurementAgeMs
                    ?.takeIf { it > 30000 }
                    ?.let { last!!.elapsed - it + 30000 }
            summary() // Freeze start and pre-context before subsequent samples can displace them.
            atomic(
                File(dir, "pre.jsonl"),
                pre.joinToString("", transform = { gson.toJson(it) + "\n" }).toByteArray(),
            )
            lastIncident = null
        }
        if (
            e.event == DiagnosticEvent.FIX_BECAME_STALE &&
                active!!.second.staleStartedElapsed == null
        ) {
            active!!.second.staleStartedElapsed =
                last
                    ?.fixMeasurementAgeMs
                    ?.takeIf { it > 30000 }
                    ?.let { last!!.elapsed - it + 30000 }
            summary()
        }
        write(e, timeline = true)
    }

    private fun recover(s: GnssDiagnostic) {
        val a = active ?: return
        if (a.second.recoveredAt != null) return
        if (a.second.staleStartedElapsed != null)
            write(entry(DiagnosticEvent.FIRST_LOCATION_AFTER_STALE, s), timeline = true)
        a.second.apply {
            after = s
            recoveredAt = s.at
            recoveredElapsed = s.elapsed
            staleDurationMs =
                staleStartedElapsed?.let {
                    (staleDurationMs ?: 0) + (s.elapsed - it).coerceAtLeast(0)
                } ?: staleDurationMs
            staleStartedElapsed = null
            recoveryReason = "fresh_accepted_location_callback"
        }
        summary()
        write(entry(DiagnosticEvent.GNSS_RECOVERED, s), timeline = true)
    }

    private fun finalizeIncident(interrupted: Boolean) {
        val a = active ?: return
        a.second.finalized = true
        a.second.interrupted = interrupted
        if (last != null)
            incident(
                entry(
                    if (interrupted) DiagnosticEvent.PROCESS_INTERRUPTED
                    else DiagnosticEvent.INCIDENT_FINALIZED,
                    last!!,
                )
            )
        summary()
        active = null
        lastIncident = null
    }

    private fun remember(e: DiagnosticEntry) {
        pre.addLast(e)
        preBytes += gson.toJson(e).length
        while (
            pre.isNotEmpty() &&
                (e.elapsed - pre.first.elapsed > limits.contextMs ||
                    preBytes > 1024 * 1024 ||
                    pre.size > 1200)
        ) {
            preBytes -= gson.toJson(pre.removeFirst()).length
        }
    }

    private fun write(e: DiagnosticEntry, timeline: Boolean) {
        remember(e)
        if (timeline) timeline(e)
        if (active != null) incident(e)
    }

    private fun timeline(e: DiagnosticEntry) {
        rotating(directory, "timeline", e)
        trim(
            directory.listFiles().orEmpty().filter {
                it.name.matches(Regex("timeline-[0-9]+\\.jsonl"))
            },
            limits.timelineBytes,
        )
    }

    private fun incident(e: DiagnosticEntry) {
        val a = active ?: return
        rotating(a.first, "events", e)
        val files = a.first.listFiles().orEmpty().filter { it.name.startsWith("events-") }
        if (files.sumOf { it.length() } > limits.incidentBytes) {
            trim(files, limits.incidentBytes)
            a.second.truncated = true
            summary()
        }
    }

    private fun rotating(dir: File, prefix: String, e: DiagnosticEntry) {
        val files =
            dir.listFiles()
                .orEmpty()
                .filter { it.name.matches(Regex("$prefix-[0-9]+\\.jsonl")) }
                .sortedBy { it.name }
        val current = files.lastOrNull()
        val file =
            if (current == null || current.length() >= limits.chunkBytes)
                File(
                    dir,
                    "$prefix-${((current?.name?.substringAfter('-')?.substringBefore('.')?.toIntOrNull() ?: -1) + 1).toString().padStart(8, '0')}.jsonl",
                )
            else current
        appendLine(file, e)
    }

    private fun appendLine(file: File, e: DiagnosticEntry) {
        val bytes = (gson.toJson(e) + "\n").toByteArray(Charsets.UTF_8)
        check(bytes.size <= 16384)
        java.io.FileOutputStream(file, true).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    private fun summary() {
        val a = active ?: return
        atomic(File(a.first, "summary.json"), gson.toJson(a.second).toByteArray())
    }

    private fun atomic(file: File, bytes: ByteArray) {
        val f = AtomicFile(file)
        val stream = f.startWrite()
        try {
            stream.write(bytes)
            f.finishWrite(stream)
        } catch (e: Exception) {
            f.failWrite(stream)
            throw e
        }
    }

    @Suppress("SENSELESS_COMPARISON") // Gson can decode null despite Kotlin declarations.
    private fun initialize() {
        if (initialized) return
        check(directory.isDirectory || directory.mkdirs())
        prune() // Enforce age before atomic repairs update directory modification times.
        // Repair incomplete/corrupt JSONL tails; valid records are retained. Never import arbitrary
        // files.
        for (file in diagnosticFiles()) {
            if (file.extension == "jsonl") {
                val valid = file.readLines().mapNotNull(::readEntry)
                atomic(
                    file,
                    valid.joinToString("", transform = { gson.toJson(it) + "\n" }).toByteArray(),
                )
            } else if (file.name == "summary.json") {
                val state = readSummary(file)
                if (state == null) file.delete()
                else if (!state.finalized) {
                    state.finalized = true
                    state.interrupted = true
                    state.recoveryReason = "process_ended_without_finalization"
                    atomic(file, gson.toJson(state).toByteArray())
                }
            }
        }
        initialized = true
        prune()
    }

    private fun trim(files: List<File>, bytes: Long) {
        var size = files.sumOf { it.length() }
        for (file in files.sortedBy { it.name }) {
            if (size <= bytes) break
            size -= file.length()
            check(file.delete())
        }
    }

    private fun prune() {
        val incidents =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("incident-") }
                .sortedBy { it.name }
        val kept = incidents.toMutableList()
        for (dir in incidents) {
            if (dir == active?.first) continue
            if (
                kept.size > limits.maxIncidents ||
                    System.currentTimeMillis() - dir.lastModified() > limits.maxAgeMs
            ) {
                check(dir.deleteRecursively())
                kept.remove(dir)
            }
        }
        var size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        for (dir in kept) {
            if (size <= limits.totalBytes) break
            if (dir == active?.first) continue
            val removed = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            check(dir.deleteRecursively())
            size -= removed
        }
        if (size > limits.totalBytes) {
            val timeline =
                directory.listFiles().orEmpty().filter { it.name.startsWith("timeline-") }
            trim(
                timeline,
                (timeline.sumOf { it.length() } - (size - limits.totalBytes)).coerceAtLeast(0),
            )
        }
        directory
            .listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith("timeline-") &&
                    System.currentTimeMillis() - it.lastModified() > limits.maxAgeMs
            }
            .forEach { it.delete() }
    }

    private fun diagnosticFiles(): List<File> =
        directory.listFiles().orEmpty().flatMap { child ->
            when {
                child.isFile && child.name.matches(Regex("timeline-[0-9]{8}\\.jsonl")) ->
                    listOf(child)
                child.isDirectory && child.name.matches(Regex("incident-[0-9]+-[0-9a-f-]{36}")) ->
                    child.listFiles().orEmpty().filter {
                        it.isFile &&
                            (it.name in setOf("summary.json", "pre.jsonl") ||
                                it.name.matches(Regex("events-[0-9]{8}\\.jsonl")))
                    }
                else -> emptyList()
            }
        }

    // Export only typed, sanitized records. Even a corrupted/injected private file cannot leak
    // unrecognized keys or arbitrary error messages. No database/cache/source files are included.
    @Suppress("SENSELESS_COMPARISON")
    @Synchronized
    fun export(
        output: java.io.OutputStream,
        build: DiagnosticBuild,
        ioFailures: Long = 0,
        dropped: Long = 0,
    ) {
        initialize()
        prune()
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(
                "manifest.json",
                gson
                    .toJson(
                        mapOf(
                            "format_version" to 1,
                            "exported_at" to utc(System.currentTimeMillis()),
                            "build" to build,
                            "writer_io_failures" to ioFailures,
                            "queue_dropped_total" to dropped,
                            "limits" to limits,
                            "note" to
                                "Evidence only. Missing records or truncated/interrupted incidents are not proof of healthy GNSS.",
                        )
                    )
                    .toByteArray(),
            )
            val sosEntries =
                diagnosticFiles()
                    .filter { it.name.startsWith("timeline-") }
                    .sortedBy { it.path }
                    .flatMap { it.readLines().mapNotNull(::readEntry) }
                    .filter { it.sos != null }
            put(
                "sos-report.json",
                gson.toJson(sosReport(sosEntries, ioFailures, dropped)).toByteArray(),
            )
            for (f in diagnosticFiles().sortedBy { it.path }) {
                val bytes =
                    if (f.extension == "jsonl") {
                        f.readLines()
                            .mapNotNull(::readEntry)
                            .joinToString("", transform = { gson.toJson(it) + "\n" })
                            .toByteArray()
                    } else {
                        val v = readSummary(f) ?: continue
                        gson.toJson(v).toByteArray()
                    }
                put(f.relativeTo(directory).path, bytes)
            }
        }
    }

    @Suppress("SENSELESS_COMPARISON") // Gson can bypass Kotlin non-null constructor contracts.
    private fun readEntry(line: String): DiagnosticEntry? =
        runCatching {
                val e = gson.fromJson(line, DiagnosticEntry::class.java)
                require(e != null && e.event != null)
                sanitize(e)
            }
            .getOrNull()

    // Gson bypasses Kotlin constructors, so corrupted files require explicit non-null validation.
    @Suppress("SENSELESS_COMPARISON")
    private fun readSummary(file: File): IncidentSummary? =
        runCatching {
                val v =
                    AtomicFile(file).openRead().bufferedReader().use {
                        gson.fromJson(it, IncidentSummary::class.java)
                    }
                require(v != null && v.trigger != null)
                v.copy(
                    incidentId = file.parentFile!!.name,
                    startedAt = timestamp(v.startedAt),
                    recoveredAt = v.recoveredAt?.let(::timestamp),
                    serviceGeneration = generation(v.serviceGeneration),
                    before = v.before?.let(::safe),
                    after = v.after?.let(::safe),
                    recoveryReason =
                        v.recoveryReason?.takeIf {
                            it in
                                setOf(
                                    "fresh_accepted_location_callback",
                                    "process_ended_without_finalization",
                                )
                        },
                )
            }
            .getOrNull()

    private fun sanitize(e: DiagnosticEntry) =
        e.copy(
            at = timestamp(e.at),
            serviceGeneration = generation(e.serviceGeneration),
            snapshot = e.snapshot?.let(::safe),
            source = e.source?.let(::safeSource),
            sos = e.sos?.let(::safeSos),
        )

    private fun safeSos(s: SosEvidence) =
        s.copy(
            eventRef = s.eventRef?.takeIf { it.matches(Regex("[0-9a-f]{16}")) },
            durationMs = s.durationMs?.takeIf { it in 0..MAX_WIRE_INTEGER },
            count = s.count?.takeIf { it >= 0 },
            competingReports = s.competingReports?.takeIf { it >= 0 },
        )

    private fun timestamp(s: String?) =
        runCatching { java.time.Instant.parse(s).toString() }.getOrDefault("unavailable")

    private fun generation(s: String?) = s?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) }

    private fun safeSource(s: SourceState) =
        s.copy(
            lastRejection =
                s.lastRejection?.takeIf {
                    it in setOf("invalid_coordinate", "unusable_elapsed_measurement_time")
                },
            error = if (s.error == null) null else "source_error",
        )

    private fun safe(s: GnssDiagnostic) =
        s.copy(
            at = timestamp(s.at),
            serviceGeneration = generation(s.serviceGeneration) ?: "unavailable",
            startReason =
                s.startReason.takeIf { it in setOf("not_started", "user_start", "sticky_restart") }
                    ?: "unavailable",
            source = safeSource(s.source),
            loopError = if (s.loopError == null) null else "tracking_loop_failure",
        )
}

data class DiagnosticBuild(
    val versionName: String,
    val versionCode: Long,
    val androidApi: Int,
    val sourceRevision: String = "unknown",
)

// Application lifetime, independent SupervisorJob. A bounded FIFO preserves transitions;
// overflow is counted explicitly rather than silently conflating them. Producer never waits for IO.
class DiagnosticRecorder(
    private val journal: DiagnosticJournal,
    private val clock: Clock = SystemClock,
    private val scope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ),
    capacity: Int = 512,
) {
    private val messages = kotlinx.coroutines.channels.Channel<Any>(capacity)
    private val lost = java.util.concurrent.atomic.AtomicLong()
    private val totalLost = java.util.concurrent.atomic.AtomicLong()
    @Volatile
    var ioFailures: Long = 0
        private set

    private data class Export(
        val file: File,
        val build: DiagnosticBuild,
        val done: kotlinx.coroutines.CompletableDeferred<File>,
    )

    private val processGeneration = java.util.UUID.randomUUID().toString()
    private val worker = scope.launchWorker()

    private fun kotlinx.coroutines.CoroutineScope.launchWorker() = launch {
        for (m in messages) {
            try {
                val dropped = lost.getAndSet(0)
                if (dropped > 0)
                    journal.event(
                        DiagnosticEntry(
                            DiagnosticEvent.RECORDS_DROPPED,
                            utc(clock.wallMillis()),
                            clock.elapsedMillis(),
                            null,
                            count = dropped,
                        )
                    )
                when (m) {
                    is GnssDiagnostic -> journal.append(m)
                    is DiagnosticEntry -> journal.event(m)
                    is Export -> {
                        try {
                            m.file.outputStream().use {
                                journal.export(it, m.build, ioFailures, totalLost.get())
                            }
                            m.done.complete(m.file)
                        } catch (e: Exception) {
                            m.file.delete()
                            m.done.completeExceptionally(e)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ioFailures++
                if (m is Export) m.done.completeExceptionally(e)
            }
        }
    }

    fun sample(s: GnssDiagnostic) {
        if (!messages.trySend(s).isSuccess) {
            lost.incrementAndGet()
            totalLost.incrementAndGet()
        }
    }

    fun event(
        event: DiagnosticEvent,
        generation: String? = null,
        source: SourceState? = null,
        startReason: ServiceStartReason? = null,
    ) {
        val e =
            DiagnosticEntry(
                event,
                utc(clock.wallMillis()),
                clock.elapsedMillis(),
                generation,
                source = source,
                startReason = startReason,
            )
        if (!messages.trySend(e).isSuccess) {
            lost.incrementAndGet()
            totalLost.incrementAndGet()
        }
    }

    fun sos(event: DiagnosticEvent, value: SosEvidence) {
        val entry =
            DiagnosticEntry(
                event,
                utc(clock.wallMillis()),
                clock.elapsedMillis(),
                processGeneration,
                sos = value,
            )
        if (!messages.trySend(entry).isSuccess) {
            lost.incrementAndGet()
            totalLost.incrementAndGet()
        }
    }

    suspend fun export(file: File, build: DiagnosticBuild): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val done = kotlinx.coroutines.CompletableDeferred<File>()
            // Sending and awaiting are one handoff: Activity cancellation cannot delete
            // the temporary file while an accepted export is still queued/writing.
            messages.send(Export(file, build, done))
            done.await()
        }

    fun close() {
        messages.close()
    }

    // Deterministic teardown for finite test/application owners; real tracking uses app lifetime.
    internal suspend fun finish() {
        messages.close()
        worker.join()
    }
}
