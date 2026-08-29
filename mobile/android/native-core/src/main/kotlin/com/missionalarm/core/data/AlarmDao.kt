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

  @Query("DELETE FROM alarm_mission_config WHERE alarm_id = :alarmId")
  abstract fun deleteMission(alarmId: String): Int

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

  @Query("SELECT * FROM alarm WHERE id = :alarmId")
  abstract fun findAlarmEntity(alarmId: String): AlarmEntity?

  @Query("SELECT COUNT(*) FROM alarm")
  abstract fun count(): Int
}
