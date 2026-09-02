package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageFailureAtomicityTest {
  private lateinit var database: MissionAlarmDatabase
  private var effectSequence = 0

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun enableRollsBackAlarmOccurrenceEffectsAndReceiptWhenStorageWriteFails() {
    val drafts = AlarmDraftRepository(database, WallClock { NOW_MS }) { AlarmId.parse(ALARM_ID) }
    drafts.save(
      SaveAlarmDraftCommand(
        commandId = CommandId.parse(SAVE_COMMAND_ID),
        alarmId = null,
        expectedRevision = null,
        label = "Offline alarm",
        scheduleKind = "WEEKLY",
        localTimeMinutes = 420,
        repeatDaysMask = 127,
        oneTimeAtUtcMs = null,
        configuredTimezoneId = "UTC",
        soundId = "classic",
        missionType = MissionType.MATH,
        target = 3,
        pushupProfileVersion = null,
        mathOperationsMask = 7,
        mathGeneratorVersion = "math-v1",
      ),
    )
    installAbortTrigger(
      name = "fail_schedule_effect",
      table = "runtime_effect",
      condition = "NEW.effect_type = 'SCHEDULE_OCCURRENCE'",
    )
    val repository = AlarmSchedulingRepository(
      database,
      WallClock { NOW_MS },
      CurrentZoneProvider { ZoneId.of("UTC") },
      OccurrenceIdGenerator { OccurrenceId.parse(OCCURRENCE_ID) },
      EffectIdGenerator { "storage-effect-${++effectSequence}" },
    )

    assertThrows(RuntimeException::class.java) {
      repository.enable(
        EnableAlarmCommand(
          CommandId.parse(ENABLE_COMMAND_ID),
          AlarmId.parse(ALARM_ID),
          Revision.of(1),
        ),
      )
    }

    val alarm = checkNotNull(database.alarmDao().findAlarmEntity(ALARM_ID))
    assertFalse(alarm.enabled)
    assertEquals(1, alarm.revision)
    assertEquals(0L, scalar("SELECT COUNT(*) FROM alarm_occurrence"))
    assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_effect"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM command_receipt"))
    assertEquals(
      0L,
      scalar("SELECT COUNT(*) FROM command_receipt WHERE command_id='$ENABLE_COMMAND_ID'"),
    )
  }

  @Test
  fun finalVerifiedRepRollsBackProgressTerminalHistoryAndEffectsWhenStorageWriteFails() {
    seedAlarm(ALARM_ID, "PUSH_UP", target = 1)
    seedInstance(
      instanceId = INSTANCE_ID,
      occurrenceId = OCCURRENCE_ID,
      alarmId = ALARM_ID,
      missionType = "PUSH_UP",
      target = 1,
      queueOrder = 1,
      attended = true,
    )
    val coordinator = PushUpMissionCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "storage-effect-${++effectSequence}" },
    )
    val started = coordinator.start(INSTANCE_ID)
    installAbortTrigger("fail_success_history", "alarm_history")

    assertThrows(RuntimeException::class.java) {
      coordinator.commitVerifiedRep(
        CommitPushUpRepCommand(
          CommandId.parse(REP_COMMAND_ID),
          INSTANCE_ID,
          started.revision,
          "storage-session",
          1,
          PUSHUP_PROFILE,
        ),
      )
    }

    val instance = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_ID))
    val mission = checkNotNull(database.runtimeDao().findMission(INSTANCE_ID))
    assertEquals("MISSION_IN_PROGRESS", instance.runtimeState)
    assertNull(instance.terminalResult)
    assertEquals(started.revision, instance.revision)
    assertEquals("IN_PROGRESS", mission.runtimeStatus)
    assertEquals(0, mission.committedProgress)
    assertEquals(0L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_effect"))
    assertEquals(0L, scalar("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun emergencyDismissRollsBackTerminalStateAndQueuePromotionWhenStorageWriteFails() {
    seedAlarm(ALARM_ID, "PUSH_UP", target = 3)
    seedAlarm(QUEUED_ALARM_ID, "QR", target = 1)
    seedInstance(INSTANCE_ID, OCCURRENCE_ID, ALARM_ID, "PUSH_UP", 3, 1, true, progress = 1)
    seedInstance(
      QUEUED_INSTANCE_ID,
      QUEUED_OCCURRENCE_ID,
      QUEUED_ALARM_ID,
      "QR",
      1,
      2,
      false,
    )
    installAbortTrigger("fail_emergency_history", "alarm_history")
    val coordinator = EmergencyDismissCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "storage-effect-${++effectSequence}" },
    )

    assertThrows(RuntimeException::class.java) { coordinator.dismiss(INSTANCE_ID) }

    val attended = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_ID))
    val queued = checkNotNull(database.runtimeDao().findInstanceById(QUEUED_INSTANCE_ID))
    assertEquals("MISSION_IN_PROGRESS", attended.runtimeState)
    assertEquals(1, attended.attentionSlot)
    assertNull(attended.terminalResult)
    assertEquals("PENDING_ATTENTION", queued.runtimeState)
    assertNull(queued.attentionSlot)
    assertEquals(0L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  private fun seedAlarm(id: String, missionType: String, target: Int) {
    val alarm = AlarmEntity(
      id, 1, missionType, false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          id,
          missionType,
          1,
          target,
          PUSHUP_PROFILE.takeIf { missionType == "PUSH_UP" },
          null,
          null,
          null,
          null,
          null,
        ),
      )
      database.alarmDao().updateAlarm(alarm.copy(enabled = true))
    }
  }

  private fun seedInstance(
    instanceId: String,
    occurrenceId: String,
    alarmId: String,
    missionType: String,
    target: Int,
    queueOrder: Long,
    attended: Boolean,
    progress: Int = 0,
  ) {
    database.runtimeDao().insertOccurrence(
      AlarmOccurrenceEntity(
        occurrenceId,
        "occ:v1:$alarmId:1:$queueOrder",
        alarmId,
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
        alarmId,
        1,
        if (attended) "MISSION_IN_PROGRESS" else "PENDING_ATTENTION",
        queueOrder,
        if (attended) 1 else null,
        NOW_MS - 100,
        NOW_MS - 5_000,
        1,
        "boot:storage",
        null,
        null,
        null,
        null,
        missionType,
        "classic",
        1,
        1,
      ),
      InstanceMissionEntity(
        instanceId,
        missionType,
        1,
        target,
        progress,
        if (attended) "IN_PROGRESS" else "READY",
        if (missionType == "PUSH_UP") "pushup-engine-v1" else "scan-code-engine-v1",
        PUSHUP_PROFILE.takeIf { missionType == "PUSH_UP" },
        null,
        null,
        null,
        null,
        1,
      ),
    )
  }

  private fun installAbortTrigger(name: String, table: String, condition: String? = null) {
    val whenClause = condition?.let { "WHEN $it" }.orEmpty()
    database.openHelper.writableDatabase.execSQL(
      """
      CREATE TRIGGER $name BEFORE INSERT ON $table
      $whenClause
      BEGIN
        SELECT RAISE(ABORT, 'injected storage write failure');
      END
      """.trimIndent(),
    )
  }

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private companion object {
    const val NOW_MS = 60_000L
    const val PUSHUP_PROFILE = "pushup-profile-v0"
    const val ALARM_ID = "72bd5f32-a357-4614-8480-581fb8b60627"
    const val QUEUED_ALARM_ID = "46f071b1-fc3d-40e3-9fe0-d809da3ff1a4"
    const val OCCURRENCE_ID = "36ad184f-b4fa-4364-a8bf-46a963c4b840"
    const val QUEUED_OCCURRENCE_ID = "03569139-9db9-428a-bd9e-b03884e6585b"
    const val INSTANCE_ID = "a74e40e7-87ac-41ce-af27-b729d62fb6f8"
    const val QUEUED_INSTANCE_ID = "b78fd1eb-53b1-4020-8da4-055a43965afd"
    const val SAVE_COMMAND_ID = "a9b99e45-2968-42bc-99a6-8e811490313b"
    const val ENABLE_COMMAND_ID = "90327766-ea8d-4d4f-bbed-70313832de03"
    const val REP_COMMAND_ID = "933a62b0-73df-41dd-8158-dfe0c19200b8"
  }
}
