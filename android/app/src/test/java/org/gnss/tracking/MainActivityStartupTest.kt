package org.gnss.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class MainActivityStartupTest {
    private lateinit var app: TrackingApp
    private lateinit var gps: LocationManager

    @Before
    fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        gps = app.getSystemService(LocationManager::class.java)
        shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(false)
        app.repository.settings("party", "Field party", "http://192.168.1.2:8080", 10, false)
    }

    private fun <T> field(activity: MainActivity, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivity::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(activity) as T
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun open(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            await { field<Boolean>(controller.get(), "initialized") }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Activity initialization: ${field<TextView>(controller.get(), "status").text}",
                e,
            )
        }
        return controller
    }

    private fun deliverPermissionResult(
        activity: MainActivity,
        code: Int,
        permissions: Array<String>,
        results: IntArray,
    ) {
        // Exercise framework dispatch too: it clears the outstanding permission
        // request before our callback, which calling the override alone cannot do.
        android.app.Activity::class
            .java
            .getDeclaredMethod(
                "dispatchRequestPermissionsResult",
                Int::class.javaPrimitiveType,
                Intent::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                activity,
                code,
                Intent()
                    .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", permissions)
                    .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", results),
            )
    }

    private fun close(controller: ActivityController<MainActivity>) {
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [26, 28, 35])
    fun readySingleTapStartsExactlyOnceAndBecomesActiveAfterDurableStartup() = runBlocking {
        val activity = open()
        val button = field<Button>(activity.get(), "startButton")
        try {
            assertTrue(button.isEnabled)
            button.performClick()
            assertEquals("Starting…", button.text.toString())
            assertFalse(button.isEnabled)
            button.performClick() // Also guard programmatic/repeated clicks, not only disabled UI.
            await { !field<Boolean>(activity.get(), "pendingStart") }
            val intent = shadowOf(app).nextStartedService
            assertEquals(TrackingService::class.java.name, intent.component!!.className)
            assertNull(shadowOf(app).nextStartedService)
            assertFalse(app.operational.value.tracking)
            assertTrue(app.operational.value.starting)
            val service = Robolectric.buildService(TrackingService::class.java).create()
            try {
                service.get().onStartCommand(intent, 0, 1)
                await { app.operational.value.tracking }
                assertTrue(app.repository.state().tracking)
                assertFalse(app.operational.value.starting)
                assertEquals("Tracking active", button.text.toString())
                button.performClick()
                assertNull(shadowOf(app).nextStartedService)
            } finally {
                service.destroy()
            }
        } finally {
            close(activity)
        }
    }

    @Test
    fun loadingDisablesStartAndExplainsAnEarlyProgrammaticTap() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction =
            launch(Dispatchers.IO) {
                app.repository.db.withTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
        entered.await()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val button = field<Button>(activity.get(), "startButton")
            assertFalse(button.isEnabled)
            assertEquals("Loading settings…", button.text.toString())
            button.performClick()
            assertTrue(app.operational.value.error!!.contains("still loading"))
            assertNull(shadowOf(app).nextStartedService)
            release.complete(Unit)
            transaction.join()
            await { button.isEnabled }
            button.performClick()
            await { !field<Boolean>(activity.get(), "pendingStart") }
            assertNotNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            release.complete(Unit)
            transaction.join()
            close(activity)
        }
    }

    @Test
    fun permissionResultBeforeVisibleResumePreservesExplicitStartWithoutSecondTap() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = open()
        try {
            assertEquals(1, field<Int?>(activity.get(), "permissionInFlight"))
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "savingStart") }
            activity.pause().stop()
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            deliverPermissionResult(
                activity.get(),
                1,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(field<Boolean>(activity.get(), "pendingStart"))
            activity.start().resume()
            await { !field<Boolean>(activity.get(), "pendingStart") }
            assertNotNull(shadowOf(app).nextStartedService)
            activity.pause().resume()
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(activity)
        }
    }

    @Test
    fun settingsReturnRefreshesGpsAndContinuesOnlyAnExplicitPendingStart() = runBlocking {
        shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val activity = open()
        try {
            assertTrue(
                field<TextView>(activity.get(), "readiness").text.contains("enable Location/GPS")
            )
            field<Button>(activity.get(), "gpsAction").performClick()
            assertEquals(
                Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                shadowOf(activity.get()).nextStartedActivity.action,
            )
            activity.pause().stop()
            shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            activity.start().resume()
            assertNull(shadowOf(app).nextStartedService) // Opening/resuming alone never starts.
            shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
            activity.pause().resume()
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "savingStart") }
            assertTrue(app.operational.value.error!!.contains("Enable System Location"))
            assertNull(shadowOf(app).nextStartedService)
            activity.pause().stop()
            shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            activity.start().resume()
            assertNotNull(shadowOf(app).nextStartedService)
            assertTrue(field<TextView>(activity.get(), "readiness").text.contains("✓ GPS on"))
        } finally {
            close(activity)
        }
    }

    @Test
    @Config(sdk = [35])
    fun runtimeNotificationPermissionGrantedWhilePausedDoesNotNeedSecondStart() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = open()
        try {
            assertEquals(2, field<Int?>(activity.get(), "permissionInFlight"))
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "savingStart") }
            activity.pause()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            deliverPermissionResult(
                activity.get(),
                2,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(app).nextStartedService)
            activity.resume()
            assertNotNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(field<TextView>(activity.get(), "readiness").text.contains("✓ Allowed"))
        } finally {
            close(activity)
        }
    }

    @Test
    @Config(sdk = [35])
    fun locationAndNotificationPermissionPromptsAreIndependentAndDenialIsExplained() = runBlocking {
        shadowOf(app)
            .denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        val activity = open()
        try {
            assertEquals(1, field<Int?>(activity.get(), "permissionInFlight"))
            deliverPermissionResult(
                activity.get(),
                1,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
            await { field<Int?>(activity.get(), "permissionInFlight") == 2 }
            deliverPermissionResult(
                activity.get(),
                2,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(field<Int?>(activity.get(), "permissionInFlight"))
            activity.pause().resume()
            assertNull(field<Int?>(activity.get(), "permissionInFlight")) // No denial loop.
            assertTrue(
                field<TextView>(activity.get(), "readiness")
                    .text
                    .contains("precise location is required")
            )
            field<Button>(activity.get(), "locationAction").performClick()
            assertEquals(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                shadowOf(activity.get()).nextStartedActivity.action,
            )
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(activity)
        }
    }

    @Test
    fun appNotificationDisablementAndBatterySaverAreRecheckedOnResume() = runBlocking {
        val notifications = app.getSystemService(NotificationManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        shadowOf(notifications).setNotificationsEnabled(false)
        shadowOf(power).setIsPowerSaveMode(true)
        val activity = open()
        try {
            val readiness = field<TextView>(activity.get(), "readiness")
            assertTrue(readiness.text.contains("allow tracking notifications"))
            assertTrue(readiness.text.contains("Battery Saver is ON"))
            field<Button>(activity.get(), "notificationAction").performClick()
            assertEquals(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                shadowOf(activity.get()).nextStartedActivity.action,
            )
            field<Button>(activity.get(), "saverAction").performClick()
            assertEquals(
                Settings.ACTION_BATTERY_SAVER_SETTINGS,
                shadowOf(activity.get()).nextStartedActivity.action,
            )
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "savingStart") }
            assertNull(shadowOf(app).nextStartedService)
            activity.pause().resume()
            shadowOf(notifications).setNotificationsEnabled(true)
            activity.pause().resume()
            assertTrue(app.operational.value.error!!.contains("Battery Saver is ON"))
            assertNull(shadowOf(app).nextStartedService)
            shadowOf(power).setIsPowerSaveMode(false)
            activity.pause().resume()
            assertNotNull(shadowOf(app).nextStartedService)
            assertTrue(readiness.text.contains("✓ Off"))
        } finally {
            close(activity)
        }
    }

    @Test
    fun batteryWarningIsTruthfulAndDoesNotAutomaticallyRequestExemption() = runBlocking {
        val activity = open()
        try {
            val description = field<TextView>(activity.get(), "readiness").text.toString()
            assertTrue(description.contains("background battery restrictions may affect tracking"))
            assertTrue(description.contains("Unrestricted / allow background activity"))
            assertNull(shadowOf(activity.get()).nextStartedActivity)
            field<Button>(activity.get(), "backgroundAction").performClick()
            val settings = shadowOf(activity.get()).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
            assertEquals("package:${app.packageName}", settings.data.toString())
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "pendingStart") }
            assertNotNull(shadowOf(app).nextStartedService) // OEM warning is not a false API gate.
        } finally {
            close(activity)
        }
    }

    @Test
    fun pendingStartSurvivesSavedActivityStateButDismissedActivityCancelsIt() = runBlocking {
        shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val first = open()
        field<Button>(first.get(), "startButton").performClick()
        await { !field<Boolean>(first.get(), "savingStart") }
        val saved = Bundle()
        first.saveInstanceState(saved)
        close(first)
        val restored =
            Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
        try {
            await { field<Boolean>(restored.get(), "initialized") }
            assertTrue(field<Boolean>(restored.get(), "pendingStart"))
            assertNull(shadowOf(app).nextStartedService)
            shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            restored.pause().resume()
            assertNotNull(shadowOf(app).nextStartedService)
        } finally {
            close(restored)
        }
        app.operational.value = Operational()
        shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val dismissed = open()
        field<Button>(dismissed.get(), "startButton").performClick()
        await { !field<Boolean>(dismissed.get(), "savingStart") }
        close(dismissed)
        assertFalse(app.operational.value.starting)
        shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fresh = open()
        try {
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(fresh)
        }
    }

    @Test
    fun channelDisabledIsAnActionRequiredEvenWhenAppNotificationsAreAllowed() = runBlocking {
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    TrackingService.CHANNEL,
                    "Tracking",
                    NotificationManager.IMPORTANCE_NONE,
                )
            )
        val activity = open()
        try {
            assertTrue(FieldPreflight(app).snapshot().notificationPermission)
            assertFalse(FieldPreflight(app).snapshot().notifications!!)
            assertTrue(
                field<TextView>(activity.get(), "readiness")
                    .text
                    .contains("Action required — allow tracking notifications")
            )
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "savingStart") }
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(app.operational.value.error!!.contains("Allow tracking notifications"))
        } finally {
            close(activity)
        }
    }

    @Test
    fun settingsSaveFinishingWhileHiddenWaitsForResumeAndCoalescesTaps() = runBlocking {
        val activity = open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction =
            launch(Dispatchers.IO) {
                app.repository.db.withTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
        withTimeout(10000) { entered.await() }
        try {
            val button = field<Button>(activity.get(), "startButton")
            button.performClick()
            button.performClick()
            activity.pause().stop()
            release.complete(Unit)
            transaction.join()
            await { !field<Boolean>(activity.get(), "savingStart") }
            assertTrue(field<Boolean>(activity.get(), "pendingStart"))
            assertNull(shadowOf(app).nextStartedService)
            activity.start().resume()
            assertNotNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            release.complete(Unit)
            transaction.join()
            close(activity)
        }
    }

    @Test
    fun failedSettingsSaveClearsStartingAndShowsActionableError() = runBlocking {
        val activity = open()
        try {
            app.repository.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER injected_failure BEFORE UPDATE ON installation BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END"
            )
            field<Button>(activity.get(), "startButton").performClick()
            await { !app.operational.value.starting }
            assertFalse(field<Boolean>(activity.get(), "pendingStart"))
            assertTrue(field<Button>(activity.get(), "startButton").isEnabled)
            assertTrue(app.operational.value.error!!.contains("injected disk failure"))
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(activity)
        }
    }

    @Test
    fun optimizationExemptionRefreshStillExplainsOemSetup() = runBlocking {
        val activity = open()
        try {
            shadowOf(app.getSystemService(PowerManager::class.java))
                .setIgnoringBatteryOptimizations(app.packageName, true)
            activity.pause().resume()
            val text = field<TextView>(activity.get(), "readiness").text.toString()
            assertTrue(text.contains("Android optimization exemption present"))
            assertTrue(text.contains("OEM setup still required"))
            assertEquals(
                android.view.View.VISIBLE,
                field<Button>(activity.get(), "backgroundAction").visibility,
            )
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(activity)
        }
    }

    @Test
    fun preciseRevocationIsDetectedAndRequestedAgainOnResumeWithoutStarting() = runBlocking {
        val activity = open()
        try {
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            activity.pause().resume()
            assertEquals(1, field<Int?>(activity.get(), "permissionInFlight"))
            assertTrue(
                field<TextView>(activity.get(), "readiness")
                    .text
                    .contains("precise location is required")
            )
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            deliverPermissionResult(
                activity.get(),
                1,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(field<TextView>(activity.get(), "readiness").text.contains("✓ Precise"))
        } finally {
            close(activity)
        }
    }

    @Test
    fun initializationFailureIsVisibleAndCannotStartService() = runBlocking {
        app.repository.db.openHelper.writableDatabase.execSQL("DROP TABLE installation")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            await {
                field<TextView>(activity.get(), "status")
                    .text
                    .startsWith("Cannot load saved settings:")
            }
            assertFalse(field<Boolean>(activity.get(), "initialized"))
            assertFalse(field<Button>(activity.get(), "startButton").isEnabled)
            assertTrue(
                field<TextView>(activity.get(), "status").text.contains("Reopen the app to retry")
            )
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(activity)
        }
    }

    @Test
    fun confirmedStopCancelsPendingStartBeforeSettingsReturn() = runBlocking {
        shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val activity = open()
        try {
            field<Button>(activity.get(), "startButton").performClick()
            await { !field<Boolean>(activity.get(), "savingStart") }
            fun buttons(view: android.view.View): Sequence<Button> = sequence {
                if (view is Button) yield(view)
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) yieldAll(buttons(view.getChildAt(i)))
                }
            }
            buttons(activity.get().findViewById(android.R.id.content))
                .single { it.text == "Stop Tracking" }
                .performClick()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick()
            await { !field<Boolean>(activity.get(), "pendingStart") }
            assertFalse(field<Boolean>(activity.get(), "pendingStart"))
            assertFalse(app.operational.value.starting)
            assertNull(app.operational.value.error)
            shadowOf(gps).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            activity.pause().resume()
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            close(activity)
        }
    }

    @After
    fun finishApplicationRecorder() =
        kotlinx.coroutines.runBlocking {
            // Application-owned IO must finish before Robolectric replaces its Android sandbox.
            // Otherwise native fsync/runtime initialization can race the next SDK's font
            // extraction.
            val application: android.app.Application = ApplicationProvider.getApplicationContext()
            (application as? TrackingApp)?.finishForTests()
            Unit
        }
}
