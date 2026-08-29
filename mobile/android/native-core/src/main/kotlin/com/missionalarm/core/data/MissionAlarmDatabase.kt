package com.missionalarm.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    AlarmEntity::class,
    AlarmMissionConfigEntity::class,
    AlarmOccurrenceEntity::class,
    AlarmInstanceEntity::class,
    InstanceMissionEntity::class,
    MathQuestionEntity::class,
    AlarmHistoryEntity::class,
    RuntimeEffectEntity::class,
    CommandReceiptEntity::class,
    DiagnosticEventEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class MissionAlarmDatabase : RoomDatabase() {
  abstract fun alarmDao(): AlarmDao
  abstract fun runtimeDao(): RuntimeDao
  abstract fun reliabilityDao(): ReliabilityDao
}

object MissionAlarmDatabaseFactory {
  private const val DATABASE_NAME = "mission-alarm.db"

  fun persistent(context: Context): MissionAlarmDatabase = configure(
    Room.databaseBuilder(context, MissionAlarmDatabase::class.java, DATABASE_NAME),
  ).build()

  internal fun persistent(context: Context, databaseName: String): MissionAlarmDatabase = configure(
    Room.databaseBuilder(context, MissionAlarmDatabase::class.java, databaseName),
  ).build()

  fun inMemory(context: Context): MissionAlarmDatabase = configure(
    Room.inMemoryDatabaseBuilder(context, MissionAlarmDatabase::class.java),
  ).allowMainThreadQueries().build()

  private fun configure(
    builder: RoomDatabase.Builder<MissionAlarmDatabase>,
  ): RoomDatabase.Builder<MissionAlarmDatabase> = builder
    .addCallback(SchemaInvariantCallback)

  private object SchemaInvariantCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
      super.onCreate(db)
      db.execSQL(ALARM_INSERT_TRIGGER)
      db.execSQL(ALARM_UPDATE_TRIGGER)
      db.execSQL(MISSION_INSERT_TRIGGER)
      db.execSQL(MISSION_UPDATE_TRIGGER)
      db.execSQL(OCCURRENCE_INSERT_TRIGGER)
      db.execSQL(OCCURRENCE_UPDATE_TRIGGER)
      db.execSQL(INSTANCE_INSERT_TRIGGER)
      db.execSQL(INSTANCE_UPDATE_TRIGGER)
      db.execSQL(INSTANCE_SUCCESS_TRIGGER)
      db.execSQL(INSTANCE_MISSION_INSERT_TRIGGER)
      db.execSQL(INSTANCE_MISSION_UPDATE_TRIGGER)
      db.execSQL(MATH_QUESTION_INSERT_TRIGGER)
      db.execSQL(MATH_QUESTION_UPDATE_TRIGGER)
      db.execSQL(HISTORY_INSERT_TRIGGER)
      db.execSQL(HISTORY_UPDATE_TRIGGER)
      db.execSQL(HISTORY_DELETE_TRIGGER)
      db.execSQL(EFFECT_INSERT_TRIGGER)
      db.execSQL(EFFECT_UPDATE_TRIGGER)
      db.execSQL(RECEIPT_INSERT_TRIGGER)
      db.execSQL(RECEIPT_UPDATE_TRIGGER)
      db.execSQL(DIAGNOSTIC_INSERT_TRIGGER)
      db.execSQL(DIAGNOSTIC_UPDATE_TRIGGER)
    }
  }

  private const val VALID_ALARM = """
    NEW.revision >= 1
    AND length(trim(NEW.label)) BETWEEN 1 AND 80
    AND NEW.local_time_minutes BETWEEN 0 AND 1439
    AND (
      (NEW.schedule_kind = 'ONE_TIME' AND NEW.one_time_at_utc_ms IS NOT NULL AND NEW.repeat_days_mask = 0)
      OR
      (NEW.schedule_kind = 'WEEKLY' AND NEW.one_time_at_utc_ms IS NULL AND NEW.repeat_days_mask BETWEEN 1 AND 127)
    )
    AND (
      NEW.enabled = 0 OR EXISTS (
        SELECT 1 FROM alarm_mission_config
        WHERE alarm_id = NEW.id
          AND (mission_type <> 'QR' OR (
            qr_reference_digest IS NOT NULL AND qr_digest_version IS NOT NULL AND qr_key_alias IS NOT NULL
          ))
      )
    )
  """

  private const val VALID_MISSION = """
    NEW.config_version >= 1
    AND (
      (NEW.mission_type = 'PUSH_UP' AND NEW.target BETWEEN 1 AND 50
        AND NEW.pushup_profile_version IS NOT NULL
        AND NEW.math_operations_mask IS NULL AND NEW.math_generator_version IS NULL
        AND NEW.qr_reference_digest IS NULL AND NEW.qr_digest_version IS NULL AND NEW.qr_key_alias IS NULL)
      OR (NEW.mission_type = 'MATH' AND NEW.target BETWEEN 1 AND 10
        AND NEW.pushup_profile_version IS NULL
        AND NEW.math_operations_mask BETWEEN 1 AND 7 AND NEW.math_generator_version IS NOT NULL
        AND NEW.qr_reference_digest IS NULL AND NEW.qr_digest_version IS NULL AND NEW.qr_key_alias IS NULL)
      OR (NEW.mission_type = 'QR' AND NEW.target = 1
        AND NEW.pushup_profile_version IS NULL
        AND NEW.math_operations_mask IS NULL AND NEW.math_generator_version IS NULL
        AND (
          (NEW.qr_reference_digest IS NOT NULL AND NEW.qr_digest_version IS NOT NULL AND NEW.qr_key_alias IS NOT NULL)
          OR (NEW.qr_reference_digest IS NULL AND NEW.qr_digest_version IS NULL AND NEW.qr_key_alias IS NULL
            AND EXISTS (SELECT 1 FROM alarm WHERE id = NEW.alarm_id AND enabled = 0))
        ))
    )
  """

  private const val VALID_OCCURRENCE = """
    NEW.alarm_revision >= 1
    AND NEW.scheduled_local_time_minutes BETWEEN 0 AND 1439
    AND length(NEW.scheduled_local_date) = 10
    AND length(trim(NEW.timezone_id)) > 0
    AND NEW.utc_offset_seconds BETWEEN -64800 AND 64800
    AND NEW.state IN ('PENDING_OS', 'SCHEDULED_OS', 'FIRED', 'CANCELLED', 'FAILED')
  """

  private const val VALID_INSTANCE = """
    NEW.revision >= 1
    AND NEW.queue_order >= 1
    AND (NEW.attention_slot IS NULL OR NEW.attention_slot = 1)
    AND NEW.runtime_state IN (
      'TRIGGERED', 'PENDING_ATTENTION', 'MISSION_LOCKED', 'MISSION_IN_PROGRESS',
      'RECOVERY_REQUIRED', 'TERMINAL'
    )
    AND (
      (NEW.runtime_state = 'TERMINAL'
        AND NEW.terminal_result IN ('SUCCESS', 'EMERGENCY_DISMISSED', 'FAILED', 'CANCELLED')
        AND NEW.terminal_at_ms IS NOT NULL)
      OR (NEW.runtime_state <> 'TERMINAL'
        AND NEW.terminal_result IS NULL AND NEW.terminal_at_ms IS NULL)
    )
    AND length(trim(NEW.label_snapshot)) BETWEEN 1 AND 80
    AND length(trim(NEW.sound_id_snapshot)) > 0
  """

  private const val VALID_INSTANCE_MISSION = """
    NEW.snapshot_version >= 1
    AND NEW.target >= 1
    AND NEW.committed_progress BETWEEN 0 AND NEW.target
    AND NEW.runtime_status IN ('READY', 'IN_PROGRESS', 'RECOVERY_REQUIRED', 'COMPLETED')
    AND ((NEW.runtime_status = 'COMPLETED' AND NEW.committed_progress = NEW.target)
      OR (NEW.runtime_status <> 'COMPLETED' AND NEW.committed_progress < NEW.target))
    AND length(trim(NEW.engine_version)) > 0
    AND (
      (NEW.mission_type = 'PUSH_UP' AND NEW.pushup_profile_version IS NOT NULL
        AND NEW.math_generator_version IS NULL AND NEW.qr_reference_digest IS NULL
        AND NEW.qr_digest_version IS NULL AND NEW.qr_key_alias IS NULL)
      OR (NEW.mission_type = 'MATH' AND NEW.pushup_profile_version IS NULL
        AND NEW.math_generator_version IS NOT NULL AND NEW.qr_reference_digest IS NULL
        AND NEW.qr_digest_version IS NULL AND NEW.qr_key_alias IS NULL)
      OR (NEW.mission_type = 'QR' AND NEW.target = 1 AND NEW.pushup_profile_version IS NULL
        AND NEW.math_generator_version IS NULL AND NEW.qr_reference_digest IS NOT NULL
        AND NEW.qr_digest_version IS NOT NULL AND NEW.qr_key_alias IS NOT NULL)
    )
  """

  private const val VALID_MATH_QUESTION = """
    NEW.ordinal >= 0
    AND NEW.operation IN ('ADD', 'SUBTRACT', 'MULTIPLY')
    AND ((NEW.answered = 1 AND NEW.answered_at_ms IS NOT NULL)
      OR (NEW.answered = 0 AND NEW.answered_at_ms IS NULL))
  """

  private const val VALID_HISTORY = """
    NEW.target >= 1
    AND NEW.final_progress BETWEEN 0 AND NEW.target
    AND (NEW.completion_duration_ms IS NULL OR NEW.completion_duration_ms >= 0)
    AND NEW.mission_type IN ('PUSH_UP', 'MATH', 'QR')
    AND NEW.result IN ('SUCCESS', 'EMERGENCY_DISMISSED', 'FAILED', 'CANCELLED')
    AND length(trim(NEW.dismiss_method)) > 0
    AND length(trim(NEW.engine_version)) > 0
  """

  private const val VALID_EFFECT = """
    NEW.aggregate_type IN ('ALARM', 'OCCURRENCE', 'INSTANCE', 'SYSTEM')
    AND NEW.payload_version >= 1
    AND NEW.attempt_count >= 0
    AND NEW.status IN (
      'PENDING', 'LEASED', 'ACKNOWLEDGED', 'RETRYABLE', 'BLOCKED_CAPABILITY', 'DEAD_LETTER'
    )
    AND ((NEW.status = 'LEASED' AND NEW.lease_owner IS NOT NULL AND NEW.lease_until_ms IS NOT NULL)
      OR (NEW.status <> 'LEASED' AND NEW.lease_owner IS NULL AND NEW.lease_until_ms IS NULL))
    AND ((NEW.status = 'ACKNOWLEDGED' AND NEW.acknowledged_at_ms IS NOT NULL)
      OR (NEW.status <> 'ACKNOWLEDGED' AND NEW.acknowledged_at_ms IS NULL))
  """

  private const val VALID_RECEIPT = """
    length(NEW.request_hash) = 64
    AND NEW.aggregate_type IN ('ALARM', 'INSTANCE')
    AND NEW.result_revision >= 1
    AND NEW.status IN ('APPLIED', 'NO_CHANGE')
    AND NEW.expires_at_ms > NEW.created_at_ms
  """

  private const val VALID_DIAGNOSTIC = """
    length(trim(NEW.event_code)) > 0
    AND NEW.expires_at_ms > NEW.occurred_at_ms
    AND (NEW.metadata_json IS NULL OR length(NEW.metadata_json) <= 4096)
  """

  private const val ALARM_INSERT_TRIGGER = """
    CREATE TRIGGER alarm_validate_insert
    BEFORE INSERT ON alarm
    WHEN NOT ($VALID_ALARM)
    BEGIN SELECT RAISE(ABORT, 'invalid alarm'); END
  """

  private const val ALARM_UPDATE_TRIGGER = """
    CREATE TRIGGER alarm_validate_update
    BEFORE UPDATE ON alarm
    WHEN NOT ($VALID_ALARM)
    BEGIN SELECT RAISE(ABORT, 'invalid alarm'); END
  """

  private const val MISSION_INSERT_TRIGGER = """
    CREATE TRIGGER mission_validate_insert
    BEFORE INSERT ON alarm_mission_config
    WHEN NOT ($VALID_MISSION)
    BEGIN SELECT RAISE(ABORT, 'invalid mission'); END
  """

  private const val MISSION_UPDATE_TRIGGER = """
    CREATE TRIGGER mission_validate_update
    BEFORE UPDATE ON alarm_mission_config
    WHEN NOT ($VALID_MISSION)
    BEGIN SELECT RAISE(ABORT, 'invalid mission'); END
  """

  private const val OCCURRENCE_INSERT_TRIGGER = """
    CREATE TRIGGER occurrence_validate_insert BEFORE INSERT ON alarm_occurrence
    WHEN NOT ($VALID_OCCURRENCE)
    BEGIN SELECT RAISE(ABORT, 'invalid occurrence'); END
  """

  private const val OCCURRENCE_UPDATE_TRIGGER = """
    CREATE TRIGGER occurrence_validate_update BEFORE UPDATE ON alarm_occurrence
    WHEN NOT ($VALID_OCCURRENCE)
    BEGIN SELECT RAISE(ABORT, 'invalid occurrence'); END
  """

  private const val INSTANCE_INSERT_TRIGGER = """
    CREATE TRIGGER instance_validate_insert BEFORE INSERT ON alarm_instance
    WHEN NOT ($VALID_INSTANCE)
    BEGIN SELECT RAISE(ABORT, 'invalid instance'); END
  """

  private const val INSTANCE_UPDATE_TRIGGER = """
    CREATE TRIGGER instance_validate_update BEFORE UPDATE ON alarm_instance
    WHEN NOT ($VALID_INSTANCE) OR NEW.revision <= OLD.revision
    BEGIN SELECT RAISE(ABORT, 'invalid instance update'); END
  """

  private const val INSTANCE_SUCCESS_TRIGGER = """
    CREATE TRIGGER instance_success_requires_completed_mission
    BEFORE UPDATE ON alarm_instance
    WHEN NEW.terminal_result = 'SUCCESS' AND NOT EXISTS (
      SELECT 1 FROM instance_mission
      WHERE instance_id = NEW.id AND runtime_status = 'COMPLETED' AND committed_progress = target
    )
    BEGIN SELECT RAISE(ABORT, 'success requires completed mission'); END
  """

  private const val INSTANCE_MISSION_INSERT_TRIGGER = """
    CREATE TRIGGER instance_mission_validate_insert BEFORE INSERT ON instance_mission
    WHEN NOT ($VALID_INSTANCE_MISSION)
    BEGIN SELECT RAISE(ABORT, 'invalid instance mission'); END
  """

  private const val INSTANCE_MISSION_UPDATE_TRIGGER = """
    CREATE TRIGGER instance_mission_validate_update BEFORE UPDATE ON instance_mission
    WHEN NOT ($VALID_INSTANCE_MISSION)
      OR NEW.committed_progress < OLD.committed_progress
      OR NEW.mission_type <> OLD.mission_type OR NEW.snapshot_version <> OLD.snapshot_version
      OR NEW.target <> OLD.target OR NEW.engine_version <> OLD.engine_version
    BEGIN SELECT RAISE(ABORT, 'invalid instance mission update'); END
  """

  private const val MATH_QUESTION_INSERT_TRIGGER = """
    CREATE TRIGGER math_question_validate_insert BEFORE INSERT ON math_question
    WHEN NOT ($VALID_MATH_QUESTION)
    BEGIN SELECT RAISE(ABORT, 'invalid math question'); END
  """

  private const val MATH_QUESTION_UPDATE_TRIGGER = """
    CREATE TRIGGER math_question_validate_update BEFORE UPDATE ON math_question
    WHEN NOT ($VALID_MATH_QUESTION) OR OLD.answered = 1
    BEGIN SELECT RAISE(ABORT, 'invalid math question update'); END
  """

  private const val HISTORY_INSERT_TRIGGER = """
    CREATE TRIGGER history_validate_insert BEFORE INSERT ON alarm_history
    WHEN NOT ($VALID_HISTORY)
    BEGIN SELECT RAISE(ABORT, 'invalid history'); END
  """

  private const val HISTORY_UPDATE_TRIGGER = """
    CREATE TRIGGER history_immutable_update BEFORE UPDATE ON alarm_history
    BEGIN SELECT RAISE(ABORT, 'history_immutable'); END
  """

  private const val HISTORY_DELETE_TRIGGER = """
    CREATE TRIGGER history_immutable_delete BEFORE DELETE ON alarm_history
    BEGIN SELECT RAISE(ABORT, 'history_immutable'); END
  """

  private const val EFFECT_INSERT_TRIGGER = """
    CREATE TRIGGER effect_validate_insert BEFORE INSERT ON runtime_effect
    WHEN NOT ($VALID_EFFECT)
    BEGIN SELECT RAISE(ABORT, 'invalid effect'); END
  """

  private const val EFFECT_UPDATE_TRIGGER = """
    CREATE TRIGGER effect_validate_update BEFORE UPDATE ON runtime_effect
    WHEN NOT ($VALID_EFFECT) OR (OLD.status = 'ACKNOWLEDGED' AND NEW.status <> 'ACKNOWLEDGED')
    BEGIN SELECT RAISE(ABORT, 'invalid effect update'); END
  """

  private const val RECEIPT_INSERT_TRIGGER = """
    CREATE TRIGGER receipt_validate_insert BEFORE INSERT ON command_receipt
    WHEN NOT ($VALID_RECEIPT)
    BEGIN SELECT RAISE(ABORT, 'invalid command receipt'); END
  """

  private const val RECEIPT_UPDATE_TRIGGER = """
    CREATE TRIGGER receipt_immutable_update BEFORE UPDATE ON command_receipt
    BEGIN SELECT RAISE(ABORT, 'command_receipt_immutable'); END
  """

  private const val DIAGNOSTIC_INSERT_TRIGGER = """
    CREATE TRIGGER diagnostic_validate_insert BEFORE INSERT ON diagnostic_event
    WHEN NOT ($VALID_DIAGNOSTIC)
    BEGIN SELECT RAISE(ABORT, 'invalid diagnostic event'); END
  """

  private const val DIAGNOSTIC_UPDATE_TRIGGER = """
    CREATE TRIGGER diagnostic_validate_update BEFORE UPDATE ON diagnostic_event
    WHEN NOT ($VALID_DIAGNOSTIC)
    BEGIN SELECT RAISE(ABORT, 'invalid diagnostic event'); END
  """
}
