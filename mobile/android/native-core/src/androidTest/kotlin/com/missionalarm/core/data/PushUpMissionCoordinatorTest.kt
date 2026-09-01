package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushUpMissionCoordinatorTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var coordinator: PushUpMissionCoordinator
  private var effectSequence = 0

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    coordinator = PushUpMissionCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "pushup-effect-${++effectSequence}" },
    )
    seedAlarm()
    seedInstance(INSTANCE_ID, OCCURRENCE_ID, attended = true, queueOrder = 1)
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun startMovesPushUpMissionToRecoverableInProgressState() {
    val snapshot = coordinator.start(INSTANCE_ID)

    assertEquals("MISSION_IN_PROGRESS", snapshot.runtimeState)
    assertEquals("IN_PROGRESS", snapshot.missionRuntimeStatus)
    assertEquals(PROFILE_VERSION, database.runtimeDao().findMission(INSTANCE_ID)!!.pushupProfileVersion)
    assertEquals(3, snapshot.revision)
    assertEquals(3, coordinator.start(INSTANCE_ID).revision)
  }

  @Test
  fun verifiedRepCommitsOnceAndExactEvidenceReplayIsReadOnly() {
    val started = coordinator.start(INSTANCE_ID)
    val command = command(COMMAND_ID_1, started.revision, repSequence = 1)

    val first = coordinator.commitVerifiedRep(command)
    val replay = coordinator.commitVerifiedRep(command)

    assertEquals(1, first.committedProgress)
    assertEquals(4, first.instanceRevision)
    assertFalse(first.completed)
    assertFalse(first.replayed)
    assertTrue(replay.replayed)
    assertEquals(first.instanceRevision, replay.instanceRevision)
    assertEquals(1, database.runtimeDao().findMission(INSTANCE_ID)!!.committedProgress)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun mismatchedProfileSequenceAndRevisionNeverMutateProgress() {
    val started = coordinator.start(INSTANCE_ID)

    assertThrows(PushUpMissionException.ProfileMismatch::class.java) {
      coordinator.commitVerifiedRep(
        command(COMMAND_ID_1, started.revision, 1, profileVersion = "other-profile"),
      )
    }
    assertThrows(PushUpMissionException.InvalidRepSequence::class.java) {
      coordinator.commitVerifiedRep(command(COMMAND_ID_2, started.revision, 2))
    }
    assertThrows(PushUpMissionException.RevisionConflict::class.java) {
      coordinator.commitVerifiedRep(command(COMMAND_ID_3, started.revision + 1, 1))
    }

    assertEquals(0, database.runtimeDao().findMission(INSTANCE_ID)!!.committedProgress)
    assertEquals(0L, scalar("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun reusingEvidenceIdWithDifferentSessionIsRejected() {
    val started = coordinator.start(INSTANCE_ID)
    coordinator.commitVerifiedRep(command(COMMAND_ID_1, started.revision, 1))

    assertThrows(PushUpMissionException.IdempotencyKeyReused::class.java) {
      coordinator.commitVerifiedRep(
        command(COMMAND_ID_1, started.revision, 1, sessionId = "replacement-session"),
      )
    }

    assertEquals(1, database.runtimeDao().findMission(INSTANCE_ID)!!.committedProgress)
  }

  @Test
  fun finalRepCompletesHistoryStopsRuntimeAndPromotesOldestQueueExactlyOnce() {
    seedInstance(QUEUED_INSTANCE_ID, QUEUED_OCCURRENCE_ID, attended = false, queueOrder = 2)
    var revision = coordinator.start(INSTANCE_ID).revision
    val ids = listOf(COMMAND_ID_1, COMMAND_ID_2, COMMAND_ID_3)
    var finalResult: PushUpRepResult? = null

    ids.forEachIndexed { index, commandId ->
      val result = coordinator.commitVerifiedRep(command(commandId, revision, index + 1))
      revision = result.instanceRevision
      finalResult = result
    }

    val completed = checkNotNull(finalResult)
    assertTrue(completed.completed)
    assertEquals(3, completed.committedProgress)
    assertEquals(QUEUED_INSTANCE_ID, completed.promotedInstanceId)
    val instance = checkNotNull(database.runtimeDao().findInstanceById(INSTANCE_ID))
    assertEquals("TERMINAL", instance.runtimeState)
    assertEquals("SUCCESS", instance.terminalResult)
    assertNull(instance.attentionSlot)
    val mission = checkNotNull(database.runtimeDao().findMission(INSTANCE_ID))
    assertEquals("COMPLETED", mission.runtimeStatus)
    assertEquals(3, mission.committedProgress)
    val history = checkNotNull(database.runtimeDao().findHistoryByInstanceId(INSTANCE_ID))
    assertEquals("SUCCESS", history.result)
    assertEquals(PROFILE_VERSION, history.profileVersion)
    assertEquals(3, history.finalProgress)
    val promoted = checkNotNull(database.runtimeDao().findInstanceById(QUEUED_INSTANCE_ID))
    assertEquals("TRIGGERED", promoted.runtimeState)
    assertEquals(1, promoted.attentionSlot)
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect"))

    val replay = coordinator.commitVerifiedRep(command(COMMAND_ID_3, 5, 3))
    assertTrue(replay.replayed)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  private fun command(
    commandId: String,
    expectedRevision: Int,
    repSequence: Int,
    sessionId: String = SESSION_ID,
    profileVersion: String = PROFILE_VERSION,
  ) = CommitPushUpRepCommand(
    CommandId.parse(commandId),
    INSTANCE_ID,
    expectedRevision,
    sessionId,
    repSequence,
    profileVersion,
  )

  private fun seedAlarm() {
    val alarm = AlarmEntity(
      ALARM_ID, 1, "Push-up alarm", false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          ALARM_ID, "PUSH_UP", 1, 3, PROFILE_VERSION, null, null, null, null, null,
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
        "Push-up alarm",
        "classic",
        1,
        1,
      ),
      InstanceMissionEntity(
        instanceId,
        "PUSH_UP",
        1,
        3,
        0,
        "READY",
        "pushup-engine-v1",
        PROFILE_VERSION,
        null,
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
    const val NOW_MS = 20_000L
    const val PROFILE_VERSION = "pushup-profile-v0"
    const val SESSION_ID = "pushup-session-a"
    const val ALARM_ID = "38240fcb-d4bb-486b-b783-f9286d06d6b7"
    const val OCCURRENCE_ID = "50515f13-dc08-40c7-9283-1971500735eb"
    const val INSTANCE_ID = "9001f2d2-e8c1-4f49-a924-759980533e01"
    const val QUEUED_OCCURRENCE_ID = "94baee12-e82d-4d5b-8f10-79d199bd37ca"
    const val QUEUED_INSTANCE_ID = "8a0cf873-7b1f-47bb-9848-00a303b7bfe4"
    const val COMMAND_ID_1 = "660f69ae-5980-411e-91dc-d38a59db5bc4"
    const val COMMAND_ID_2 = "360408dc-886f-4d84-8f5e-16b57a9890e7"
    const val COMMAND_ID_3 = "0833acae-6147-4944-9605-119d3a83bbbe"
  }
}
