package com.missionalarm.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "boot_schedule",
  indices = [
    Index(value = ["dedupe_key"], unique = true),
    Index(value = ["state", "scheduled_at_utc_ms"], name = "idx_boot_schedule_due"),
  ],
)
data class BootScheduleEntity(
  @PrimaryKey @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
  @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
  @ColumnInfo(name = "scheduled_at_utc_ms") val scheduledAtUtcMs: Long,
  @ColumnInfo(name = "sound_id") val soundId: String,
  @ColumnInfo(name = "mission_type") val missionType: String,
  val target: Int,
  @ColumnInfo(name = "alarm_revision") val alarmRevision: Int,
  @ColumnInfo(name = "mirror_revision") val mirrorRevision: Long,
  val state: String,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
  tableName = "boot_journal",
  indices = [
    Index(value = ["idempotency_key"], unique = true),
    Index(value = ["import_state", "occurred_at_ms"], name = "idx_boot_journal_import"),
  ],
)
data class BootJournalEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
  @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
  @ColumnInfo(name = "event_type") val eventType: String,
  @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
  @ColumnInfo(name = "sound_started_at_ms") val soundStartedAtMs: Long?,
  @ColumnInfo(name = "import_state") val importState: String,
  @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long?,
  @ColumnInfo(name = "reason_code") val reasonCode: String?,
)
