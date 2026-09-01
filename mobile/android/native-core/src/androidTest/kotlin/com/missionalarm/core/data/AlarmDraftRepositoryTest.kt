package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmDraftRepositoryTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var clock: MutableWallClock
  private lateinit var repository: AlarmDraftRepository

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    clock = MutableWallClock(1_000)
    repository = AlarmDraftRepository(database, clock) { AlarmId.parse(ALARM_ID) }
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun newDraftAndReceiptCommitAtomicallyAndRetryReplaysAck() {
    val command = mathCommand(COMMAND_ID)
    val applied = repository.save(command)
    clock.nowMs = 9_000
    val replayed = repository.save(command)
    val restored = repository.find(AlarmId.parse(ALARM_ID))!!

    assertEquals(ALARM_ID, applied.alarmId)
    assertEquals(1, applied.revision)
    assertFalse(applied.replayed)
    assertEquals(applied.copy(replayed = true), replayed)
    assertFalse(restored.alarm.enabled)
    assertEquals("Wake up", restored.alarm.label)
    assertEquals("MATH", restored.mission.missionType)
    assertEquals(1, scalarLong("SELECT COUNT(*) FROM alarm"))
    assertEquals(1, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun reusedCommandIdWithDifferentContentIsRejectedWithoutMutation() {
    repository.save(mathCommand(COMMAND_ID))

    assertThrows(AlarmDraftRepositoryException.IdempotencyKeyReused::class.java) {
      repository.save(mathCommand(COMMAND_ID).copy(label = "Different"))
    }
    assertEquals("Wake up", repository.find(AlarmId.parse(ALARM_ID))!!.alarm.label)
    assertEquals(1, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun editRequiresCurrentRevisionAndPreservesCreationTimestamp() {
    repository.save(mathCommand(COMMAND_ID))
    clock.nowMs = 2_000
    val edit = mathCommand(EDIT_COMMAND_ID).copy(
      alarmId = AlarmId.parse(ALARM_ID),
      expectedRevision = Revision.of(1),
      label = "Updated",
      target = 5,
    )
    val ack = repository.save(edit)
    val restored = repository.find(AlarmId.parse(ALARM_ID))!!

    assertEquals(2, ack.revision)
    assertEquals(1_000, restored.alarm.createdAtMs)
    assertEquals(2_000, restored.alarm.updatedAtMs)
    assertEquals(5, restored.mission.target)

    assertThrows(AlarmDraftRepositoryException.RevisionConflict::class.java) {
      repository.save(edit.copy(commandId = CommandId.parse(STALE_COMMAND_ID)))
    }
    assertEquals(2, repository.find(AlarmId.parse(ALARM_ID))!!.alarm.revision)
    assertEquals(2, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun scanMissionConfigurationPersistsWithoutReferenceData() {
    val ack = repository.save(
      mathCommand(COMMAND_ID).copy(
        missionType = MissionType.QR,
        target = 1,
        mathOperationsMask = null,
        mathGeneratorVersion = null,
      ),
    )

    assertEquals(1, ack.revision)
    assertEquals("QR", repository.find(AlarmId.parse(ALARM_ID))!!.mission.missionType)
    database.openHelper.writableDatabase.execSQL(
      "UPDATE alarm SET enabled = 1, revision = 2 WHERE id = ?",
      arrayOf(ALARM_ID),
    )
    assertTrue(repository.find(AlarmId.parse(ALARM_ID))!!.alarm.enabled)
  }

  private fun mathCommand(commandId: String) = SaveAlarmDraftCommand(
    commandId = CommandId.parse(commandId),
    alarmId = null,
    expectedRevision = null,
    label = " Wake up ",
    scheduleKind = "WEEKLY",
    localTimeMinutes = 420,
    repeatDaysMask = 31,
    oneTimeAtUtcMs = null,
    configuredTimezoneId = "Asia/Makassar",
    soundId = "classic",
    missionType = MissionType.MATH,
    target = 3,
    pushupProfileVersion = null,
    mathOperationsMask = 7,
    mathGeneratorVersion = "math-v1",
  )

  private fun scalarLong(query: String): Long =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      cursor.moveToFirst()
      cursor.getLong(0)
    }

  private class MutableWallClock(var nowMs: Long) : WallClock {
    override fun nowEpochMillis(): Long = nowMs
  }

  private companion object {
    const val ALARM_ID = "5a7464b0-77b6-4f75-8459-974dc6d44160"
    const val COMMAND_ID = "126baf63-80fb-4449-89ac-37667b33ff44"
    const val EDIT_COMMAND_ID = "dc1457ab-ef8d-49c1-a67c-4cafc5063c22"
    const val STALE_COMMAND_ID = "725d43c5-ac42-42df-bade-a752b3f532ff"
  }
}
