package com.missionalarm.core.data

import com.missionalarm.core.domain.InstanceRuntimeState
import com.missionalarm.core.domain.InstanceState
import com.missionalarm.core.domain.MissionProgress
import com.missionalarm.core.domain.TerminalResult
import com.missionalarm.core.domain.WallClock
import com.missionalarm.core.domain.CommandId
import java.security.MessageDigest

data class StartMissionCommand(
  val commandId: CommandId,
  val instanceId: String,
  val expectedRevision: Int,
)

data class SubmitMathAnswerCommand(
  val commandId: CommandId,
  val instanceId: String,
  val expectedRevision: Int,
  val questionOrdinal: Int,
  val answer: Int,
)

data class MissionCommandAck(
  val commandId: String,
  val instanceId: String,
  val revision: Int,
  val appliedAtMs: Long,
  val replayed: Boolean,
)

data class MathAnswerResult(
  val instanceId: String,
  val instanceRevision: Int,
  val correct: Boolean,
  val committedProgress: Int,
  val completed: Boolean,
  val promotedInstanceId: String?,
  val commandId: String? = null,
  val appliedAtMs: Long? = null,
  val replayed: Boolean = false,
)

sealed class MathMissionException(message: String) : IllegalStateException(message) {
  class InstanceNotFound : MathMissionException("instance not found")
  class NotAttended : MathMissionException("instance is not currently attended")
  class WrongMissionType : MathMissionException("active mission is not Math")
  class QuestionNotFound : MathMissionException("Math question not found")
  class StaleQuestion : MathMissionException("Math question is no longer current")
  class RevisionConflict : MathMissionException("instance revision changed")
  class IdempotencyKeyReused : MathMissionException("command ID reused with different request")
  class InvalidState : MathMissionException("Math mission is not answerable")
}

/** Native authority for starting and committing deterministic Math mission progress. */
class MathMissionCoordinator(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val effectIdGenerator: EffectIdGenerator,
) {
  fun start(command: StartMissionCommand): MissionCommandAck =
    database.runInTransaction<MissionCommandAck> {
      val requestHash = MathCommandHasher.start(command)
      database.reliabilityDao().findReceipt(command.commandId.value)?.let { receipt ->
        validateReceipt(receipt, START_COMMAND_TYPE, requestHash)
        return@runInTransaction MissionCommandAck(
          receipt.commandId,
          receipt.aggregateId,
          receipt.resultRevision,
          receipt.createdAtMs,
          replayed = true,
        )
      }
      val current = database.runtimeDao().findInstanceById(command.instanceId)
        ?: throw MathMissionException.InstanceNotFound()
      if (current.revision != command.expectedRevision) throw MathMissionException.RevisionConflict()
      val snapshot = start(command.instanceId)
      val nowMs = nowMs()
      insertReceipt(
        command.commandId,
        START_COMMAND_TYPE,
        requestHash,
        command.instanceId,
        snapshot.revision,
        "APPLIED",
        STARTED_OUTCOME,
        nowMs,
      )
      MissionCommandAck(
        command.commandId.value,
        command.instanceId,
        snapshot.revision,
        nowMs,
        replayed = false,
      )
    }

  fun start(instanceId: String): ActiveRuntimeSnapshot =
    database.runInTransaction<ActiveRuntimeSnapshot> {
      val instance = database.runtimeDao().findInstanceById(instanceId)
        ?: throw MathMissionException.InstanceNotFound()
      val mission = database.runtimeDao().findMission(instanceId)
        ?: throw MathMissionException.InvalidState()
      validateAttendedMath(instance, mission)
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
          throw MathMissionException.RevisionConflict()
        }
        expectedRevision = Math.addExact(expectedRevision, 1)
      } else if (instance.runtimeState != "MISSION_LOCKED") {
        throw MathMissionException.InvalidState()
      }

      if (mission.runtimeStatus == "READY") {
        if (database.runtimeDao().startMathMissionState(instanceId, nowMs) != 1) {
          throw MathMissionException.InvalidState()
        }
      } else if (mission.runtimeStatus != "IN_PROGRESS") {
        throw MathMissionException.InvalidState()
      }
      state.startMission().also { check(it.runtime == InstanceRuntimeState.MISSION_IN_PROGRESS) }
      if (database.runtimeDao().startMission(instanceId, expectedRevision, nowMs) != 1) {
        throw MathMissionException.RevisionConflict()
      }
      activeSnapshot(instanceId)
    }

  fun submitAnswer(
    instanceId: String,
    expectedRevision: Int,
    questionOrdinal: Int,
    answer: Int,
  ): MathAnswerResult = database.runInTransaction<MathAnswerResult> {
    val instance = database.runtimeDao().findInstanceById(instanceId)
      ?: throw MathMissionException.InstanceNotFound()
    val mission = database.runtimeDao().findMission(instanceId)
      ?: throw MathMissionException.InvalidState()
    if (instance.runtimeState == "TERMINAL" && instance.terminalResult == "SUCCESS" &&
      mission.runtimeStatus == "COMPLETED" &&
      database.runtimeDao().findHistoryByInstanceId(instanceId)?.result == "SUCCESS"
    ) {
      return@runInTransaction MathAnswerResult(
        instanceId,
        instance.revision,
        correct = true,
        mission.committedProgress,
        completed = true,
        promotedInstanceId = database.runtimeDao().findAttendedInstance()?.id,
      )
    }
    validateAttendedMath(instance, mission)
    if (instance.revision != expectedRevision) throw MathMissionException.RevisionConflict()
    if (instance.runtimeState != "MISSION_IN_PROGRESS" || mission.runtimeStatus != "IN_PROGRESS") {
      throw MathMissionException.InvalidState()
    }
    val current = database.runtimeDao().findCurrentMathQuestion(instanceId)
      ?: throw MathMissionException.QuestionNotFound()
    if (current.ordinal != questionOrdinal) throw MathMissionException.StaleQuestion()
    val stored = database.runtimeDao().findMathQuestion(instanceId, questionOrdinal)
      ?: throw MathMissionException.QuestionNotFound()
    if (stored.answered) throw MathMissionException.StaleQuestion()
    if (stored.correctAnswer != answer) {
      return@runInTransaction MathAnswerResult(
        instanceId,
        instance.revision,
        correct = false,
        mission.committedProgress,
        completed = false,
        promotedInstanceId = null,
      )
    }

    val nowMs = nowMs()
    val progress = MissionProgress.restore(mission.target, mission.committedProgress)
      .commitVerified(Math.addExact(mission.committedProgress, 1))
    if (database.runtimeDao().markMathQuestionAnswered(instanceId, questionOrdinal, nowMs) != 1) {
      throw MathMissionException.StaleQuestion()
    }
    if (database.runtimeDao().advanceMathProgress(
        instanceId,
        mission.committedProgress,
        progress.committed,
        if (progress.isComplete) "COMPLETED" else "IN_PROGRESS",
        nowMs,
      ) != 1
    ) {
      throw MathMissionException.InvalidState()
    }
    val terminalRevision = Math.addExact(instance.revision, 1)
    if (!progress.isComplete) {
      if (database.runtimeDao().commitMissionProgressRevision(
          instanceId,
          instance.revision,
          nowMs,
        ) != 1
      ) {
        throw MathMissionException.RevisionConflict()
      }
      return@runInTransaction MathAnswerResult(
        instanceId,
        terminalRevision,
        correct = true,
        progress.committed,
        completed = false,
        promotedInstanceId = null,
      )
    }

    InstanceState(InstanceRuntimeState.MISSION_IN_PROGRESS)
      .completeVerified(progress)
      .also { check(it.terminalResult == TerminalResult.SUCCESS) }
    if (database.runtimeDao().completeVerifiedMission(instanceId, instance.revision, nowMs) != 1) {
      throw MathMissionException.RevisionConflict()
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
        profileVersion = mission.mathGeneratorVersion,
        createdAtMs = nowMs,
      ),
    )
    insertEffect(instance.id, "STOP_ALARM_RUNTIME", nowMs)

    val promoted = database.runtimeDao().findOldestQueuedInstance()?.also {
      if (database.runtimeDao().promoteQueuedInstance(it.id, it.revision, nowMs) != 1) {
        throw MathMissionException.InvalidState()
      }
      insertEffect(it.id, "START_ALARM_RUNTIME", nowMs)
      insertEffect(it.id, "PRESENT_ACTIVE_INSTANCE", nowMs)
    }
    MathAnswerResult(
      instanceId,
      terminalRevision,
      correct = true,
      progress.committed,
      completed = true,
      promotedInstanceId = promoted?.id,
    )
  }

  fun submitAnswer(command: SubmitMathAnswerCommand): MathAnswerResult =
    database.runInTransaction<MathAnswerResult> {
      val requestHash = MathCommandHasher.answer(command)
      database.reliabilityDao().findReceipt(command.commandId.value)?.let { receipt ->
        validateReceipt(receipt, ANSWER_COMMAND_TYPE, requestHash)
        return@runInTransaction receipt.toMathAnswerResult(replayed = true)
      }
      val result = submitAnswer(
        command.instanceId,
        command.expectedRevision,
        command.questionOrdinal,
        command.answer,
      )
      val nowMs = nowMs()
      val outcome = listOf(
        if (result.correct) CORRECT_OUTCOME else INCORRECT_OUTCOME,
        result.committedProgress.toString(),
        result.completed.toString(),
      ).joinToString(OUTCOME_SEPARATOR)
      insertReceipt(
        command.commandId,
        ANSWER_COMMAND_TYPE,
        requestHash,
        command.instanceId,
        result.instanceRevision,
        if (result.correct) "APPLIED" else "NO_CHANGE",
        outcome,
        nowMs,
      )
      result.copy(
        commandId = command.commandId.value,
        appliedAtMs = nowMs,
        replayed = false,
      )
    }

  private fun validateAttendedMath(
    instance: AlarmInstanceEntity,
    mission: InstanceMissionEntity,
  ) {
    if (instance.attentionSlot != 1) throw MathMissionException.NotAttended()
    if (mission.missionType != "MATH") throw MathMissionException.WrongMissionType()
  }

  private fun activeSnapshot(instanceId: String): ActiveRuntimeSnapshot {
    val snapshot = database.runtimeDao().loadActiveRuntimeSnapshot()
      ?: throw MathMissionException.InvalidState()
    if (snapshot.instanceId != instanceId) throw MathMissionException.NotAttended()
    return snapshot
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
    ) != -1L) { "duplicate Math completion effect identity" }
  }

  private fun insertReceipt(
    commandId: CommandId,
    commandType: String,
    requestHash: String,
    instanceId: String,
    revision: Int,
    status: String,
    outcomeCode: String,
    nowMs: Long,
  ) {
    check(database.reliabilityDao().insertReceipt(
      CommandReceiptEntity(
        commandId = commandId.value,
        commandType = commandType,
        requestHash = requestHash,
        aggregateType = "INSTANCE",
        aggregateId = instanceId,
        resultRevision = revision,
        status = status,
        outcomeCode = outcomeCode,
        createdAtMs = nowMs,
        expiresAtMs = Math.addExact(nowMs, RECEIPT_RETENTION_MS),
      ),
    ) != -1L) { "command receipt race" }
  }

  private fun validateReceipt(
    receipt: CommandReceiptEntity,
    commandType: String,
    requestHash: String,
  ) {
    if (receipt.commandType != commandType || receipt.requestHash != requestHash) {
      throw MathMissionException.IdempotencyKeyReused()
    }
  }

  private fun CommandReceiptEntity.toMathAnswerResult(replayed: Boolean): MathAnswerResult {
    val parts = outcomeCode?.split(OUTCOME_SEPARATOR)
      ?: throw MathMissionException.InvalidState()
    if (parts.size != 3) throw MathMissionException.InvalidState()
    val correct = when (parts[0]) {
      CORRECT_OUTCOME -> true
      INCORRECT_OUTCOME -> false
      else -> throw MathMissionException.InvalidState()
    }
    val committedProgress = parts[1].toIntOrNull() ?: throw MathMissionException.InvalidState()
    val completed = parts[2].toBooleanStrictOrNull() ?: throw MathMissionException.InvalidState()
    return MathAnswerResult(
      aggregateId,
      resultRevision,
      correct,
      committedProgress,
      completed,
      promotedInstanceId = null,
      commandId = commandId,
      appliedAtMs = createdAtMs,
      replayed = replayed,
    )
  }

  private fun nowMs() = wallClock.nowEpochMillis().also { require(it >= 0) }

  private companion object {
    const val START_COMMAND_TYPE = "START_MISSION"
    const val ANSWER_COMMAND_TYPE = "SUBMIT_MATH_ANSWER"
    const val STARTED_OUTCOME = "MISSION_STARTED"
    const val CORRECT_OUTCOME = "MATH_CORRECT"
    const val INCORRECT_OUTCOME = "MATH_INCORRECT"
    const val OUTCOME_SEPARATOR = "|"
    const val RECEIPT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
  }
}

private object MathCommandHasher {
  fun start(command: StartMissionCommand) = hash(
    command.instanceId,
    command.expectedRevision.toString(),
  )

  fun answer(command: SubmitMathAnswerCommand) = hash(
    command.instanceId,
    command.expectedRevision.toString(),
    command.questionOrdinal.toString(),
    command.answer.toString(),
  )

  private fun hash(vararg fields: String): String {
    val canonical = fields.joinToString("") { value ->
      "${value.toByteArray(Charsets.UTF_8).size}:$value"
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
  }
}
