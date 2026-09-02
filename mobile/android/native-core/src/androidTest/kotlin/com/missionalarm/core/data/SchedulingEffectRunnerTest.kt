package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchedulingEffectRunnerTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var clock: MutableWallClock
  private lateinit var scheduler: FakeExactAlarmScheduler
  private lateinit var runner: SchedulingEffectRunner

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    clock = MutableWallClock(NOW_MS)
    scheduler = FakeExactAlarmScheduler()
    runner = SchedulingEffectRunner(
      database = database,
      wallClock = clock,
      leaseOwnerGenerator = LeaseOwnerGenerator { OWNER },
      scheduler = scheduler,
    )
    seedEnabledAlarm()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun successSchedulesWithStableIdentityAndCommitsHealthWithAck() {
    assertEquals(1, runner.drain())
    assertEquals(0, runner.drain())

    assertEquals(listOf(OCCURRENCE_ID to EXPECTED_NEXT_MS), scheduler.scheduleCalls)
    assertEquals("SCHEDULED_OS", occurrence().state)
    assertNull(occurrence().lastErrorCode)
    assertEquals("ACKNOWLEDGED", scheduleEffect().status)
    assertEquals(1, scheduleEffect().attemptCount)
    assertEquals("PENDING", mirrorEffect().status)
  }

  @Test
  fun transientFailureIsDelayedThenRetriedIdempotently() {
    scheduler.mode = FakeExactAlarmScheduler.Mode.TRANSIENT

    assertEquals(1, runner.drain())

    assertEquals("RETRYABLE", scheduleEffect().status)
    assertEquals(NOW_MS + 1_000, scheduleEffect().nextAttemptAtMs)
    assertEquals("EXACT_ALARM_TRANSIENT_FAILURE", occurrence().lastErrorCode)
    assertEquals(0, runner.drain())

    scheduler.mode = FakeExactAlarmScheduler.Mode.SUCCESS
    clock.nowMs = NOW_MS + 1_000
    assertEquals(1, runner.drain())

    assertEquals(2, scheduler.scheduleCalls.size)
    assertEquals(scheduler.scheduleCalls[0], scheduler.scheduleCalls[1])
    assertEquals("SCHEDULED_OS", occurrence().state)
    assertEquals("ACKNOWLEDGED", scheduleEffect().status)
    assertEquals(2, scheduleEffect().attemptCount)
  }

  @Test
  fun missingCapabilityBlocksWithoutRetryLoopOrHealthyState() {
    scheduler.mode = FakeExactAlarmScheduler.Mode.CAPABILITY

    assertEquals(1, runner.drain())
    assertEquals(0, runner.drain())

    assertEquals("PENDING_OS", occurrence().state)
    assertEquals("EXACT_ALARM_CAPABILITY_REQUIRED", occurrence().lastErrorCode)
    assertEquals("BLOCKED_CAPABILITY", scheduleEffect().status)
    assertNull(scheduleEffect().nextAttemptAtMs)
  }

  @Test
  fun capabilityRevocationDowngradesHealthAndGrantReschedulesSameOccurrence() {
    assertEquals(1, runner.drain())
    assertEquals("SCHEDULED_OS", occurrence().state)

    clock.nowMs += 100
    assertEquals(1, capabilityReconciler().observe(granted = false, observationId = "loss-1"))
    assertEquals(0, capabilityReconciler().observe(granted = false, observationId = "loss-1"))
    assertEquals("PENDING_OS", occurrence().state)
    assertEquals("EXACT_ALARM_CAPABILITY_REQUIRED", occurrence().lastErrorCode)
    assertEquals("ACKNOWLEDGED", scheduleEffect().status)
    assertEquals("BLOCKED_CAPABILITY", capabilityLossEffect().status)

    clock.nowMs += 100
    assertEquals(1, capabilityReconciler().observe(granted = true, observationId = "grant-1"))
    assertEquals(0, capabilityReconciler().observe(granted = true, observationId = "grant-1"))
    assertEquals("RETRYABLE", capabilityLossEffect().status)
    assertEquals(1, runner.drain())

    assertEquals(2, scheduler.scheduleCalls.size)
    assertEquals(scheduler.scheduleCalls[0], scheduler.scheduleCalls[1])
    assertEquals("SCHEDULED_OS", occurrence().state)
    assertNull(occurrence().lastErrorCode)
    assertEquals("ACKNOWLEDGED", capabilityLossEffect().status)
  }

  @Test
  fun blockedInitialScheduleCanResumeAfterCapabilityGrant() {
    scheduler.mode = FakeExactAlarmScheduler.Mode.CAPABILITY
    assertEquals(1, runner.drain())
    assertEquals("BLOCKED_CAPABILITY", scheduleEffect().status)

    scheduler.mode = FakeExactAlarmScheduler.Mode.SUCCESS
    clock.nowMs += 100
    assertEquals(1, capabilityReconciler().observe(granted = true, observationId = "grant-2"))
    assertEquals(1, runner.drain())

    assertEquals("SCHEDULED_OS", occurrence().state)
    assertNull(occurrence().lastErrorCode)
    assertEquals("ACKNOWLEDGED", scheduleEffect().status)
  }

  @Test
  fun malformedPayloadIsDeadLetteredAndCannotSchedule() {
    database.openHelper.writableDatabase.execSQL(
      "UPDATE runtime_effect SET payload_json = '{}' WHERE effect_type = 'SCHEDULE_OCCURRENCE'",
    )

    assertEquals(1, runner.drain())

    assertEquals(emptyList<Pair<String, Long>>(), scheduler.scheduleCalls)
    assertEquals("FAILED", occurrence().state)
    assertEquals("INVALID_EFFECT_PAYLOAD", occurrence().lastErrorCode)
    assertEquals("DEAD_LETTER", scheduleEffect().status)
  }

  @Test
  fun repeatedTransientFailureStopsAfterBoundedAttempts() {
    scheduler.mode = FakeExactAlarmScheduler.Mode.TRANSIENT

    repeat(5) { attemptIndex ->
      assertEquals(1, runner.drain())
      if (attemptIndex < 4) clock.nowMs = checkNotNull(scheduleEffect().nextAttemptAtMs)
    }

    assertEquals(5, scheduler.scheduleCalls.size)
    assertEquals("FAILED", occurrence().state)
    assertEquals("EXACT_ALARM_RETRY_EXHAUSTED", occurrence().lastErrorCode)
    assertEquals("DEAD_LETTER", scheduleEffect().status)
    assertEquals(5, scheduleEffect().attemptCount)
  }

  @Test
  fun enabledEditCancelsOldPendingIntentBeforeSchedulingReplacement() {
    assertEquals(1, runner.drain())
    editEnabledAlarm()

    assertEquals(2, runner.drain())

    assertEquals(listOf(OCCURRENCE_ID), scheduler.cancelCalls)
    assertEquals(
      listOf(OCCURRENCE_ID, REPLACEMENT_OCCURRENCE_ID),
      scheduler.scheduleCalls.map { it.first },
    )
    assertEquals("ACKNOWLEDGED", cancellationEffect().status)
    assertEquals("SCHEDULED_OS", replacementOccurrence().state)
  }

  @Test
  fun transientCancellationFailureBlocksReplacementUntilCancellationSucceeds() {
    assertEquals(1, runner.drain())
    editEnabledAlarm()
    scheduler.cancelTransient = true

    assertEquals(1, runner.drain())

    assertEquals("RETRYABLE", cancellationEffect().status)
    assertEquals(1, scheduler.scheduleCalls.size)
    assertEquals("PENDING", replacementScheduleEffect().status)

    scheduler.cancelTransient = false
    clock.nowMs = checkNotNull(cancellationEffect().nextAttemptAtMs)
    assertEquals(2, runner.drain())
    assertEquals("ACKNOWLEDGED", cancellationEffect().status)
    assertEquals("SCHEDULED_OS", replacementOccurrence().state)
  }

  private fun seedEnabledAlarm() {
    val drafts = AlarmDraftRepository(database, clock) { AlarmId.parse(ALARM_ID) }
    drafts.save(
      SaveAlarmDraftCommand(
        commandId = CommandId.parse(SAVE_COMMAND_ID),
        alarmId = null,
        expectedRevision = null,
        label = "Wake up",
        scheduleKind = "WEEKLY",
        localTimeMinutes = 420,
        repeatDaysMask = 1,
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
    val effectIds = ArrayDeque(listOf(EFFECT_ID_1, EFFECT_ID_2))
    AlarmSchedulingRepository(
      database = database,
      wallClock = clock,
      currentZoneProvider = CurrentZoneProvider { ZoneId.of("Asia/Makassar") },
      occurrenceIdGenerator = OccurrenceIdGenerator { OccurrenceId.parse(OCCURRENCE_ID) },
      effectIdGenerator = EffectIdGenerator { effectIds.removeFirst() },
    ).enable(
      EnableAlarmCommand(
        commandId = CommandId.parse(ENABLE_COMMAND_ID),
        alarmId = AlarmId.parse(ALARM_ID),
        expectedRevision = Revision.of(1),
      ),
    )
  }

  private fun editEnabledAlarm() {
    val effectIds = ArrayDeque(listOf(EDIT_EFFECT_ID_1, EDIT_EFFECT_ID_2, EDIT_EFFECT_ID_3))
    AlarmSchedulingRepository(
      database = database,
      wallClock = clock,
      currentZoneProvider = CurrentZoneProvider { ZoneId.of("Asia/Makassar") },
      occurrenceIdGenerator = OccurrenceIdGenerator {
        OccurrenceId.parse(REPLACEMENT_OCCURRENCE_ID)
      },
      effectIdGenerator = EffectIdGenerator { effectIds.removeFirst() },
    ).editEnabled(
      SaveAlarmDraftCommand(
        commandId = CommandId.parse(EDIT_COMMAND_ID),
        alarmId = AlarmId.parse(ALARM_ID),
        expectedRevision = Revision.of(2),
        label = "Updated",
        scheduleKind = "WEEKLY",
        localTimeMinutes = 480,
        repeatDaysMask = 1,
        oneTimeAtUtcMs = null,
        configuredTimezoneId = "UTC",
        soundId = "classic",
        missionType = MissionType.MATH,
        target = 4,
        pushupProfileVersion = null,
        mathOperationsMask = 7,
        mathGeneratorVersion = "math-v1",
      ),
    )
  }

  private fun occurrence() = checkNotNull(database.runtimeDao().findOccurrenceById(OCCURRENCE_ID))

  private fun scheduleEffect() = checkNotNull(
    database.reliabilityDao().findEffect("effect:v1:occurrence:$OCCURRENCE_ID:schedule"),
  )

  private fun capabilityLossEffect() = checkNotNull(
    database.reliabilityDao().findEffect(
      "effect:v1:capability-loss:loss-1:occurrence:$OCCURRENCE_ID:schedule",
    ),
  )

  private fun capabilityReconciler() = SchedulingCapabilityReconciler(
    database,
    clock,
    EffectIdGenerator { "capability-effect-${++capabilityEffectSequence}" },
  )

  private fun mirrorEffect() = checkNotNull(
    database.reliabilityDao().findEffect("effect:v1:alarm:$ALARM_ID:revision:2:direct-boot"),
  )

  private fun cancellationEffect() = checkNotNull(
    database.reliabilityDao().findEffect("effect:v1:occurrence:$OCCURRENCE_ID:cancel"),
  )

  private fun replacementScheduleEffect() = checkNotNull(
    database.reliabilityDao().findEffect(
      "effect:v1:occurrence:$REPLACEMENT_OCCURRENCE_ID:schedule",
    ),
  )

  private fun replacementOccurrence() = checkNotNull(
    database.runtimeDao().findOccurrenceById(REPLACEMENT_OCCURRENCE_ID),
  )

  private class MutableWallClock(var nowMs: Long) : WallClock {
    override fun nowEpochMillis(): Long = nowMs
  }

  private class FakeExactAlarmScheduler : ExactAlarmScheduler {
    enum class Mode { SUCCESS, TRANSIENT, CAPABILITY }

    var mode = Mode.SUCCESS
    var cancelTransient = false
    val scheduleCalls = mutableListOf<Pair<String, Long>>()
    val cancelCalls = mutableListOf<String>()

    override fun schedule(occurrenceId: OccurrenceId, scheduledAtUtcMs: Long) {
      scheduleCalls += occurrenceId.value to scheduledAtUtcMs
      when (mode) {
        Mode.SUCCESS -> Unit
        Mode.TRANSIENT -> throw IllegalStateException("injected transient failure")
        Mode.CAPABILITY -> throw ExactAlarmCapabilityException()
      }
    }

    override fun cancel(occurrenceId: OccurrenceId) {
      cancelCalls += occurrenceId.value
      if (cancelTransient) throw IllegalStateException("injected cancellation failure")
    }
  }

  private companion object {
    var capabilityEffectSequence = 0
    val NOW_MS = Instant.parse("2026-08-29T00:00:00Z").toEpochMilli()
    val EXPECTED_NEXT_MS = Instant.parse("2026-08-30T23:00:00Z").toEpochMilli()
    const val OWNER = "runner-test-owner"
    const val ALARM_ID = "5a7464b0-77b6-4f75-8459-974dc6d44160"
    const val OCCURRENCE_ID = "a6b9c6bb-bc38-4a8d-b8d6-1a34202e0bc4"
    const val SAVE_COMMAND_ID = "126baf63-80fb-4449-89ac-37667b33ff44"
    const val ENABLE_COMMAND_ID = "dc1457ab-ef8d-49c1-a67c-4cafc5063c22"
    const val EDIT_COMMAND_ID = "957c85c3-1292-46ec-a714-35a7bded0781"
    const val REPLACEMENT_OCCURRENCE_ID = "cdd7bc61-d2f7-4ad4-894f-a29341766e31"
    const val EFFECT_ID_1 = "725d43c5-ac42-42df-bade-a752b3f532ff"
    const val EFFECT_ID_2 = "89927654-58ae-47d4-aaf8-50122651f698"
    const val EDIT_EFFECT_ID_1 = "e3394818-f16a-4896-b364-85499f30c99d"
    const val EDIT_EFFECT_ID_2 = "47fd07a5-b11f-42e1-a62f-64164b50557b"
    const val EDIT_EFFECT_ID_3 = "d66ac6af-b243-4e1c-839e-a77f638cc2f7"
  }
}
