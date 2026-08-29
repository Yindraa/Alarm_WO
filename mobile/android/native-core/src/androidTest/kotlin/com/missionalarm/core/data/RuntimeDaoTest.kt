package com.missionalarm.core.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDaoTest {
  private lateinit var database: MissionAlarmDatabase

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    database.alarmDao().insertDraft(alarm(), alarmMission())
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun duplicateTriggerReturnsExistingInstanceWithoutDuplicateMutation() {
    database.runtimeDao().insertOccurrence(occurrence())
    val first = database.runtimeDao().getOrCreateTriggeredInstance(instance(), instanceMission())
    val retried = database.runtimeDao().getOrCreateTriggeredInstance(
      instance().copy(id = SECOND_INSTANCE_ID),
      instanceMission().copy(instanceId = SECOND_INSTANCE_ID),
    )

    assertEquals(first.id, retried.id)
    assertEquals(1, scalarLong("SELECT COUNT(*) FROM alarm_instance"))
    assertEquals("FIRED", scalarString("SELECT state FROM alarm_occurrence"))
  }

  @Test
  fun cancelledOccurrenceCannotCreateInstanceAndTransactionRollsBack() {
    database.runtimeDao().insertOccurrence(occurrence().copy(state = "CANCELLED"))

    assertThrows(IllegalStateException::class.java) {
      database.runtimeDao().getOrCreateTriggeredInstance(instance(), instanceMission())
    }
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM alarm_instance"))
    assertEquals("CANCELLED", scalarString("SELECT state FROM alarm_occurrence"))
  }

  @Test
  fun progressCannotDecreaseAndSuccessRequiresCompletedMission() {
    database.runtimeDao().insertOccurrence(occurrence())
    database.runtimeDao().getOrCreateTriggeredInstance(
      instance(),
      instanceMission().copy(committedProgress = 1, runtimeStatus = "IN_PROGRESS"),
    )
    val sql = database.openHelper.writableDatabase

    assertThrows(SQLiteConstraintException::class.java) {
      sql.execSQL("UPDATE instance_mission SET committed_progress = 0 WHERE instance_id = ?", arrayOf(INSTANCE_ID))
    }
    assertThrows(SQLiteConstraintException::class.java) {
      sql.execSQL(
        """UPDATE alarm_instance
           SET revision = 2, runtime_state = 'TERMINAL', terminal_result = 'SUCCESS', terminal_at_ms = 2000
           WHERE id = ?""",
        arrayOf(INSTANCE_ID),
      )
    }

    sql.execSQL(
      "UPDATE instance_mission SET committed_progress = 3, runtime_status = 'COMPLETED' WHERE instance_id = ?",
      arrayOf(INSTANCE_ID),
    )
    sql.execSQL(
      """UPDATE alarm_instance
         SET revision = 2, runtime_state = 'TERMINAL', terminal_result = 'SUCCESS', terminal_at_ms = 2000
         WHERE id = ?""",
      arrayOf(INSTANCE_ID),
    )
    assertEquals("SUCCESS", scalarString("SELECT terminal_result FROM alarm_instance"))
  }

  @Test
  fun effectAndCommandKeysAreIdempotent() {
    val reliability = database.reliabilityDao()
    assertNotEquals(-1L, reliability.insertEffect(effect()))
    assertEquals(-1L, reliability.insertEffect(effect().copy(id = SECOND_EFFECT_ID)))
    assertEquals(EFFECT_ID, reliability.findEffect("effect:v1:alarm:$ALARM_ID")!!.id)

    assertNotEquals(-1L, reliability.insertReceipt(receipt()))
    assertEquals(
      -1L,
      reliability.insertReceipt(receipt().copy(requestHash = "b".repeat(64))),
    )
    assertEquals("a".repeat(64), reliability.findReceipt(COMMAND_ID)!!.requestHash)
  }

  @Test
  fun effectLeaseRequiresOwnerAndAcknowledgementIsTerminal() {
    val reliability = database.reliabilityDao()
    reliability.insertEffect(effect())

    val claimed = reliability.claimNext(owner = "worker-a", nowMs = 1_000, leaseDurationMs = 500)!!
    assertEquals("LEASED", claimed.status)
    assertEquals(1, claimed.attemptCount)
    assertEquals(0, reliability.acknowledge(EFFECT_ID, "worker-b", 1_100))
    assertEquals(1, reliability.acknowledge(EFFECT_ID, "worker-a", 1_100))
    assertEquals("ACKNOWLEDGED", reliability.findEffectById(EFFECT_ID)!!.status)
    assertEquals(null, reliability.claimNext("worker-a", 2_000, 500))
  }

  @Test
  fun retryWaitsUntilDueAndExpiredLeaseCanBeReclaimed() {
    val reliability = database.reliabilityDao()
    reliability.insertEffect(effect())
    reliability.claimNext("worker-a", 1_000, 500)
    assertEquals(1, reliability.retry(EFFECT_ID, "worker-a", 2_000, "OS_BUSY", 1_100))
    assertEquals(null, reliability.claimNext("worker-b", 1_999, 500))

    val secondAttempt = reliability.claimNext("worker-b", 2_000, 500)!!
    assertEquals(2, secondAttempt.attemptCount)
    assertEquals("worker-b", secondAttempt.leaseOwner)

    val reclaimed = reliability.claimNext("worker-c", 2_500, 500)!!
    assertEquals(3, reclaimed.attemptCount)
    assertEquals("worker-c", reclaimed.leaseOwner)
  }

  @Test
  fun criticalDueQueriesUseDeclaredIndexes() {
    val occurrencePlan = queryPlan(
      """EXPLAIN QUERY PLAN SELECT id FROM alarm_occurrence
         WHERE state IN ('PENDING_OS', 'SCHEDULED_OS') AND scheduled_at_utc_ms <= 2000
         ORDER BY scheduled_at_utc_ms, id""",
    )
    val effectPlan = queryPlan(
      """EXPLAIN QUERY PLAN SELECT id FROM runtime_effect
         WHERE status = 'PENDING' AND next_attempt_at_ms <= 2000
         ORDER BY created_at_ms""",
    )

    assertTrue(occurrencePlan.contains("idx_occurrence_due"))
    assertTrue(effectPlan.contains("idx_effect_claim"))
  }

  private fun scalarLong(query: String): Long =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      cursor.moveToFirst()
      cursor.getLong(0)
    }

  private fun scalarString(query: String): String =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      cursor.moveToFirst()
      cursor.getString(0)
    }

  private fun queryPlan(query: String): String =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      buildString {
        while (cursor.moveToNext()) append(cursor.getString(3)).append('\n')
      }
    }

  private fun alarm() = AlarmEntity(
    id = ALARM_ID,
    revision = 1,
    label = "Alarm",
    enabled = false,
    scheduleKind = "WEEKLY",
    localTimeMinutes = 420,
    repeatDaysMask = 1,
    oneTimeAtUtcMs = null,
    configuredTimezoneId = "Asia/Makassar",
    soundId = "classic",
    createdAtMs = 1000,
    updatedAtMs = 1000,
  )

  private fun alarmMission() = AlarmMissionConfigEntity(
    alarmId = ALARM_ID,
    missionType = "MATH",
    configVersion = 1,
    target = 3,
    pushupProfileVersion = null,
    mathOperationsMask = 7,
    mathGeneratorVersion = "math-v1",
    qrReferenceDigest = null,
    qrDigestVersion = null,
    qrKeyAlias = null,
  )

  private fun occurrence() = AlarmOccurrenceEntity(
    id = OCCURRENCE_ID,
    dedupeKey = "occ:v1:$ALARM_ID:1:2000",
    alarmId = ALARM_ID,
    alarmRevision = 1,
    scheduledAtUtcMs = 2000,
    scheduledLocalDate = "2026-08-29",
    scheduledLocalTimeMinutes = 420,
    timezoneId = "Asia/Makassar",
    utcOffsetSeconds = 28800,
    state = "PENDING_OS",
    lastErrorCode = null,
    createdAtMs = 1000,
    updatedAtMs = 1000,
  )

  private fun instance() = AlarmInstanceEntity(
    id = INSTANCE_ID,
    occurrenceId = OCCURRENCE_ID,
    alarmId = ALARM_ID,
    revision = 1,
    runtimeState = "MISSION_LOCKED",
    queueOrder = 1,
    attentionSlot = 1,
    scheduledAtUtcMs = 2000,
    actualTriggerAtMs = 2001,
    triggerElapsedRealtimeMs = 500,
    bootSessionToken = "boot-1",
    terminalAtMs = null,
    terminalResult = null,
    dismissMethod = null,
    errorReasonCode = null,
    labelSnapshot = "Alarm",
    soundIdSnapshot = "classic",
    createdAtMs = 2001,
    updatedAtMs = 2001,
  )

  private fun instanceMission() = InstanceMissionEntity(
    instanceId = INSTANCE_ID,
    missionType = "MATH",
    snapshotVersion = 1,
    target = 3,
    committedProgress = 0,
    runtimeStatus = "READY",
    engineVersion = "math-engine-v1",
    pushupProfileVersion = null,
    mathGeneratorVersion = "math-v1",
    qrReferenceDigest = null,
    qrDigestVersion = null,
    qrKeyAlias = null,
    updatedAtMs = 2001,
  )

  private fun effect() = RuntimeEffectEntity(
    id = EFFECT_ID,
    effectKey = "effect:v1:alarm:$ALARM_ID",
    aggregateType = "ALARM",
    aggregateId = ALARM_ID,
    effectType = "SCHEDULE_OCCURRENCE",
    payloadVersion = 1,
    payloadJson = "{}",
    status = "PENDING",
    attemptCount = 0,
    nextAttemptAtMs = 1000,
    leaseOwner = null,
    leaseUntilMs = null,
    lastErrorCode = null,
    createdAtMs = 1000,
    updatedAtMs = 1000,
    acknowledgedAtMs = null,
  )

  private fun receipt() = CommandReceiptEntity(
    commandId = COMMAND_ID,
    commandType = "saveAlarm",
    requestHash = "a".repeat(64),
    aggregateType = "ALARM",
    aggregateId = ALARM_ID,
    resultRevision = 1,
    status = "APPLIED",
    outcomeCode = null,
    createdAtMs = 1000,
    expiresAtMs = 2000,
  )

  private companion object {
    const val ALARM_ID = "5a7464b0-77b6-4f75-8459-974dc6d44160"
    const val OCCURRENCE_ID = "95387394-b5f1-408d-9066-4dc49b7eb0d0"
    const val INSTANCE_ID = "a74912a6-d474-4482-a6e6-2fbb8bb0b5cc"
    const val SECOND_INSTANCE_ID = "80a11c97-95ca-4615-8caf-f21d25ae5e6e"
    const val EFFECT_ID = "3519f479-c0e0-4e5a-9a8e-0a5c2bf04861"
    const val SECOND_EFFECT_ID = "3e812a12-8a49-48e0-9f78-d7cfade21698"
    const val COMMAND_ID = "126baf63-80fb-4449-89ac-37667b33ff44"
  }
}
