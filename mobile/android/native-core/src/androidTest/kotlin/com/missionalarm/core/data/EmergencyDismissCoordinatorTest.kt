package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmergencyDismissCoordinatorTest {
  private lateinit var database: MissionAlarmDatabase
  private var effectSequence = 0
  private lateinit var coordinator: EmergencyDismissCoordinator

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    coordinator = EmergencyDismissCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "emergency-effect-${++effectSequence}" },
    )
    seedAlarm()
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun dismissalPersistsTerminalHistoryAndStopEffectWithoutFalseSuccess() {
    seedInstance(INSTANCE_1, OCCURRENCE_1, queueOrder = 1, attended = true, progress = 1)

    val result = coordinator.dismiss(INSTANCE_1)

    assertEquals(EmergencyDismissResult(INSTANCE_1, 2, null, false), result)
    val instance = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_1))
    assertEquals("TERMINAL", instance.runtimeState)
    assertEquals("EMERGENCY_DISMISSED", instance.terminalResult)
    assertEquals("EMERGENCY_HOLD", instance.dismissMethod)
    assertNull(instance.attentionSlot)
    val history = checkNotNull(database.runtimeDao().findHistoryByInstanceId(INSTANCE_1))
    assertEquals("EMERGENCY_DISMISSED", history.result)
    assertEquals("EMERGENCY_HOLD", history.dismissMethod)
    assertEquals(1, history.finalProgress)
    assertEquals(5_000L, history.completionDurationMs)
    assertEquals(1L, count("effect_type='STOP_ALARM_RUNTIME'"))
  }

  @Test
  fun dismissalPromotesExactlyOldestQueuedInstanceAndCreatesRuntimeEffects() {
    seedInstance(INSTANCE_1, OCCURRENCE_1, queueOrder = 1, attended = true)
    seedInstance(INSTANCE_2, OCCURRENCE_2, queueOrder = 2, attended = false)
    seedInstance(INSTANCE_3, OCCURRENCE_3, queueOrder = 3, attended = false)

    val result = coordinator.dismiss(INSTANCE_1)

    assertEquals(INSTANCE_2, result.promotedInstanceId)
    val promoted = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_2))
    assertEquals("TRIGGERED", promoted.runtimeState)
    assertEquals(1, promoted.attentionSlot)
    assertEquals("PENDING_ATTENTION", database.runtimeDao().findInstanceById(INSTANCE_3)!!.runtimeState)
    assertNull(database.runtimeDao().findInstanceById(INSTANCE_3)!!.attentionSlot)
    assertEquals(1, database.runtimeDao().countAttendedInstances())
    assertEquals(1L, count("effect_type='START_ALARM_RUNTIME' AND aggregate_id='$INSTANCE_2'"))
    assertEquals(1L, count("effect_type='PRESENT_ACTIVE_INSTANCE' AND aggregate_id='$INSTANCE_2'"))
  }

  @Test
  fun repeatedDismissalIsIdempotentAndDoesNotDuplicateEffectsOrHistory() {
    seedInstance(INSTANCE_1, OCCURRENCE_1, queueOrder = 1, attended = true)

    val first = coordinator.dismiss(INSTANCE_1)
    val effects = count("1=1")
    val replay = coordinator.dismiss(INSTANCE_1)

    assertTrue(!first.replayed)
    assertTrue(replay.replayed)
    assertEquals(effects, count("1=1"))
    assertEquals(1L, countHistory())
  }

  @Test
  fun queuedInstanceCannotBeEmergencyDismissed() {
    seedInstance(INSTANCE_1, OCCURRENCE_1, queueOrder = 1, attended = true)
    seedInstance(INSTANCE_2, OCCURRENCE_2, queueOrder = 2, attended = false)

    assertThrows(EmergencyDismissException.NotAttended::class.java) {
      coordinator.dismiss(INSTANCE_2)
    }

    assertEquals("PENDING_ATTENTION", database.runtimeDao().findInstanceById(INSTANCE_2)!!.runtimeState)
    assertNull(database.runtimeDao().findHistoryByInstanceId(INSTANCE_2))
  }

  @Test
  fun runtimeStopEffectAcknowledgesOnlyAfterInstanceScopedStop() {
    seedInstance(INSTANCE_1, OCCURRENCE_1, queueOrder = 1, attended = true)
    coordinator.dismiss(INSTANCE_1)
    val stopped = mutableListOf<String>()
    val runner = RuntimeStopEffectRunner(
      database,
      WallClock { NOW_MS + 1 },
      LeaseOwnerGenerator { "stop-owner" },
      AlarmRuntimeStopper { stopped += it },
    )

    assertEquals(1, runner.drain())
    assertEquals(listOf(INSTANCE_1), stopped)
    assertEquals(
      "ACKNOWLEDGED",
      database.openHelper.writableDatabase.query(
        "SELECT status FROM runtime_effect WHERE effect_type='STOP_ALARM_RUNTIME'",
      ).use { cursor -> cursor.moveToFirst(); cursor.getString(0) },
    )
  }

  private fun seedAlarm() {
    val alarm = AlarmEntity(
      ALARM_ID, 1, "Alarm", false, "WEEKLY", 420, 1, null, "UTC", "classic", 1, 1,
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
    queueOrder: Long,
    attended: Boolean,
    progress: Int = 0,
  ) {
    database.runtimeDao().insertOccurrence(
      AlarmOccurrenceEntity(
        occurrenceId,
        "occ:v1:$ALARM_ID:1:$queueOrder",
        ALARM_ID,
        1,
        queueOrder,
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
        queueOrder,
        NOW_MS - 5_000,
        100,
        "boot:1",
        null,
        null,
        null,
        null,
        "Alarm $queueOrder",
        "classic",
        1,
        1,
      ),
      InstanceMissionEntity(
        instanceId, "MATH", 1, 3, progress, "READY", "math-v1",
        null, "math-v1", null, null, null, 1,
      ),
    )
  }

  private fun count(where: String): Long = database.openHelper.writableDatabase
    .query("SELECT COUNT(*) FROM runtime_effect WHERE $where")
    .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

  private fun countHistory(): Long = database.openHelper.writableDatabase
    .query("SELECT COUNT(*) FROM alarm_history")
    .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

  private companion object {
    const val NOW_MS = 10_000L
    const val ALARM_ID = "df0575a5-fc4f-4f70-8bc4-d67c7ba9577b"
    const val OCCURRENCE_1 = "36ca13ee-ac0c-4fe8-99f5-a0c577eafde3"
    const val OCCURRENCE_2 = "73bb1bdc-6081-4f94-81bf-59c927e41ba5"
    const val OCCURRENCE_3 = "09fcc84d-0d20-4da7-839d-791e0f39227f"
    const val INSTANCE_1 = "04e4c33e-dd25-42e0-8d19-1ce240f9d2f2"
    const val INSTANCE_2 = "a14967b8-8ce0-46df-ad92-7384c3350396"
    const val INSTANCE_3 = "c707d3a9-8907-4426-96cf-b90d818689f5"
  }
}
