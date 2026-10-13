package org.gnss.tracking

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TrackingApp::class)
class SosActivityTest {
    private lateinit var app: TrackingApp

    @Before
    fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        // Keep endpoint unconfigured: UI activation must still save offline.
        app.repository.state()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java))
            .setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun buttons(group: ViewGroup): List<Button> =
        (0 until group.childCount).flatMap {
            val view = group.getChildAt(it)
            when (view) {
                is Button -> listOf(view)
                is ViewGroup -> buttons(view)
                else -> emptyList()
            }
        }

    private suspend fun saved(): Outbound =
        withTimeout(10000) {
            var row = app.repository.dao.latestSos()
            while (row == null) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
                row = app.repository.dao.latestSos()
            }
            row
        }

    @Test
    fun ordinaryTapCannotActivateAndLongPressSurvivesActivityDestruction() = runBlocking {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val sos =
            buttons(activity.get().window.decorView as ViewGroup).first {
                it.text.startsWith("SOS —")
            }
        sos.performClick()
        assertNull(app.repository.dao.latestSos())
        assertTrue(sos.performLongClick())
        assertTrue(app.sosNotice.value!!.contains("SOS"))
        activity.pause().stop().destroy()
        val row = saved()
        assertEquals("sos", Protocol.decodeMessage(row.json).type)
        assertFalse(app.activityVisible)
        assertNull(row.deliveredAt)
    }

    @Test
    fun centralEngineWorksWhileServiceActiveAndActivityHiddenWithoutNewLocationListener() =
        runBlocking {
            val service = Robolectric.buildService(TrackingService::class.java).create()
            try {
                service.get().onStartCommand(Intent(app, TrackingService::class.java), 0, 1)
                val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
                activity.pause().stop().destroy()
                val manager = app.getSystemService(LocationManager::class.java)
                assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
                val saved = app.sos.activate(SosTrigger.VOLUME_UP)
                assertTrue(saved.created)
                assertFalse(app.activityVisible)
                assertNull(Protocol.decodeMessage(saved.row.json).fix)
                assertEquals(1, shadowOf(manager).getLocationUpdateListeners().size)
            } finally {
                service.destroy()
            }
        }

    @After fun finish() = runBlocking { app.finishForTests() }
}
