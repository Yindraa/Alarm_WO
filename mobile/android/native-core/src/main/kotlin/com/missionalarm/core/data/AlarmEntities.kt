package com.missionalarm.core.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
  tableName = "alarm",
  indices = [Index(value = ["enabled"], name = "idx_alarm_enabled")],
)
data class AlarmEntity(
  @PrimaryKey
  val id: String,
  val revision: Int,
  val label: String,
  val enabled: Boolean,
  @ColumnInfo(name = "schedule_kind")
  val scheduleKind: String,
  @ColumnInfo(name = "local_time_minutes")
  val localTimeMinutes: Int,
  @ColumnInfo(name = "repeat_days_mask")
  val repeatDaysMask: Int,
  @ColumnInfo(name = "one_time_at_utc_ms")
  val oneTimeAtUtcMs: Long?,
  @ColumnInfo(name = "configured_timezone_id")
  val configuredTimezoneId: String,
  @ColumnInfo(name = "sound_id")
  val soundId: String,
  @ColumnInfo(name = "created_at_ms")
  val createdAtMs: Long,
  @ColumnInfo(name = "updated_at_ms")
  val updatedAtMs: Long,
)

@Entity(
  tableName = "alarm_mission_config",
  foreignKeys = [
    ForeignKey(
      entity = AlarmEntity::class,
      parentColumns = ["id"],
      childColumns = ["alarm_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class AlarmMissionConfigEntity(
  @PrimaryKey
  @ColumnInfo(name = "alarm_id")
  val alarmId: String,
  @ColumnInfo(name = "mission_type")
  val missionType: String,
  @ColumnInfo(name = "config_version")
  val configVersion: Int,
  val target: Int,
  @ColumnInfo(name = "pushup_profile_version")
  val pushupProfileVersion: String?,
  @ColumnInfo(name = "math_operations_mask")
  val mathOperationsMask: Int?,
  @ColumnInfo(name = "math_generator_version")
  val mathGeneratorVersion: String?,
  @ColumnInfo(name = "qr_reference_digest")
  val qrReferenceDigest: ByteArray?,
  @ColumnInfo(name = "qr_digest_version")
  val qrDigestVersion: String?,
  @ColumnInfo(name = "qr_key_alias")
  val qrKeyAlias: String?,
)

data class AlarmWithMission(
  @Embedded
  val alarm: AlarmEntity,
  @Relation(
    parentColumn = "id",
    entityColumn = "alarm_id",
  )
  val mission: AlarmMissionConfigEntity,
)
