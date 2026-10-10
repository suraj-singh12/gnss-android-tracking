package org.gnss.tracking

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class PhysicalButtonControllerTest {
    private val records = mutableListOf<Pair<DiagnosticEvent, PhysicalButtonEvidence?>>()
    private val controller =
        PhysicalButtonTestController { event, evidence -> records += event to evidence }

    @Test
    fun startStopAndRepeatsCountOnlyActualDownPressesWithoutSos() {
        assertFalse(
            controller.observeActivityKey(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN,
                0,
                true,
                true,
                false,
            )
        )
        controller.start()
        assertTrue(
            controller.observeActivityKey(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN,
                0,
                true,
                true,
                false,
            )
        )
        controller.observeActivityKey(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.ACTION_DOWN,
            2,
            true,
            true,
            false,
        )
        controller.observeActivityKey(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.ACTION_UP,
            0,
            true,
            true,
            false,
        )
        assertFalse(
            controller.observeActivityKey(
                KeyEvent.KEYCODE_A,
                KeyEvent.ACTION_DOWN,
                0,
                true,
                true,
                false,
            )
        )
        controller.stop()
        assertEquals(
            listOf(
                DiagnosticEvent.PHYSICAL_BUTTON_TEST_STARTED,
                DiagnosticEvent.PHYSICAL_BUTTON_KEY_EVENT,
                DiagnosticEvent.PHYSICAL_BUTTON_KEY_EVENT,
                DiagnosticEvent.PHYSICAL_BUTTON_KEY_EVENT,
                DiagnosticEvent.PHYSICAL_BUTTON_TEST_STOPPED,
            ),
            records.map { it.first },
        )
        assertEquals(
            1,
            records.mapNotNull { it.second }.count {
                it.action == PhysicalButtonAction.DOWN && it.repeatCount == 0
            },
        )
        assertTrue(records.none { it.first == DiagnosticEvent.SOS_TRIGGER_DETECTED })
    }

    @Test
    fun pauseStopsListeningAndRestoresTheNormalAdapterContract() {
        controller.start()
        controller.activityPaused()
        assertFalse(controller.listening)
        assertEquals(
            listOf(
                DiagnosticEvent.PHYSICAL_BUTTON_TEST_STARTED,
                DiagnosticEvent.PHYSICAL_BUTTON_ACTIVITY_PAUSED,
                DiagnosticEvent.PHYSICAL_BUTTON_TEST_STOPPED,
            ),
            records.map { it.first },
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhysicalButtonJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private var time = 1791642738000L

    private fun journal(folder: File = temporary.newFolder()) =
        DiagnosticJournal(folder, DiagnosticJournal.Limits(chunkBytes = 256 * 1024, timelineBytes = 4 * 1024 * 1024))

    private fun controller(journal: DiagnosticJournal) =
        PhysicalButtonTestController { event, evidence ->
            time += 1000
            journal.event(
                DiagnosticEntry(
                    event,
                    Instant.ofEpochMilli(time).toString(),
                    time,
                    "button-test-process",
                    physicalButton = evidence,
                )
            )
        }

    private fun tap(test: PhysicalButtonTestController, key: Int = KeyEvent.KEYCODE_VOLUME_UP) {
        test.observeActivityKey(key, KeyEvent.ACTION_DOWN, 0, true, true, false)
        test.observeActivityKey(key, KeyEvent.ACTION_UP, 0, true, true, false)
    }

    @Test
    fun evidenceSurvivesNewControllerClearIsBoundedAndExportsMetadata() {
        val directory = temporary.newFolder()
        val first = controller(journal(directory))
        first.start()
        tap(first)
        first.stop()
        assertEquals("DETECTED", journal(directory).physicalButtonHistory().activityResult)
        assertEquals(1L, journal(directory).physicalButtonHistory().totalPresses)

        // A recreated controller begins off while evidence in the established journal remains.
        val restarted = controller(journal(directory))
        assertFalse(restarted.listening)
        assertEquals(2, journal(directory).physicalButtonHistory().retainedEvents.size)
        restarted.clearResults()
        assertTrue(journal(directory).physicalButtonHistory().retainedEvents.isEmpty())

        val boundedJournal = journal()
        val bounded = controller(boundedJournal)
        bounded.start()
        repeat(PhysicalButtonTestController.MAX_DISPLAY_EVENTS + 5) {
            bounded.observeActivityKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0, true, true, false)
        }
        bounded.stop()
        val report = boundedJournal.physicalButtonHistory()
        assertEquals(PhysicalButtonTestController.MAX_DISPLAY_EVENTS, report.retainedEvents.size)
        assertTrue(report.olderEventsOmitted)
        assertNotNull(report.manufacturer)
        assertNotNull(report.androidVersion)
    }

    @Test
    fun interruptedAndCompletedEmptyRunsAreDistinguished() {
        val completed = journal()
        controller(completed).apply { start(); stop() }
        assertEquals("NOT_DETECTED", completed.physicalButtonHistory().activityResult)

        val interrupted = journal()
        controller(interrupted).apply { start(); activityPaused() }
        assertEquals("INCONCLUSIVE", interrupted.physicalButtonHistory().activityResult)
        assertTrue(interrupted.physicalButtonHistory().activityWasInterrupted)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TrackingApp::class)
class PhysicalButtonActivityTest {
    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun diagnosticSuppressesSosOnlyWhileListeningAndRetainsInterruptedEvidence() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TrackingApp>()
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = activityController.get()
        try {
            val views = descendants(activity.findViewById(android.R.id.content))
            views.filterIsInstance<CheckBox>().single().isChecked = true
            views.filterIsInstance<Button>().single { it.text == "Diagnostics" }.performClick()
            views.filterIsInstance<Button>().single { it.text == "Physical Button Test" }.performClick()
            views.filterIsInstance<Button>().single { it.text == "Start Test" }.performClick()
            repeat(3) { press ->
                val at = (press + 1) * 100L
                activity.dispatchKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0))
                activity.dispatchKeyEvent(KeyEvent(at, at + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0))
            }
            assertTrue(app.physicalButtonTest.listening)
            assertTrue(app.repository.dao.sosHistory().isEmpty())
            assertEquals(3L, app.recorder.physicalButtonHistory().totalPresses)

            activityController.pause()
            assertFalse(app.physicalButtonTest.listening)
            val interrupted = app.recorder.physicalButtonHistory()
            assertTrue(interrupted.activityWasInterrupted)
            assertEquals("DETECTED", interrupted.activityResult)

            activityController.resume()
            repeat(3) { press ->
                val at = 1000L + press * 100L
                activity.dispatchKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0))
                activity.dispatchKeyEvent(KeyEvent(at, at + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0))
            }
            repeat(20) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(10)
            }
            assertFalse(app.repository.dao.sosHistory().isEmpty())
        } finally {
            activityController.stop().destroy()
        }
    }

    @After
    fun closeRecorder(): Unit = runBlocking {
        (ApplicationProvider.getApplicationContext<android.app.Application>() as? TrackingApp)
            ?.finishForTests()
        Unit
    }
}
