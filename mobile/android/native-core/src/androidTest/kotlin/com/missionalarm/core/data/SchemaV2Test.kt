package com.missionalarm.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV2Test {
  @get:Rule
  val migrationHelper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MissionAlarmDatabase::class.java,
  )

  @After
  fun cleanUp() {
    ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun placeholderPushUpProfileMigratesInConfigurationAndActiveSnapshot() {
    migrationHelper.createDatabase(DATABASE_NAME, 2).use { db ->
      db.execSQL(
        """INSERT INTO alarm(
          id, revision, label, enabled, schedule_kind, local_time_minutes, repeat_days_mask,
          one_time_at_utc_ms, configured_timezone_id, sound_id, created_at_ms, updated_at_ms
        ) VALUES('alarm-pushup', 1, 'Push-up', 0, 'WEEKLY', 420, 127, NULL, 'UTC',
          'classic', 1, 1)""",
      )
      db.execSQL(
        """INSERT INTO alarm_mission_config(
          alarm_id, mission_type, config_version, target, pushup_profile_version,
          math_operations_mask, math_generator_version, qr_reference_digest,
          qr_digest_version, qr_key_alias
        ) VALUES('alarm-pushup', 'PUSH_UP', 1, 3, 'pushup-profile-v1', NULL, NULL,
          NULL, NULL, NULL)""",
      )
      db.execSQL("UPDATE alarm SET enabled = 1 WHERE id = 'alarm-pushup'")
      db.execSQL(
        """INSERT INTO alarm_occurrence(
          id, dedupe_key, alarm_id, alarm_revision, scheduled_at_utc_ms,
          scheduled_local_date, scheduled_local_time_minutes, timezone_id, utc_offset_seconds,
          state, last_error_code, created_at_ms, updated_at_ms
        ) VALUES('occ-pushup', 'occ:v1:alarm-pushup:1:1', 'alarm-pushup', 1, 20000,
          '2026-09-01', 420, 'UTC', 0, 'SCHEDULED_OS', NULL, 1, 1)""",
      )
      db.execSQL(
        """INSERT INTO alarm_instance(
          id, occurrence_id, alarm_id, revision, runtime_state, queue_order, attention_slot,
          scheduled_at_utc_ms, actual_trigger_at_ms, trigger_elapsed_realtime_ms,
          boot_session_token, terminal_at_ms, terminal_result, dismiss_method, error_reason_code,
          label_snapshot, sound_id_snapshot, created_at_ms, updated_at_ms
        ) VALUES('instance-pushup', 'occ-pushup', 'alarm-pushup', 3, 'MISSION_IN_PROGRESS',
          1, 1, 20000, 20010, 100, 'boot:1', NULL, NULL, NULL, NULL, 'Push-up', 'classic', 1, 1)""",
      )
      db.execSQL(
        """INSERT INTO instance_mission(
          instance_id, mission_type, snapshot_version, target, committed_progress,
          runtime_status, engine_version, pushup_profile_version, math_generator_version,
          qr_reference_digest, qr_digest_version, qr_key_alias, updated_at_ms
        ) VALUES('instance-pushup', 'PUSH_UP', 1, 3, 0, 'IN_PROGRESS', 'pushup-engine-v1',
          'pushup-profile-v1', NULL, NULL, NULL, NULL, 1)""",
      )
    }

    val database = MissionAlarmDatabaseFactory.persistent(
      ApplicationProvider.getApplicationContext(),
      DATABASE_NAME,
    )
    try {
      val alarm = checkNotNull(database.alarmDao().findById("alarm-pushup"))
      assertEquals(PROFILE_V0, alarm.mission.pushupProfileVersion)
      val mission = checkNotNull(database.runtimeDao().findMission("instance-pushup"))
      assertEquals(PROFILE_V0, mission.pushupProfileVersion)
      assertEquals("MISSION_IN_PROGRESS", database.runtimeDao().findInstanceById("instance-pushup")!!.runtimeState)
    } finally {
      database.close()
    }
  }

  private companion object {
    const val DATABASE_NAME = "schema-v2-test.db"
    const val PROFILE_V0 = "pushup-profile-v0"
  }
}
