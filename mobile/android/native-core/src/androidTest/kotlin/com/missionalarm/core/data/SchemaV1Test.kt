package com.missionalarm.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV1Test {
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
  fun versionOneRegisteredQrMigratesToReferenceFreeScanMission() {
    migrationHelper.createDatabase(DATABASE_NAME, 1).use { db ->
      db.execSQL(
        """INSERT INTO alarm VALUES(
          'alarm-1', 1, 'Scan alarm', 0, 'WEEKLY', 420, 127, NULL, 'UTC', 'classic', 1, 1
        )""",
      )
      db.execSQL(
        """INSERT INTO alarm_mission_config VALUES(
          'alarm-1', 'QR', 1, 1, NULL, NULL, NULL, x'000102', 'legacy-v1', 'legacy-key'
        )""",
      )
      db.execSQL("UPDATE alarm SET enabled = 1, revision = 2 WHERE id = 'alarm-1'")
    }

    val database = MissionAlarmDatabaseFactory.persistent(
      ApplicationProvider.getApplicationContext(),
      DATABASE_NAME,
    )
    try {
      val migrated = database.alarmDao().findById("alarm-1")!!
      assertTrue(migrated.alarm.enabled)
      assertEquals(2, migrated.alarm.revision)
      assertEquals("QR", migrated.mission.missionType)
      assertEquals(null, migrated.mission.qrReferenceDigest)
      assertEquals(null, migrated.mission.qrDigestVersion)
      assertEquals(null, migrated.mission.qrKeyAlias)
    } finally {
      database.close()
    }
  }

  private companion object {
    const val DATABASE_NAME = "schema-v1-test.db"
  }
}
