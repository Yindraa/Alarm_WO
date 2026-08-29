package com.missionalarm.core.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmDaoTest {
  private lateinit var database: MissionAlarmDatabase

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun draftRoundTripPersistsOneAlarmWithOneMission() {
    val alarm = validAlarm()
    val mission = validMission(alarm.id)

    database.alarmDao().insertDraft(alarm, mission)
    val restored = database.alarmDao().findById(alarm.id)!!

    assertEquals(1, database.alarmDao().count())
    assertEquals("Alarm", restored.alarm.label)
    assertFalse(restored.alarm.enabled)
    assertEquals("MATH", restored.mission.missionType)
    assertEquals(3, restored.mission.target)
  }

  @Test
  fun invalidScheduleIsRejectedByDatabaseInvariantTrigger() {
    val alarm = validAlarm().copy(repeatDaysMask = 0)

    assertThrows(SQLiteConstraintException::class.java) {
      database.alarmDao().insertDraft(alarm, validMission(alarm.id))
    }
    assertEquals(0, database.alarmDao().count())
  }

  private fun validAlarm() = AlarmEntity(
    id = "5a7464b0-77b6-4f75-8459-974dc6d44160",
    revision = 1,
    label = "Alarm",
    enabled = false,
    scheduleKind = "WEEKLY",
    localTimeMinutes = 7 * 60,
    repeatDaysMask = 1,
    oneTimeAtUtcMs = null,
    configuredTimezoneId = "Asia/Makassar",
    soundId = "classic",
    createdAtMs = 1_788_000_000_000,
    updatedAtMs = 1_788_000_000_000,
  )

  private fun validMission(alarmId: String) = AlarmMissionConfigEntity(
    alarmId = alarmId,
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
}
