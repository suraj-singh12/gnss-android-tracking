package org.gnss.tracking

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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
import org.robolectric.annotation.LooperMode

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
    @LooperMode(LooperMode.Mode.PAUSED)
    fun touchHoldSavesOnceOfflineButEarlyReleaseAndActivityPauseSaveNothing() = runBlocking {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val button =
                buttons(activity.get().window.decorView as ViewGroup).single {
                    it.text.startsWith("SOS —")
                }
            button.layout(0, 0, 240, 64)
            fun touch(action: Int) {
                val e = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 120f, 32f, 0)
                button.dispatchTouchEvent(e)
                e.recycle()
            }
            fun advance(ms: Long) =
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
            touch(MotionEvent.ACTION_DOWN)
            advance(600)
            activity.pause().stop()
            advance(2000)
            assertTrue(app.repository.dao.sosHistory().isEmpty())
            activity.start().resume()
            touch(MotionEvent.ACTION_DOWN)
            advance(600)
            touch(MotionEvent.ACTION_UP)
            advance(1000)
            assertTrue(app.repository.dao.sosHistory().isEmpty())
            touch(MotionEvent.ACTION_DOWN)
            advance(1200)
            advance(2400)
            touch(MotionEvent.ACTION_UP)
            val row = saved()
            assertEquals(1, app.repository.dao.sosHistory().size)
            assertNull(row.deliveredAt)
            val message = Protocol.decodeMessage(row.json)
            assertEquals("sos", message.type)
            assertEquals(row.messageId, message.sos!!.event_id)
            withTimeout(10000) {
                while (app.sosNotice.value != "SOS saved on phone") {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            // The engine's already-tested storage failure notice must remain visible
            // even when this earlier durable SOS is still pending.
            app.sosNotice.value =
                "SOS NOT SAVED — storage unavailable. Retry activation; use another emergency path."
            val status =
                activity
                    .get()
                    .javaClass
                    .getDeclaredField("sosStatus")
                    .apply { isAccessible = true }
                    .get(activity.get()) as android.widget.TextView
            withTimeout(10000) {
                while (!status.text.contains("SOS NOT SAVED")) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue(status.text.contains("awaiting Command receipt"))
            assertEquals(1, app.repository.dao.sosHistory().size)
        } finally {
            activity.pause().stop().destroy()
        }
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
