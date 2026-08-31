package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectBootDatabaseTest {
  private lateinit var database: DirectBootDatabase

  @Before
  fun setUp() {
    database = DirectBootDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun rebuildKeepsOnlyAllowlistedActiveSchedulesAndAdvancesRevision() {
    val store = RoomDirectBootMirrorStore(database)
    store.rebuild(listOf(firstSnapshot()), 100)

    val first = database.directBootDao().findSchedule(OCCURRENCE_ID)!!
    assertEquals("occurrence:$OCCURRENCE_ID", first.dedupeKey)
    assertEquals("classic", first.soundId)
    assertEquals("MATH", first.missionType)
    assertEquals(1L, first.mirrorRevision)
    assertEquals("ACTIVE", first.state)

    store.rebuild(listOf(firstSnapshot().copy(target = 4)), 200)
    assertEquals(2L, database.directBootDao().findSchedule(OCCURRENCE_ID)!!.mirrorRevision)
    assertEquals(4, database.directBootDao().findSchedule(OCCURRENCE_ID)!!.target)

    store.rebuild(emptyList(), 300)
    assertTrue(database.directBootDao().findAllSchedules().isEmpty())
  }

  @Test
  fun triggerJournalIsIdempotentAndRejectsUnknownOccurrence() {
    RoomDirectBootMirrorStore(database).rebuild(listOf(firstSnapshot()), 100)

    assertTrue(database.directBootDao().recordTriggered(OCCURRENCE_ID, 500))
    assertTrue(database.directBootDao().recordTriggered(OCCURRENCE_ID, 600))
    assertFalse(database.directBootDao().recordTriggered(OTHER_OCCURRENCE_ID, 600))

    assertEquals("FIRED", database.directBootDao().findSchedule(OCCURRENCE_ID)!!.state)
    val pending = database.directBootDao().findPendingJournal()
    assertEquals(1, pending.size)
    assertEquals("TRIGGERED", pending.single().eventType)
    assertEquals(500L, pending.single().occurredAtMs)

    RoomDirectBootMirrorStore(database).rebuild(listOf(firstSnapshot()), 700)
    assertEquals("FIRED", database.directBootDao().findSchedule(OCCURRENCE_ID)!!.state)
  }

  @Test
  fun schemaContainsRequiredTablesAndIndexes() {
    val sqlite = database.openHelper.writableDatabase
    listOf("boot_schedule", "boot_journal").forEach { table ->
      sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use {
        assertTrue(it.moveToFirst())
      }
    }
    listOf("idx_boot_schedule_due", "idx_boot_journal_import").forEach { index ->
      sqlite.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$index'").use {
        assertTrue(it.moveToFirst())
      }
    }
  }

  @Test
  fun invalidJournalStateIsRejectedByDatabaseBoundary() {
    val error = runCatching {
      database.directBootDao().insertJournal(
        BootJournalEntity(
          id = "entry",
          idempotencyKey = "entry",
          occurrenceId = OCCURRENCE_ID,
          eventType = "TRIGGERED",
          occurredAtMs = 1,
          soundStartedAtMs = null,
          importState = "IMPORTED",
          importedAtMs = null,
          reasonCode = null,
        ),
      )
    }.exceptionOrNull()

    assertNotNull(error)
  }

  @Test
  fun emergencyFallbackJournalIsIdempotentAndNeverClaimsSuccess() {
    database.directBootDao().recordEmergencyFallback(OCCURRENCE_ID, 700)
    database.directBootDao().recordEmergencyFallback(OCCURRENCE_ID, 800)

    val pending = database.directBootDao().findPendingJournal()
    assertEquals(2, pending.size)
    assertEquals(
      setOf("EMERGENCY_DISMISSED", "RUNTIME_STOPPED"),
      pending.map { it.eventType }.toSet(),
    )
    assertTrue(pending.none { it.eventType == "TRIGGERED" })
    assertTrue(pending.none { it.reasonCode == "SUCCESS" })
    assertEquals(setOf(700L), pending.map { it.occurredAtMs }.toSet())
  }

  private fun firstSnapshot() = BootScheduleSnapshot(
    occurrenceId = OCCURRENCE_ID,
    scheduledAtUtcMs = 1_000,
    soundId = "classic",
    missionType = "MATH",
    target = 3,
    alarmRevision = 2,
  )

  private companion object {
    const val OCCURRENCE_ID = "768de903-4952-4c67-8547-70886bcf8a92"
    const val OTHER_OCCURRENCE_ID = "0d0a57a0-9c25-4eab-a69d-5ed6e19620a4"
  }
}
