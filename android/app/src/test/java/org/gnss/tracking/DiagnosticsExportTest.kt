package org.gnss.tracking

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
abstract class DiagnosticsExportChecks {
    private fun status(a: MainActivity) =
        MainActivity::class
            .java
            .getDeclaredField("exportStatus")
            .apply { isAccessible = true }
            .get(a) as TextView

    @Test
    fun exportUsesOfflineDocumentPickerWithoutStartingOrStoppingTracking() {
        val app: TrackingApp = ApplicationProvider.getApplicationContext()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun buttons(view: android.view.View): List<Button> =
                (if (view is Button) listOf(view) else emptyList()) +
                    if (view is android.view.ViewGroup)
                        (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                    else emptyList()
            val controls = buttons(controller.get().findViewById(android.R.id.content))
            controls.single { it.text == "Diagnostics" }.performClick()
            val button = controls.single { it.text == "Export diagnostics" }
            assertTrue(button.isShown)
            val before = app.operational.value
            button.performClick()
            val intent = shadowOf(controller.get()).nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals("application/zip", intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("gnss-diagnostics-"))
            assertEquals(before, app.operational.value)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun actualDocumentExportKeepsNativeSourceAndWakeLockRunning() = runBlocking {
        val app: TrackingApp = ApplicationProvider.getApplicationContext()
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val service = Robolectric.buildService(TrackingService::class.java).create()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val file = File(app.cacheDir, "selected-diagnostics.zip")
        try {
            app.physicalButtonTest.stop()
            app.physicalButtonTest.clearResults()
            app.physicalButtonTest.start()
            app.physicalButtonTest.observeActivityKey(
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.ACTION_DOWN,
                0,
                true,
                true,
                false,
            )
            app.physicalButtonTest.stop()
            assertEquals("DETECTED", app.recorder.physicalButtonHistory().activityResult)
            service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val generation = app.diagnostics.value!!.serviceGeneration
            val listeners = shadowOf(manager).getLocationUpdateListeners().toList()
            MainActivity::class
                .java
                .getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java,
                )
                .apply { isAccessible = true }
                .invoke(
                    controller.get(),
                    MainActivity.EXPORT_DIAGNOSTICS,
                    Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(file)),
                )
            withTimeout(10000) {
                while (!status(controller.get()).text.startsWith("Diagnostics exported")) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue(file.length() > 0)
            java.util.zip.ZipFile(file).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                val report =
                    zip.getInputStream(zip.getEntry("physical-button-report.json"))
                        .bufferedReader()
                        .readText()
                assertTrue(report.contains("DETECTED"))
                assertTrue(report.contains("VOLUME_UP"))
                assertTrue(report.contains("MECHANISM UNAVAILABLE"))
                assertTrue(report.contains("\"manufacturer\""))
                assertTrue(report.contains("\"model\""))
                assertTrue(report.contains("\"android_version\""))
                assertTrue(report.contains("\"android_api\""))
                assertTrue(report.contains("locked_screen"))
            }
            assertEquals(generation, app.diagnostics.value!!.serviceGeneration)
            assertEquals(listeners, shadowOf(manager).getLocationUpdateListeners().toList())
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertFalse(shadowOf(service.get()).isStoppedBySelf)
        } finally {
            controller.pause().stop().destroy()
            service.destroy()
            file.delete()
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

// API 35 loads a different native runtime; each API gets its own Gradle test-class fork.
@Config(sdk = [26], application = TrackingApp::class)
class DiagnosticsExportApi26Test : DiagnosticsExportChecks()

@Config(sdk = [28], application = TrackingApp::class)
class DiagnosticsExportApi28Test : DiagnosticsExportChecks()

@Config(sdk = [35], application = TrackingApp::class)
class DiagnosticsExportApi35Test : DiagnosticsExportChecks()
