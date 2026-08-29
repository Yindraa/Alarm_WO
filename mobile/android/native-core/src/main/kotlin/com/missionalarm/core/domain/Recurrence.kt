package com.missionalarm.core.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@JvmInline
value class LocalTimeMinutes private constructor(val value: Int) {
  fun asLocalTime(): LocalTime = LocalTime.of(value / MINUTES_PER_HOUR, value % MINUTES_PER_HOUR)

  companion object {
    private const val MINUTES_PER_HOUR = 60

    fun of(value: Int): LocalTimeMinutes {
      require(value in 0..1439) { "local time minutes must be 0..1439" }
      return LocalTimeMinutes(value)
    }
  }
}

@JvmInline
value class WeekdayMask private constructor(val value: Int) {
  operator fun contains(day: DayOfWeek): Boolean = value and bitFor(day) != 0

  companion object {
    fun of(value: Int): WeekdayMask {
      require(value in 1..127) { "weekly repeat mask must be 1..127" }
      return WeekdayMask(value)
    }

    fun of(vararg days: DayOfWeek): WeekdayMask {
      require(days.isNotEmpty()) { "at least one weekday is required" }
      return WeekdayMask(days.fold(0) { mask, day -> mask or bitFor(day) })
    }

    private fun bitFor(day: DayOfWeek): Int = 1 shl (day.value - 1)
  }
}

sealed interface AlarmSchedule {
  data class OneTime(
    val at: Instant,
  ) : AlarmSchedule

  data class Weekly(
    val localTime: LocalTimeMinutes,
    val repeatDays: WeekdayMask,
  ) : AlarmSchedule
}

data class OccurrenceTime(
  val instant: Instant,
  val scheduledLocalDate: LocalDate,
  val scheduledLocalTime: LocalTimeMinutes,
  val timezoneId: String,
  val utcOffsetSeconds: Int,
)

class RecurrencePolicy {
  fun next(
    schedule: AlarmSchedule,
    notBefore: Instant,
    currentZone: ZoneId,
  ): OccurrenceTime? = when (schedule) {
    is AlarmSchedule.OneTime -> oneTime(schedule, notBefore, currentZone)
    is AlarmSchedule.Weekly -> weekly(schedule, notBefore, currentZone)
  }

  private fun oneTime(
    schedule: AlarmSchedule.OneTime,
    notBefore: Instant,
    zone: ZoneId,
  ): OccurrenceTime? {
    if (schedule.at < notBefore) return null
    val local = schedule.at.atZone(zone)
    return local.toOccurrenceTime(LocalTimeMinutes.of(local.hour * 60 + local.minute))
  }

  private fun weekly(
    schedule: AlarmSchedule.Weekly,
    notBefore: Instant,
    zone: ZoneId,
  ): OccurrenceTime {
    val referenceDate = notBefore.atZone(zone).toLocalDate()

    for (dayOffset in 0..MAX_SEARCH_DAYS) {
      val date = referenceDate.plusDays(dayOffset.toLong())
      if (date.dayOfWeek !in schedule.repeatDays) continue

      // atZone resolves a DST gap forward and chooses the earlier offset in an overlap.
      // This yields at most one occurrence for a configured local date.
      val candidate = LocalDateTime.of(date, schedule.localTime.asLocalTime()).atZone(zone)
      if (!candidate.toInstant().isBefore(notBefore)) {
        return candidate.toOccurrenceTime(schedule.localTime)
      }
    }

    error("weekly recurrence search exceeded its seven-day invariant")
  }

  private fun ZonedDateTime.toOccurrenceTime(configuredTime: LocalTimeMinutes) = OccurrenceTime(
    instant = toInstant(),
    scheduledLocalDate = toLocalDate(),
    scheduledLocalTime = configuredTime,
    timezoneId = zone.id,
    utcOffsetSeconds = offset.totalSeconds,
  )

  private companion object {
    const val MAX_SEARCH_DAYS = 7
  }
}

object OccurrenceIdentity {
  fun dedupeKey(
    alarmId: AlarmId,
    alarmRevision: Revision,
    scheduledAt: Instant,
  ): String {
    require(!scheduledAt.isBefore(Instant.EPOCH)) { "scheduled instant must not predate epoch" }
    return "occ:v1:${alarmId.value}:${alarmRevision.value}:${scheduledAt.toEpochMilli()}"
  }
}
