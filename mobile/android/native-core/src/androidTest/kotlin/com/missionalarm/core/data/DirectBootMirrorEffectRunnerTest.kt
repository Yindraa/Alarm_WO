package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectBootMirrorEffectRunnerTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var store: RecordingMirrorStore
  private lateinit var clock: MutableClock
  private lateinit var runner: DirectBootMirrorEffectRunner

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    store = RecordingMirrorStore()
    clock = MutableClock(1_000)
    runner = DirectBootMirrorEffectRunner(database, clock, LeaseOwnerGenerator { "test-owner" }, store)
    seedEnabledAlarmAndEffect(validPayload())
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun validEffectRebuildsSanitizedSnapshotAndAcknowledges() {
    assertEquals(1, runner.drain())
    assertEquals(0, runner.drain())

    val snapshot = store.calls.single().single()
    assertEquals(OCCURRENCE_ID, snapshot.occurrenceId)
    assertEquals("classic", snapshot.soundId)
    assertEquals("MATH", snapshot.missionType)
    assertEquals(3, snapshot.target)
    assertEquals("ACKNOWLEDGED", effect().status)
  }

  @Test
  fun malformedPayloadIsDeadLetteredWithoutTouchingMirror() {
    database.openHelper.writableDatabase.execSQL(
      "UPDATE runtime_effect SET payload_json = '{}' WHERE id = '$EFFECT_ID'",
    )

    assertEquals(1, runner.drain())
    assertTrue(store.calls.isEmpty())
    assertEquals("DEAD_LETTER", effect().status)
    assertEquals("INVALID_EFFECT_PAYLOAD", effect().lastErrorCode)
  }

  @Test
  fun transientMirrorFailureIsDelayedForRetry() {
    store.fail = true

    assertEquals(1, runner.drain())
    assertEquals("RETRYABLE", effect().status)
    assertEquals(2_000L, effect().nextAttemptAtMs)
    assertEquals("DIRECT_BOOT_MIRROR_TRANSIENT_FAILURE", effect().lastErrorCode)
    assertEquals(0, runner.drain())
  }

  private fun seedEnabledAlarmAndEffect(payload: String) {
    val alarm = AlarmEntity(
      id = ALARM_ID,
      revision = 2,
      label = "Private label",
      enabled = false,
      scheduleKind = "WEEKLY",
      localTimeMinutes = 420,
      repeatDaysMask = 1,
      oneTimeAtUtcMs = null,
      configuredTimezoneId = "UTC",
      soundId = "classic",
      createdAtMs = 1,
      updatedAtMs = 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
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
        ),
      )
      database.alarmDao().updateAlarm(alarm.copy(enabled = true))
      database.runtimeDao().insertOccurrence(
        AlarmOccurrenceEntity(
          id = OCCURRENCE_ID,
          dedupeKey = "alarm:$ALARM_ID:revision:2",
          alarmId = ALARM_ID,
          alarmRevision = 2,
          scheduledAtUtcMs = 5_000,
          scheduledLocalDate = "2026-08-31",
          scheduledLocalTimeMinutes = 420,
          timezoneId = "UTC",
          utcOffsetSeconds = 0,
          state = "PENDING_OS",
          lastErrorCode = null,
          createdAtMs = 1,
          updatedAtMs = 1,
        ),
      )
      database.reliabilityDao().insertEffect(
        RuntimeEffectEntity(
          id = EFFECT_ID,
          effectKey = "mirror-test",
          aggregateType = "ALARM",
          aggregateId = ALARM_ID,
          effectType = "SYNC_DIRECT_BOOT_MIRROR",
          payloadVersion = 1,
          payloadJson = payload,
          status = "PENDING",
          attemptCount = 0,
          nextAttemptAtMs = null,
          leaseOwner = null,
          leaseUntilMs = null,
          lastErrorCode = null,
          createdAtMs = 1,
          updatedAtMs = 1,
          acknowledgedAtMs = null,
        ),
      )
    }
  }

  private fun validPayload() =
    "{\"alarmId\":\"$ALARM_ID\",\"alarmRevision\":2,\"occurrenceId\":\"$OCCURRENCE_ID\"}"

  private fun effect() = checkNotNull(database.reliabilityDao().findEffectById(EFFECT_ID))

  private class MutableClock(var now: Long) : WallClock {
    override fun nowEpochMillis() = now
  }

  private class RecordingMirrorStore : DirectBootMirrorStore {
    val calls = mutableListOf<List<BootScheduleSnapshot>>()
    var fail = false

    override fun rebuild(schedules: List<BootScheduleSnapshot>, updatedAtMs: Long) {
      if (fail) throw IllegalStateException("disk unavailable")
      calls += schedules
    }
  }

  private companion object {
    const val ALARM_ID = "ed6aa8c6-ad1b-43dd-bce7-640cc54d8e09"
    const val OCCURRENCE_ID = "768de903-4952-4c67-8547-70886bcf8a92"
    const val EFFECT_ID = "592507c8-13a8-483d-bc0d-c4104722be38"
  }
}
