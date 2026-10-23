package org.gnss.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual framework notification RemoteViews, not a fabricated in-app notification card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationVisualTest {
    private fun views(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    @Test
    fun trackingAndUrgentNativeContentRenderWithContainedActions() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val dir = System.getenv("GNSS_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
        val row =
            CommandRecord(
                Protocol.newId(),
                Protocol.newId(),
                app.repository.state().deviceId,
                Protocol.newId(),
                "alert",
                1,
                System.currentTimeMillis(),
                attempted = true,
            )
        try {
            for (night in listOf(false, true)) for (width in listOf(360, 480)) for (font in
                listOf(1f, 1.5f)) {
                app.getSharedPreferences("appearance", 0).edit().putBoolean("night", night).commit()
                val config =
                    Configuration(app.resources.configuration).apply {
                        fontScale = font
                        uiMode =
                            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (night) Configuration.UI_MODE_NIGHT_YES
                                else Configuration.UI_MODE_NIGHT_NO
                    }
                org.robolectric.RuntimeEnvironment.setQualifiers(
                    "w${width}dp-h800dp-${if (night) "night" else "notnight"}"
                )
                app.resources.updateConfiguration(config, app.resources.displayMetrics)
                android.content.res.Resources.getSystem()
                    .updateConfiguration(config, app.resources.displayMetrics)
                val context = app.createConfigurationContext(config)
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        TrackingService.CHANNEL,
                        "Tracking",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
                NotificationPhoneAlertOutput(context)
                    .attempt(row, PhoneAlertSpec("urgent", "Check in with Command", 60, 10), 60000)
                val urgent =
                    manager.activeNotifications
                        .first { it.tag?.endsWith(row.id) == true }
                        .notification
                for ((name, notification) in
                    listOf("tracking" to TrackingNotification.build(context), "urgent" to urgent)) {
                    val view =
                        Notification.Builder.recoverBuilder(context, notification)
                            .createBigContentView()
                            .apply(context, null)
                    val w = (width * context.resources.displayMetrics.density).toInt()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, w, view.measuredHeight)
                    for (action in notification.actions) {
                        val label =
                            views(view).filterIsInstance<TextView>().firstOrNull {
                                it.text.toString() == action.title.toString() &&
                                    it.visibility == View.VISIBLE
                            }
                        assertNotNull("Framework must render ${action.title}", label)
                        assertTrue(
                            "Native action label must not be ellipsized",
                            (0 until label!!.lineCount).all {
                                label.layout.getEllipsisCount(it) == 0
                            },
                        )
                        val bounds = android.graphics.Rect(0, 0, label.width, label.height)
                        (view as ViewGroup).offsetDescendantRectToMyCoords(label, bounds)
                        assertTrue(
                            "Action must stay in native notification content",
                            bounds.left >= 0 &&
                                bounds.right <= w &&
                                bounds.top >= 0 &&
                                bounds.bottom <= view.height,
                        )
                    }
                    if (dir != null) {
                        val bitmap = Bitmap.createBitmap(w, view.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(context.getColor(R.color.surface))
                        view.draw(canvas)
                        File(
                                dir,
                                "android-${if(night) "night" else "day"}-$width-$font-notification-$name.png",
                            )
                            .outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            app.finishForTests()
        }
    }
}
