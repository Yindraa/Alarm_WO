package com.missionalarm.core.data

import com.missionalarm.core.domain.InstanceRuntimeState
import com.missionalarm.core.domain.InstanceState
import com.missionalarm.core.domain.MissionProgress
import com.missionalarm.core.domain.TerminalResult
import com.missionalarm.core.domain.WallClock

data class MathAnswerResult(
  val instanceId: String,
  val instanceRevision: Int,
  val correct: Boolean,
  val committedProgress: Int,
  val completed: Boolean,
  val promotedInstanceId: String?,
)

sealed class MathMissionException(message: String) : IllegalStateException(message) {
  class InstanceNotFound : MathMissionException("instance not found")
  class NotAttended : MathMissionException("instance is not currently attended")
  class WrongMissionType : MathMissionException("active mission is not Math")
  class QuestionNotFound : MathMissionException("Math question not found")
  class StaleQuestion : MathMissionException("Math question is no longer current")
  class RevisionConflict : MathMissionException("instance revision changed")
  class InvalidState : MathMissionException("Math mission is not answerable")
}

/** Native authority for starting and committing deterministic Math mission progress. */
class MathMissionCoordinator(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val effectIdGenerator: EffectIdGenerator,
) {
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

  private fun nowMs() = wallClock.nowEpochMillis().also { require(it >= 0) }
}
