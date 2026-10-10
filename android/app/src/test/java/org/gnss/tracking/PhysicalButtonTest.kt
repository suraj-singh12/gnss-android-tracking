package org.gnss.tracking

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric

class PhysicalButtonControllerTest {
  private val records = mutableListOf<Pair<DiagnosticEvent, PhysicalButtonEvidence?>>()
  private var changes = 0
  private val controller =
      PhysicalButtonTestController(
          record = { event, evidence -> records += event to evidence },
          changed = { changes++ },
      )

  @Test
  fun explicitLifecycleSeparatesDownUpRepeatsAndNeverInvokesSos() {
    controller.observeActivityKey(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.ACTION_DOWN,
        0,
        true,
        true,
        false,
    )
    assertTrue(records.isEmpty()) // Off by default.
    controller.start()
    assertTrue(controller.listening)
    assertTrue(
        controller.observeActivityKey(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.ACTION_DOWN,
            0,
            true,
            true,
            false,
        ))
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
        )) // Character/keyboard input is never collected.
    controller.stop()
    assertFalse(controller.listening)
    assertFalse(
        controller.observeActivityKey(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.ACTION_DOWN,
            0,
            true,
            true,
            false,
        ))
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
    val keyEvents = records.mapNotNull { it.second }
    assertEquals(
        listOf(PhysicalButtonAction.DOWN, PhysicalButtonAction.DOWN, PhysicalButtonAction.UP),
        keyEvents.map { it.action },
    )
    assertEquals(listOf(0, 2, 0), keyEvents.map { it.repeatCount })
    assertEquals("VOLUME_UP", keyEvents.first().keyName)
    assertEquals(
        1, keyEvents.count { it.action == PhysicalButtonAction.DOWN && it.repeatCount == 0 })
    assertEquals(5, changes)
    // This isolated controller accepts only typed diagnostic callbacks; it has no SOS callback.
    assertTrue(records.none { it.first == DiagnosticEvent.SOS_TRIGGER_DETECTED })
  }

  @Test
  fun availableHardwareKeysAreWhitelistedAndBoundedNames() {
    controller.start()
    for (code in
        listOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_FOCUS,
            KeyEvent.KEYCODE_POWER,
        )) {
      assertTrue(
          controller.observeActivityKey(
              code,
              KeyEvent.ACTION_DOWN,
              0,
              true,
              true,
              false,
          ))
    }
    assertTrue(
        records
            .mapNotNull { it.second }
            .all {
              physicalButtonKeyName(it.keyCode) == it.keyName &&
                  it.source == PhysicalButtonSource.ACTIVITY
            })
    assertTrue(PhysicalButtonTestController.BACKGROUND_STATUS.contains("MECHANISM UNAVAILABLE"))
    assertTrue(PhysicalButtonTestController.MEDIA_SESSION_STATUS.contains("no session"))
  }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PhysicalButtonJournalTest {
  @get:Rule val temporary = TemporaryFolder()
  private var time = 1791642738000L

  private fun journal(folder: File = temporary.newFolder()) =
      DiagnosticJournal(
          folder,
          DiagnosticJournal.Limits(chunkBytes = 256 * 1024, timelineBytes = 4 * 1024 * 1024),
      )

  private fun controller(journal: DiagnosticJournal) =
      PhysicalButtonTestController(
          record = { event, evidence ->
            time += 1000
            journal.event(
                DiagnosticEntry(
                    event,
                    Instant.ofEpochMilli(time).toString(),
                    time,
                    "button-test-process",
                    physicalButton = evidence,
                ))
          })

  private fun tap(button: PhysicalButtonTestController, code: Int = KeyEvent.KEYCODE_VOLUME_UP) {
    button.observeActivityKey(code, KeyEvent.ACTION_DOWN, 0, true, true, false)
    button.observeActivityKey(code, KeyEvent.ACTION_UP, 0, true, true, false)
  }

  @Test
  fun saveStopRestartClearAndClassificationUseExistingBoundedDiagnostics() {
    val folder = temporary.newFolder()
    val firstJournal = journal(folder)
    val first = controller(firstJournal)
    assertFalse(first.listening)
    first.start()
    tap(first)
    first.stop()
    val completed = firstJournal.physicalButtonHistory()
    assertEquals("DETECTED", completed.activityResult)
    assertEquals(1, completed.totalPresses)
    assertEquals(2, completed.retainedEvents.size)
    assertEquals("2026-10-10T14:32:21Z", completed.retainedEvents.last().at)

    // A new controller models a process recreation: opt-in is off, old evidence remains.
    val reopenedJournal = journal(folder)
    val restarted = controller(reopenedJournal)
    assertFalse(restarted.listening)
    assertEquals(completed.retainedEvents, reopenedJournal.physicalButtonHistory().retainedEvents)
    restarted.clearResults()
    val cleared = journal(folder).physicalButtonHistory()
    assertTrue(cleared.retainedEvents.isEmpty())
    assertEquals(0, cleared.totalPresses)
    assertNull(cleared.startedAt)
    assertEquals("INCONCLUSIVE", cleared.activityResult)
  }

  @Test
  fun noDeliveredEventIsNotDetectedOnlyForCompletedUninterruptedForegroundRun() {
    val noInput = journal()
    val completed = controller(noInput)
    completed.start()
    completed.stop()
    assertEquals("NOT_DETECTED", noInput.physicalButtonHistory().activityResult)

    val interrupted = journal()
    val background = controller(interrupted)
    background.start()
    background.activityPaused()
    background.activityResumed()
    background.stop()
    val report = interrupted.physicalButtonHistory()
    assertEquals("INCONCLUSIVE", report.activityResult)
    assertTrue(report.activityWasInterrupted)
  }

  @Test
  fun clearingDuringTestStartsANewEvidenceIntervalWithoutDisablingListener() {
    val journal = journal()
    val test = controller(journal)
    test.start()
    tap(test)
    test.clearResults()
    assertTrue(test.listening)
    tap(test, KeyEvent.KEYCODE_VOLUME_DOWN)
    test.stop()
    val report = journal.physicalButtonHistory()
    assertEquals("DETECTED", report.activityResult)
    assertEquals(1L, report.totalPresses)
    assertEquals(2, report.retainedEvents.size)
    assertEquals("VOLUME_DOWN", report.retainedEvents.first().evidence.keyName)
  }

  @Test
  fun visibleHistoryIsBoundedAndTruncationIsExplicit() {
    val journal = journal()
    val test = controller(journal)
    test.start()
    repeat(PhysicalButtonTestController.MAX_DISPLAY_EVENTS + 5) {
      test.observeActivityKey(
          KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0, true, true, false)
    }
    test.stop()
    val report = journal.physicalButtonHistory()
    assertEquals(PhysicalButtonTestController.MAX_DISPLAY_EVENTS + 5L, report.totalPresses)
    assertEquals(PhysicalButtonTestController.MAX_DISPLAY_EVENTS, report.retainedEvents.size)
    assertTrue(report.olderEventsOmitted)
    assertEquals("DETECTED", report.activityResult)
  }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = TrackingApp::class)
class PhysicalButtonActivityTest {
  @Test
  fun diagnosticsEntryCapturesVolumeButNeverCreatesSosEvenWhenLegacyTriggerEnabled() = runBlocking {
    val app: TrackingApp = ApplicationProvider.getApplicationContext()
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    try {
      val root = activity.findViewById<android.widget.FrameLayout>(android.R.id.content)
      val content =
          ((root.getChildAt(0) as android.widget.ScrollView).getChildAt(0)
              as android.widget.LinearLayout)
      fun descendants(view: android.view.View): Sequence<android.view.View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup)
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
      }
      fun button(label: String) =
          descendants(content).filterIsInstance<android.widget.Button>().single { it.text == label }
      (0 until content.childCount)
          .map { content.getChildAt(it) }
          .filterIsInstance<android.widget.CheckBox>()
          .single()
          .isChecked = true
      button("Physical Button Test").performClick()
      button("Start Test").performClick()
      for (action in
          listOf(
              KeyEvent.ACTION_DOWN,
              KeyEvent.ACTION_UP,
              KeyEvent.ACTION_DOWN,
              KeyEvent.ACTION_UP,
              KeyEvent.ACTION_DOWN,
              KeyEvent.ACTION_UP)) {
        activity.dispatchKeyEvent(KeyEvent(1L, 1L, action, KeyEvent.KEYCODE_VOLUME_UP, 0))
      }
      assertTrue(app.physicalButtonTest.listening)
      assertTrue(app.repository.dao.sosHistory().isEmpty())
      assertEquals(false, app.operational.value.tracking)
      val history = app.recorder.physicalButtonHistory()
      assertEquals("DETECTED", history.activityResult)
      assertEquals(3L, history.totalPresses)
      button("Stop Test").performClick()
      activity.dispatchKeyEvent(
          KeyEvent(2L, 2L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0))
      assertTrue(app.repository.dao.sosHistory().isEmpty())
    } finally {
      activity.finish()
    }
  }

  @After
  fun closeRecorder(): Unit = runBlocking {
    (ApplicationProvider.getApplicationContext<android.app.Application>() as? TrackingApp)
        ?.finishForTests()
    Unit
  }
}
