package com.missionalarm.core.data

import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.AlarmSchedule
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.LocalTimeMinutes
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.OccurrenceIdentity
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
  class QrNotRegistered : AlarmSchedulingRepositoryException("QR reference is not registered")
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
      if (stored.mission.missionType == "QR" && stored.mission.qrReferenceDigest == null) {
        throw AlarmSchedulingRepositoryException.QrNotRegistered()
      }
      if (database.runtimeDao().findNextOccurrence(command.alarmId.value) != null) {
        throw AlarmSchedulingRepositoryException.PendingOccurrenceExists()
      }

      val nowMs = wallClock.nowEpochMillis()
      require(nowMs >= 0) { "wall clock must not predate epoch" }
      val currentZone = currentZoneProvider.current()
      val next = recurrencePolicy.next(
        schedule = stored.alarm.toSchedule(),
        notBefore = Instant.ofEpochMilli(nowMs),
        currentZone = currentZone,
      ) ?: throw AlarmSchedulingRepositoryException.ScheduleExpired()
      val revision = command.expectedRevision.next()
      val occurrenceId = occurrenceIdGenerator.next()
      val scheduledAtMs = next.instant.toEpochMilli()

      check(
        alarmDao.updateAlarm(
          stored.alarm.copy(
            revision = revision.value,
            enabled = true,
            updatedAtMs = nowMs,
          ),
        ) == 1,
      ) { "alarm enable update lost" }

      database.runtimeDao().insertOccurrence(
        AlarmOccurrenceEntity(
          id = occurrenceId.value,
          dedupeKey = OccurrenceIdentity.dedupeKey(command.alarmId, revision, next.instant),
          alarmId = command.alarmId.value,
          alarmRevision = revision.value,
          scheduledAtUtcMs = scheduledAtMs,
          scheduledLocalDate = next.scheduledLocalDate.toString(),
          scheduledLocalTimeMinutes = next.scheduledLocalTime.value,
          timezoneId = next.timezoneId,
          utcOffsetSeconds = next.utcOffsetSeconds,
          state = "PENDING_OS",
          lastErrorCode = null,
          createdAtMs = nowMs,
          updatedAtMs = nowMs,
        ),
      )

      insertEffect(
        RuntimeEffectEntity(
          id = effectIdGenerator.next(),
          effectKey = "effect:v1:occurrence:${occurrenceId.value}:schedule",
          aggregateType = "OCCURRENCE",
          aggregateId = occurrenceId.value,
          effectType = "SCHEDULE_OCCURRENCE",
          payloadVersion = 1,
          payloadJson =
            "{\"occurrenceId\":\"${occurrenceId.value}\",\"scheduledAtUtcMs\":$scheduledAtMs}",
          status = "PENDING",
          attemptCount = 0,
          nextAttemptAtMs = null,
          leaseOwner = null,
          leaseUntilMs = null,
          lastErrorCode = null,
          createdAtMs = nowMs,
          updatedAtMs = nowMs,
          acknowledgedAtMs = null,
        ),
      )
      insertEffect(
        RuntimeEffectEntity(
          id = effectIdGenerator.next(),
          effectKey = "effect:v1:alarm:${command.alarmId.value}:revision:${revision.value}:direct-boot",
          aggregateType = "ALARM",
          aggregateId = command.alarmId.value,
          effectType = "SYNC_DIRECT_BOOT_MIRROR",
          payloadVersion = 1,
          payloadJson =
            "{\"alarmId\":\"${command.alarmId.value}\",\"alarmRevision\":${revision.value}," +
              "\"occurrenceId\":\"${occurrenceId.value}\"}",
          status = "PENDING",
          attemptCount = 0,
          nextAttemptAtMs = null,
          leaseOwner = null,
          leaseUntilMs = null,
          lastErrorCode = null,
          createdAtMs = nowMs,
          updatedAtMs = nowMs,
          acknowledgedAtMs = null,
        ),
      )

      val receipt = CommandReceiptEntity(
        commandId = command.commandId.value,
        commandType = COMMAND_TYPE,
        requestHash = requestHash,
        aggregateType = "ALARM",
        aggregateId = command.alarmId.value,
        resultRevision = revision.value,
        status = "APPLIED",
        outcomeCode = null,
        createdAtMs = nowMs,
        expiresAtMs = Math.addExact(nowMs, RECEIPT_RETENTION_MS),
      )
      check(reliabilityDao.insertReceipt(receipt) != -1L) { "command receipt race" }
      receipt.toAck(replayed = false)
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
