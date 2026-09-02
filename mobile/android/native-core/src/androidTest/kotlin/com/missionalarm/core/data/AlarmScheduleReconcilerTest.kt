package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.WallClock
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmScheduleReconcilerTest {
  private lateinit var database: MissionAlarmDatabase
  private var effectSequence = 0

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun timezoneChangeAtomicallyCancelsOldOccurrenceAndSchedulesLocalTimeInNewZone() {
    seedWeeklyAlarmAndOccurrence(
      scheduledAt = OLD_MAKASSAR_OCCURRENCE,
      scheduledLocalDate = "2026-09-08",
      timezoneId = "Asia/Makassar",
      utcOffsetSeconds = 8 * 60 * 60,
    )
    val reconciler = reconciler("Asia/Jakarta")

    val first = reconciler.reconcile("timezone-change-1")
    val replay = reconciler.reconcile("timezone-change-1")

    assertEquals(ScheduleReconciliationResult(0, 1, 0, 0), first)
    assertEquals(ScheduleReconciliationResult(0, 0, 1, 0), replay)
    assertEquals("CANCELLED", database.runtimeDao().findOccurrenceById(OLD_OCCURRENCE_ID)!!.state)
    val replacement = checkNotNull(database.runtimeDao().findNextOccurrence(ALARM_ID))
    assertEquals(REPLACEMENT_OCCURRENCE_ID, replacement.id)
    assertEquals(EXPECTED_JAKARTA_OCCURRENCE, replacement.scheduledAtUtcMs)
    assertEquals("2026-09-01", replacement.scheduledLocalDate)
    assertEquals(420, replacement.scheduledLocalTimeMinutes)
    assertEquals("Asia/Jakarta", replacement.timezoneId)
    assertEquals(7 * 60 * 60, replacement.utcOffsetSeconds)
    assertEquals(2, database.alarmDao().findAlarmEntity(ALARM_ID)!!.revision)
    assertEquals(3L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun matchingOccurrenceIsForceRescheduledWithoutRevisionOrOccurrenceDuplication() {
    seedWeeklyAlarmAndOccurrence(
      scheduledAt = EXPECTED_JAKARTA_OCCURRENCE,
      scheduledLocalDate = "2026-09-01",
      timezoneId = "Asia/Jakarta",
      utcOffsetSeconds = 7 * 60 * 60,
    )
    val reconciler = reconciler("Asia/Jakarta")

    val first = reconciler.reconcile("package-replaced-1")
    val replay = reconciler.reconcile("package-replaced-1")

    assertEquals(ScheduleReconciliationResult(0, 0, 1, 0), first)
    assertEquals(ScheduleReconciliationResult(0, 0, 1, 0), replay)
    assertEquals(1, database.alarmDao().findAlarmEntity(ALARM_ID)!!.revision)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_occurrence"))
    assertEquals(2L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun expiredOneTimeWithoutDueOccurrenceIsLeftFailClosedForTriggerRecovery() {
    seedOneTimeAlarm()

    val result = reconciler("UTC").reconcile("time-change-expired")

    assertEquals(ScheduleReconciliationResult(0, 0, 0, 1), result)
    assertEquals(1, database.alarmDao().findAlarmEntity(ALARM_ID)!!.revision)
    assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  private fun reconciler(zoneId: String): AlarmScheduleReconciler {
    var occurrenceIssued = false
    return AlarmScheduleReconciler(
      database,
      WallClock { NOW_MS },
      CurrentZoneProvider { ZoneId.of(zoneId) },
      OccurrenceIdGenerator {
        check(!occurrenceIssued) { "unexpected replacement occurrence" }
        occurrenceIssued = true
        OccurrenceId.parse(REPLACEMENT_OCCURRENCE_ID)
      },
      EffectIdGenerator { "time-effect-${++effectSequence}" },
    )
  }

  private fun seedWeeklyAlarmAndOccurrence(
    scheduledAt: Long,
    scheduledLocalDate: String,
    timezoneId: String,
    utcOffsetSeconds: Int,
  ) {
    seedAlarm(
      AlarmEntity(
        ALARM_ID, 1, "Weekly", false, "WEEKLY", 420, 2, null, "UTC", "classic", 1, 1,
      ),
    )
    database.runtimeDao().insertOccurrence(
      AlarmOccurrenceEntity(
        OLD_OCCURRENCE_ID,
        "occ:v1:$ALARM_ID:1:$scheduledAt",
        ALARM_ID,
        1,
        scheduledAt,
        scheduledLocalDate,
        420,
        timezoneId,
        utcOffsetSeconds,
        "SCHEDULED_OS",
        null,
        1,
        1,
      ),
    )
  }

  private fun seedOneTimeAlarm() {
    seedAlarm(
      AlarmEntity(
        ALARM_ID,
        1,
        "Once",
        false,
        "ONE_TIME",
        0,
        0,
        NOW_MS - 1,
        "UTC",
        "classic",
        1,
        1,
      ),
    )
  }

  private fun seedAlarm(disabled: AlarmEntity) {
    database.runInTransaction {
      database.alarmDao().insertAlarm(disabled)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          ALARM_ID, "MATH", 1, 1, null, 7, "math-v1", null, null, null,
        ),
      )
      database.alarmDao().updateAlarm(disabled.copy(enabled = true))
    }
  }

  private fun scalar(sql: String): Long = database.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private companion object {
    val NOW_MS = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
    val OLD_MAKASSAR_OCCURRENCE = Instant.parse("2026-09-07T23:00:00Z").toEpochMilli()
    val EXPECTED_JAKARTA_OCCURRENCE = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
    const val ALARM_ID = "07b675f9-29ad-4c22-b7e6-e677ae783d5d"
    const val OLD_OCCURRENCE_ID = "8e2d947a-0057-4810-a95f-cf9eaf83ef76"
    const val REPLACEMENT_OCCURRENCE_ID = "2d15a85e-a19e-4f21-92b3-6b5d4d2d5b53"
  }
}
