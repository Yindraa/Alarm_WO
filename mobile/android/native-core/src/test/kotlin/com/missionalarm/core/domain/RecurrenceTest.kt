package com.missionalarm.core.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {
  private val policy = RecurrencePolicy()

  @Test
  fun `one-time schedule returns future instant once and rejects past instant`() {
    val scheduled = Instant.parse("2026-08-29T08:00:00Z")
    val zone = ZoneId.of("Asia/Makassar")

    assertEquals(
      scheduled,
      policy.next(AlarmSchedule.OneTime(scheduled), scheduled, zone)?.instant,
    )
    assertNull(
      policy.next(
        AlarmSchedule.OneTime(scheduled),
        scheduled.plusMillis(1),
        zone,
      ),
    )
  }

  @Test
  fun `every valid weekday mask selects a day contained in that mask`() {
    val reference = Instant.parse("2026-08-31T00:00:00Z")
    val zone = ZoneOffset.UTC

    for (rawMask in 1..127) {
      val mask = WeekdayMask.of(rawMask)
      val next = policy.next(
        AlarmSchedule.Weekly(LocalTimeMinutes.of(7 * 60), mask),
        reference,
        zone,
      )!!

      assertTrue(next.scheduledLocalDate.dayOfWeek in mask)
      assertTrue(!next.instant.isBefore(reference))
    }
  }

  @Test
  fun `DST gap moves to first valid local instant while preserving configured audit time`() {
    val zone = ZoneId.of("America/New_York")
    val result = policy.next(
      AlarmSchedule.Weekly(
        LocalTimeMinutes.of(2 * 60 + 30),
        WeekdayMask.of(DayOfWeek.SUNDAY),
      ),
      Instant.parse("2026-03-08T05:00:00Z"),
      zone,
    )!!

    assertEquals(Instant.parse("2026-03-08T07:30:00Z"), result.instant)
    assertEquals(2 * 60 + 30, result.scheduledLocalTime.value)
    assertEquals(-4 * 60 * 60, result.utcOffsetSeconds)
  }

  @Test
  fun `DST overlap creates only the earlier occurrence for that local date`() {
    val zone = ZoneId.of("America/New_York")
    val schedule = AlarmSchedule.Weekly(
      LocalTimeMinutes.of(1 * 60 + 30),
      WeekdayMask.of(DayOfWeek.SUNDAY),
    )

    val first = policy.next(schedule, Instant.parse("2026-11-01T04:00:00Z"), zone)!!
    val afterFirst = policy.next(schedule, first.instant.plusSeconds(1), zone)!!

    assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first.instant)
    assertEquals(-4 * 60 * 60, first.utcOffsetSeconds)
    assertEquals(Instant.parse("2026-11-08T06:30:00Z"), afterFirst.instant)
  }

  @Test
  fun `weekly schedule follows current timezone when timezone changes`() {
    val schedule = AlarmSchedule.Weekly(
      LocalTimeMinutes.of(7 * 60),
      WeekdayMask.of(DayOfWeek.SUNDAY),
    )
    val reference = Instant.parse("2026-08-29T12:00:00Z")

    val makassar = policy.next(schedule, reference, ZoneId.of("Asia/Makassar"))!!
    val tokyo = policy.next(schedule, reference, ZoneId.of("Asia/Tokyo"))!!

    assertEquals("Asia/Makassar", makassar.timezoneId)
    assertEquals("Asia/Tokyo", tokyo.timezoneId)
    assertEquals(Instant.parse("2026-08-29T23:00:00Z"), makassar.instant)
    assertEquals(Instant.parse("2026-08-29T22:00:00Z"), tokyo.instant)
  }

  @Test
  fun `occurrence dedupe key is readable and revision-specific`() {
    val alarmId = AlarmId.parse("5a7464b0-77b6-4f75-8459-974dc6d44160")
    val instant = Instant.parse("2026-08-29T08:00:00Z")

    assertEquals(
      "occ:v1:5a7464b0-77b6-4f75-8459-974dc6d44160:3:1787990400000",
      OccurrenceIdentity.dedupeKey(alarmId, Revision.of(3), instant),
    )
  }
}
