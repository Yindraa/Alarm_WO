package com.missionalarm.core.data

import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.AlarmSchedule
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.LocalTimeMinutes
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.OccurrenceIdentity
import com.missionalarm.core.domain.OccurrenceTime
import com.missionalarm.core.domain.RecurrencePolicy
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import com.missionalarm.core.domain.WeekdayMask
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

data class EnableAlarmCommand(
  val commandId: CommandId,
  val alarmId: AlarmId,
  val expectedRevision: Revision,
)

sealed class AlarmSchedulingRepositoryException(message: String) : IllegalStateException(message) {
  class NotFound : AlarmSchedulingRepositoryException("alarm not found")
  class RevisionConflict : AlarmSchedulingRepositoryException("alarm revision conflict")
  class IdempotencyKeyReused :
    AlarmSchedulingRepositoryException("command ID reused with different request")
  class AlreadyEnabled : AlarmSchedulingRepositoryException("alarm is already enabled")
  class AlreadyDisabled : AlarmSchedulingRepositoryException("alarm is already disabled")
  class ActiveInstanceExists :
    AlarmSchedulingRepositoryException("alarm has a non-terminal instance")
  class ScheduleExpired : AlarmSchedulingRepositoryException("one-time schedule has expired")
  class PendingOccurrenceExists :
    AlarmSchedulingRepositoryException("disabled alarm still has a pending occurrence")
}

fun interface OccurrenceIdGenerator {
  fun next(): OccurrenceId
}

fun interface EffectIdGenerator {
  fun next(): String
}

fun interface CurrentZoneProvider {
  fun current(): ZoneId
}

/**
 * Persists the complete desired scheduling state before any Android side effect is attempted.
 * The effect runner is the only component allowed to execute the resulting outbox effects.
 */
class AlarmSchedulingRepository(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val currentZoneProvider: CurrentZoneProvider,
  private val occurrenceIdGenerator: OccurrenceIdGenerator,
  private val effectIdGenerator: EffectIdGenerator,
  private val recurrencePolicy: RecurrencePolicy = RecurrencePolicy(),
) {
  fun enable(command: EnableAlarmCommand): DraftCommandAck =
    database.runInTransaction<DraftCommandAck> {
      val requestHash = EnableRequestHasher.hash(command)
      val reliabilityDao = database.reliabilityDao()
      reliabilityDao.findReceipt(command.commandId.value)?.let { receipt ->
        if (receipt.commandType != COMMAND_TYPE || receipt.requestHash != requestHash) {
          throw AlarmSchedulingRepositoryException.IdempotencyKeyReused()
        }
        return@runInTransaction receipt.toAck(replayed = true)
      }

      val alarmDao = database.alarmDao()
      val stored = alarmDao.findById(command.alarmId.value)
        ?: throw AlarmSchedulingRepositoryException.NotFound()
      if (stored.alarm.revision != command.expectedRevision.value) {
        throw AlarmSchedulingRepositoryException.RevisionConflict()
      }
      if (stored.alarm.enabled) throw AlarmSchedulingRepositoryException.AlreadyEnabled()
      if (database.runtimeDao().findNextOccurrence(command.alarmId.value) != null) {
        throw AlarmSchedulingRepositoryException.PendingOccurrenceExists()
      }

      val nowMs = checkedNowMs()
      val next = nextOccurrence(stored.alarm.toSchedule(), nowMs)
      val revision = command.expectedRevision.next()

      check(
        alarmDao.updateAlarm(
          stored.alarm.copy(
            revision = revision.value,
            enabled = true,
            updatedAtMs = nowMs,
          ),
        ) == 1,
      ) { "alarm enable update lost" }

      val occurrence = insertScheduledOccurrence(command.alarmId, revision, next, nowMs)
      insertScheduleEffect(occurrence, nowMs)
      insertMirrorEffect(command.alarmId, revision, occurrence.id, nowMs)
      insertReceipt(
        commandId = command.commandId,
        commandType = COMMAND_TYPE,
        requestHash = requestHash,
        alarmId = command.alarmId,
        revision = revision,
        nowMs = nowMs,
      ).toAck(replayed = false)
    }

  fun editEnabled(command: SaveAlarmDraftCommand): DraftCommandAck =
    database.runInTransaction<DraftCommandAck> {
      val alarmId = requireNotNull(command.alarmId) { "enabled edit requires alarm ID" }
      val expectedRevision = requireNotNull(command.expectedRevision) {
        "enabled edit requires expected revision"
      }
      val requestHash = DraftRequestHasher.hash(command)
      val reliabilityDao = database.reliabilityDao()
      reliabilityDao.findReceipt(command.commandId.value)?.let { receipt ->
        if (receipt.commandType != EDIT_COMMAND_TYPE || receipt.requestHash != requestHash) {
          throw AlarmSchedulingRepositoryException.IdempotencyKeyReused()
        }
        return@runInTransaction receipt.toAck(replayed = true)
      }

      val alarmDao = database.alarmDao()
      val stored = alarmDao.findById(alarmId.value)
        ?: throw AlarmSchedulingRepositoryException.NotFound()
      if (stored.alarm.revision != expectedRevision.value) {
        throw AlarmSchedulingRepositoryException.RevisionConflict()
      }
      if (!stored.alarm.enabled) throw AlarmSchedulingRepositoryException.AlreadyDisabled()

      val replacementMission = command.toEnabledMission(alarmId)
      val nowMs = checkedNowMs()
      val revision = expectedRevision.next()
      val next = nextOccurrence(command.toSchedule(), nowMs)
      val superseded = cancelSchedulableOccurrences(alarmId, nowMs)

      check(
        alarmDao.updateAlarm(
          command.toEnabledAlarm(stored.alarm, revision, nowMs),
        ) == 1,
      ) { "enabled alarm edit lost" }
      check(alarmDao.deleteMission(alarmId.value) == 1) { "alarm mission missing" }
      alarmDao.insertMission(replacementMission)

      superseded.forEach { insertCancellationEffect(it, nowMs) }
      val occurrence = insertScheduledOccurrence(alarmId, revision, next, nowMs)
      insertScheduleEffect(occurrence, nowMs)
      insertMirrorEffect(alarmId, revision, occurrence.id, nowMs)

      insertReceipt(
        commandId = command.commandId,
        commandType = EDIT_COMMAND_TYPE,
        requestHash = requestHash,
        alarmId = alarmId,
        revision = revision,
        nowMs = nowMs,
      ).toAck(replayed = false)
    }

  fun disable(command: EnableAlarmCommand): DraftCommandAck =
    database.runInTransaction<DraftCommandAck> {
      replayAggregateReceipt(command, DISABLE_COMMAND_TYPE)?.let { return@runInTransaction it }
      val alarmDao = database.alarmDao()
      val stored = alarmDao.findById(command.alarmId.value)
        ?: throw AlarmSchedulingRepositoryException.NotFound()
      requireCurrentRevision(stored.alarm, command.expectedRevision)
      if (!stored.alarm.enabled) throw AlarmSchedulingRepositoryException.AlreadyDisabled()

      val nowMs = checkedNowMs()
      val revision = command.expectedRevision.next()
      val superseded = cancelSchedulableOccurrences(command.alarmId, nowMs)
      check(
        alarmDao.updateAlarm(
          stored.alarm.copy(revision = revision.value, enabled = false, updatedAtMs = nowMs),
        ) == 1,
      ) { "alarm disable update lost" }
      superseded.forEach { insertCancellationEffect(it, nowMs) }
      insertMirrorEffect(command.alarmId, revision, occurrenceId = null, nowMs)
      insertAggregateReceipt(command, DISABLE_COMMAND_TYPE, revision, nowMs).toAck(replayed = false)
    }

  fun delete(command: EnableAlarmCommand): DraftCommandAck =
    database.runInTransaction<DraftCommandAck> {
      replayAggregateReceipt(command, DELETE_COMMAND_TYPE)?.let { return@runInTransaction it }
      val alarmDao = database.alarmDao()
      val stored = alarmDao.findById(command.alarmId.value)
        ?: throw AlarmSchedulingRepositoryException.NotFound()
      requireCurrentRevision(stored.alarm, command.expectedRevision)
      if (database.runtimeDao().countNonTerminalInstances(command.alarmId.value) != 0) {
        throw AlarmSchedulingRepositoryException.ActiveInstanceExists()
      }

      val nowMs = checkedNowMs()
      val revision = command.expectedRevision.next()
      val superseded = cancelSchedulableOccurrences(command.alarmId, nowMs)
      superseded.forEach { insertCancellationEffect(it, nowMs) }
      insertMirrorEffect(command.alarmId, revision, occurrenceId = null, nowMs)
      database.runtimeDao().detachTerminalInstancesFromAlarm(command.alarmId.value, nowMs)
      database.runtimeDao().detachOccurrencesFromAlarm(command.alarmId.value, nowMs)
      check(alarmDao.deleteAlarm(command.alarmId.value) == 1) { "alarm delete lost" }
      insertAggregateReceipt(command, DELETE_COMMAND_TYPE, revision, nowMs).toAck(replayed = false)
    }

  private fun replayAggregateReceipt(
    command: EnableAlarmCommand,
    commandType: String,
  ): DraftCommandAck? {
    val requestHash = EnableRequestHasher.hash(command)
    val receipt = database.reliabilityDao().findReceipt(command.commandId.value) ?: return null
    if (receipt.commandType != commandType || receipt.requestHash != requestHash) {
      throw AlarmSchedulingRepositoryException.IdempotencyKeyReused()
    }
    return receipt.toAck(replayed = true)
  }

  private fun requireCurrentRevision(alarm: AlarmEntity, expectedRevision: Revision) {
    if (alarm.revision != expectedRevision.value) {
      throw AlarmSchedulingRepositoryException.RevisionConflict()
    }
  }

  private fun checkedNowMs(): Long = wallClock.nowEpochMillis().also {
    require(it >= 0) { "wall clock must not predate epoch" }
  }

  private fun nextOccurrence(schedule: AlarmSchedule, nowMs: Long) =
    recurrencePolicy.next(
      schedule = schedule,
      notBefore = Instant.ofEpochMilli(nowMs),
      currentZone = currentZoneProvider.current(),
    ) ?: throw AlarmSchedulingRepositoryException.ScheduleExpired()

  private fun cancelSchedulableOccurrences(
    alarmId: AlarmId,
    nowMs: Long,
  ): List<AlarmOccurrenceEntity> = database.runtimeDao()
    .findSchedulableOccurrences(alarmId.value)
    .onEach { occurrence ->
      check(database.runtimeDao().markOccurrenceCancelled(occurrence.id, nowMs) == 1) {
        "occurrence cancellation state changed"
      }
    }

  private fun insertScheduledOccurrence(
    alarmId: AlarmId,
    revision: Revision,
    next: OccurrenceTime,
    nowMs: Long,
  ): AlarmOccurrenceEntity {
    val occurrence = AlarmOccurrenceEntity(
      id = occurrenceIdGenerator.next().value,
      dedupeKey = OccurrenceIdentity.dedupeKey(alarmId, revision, next.instant),
      alarmId = alarmId.value,
      alarmRevision = revision.value,
      scheduledAtUtcMs = next.instant.toEpochMilli(),
      scheduledLocalDate = next.scheduledLocalDate.toString(),
      scheduledLocalTimeMinutes = next.scheduledLocalTime.value,
      timezoneId = next.timezoneId,
      utcOffsetSeconds = next.utcOffsetSeconds,
      state = "PENDING_OS",
      lastErrorCode = null,
      createdAtMs = nowMs,
      updatedAtMs = nowMs,
    )
    database.runtimeDao().insertOccurrence(occurrence)
    return occurrence
  }

  private fun insertScheduleEffect(occurrence: AlarmOccurrenceEntity, nowMs: Long) {
    insertEffect(
      newEffect(
        effectKey = "effect:v1:occurrence:${occurrence.id}:schedule",
        aggregateType = "OCCURRENCE",
        aggregateId = occurrence.id,
        effectType = "SCHEDULE_OCCURRENCE",
        payloadJson =
          "{\"occurrenceId\":\"${occurrence.id}\",\"scheduledAtUtcMs\":${occurrence.scheduledAtUtcMs}}",
        nowMs = nowMs,
      ),
    )
  }

  private fun insertCancellationEffect(occurrence: AlarmOccurrenceEntity, nowMs: Long) {
    insertEffect(
      newEffect(
        effectKey = "effect:v1:occurrence:${occurrence.id}:cancel",
        aggregateType = "OCCURRENCE",
        aggregateId = occurrence.id,
        effectType = "CANCEL_OCCURRENCE",
        payloadJson = "{\"occurrenceId\":\"${occurrence.id}\"}",
        nowMs = nowMs,
      ),
    )
  }

  private fun insertMirrorEffect(
    alarmId: AlarmId,
    revision: Revision,
    occurrenceId: String?,
    nowMs: Long,
  ) {
    val encodedOccurrence = occurrenceId?.let { "\"$it\"" } ?: "null"
    insertEffect(
      newEffect(
        effectKey = "effect:v1:alarm:${alarmId.value}:revision:${revision.value}:direct-boot",
        aggregateType = "ALARM",
        aggregateId = alarmId.value,
        effectType = "SYNC_DIRECT_BOOT_MIRROR",
        payloadJson =
          "{\"alarmId\":\"${alarmId.value}\",\"alarmRevision\":${revision.value}," +
            "\"occurrenceId\":$encodedOccurrence}",
        nowMs = nowMs,
      ),
    )
  }

  private fun newEffect(
    effectKey: String,
    aggregateType: String,
    aggregateId: String,
    effectType: String,
    payloadJson: String,
    nowMs: Long,
  ) = RuntimeEffectEntity(
    id = effectIdGenerator.next(),
    effectKey = effectKey,
    aggregateType = aggregateType,
    aggregateId = aggregateId,
    effectType = effectType,
    payloadVersion = 1,
    payloadJson = payloadJson,
    status = "PENDING",
    attemptCount = 0,
    nextAttemptAtMs = null,
    leaseOwner = null,
    leaseUntilMs = null,
    lastErrorCode = null,
    createdAtMs = nowMs,
    updatedAtMs = nowMs,
    acknowledgedAtMs = null,
  )

  private fun insertAggregateReceipt(
    command: EnableAlarmCommand,
    commandType: String,
    revision: Revision,
    nowMs: Long,
  ) = insertReceipt(
    commandId = command.commandId,
    commandType = commandType,
    requestHash = EnableRequestHasher.hash(command),
    alarmId = command.alarmId,
    revision = revision,
    nowMs = nowMs,
  )

  private fun insertReceipt(
    commandId: CommandId,
    commandType: String,
    requestHash: String,
    alarmId: AlarmId,
    revision: Revision,
    nowMs: Long,
  ): CommandReceiptEntity {
    val receipt = CommandReceiptEntity(
      commandId = commandId.value,
      commandType = commandType,
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
    return receipt
  }

  private fun SaveAlarmDraftCommand.toSchedule(): AlarmSchedule = when (scheduleKind) {
    "ONE_TIME" -> AlarmSchedule.OneTime(Instant.ofEpochMilli(checkNotNull(oneTimeAtUtcMs)))
    "WEEKLY" -> AlarmSchedule.Weekly(
      localTime = LocalTimeMinutes.of(localTimeMinutes),
      repeatDays = WeekdayMask.of(repeatDaysMask),
    )
    else -> error("command has unsupported schedule kind")
  }

  private fun SaveAlarmDraftCommand.toEnabledAlarm(
    current: AlarmEntity,
    revision: Revision,
    nowMs: Long,
  ) = AlarmEntity(
    id = current.id,
    revision = revision.value,
    label = label.trim(),
    enabled = true,
    scheduleKind = scheduleKind,
    localTimeMinutes = localTimeMinutes,
    repeatDaysMask = repeatDaysMask,
    oneTimeAtUtcMs = oneTimeAtUtcMs,
    configuredTimezoneId = configuredTimezoneId,
    soundId = soundId,
    createdAtMs = current.createdAtMs,
    updatedAtMs = nowMs,
  )

  private fun SaveAlarmDraftCommand.toEnabledMission(
    alarmId: AlarmId,
  ): AlarmMissionConfigEntity {
    return AlarmMissionConfigEntity(
      alarmId = alarmId.value,
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
  }

  private fun AlarmEntity.toSchedule(): AlarmSchedule = when (scheduleKind) {
    "ONE_TIME" -> AlarmSchedule.OneTime(Instant.ofEpochMilli(checkNotNull(oneTimeAtUtcMs)))
    "WEEKLY" -> AlarmSchedule.Weekly(
      localTime = LocalTimeMinutes.of(localTimeMinutes),
      repeatDays = WeekdayMask.of(repeatDaysMask),
    )
    else -> error("persisted alarm has unsupported schedule kind")
  }

  private fun insertEffect(effect: RuntimeEffectEntity) {
    check(database.reliabilityDao().insertEffect(effect) != -1L) { "runtime effect key collision" }
  }

  private fun CommandReceiptEntity.toAck(replayed: Boolean) = DraftCommandAck(
    commandId = commandId,
    alarmId = aggregateId,
    revision = resultRevision,
    appliedAtMs = createdAtMs,
    replayed = replayed,
  )

  private companion object {
    const val COMMAND_TYPE = "ENABLE_ALARM"
    const val EDIT_COMMAND_TYPE = "SAVE_ALARM_CONFIGURATION"
    const val DISABLE_COMMAND_TYPE = "DISABLE_ALARM"
    const val DELETE_COMMAND_TYPE = "DELETE_ALARM"
    const val RECEIPT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
  }
}

private object EnableRequestHasher {
  fun hash(command: EnableAlarmCommand): String {
    val canonicalFields = listOf(
      command.alarmId.value,
      command.expectedRevision.value.toString(),
    ).joinToString(separator = "") { value ->
      "${value.toByteArray(Charsets.UTF_8).size}:$value"
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonicalFields.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
  }
}
