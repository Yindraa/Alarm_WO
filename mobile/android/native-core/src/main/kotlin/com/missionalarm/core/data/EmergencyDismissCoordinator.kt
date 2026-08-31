package com.missionalarm.core.data

import com.missionalarm.core.domain.InstanceRuntimeState
import com.missionalarm.core.domain.InstanceState
import com.missionalarm.core.domain.TerminalResult
import com.missionalarm.core.domain.WallClock

data class EmergencyDismissResult(
  val dismissedInstanceId: String,
  val terminalRevision: Int,
  val promotedInstanceId: String?,
  val replayed: Boolean,
)

sealed class EmergencyDismissException(message: String) : IllegalStateException(message) {
  class InstanceNotFound : EmergencyDismissException("instance not found")
  class NotAttended : EmergencyDismissException("instance is not currently attended")
  class AlreadyTerminal : EmergencyDismissException("instance has a different terminal result")
  class StateConflict : EmergencyDismissException("instance state changed during dismissal")
}

class EmergencyDismissCoordinator(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val effectIdGenerator: EffectIdGenerator,
) {
  fun dismiss(instanceId: String): EmergencyDismissResult =
    database.runInTransaction<EmergencyDismissResult> {
      val instance = database.runtimeDao().findInstanceById(instanceId)
        ?: throw EmergencyDismissException.InstanceNotFound()
      if (instance.runtimeState == "TERMINAL") {
        if (instance.terminalResult != "EMERGENCY_DISMISSED") {
          throw EmergencyDismissException.AlreadyTerminal()
        }
        return@runInTransaction EmergencyDismissResult(
          instance.id,
          instance.revision,
          database.runtimeDao().findAttendedInstance()?.id,
          replayed = true,
        )
      }
      if (instance.attentionSlot != 1) throw EmergencyDismissException.NotAttended()
      InstanceState(
        runtime = InstanceRuntimeState.valueOf(instance.runtimeState),
      ).emergencyDismiss().also {
        check(it.terminalResult == TerminalResult.EMERGENCY_DISMISSED)
      }
      val mission = database.runtimeDao().findMission(instance.id)
        ?: throw EmergencyDismissException.StateConflict()
      val nowMs = wallClock.nowEpochMillis().also { require(it >= 0) }
      if (database.runtimeDao().markEmergencyDismissed(instance.id, instance.revision, nowMs) != 1) {
        throw EmergencyDismissException.StateConflict()
      }
      val terminalRevision = Math.addExact(instance.revision, 1)
      database.runtimeDao().insertHistory(
        AlarmHistoryEntity(
          instanceId = instance.id,
          scheduledAtUtcMs = instance.scheduledAtUtcMs,
          actualTriggerAtMs = instance.actualTriggerAtMs,
          endedAtMs = nowMs,
          completionDurationMs = instance.actualTriggerAtMs?.let { maxOf(0, nowMs - it) },
          missionType = mission.missionType,
          target = mission.target,
          finalProgress = mission.committedProgress,
          result = "EMERGENCY_DISMISSED",
          dismissMethod = "EMERGENCY_HOLD",
          errorReasonCode = null,
          engineVersion = mission.engineVersion,
          profileVersion = mission.pushupProfileVersion ?: mission.mathGeneratorVersion,
          createdAtMs = nowMs,
        ),
      )
      insertEffect(instance.id, "STOP_ALARM_RUNTIME", nowMs)

      val queued = database.runtimeDao().findOldestQueuedInstance()
      val promoted = queued?.also {
        if (database.runtimeDao().promoteQueuedInstance(it.id, it.revision, nowMs) != 1) {
          throw EmergencyDismissException.StateConflict()
        }
        insertEffect(it.id, "START_ALARM_RUNTIME", nowMs)
        insertEffect(it.id, "PRESENT_ACTIVE_INSTANCE", nowMs)
      }
      EmergencyDismissResult(instance.id, terminalRevision, promoted?.id, replayed = false)
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
    ) != -1L) { "duplicate emergency effect identity" }
  }
}
