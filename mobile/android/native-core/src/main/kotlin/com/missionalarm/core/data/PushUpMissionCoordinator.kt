package com.missionalarm.core.data

import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.InstanceRuntimeState
import com.missionalarm.core.domain.InstanceState
import com.missionalarm.core.domain.MissionProgress
import com.missionalarm.core.domain.TerminalResult
import com.missionalarm.core.domain.WallClock
import java.security.MessageDigest

data class CommitPushUpRepCommand(
  val commandId: CommandId,
  val instanceId: String,
  val expectedRevision: Int,
  val sessionId: String,
  val repSequence: Int,
  val profileVersion: String,
) {
  init {
    require(instanceId.isNotBlank())
    require(expectedRevision >= 1)
    require(sessionId.isNotBlank())
    require(repSequence >= 1)
    require(profileVersion.isNotBlank())
  }
}

data class PushUpRepResult(
  val instanceId: String,
  val instanceRevision: Int,
  val committedProgress: Int,
  val completed: Boolean,
  val promotedInstanceId: String?,
  val commandId: String,
  val appliedAtMs: Long,
  val replayed: Boolean,
)

sealed class PushUpMissionException(message: String) : IllegalStateException(message) {
  class InstanceNotFound : PushUpMissionException("instance not found")
  class NotAttended : PushUpMissionException("instance is not currently attended")
  class WrongMissionType : PushUpMissionException("active mission is not Push-up")
  class RevisionConflict : PushUpMissionException("instance revision changed")
  class ProfileMismatch : PushUpMissionException("verification profile does not match mission snapshot")
  class InvalidRepSequence : PushUpMissionException("verified rep sequence is not the next committed rep")
  class IdempotencyKeyReused : PushUpMissionException("evidence ID reused with different verification")
  class InvalidState : PushUpMissionException("Push-up mission cannot accept verified progress")
}

/** Native persistence authority for repetitions emitted by the pure Push-up verifier. */
class PushUpMissionCoordinator(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val effectIdGenerator: EffectIdGenerator,
) {
  fun start(instanceId: String): ActiveRuntimeSnapshot =
    database.runInTransaction<ActiveRuntimeSnapshot> {
      val instance = database.runtimeDao().findInstanceById(instanceId)
        ?: throw PushUpMissionException.InstanceNotFound()
      val mission = database.runtimeDao().findMission(instanceId)
        ?: throw PushUpMissionException.InvalidState()
      validateAttendedPushUp(instance, mission)
      if (instance.runtimeState == "MISSION_IN_PROGRESS" && mission.runtimeStatus == "IN_PROGRESS") {
        return@runInTransaction activeSnapshot(instanceId)
      }

      val nowMs = nowMs()
      var expectedRevision = instance.revision
      var state = InstanceState(InstanceRuntimeState.valueOf(instance.runtimeState))
      if (instance.runtimeState == "TRIGGERED") {
        state = state.lockMission()
        check(state.runtime == InstanceRuntimeState.MISSION_LOCKED)
        if (database.runtimeDao().lockMission(instanceId, expectedRevision, nowMs) != 1) {
          throw PushUpMissionException.RevisionConflict()
        }
        expectedRevision = Math.addExact(expectedRevision, 1)
      } else if (instance.runtimeState != "MISSION_LOCKED") {
        throw PushUpMissionException.InvalidState()
      }

      if (mission.runtimeStatus == "READY") {
        if (database.runtimeDao().startPushUpMissionState(instanceId, nowMs) != 1) {
          throw PushUpMissionException.InvalidState()
        }
      } else if (mission.runtimeStatus != "IN_PROGRESS") {
        throw PushUpMissionException.InvalidState()
      }
      state.startMission().also { check(it.runtime == InstanceRuntimeState.MISSION_IN_PROGRESS) }
      if (database.runtimeDao().startMission(instanceId, expectedRevision, nowMs) != 1) {
        throw PushUpMissionException.RevisionConflict()
      }
      activeSnapshot(instanceId)
    }

  fun commitVerifiedRep(command: CommitPushUpRepCommand): PushUpRepResult =
    database.runInTransaction<PushUpRepResult> {
      val requestHash = PushUpEvidenceHasher.hash(command)
      database.reliabilityDao().findReceipt(command.commandId.value)?.let { receipt ->
        if (receipt.commandType != COMMAND_TYPE || receipt.requestHash != requestHash) {
          throw PushUpMissionException.IdempotencyKeyReused()
        }
        return@runInTransaction receipt.toPushUpResult(replayed = true)
      }

      val result = applyVerifiedRep(command)
      val outcome = listOf(
        result.committedProgress.toString(),
        result.completed.toString(),
        result.promotedInstanceId.orEmpty(),
      ).joinToString(OUTCOME_SEPARATOR)
      val nowMs = nowMs()
      check(database.reliabilityDao().insertReceipt(
        CommandReceiptEntity(
          commandId = command.commandId.value,
          commandType = COMMAND_TYPE,
          requestHash = requestHash,
          aggregateType = "INSTANCE",
          aggregateId = command.instanceId,
          resultRevision = result.instanceRevision,
          status = "APPLIED",
          outcomeCode = outcome,
          createdAtMs = nowMs,
          expiresAtMs = Math.addExact(nowMs, RECEIPT_RETENTION_MS),
        ),
      ) != -1L) { "Push-up evidence receipt race" }
      result.copy(commandId = command.commandId.value, appliedAtMs = nowMs)
    }

  private fun applyVerifiedRep(command: CommitPushUpRepCommand): PushUpRepResult {
    val instance = database.runtimeDao().findInstanceById(command.instanceId)
      ?: throw PushUpMissionException.InstanceNotFound()
    val mission = database.runtimeDao().findMission(command.instanceId)
      ?: throw PushUpMissionException.InvalidState()
    validateAttendedPushUp(instance, mission)
    if (instance.revision != command.expectedRevision) throw PushUpMissionException.RevisionConflict()
    if (instance.runtimeState != "MISSION_IN_PROGRESS" || mission.runtimeStatus != "IN_PROGRESS") {
      throw PushUpMissionException.InvalidState()
    }
    if (mission.pushupProfileVersion != command.profileVersion) {
      throw PushUpMissionException.ProfileMismatch()
    }
    val nextProgress = Math.addExact(mission.committedProgress, 1)
    if (command.repSequence != nextProgress) throw PushUpMissionException.InvalidRepSequence()

    val nowMs = nowMs()
    val progress = MissionProgress.restore(mission.target, mission.committedProgress)
      .commitVerified(nextProgress)
    if (database.runtimeDao().advancePushUpProgress(
        command.instanceId,
        mission.committedProgress,
        progress.committed,
        if (progress.isComplete) "COMPLETED" else "IN_PROGRESS",
        nowMs,
      ) != 1
    ) {
      throw PushUpMissionException.InvalidState()
    }
    val nextRevision = Math.addExact(instance.revision, 1)
    if (!progress.isComplete) {
      if (database.runtimeDao().commitMissionProgressRevision(
          command.instanceId,
          instance.revision,
          nowMs,
        ) != 1
      ) {
        throw PushUpMissionException.RevisionConflict()
      }
      return PushUpRepResult(
        command.instanceId,
        nextRevision,
        progress.committed,
        completed = false,
        promotedInstanceId = null,
        commandId = command.commandId.value,
        appliedAtMs = nowMs,
        replayed = false,
      )
    }

    InstanceState(InstanceRuntimeState.MISSION_IN_PROGRESS)
      .completeVerified(progress)
      .also { check(it.terminalResult == TerminalResult.SUCCESS) }
    if (database.runtimeDao().completeVerifiedMission(command.instanceId, instance.revision, nowMs) != 1) {
      throw PushUpMissionException.RevisionConflict()
    }
    database.runtimeDao().insertHistory(
      AlarmHistoryEntity(
        instanceId = instance.id,
        scheduledAtUtcMs = instance.scheduledAtUtcMs,
        actualTriggerAtMs = instance.actualTriggerAtMs,
        endedAtMs = nowMs,
        completionDurationMs = instance.actualTriggerAtMs?.let { maxOf(0, nowMs - it) },
        missionType = mission.missionType,
        target = mission.target,
        finalProgress = progress.committed,
        result = "SUCCESS",
        dismissMethod = "VERIFIED_MISSION",
        errorReasonCode = null,
        engineVersion = mission.engineVersion,
        profileVersion = mission.pushupProfileVersion,
        createdAtMs = nowMs,
      ),
    )
    insertEffect(instance.id, "STOP_ALARM_RUNTIME", nowMs)

    val promoted = database.runtimeDao().findOldestQueuedInstance()?.also {
      if (database.runtimeDao().promoteQueuedInstance(it.id, it.revision, nowMs) != 1) {
        throw PushUpMissionException.InvalidState()
      }
      insertEffect(it.id, "START_ALARM_RUNTIME", nowMs)
      insertEffect(it.id, "PRESENT_ACTIVE_INSTANCE", nowMs)
    }
    return PushUpRepResult(
      command.instanceId,
      nextRevision,
      progress.committed,
      completed = true,
      promotedInstanceId = promoted?.id,
      commandId = command.commandId.value,
      appliedAtMs = nowMs,
      replayed = false,
    )
  }

  private fun validateAttendedPushUp(
    instance: AlarmInstanceEntity,
    mission: InstanceMissionEntity,
  ) {
    if (instance.attentionSlot != 1) throw PushUpMissionException.NotAttended()
    if (mission.missionType != "PUSH_UP") throw PushUpMissionException.WrongMissionType()
    if (mission.pushupProfileVersion.isNullOrBlank()) throw PushUpMissionException.InvalidState()
  }

  private fun activeSnapshot(instanceId: String): ActiveRuntimeSnapshot {
    val snapshot = database.runtimeDao().loadActiveRuntimeSnapshot()
      ?: throw PushUpMissionException.InvalidState()
    if (snapshot.instanceId != instanceId) throw PushUpMissionException.NotAttended()
    return snapshot
  }

  private fun CommandReceiptEntity.toPushUpResult(replayed: Boolean): PushUpRepResult {
    val parts = outcomeCode?.split(OUTCOME_SEPARATOR, limit = 3)
      ?: throw PushUpMissionException.InvalidState()
    if (parts.size != 3) throw PushUpMissionException.InvalidState()
    return PushUpRepResult(
      instanceId = aggregateId,
      instanceRevision = resultRevision,
      committedProgress = parts[0].toIntOrNull() ?: throw PushUpMissionException.InvalidState(),
      completed = parts[1].toBooleanStrictOrNull() ?: throw PushUpMissionException.InvalidState(),
      promotedInstanceId = parts[2].ifBlank { null },
      commandId = commandId,
      appliedAtMs = createdAtMs,
      replayed = replayed,
    )
  }

  private fun insertEffect(instanceId: String, effectType: String, nowMs: Long) {
    check(database.reliabilityDao().insertEffect(
      RuntimeEffectEntity(
        id = effectIdGenerator.next(),
        effectKey = "effect:v1:instance:$instanceId:$effectType",
        aggregateType = "INSTANCE",
        aggregateId = instanceId,
        effectType = effectType,
        payloadVersion = 1,
        payloadJson = "{\"instanceId\":\"$instanceId\"}",
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
    ) != -1L) { "duplicate Push-up completion effect identity" }
  }

  private fun nowMs(): Long = wallClock.nowEpochMillis().also {
    require(it >= 0) { "wall clock must not predate epoch" }
  }

  private companion object {
    const val COMMAND_TYPE = "COMMIT_PUSH_UP_REP"
    const val OUTCOME_SEPARATOR = "|"
    const val RECEIPT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
  }
}

private object PushUpEvidenceHasher {
  fun hash(command: CommitPushUpRepCommand): String = hashFields(
    command.instanceId,
    command.expectedRevision.toString(),
    command.sessionId,
    command.repSequence.toString(),
    command.profileVersion,
  )

  private fun hashFields(vararg fields: String): String {
    val canonical = fields.joinToString("") { value ->
      "${value.toByteArray(Charsets.UTF_8).size}:$value"
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
  }
}
