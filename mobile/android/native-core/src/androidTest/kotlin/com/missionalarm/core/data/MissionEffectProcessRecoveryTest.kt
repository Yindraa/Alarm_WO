package com.missionalarm.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MissionEffectProcessRecoveryTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var database: MissionAlarmDatabase
  private var effectSequence = 0

  @Before
  fun setUp() {
    context.deleteDatabase(DATABASE_NAME)
    database = openDatabase()
    seedMathThenScan()
  }

  @After
  fun tearDown() {
    if (::database.isInitialized && database.isOpen) database.close()
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun committedCompletionEffectsSurviveDatabaseReopenAndDrainExactlyOnce() {
    completeMathAndPromoteScan()
    database.close()
    database = openDatabase()

    val stops = mutableListOf<String>()
    val starts = mutableListOf<String>()
    val presentations = mutableListOf<String>()
    val clock = WallClock { NOW_MS + 1 }
    val owner = LeaseOwnerGenerator { "recovered-owner" }
    val stopRunner = RuntimeStopEffectRunner(database, clock, owner) { stops += it }
    val startRunner = RuntimeEffectRunner(database, clock, owner) { starts += it }
    val presentationRunner = PresentationEffectRunner(database, clock, owner) { presentations += it }

    assertEquals(1, stopRunner.drain())
    assertEquals(1, startRunner.drain())
    assertEquals(1, presentationRunner.drain())
    assertEquals(listOf(MATH_INSTANCE_ID), stops)
    assertEquals(listOf(SCAN_INSTANCE_ID), starts)
    assertEquals(listOf(SCAN_INSTANCE_ID), presentations)
    assertEquals(0, stopRunner.drain())
    assertEquals(0, startRunner.drain())
    assertEquals(0, presentationRunner.drain())
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE status='ACKNOWLEDGED'"))
    assertEquals(SCAN_INSTANCE_ID, database.runtimeDao().loadActiveRuntimeSnapshot()!!.instanceId)
  }

  @Test
  fun effectClaimedBeforeProcessLossIsRecoveredOnlyAfterLeaseExpiry() {
    completeMathAndPromoteScan()
    val leased = database.reliabilityDao().claimNext(
      "START_ALARM_RUNTIME",
      "dead-process-owner",
      NOW_MS,
      LEASE_DURATION_MS,
    )
    assertNotNull(leased)
    database.close()
    database = openDatabase()

    val starts = mutableListOf<String>()
    val clock = MutableClock(NOW_MS + LEASE_DURATION_MS - 1)
    val runner = RuntimeEffectRunner(
      database,
      clock,
      LeaseOwnerGenerator { "replacement-owner" },
    ) { starts += it }

    assertEquals(0, runner.drain())
    assertEquals(emptyList<String>(), starts)
    clock.now = NOW_MS + LEASE_DURATION_MS
    assertEquals(1, runner.drain())
    assertEquals(listOf(SCAN_INSTANCE_ID), starts)
    val recovered = checkNotNull(database.reliabilityDao().findEffectById(leased!!.id))
    assertEquals("ACKNOWLEDGED", recovered.status)
    assertEquals(2, recovered.attemptCount)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history"))
  }

  private fun completeMathAndPromoteScan() {
    val coordinator = MathMissionCoordinator(
      database,
      WallClock { NOW_MS },
      EffectIdGenerator { "recovery-effect-${++effectSequence}" },
    )
    val started = coordinator.start(MATH_INSTANCE_ID)
    val result = coordinator.submitAnswer(MATH_INSTANCE_ID, started.revision, 0, 5)
    assertEquals(SCAN_INSTANCE_ID, result.promotedInstanceId)
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE status='PENDING'"))
  }

  private fun seedMathThenScan() {
    seedAlarm(MATH_ALARM_ID, "Math", "MATH")
    seedAlarm(SCAN_ALARM_ID, "Scan", "QR")
    seedInstance(MATH_INSTANCE_ID, MATH_OCCURRENCE_ID, MATH_ALARM_ID, "MATH", 1, true)
    seedInstance(SCAN_INSTANCE_ID, SCAN_OCCURRENCE_ID, SCAN_ALARM_ID, "QR", 2, false)
    database.runtimeDao().insertMathQuestions(
      listOf(MathQuestionEntity(MATH_INSTANCE_ID, 0, "ADD", 2, 3, 5, false, null)),
    )
  }

  private fun seedAlarm(id: String, label: String, missionType: String) {
    val alarm = AlarmEntity(
      id, 1, label, false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        if (missionType == "MATH") {
          AlarmMissionConfigEntity(id, missionType, 1, 1, null, 7, "math-v1", null, null, null)
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
        if (missionType == "MATH") "math-v1" else "scan-code-engine-v1",
        null,
        "math-v1".takeIf { missionType == "MATH" },
        null,
        null,
        null,
        1,
      ),
    )
  }

  private fun openDatabase() = MissionAlarmDatabaseFactory.persistent(context, DATABASE_NAME)

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private class MutableClock(var now: Long) : WallClock {
    override fun nowEpochMillis() = now
  }

  private companion object {
    const val DATABASE_NAME = "mission-effect-process-recovery.db"
    const val NOW_MS = 40_000L
    const val LEASE_DURATION_MS = 30_000L
    const val MATH_ALARM_ID = "36fa1d6e-862c-49d2-8127-c1d980354981"
    const val SCAN_ALARM_ID = "dfcd9429-50da-4dc0-b8d2-8c10762db83f"
    const val MATH_OCCURRENCE_ID = "dfd4070b-b36f-4835-a1de-63530480f38b"
    const val SCAN_OCCURRENCE_ID = "d8b10db8-9a19-4ff8-aec1-9fbbdf59619e"
    const val MATH_INSTANCE_ID = "867ec310-d09d-4db9-8207-d04c03891f67"
    const val SCAN_INSTANCE_ID = "808fc4de-4590-442a-940f-cce838845401"
  }
}
