package org.gnss.tracking

import android.Manifest
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** 20 Nov 2026 refinement: real-state presentation, retired routes and consolidated screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class V46RefinementTest {
    private val app get() = ApplicationProvider.getApplicationContext<TrackingApp>()

    private suspend fun settle() = repeat(25) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }

    private suspend fun until(check: () -> Boolean) = withTimeout(15000) {
        // The physical-test journal refresh is a scheduled poll; advance looper time too.
        while (!check()) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); delay(10) }
    }

    private fun shown(a: MainActivity) =
        nativeViews(a.findViewById(android.R.id.content)).filter { it.isShown }

    private fun texts(a: MainActivity) = shown(a).filterIsInstance<TextView>().map { it.text.toString() }

    private fun field(a: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)

    private fun setReachable(value: Boolean?) =
        Sender::class.java.getDeclaredField("commandReachable").apply { isAccessible = true }.set(app.sender, value)

    @After fun finish() = runBlocking { app.finishForTests() }

    @Test
    fun savedSosWithFailedDeliveryStaysPendingWithoutARedFailureAndKeepsItsIdentity() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            until { shown(a).filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled } }
            val saved = withContext(Dispatchers.IO) {
                app.repository.saveSos(Protocol.newId(), System.currentTimeMillis(), System.currentTimeMillis(), null, Health()).row
            }
            // The sender's real retryable failure path: the exception message reaches Room.
            withContext(Dispatchers.IO) {
                app.repository.fail(saved, WifiUnavailable().message!!, System.currentTimeMillis() + 60_000, false,
                    app.repository.state().endpointGeneration)
            }
            setReachable(false)
            app.operational.value = Operational(health = Health(wifi_connected = false))
            until { (field(a, "sosStatus") as TextView).text.startsWith("SOS pending") }
            settle()
            val row = withContext(Dispatchers.IO) { app.repository.dao.latestSos() }!!
            assertEquals("Original event identity retained", saved.messageId, row.messageId)
            assertNull("No Command receipt is implied", row.deliveredAt)
            assertEquals("Wi-Fi unavailable", withContext(Dispatchers.IO) { app.repository.state().operationalError })
            assertFalse("A retryable attempt is not shown as a Tracking failure", texts(a).any { it.contains("Wi-Fi unavailable") })
            assertFalse(texts(a).contains("Command offline"))
            assertTrue(texts(a).contains("Not set up") || texts(a).contains("Unreachable"))
            assertFalse(texts(a).any { it.contains("Received") })
            // A paused receiver is a genuine problem and stays visible.
            withContext(Dispatchers.IO) { app.repository.pauseReceiver("Receiver rejected configuration", app.repository.state().endpointGeneration) }
            until { texts(a).contains("Receiver rejected configuration") }
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun commandIndicatorFollowsActualCommandResponsesNotWifi() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            until { shown(a).filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled } }
            withContext(Dispatchers.IO) { app.repository.settings("A", "Alpha", "http://192.168.1.2:8080", 10) }
            // The live Sender may reset its own contact state (endpoint generation,
            // connectivity callbacks) while the test runs; re-apply the state under test
            // with a fresh Operational emission until the Tracking row renders it.
            var tick = 0
            suspend fun shows(reachable: Boolean?, text: String) = until {
                setReachable(reachable)
                app.operational.value = Operational(health = Health(wifi_connected = true, battery_percent = 50 + tick++ % 2))
                texts(a).contains(text)
            }
            shows(null, "Not checked yet")
            assertFalse("Unknown is not offline", texts(a).contains("Unreachable"))
            assertFalse("Wi-Fi alone is not Command contact", texts(a).contains("Reachable"))
            shows(true, "Reachable")
            shows(false, "Unreachable")
            assertFalse(texts(a).any { it.contains("Saved data will retry") || it.contains("Observations retained until received") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun eachSaveBarSavesOnlyItsOwnPageAndIgnoresTheOtherDraft() = runBlocking {
        withContext(Dispatchers.IO) { app.repository.settings("A", "Alpha", "http://192.168.1.2:8080", 10) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            until { shown(a).filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled } }
            until { (field(a, "endpoint") as TextView).text.toString() == "http://192.168.1.2:8080" }
            fun save() = shown(a).filterIsInstance<Button>().single { it.text == "Save" }.performClick()
            openNativeScreen(a, "Party & Command"); settle()
            (field(a, "endpoint") as TextView).text = "http://192.168.1.9:8080"
            openNativeScreen(a, "Tracking policy"); settle()
            (field(a, "interval") as TextView).text = "30"
            settle()
            save()
            until { kotlinx.coroutines.runBlocking(Dispatchers.IO) { app.repository.state() }.localInterval == 30 }
            assertEquals("Unsaved address draft is not committed by Tracking policy",
                "http://192.168.1.2:8080", withContext(Dispatchers.IO) { app.repository.state() }.endpoint)
            (field(a, "interval") as TextView).text = "3"
            openNativeScreen(a, "Party & Command"); settle()
            save()
            until { kotlinx.coroutines.runBlocking(Dispatchers.IO) { app.repository.state() }.endpoint == "http://192.168.1.9:8080" }
            assertEquals("Invalid interval draft neither blocks nor rides along", 30,
                withContext(Dispatchers.IO) { app.repository.state() }.localInterval)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun deliveredSosCardExpiresOnItsDeadlineWithoutADatabaseEmission() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            until { shown(a).filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled } }
            val captured = System.currentTimeMillis() - 30 * 60_000L + 3_000L
            withContext(Dispatchers.IO) {
                val row = app.repository.saveSos(Protocol.newId(), captured, captured, null, Health()).row
                app.repository.dao.updateRow(app.repository.dao.row(row.sequence)!!.copy(deliveredAt = "2026-11-22T00:00:00Z"))
            }
            until { texts(a).any { it.startsWith("Raised ") } }
            // No further outbox write: only the scheduled deadline can hide the card.
            until { texts(a).none { it.startsWith("Raised ") } }
            assertTrue("Needs attention returns once the card is gone", texts(a).contains("Add a Command address"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun undeliverableSosSaysWhyAndKeepsTheUnblockingAction() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            until { shown(a).filterIsInstance<Button>().any { it.text == "Start Tracking" && it.isEnabled } }
            withContext(Dispatchers.IO) {
                app.repository.saveSos(Protocol.newId(), System.currentTimeMillis(), System.currentTimeMillis(), null, Health())
            }
            until { texts(a).contains("Not sent · add a Command address") }
            assertFalse(texts(a).contains("Waiting for Command receipt · retries automatically"))
            assertTrue("Setup stays reachable during SOS", texts(a).contains("Add a Command address"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun retiredDestinationsHaveNoEntriesAndRestoredRoutesLandOnTheirOwners() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            settle()
            val retired = listOf("GNSS details", "Command contact", "Transfer recovery", "Storage health", "Identity & verification details")
            for (screen in listOf("Diagnostics", "Saved data & sync", "Manage Command access")) {
                openNativeScreen(a, screen)
                settle()
                val labels = texts(a) + shown(a).mapNotNull { it.contentDescription?.toString() }
                for (name in retired) assertFalse("$name entry on $screen", labels.any { it.startsWith(name) })
            }
            @Suppress("UNCHECKED_CAST") val navigate = field(a, "navigateTo") as (String) -> Unit
            for ((old, owner) in listOf("Storage health" to "SAVED DATA & SYNC", "Transfer recovery" to "SAVED DATA & SYNC",
                "GNSS details" to "Diagnostics", "Command contact" to "Diagnostics",
                "Identity & verification details" to "This phone ID")) {
                navigate(old)
                settle()
                assertTrue("$old → $owner", texts(a).contains(owner))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun manageAccessShowsIdentityEvidenceDirectlyAndDiagnosticToolsShowOnlyRealResults() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            settle()
            openNativeScreen(a, "Manage Command access")
            settle()
            val buttons = shown(a).filterIsInstance<Button>().map { it.text.toString() }
            assertTrue(buttons.toString(), buttons.contains("Copy phone ID"))
            assertFalse("Destructive access actions only after enrollment: $buttons",
                buttons.any { it == "Disable remote control" || it == "Re-enroll Command" })
            assertTrue(texts(a).contains("This phone ID"))
            assertEquals("Only the private credential is editable before enrollment", listOf("Paste Command credential"),
                shown(a).filterIsInstance<android.widget.EditText>().map { it.hint.toString() })

            openNativeScreen(a, "Diagnostic tools")
            settle()
            assertFalse("No result before a real key event", texts(a).contains("Detected key events"))
            assertEquals(1, shown(a).filterIsInstance<Button>().count { it.text.contains("Export") })
            shown(a).filterIsInstance<Button>().single { it.text == "Start Test" }.performClick()
            settle()
            assertFalse(texts(a).contains("Detected key events"))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            settle()
            until { texts(a).contains("Detected key events") }
            assertTrue("Key results keep their own export", shown(a).filterIsInstance<Button>().any { it.text == "Export test results" })
            shown(a).filterIsInstance<Button>().single { it.text == "Stop Test" }.performClick()
            settle()

            openNativeScreen(a, "Field readiness")
            settle()
            assertFalse("Granted permission offers no fix action", shown(a).filterIsInstance<Button>().any { it.contentDescription == "Allow precise location · Precise location" })
            assertTrue(texts(a).contains("Precise location"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun activitySearchAndFiltersSpanEveryRecordTypeAndRecordsOpenInASheet() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            // Fresh install: the address is required before Start; no promise that tracking already works.
            until { texts(a).any { it.startsWith("Required before tracking can start.") } }
            assertFalse(texts(a).any { it.contains("Tracking works offline") })
            val device = withContext(Dispatchers.IO) { app.repository.state().deviceId }
            withContext(Dispatchers.IO) {
                app.repository.dao.insertCommand(
                    CommandRecord(Protocol.newId(), "authority", device, "enrollment", "alert", 1, System.currentTimeMillis(),
                        state = "unknown",
                        requested = """{"mode":"urgent","message":"Check in at ridge","duration_s":60,"repeat_s":10}"""))
                app.repository.saveSos(Protocol.newId(), System.currentTimeMillis(), System.currentTimeMillis(), null, Health())
            }
            openNativeScreen(a, "Diagnostics")
            shown(a).filterIsInstance<Button>().single { it.text == "Activity" }.performClick()
            settle()
            fun instruction() = shown(a).firstOrNull { it.isClickable && it.contentDescription?.contains("Open instruction details") == true }
            fun sos() = shown(a).firstOrNull { it.isClickable && it.contentDescription?.startsWith("Delivery pending.") == true }
            until { instruction() != null && sos() != null }
            assertTrue("One Filters control beside the type chips", shown(a).filterIsInstance<Button>().any { it.text == "Filters" })
            assertFalse("No nested history entry in Activity", texts(a).contains("History search & filters"))
            val search = shown(a).filterIsInstance<EditText>().single { it.hint == "Search activity" }
            search.setText("ridge")
            settle()
            assertNotNull("Search matches the alert instruction", instruction())
            assertNull(sos())
            assertTrue(texts(a).contains("No matching SOS events"))
            search.setText("SOS")
            settle()
            assertNotNull("The same search reaches SOS records", sos())
            assertNull(instruction())
            assertTrue(texts(a).contains("No matching instructions"))
            assertTrue(texts(a).contains("No matching phone alerts"))
            search.setText("")
            settle()
            instruction()!!.performClick()
            settle()
            val sheet = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue("Records open in a sheet", sheet.isShowing)
            assertTrue(nativeViews(sheet.window!!.decorView).filterIsInstance<TextView>().any { it.text == "Instruction details" })
            assertEquals("Diagnostics stays underneath the sheet", "Diagnostics", field(a, "route"))
            sheet.dismiss()
            settle()
            assertNotNull("Closing the sheet returns to the list", instruction())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun sosDockHasNoSeparateTrackAndHoldProgressFillsTheButton() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            settle()
            val button = shown(a).filterIsInstance<Button>().single { it.text == "Hold to activate SOS" }
            val dock = button.parent as android.view.ViewGroup
            assertTrue("No visible progress track/divider in the dock",
                nativeViews(dock).none { it.isShown && it is android.widget.ProgressBar })
            val fill = (button.background as android.graphics.drawable.LayerDrawable).getDrawable(1)
            assertEquals(0, fill.level)
            val bar = nativeViews(dock).filterIsInstance<HoldProgressBar>().single()
            bar.progress = 50 // the value SosHoldGesture writes while held
            assertEquals(5000, fill.level)
            bar.progress = 0
            assertEquals(0, fill.level)
            assertEquals("No SOS from a progress write", 0, withContext(Dispatchers.IO) { app.repository.dao.sosHistory().size })
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun trackingPolicyHasOnlyAdaptiveStorageAndPolicyDetails() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            settle()
            openNativeScreen(a, "Tracking policy")
            settle()
            val controls = shown(a).filter { it is android.widget.Spinner || it is android.widget.EditText || (it is Button && it !is android.widget.CompoundButton && it.text.isNotEmpty() && it.parent?.parent?.let { p -> p is android.widget.ScrollView || (p as View).id != View.NO_ID } == true) }
            // v3: Tracking policy owns the reporting interval, adaptive storage and the facts in effect.
            assertTrue("Interval selector", controls.any { it is android.widget.Spinner && it.contentDescription == "Reporting interval" })
            assertTrue(shown(a).filterIsInstance<android.widget.Switch>().any { it.text == "Adaptive storage" })
            assertFalse("Save appears only after a change", shown(a).filterIsInstance<Button>().any { it.text == "Save" })
            assertTrue(texts(a).contains("IN EFFECT"))
            assertFalse("Policy details merged into this page", shown(a).any { it.contentDescription?.startsWith("Policy details. ") == true })
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun fieldReadinessOffersTheResolvingActionOnlyWhenNeeded() = runBlocking {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            settle()
            openNativeScreen(a, "Field readiness")
            settle()
            val allow = shown(a).filterIsInstance<Button>().single { it.contentDescription == "Allow precise location · Precise location" }
            assertEquals(View.VISIBLE, allow.visibility)
            assertEquals("Fix", allow.text.toString())
            assertTrue(texts(a).any { it.startsWith("Required") })
        } finally { controller.pause().stop().destroy() }
    }
}
