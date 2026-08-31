package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSchedulingRepositoryTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var clock: MutableWallClock
  private lateinit var drafts: AlarmDraftRepository
  private lateinit var scheduling: AlarmSchedulingRepository

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    clock = MutableWallClock(NOW_MS)
    drafts = AlarmDraftRepository(database, clock) { AlarmId.parse(ALARM_ID) }
    val occurrenceIds = ArrayDeque(listOf(OCCURRENCE_ID, REPLACEMENT_OCCURRENCE_ID))
    val effectIds = ArrayDeque(
      listOf(EFFECT_ID_1, EFFECT_ID_2, EFFECT_ID_3, EFFECT_ID_4, EFFECT_ID_5, EFFECT_ID_6),
    )
    scheduling = AlarmSchedulingRepository(
      database = database,
      wallClock = clock,
      currentZoneProvider = CurrentZoneProvider { ZoneId.of("Asia/Makassar") },
      occurrenceIdGenerator = OccurrenceIdGenerator {
        OccurrenceId.parse(occurrenceIds.removeFirst())
      },
      effectIdGenerator = EffectIdGenerator { effectIds.removeFirst() },
    )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun enableCommitsAlarmOccurrenceEffectsAndReceiptThenRetryReplays() {
    drafts.save(weeklyDraft())
    val command = enableCommand(ENABLE_COMMAND_ID)

    val applied = scheduling.enable(command)
    clock.nowMs += 5_000
    val replayed = scheduling.enable(command)
    val stored = drafts.find(AlarmId.parse(ALARM_ID))!!
    val occurrence = database.runtimeDao().findNextOccurrence(ALARM_ID)!!

    assertEquals(2, applied.revision)
    assertFalse(applied.replayed)
    assertEquals(applied.copy(replayed = true), replayed)
    assertTrue(stored.alarm.enabled)
    assertEquals(2, stored.alarm.revision)
    assertEquals(EXPECTED_NEXT_MS, occurrence.scheduledAtUtcMs)
    assertEquals("2026-08-31", occurrence.scheduledLocalDate)
    assertEquals(420, occurrence.scheduledLocalTimeMinutes)
    assertEquals("Asia/Makassar", occurrence.timezoneId)
    assertEquals(8 * 60 * 60, occurrence.utcOffsetSeconds)
    assertEquals("PENDING_OS", occurrence.state)
    assertEquals(
      "occ:v1:$ALARM_ID:2:$EXPECTED_NEXT_MS",
      occurrence.dedupeKey,
    )
    assertEquals(2, scalarLong("SELECT COUNT(*) FROM runtime_effect"))
    assertEquals(
      listOf("SCHEDULE_OCCURRENCE", "SYNC_DIRECT_BOOT_MIRROR"),
      stringList("SELECT effect_type FROM runtime_effect ORDER BY created_at_ms, id"),
    )
    assertEquals(2, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun staleRevisionRollsBackWithoutSchedulingState() {
    drafts.save(weeklyDraft())

    assertThrows(AlarmSchedulingRepositoryException.RevisionConflict::class.java) {
      scheduling.enable(enableCommand(ENABLE_COMMAND_ID).copy(expectedRevision = Revision.of(2)))
    }

    assertFalse(drafts.find(AlarmId.parse(ALARM_ID))!!.alarm.enabled)
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM alarm_occurrence"))
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM runtime_effect"))
    assertEquals(1, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun expiredOneTimeScheduleRollsBack() {
    drafts.save(
      weeklyDraft().copy(
        scheduleKind = "ONE_TIME",
        repeatDaysMask = 0,
        oneTimeAtUtcMs = NOW_MS - 1,
      ),
    )

    assertThrows(AlarmSchedulingRepositoryException.ScheduleExpired::class.java) {
      scheduling.enable(enableCommand(ENABLE_COMMAND_ID))
    }

    assertFalse(drafts.find(AlarmId.parse(ALARM_ID))!!.alarm.enabled)
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM alarm_occurrence"))
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun unregisteredQrAlarmIsRejectedBeforeMutation() {
    drafts.save(
      weeklyDraft().copy(
        missionType = MissionType.QR,
        target = 1,
        mathOperationsMask = null,
        mathGeneratorVersion = null,
      ),
    )

    assertThrows(AlarmSchedulingRepositoryException.QrNotRegistered::class.java) {
      scheduling.enable(enableCommand(ENABLE_COMMAND_ID))
    }

    assertFalse(drafts.find(AlarmId.parse(ALARM_ID))!!.alarm.enabled)
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM alarm_occurrence"))
    assertEquals(0, scalarLong("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun enabledEditCancelsOldOccurrenceAndCreatesReplacementAtomically() {
    drafts.save(weeklyDraft())
    scheduling.enable(enableCommand(ENABLE_COMMAND_ID))
    clock.nowMs += 1_000

    val ack = scheduling.editEnabled(
      weeklyDraft().copy(
        commandId = CommandId.parse(EDIT_COMMAND_ID),
        alarmId = AlarmId.parse(ALARM_ID),
        expectedRevision = Revision.of(2),
        label = "Updated",
        localTimeMinutes = 480,
        target = 5,
      ),
    )

    val stored = drafts.find(AlarmId.parse(ALARM_ID))!!
    assertEquals(3, ack.revision)
    assertTrue(stored.alarm.enabled)
    assertEquals("Updated", stored.alarm.label)
    assertEquals(5, stored.mission.target)
    assertEquals("CANCELLED", database.runtimeDao().findOccurrenceById(OCCURRENCE_ID)!!.state)
    assertEquals(
      REPLACEMENT_OCCURRENCE_ID,
      database.runtimeDao().findNextOccurrence(ALARM_ID)!!.id,
    )
    assertEquals(
      listOf(
        "SCHEDULE_OCCURRENCE",
        "SYNC_DIRECT_BOOT_MIRROR",
        "CANCEL_OCCURRENCE",
        "SCHEDULE_OCCURRENCE",
        "SYNC_DIRECT_BOOT_MIRROR",
      ),
      stringList("SELECT effect_type FROM runtime_effect ORDER BY created_at_ms, id"),
    )
  }

  @Test
  fun disableCancelsFutureOccurrenceAndReplayDoesNotDuplicateEffects() {
    drafts.save(weeklyDraft())
    scheduling.enable(enableCommand(ENABLE_COMMAND_ID))
    val command = enableCommand(DISABLE_COMMAND_ID).copy(expectedRevision = Revision.of(2))

    val applied = scheduling.disable(command)
    val replayed = scheduling.disable(command)

    assertEquals(applied.copy(replayed = true), replayed)
    assertEquals(3, applied.revision)
    assertFalse(drafts.find(AlarmId.parse(ALARM_ID))!!.alarm.enabled)
    assertEquals("CANCELLED", database.runtimeDao().findOccurrenceById(OCCURRENCE_ID)!!.state)
    assertEquals(4, scalarLong("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun deleteCancelsFutureOccurrenceAndDetachesItFromDeletedAlarm() {
    drafts.save(weeklyDraft())
    scheduling.enable(enableCommand(ENABLE_COMMAND_ID))

    val ack = scheduling.delete(
      enableCommand(DELETE_COMMAND_ID).copy(expectedRevision = Revision.of(2)),
    )

    assertEquals(3, ack.revision)
    assertEquals(null, drafts.find(AlarmId.parse(ALARM_ID)))
    val retainedOccurrence = database.runtimeDao().findOccurrenceById(OCCURRENCE_ID)!!
    assertEquals("CANCELLED", retainedOccurrence.state)
    assertEquals(null, retainedOccurrence.alarmId)
    assertEquals(3, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun deleteRejectsNonTerminalInstanceWithoutMutation() {
    drafts.save(weeklyDraft())
    scheduling.enable(enableCommand(ENABLE_COMMAND_ID))
    database.runtimeDao().getOrCreateTriggeredInstance(
      instance = instanceFixture(),
      mission = instanceMissionFixture(),
    )

    assertThrows(AlarmSchedulingRepositoryException.ActiveInstanceExists::class.java) {
      scheduling.delete(enableCommand(DELETE_COMMAND_ID).copy(expectedRevision = Revision.of(2)))
    }
    assertTrue(drafts.find(AlarmId.parse(ALARM_ID))!!.alarm.enabled)
    assertEquals(2, scalarLong("SELECT COUNT(*) FROM runtime_effect"))
  }

  @Test
  fun deletePreservesTerminalInstanceAndImmutableHistory() {
    drafts.save(weeklyDraft())
    scheduling.enable(enableCommand(ENABLE_COMMAND_ID))
    database.runtimeDao().getOrCreateTriggeredInstance(
      instance = instanceFixture(),
      mission = instanceMissionFixture(),
    )
    database.openHelper.writableDatabase.execSQL(
      """
      UPDATE instance_mission
      SET committed_progress = target, runtime_status = 'COMPLETED', updated_at_ms = ?
      WHERE instance_id = ?
      """.trimIndent(),
      arrayOf<Any>(NOW_MS + 1, INSTANCE_ID),
    )
    database.openHelper.writableDatabase.execSQL(
      """
      UPDATE alarm_instance
      SET revision = 2, runtime_state = 'TERMINAL', terminal_at_ms = ?,
        terminal_result = 'SUCCESS', dismiss_method = 'VERIFIED_MISSION', updated_at_ms = ?
      WHERE id = ?
      """.trimIndent(),
      arrayOf<Any>(NOW_MS + 2, NOW_MS + 2, INSTANCE_ID),
    )
    database.runtimeDao().insertHistory(
      AlarmHistoryEntity(
        instanceId = INSTANCE_ID,
        scheduledAtUtcMs = EXPECTED_NEXT_MS,
        actualTriggerAtMs = NOW_MS,
        endedAtMs = NOW_MS + 2,
        completionDurationMs = 2,
        missionType = "MATH",
        target = 3,
        finalProgress = 3,
        result = "SUCCESS",
        dismissMethod = "VERIFIED_MISSION",
        errorReasonCode = null,
        engineVersion = "math-v1",
        profileVersion = null,
        createdAtMs = NOW_MS + 2,
      ),
    )

    scheduling.delete(enableCommand(DELETE_COMMAND_ID).copy(expectedRevision = Revision.of(2)))

    assertEquals(1L, scalarLong("SELECT COUNT(*) FROM alarm_history"))
    assertEquals(
      1L,
      scalarLong("SELECT COUNT(*) FROM alarm_instance WHERE id = '$INSTANCE_ID' AND alarm_id IS NULL"),
    )
    assertEquals(
      1L,
      scalarLong("SELECT COUNT(*) FROM alarm_occurrence WHERE id = '$OCCURRENCE_ID' AND alarm_id IS NULL"),
    )
  }

  private fun instanceFixture() = AlarmInstanceEntity(
    id = INSTANCE_ID,
    occurrenceId = OCCURRENCE_ID,
    alarmId = ALARM_ID,
    revision = 1,
    runtimeState = "MISSION_LOCKED",
    queueOrder = 1,
    attentionSlot = 1,
    scheduledAtUtcMs = EXPECTED_NEXT_MS,
    actualTriggerAtMs = NOW_MS,
    triggerElapsedRealtimeMs = 1,
    bootSessionToken = "boot",
    terminalAtMs = null,
    terminalResult = null,
    dismissMethod = null,
    errorReasonCode = null,
    labelSnapshot = "Wake up",
    soundIdSnapshot = "classic",
    createdAtMs = NOW_MS,
    updatedAtMs = NOW_MS,
  )

  private fun instanceMissionFixture() = InstanceMissionEntity(
    instanceId = INSTANCE_ID,
    missionType = "MATH",
    snapshotVersion = 1,
    target = 3,
    committedProgress = 0,
    runtimeStatus = "READY",
    engineVersion = "math-v1",
    pushupProfileVersion = null,
    mathGeneratorVersion = "math-v1",
    qrReferenceDigest = null,
    qrDigestVersion = null,
    qrKeyAlias = null,
    updatedAtMs = NOW_MS,
  )

  private fun weeklyDraft() = SaveAlarmDraftCommand(
    commandId = CommandId.parse(SAVE_COMMAND_ID),
    alarmId = null,
    expectedRevision = null,
    label = "Wake up",
    scheduleKind = "WEEKLY",
    localTimeMinutes = 420,
    repeatDaysMask = 1,
    oneTimeAtUtcMs = null,
    configuredTimezoneId = "UTC",
    soundId = "classic",
    missionType = MissionType.MATH,
    target = 3,
    pushupProfileVersion = null,
    mathOperationsMask = 7,
    mathGeneratorVersion = "math-v1",
  )

  private fun enableCommand(commandId: String) = EnableAlarmCommand(
    commandId = CommandId.parse(commandId),
    alarmId = AlarmId.parse(ALARM_ID),
    expectedRevision = Revision.of(1),
  )

  private fun scalarLong(query: String): Long =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      cursor.moveToFirst()
      cursor.getLong(0)
    }

  private fun stringList(query: String): List<String> =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      buildList {
        while (cursor.moveToNext()) add(cursor.getString(0))
      }
    }

  private class MutableWallClock(var nowMs: Long) : WallClock {
    override fun nowEpochMillis(): Long = nowMs
  }

  private companion object {
    val NOW_MS = Instant.parse("2026-08-29T00:00:00Z").toEpochMilli()
    val EXPECTED_NEXT_MS = Instant.parse("2026-08-30T23:00:00Z").toEpochMilli()
    const val ALARM_ID = "5a7464b0-77b6-4f75-8459-974dc6d44160"
    const val OCCURRENCE_ID = "a6b9c6bb-bc38-4a8d-b8d6-1a34202e0bc4"
    const val SAVE_COMMAND_ID = "126baf63-80fb-4449-89ac-37667b33ff44"
    const val ENABLE_COMMAND_ID = "dc1457ab-ef8d-49c1-a67c-4cafc5063c22"
    const val EDIT_COMMAND_ID = "725d43c5-ac42-42df-bade-a752b3f532ff"
    const val DISABLE_COMMAND_ID = "89927654-58ae-47d4-aaf8-50122651f698"
    const val DELETE_COMMAND_ID = "957c85c3-1292-46ec-a714-35a7bded0781"
    const val REPLACEMENT_OCCURRENCE_ID = "cdd7bc61-d2f7-4ad4-894f-a29341766e31"
    const val INSTANCE_ID = "e3394818-f16a-4896-b364-85499f30c99d"
    const val EFFECT_ID_1 = "725d43c5-ac42-42df-bade-a752b3f532ff"
    const val EFFECT_ID_2 = "89927654-58ae-47d4-aaf8-50122651f698"
    const val EFFECT_ID_3 = "957c85c3-1292-46ec-a714-35a7bded0781"
    const val EFFECT_ID_4 = "cdd7bc61-d2f7-4ad4-894f-a29341766e31"
    const val EFFECT_ID_5 = "e3394818-f16a-4896-b364-85499f30c99d"
    const val EFFECT_ID_6 = "47fd07a5-b11f-42e1-a62f-64164b50557b"
  }
}
