package org.gnss.tracking

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GnssDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun silenceIsDifferentFromPoorOrStaleFixAndDoesNotRestartAnything() {
        val registered = SourceState(registered = true, registeredAt = 1000)
        assertFalse(registered.callbacksQuiet(300999))
        assertTrue(registered.callbacksQuiet(301000))
        assertFalse(registered.copy(lastGnssCallback = 300000).callbacksQuiet(301000))
        assertFalse(registered.copy(lastLocationCallback = 300000).callbacksQuiet(301000))
        assertFalse(registered.copy(registered = false).callbacksQuiet(301000))
        assertFalse(registered.copy(registeredAt = 301000).callbacksQuiet(301000))
    }

    @Test
    @Config(sdk = [26])
    fun unavailablePowerApisStayNullOnMinimumSdk() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val p = PlatformPower(context).snapshot()
        assertNull(p.locationPowerSaveMode)
        assertNull(p.thermalStatus)
        assertNull(p.lowPowerStandby)
    }

    @Test
    @Config(sdk = [35])
    fun powerPolicyStatesAndPermissionAreMeasuredWithoutChangingPolicy() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = context.getSystemService(PowerManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setIgnoringBatteryOptimizations(context.packageName, false)
        shadow.setIsPowerSaveMode(true)
        shadow.setIsDeviceIdleMode(true)
        shadow.setIsInteractive(false)
        shadow.setLowPowerStandbySupported(true)
        shadow.setLowPowerStandbyEnabled(true)
        shadow.setLocationPowerSaveMode(PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF)
        shadow.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val snapshot = PlatformPower(context).snapshot()
        assertTrue(snapshot.precisePermission)
        assertEquals(false, snapshot.ignoringBatteryOptimizations)
        assertEquals(true, snapshot.powerSave)
        assertEquals(true, snapshot.deviceIdle)
        assertEquals(false, snapshot.interactive)
        assertEquals(true, snapshot.lowPowerStandby)
        assertEquals(
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            snapshot.locationPowerSaveMode,
        )
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, snapshot.thermalStatus)
        assertTrue(snapshot.warning()!!.contains("Battery Saver"))
        assertTrue(manager.isPowerSaveMode) // Diagnostics do not request/bypass exemptions.
    }

    @Test
    fun journalIsBoundedDurableAndContainsNoLocationOrReceiverData() {
        val file = File(temporary.root, "gnss-diagnostics.json")
        val power = PowerState(true, false, false, 0, false, false, null, null, 125, 4)
        fun sample(i: Long) =
            GnssDiagnostic(
                utc(i),
                i,
                "service-generation",
                "user_start",
                null,
                SourceState(registered = true, registeredAt = 0),
                true,
                null,
                null,
                null,
                false,
                true,
                true,
                0,
                power,
                0,
                0,
                null,
                null,
            )
        val journal = DiagnosticJournal(file)
        for (i in 0L..70L) journal.append(sample(i))
        DiagnosticJournal(file).append(sample(71))
        val data = JsonParser.parseString(file.readText()).asJsonArray
        assertEquals(DiagnosticJournal.MAX_SAMPLES, data.size())
        assertEquals(8L, data.first().asJsonObject["elapsed"].asLong)
        assertEquals(71L, data.last().asJsonObject["elapsed"].asLong)
        assertTrue(file.length() <= DiagnosticJournal.MAX_BYTES)
        for (secret in
            listOf("latitude", "longitude", "endpoint", "device_id", "party")) assertFalse(
            file.readText().contains(secret)
        )
        val workers = Executors.newFixedThreadPool(2)
        val concurrent = File(temporary.root, "overlapping-generations.json")
        try {
            val tasks =
                (0L..39L).map { i -> Callable { DiagnosticJournal(concurrent).append(sample(i)) } }
            for (task in workers.invokeAll(tasks)) task.get(10, TimeUnit.SECONDS)
            val preserved = JsonParser.parseString(concurrent.readText()).asJsonArray
            assertEquals(40, preserved.size())
            assertEquals(
                (0L..39L).toSet(),
                preserved.map { it.asJsonObject["elapsed"].asLong }.toSet(),
            )
        } finally {
            workers.shutdownNow()
        }
        file.writeText("corrupted")
        journal.append(sample(72))
        assertEquals(1, JsonParser.parseString(file.readText()).asJsonArray.size())
    }
}
