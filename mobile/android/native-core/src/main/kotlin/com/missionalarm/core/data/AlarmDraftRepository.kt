package com.missionalarm.core.data

import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.LocalTimeMinutes
import com.missionalarm.core.domain.MissionConfig
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import com.missionalarm.core.domain.WeekdayMask
import java.security.MessageDigest

data class SaveAlarmDraftCommand(
  val commandId: CommandId,
  val alarmId: AlarmId?,
  val expectedRevision: Revision?,
  val label: String,
  val scheduleKind: String,
  val localTimeMinutes: Int,
  val repeatDaysMask: Int,
  val oneTimeAtUtcMs: Long?,
  val configuredTimezoneId: String,
  val soundId: String,
  val missionType: MissionType,
  val target: Int,
  val pushupProfileVersion: String?,
  val mathOperationsMask: Int?,
  val mathGeneratorVersion: String?,
) {
  init {
    require(label.trim().length in 1..80) { "label must contain 1..80 trimmed characters" }
    LocalTimeMinutes.of(localTimeMinutes)
    require(configuredTimezoneId.isNotBlank()) { "configured timezone must not be blank" }
    require(soundId.isNotBlank()) { "sound ID must not be blank" }
    require(
      (scheduleKind == "ONE_TIME" && oneTimeAtUtcMs != null && repeatDaysMask == 0) ||
        (scheduleKind == "WEEKLY" && oneTimeAtUtcMs == null && repeatDaysMask in 1..127),
    ) { "invalid alarm schedule" }
    if (scheduleKind == "WEEKLY") WeekdayMask.of(repeatDaysMask)
    MissionConfig(missionType, version = 1, target = target)
    when (missionType) {
      MissionType.PUSH_UP -> require(
        pushupProfileVersion != null && mathOperationsMask == null && mathGeneratorVersion == null,
      ) { "invalid Push-up configuration" }
      MissionType.MATH -> require(
        pushupProfileVersion == null && mathOperationsMask in 1..7 && mathGeneratorVersion != null,
      ) { "invalid Math configuration" }
      MissionType.QR -> require(
        pushupProfileVersion == null && mathOperationsMask == null && mathGeneratorVersion == null,
      ) { "invalid QR draft configuration" }
    }
  }
}

data class DraftCommandAck(
  val commandId: String,
  val alarmId: String,
  val revision: Int,
  val appliedAtMs: Long,
  val replayed: Boolean,
)

sealed class AlarmDraftRepositoryException(message: String) : IllegalStateException(message) {
  class NotFound : AlarmDraftRepositoryException("alarm not found")
  class RevisionConflict : AlarmDraftRepositoryException("alarm revision conflict")
  class IdempotencyKeyReused : AlarmDraftRepositoryException("command ID reused with different request")
  class EnabledEditUnsupported : AlarmDraftRepositoryException("enabled alarm edit requires scheduling workflow")
}

fun interface AlarmIdGenerator {
  fun next(): AlarmId
}

class AlarmDraftRepository(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val alarmIdGenerator: AlarmIdGenerator,
) {
  fun save(command: SaveAlarmDraftCommand): DraftCommandAck = database.runInTransaction<DraftCommandAck> {
    val requestHash = DraftRequestHasher.hash(command)
    database.reliabilityDao().findReceipt(command.commandId.value)?.let { receipt ->
      if (receipt.commandType != COMMAND_TYPE || receipt.requestHash != requestHash) {
        throw AlarmDraftRepositoryException.IdempotencyKeyReused()
      }
      return@runInTransaction receipt.toAck(replayed = true)
    }

    val nowMs = wallClock.nowEpochMillis()
    require(nowMs >= 0) { "wall clock must not predate epoch" }
    val alarmId: AlarmId
    val revision: Revision
    val createdAtMs: Long
    val alarmDao = database.alarmDao()

    if (command.alarmId == null) {
      require(command.expectedRevision == null) { "new draft cannot have expected revision" }
      alarmId = alarmIdGenerator.next()
      revision = Revision.of(1)
      createdAtMs = nowMs
      alarmDao.insertDraft(
        command.toAlarm(alarmId, revision, enabled = false, createdAtMs, nowMs),
        command.toMission(alarmId),
      )
    } else {
      val current = alarmDao.findAlarmEntity(command.alarmId.value)
        ?: throw AlarmDraftRepositoryException.NotFound()
      val expected = command.expectedRevision ?: throw AlarmDraftRepositoryException.RevisionConflict()
      if (current.revision != expected.value) throw AlarmDraftRepositoryException.RevisionConflict()
      if (current.enabled) throw AlarmDraftRepositoryException.EnabledEditUnsupported()
      alarmId = command.alarmId
      revision = expected.next()
      createdAtMs = current.createdAtMs
      check(
        alarmDao.updateAlarm(command.toAlarm(alarmId, revision, false, createdAtMs, nowMs)) == 1,
      ) { "alarm update lost" }
      check(alarmDao.deleteMission(alarmId.value) == 1) { "alarm mission missing" }
      alarmDao.insertMission(command.toMission(alarmId))
    }

    val receipt = CommandReceiptEntity(
      commandId = command.commandId.value,
      commandType = COMMAND_TYPE,
      requestHash = requestHash,
      aggregateType = "ALARM",
      aggregateId = alarmId.value,
      resultRevision = revision.value,
      status = "APPLIED",
      outcomeCode = null,
      createdAtMs = nowMs,
      expiresAtMs = Math.addExact(nowMs, RECEIPT_RETENTION_MS),
    )
    check(database.reliabilityDao().insertReceipt(receipt) != -1L) { "command receipt race" }
    receipt.toAck(replayed = false)
  }

  fun find(alarmId: AlarmId): AlarmWithMission? = database.alarmDao().findById(alarmId.value)

  private fun SaveAlarmDraftCommand.toAlarm(
    id: AlarmId,
    revision: Revision,
    enabled: Boolean,
    createdAtMs: Long,
    updatedAtMs: Long,
  ) = AlarmEntity(
    id = id.value,
    revision = revision.value,
    label = label.trim(),
    enabled = enabled,
    scheduleKind = scheduleKind,
    localTimeMinutes = localTimeMinutes,
    repeatDaysMask = repeatDaysMask,
    oneTimeAtUtcMs = oneTimeAtUtcMs,
    configuredTimezoneId = configuredTimezoneId,
    soundId = soundId,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
  )

  private fun SaveAlarmDraftCommand.toMission(id: AlarmId) = AlarmMissionConfigEntity(
    alarmId = id.value,
    missionType = missionType.name,
    configVersion = 1,
    target = target,
    pushupProfileVersion = pushupProfileVersion,
    mathOperationsMask = mathOperationsMask,
    mathGeneratorVersion = mathGeneratorVersion,
    qrReferenceDigest = null,
    qrDigestVersion = null,
    qrKeyAlias = null,
  )

  private fun CommandReceiptEntity.toAck(replayed: Boolean) = DraftCommandAck(
    commandId = commandId,
    alarmId = aggregateId,
    revision = resultRevision,
    appliedAtMs = createdAtMs,
    replayed = replayed,
  )

  private companion object {
    const val COMMAND_TYPE = "SAVE_ALARM_CONFIGURATION"
    const val RECEIPT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
  }
}

private object DraftRequestHasher {
  fun hash(command: SaveAlarmDraftCommand): String {
    val canonicalFields = listOf(
      command.alarmId?.value,
      command.expectedRevision?.value?.toString(),
      command.label.trim(),
      command.scheduleKind,
      command.localTimeMinutes.toString(),
      command.repeatDaysMask.toString(),
      command.oneTimeAtUtcMs?.toString(),
      command.configuredTimezoneId,
      command.soundId,
      command.missionType.name,
      command.target.toString(),
      command.pushupProfileVersion,
      command.mathOperationsMask?.toString(),
      command.mathGeneratorVersion,
    ).joinToString(separator = "") { value ->
      if (value == null) "-1:" else "${value.toByteArray(Charsets.UTF_8).size}:$value"
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonicalFields.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
  }
}
