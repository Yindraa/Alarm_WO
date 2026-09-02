package com.missionalarm.core.data

import com.missionalarm.core.domain.WallClock

/** Converts fresh OS exact-alarm capability observations into durable scheduling work. */
class SchedulingCapabilityReconciler(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val effectIdGenerator: EffectIdGenerator,
) {
  fun observe(granted: Boolean, observationId: String): Int {
    require(observationId.matches(SAFE_ID)) { "invalid capability observation identity" }
    val nowMs = wallClock.nowEpochMillis().also { require(it >= 0) }
    if (granted) {
      return database.reliabilityDao().releaseSchedulesAfterCapabilityRecovery(
        SchedulingEffectRunner.ERROR_CAPABILITY_REQUIRED,
        nowMs,
      )
    }
    return database.runInTransaction<Int> {
      val scheduled = database.runtimeDao().findScheduledOccurrences()
      scheduled.forEach { occurrence ->
        database.reliabilityDao().insertEffect(
          RuntimeEffectEntity(
            id = effectIdGenerator.next(),
            effectKey = "effect:v1:capability-loss:$observationId:occurrence:${occurrence.id}:schedule",
            aggregateType = "OCCURRENCE",
            aggregateId = occurrence.id,
            effectType = "SCHEDULE_OCCURRENCE",
            payloadVersion = 1,
            payloadJson = "{\"occurrenceId\":\"${occurrence.id}\"," +
              "\"scheduledAtUtcMs\":${occurrence.scheduledAtUtcMs}}",
            status = "BLOCKED_CAPABILITY",
            attemptCount = 0,
            nextAttemptAtMs = null,
            leaseOwner = null,
            leaseUntilMs = null,
            lastErrorCode = SchedulingEffectRunner.ERROR_CAPABILITY_REQUIRED,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            acknowledgedAtMs = null,
          ),
        )
      }
      val blocked = database.runtimeDao().markScheduledOccurrencesCapabilityBlocked(
        SchedulingEffectRunner.ERROR_CAPABILITY_REQUIRED,
        nowMs,
      )
      check(blocked == scheduled.size) { "exact-alarm capability reconciliation lost an occurrence" }
      blocked
    }
  }

  private companion object {
    val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,160}")
  }
}
