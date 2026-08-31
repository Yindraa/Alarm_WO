package com.missionalarm.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
abstract class AlarmDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  abstract fun insertAlarm(alarm: AlarmEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  abstract fun insertMission(mission: AlarmMissionConfigEntity)

  @Update
  abstract fun updateAlarm(alarm: AlarmEntity): Int

  @Query(
    """
    UPDATE alarm_mission_config
    SET qr_reference_digest = :digest, qr_digest_version = :digestVersion,
      qr_key_alias = :keyAlias
    WHERE alarm_id = :alarmId AND mission_type = 'QR'
      AND qr_reference_digest IS NULL AND qr_digest_version IS NULL AND qr_key_alias IS NULL
    """,
  )
  abstract fun registerQrReference(
    alarmId: String,
    digest: ByteArray,
    digestVersion: String,
    keyAlias: String,
  ): Int

  @Query("DELETE FROM alarm_mission_config WHERE alarm_id = :alarmId")
  abstract fun deleteMission(alarmId: String): Int

  @Query("DELETE FROM alarm WHERE id = :alarmId")
  abstract fun deleteAlarm(alarmId: String): Int

  @Transaction
  open fun insertDraft(
    alarm: AlarmEntity,
    mission: AlarmMissionConfigEntity,
  ) {
    require(!alarm.enabled) { "new alarm draft must be disabled" }
    require(alarm.id == mission.alarmId) { "alarm and mission identity must match" }
    insertAlarm(alarm)
    insertMission(mission)
  }

  @Transaction
  @Query("SELECT * FROM alarm WHERE id = :alarmId")
  abstract fun findById(alarmId: String): AlarmWithMission?

  @Transaction
  @Query("SELECT * FROM alarm WHERE enabled = 1 ORDER BY id")
  abstract fun findAllEnabled(): List<AlarmWithMission>

  @Transaction
  @Query(
    """
    SELECT * FROM alarm
    ORDER BY enabled DESC, local_time_minutes, id
    LIMIT 500
    """,
  )
  abstract fun findHomeAlarms(): List<AlarmWithMission>

  @Query("SELECT * FROM alarm WHERE id = :alarmId")
  abstract fun findAlarmEntity(alarmId: String): AlarmEntity?

  @Query("SELECT COUNT(*) FROM alarm")
  abstract fun count(): Int
}
