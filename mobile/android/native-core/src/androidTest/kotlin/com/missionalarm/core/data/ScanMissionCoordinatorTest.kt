package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanMissionCoordinatorTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var coordinator: ScanMissionCoordinator
  private var effectSequence = 0

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    coordinator = ScanMissionCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "scan-effect-${++effectSequence}" },
    )
    seedAlarm()
    seedInstance(INSTANCE_ID, OCCURRENCE_ID, attended = true, queueOrder = 1)
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun startMovesScanMissionToRecoverableInProgressState() {
    val snapshot = coordinator.start(INSTANCE_ID)

    assertEquals("MISSION_IN_PROGRESS", snapshot.runtimeState)
    assertEquals("IN_PROGRESS", snapshot.missionRuntimeStatus)
    assertEquals(3, snapshot.revision)
    assertEquals(3, coordinator.start(INSTANCE_ID).revision)
  }

  @Test
  fun formatOnlyEvidenceCompletesExactlyOnceAndCreatesStopEffect() {
    val started = coordinator.start(INSTANCE_ID)

    val result = coordinator.complete(INSTANCE_ID, started.revision)

    assertTrue(result.completed)
    val instance = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_ID))
    assertEquals("TERMINAL", instance.runtimeState)
    assertEquals("SUCCESS", instance.terminalResult)
    assertEquals("VERIFIED_MISSION", instance.dismissMethod)
    assertNull(instance.attentionSlot)
    val mission = checkNotNull(database.runtimeDao().findMission(INSTANCE_ID))
    assertEquals("COMPLETED", mission.runtimeStatus)
    assertEquals(1, mission.committedProgress)
    assertEquals("SUCCESS", database.runtimeDao().findHistoryByInstanceId(INSTANCE_ID)!!.result)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='STOP_ALARM_RUNTIME'"))

    val replay = coordinator.complete(INSTANCE_ID, result.instanceRevision)
    assertTrue(replay.completed)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun completionPromotesOldestQueuedAlarm() {
    seedInstance(QUEUED_INSTANCE_ID, QUEUED_OCCURRENCE_ID, attended = false, queueOrder = 2)
    val started = coordinator.start(INSTANCE_ID)

    val result = coordinator.complete(INSTANCE_ID, started.revision)

    assertEquals(QUEUED_INSTANCE_ID, result.promotedInstanceId)
    val promoted = checkNotNull(database.runtimeDao().findInstanceById(QUEUED_INSTANCE_ID))
    assertEquals("TRIGGERED", promoted.runtimeState)
    assertEquals(1, promoted.attentionSlot)
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  private fun seedAlarm() {
    val alarm = AlarmEntity(
      ALARM_ID, 1, "Scan alarm", false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          ALARM_ID, "QR", 1, 1, null, null, null, null, null, null,
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
        "2026-09-01",
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
        "Scan alarm",
        "classic",
        1,
        1,
      ),
      InstanceMissionEntity(
        instanceId, "QR", 1, 1, 0, "READY", "scan-code-engine-v1",
        null, null, null, null, null, 1,
      ),
    )
  }

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private companion object {
    const val NOW_MS = 20_000L
    const val ALARM_ID = "9cc36659-f876-43e1-b5b2-d2318098b04e"
    const val OCCURRENCE_ID = "4e247df0-4a1b-4ce0-a81f-85867b11dbb1"
    const val INSTANCE_ID = "95e74afe-e03b-4c33-a1ba-79449a922c85"
    const val QUEUED_OCCURRENCE_ID = "13c28221-32d6-48d9-b33a-012b95b095bc"
    const val QUEUED_INSTANCE_ID = "295557ac-c7c0-48b7-b83f-e0216554b937"
  }
}
