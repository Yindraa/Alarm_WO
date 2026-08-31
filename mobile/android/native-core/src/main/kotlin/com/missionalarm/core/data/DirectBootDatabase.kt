package com.missionalarm.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [BootScheduleEntity::class, BootJournalEntity::class],
  version = 1,
  exportSchema = true,
)
abstract class DirectBootDatabase : RoomDatabase() {
  abstract fun directBootDao(): DirectBootDao
}

object DirectBootDatabaseFactory {
  const val DATABASE_NAME = "mission_alarm_boot.db"

  fun persistent(context: Context): DirectBootDatabase {
    val deviceContext = context.applicationContext.createDeviceProtectedStorageContext()
    return configure(Room.databaseBuilder(deviceContext, DirectBootDatabase::class.java, DATABASE_NAME))
      .build()
  }

  fun inMemory(context: Context): DirectBootDatabase = configure(
    Room.inMemoryDatabaseBuilder(context, DirectBootDatabase::class.java),
  ).allowMainThreadQueries().build()

  private fun configure(builder: RoomDatabase.Builder<DirectBootDatabase>) = builder
    .addCallback(object : RoomDatabase.Callback() {
      override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """CREATE TRIGGER boot_schedule_validate_insert BEFORE INSERT ON boot_schedule
          WHEN NOT (${VALID_SCHEDULE}) BEGIN SELECT RAISE(ABORT, 'invalid boot schedule'); END""",
        )
        db.execSQL(
          """CREATE TRIGGER boot_schedule_validate_update BEFORE UPDATE ON boot_schedule
          WHEN NOT (${VALID_SCHEDULE}) BEGIN SELECT RAISE(ABORT, 'invalid boot schedule'); END""",
        )
        db.execSQL(
          """CREATE TRIGGER boot_journal_validate_insert BEFORE INSERT ON boot_journal
          WHEN NOT (${VALID_JOURNAL}) BEGIN SELECT RAISE(ABORT, 'invalid boot journal'); END""",
        )
        db.execSQL(
          """CREATE TRIGGER boot_journal_validate_update BEFORE UPDATE ON boot_journal
          WHEN NOT (${VALID_JOURNAL}) BEGIN SELECT RAISE(ABORT, 'invalid boot journal'); END""",
        )
      }
    })

  private const val VALID_SCHEDULE = """
    NEW.scheduled_at_utc_ms >= 0 AND length(trim(NEW.dedupe_key)) > 0
    AND length(trim(NEW.sound_id)) > 0
    AND NEW.mission_type IN ('PUSH_UP', 'MATH', 'QR') AND NEW.target >= 1
    AND NEW.alarm_revision >= 1 AND NEW.mirror_revision >= 1
    AND NEW.state IN ('ACTIVE', 'FIRED', 'CANCELLED') AND NEW.updated_at_ms >= 0
  """
  private const val VALID_JOURNAL = """
    length(trim(NEW.idempotency_key)) > 0
    AND NEW.event_type IN ('TRIGGERED', 'EMERGENCY_DISMISSED', 'RUNTIME_STOPPED')
    AND NEW.occurred_at_ms >= 0
    AND (NEW.sound_started_at_ms IS NULL OR NEW.sound_started_at_ms >= 0)
    AND NEW.import_state IN ('PENDING', 'IMPORTED', 'QUARANTINED')
    AND ((NEW.import_state = 'IMPORTED' AND NEW.imported_at_ms IS NOT NULL)
      OR (NEW.import_state <> 'IMPORTED' AND NEW.imported_at_ms IS NULL))
    AND (NEW.reason_code IS NULL OR length(NEW.reason_code) BETWEEN 1 AND 80)
  """
}
