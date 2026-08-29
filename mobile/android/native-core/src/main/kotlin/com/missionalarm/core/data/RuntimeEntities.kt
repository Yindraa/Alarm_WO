package com.missionalarm.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
  tableName = "alarm_occurrence",
  foreignKeys = [
    ForeignKey(
      entity = AlarmEntity::class,
      parentColumns = ["id"],
      childColumns = ["alarm_id"],
      onDelete = ForeignKey.SET_NULL,
    ),
  ],
  indices = [
    Index(value = ["dedupe_key"], unique = true),
    Index(value = ["state", "scheduled_at_utc_ms"], name = "idx_occurrence_due"),
    Index(value = ["alarm_id", "scheduled_at_utc_ms"], name = "idx_occurrence_alarm_time"),
  ],
)
data class AlarmOccurrenceEntity(
  @androidx.room.PrimaryKey val id: String,
  @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
  @ColumnInfo(name = "alarm_id") val alarmId: String?,
  @ColumnInfo(name = "alarm_revision") val alarmRevision: Int,
  @ColumnInfo(name = "scheduled_at_utc_ms") val scheduledAtUtcMs: Long,
  @ColumnInfo(name = "scheduled_local_date") val scheduledLocalDate: String,
  @ColumnInfo(name = "scheduled_local_time_minutes") val scheduledLocalTimeMinutes: Int,
  @ColumnInfo(name = "timezone_id") val timezoneId: String,
  @ColumnInfo(name = "utc_offset_seconds") val utcOffsetSeconds: Int,
  val state: String,
  @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
  @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
  tableName = "alarm_instance",
  foreignKeys = [
    ForeignKey(
      entity = AlarmOccurrenceEntity::class,
      parentColumns = ["id"],
      childColumns = ["occurrence_id"],
    ),
    ForeignKey(
      entity = AlarmEntity::class,
      parentColumns = ["id"],
      childColumns = ["alarm_id"],
      onDelete = ForeignKey.SET_NULL,
    ),
  ],
  indices = [
    Index(value = ["occurrence_id"], unique = true),
    Index(value = ["queue_order"], unique = true),
    Index(value = ["attention_slot"], unique = true),
    Index(
      value = ["runtime_state", "scheduled_at_utc_ms", "queue_order"],
      name = "idx_instance_active_fifo",
    ),
    Index(value = ["alarm_id", "created_at_ms"], name = "idx_instance_alarm"),
  ],
)
data class AlarmInstanceEntity(
  @androidx.room.PrimaryKey val id: String,
  @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
  @ColumnInfo(name = "alarm_id") val alarmId: String?,
  val revision: Int,
  @ColumnInfo(name = "runtime_state") val runtimeState: String,
  @ColumnInfo(name = "queue_order") val queueOrder: Long,
  @ColumnInfo(name = "attention_slot") val attentionSlot: Int?,
  @ColumnInfo(name = "scheduled_at_utc_ms") val scheduledAtUtcMs: Long,
  @ColumnInfo(name = "actual_trigger_at_ms") val actualTriggerAtMs: Long?,
  @ColumnInfo(name = "trigger_elapsed_realtime_ms") val triggerElapsedRealtimeMs: Long?,
  @ColumnInfo(name = "boot_session_token") val bootSessionToken: String?,
  @ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long?,
  @ColumnInfo(name = "terminal_result") val terminalResult: String?,
  @ColumnInfo(name = "dismiss_method") val dismissMethod: String?,
  @ColumnInfo(name = "error_reason_code") val errorReasonCode: String?,
  @ColumnInfo(name = "label_snapshot") val labelSnapshot: String,
  @ColumnInfo(name = "sound_id_snapshot") val soundIdSnapshot: String,
  @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
  tableName = "instance_mission",
  foreignKeys = [
    ForeignKey(
      entity = AlarmInstanceEntity::class,
      parentColumns = ["id"],
      childColumns = ["instance_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class InstanceMissionEntity(
  @androidx.room.PrimaryKey
  @ColumnInfo(name = "instance_id") val instanceId: String,
  @ColumnInfo(name = "mission_type") val missionType: String,
  @ColumnInfo(name = "snapshot_version") val snapshotVersion: Int,
  val target: Int,
  @ColumnInfo(name = "committed_progress") val committedProgress: Int,
  @ColumnInfo(name = "runtime_status") val runtimeStatus: String,
  @ColumnInfo(name = "engine_version") val engineVersion: String,
  @ColumnInfo(name = "pushup_profile_version") val pushupProfileVersion: String?,
  @ColumnInfo(name = "math_generator_version") val mathGeneratorVersion: String?,
  @ColumnInfo(name = "qr_reference_digest") val qrReferenceDigest: ByteArray?,
  @ColumnInfo(name = "qr_digest_version") val qrDigestVersion: String?,
  @ColumnInfo(name = "qr_key_alias") val qrKeyAlias: String?,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
  tableName = "math_question",
  primaryKeys = ["instance_id", "ordinal"],
  foreignKeys = [
    ForeignKey(
      entity = AlarmInstanceEntity::class,
      parentColumns = ["id"],
      childColumns = ["instance_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class MathQuestionEntity(
  @ColumnInfo(name = "instance_id") val instanceId: String,
  val ordinal: Int,
  val operation: String,
  @ColumnInfo(name = "operand_a") val operandA: Int,
  @ColumnInfo(name = "operand_b") val operandB: Int,
  @ColumnInfo(name = "correct_answer") val correctAnswer: Int,
  val answered: Boolean,
  @ColumnInfo(name = "answered_at_ms") val answeredAtMs: Long?,
)

@Entity(
  tableName = "alarm_history",
  foreignKeys = [
    ForeignKey(
      entity = AlarmInstanceEntity::class,
      parentColumns = ["id"],
      childColumns = ["instance_id"],
    ),
  ],
  indices = [
    Index(value = ["ended_at_ms", "instance_id"], name = "idx_history_recent"),
  ],
)
data class AlarmHistoryEntity(
  @androidx.room.PrimaryKey
  @ColumnInfo(name = "instance_id") val instanceId: String,
  @ColumnInfo(name = "scheduled_at_utc_ms") val scheduledAtUtcMs: Long,
  @ColumnInfo(name = "actual_trigger_at_ms") val actualTriggerAtMs: Long?,
  @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long,
  @ColumnInfo(name = "completion_duration_ms") val completionDurationMs: Long?,
  @ColumnInfo(name = "mission_type") val missionType: String,
  val target: Int,
  @ColumnInfo(name = "final_progress") val finalProgress: Int,
  val result: String,
  @ColumnInfo(name = "dismiss_method") val dismissMethod: String,
  @ColumnInfo(name = "error_reason_code") val errorReasonCode: String?,
  @ColumnInfo(name = "engine_version") val engineVersion: String,
  @ColumnInfo(name = "profile_version") val profileVersion: String?,
  @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
  tableName = "runtime_effect",
  indices = [
    Index(value = ["effect_key"], unique = true),
    Index(
      value = ["status", "next_attempt_at_ms", "lease_until_ms", "created_at_ms"],
      name = "idx_effect_claim",
    ),
    Index(value = ["aggregate_type", "aggregate_id"], name = "idx_effect_aggregate"),
  ],
)
data class RuntimeEffectEntity(
  @androidx.room.PrimaryKey val id: String,
  @ColumnInfo(name = "effect_key") val effectKey: String,
  @ColumnInfo(name = "aggregate_type") val aggregateType: String,
  @ColumnInfo(name = "aggregate_id") val aggregateId: String,
  @ColumnInfo(name = "effect_type") val effectType: String,
  @ColumnInfo(name = "payload_version") val payloadVersion: Int,
  @ColumnInfo(name = "payload_json") val payloadJson: String,
  val status: String,
  @ColumnInfo(name = "attempt_count") val attemptCount: Int,
  @ColumnInfo(name = "next_attempt_at_ms") val nextAttemptAtMs: Long?,
  @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
  @ColumnInfo(name = "lease_until_ms") val leaseUntilMs: Long?,
  @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
  @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
  @ColumnInfo(name = "acknowledged_at_ms") val acknowledgedAtMs: Long?,
)

@Entity(
  tableName = "command_receipt",
  indices = [
    Index(value = ["expires_at_ms"], name = "idx_command_expiry"),
    Index(
      value = ["aggregate_type", "aggregate_id", "created_at_ms"],
      name = "idx_command_aggregate",
    ),
  ],
)
data class CommandReceiptEntity(
  @androidx.room.PrimaryKey
  @ColumnInfo(name = "command_id") val commandId: String,
  @ColumnInfo(name = "command_type") val commandType: String,
  @ColumnInfo(name = "request_hash") val requestHash: String,
  @ColumnInfo(name = "aggregate_type") val aggregateType: String,
  @ColumnInfo(name = "aggregate_id") val aggregateId: String,
  @ColumnInfo(name = "result_revision") val resultRevision: Int,
  val status: String,
  @ColumnInfo(name = "outcome_code") val outcomeCode: String?,
  @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
  @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)

@Entity(
  tableName = "diagnostic_event",
  indices = [
    Index(value = ["expires_at_ms"], name = "idx_diag_expiry"),
    Index(value = ["instance_id", "occurred_at_ms"], name = "idx_diag_instance"),
  ],
)
data class DiagnosticEventEntity(
  @androidx.room.PrimaryKey val id: String,
  @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
  @ColumnInfo(name = "elapsed_realtime_ms") val elapsedRealtimeMs: Long?,
  @ColumnInfo(name = "event_code") val eventCode: String,
  @ColumnInfo(name = "reason_code") val reasonCode: String?,
  @ColumnInfo(name = "alarm_id") val alarmId: String?,
  @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
  @ColumnInfo(name = "instance_id") val instanceId: String?,
  @ColumnInfo(name = "mission_session_id") val missionSessionId: String?,
  @ColumnInfo(name = "metadata_json") val metadataJson: String?,
  @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)
