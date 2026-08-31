package com.missionalarm.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class DirectBootDao {
  @Query("SELECT * FROM boot_schedule ORDER BY scheduled_at_utc_ms, occurrence_id")
  abstract fun findAllSchedules(): List<BootScheduleEntity>

  @Query("SELECT * FROM boot_schedule WHERE occurrence_id = :occurrenceId")
  abstract fun findSchedule(occurrenceId: String): BootScheduleEntity?

  @Query(
    "SELECT * FROM boot_schedule WHERE state = 'ACTIVE' ORDER BY scheduled_at_utc_ms, occurrence_id",
  )
  abstract fun findActiveSchedules(): List<BootScheduleEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract fun upsertSchedule(schedule: BootScheduleEntity)

  @Query("DELETE FROM boot_schedule WHERE occurrence_id = :occurrenceId")
  abstract fun deleteSchedule(occurrenceId: String): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract fun insertJournal(entry: BootJournalEntity): Long

  @Query(
    "SELECT * FROM boot_journal WHERE import_state = 'PENDING' ORDER BY occurred_at_ms, id",
  )
  abstract fun findPendingJournal(): List<BootJournalEntity>

  @Query(
    """
    UPDATE boot_journal SET import_state = 'IMPORTED', imported_at_ms = :importedAtMs
    WHERE id = :id AND import_state = 'PENDING'
    """,
  )
  abstract fun markJournalImported(id: String, importedAtMs: Long): Int

  @Query(
    """
    UPDATE boot_journal SET import_state = 'QUARANTINED', imported_at_ms = NULL,
      reason_code = :reasonCode
    WHERE id = :id AND import_state = 'PENDING'
    """,
  )
  abstract fun quarantineJournal(id: String, reasonCode: String): Int

  @Query(
    """
    UPDATE boot_schedule SET state = 'FIRED', updated_at_ms = :occurredAtMs
    WHERE occurrence_id = :occurrenceId AND state IN ('ACTIVE', 'FIRED')
    """,
  )
  protected abstract fun markScheduleFired(occurrenceId: String, occurredAtMs: Long): Int

  @Transaction
  open fun recordTriggered(occurrenceId: String, occurredAtMs: Long): Boolean {
    require(occurredAtMs >= 0)
    if (markScheduleFired(occurrenceId, occurredAtMs) != 1) return false
    val key = "triggered:$occurrenceId"
    insertJournal(
      BootJournalEntity(
        id = key,
        idempotencyKey = key,
        occurrenceId = occurrenceId,
        eventType = "TRIGGERED",
        occurredAtMs = occurredAtMs,
        soundStartedAtMs = null,
        importState = "PENDING",
        importedAtMs = null,
        reasonCode = null,
      ),
    )
    return true
  }

  @Transaction
  open fun recordEmergencyFallback(occurrenceId: String, occurredAtMs: Long) {
    require(occurredAtMs >= 0)
    val emergencyKey = "emergency-dismissed:$occurrenceId"
    insertJournal(
      BootJournalEntity(
        id = emergencyKey,
        idempotencyKey = emergencyKey,
        occurrenceId = occurrenceId,
        eventType = "EMERGENCY_DISMISSED",
        occurredAtMs = occurredAtMs,
        soundStartedAtMs = null,
        importState = "PENDING",
        importedAtMs = null,
        reasonCode = "CANONICAL_UNAVAILABLE",
      ),
    )
    val stoppedKey = "runtime-stopped:$occurrenceId"
    insertJournal(
      BootJournalEntity(
        id = stoppedKey,
        idempotencyKey = stoppedKey,
        occurrenceId = occurrenceId,
        eventType = "RUNTIME_STOPPED",
        occurredAtMs = occurredAtMs,
        soundStartedAtMs = null,
        importState = "PENDING",
        importedAtMs = null,
        reasonCode = "EMERGENCY_HOLD",
      ),
    )
  }
}
