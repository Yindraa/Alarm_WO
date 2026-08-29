package com.missionalarm.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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
  fun exportedVersionOneSchemaPassesProductionRoomValidation() {
    migrationHelper.createDatabase(DATABASE_NAME, 1).close()

    val database = MissionAlarmDatabaseFactory.persistent(
      ApplicationProvider.getApplicationContext(),
      DATABASE_NAME,
    )
    try {
      database.openHelper.writableDatabase
    } finally {
      database.close()
    }
  }

  private companion object {
    const val DATABASE_NAME = "schema-v1-test.db"
  }
}
