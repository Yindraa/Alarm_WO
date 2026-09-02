package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MixedMissionFifoTest {
  private lateinit var database: MissionAlarmDatabase
  private var effectSequence = 0
  private val clock = WallClock { NOW_MS }
  private val effectIds = EffectIdGenerator { "mixed-effect-${++effectSequence}" }

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    seedAlarm(MATH_ALARM_ID, "Math", "MATH")
    seedAlarm(SCAN_ALARM_ID, "Scan", "QR")
    seedAlarm(PUSHUP_ALARM_ID, "Push-up", "PUSH_UP")
    seedInstance(MATH_INSTANCE_ID, MATH_OCCURRENCE_ID, MATH_ALARM_ID, "MATH", 1, attended = true)
    seedInstance(SCAN_INSTANCE_ID, SCAN_OCCURRENCE_ID, SCAN_ALARM_ID, "QR", 2, attended = false)
    seedInstance(PUSHUP_INSTANCE_ID, PUSHUP_OCCURRENCE_ID, PUSHUP_ALARM_ID, "PUSH_UP", 3, attended = false)
    database.runtimeDao().insertMathQuestions(
      listOf(MathQuestionEntity(MATH_INSTANCE_ID, 0, "ADD", 2, 3, 5, false, null)),
    )
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun mixedMissionCompletionsPromoteStrictFifoAcrossCoordinatorRecreation() {
    val mathCoordinator = MathMissionCoordinator(database, clock, effectIds)
    val mathStarted = mathCoordinator.start(MATH_INSTANCE_ID)
    val mathResult = mathCoordinator.submitAnswer(MATH_INSTANCE_ID, mathStarted.revision, 0, 5)

    assertTrue(mathResult.completed)
    assertEquals(SCAN_INSTANCE_ID, mathResult.promotedInstanceId)
    assertActive(SCAN_INSTANCE_ID, "QR")

    val scanCoordinator = ScanMissionCoordinator(database, clock, effectIds)
    val scanStarted = scanCoordinator.start(SCAN_INSTANCE_ID)
    val scanResult = scanCoordinator.complete(SCAN_INSTANCE_ID, scanStarted.revision)

    assertTrue(scanResult.completed)
    assertEquals(PUSHUP_INSTANCE_ID, scanResult.promotedInstanceId)
    assertActive(PUSHUP_INSTANCE_ID, "PUSH_UP")

    val pushUpCoordinator = PushUpMissionCoordinator(database, clock, effectIds)
    val pushUpStarted = pushUpCoordinator.start(PUSHUP_INSTANCE_ID)
    val pushUpCommand = CommitPushUpRepCommand(
      CommandId.parse(PUSHUP_COMMAND_ID),
      PUSHUP_INSTANCE_ID,
      pushUpStarted.revision,
      "mixed-session",
      1,
      PUSHUP_PROFILE,
    )
    val pushUpResult = pushUpCoordinator.commitVerifiedRep(pushUpCommand)

    assertTrue(pushUpResult.completed)
    assertNull(pushUpResult.promotedInstanceId)
    assertNull(database.runtimeDao().loadActiveRuntimeSnapshot())
    assertEquals(3L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='SUCCESS'"))
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='STOP_ALARM_RUNTIME'"))
    assertEquals(2L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='START_ALARM_RUNTIME'"))
    assertEquals(2L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='PRESENT_ACTIVE_INSTANCE'"))
    assertEquals(7L, scalar("SELECT COUNT(*) FROM runtime_effect"))

    val replay = pushUpCoordinator.commitVerifiedRep(pushUpCommand)
    assertTrue(replay.replayed)
    assertEquals(3L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(7L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  private fun assertActive(instanceId: String, missionType: String) {
    val active = checkNotNull(database.runtimeDao().loadActiveRuntimeSnapshot())
    assertEquals(instanceId, active.instanceId)
    assertEquals(missionType, active.missionType)
    assertEquals(1, database.runtimeDao().findInstanceById(instanceId)!!.attentionSlot)
  }

  private fun seedAlarm(id: String, label: String, missionType: String) {
    val alarm = AlarmEntity(
      id, 1, label, false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        when (missionType) {
          "MATH" -> AlarmMissionConfigEntity(
            id, missionType, 1, 1, null, 7, "math-v1", null, null, null,
          )
          "QR" -> AlarmMissionConfigEntity(
            id, missionType, 1, 1, null, null, null, null, null, null,
          )
          else -> AlarmMissionConfigEntity(
            id, missionType, 1, 1, PUSHUP_PROFILE, null, null, null, null, null,
          )
        },
      )
      database.alarmDao().updateAlarm(alarm.copy(enabled = true))
    }
  }

  private fun seedInstance(
    instanceId: String,
    occurrenceId: String,
    alarmId: String,
    missionType: String,
    queueOrder: Long,
    attended: Boolean,
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
        missionType,
        "classic",
        1,
        1,
      ),
      InstanceMissionEntity(
        instanceId,
        missionType,
        1,
        1,
        0,
        "READY",
        when (missionType) {
          "MATH" -> "math-v1"
          "QR" -> "scan-code-engine-v1"
          else -> "pushup-engine-v1"
        },
        PUSHUP_PROFILE.takeIf { missionType == "PUSH_UP" },
        "math-v1".takeIf { missionType == "MATH" },
        null,
        null,
        null,
        1,
      ),
    )
  }

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private companion object {
    const val NOW_MS = 30_000L
    const val PUSHUP_PROFILE = "pushup-profile-v0"
    const val MATH_ALARM_ID = "07136102-bb34-452c-bc2f-d67dce237c1a"
    const val SCAN_ALARM_ID = "a2bdbbb4-102f-4c89-9714-b35081f846b5"
    const val PUSHUP_ALARM_ID = "bf0b58d0-32c2-4354-b1fc-dfa52b19ffad"
    const val MATH_OCCURRENCE_ID = "dd0b891e-6e2d-463a-a116-b7a099928ac4"
    const val SCAN_OCCURRENCE_ID = "7f4d42d4-f0ab-46c9-9447-afac1173352b"
    const val PUSHUP_OCCURRENCE_ID = "d181d513-38ff-4b80-8580-f728c26f02a5"
    const val MATH_INSTANCE_ID = "64d33272-6699-4c69-bc53-fb23e8911564"
    const val SCAN_INSTANCE_ID = "7c58ac8f-bc7a-4485-ae83-862cd5fdd267"
    const val PUSHUP_INSTANCE_ID = "f00fa3d2-70cc-4baa-b4fc-d19c67dcd1dc"
    const val PUSHUP_COMMAND_ID = "af8a45a4-1f2c-464a-af8c-ae29ee789f49"
  }
}
