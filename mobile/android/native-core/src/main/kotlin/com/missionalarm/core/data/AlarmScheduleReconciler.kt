package com.missionalarm.core.data

import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.AlarmSchedule
import com.missionalarm.core.domain.LocalTimeMinutes
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.OccurrenceIdentity
import com.missionalarm.core.domain.RecurrencePolicy
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import com.missionalarm.core.domain.WeekdayMask
import java.time.Instant

data class ScheduleReconciliationResult(
  val unchanged: Int,
  val replaced: Int,
  val forceRescheduled: Int,
  val skippedExpired: Int,
)

/** Recomputes enabled alarm occurrences after wall-clock, date, timezone, or package changes. */
class AlarmScheduleReconciler(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val currentZoneProvider: CurrentZoneProvider,
  private val occurrenceIdGenerator: OccurrenceIdGenerator,
  private val effectIdGenerator: EffectIdGenerator,
  private val recurrencePolicy: RecurrencePolicy = RecurrencePolicy(),
) {
  fun reconcile(reconciliationId: String, forceReschedule: Boolean = true): ScheduleReconciliationResult {
    require(reconciliationId.matches(SAFE_ID)) { "invalid reconciliation identity" }
    return database.runInTransaction<ScheduleReconciliationResult> {
      val nowMs = wallClock.nowEpochMillis().also { require(it >= 0) }
      val zone = currentZoneProvider.current()
      var unchanged = 0
      var replaced = 0
      var forceRescheduled = 0
      var skippedExpired = 0

      database.alarmDao().findAllEnabled().forEach { stored ->
        val alarm = stored.alarm
        val desired = recurrencePolicy.next(alarm.toSchedule(), Instant.ofEpochMilli(nowMs), zone)
        if (desired == null) {
          skippedExpired += 1
          return@forEach
        }
        val current = database.runtimeDao().findSchedulableOccurrences(alarm.id)
        val matching = current.singleOrNull()?.takeIf {
          it.alarmRevision == alarm.revision &&
            it.scheduledAtUtcMs == desired.instant.toEpochMilli() &&
            it.scheduledLocalDate == desired.scheduledLocalDate.toString() &&
            it.scheduledLocalTimeMinutes == desired.scheduledLocalTime.value &&
            it.timezoneId == desired.timezoneId &&
            it.utcOffsetSeconds == desired.utcOffsetSeconds
        }
        if (matching != null) {
          if (forceReschedule) {
            insertScheduleEffect(matching, reconciliationId, nowMs)
            insertMirrorEffect(alarm.id, alarm.revision, matching.id, reconciliationId, nowMs)
            forceRescheduled += 1
          } else {
            unchanged += 1
          }
          return@forEach
        }

        val revision = Revision.of(alarm.revision).next()
        check(database.alarmDao().updateAlarm(
          alarm.copy(revision = revision.value, updatedAtMs = nowMs),
        ) == 1) { "alarm reconciliation update lost" }
        current.forEach { occurrence ->
          check(database.runtimeDao().markOccurrenceCancelled(occurrence.id, nowMs) == 1) {
            "occurrence reconciliation cancellation lost"
          }
          insertCancelEffect(occurrence, reconciliationId, nowMs)
        }
        val replacementId = occurrenceIdGenerator.next()
        val replacement = AlarmOccurrenceEntity(
          id = replacementId.value,
          dedupeKey = OccurrenceIdentity.dedupeKey(AlarmId.parse(alarm.id), revision, desired.instant),
          alarmId = alarm.id,
          alarmRevision = revision.value,
          scheduledAtUtcMs = desired.instant.toEpochMilli(),
          scheduledLocalDate = desired.scheduledLocalDate.toString(),
          scheduledLocalTimeMinutes = desired.scheduledLocalTime.value,
          timezoneId = desired.timezoneId,
          utcOffsetSeconds = desired.utcOffsetSeconds,
          state = "PENDING_OS",
          lastErrorCode = null,
          createdAtMs = nowMs,
          updatedAtMs = nowMs,
        )
        database.runtimeDao().insertOccurrence(replacement)
        insertScheduleEffect(replacement, reconciliationId, nowMs)
        insertMirrorEffect(alarm.id, revision.value, replacement.id, reconciliationId, nowMs)
        replaced += 1
      }
      ScheduleReconciliationResult(unchanged, replaced, forceRescheduled, skippedExpired)
    }
  }

  private fun AlarmEntity.toSchedule(): AlarmSchedule = when (scheduleKind) {
    "ONE_TIME" -> AlarmSchedule.OneTime(Instant.ofEpochMilli(checkNotNull(oneTimeAtUtcMs)))
    "WEEKLY" -> AlarmSchedule.Weekly(
      LocalTimeMinutes.of(localTimeMinutes),
      WeekdayMask.of(repeatDaysMask),
    )
    else -> error("unsupported schedule kind")
  }

  private fun insertScheduleEffect(
    occurrence: AlarmOccurrenceEntity,
    reconciliationId: String,
    nowMs: Long,
  ) = insertEffect(
    "effect:v1:reconcile:$reconciliationId:occurrence:${occurrence.id}:schedule",
    "OCCURRENCE",
    occurrence.id,
    "SCHEDULE_OCCURRENCE",
    "{\"occurrenceId\":\"${occurrence.id}\",\"scheduledAtUtcMs\":${occurrence.scheduledAtUtcMs}}",
    nowMs,
  )

  private fun insertCancelEffect(
    occurrence: AlarmOccurrenceEntity,
    reconciliationId: String,
    nowMs: Long,
  ) = insertEffect(
    "effect:v1:reconcile:$reconciliationId:occurrence:${occurrence.id}:cancel",
    "OCCURRENCE",
    occurrence.id,
    "CANCEL_OCCURRENCE",
    "{\"occurrenceId\":\"${occurrence.id}\"}",
    nowMs,
  )

  private fun insertMirrorEffect(
    alarmId: String,
    revision: Int,
    occurrenceId: String,
    reconciliationId: String,
    nowMs: Long,
  ) = insertEffect(
    "effect:v1:reconcile:$reconciliationId:alarm:$alarmId:mirror",
    "ALARM",
    alarmId,
    "SYNC_DIRECT_BOOT_MIRROR",
    "{\"alarmId\":\"$alarmId\",\"alarmRevision\":$revision," +
      "\"occurrenceId\":\"$occurrenceId\"}",
    nowMs,
  )

  private fun insertEffect(
    effectKey: String,
    aggregateType: String,
    aggregateId: String,
    effectType: String,
    payloadJson: String,
    nowMs: Long,
  ) {
    database.reliabilityDao().insertEffect(
      RuntimeEffectEntity(
        effectIdGenerator.next(),
        effectKey,
        aggregateType,
        aggregateId,
        effectType,
        1,
        payloadJson,
        "PENDING",
        0,
        null,
        null,
        null,
        null,
        nowMs,
        nowMs,
        null,
      ),
    )
  }

  private companion object {
    val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,160}")
  }
}
