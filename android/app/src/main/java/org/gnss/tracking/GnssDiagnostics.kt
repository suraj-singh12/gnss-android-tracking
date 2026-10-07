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
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File

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
)

// One private atomic file, at most 64 minute samples, bounded independently of Room.
// Written by a single IO worker; a conflated channel keeps disk stalls from creating
// a second queue or blocking CPU-lock renewal/native callbacks.
class DiagnosticJournal(file: File) {
    private val file = AtomicFile(file)
    private val gson = GsonBuilder().serializeNulls().create()

    fun append(snapshot: GnssDiagnostic) =
        synchronized(writerLock) {
            val samples =
                runCatching {
                        file.openRead().use {
                            val buffer = ByteArray(MAX_BYTES + 1)
                            var size = 0
                            while (size < buffer.size) {
                                val n = it.read(buffer, size, buffer.size - size)
                                if (n < 0) break
                                size += n
                            }
                            check(size <= MAX_BYTES)
                            JsonParser.parseString(String(buffer, 0, size, Charsets.UTF_8))
                                .asJsonArray
                        }
                    }
                    .getOrDefault(JsonArray())
            while (samples.size() >= MAX_SAMPLES) samples.remove(0)
            samples.add(gson.toJsonTree(snapshot))
            val bytes = gson.toJson(samples).toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES) { "GNSS diagnostic size limit" }
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }

    companion object {
        // A canceled service worker may finish synchronous IO while its replacement
        // starts. AtomicFile is not multi-writer safe; serialize across generations.
        private val writerLock = Any()
        const val MAX_SAMPLES = 64
        const val MAX_BYTES = 128 * 1024
    }
}
