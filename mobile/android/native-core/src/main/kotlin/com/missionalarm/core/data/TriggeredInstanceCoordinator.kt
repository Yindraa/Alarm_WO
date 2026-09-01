package com.missionalarm.core.data

import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.AlarmSchedule
import com.missionalarm.core.domain.LocalTimeMinutes
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.OccurrenceIdentity
import com.missionalarm.core.domain.RecurrencePolicy
import com.missionalarm.core.domain.Revision
import com.missionalarm.core.domain.WallClock
import com.missionalarm.core.domain.WeekdayMask
import java.security.MessageDigest
import java.time.Instant

fun interface InstanceIdGenerator {
  fun next(): String
}

data class TriggerTiming(
  val occurredAtMs: Long,
  val elapsedRealtimeMs: Long? = null,
  val bootSessionToken: String? = null,
) {
  init {
    require(occurredAtMs >= 0)
    require(elapsedRealtimeMs == null || elapsedRealtimeMs >= 0)
    require((elapsedRealtimeMs == null) == (bootSessionToken == null))
    require(bootSessionToken == null || bootSessionToken.isNotBlank())
  }
}

data class TriggeredInstanceResult(
  val instance: AlarmInstanceEntity,
  val created: Boolean,
)

sealed class TriggeredInstanceException(message: String) : IllegalStateException(message) {
  class OccurrenceNotFound : TriggeredInstanceException("occurrence not found")
  class OccurrenceNotTriggerable : TriggeredInstanceException("occurrence is not triggerable")
  class AlarmUnavailable : TriggeredInstanceException("alarm is unavailable")
}

class TriggeredInstanceCoordinator(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val currentZoneProvider: CurrentZoneProvider,
  private val instanceIdGenerator: InstanceIdGenerator,
  private val occurrenceIdGenerator: OccurrenceIdGenerator,
  private val effectIdGenerator: EffectIdGenerator,
  private val recurrencePolicy: RecurrencePolicy = RecurrencePolicy(),
  private val mathQuestionGenerator: MathQuestionGenerator = SeededMathQuestionGeneratorV1(),
) {
  fun getOrCreate(occurrenceId: OccurrenceId, timing: TriggerTiming): TriggeredInstanceResult =
    database.runInTransaction<TriggeredInstanceResult> {
      database.runtimeDao().findInstanceByOccurrence(occurrenceId.value)?.let {
        return@runInTransaction TriggeredInstanceResult(it, created = false)
      }
      val occurrence = database.runtimeDao().findOccurrenceById(occurrenceId.value)
        ?: throw TriggeredInstanceException.OccurrenceNotFound()
      if (occurrence.state !in setOf("PENDING_OS", "SCHEDULED_OS", "FIRED")) {
        throw TriggeredInstanceException.OccurrenceNotTriggerable()
      }
      val alarmId = occurrence.alarmId ?: throw TriggeredInstanceException.AlarmUnavailable()
      val stored = database.alarmDao().findById(alarmId)
        ?.takeIf { it.alarm.enabled }
        ?: throw TriggeredInstanceException.AlarmUnavailable()
      if (stored.alarm.revision != occurrence.alarmRevision) {
        throw TriggeredInstanceException.OccurrenceNotTriggerable()
      }

      val nowMs = nowMs()
      val instanceId = instanceIdGenerator.next()
      val attended = database.runtimeDao().countAttendedInstances() == 0
      val instance = AlarmInstanceEntity(
        id = instanceId,
        occurrenceId = occurrence.id,
        alarmId = alarmId,
        revision = 1,
        runtimeState = if (attended) "TRIGGERED" else "PENDING_ATTENTION",
        queueOrder = database.runtimeDao().nextQueueOrder(),
        attentionSlot = if (attended) 1 else null,
        scheduledAtUtcMs = occurrence.scheduledAtUtcMs,
        actualTriggerAtMs = timing.occurredAtMs,
        triggerElapsedRealtimeMs = timing.elapsedRealtimeMs,
        bootSessionToken = timing.bootSessionToken,
        terminalAtMs = null,
        terminalResult = null,
        dismissMethod = null,
        errorReasonCode = null,
        labelSnapshot = stored.alarm.label,
        soundIdSnapshot = stored.alarm.soundId,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
      )
      val mission = stored.mission.toInstanceMission(instanceId, nowMs)
      database.runtimeDao().getOrCreateTriggeredInstance(instance, mission)
      if (stored.mission.missionType == "MATH") {
        database.runtimeDao().insertMathQuestions(
          mathQuestionGenerator.generate(
            instanceId,
            occurrence.id,
            stored.mission.target,
            checkNotNull(stored.mission.mathOperationsMask),
            checkNotNull(stored.mission.mathGeneratorVersion),
          ),
        )
      }
      if (attended) {
        insertRuntimeEffect(instance, "START_ALARM_RUNTIME", nowMs)
        insertRuntimeEffect(instance, "PRESENT_ACTIVE_INSTANCE", nowMs)
      }
      advanceSchedule(stored, occurrence, nowMs)
      TriggeredInstanceResult(instance, created = true)
    }

  private fun advanceSchedule(
    stored: AlarmWithMission,
    fired: AlarmOccurrenceEntity,
    nowMs: Long,
  ) {
    val alarm = stored.alarm
    if (alarm.scheduleKind == "ONE_TIME") {
      val revision = Revision.of(alarm.revision).next()
      check(database.alarmDao().updateAlarm(alarm.copy(
        revision = revision.value,
        enabled = false,
        updatedAtMs = nowMs,
      )) == 1)
      insertMirrorEffect(alarm.id, revision.value, null, fired.id, nowMs)
      return
    }

    val next = checkNotNull(
      recurrencePolicy.next(
        AlarmSchedule.Weekly(
          LocalTimeMinutes.of(alarm.localTimeMinutes),
          WeekdayMask.of(alarm.repeatDaysMask),
        ),
        Instant.ofEpochMilli(maxOf(nowMs, Math.addExact(fired.scheduledAtUtcMs, 1L))),
        currentZoneProvider.current(),
      ),
    )
    val nextId = occurrenceIdGenerator.next()
    val nextOccurrence = AlarmOccurrenceEntity(
      id = nextId.value,
      dedupeKey = OccurrenceIdentity.dedupeKey(
        AlarmId.parse(alarm.id),
        Revision.of(alarm.revision),
        next.instant,
      ),
      alarmId = alarm.id,
      alarmRevision = alarm.revision,
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
    database.runtimeDao().insertOccurrence(nextOccurrence)
    insertEffect(
      effectKey = "effect:v1:occurrence:${nextId.value}:schedule",
      aggregateType = "OCCURRENCE",
      aggregateId = nextId.value,
      effectType = "SCHEDULE_OCCURRENCE",
      payloadJson =
        "{\"occurrenceId\":\"${nextId.value}\",\"scheduledAtUtcMs\":${next.instant.toEpochMilli()}}",
      nowMs = nowMs,
    )
    insertMirrorEffect(alarm.id, alarm.revision, nextId.value, fired.id, nowMs)
  }

  private fun insertRuntimeEffect(instance: AlarmInstanceEntity, effectType: String, nowMs: Long) {
    insertEffect(
      effectKey = "effect:v1:instance:${instance.id}:$effectType",
      aggregateType = "INSTANCE",
      aggregateId = instance.id,
      effectType = effectType,
      payloadJson = "{\"instanceId\":\"${instance.id}\"}",
      nowMs = nowMs,
    )
  }

  private fun insertMirrorEffect(
    alarmId: String,
    alarmRevision: Int,
    occurrenceId: String?,
    firedOccurrenceId: String,
    nowMs: Long,
  ) {
    val encoded = occurrenceId?.let { "\"$it\"" } ?: "null"
    insertEffect(
      effectKey = "effect:v1:alarm:$alarmId:trigger:$firedOccurrenceId:direct-boot",
      aggregateType = "ALARM",
      aggregateId = alarmId,
      effectType = "SYNC_DIRECT_BOOT_MIRROR",
      payloadJson =
        "{\"alarmId\":\"$alarmId\",\"alarmRevision\":$alarmRevision,\"occurrenceId\":$encoded}",
      nowMs = nowMs,
    )
  }

  private fun insertEffect(
    effectKey: String,
    aggregateType: String,
    aggregateId: String,
    effectType: String,
    payloadJson: String,
    nowMs: Long,
  ) {
    check(database.reliabilityDao().insertEffect(RuntimeEffectEntity(
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
    )) != -1L) { "duplicate runtime effect identity" }
  }

  private fun nowMs() = wallClock.nowEpochMillis().also { require(it >= 0) }
}

private fun AlarmMissionConfigEntity.toInstanceMission(instanceId: String, nowMs: Long) =
  InstanceMissionEntity(
    instanceId = instanceId,
    missionType = missionType,
    snapshotVersion = configVersion,
    target = target,
    committedProgress = 0,
    runtimeStatus = "READY",
    engineVersion = when (missionType) {
      "PUSH_UP" -> "pushup-engine-v1"
      "MATH" -> checkNotNull(mathGeneratorVersion)
      "QR" -> "scan-code-engine-v1"
      else -> error("unsupported mission type")
    },
    pushupProfileVersion = pushupProfileVersion,
    mathGeneratorVersion = mathGeneratorVersion,
    qrReferenceDigest = qrReferenceDigest?.copyOf(),
    qrDigestVersion = qrDigestVersion,
    qrKeyAlias = qrKeyAlias,
    updatedAtMs = nowMs,
  )

fun interface MathQuestionGenerator {
  fun generate(
    instanceId: String,
    occurrenceId: String,
    count: Int,
    operationsMask: Int,
    generatorVersion: String,
  ): List<MathQuestionEntity>
}

class SeededMathQuestionGeneratorV1 : MathQuestionGenerator {
  override fun generate(
    instanceId: String,
    occurrenceId: String,
    count: Int,
    operationsMask: Int,
    generatorVersion: String,
  ): List<MathQuestionEntity> {
    require(count in 1..10)
    val operations = buildList {
      if (operationsMask and 1 != 0) add("ADD")
      if (operationsMask and 2 != 0) add("SUBTRACT")
      if (operationsMask and 4 != 0) add("MULTIPLY")
    }
    require(operations.isNotEmpty())
    return List(count) { ordinal ->
      val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$generatorVersion|$occurrenceId|$ordinal".toByteArray(Charsets.UTF_8))
      val operation = operations[(bytes[0].toInt() and 0xff) % operations.size]
      val limit = if (operation == "MULTIPLY") 12 else 50
      val a = (bytes[1].toInt() and 0xff) % limit + 1
      val b = (bytes[2].toInt() and 0xff) % limit + 1
      MathQuestionEntity(
        instanceId = instanceId,
        ordinal = ordinal,
        operation = operation,
        operandA = a,
        operandB = b,
        correctAnswer = when (operation) {
          "ADD" -> a + b
          "SUBTRACT" -> a - b
          else -> a * b
        },
        answered = false,
        answeredAtMs = null,
      )
    }
  }
}
