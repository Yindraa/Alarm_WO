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
class MixedMissionOutcomeTest {
  private lateinit var database: MissionAlarmDatabase
  private var effectSequence = 0

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    seedAlarm(PUSHUP_ALARM_ID, "Push-up", "PUSH_UP")
    seedAlarm(SCAN_ALARM_ID, "Scan", "QR")
    seedInstance(PUSHUP_INSTANCE_ID, PUSHUP_OCCURRENCE_ID, PUSHUP_ALARM_ID, "PUSH_UP", 1, true, 1)
    seedInstance(SCAN_INSTANCE_ID, SCAN_OCCURRENCE_ID, SCAN_ALARM_ID, "QR", 2, false, 0)
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun emergencyPushUpPromotesScanWithoutFalseSuccessAndQueueCanFinish() {
    val emergency = emergencyCoordinator()
    val dismissed = emergency.dismiss(PUSHUP_INSTANCE_ID)

    assertEquals(SCAN_INSTANCE_ID, dismissed.promotedInstanceId)
    val pushUpHistory = checkNotNull(database.runtimeDao().findHistoryByInstanceId(PUSHUP_INSTANCE_ID))
    assertEquals("EMERGENCY_DISMISSED", pushUpHistory.result)
    assertEquals("EMERGENCY_HOLD", pushUpHistory.dismissMethod)
    assertEquals(1, pushUpHistory.finalProgress)
    assertEquals(SCAN_INSTANCE_ID, database.runtimeDao().loadActiveRuntimeSnapshot()!!.instanceId)

    val scan = ScanMissionCoordinator(database, WallClock { NOW_MS + 1 }, effectIds())
    val started = scan.start(SCAN_INSTANCE_ID)
    val completed = scan.complete(SCAN_INSTANCE_ID, started.revision)

    assertTrue(completed.completed)
    assertNull(completed.promotedInstanceId)
    assertNull(database.runtimeDao().loadActiveRuntimeSnapshot())
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='EMERGENCY_DISMISSED'"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='SUCCESS'"))
    assertEquals(4L, scalar("SELECT COUNT(*) FROM runtime_effect"))

    val replay = emergency.dismiss(PUSHUP_INSTANCE_ID)
    assertTrue(replay.replayed)
    assertEquals(2L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(4L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun transientEmergencyStopFailureRetriesWithoutChangingCanonicalOutcomes() {
    emergencyCoordinator().dismiss(PUSHUP_INSTANCE_ID)
    val clock = MutableClock(NOW_MS + 1)
    val stopper = RecordingStopper(fail = true)
    val runner = RuntimeStopEffectRunner(
      database,
      clock,
      LeaseOwnerGenerator { "stop-recovery-owner" },
      stopper,
    )

    assertEquals(1, runner.drain())
    val retryable = effect("STOP_ALARM_RUNTIME")
    assertEquals("RETRYABLE", retryable.status)
    assertEquals("ALARM_RUNTIME_STOP_TRANSIENT_FAILURE", retryable.lastErrorCode)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='EMERGENCY_DISMISSED'"))
    assertEquals(0L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='SUCCESS'"))
    assertEquals(SCAN_INSTANCE_ID, database.runtimeDao().loadActiveRuntimeSnapshot()!!.instanceId)

    stopper.fail = false
    clock.now = checkNotNull(retryable.nextAttemptAtMs)
    assertEquals(1, runner.drain())
    assertEquals("ACKNOWLEDGED", effect("STOP_ALARM_RUNTIME").status)
    assertEquals(listOf(PUSHUP_INSTANCE_ID, PUSHUP_INSTANCE_ID), stopper.calls)
  }

  @Test
  fun permanentPresentationFailureDeadLettersButKeepsPromotedMissionRecoverable() {
    emergencyCoordinator().dismiss(PUSHUP_INSTANCE_ID)
    val presentations = mutableListOf<String>()
    val runner = PresentationEffectRunner(
      database,
      WallClock { NOW_MS + 1 },
      LeaseOwnerGenerator { "presentation-failure-owner" },
    ) {
      presentations += it
      throw PermanentAlarmPresentationException()
    }

    assertEquals(1, runner.drain())
    val effect = effect("PRESENT_ACTIVE_INSTANCE")
    assertEquals("DEAD_LETTER", effect.status)
    assertEquals("ALARM_PRESENTATION_PERMANENT_FAILURE", effect.lastErrorCode)
    assertEquals(listOf(SCAN_INSTANCE_ID), presentations)
    val active = checkNotNull(database.runtimeDao().loadActiveRuntimeSnapshot())
    assertEquals(SCAN_INSTANCE_ID, active.instanceId)
    assertEquals("TRIGGERED", active.runtimeState)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='EMERGENCY_DISMISSED'"))
    assertEquals(0L, scalar("SELECT COUNT(*) FROM alarm_history WHERE result='SUCCESS'"))
  }

  private fun emergencyCoordinator() = EmergencyDismissCoordinator(
    database,
    WallClock { NOW_MS },
    effectIds(),
  )

  private fun effectIds() = EffectIdGenerator { "outcome-effect-${++effectSequence}" }

  private fun effect(type: String): RuntimeEffectEntity = database.openHelper.writableDatabase.query(
    "SELECT id FROM runtime_effect WHERE effect_type='$type'",
  ).use {
    check(it.moveToFirst())
    checkNotNull(database.reliabilityDao().findEffectById(it.getString(0)))
  }

  private fun seedAlarm(id: String, label: String, missionType: String) {
    val alarm = AlarmEntity(
      id, 1, label, false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        if (missionType == "PUSH_UP") {
          AlarmMissionConfigEntity(
            id, missionType, 1, 3, PUSHUP_PROFILE, null, null, null, null, null,
          )
        } else {
          AlarmMissionConfigEntity(id, missionType, 1, 1, null, null, null, null, null, null)
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
    progress: Int,
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
        if (missionType == "PUSH_UP") 3 else 1,
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

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private class MutableClock(var now: Long) : WallClock {
    override fun nowEpochMillis() = now
  }

  private class RecordingStopper(var fail: Boolean) : AlarmRuntimeStopper {
    val calls = mutableListOf<String>()

    override fun stop(instanceId: String) {
      calls += instanceId
      if (fail) throw IllegalStateException("temporary")
    }
  }

  private companion object {
    const val NOW_MS = 50_000L
    const val PUSHUP_PROFILE = "pushup-profile-v0"
    const val PUSHUP_ALARM_ID = "53b6064d-a61d-47ea-bcca-974ff629575a"
    const val SCAN_ALARM_ID = "8c4c89aa-fe44-4098-8985-78138bc56022"
    const val PUSHUP_OCCURRENCE_ID = "d7ff7aae-0f31-4f58-92b4-f37b6da6ea8d"
    const val SCAN_OCCURRENCE_ID = "021a6ab4-41c9-44de-8b55-3fbd13452237"
    const val PUSHUP_INSTANCE_ID = "1541a75f-a6ea-4258-bda2-e32233ae2742"
    const val SCAN_INSTANCE_ID = "aee890d9-66c8-49bd-8b04-1f329a1b9cd6"
  }
}
