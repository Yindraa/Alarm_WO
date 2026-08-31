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
class PresentationEffectRunnerTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var clock: MutableClock
  private lateinit var presenter: RecordingPresenter
  private lateinit var runner: PresentationEffectRunner

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    clock = MutableClock(1_000)
    presenter = RecordingPresenter()
    runner = PresentationEffectRunner(
      database,
      clock,
      LeaseOwnerGenerator { "presentation-owner" },
      presenter,
    )
    seedActiveInstanceAndEffect()
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun successfulPresentationUsesStableIdentityAndAcknowledges() {
    assertEquals(1, runner.drain())
    assertEquals(0, runner.drain())
    assertEquals(listOf(INSTANCE_ID), presenter.calls)
    assertEquals("ACKNOWLEDGED", effect().status)
  }

  @Test
  fun transientPresentationFailureRetriesAtSameIdentity() {
    presenter.fail = true

    assertEquals(1, runner.drain())
    assertEquals("RETRYABLE", effect().status)
    assertEquals(2_000L, effect().nextAttemptAtMs)
    assertEquals("ALARM_PRESENTATION_TRANSIENT_FAILURE", effect().lastErrorCode)

    clock.now = 2_000
    presenter.fail = false
    assertEquals(1, runner.drain())
    assertEquals(listOf(INSTANCE_ID, INSTANCE_ID), presenter.calls)
    assertEquals("ACKNOWLEDGED", effect().status)
  }

  @Test
  fun malformedPayloadIsDeadLetteredWithoutOpeningHost() {
    database.openHelper.writableDatabase.execSQL(
      "UPDATE runtime_effect SET payload_json='{}' WHERE id='$EFFECT_ID'",
    )

    assertEquals(1, runner.drain())
    assertTrue(presenter.calls.isEmpty())
    assertEquals("DEAD_LETTER", effect().status)
    assertEquals("INVALID_EFFECT_PAYLOAD", effect().lastErrorCode)
  }

  private fun seedActiveInstanceAndEffect() {
    val alarm = AlarmEntity(
      ALARM_ID, 1, "Alarm", false, "WEEKLY", 420, 1, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          ALARM_ID, "MATH", 1, 3, null, 7, "math-v1", null, null, null,
        ),
      )
      database.alarmDao().updateAlarm(alarm.copy(enabled = true))
      database.runtimeDao().insertOccurrence(
        AlarmOccurrenceEntity(
          OCCURRENCE_ID,
          "occ:v1:$ALARM_ID:1:500",
          ALARM_ID,
          1,
          500,
          "2026-08-30",
          420,
          "UTC",
          0,
          "SCHEDULED_OS",
          null,
          1,
          1,
        ),
      )
      database.runtimeDao().getOrCreateTriggeredInstance(
        AlarmInstanceEntity(
          INSTANCE_ID,
          OCCURRENCE_ID,
          ALARM_ID,
          1,
          "TRIGGERED",
          1,
          1,
          500,
          1_000,
          100,
          "boot:1",
          null,
          null,
          null,
          null,
          "Alarm",
          "classic",
          1_000,
          1_000,
        ),
        InstanceMissionEntity(
          INSTANCE_ID, "MATH", 1, 3, 0, "READY", "math-v1",
          null, "math-v1", null, null, null, 1_000,
        ),
      )
      database.reliabilityDao().insertEffect(
        RuntimeEffectEntity(
          EFFECT_ID,
          "effect:v1:instance:$INSTANCE_ID:PRESENT_ACTIVE_INSTANCE",
          "INSTANCE",
          INSTANCE_ID,
          "PRESENT_ACTIVE_INSTANCE",
          1,
          "{\"instanceId\":\"$INSTANCE_ID\"}",
          "PENDING",
          0,
          null,
          null,
          null,
          null,
          1,
          1,
          null,
        ),
      )
    }
  }

  private fun effect() = checkNotNull(database.reliabilityDao().findEffectById(EFFECT_ID))

  private class MutableClock(var now: Long) : WallClock {
    override fun nowEpochMillis() = now
  }

  private class RecordingPresenter : AlarmHostPresenter {
    val calls = mutableListOf<String>()
    var fail = false

    override fun present(instanceId: String) {
      calls += instanceId
      if (fail) throw IllegalStateException("temporary")
    }
  }

  private companion object {
    const val ALARM_ID = "df0575a5-fc4f-4f70-8bc4-d67c7ba9577b"
    const val OCCURRENCE_ID = "36ca13ee-ac0c-4fe8-99f5-a0c577eafde3"
    const val INSTANCE_ID = "04e4c33e-dd25-42e0-8d19-1ce240f9d2f2"
    const val EFFECT_ID = "9a1e1896-c296-4f1f-91d0-b1bf89e32a9e"
  }
}
