package org.gnss.tracking

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
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
}
