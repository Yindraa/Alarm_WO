package com.missionalarm.core.data

import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.WallClock

fun interface ExactAlarmScheduler {
  @Throws(ExactAlarmCapabilityException::class, PermanentSchedulingException::class)
  fun schedule(occurrenceId: OccurrenceId, scheduledAtUtcMs: Long)

  fun cancel(occurrenceId: OccurrenceId) = Unit
}

class ExactAlarmCapabilityException : IllegalStateException("exact alarm capability is unavailable")

class PermanentSchedulingException : IllegalStateException("exact alarm request is invalid")

fun interface LeaseOwnerGenerator {
  fun next(): String
}

/** Processes exact-alarm cancellation before scheduling; unrelated outbox effects remain untouched. */
class SchedulingEffectRunner(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val leaseOwnerGenerator: LeaseOwnerGenerator,
  private val scheduler: ExactAlarmScheduler,
) {
  fun drain(maxEffects: Int = DEFAULT_MAX_DRAIN): Int {
    require(maxEffects in 1..MAX_DRAIN_LIMIT) { "max effects must be 1..$MAX_DRAIN_LIMIT" }
    val owner = leaseOwnerGenerator.next().also {
      require(it.isNotBlank()) { "lease owner must not be blank" }
    }
    var processed = 0
    while (processed < maxEffects) {
      if (runNextCancellation(owner)) {
        processed += 1
        continue
      }
      // A delayed or terminal cancellation fails closed: no replacement schedule becomes healthy
      // while an older immutable PendingIntent may still exist in the OS.
      if (database.reliabilityDao().countUnacknowledgedEffects(CANCEL_EFFECT_TYPE) != 0) break
      if (!runNextSchedule(owner)) break
      processed += 1
    }
    return processed
  }

  private fun runNextSchedule(owner: String): Boolean {
    val claimAtMs = nowMs()
    val effect = database.reliabilityDao().claimNext(
      effectType = SCHEDULE_EFFECT_TYPE,
      owner = owner,
      nowMs = claimAtMs,
      leaseDurationMs = LEASE_DURATION_MS,
    ) ?: return false

    val payload = try {
      ScheduleOccurrencePayload.decode(effect)
    } catch (_: IllegalArgumentException) {
      failAndDeadLetter(effect, owner, ERROR_INVALID_PAYLOAD)
      return true
    }

    val occurrence = database.runtimeDao().findOccurrenceById(payload.occurrenceId.value)
    if (occurrence == null || occurrence.scheduledAtUtcMs != payload.scheduledAtUtcMs) {
      failAndDeadLetter(effect, owner, ERROR_INVALID_PAYLOAD)
      return true
    }
    if (occurrence.state !in SCHEDULABLE_STATES) {
      acknowledge(effect, owner)
      return true
    }

    try {
      scheduler.schedule(payload.occurrenceId, payload.scheduledAtUtcMs)
    } catch (_: ExactAlarmCapabilityException) {
      blockCapability(effect, owner, payload.occurrenceId.value)
      return true
    } catch (_: PermanentSchedulingException) {
      failAndDeadLetter(effect, owner, ERROR_PERMANENT_SCHEDULING)
      return true
    } catch (_: RuntimeException) {
      retryOrFail(effect, owner, payload.occurrenceId.value)
      return true
    }

    val latest = database.runtimeDao().findOccurrenceById(payload.occurrenceId.value)
    if (latest == null || latest.state !in SCHEDULABLE_STATES) {
      scheduler.cancel(payload.occurrenceId)
      acknowledge(effect, owner)
      return true
    }

    val completedAtMs = nowMs()
    database.runInTransaction {
      check(
        database.runtimeDao().markOccurrenceScheduled(payload.occurrenceId.value, completedAtMs) == 1,
      ) { "occurrence scheduling state changed during serialized completion" }
      check(database.reliabilityDao().acknowledge(effect.id, owner, completedAtMs) == 1) {
        "schedule effect lease was lost"
      }
    }
    return true
  }

  private fun runNextCancellation(owner: String): Boolean {
    val effect = database.reliabilityDao().claimNext(
      effectType = CANCEL_EFFECT_TYPE,
      owner = owner,
      nowMs = nowMs(),
      leaseDurationMs = LEASE_DURATION_MS,
    ) ?: return false
    val occurrenceId = try {
      CancelOccurrencePayload.decode(effect)
    } catch (_: IllegalArgumentException) {
      deadLetterEffectOnly(effect, owner, ERROR_INVALID_PAYLOAD)
      return true
    }
    if (database.runtimeDao().findOccurrenceById(occurrenceId.value) == null) {
      deadLetterEffectOnly(effect, owner, ERROR_INVALID_PAYLOAD)
      return true
    }

    try {
      scheduler.cancel(occurrenceId)
    } catch (_: PermanentSchedulingException) {
      deadLetterEffectOnly(effect, owner, ERROR_PERMANENT_CANCELLATION)
      return true
    } catch (_: RuntimeException) {
      retryCancellationOrDeadLetter(effect, owner)
      return true
    }
    acknowledge(effect, owner)
    return true
  }

  private fun acknowledge(effect: RuntimeEffectEntity, owner: String) {
    val completedAtMs = nowMs()
    check(database.reliabilityDao().acknowledge(effect.id, owner, completedAtMs) == 1) {
      "schedule effect lease was lost"
    }
  }

  private fun blockCapability(
    effect: RuntimeEffectEntity,
    owner: String,
    occurrenceId: String,
  ) {
    val completedAtMs = nowMs()
    database.runInTransaction {
      database.runtimeDao().markOccurrenceSchedulingError(
        occurrenceId,
        ERROR_CAPABILITY_REQUIRED,
        completedAtMs,
      )
      check(
        database.reliabilityDao().blockCapability(
          effect.id,
          owner,
          ERROR_CAPABILITY_REQUIRED,
          completedAtMs,
        ) == 1,
      ) { "schedule effect lease was lost" }
    }
  }

  private fun retryOrFail(
    effect: RuntimeEffectEntity,
    owner: String,
    occurrenceId: String,
  ) {
    val completedAtMs = nowMs()
    if (effect.attemptCount >= MAX_ATTEMPTS) {
      database.runInTransaction {
        database.runtimeDao().markOccurrenceSchedulingFailed(
          occurrenceId,
          ERROR_RETRY_EXHAUSTED,
          completedAtMs,
        )
        check(
          database.reliabilityDao().deadLetter(
            effect.id,
            owner,
            ERROR_RETRY_EXHAUSTED,
            completedAtMs,
          ) == 1,
        ) { "schedule effect lease was lost" }
      }
      return
    }

    val nextAttemptAtMs = Math.addExact(completedAtMs, retryDelayMs(effect.attemptCount))
    database.runInTransaction {
      database.runtimeDao().markOccurrenceSchedulingError(
        occurrenceId,
        ERROR_TRANSIENT_SCHEDULING,
        completedAtMs,
      )
      check(
        database.reliabilityDao().retry(
          effect.id,
          owner,
          nextAttemptAtMs,
          ERROR_TRANSIENT_SCHEDULING,
          completedAtMs,
        ) == 1,
      ) { "schedule effect lease was lost" }
    }
  }

  private fun failAndDeadLetter(
    effect: RuntimeEffectEntity,
    owner: String,
    errorCode: String,
  ) {
    val completedAtMs = nowMs()
    database.runInTransaction {
      database.runtimeDao().markOccurrenceSchedulingFailed(
        effect.aggregateId,
        errorCode,
        completedAtMs,
      )
      check(database.reliabilityDao().deadLetter(effect.id, owner, errorCode, completedAtMs) == 1) {
        "schedule effect lease was lost"
      }
    }
  }

  private fun retryCancellationOrDeadLetter(effect: RuntimeEffectEntity, owner: String) {
    val completedAtMs = nowMs()
    if (effect.attemptCount >= MAX_ATTEMPTS) {
      check(
        database.reliabilityDao().deadLetter(
          effect.id,
          owner,
          ERROR_CANCELLATION_RETRY_EXHAUSTED,
          completedAtMs,
        ) == 1,
      ) { "cancel effect lease was lost" }
      return
    }
    check(
      database.reliabilityDao().retry(
        effect.id,
        owner,
        Math.addExact(completedAtMs, retryDelayMs(effect.attemptCount)),
        ERROR_TRANSIENT_CANCELLATION,
        completedAtMs,
      ) == 1,
    ) { "cancel effect lease was lost" }
  }

  private fun deadLetterEffectOnly(
    effect: RuntimeEffectEntity,
    owner: String,
    errorCode: String,
  ) {
    val completedAtMs = nowMs()
    check(database.reliabilityDao().deadLetter(effect.id, owner, errorCode, completedAtMs) == 1) {
      "effect lease was lost"
    }
  }

  private fun nowMs(): Long = wallClock.nowEpochMillis().also {
    require(it >= 0) { "wall clock must not predate epoch" }
  }

  private fun retryDelayMs(attemptCount: Int): Long =
    (INITIAL_RETRY_MS shl (attemptCount - 1).coerceAtLeast(0)).coerceAtMost(MAX_RETRY_MS)

  private companion object {
    const val SCHEDULE_EFFECT_TYPE = "SCHEDULE_OCCURRENCE"
    const val CANCEL_EFFECT_TYPE = "CANCEL_OCCURRENCE"
    const val LEASE_DURATION_MS = 30_000L
    const val INITIAL_RETRY_MS = 1_000L
    const val MAX_RETRY_MS = 5 * 60_000L
    const val MAX_ATTEMPTS = 5
    const val DEFAULT_MAX_DRAIN = 16
    const val MAX_DRAIN_LIMIT = 64
    const val ERROR_CAPABILITY_REQUIRED = "EXACT_ALARM_CAPABILITY_REQUIRED"
    const val ERROR_TRANSIENT_SCHEDULING = "EXACT_ALARM_TRANSIENT_FAILURE"
    const val ERROR_RETRY_EXHAUSTED = "EXACT_ALARM_RETRY_EXHAUSTED"
    const val ERROR_INVALID_PAYLOAD = "INVALID_EFFECT_PAYLOAD"
    const val ERROR_PERMANENT_SCHEDULING = "INVALID_EXACT_ALARM_REQUEST"
    const val ERROR_TRANSIENT_CANCELLATION = "EXACT_ALARM_CANCEL_TRANSIENT_FAILURE"
    const val ERROR_CANCELLATION_RETRY_EXHAUSTED = "EXACT_ALARM_CANCEL_RETRY_EXHAUSTED"
    const val ERROR_PERMANENT_CANCELLATION = "INVALID_EXACT_ALARM_CANCELLATION"
    val SCHEDULABLE_STATES = setOf("PENDING_OS", "SCHEDULED_OS")
  }
}

private data class CancelOccurrencePayload(
  val occurrenceId: OccurrenceId,
) {
  companion object {
    private val EXACT_PAYLOAD = Regex(
      """^\{"occurrenceId":"([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})"\}$""",
    )

    fun decode(effect: RuntimeEffectEntity): OccurrenceId {
      require(effect.effectType == "CANCEL_OCCURRENCE")
      require(effect.aggregateType == "OCCURRENCE")
      require(effect.payloadVersion == 1)
      val match = requireNotNull(EXACT_PAYLOAD.matchEntire(effect.payloadJson))
      val occurrenceId = OccurrenceId.parse(match.groupValues[1])
      require(effect.aggregateId == occurrenceId.value)
      return occurrenceId
    }
  }
}

private data class ScheduleOccurrencePayload(
  val occurrenceId: OccurrenceId,
  val scheduledAtUtcMs: Long,
) {
  companion object {
    private val EXACT_PAYLOAD = Regex(
      """^\{"occurrenceId":"([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})","scheduledAtUtcMs":([0-9]{1,16})\}$""",
    )

    fun decode(effect: RuntimeEffectEntity): ScheduleOccurrencePayload {
      require(effect.effectType == "SCHEDULE_OCCURRENCE")
      require(effect.aggregateType == "OCCURRENCE")
      require(effect.payloadVersion == 1)
      val match = requireNotNull(EXACT_PAYLOAD.matchEntire(effect.payloadJson))
      val occurrenceId = OccurrenceId.parse(match.groupValues[1])
      require(effect.aggregateId == occurrenceId.value)
      val scheduledAtUtcMs = requireNotNull(match.groupValues[2].toLongOrNull())
      require(scheduledAtUtcMs >= 0)
      return ScheduleOccurrencePayload(occurrenceId, scheduledAtUtcMs)
    }
  }
}
