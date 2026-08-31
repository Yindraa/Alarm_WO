package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathMissionCoordinatorTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var coordinator: MathMissionCoordinator
  private var effectSequence = 0

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    coordinator = MathMissionCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "math-effect-${++effectSequence}" },
    )
    seedAlarm()
    seedInstance(INSTANCE_ID, OCCURRENCE_ID, attended = true, queueOrder = 1)
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun startMovesTheAttendedMathMissionToRecoverableInProgressState() {
    val snapshot = coordinator.start(INSTANCE_ID)

    assertEquals("MISSION_IN_PROGRESS", snapshot.runtimeState)
    assertEquals("IN_PROGRESS", snapshot.missionRuntimeStatus)
    assertEquals(3, snapshot.revision)
    assertEquals(0, snapshot.mathQuestion!!.ordinal)
    assertEquals("MISSION_IN_PROGRESS", database.runtimeDao().findInstanceById(INSTANCE_ID)!!.runtimeState)
    assertEquals("IN_PROGRESS", database.runtimeDao().findMission(INSTANCE_ID)!!.runtimeStatus)

    val replay = coordinator.start(INSTANCE_ID)
    assertEquals(3, replay.revision)
  }

  @Test
  fun wrongAnswerDoesNotMutateQuestionProgressOrRevision() {
    val started = coordinator.start(INSTANCE_ID)
    val question = checkNotNull(database.runtimeDao().findMathQuestion(INSTANCE_ID, 0))

    val result = coordinator.submitAnswer(
      INSTANCE_ID,
      started.revision,
      question.ordinal,
      question.correctAnswer + 1,
    )

    assertFalse(result.correct)
    assertFalse(result.completed)
    assertEquals(0, result.committedProgress)
    assertEquals(started.revision, result.instanceRevision)
    assertFalse(database.runtimeDao().findMathQuestion(INSTANCE_ID, 0)!!.answered)
    assertEquals(0, database.runtimeDao().findMission(INSTANCE_ID)!!.committedProgress)
  }

  @Test
  fun correctAnswersAdvanceExactlyOnceAndFinalAnswerCompletesHistoryAndStopEffect() {
    var snapshot = coordinator.start(INSTANCE_ID)

    repeat(3) { ordinal ->
      val question = checkNotNull(database.runtimeDao().findMathQuestion(INSTANCE_ID, ordinal))
      val result = coordinator.submitAnswer(
        INSTANCE_ID,
        snapshot.revision,
        ordinal,
        question.correctAnswer,
      )
      assertTrue(result.correct)
      assertEquals(ordinal + 1, result.committedProgress)
      if (ordinal < 2) {
        assertFalse(result.completed)
        snapshot = checkNotNull(database.runtimeDao().loadActiveRuntimeSnapshot())
        assertEquals(ordinal + 1, snapshot.committedProgress)
        assertEquals(ordinal + 1, snapshot.mathQuestion!!.ordinal)
      } else {
        assertTrue(result.completed)
      }
    }

    val instance = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_ID))
    assertEquals("TERMINAL", instance.runtimeState)
    assertEquals("SUCCESS", instance.terminalResult)
    assertEquals("VERIFIED_MISSION", instance.dismissMethod)
    assertNull(instance.attentionSlot)
    val mission = checkNotNull(database.runtimeDao().findMission(INSTANCE_ID))
    assertEquals("COMPLETED", mission.runtimeStatus)
    assertEquals(3, mission.committedProgress)
    val history = checkNotNull(database.runtimeDao().findHistoryByInstanceId(INSTANCE_ID))
    assertEquals("SUCCESS", history.result)
    assertEquals(3, history.finalProgress)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='STOP_ALARM_RUNTIME'"))

    val replay = coordinator.submitAnswer(INSTANCE_ID, 5, 2, 0)
    assertTrue(replay.completed)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='STOP_ALARM_RUNTIME'"))
  }

  @Test
  fun completionPromotesTheOldestQueuedAlarm() {
    seedInstance(QUEUED_INSTANCE_ID, QUEUED_OCCURRENCE_ID, attended = false, queueOrder = 2)
    var snapshot = coordinator.start(INSTANCE_ID)
    repeat(3) { ordinal ->
      val question = checkNotNull(database.runtimeDao().findMathQuestion(INSTANCE_ID, ordinal))
      val result = coordinator.submitAnswer(
        INSTANCE_ID,
        snapshot.revision,
        ordinal,
        question.correctAnswer,
      )
      if (!result.completed) snapshot = checkNotNull(database.runtimeDao().loadActiveRuntimeSnapshot())
      else assertEquals(QUEUED_INSTANCE_ID, result.promotedInstanceId)
    }

    val promoted = checkNotNull(database.runtimeDao().findInstanceById(QUEUED_INSTANCE_ID))
    assertEquals("TRIGGERED", promoted.runtimeState)
    assertEquals(1, promoted.attentionSlot)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='START_ALARM_RUNTIME' AND aggregate_id='$QUEUED_INSTANCE_ID'"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='PRESENT_ACTIVE_INSTANCE' AND aggregate_id='$QUEUED_INSTANCE_ID'"))
  }

  private fun seedAlarm() {
    val alarm = AlarmEntity(
      ALARM_ID, 1, "Alarm", false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          ALARM_ID, "MATH", 1, 3, null, 7, "math-v1", null, null, null,
        ),
      )
      database.alarmDao().updateAlarm(alarm.copy(enabled = true))
    }
  }

  private fun seedInstance(
    instanceId: String,
    occurrenceId: String,
    attended: Boolean,
    queueOrder: Long,
  ) {
    database.runtimeDao().insertOccurrence(
      AlarmOccurrenceEntity(
        occurrenceId,
        "occ:v1:$ALARM_ID:1:$queueOrder",
        ALARM_ID,
        1,
        NOW_MS - 100,
        "2026-08-31",
        420,
        "UTC",
        0,
        "SCHEDULED_OS",
        null,
        1,
        1,
      ),
    )
    database.runtimeDao().getOrCreateTriggeredInstance(
      AlarmInstanceEntity(
        instanceId,
        occurrenceId,
        ALARM_ID,
        1,
        if (attended) "TRIGGERED" else "PENDING_ATTENTION",
        queueOrder,
        if (attended) 1 else null,
        NOW_MS - 100,
        NOW_MS - 5_000,
        1,
        "boot:1",
        null,
        null,
        null,
        null,
        "Alarm",
        "classic",
        1,
        1,
      ),
      InstanceMissionEntity(
        instanceId, "MATH", 1, 3, 0, "READY", "math-v1",
        null, "math-v1", null, null, null, 1,
      ),
    )
    database.runtimeDao().insertMathQuestions(
      listOf(
        MathQuestionEntity(instanceId, 0, "ADD", 2, 3, 5, false, null),
        MathQuestionEntity(instanceId, 1, "SUBTRACT", 4, 6, -2, false, null),
        MathQuestionEntity(instanceId, 2, "MULTIPLY", 3, 4, 12, false, null),
      ),
    )
  }

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private companion object {
    const val NOW_MS = 10_000L
    const val ALARM_ID = "df0575a5-fc4f-4f70-8bc4-d67c7ba9577b"
    const val OCCURRENCE_ID = "36ca13ee-ac0c-4fe8-99f5-a0c577eafde3"
    const val INSTANCE_ID = "04e4c33e-dd25-42e0-8d19-1ce240f9d2f2"
    const val QUEUED_OCCURRENCE_ID = "73bb1bdc-6081-4f94-81bf-59c927e41ba5"
    const val QUEUED_INSTANCE_ID = "a14967b8-8ce0-46df-ad92-7384c3350396"
  }
}
