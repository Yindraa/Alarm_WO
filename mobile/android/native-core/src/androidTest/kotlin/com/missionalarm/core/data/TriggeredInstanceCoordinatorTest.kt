package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.WallClock
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TriggeredInstanceCoordinatorTest {
  private lateinit var canonical: MissionAlarmDatabase
  private lateinit var boot: DirectBootDatabase
  private lateinit var coordinator: TriggeredInstanceCoordinator
  private lateinit var effectIdGenerator: EffectIdGenerator
  private val instanceIds = ArrayDeque<String>()
  private val occurrenceIds = ArrayDeque<String>()
  private var effectSequence = 0

  @Before
  fun setUp() {
    canonical = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    boot = DirectBootDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    instanceIds += listOf(INSTANCE_ID_1, INSTANCE_ID_2)
    occurrenceIds += listOf(NEXT_OCCURRENCE_ID_1, NEXT_OCCURRENCE_ID_2)
    effectIdGenerator = EffectIdGenerator { "effect-${++effectSequence}" }
    coordinator = TriggeredInstanceCoordinator(
      database = canonical,
      wallClock = WallClock { NOW_MS },
      currentZoneProvider = CurrentZoneProvider { ZoneId.of("UTC") },
      instanceIdGenerator = InstanceIdGenerator { instanceIds.removeFirst() },
      occurrenceIdGenerator = OccurrenceIdGenerator {
        OccurrenceId.parse(occurrenceIds.removeFirst())
      },
      effectIdGenerator = effectIdGenerator,
    )
  }

  @After
  fun tearDown() {
    boot.close()
    canonical.close()
  }

  @Test
  fun firstWeeklyTriggerCreatesSnapshotQuestionsRuntimeEffectsAndNextOccurrence() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "Private wake label")

    val result = coordinator.getOrCreate(
      OccurrenceId.parse(OCCURRENCE_ID_1),
      TriggerTiming(NOW_MS - 10, 55, "boot:7"),
    )

    assertTrue(result.created)
    assertEquals("TRIGGERED", result.instance.runtimeState)
    assertEquals(1, result.instance.attentionSlot)
    assertEquals("Private wake label", result.instance.labelSnapshot)
    assertEquals(55L, result.instance.triggerElapsedRealtimeMs)
    assertEquals("boot:7", result.instance.bootSessionToken)
    assertEquals("FIRED", canonical.runtimeDao().findOccurrenceById(OCCURRENCE_ID_1)!!.state)
    assertEquals(3L, scalar("SELECT COUNT(*) FROM math_question WHERE instance_id='$INSTANCE_ID_1'"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='START_ALARM_RUNTIME'"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='PRESENT_ACTIVE_INSTANCE'"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='SCHEDULE_OCCURRENCE'"))
    assertEquals(1L, canonical.runtimeDao().findSchedulableOccurrences(ALARM_ID_1).size.toLong())
  }

  @Test
  fun duplicateTriggerReturnsSameInstanceWithoutRegeneratingStateOrEffects() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "Alarm")
    val first = coordinator.getOrCreate(
      OccurrenceId.parse(OCCURRENCE_ID_1),
      TriggerTiming(NOW_MS, 1, "boot:1"),
    )
    val effectCount = scalar("SELECT COUNT(*) FROM runtime_effect")

    val duplicate = coordinator.getOrCreate(
      OccurrenceId.parse(OCCURRENCE_ID_1),
      TriggerTiming(NOW_MS + 100, 101, "boot:1"),
    )

    assertFalse(duplicate.created)
    assertEquals(first.instance.id, duplicate.instance.id)
    assertEquals(effectCount, scalar("SELECT COUNT(*) FROM runtime_effect"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_instance"))
    assertEquals(3L, scalar("SELECT COUNT(*) FROM math_question"))
  }

  @Test
  fun overlappingTriggerGetsMonotonicFifoOrderWithoutSecondAttentionSlot() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "First")
    seedAlarm(ALARM_ID_2, OCCURRENCE_ID_2, "Second")
    val first = coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_1), TriggerTiming(NOW_MS))
    val second = coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_2), TriggerTiming(NOW_MS + 1))

    assertEquals(1L, first.instance.queueOrder)
    assertEquals(2L, second.instance.queueOrder)
    assertEquals("PENDING_ATTENTION", second.instance.runtimeState)
    assertNull(second.instance.attentionSlot)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_instance WHERE attention_slot=1"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='START_ALARM_RUNTIME'"))
  }

  @Test
  fun activeRuntimeSnapshotComesFromAttendedInstanceAndExcludesAnswerKey() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "First alarm")
    seedAlarm(ALARM_ID_2, OCCURRENCE_ID_2, "Queued alarm")
    coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_1), TriggerTiming(NOW_MS))
    coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_2), TriggerTiming(NOW_MS + 1))

    val snapshot = checkNotNull(canonical.runtimeDao().loadActiveRuntimeSnapshot())

    assertEquals(INSTANCE_ID_1, snapshot.instanceId)
    assertEquals("First alarm", snapshot.label)
    assertEquals("MATH", snapshot.missionType)
    assertEquals(0, snapshot.committedProgress)
    assertEquals(3, snapshot.target)
    assertEquals(1, snapshot.queuedCount)
    assertEquals(0, snapshot.mathQuestion?.ordinal)
    assertTrue(snapshot.mathQuestion!!.operation in setOf("ADD", "SUBTRACT", "MULTIPLY"))
  }

  @Test
  fun journalImportRecoversCrashWindowAndMarksEntryImportedOnce() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "Alarm")
    RoomDirectBootMirrorStore(boot).rebuild(
      listOf(BootScheduleSnapshot(OCCURRENCE_ID_1, NOW_MS, "classic", "MATH", 3, 1)),
      NOW_MS,
    )
    assertTrue(boot.directBootDao().recordTriggered(OCCURRENCE_ID_1, NOW_MS))

    // Simulate canonical commit followed by process death before the journal state update.
    coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_1), TriggerTiming(NOW_MS))
    val effectsBeforeRetry = scalar("SELECT COUNT(*) FROM runtime_effect")
    val importer = importer { NOW_MS + 1 }

    assertEquals(JournalImportResult(1, 0, 0), importer.importPending())
    assertEquals(JournalImportResult(0, 0, 0), importer.importPending())
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_instance"))
    assertEquals(effectsBeforeRetry, scalar("SELECT COUNT(*) FROM runtime_effect"))
    assertTrue(boot.directBootDao().findPendingJournal().isEmpty())
  }

  @Test
  fun invalidJournalReferenceIsQuarantinedWithoutCreatingInstance() {
    boot.directBootDao().insertJournal(
      BootJournalEntity(
        id = "unknown-trigger",
        idempotencyKey = "unknown-trigger",
        occurrenceId = UNKNOWN_OCCURRENCE_ID,
        eventType = "TRIGGERED",
        occurredAtMs = NOW_MS,
        soundStartedAtMs = null,
        importState = "PENDING",
        importedAtMs = null,
        reasonCode = null,
      ),
    )

    val result = importer().importPending()

    assertEquals(JournalImportResult(0, 1, 0), result)
    assertEquals(0L, scalar("SELECT COUNT(*) FROM alarm_instance"))
    val cursor = boot.openHelper.writableDatabase.query(
      "SELECT import_state, reason_code FROM boot_journal WHERE id='unknown-trigger'",
    )
    cursor.use {
      assertTrue(it.moveToFirst())
      assertEquals("QUARANTINED", it.getString(0))
      assertEquals("OCCURRENCE_NOT_FOUND", it.getString(1))
    }
  }

  @Test
  fun oneTimeTriggerDisablesAlarmWithoutCreatingAnotherOccurrence() {
    seedAlarm(
      ALARM_ID_1,
      OCCURRENCE_ID_1,
      "One time",
      scheduleKind = "ONE_TIME",
    )

    coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_1), TriggerTiming(NOW_MS))

    val alarm = canonical.alarmDao().findAlarmEntity(ALARM_ID_1)!!
    assertFalse(alarm.enabled)
    assertEquals(2, alarm.revision)
    assertTrue(canonical.runtimeDao().findSchedulableOccurrences(ALARM_ID_1).isEmpty())
    assertEquals(0L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='SCHEDULE_OCCURRENCE'"))
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='SYNC_DIRECT_BOOT_MIRROR'"))
  }

  @Test
  fun runtimeStoppedWithoutTerminalInstanceStaysPending() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "Alarm")
    coordinator.getOrCreate(OccurrenceId.parse(OCCURRENCE_ID_1), TriggerTiming(NOW_MS))
    boot.directBootDao().insertJournal(
      BootJournalEntity(
        id = "runtime-stopped:$OCCURRENCE_ID_1",
        idempotencyKey = "runtime-stopped:$OCCURRENCE_ID_1",
        occurrenceId = OCCURRENCE_ID_1,
        eventType = "RUNTIME_STOPPED",
        occurredAtMs = NOW_MS,
        soundStartedAtMs = null,
        importState = "PENDING",
        importedAtMs = null,
        reasonCode = "PROCESS_RECOVERY",
      ),
    )

    assertEquals(
      JournalImportResult(0, 0, 1),
      importer().importPending(),
    )
    assertEquals(1, boot.directBootDao().findPendingJournal().size)
  }

  @Test
  fun directBootTriggerAndEmergencyFallbackImportToCanonicalTerminalHistory() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "Alarm")
    RoomDirectBootMirrorStore(boot).rebuild(
      listOf(BootScheduleSnapshot(OCCURRENCE_ID_1, NOW_MS, "classic", "MATH", 3, 1)),
      NOW_MS,
    )
    assertTrue(boot.directBootDao().recordTriggered(OCCURRENCE_ID_1, NOW_MS))
    boot.directBootDao().recordEmergencyFallback(OCCURRENCE_ID_1, NOW_MS)

    assertEquals(JournalImportResult(3, 0, 0), importer().importPending())

    val instance = checkNotNull(canonical.runtimeDao().findInstanceByOccurrence(OCCURRENCE_ID_1))
    assertEquals("TERMINAL", instance.runtimeState)
    assertEquals("EMERGENCY_DISMISSED", instance.terminalResult)
    assertEquals(NOW_MS, instance.terminalAtMs)
    val history = checkNotNull(canonical.runtimeDao().findHistoryByInstanceId(instance.id))
    assertEquals("EMERGENCY_DISMISSED", history.result)
    assertEquals("EMERGENCY_HOLD", history.dismissMethod)
    assertEquals(NOW_MS, history.endedAtMs)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM runtime_effect WHERE effect_type='STOP_ALARM_RUNTIME'"))
    assertTrue(boot.directBootDao().findPendingJournal().isEmpty())
  }

  @Test
  fun emergencyJournalCrashWindowReplaysWithoutDuplicateHistoryOrEffects() {
    seedAlarm(ALARM_ID_1, OCCURRENCE_ID_1, "Alarm")
    val instance = coordinator.getOrCreate(
      OccurrenceId.parse(OCCURRENCE_ID_1),
      TriggerTiming(NOW_MS),
    ).instance
    EmergencyDismissCoordinator(
      canonical,
      WallClock { NOW_MS + 10 },
      effectIdGenerator,
    ).dismiss(instance.id)
    boot.directBootDao().recordEmergencyFallback(OCCURRENCE_ID_1, NOW_MS + 10)
    val effectsBeforeRetry = scalar("SELECT COUNT(*) FROM runtime_effect")

    assertEquals(JournalImportResult(2, 0, 0), importer().importPending())
    assertEquals(JournalImportResult(0, 0, 0), importer().importPending())
    assertEquals(1L, scalar("SELECT COUNT(*) FROM alarm_history WHERE instance_id='$INSTANCE_ID_1'"))
    assertEquals(effectsBeforeRetry, scalar("SELECT COUNT(*) FROM runtime_effect"))
  }

  private fun importer(now: () -> Long = { NOW_MS }) = DirectBootJournalImporter(
    bootDatabase = boot,
    coordinator = coordinator,
    canonicalDatabase = canonical,
    effectIdGenerator = effectIdGenerator,
    importedAtMs = now,
  )

  private fun seedAlarm(
    alarmId: String,
    occurrenceId: String,
    label: String,
    scheduleKind: String = "WEEKLY",
  ) {
    val alarm = AlarmEntity(
      id = alarmId,
      revision = 1,
      label = label,
      enabled = false,
      scheduleKind = scheduleKind,
      localTimeMinutes = 420,
      repeatDaysMask = if (scheduleKind == "WEEKLY") 127 else 0,
      oneTimeAtUtcMs = if (scheduleKind == "ONE_TIME") NOW_MS - 100 else null,
      configuredTimezoneId = "UTC",
      soundId = "classic",
      createdAtMs = NOW_MS - 1_000,
      updatedAtMs = NOW_MS - 1_000,
    )
    canonical.runInTransaction {
      canonical.alarmDao().insertAlarm(alarm)
      canonical.alarmDao().insertMission(
        AlarmMissionConfigEntity(
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
        ),
      )
      canonical.alarmDao().updateAlarm(alarm.copy(enabled = true))
      canonical.runtimeDao().insertOccurrence(
        AlarmOccurrenceEntity(
          id = occurrenceId,
          dedupeKey = "occ:v1:$alarmId:1:${NOW_MS - 100}",
          alarmId = alarmId,
          alarmRevision = 1,
          scheduledAtUtcMs = NOW_MS - 100,
          scheduledLocalDate = "2026-08-30",
          scheduledLocalTimeMinutes = 420,
          timezoneId = "UTC",
          utcOffsetSeconds = 0,
          state = "SCHEDULED_OS",
          lastErrorCode = null,
          createdAtMs = NOW_MS - 1_000,
          updatedAtMs = NOW_MS - 1_000,
        ),
      )
    }
  }

  private fun scalar(sql: String): Long = canonical.openHelper.writableDatabase.query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
  }

  private companion object {
    const val NOW_MS = 1_777_507_200_000L
    const val ALARM_ID_1 = "0ae96088-6834-4538-8c4f-491d28546631"
    const val ALARM_ID_2 = "eb843e65-c084-45d3-94a7-d89694979d0a"
    const val OCCURRENCE_ID_1 = "b8ce9d56-1214-4cac-853d-ab9a5fbe0f74"
    const val OCCURRENCE_ID_2 = "a8ad0811-0fe5-4452-a18a-6101e0988e34"
    const val NEXT_OCCURRENCE_ID_1 = "556db096-60fd-48f8-9bf7-a54e62fcb178"
    const val NEXT_OCCURRENCE_ID_2 = "9ab813a0-1c48-4c69-8b54-335ab1b41eb4"
    const val INSTANCE_ID_1 = "b8ffb468-3e04-47c6-846f-898e3b61ae88"
    const val INSTANCE_ID_2 = "26555640-d04f-400a-af83-c8d904838233"
    const val UNKNOWN_OCCURRENCE_ID = "7ee9c14a-80e4-4074-a168-cfeb7593f70f"
  }
}
