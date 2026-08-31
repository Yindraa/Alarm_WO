package com.missionalarm.runtime

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.app.R
import com.missionalarm.core.data.AlarmEntity
import com.missionalarm.core.data.AlarmInstanceEntity
import com.missionalarm.core.data.AlarmMissionConfigEntity
import com.missionalarm.core.data.AlarmOccurrenceEntity
import com.missionalarm.core.data.InstanceMissionEntity
import com.missionalarm.core.data.MathQuestionEntity
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmHostActivityMathTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.deleteDatabase(DATABASE_NAME)
    seedMathAlarm()
  }

  @After
  fun tearDown() {
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun wrongSignedAndRecreatedMathFlowCompletesDurably() {
    val scenario = ActivityScenario.launch<AlarmHostActivity>(
      AlarmHostActivity.intent(context, INSTANCE_ID),
    )
    try {
      waitForText(scenario, R.id.alarm_host_primary_action, "Mulai misi matematika")
      click(scenario, R.id.alarm_host_primary_action)
      waitForVisibility(scenario, R.id.alarm_host_math_workspace, View.VISIBLE)
      waitForText(scenario, R.id.alarm_host_math_question, "2 + 3 = ?")

      scenario.onActivity { activity ->
        val answer = activity.findViewById<EditText>(R.id.alarm_host_math_answer)
        val question = activity.findViewById<TextView>(R.id.alarm_host_math_question)
        val feedback = activity.findViewById<TextView>(R.id.alarm_host_math_feedback)
        val primary = activity.findViewById<Button>(R.id.alarm_host_primary_action)
        val keypad = activity.findViewById<ViewGroup>(R.id.alarm_host_math_keypad)
        assertTrue(answer.inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0)
        assertEquals(answer.id, question.labelFor)
        assertTrue(question.isAccessibilityHeading)
        assertTrue(answer.minHeight >= dp(activity, 48))
        assertTrue(primary.minHeight >= dp(activity, 48))
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, feedback.accessibilityLiveRegion)
        assertEquals(4, keypad.childCount)
        answer.setText("999")
        primary.performClick()
      }
      waitForText(scenario, R.id.alarm_host_math_feedback, "Belum tepat. Coba hitung kembali.")
      assertMissionProgress(0)

      answer(scenario, "5")
      waitForText(scenario, R.id.alarm_host_math_question, "4 − 6 = ?")
      assertMissionProgress(1)

      scenario.recreate()
      waitForText(scenario, R.id.alarm_host_primary_action, "Lanjutkan misi")
      click(scenario, R.id.alarm_host_primary_action)
      waitForText(scenario, R.id.alarm_host_math_question, "4 − 6 = ?")

      pressMathKey(scenario, "−")
      pressMathKey(scenario, "2")
      click(scenario, R.id.alarm_host_primary_action)
      waitForText(scenario, R.id.alarm_host_math_question, "3 × 4 = ?")
      answer(scenario, "12")
      waitForText(scenario, R.id.alarm_host_title, "Misi selesai")
      waitForText(scenario, R.id.alarm_host_progress, "Progres 3 dari 3")
    } finally {
      scenario.close()
    }

    val database = MissionAlarmDatabaseFactory.persistent(context)
    try {
      val instance = database.runtimeDao().findInstanceById(INSTANCE_ID)
      val history = database.runtimeDao().findHistoryByInstanceId(INSTANCE_ID)
      assertEquals("TERMINAL", instance?.runtimeState)
      assertEquals("SUCCESS", instance?.terminalResult)
      assertNotNull(history)
      assertEquals("VERIFIED_MISSION", history?.dismissMethod)
      assertEquals(3, history?.finalProgress)
    } finally {
      database.close()
    }
  }

  private fun answer(scenario: ActivityScenario<AlarmHostActivity>, value: String) {
    scenario.onActivity { activity ->
      activity.findViewById<EditText>(R.id.alarm_host_math_answer).setText(value)
      activity.findViewById<Button>(R.id.alarm_host_primary_action).performClick()
    }
  }

  private fun click(scenario: ActivityScenario<AlarmHostActivity>, viewId: Int) {
    scenario.onActivity { activity -> activity.findViewById<View>(viewId).performClick() }
  }

  private fun pressMathKey(scenario: ActivityScenario<AlarmHostActivity>, label: String) {
    scenario.onActivity { activity ->
      val keypad = activity.findViewById<ViewGroup>(R.id.alarm_host_math_keypad)
      val button = findButton(keypad, label)
        ?: throw AssertionError("Math key '$label' was not found")
      button.performClick()
    }
  }

  private fun findButton(root: ViewGroup, label: String): Button? {
    for (index in 0 until root.childCount) {
      when (val child = root.getChildAt(index)) {
        is Button -> if (child.text.toString() == label) return child
        is ViewGroup -> findButton(child, label)?.let { return it }
      }
    }
    return null
  }

  private fun waitForText(
    scenario: ActivityScenario<AlarmHostActivity>,
    viewId: Int,
    expected: String,
  ) = waitUntil("text '$expected' on view $viewId") {
    var actual: String? = null
    scenario.onActivity { activity ->
      actual = activity.findViewById<TextView>(viewId)?.text?.toString()
    }
    actual == expected
  }

  private fun waitForVisibility(
    scenario: ActivityScenario<AlarmHostActivity>,
    viewId: Int,
    expected: Int,
  ) = waitUntil("visibility $expected on view $viewId") {
    var actual: Int? = null
    scenario.onActivity { activity -> actual = activity.findViewById<View>(viewId)?.visibility }
    actual == expected
  }

  private fun waitUntil(description: String, predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      SystemClock.sleep(POLL_INTERVAL_MS)
    }
    throw AssertionError("Timed out waiting for $description")
  }

  private fun assertMissionProgress(expected: Int) {
    val database = MissionAlarmDatabaseFactory.persistent(context)
    try {
      assertEquals(expected, database.runtimeDao().findMission(INSTANCE_ID)?.committedProgress)
    } finally {
      database.close()
    }
  }

  private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

  private fun seedMathAlarm() {
    val database = MissionAlarmDatabaseFactory.persistent(context)
    try {
      database.runInTransaction {
        val alarm = AlarmEntity(
          ALARM_ID, 1, "Morning focus", false, "WEEKLY", 420, 127, null, "UTC", "classic",
          NOW_MS - 10_000, NOW_MS - 10_000,
        )
        database.alarmDao().insertAlarm(alarm)
        database.alarmDao().insertMission(
          AlarmMissionConfigEntity(
            ALARM_ID, "MATH", 1, 3, null, 7, "math-v1", null, null, null,
          ),
        )
        check(database.alarmDao().updateAlarm(alarm.copy(enabled = true)) == 1)
        database.runtimeDao().insertOccurrence(
          AlarmOccurrenceEntity(
            OCCURRENCE_ID, "occ:v1:$ALARM_ID:1:1", ALARM_ID, 1, NOW_MS - 1_000,
            "2026-08-31", 420, "UTC", 0, "SCHEDULED_OS", null, NOW_MS - 2_000,
            NOW_MS - 2_000,
          ),
        )
        database.runtimeDao().getOrCreateTriggeredInstance(
          AlarmInstanceEntity(
            INSTANCE_ID, OCCURRENCE_ID, ALARM_ID, 1, "TRIGGERED", 1, 1, NOW_MS - 1_000,
            NOW_MS - 1_000, 1, "boot:ui-test", null, null, null, null, "Morning focus", "classic",
            NOW_MS - 1_000, NOW_MS - 1_000,
          ),
          InstanceMissionEntity(
            INSTANCE_ID, "MATH", 1, 3, 0, "READY", "math-v1", null, "math-v1", null,
            null, null, NOW_MS - 1_000,
          ),
        )
        database.runtimeDao().insertMathQuestions(
          listOf(
            MathQuestionEntity(INSTANCE_ID, 0, "ADD", 2, 3, 5, false, null),
            MathQuestionEntity(INSTANCE_ID, 1, "SUBTRACT", 4, 6, -2, false, null),
            MathQuestionEntity(INSTANCE_ID, 2, "MULTIPLY", 3, 4, 12, false, null),
          ),
        )
      }
    } finally {
      database.close()
    }
  }

  private companion object {
    const val DATABASE_NAME = "mission-alarm.db"
    const val UI_TIMEOUT_MS = 5_000L
    const val POLL_INTERVAL_MS = 50L
    const val NOW_MS = 1_777_507_200_000L
    const val ALARM_ID = "576d0066-ca10-4d4d-90b8-b4f5d4433217"
    const val OCCURRENCE_ID = "c59123be-b12d-40cf-9658-e40c131ef82e"
    const val INSTANCE_ID = "ddca3916-f3ab-45db-aa2c-d714527c4618"
  }
}
