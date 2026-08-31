package com.missionalarm.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrRegistrationRepositoryTest {
  private lateinit var database: MissionAlarmDatabase
  private lateinit var repository: QrRegistrationRepository

  @Before
  fun setUp() {
    database = MissionAlarmDatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    repository = QrRegistrationRepository(database, WallClock { 2_000 })
    AlarmDraftRepository(database, WallClock { 1_000 }) { AlarmId.parse(ALARM_ID) }
      .save(qrDraft())
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun registrationPersistsOnlyDigestMetadataAndReplaysAtomically() {
    val command = command(COMMAND_ID)
    val applied = repository.register(command)
    val replayed = repository.register(command)
    val stored = database.alarmDao().findById(ALARM_ID)!!

    assertEquals(2, applied.revision)
    assertFalse(applied.replayed)
    assertEquals(applied.copy(replayed = true), replayed)
    assertArrayEquals(DIGEST, stored.mission.qrReferenceDigest)
    assertEquals(QrDigestContract.VERSION, stored.mission.qrDigestVersion)
    assertEquals(KEY_ALIAS, stored.mission.qrKeyAlias)
    assertEquals(2, stored.alarm.revision)
    assertEquals(2, scalarLong("SELECT COUNT(*) FROM command_receipt"))
  }

  @Test
  fun staleRevisionAndCommandReuseDoNotReplaceReference() {
    repository.register(command(COMMAND_ID))

    assertThrows(QrRegistrationException.IdempotencyKeyReused::class.java) {
      repository.register(command(COMMAND_ID).copy(reference = reference(7)))
    }
    assertThrows(QrRegistrationException.RevisionConflict::class.java) {
      repository.register(command(SECOND_COMMAND_ID))
    }
    assertArrayEquals(DIGEST, database.alarmDao().findById(ALARM_ID)!!.mission.qrReferenceDigest)
  }

  private fun command(commandId: String) = RegisterQrReferenceCommand(
    CommandId.parse(commandId),
    AlarmId.parse(ALARM_ID),
    Revision.of(1),
    reference(3),
  )

  private fun reference(seed: Int) = QrReferenceMaterial(
    ByteArray(QrDigestContract.DIGEST_SIZE_BYTES) { (it + seed).toByte() },
    QrDigestContract.VERSION,
    KEY_ALIAS,
  )

  private fun qrDraft() = SaveAlarmDraftCommand(
    commandId = CommandId.parse(DRAFT_COMMAND_ID),
    alarmId = null,
    expectedRevision = null,
    label = "QR alarm",
    scheduleKind = "WEEKLY",
    localTimeMinutes = 420,
    repeatDaysMask = 31,
    oneTimeAtUtcMs = null,
    configuredTimezoneId = "Asia/Makassar",
    soundId = "classic",
    missionType = MissionType.QR,
    target = 1,
    pushupProfileVersion = null,
    mathOperationsMask = null,
    mathGeneratorVersion = null,
  )

  private fun scalarLong(query: String): Long =
    database.openHelper.readableDatabase.query(query).use { cursor ->
      cursor.moveToFirst()
      cursor.getLong(0)
    }

  private companion object {
    const val ALARM_ID = "0f74b958-b9de-4f93-a879-3463be450da5"
    const val DRAFT_COMMAND_ID = "7ecce2ae-51aa-452f-8d1d-af904990a2dd"
    const val COMMAND_ID = "7fd28261-f46a-4523-b03e-490748c23c79"
    const val SECOND_COMMAND_ID = "a18d746c-1018-49e3-a0b0-406e6288ff5c"
    const val KEY_ALIAS = "mission_alarm_qr_hmac_v1"
    val DIGEST = ByteArray(QrDigestContract.DIGEST_SIZE_BYTES) { (it + 3).toByte() }
  }
}
