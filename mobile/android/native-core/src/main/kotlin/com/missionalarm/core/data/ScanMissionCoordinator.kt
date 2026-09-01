package com.missionalarm.core.data

import com.missionalarm.core.domain.InstanceRuntimeState
import com.missionalarm.core.domain.InstanceState
import com.missionalarm.core.domain.MissionProgress
import com.missionalarm.core.domain.TerminalResult
import com.missionalarm.core.domain.WallClock

data class ScanMissionResult(
  val instanceId: String,
  val instanceRevision: Int,
  val completed: Boolean,
  val promotedInstanceId: String?,
)

sealed class ScanMissionException(message: String) : IllegalStateException(message) {
  class InstanceNotFound : ScanMissionException("instance not found")
  class NotAttended : ScanMissionException("instance is not currently attended")
  class WrongMissionType : ScanMissionException("active mission is not Scan Code")
  class RevisionConflict : ScanMissionException("instance revision changed")
  class InvalidState : ScanMissionException("Scan Code mission is not completable")
}

/** Native authority for starting and completing a scan-any-supported-code mission. */
class ScanMissionCoordinator(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val effectIdGenerator: EffectIdGenerator,
) {
  fun start(instanceId: String): ActiveRuntimeSnapshot =
    database.runInTransaction<ActiveRuntimeSnapshot> {
      val instance = database.runtimeDao().findInstanceById(instanceId)
        ?: throw ScanMissionException.InstanceNotFound()
      val mission = database.runtimeDao().findMission(instanceId)
        ?: throw ScanMissionException.InvalidState()
      validateAttendedScan(instance, mission)
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
          throw ScanMissionException.RevisionConflict()
        }
        expectedRevision = Math.addExact(expectedRevision, 1)
      } else if (instance.runtimeState != "MISSION_LOCKED") {
        throw ScanMissionException.InvalidState()
      }

      if (mission.runtimeStatus == "READY") {
        if (database.runtimeDao().startScanMissionState(instanceId, nowMs) != 1) {
          throw ScanMissionException.InvalidState()
        }
      } else if (mission.runtimeStatus != "IN_PROGRESS") {
        throw ScanMissionException.InvalidState()
      }
      state.startMission().also { check(it.runtime == InstanceRuntimeState.MISSION_IN_PROGRESS) }
      if (database.runtimeDao().startMission(instanceId, expectedRevision, nowMs) != 1) {
        throw ScanMissionException.RevisionConflict()
      }
      activeSnapshot(instanceId)
    }

  fun complete(instanceId: String, expectedRevision: Int): ScanMissionResult =
    database.runInTransaction<ScanMissionResult> {
      val instance = database.runtimeDao().findInstanceById(instanceId)
        ?: throw ScanMissionException.InstanceNotFound()
      val mission = database.runtimeDao().findMission(instanceId)
        ?: throw ScanMissionException.InvalidState()
      if (instance.runtimeState == "TERMINAL" && instance.terminalResult == "SUCCESS" &&
        mission.runtimeStatus == "COMPLETED" &&
        database.runtimeDao().findHistoryByInstanceId(instanceId)?.result == "SUCCESS"
      ) {
        return@runInTransaction ScanMissionResult(
          instanceId,
          instance.revision,
          completed = true,
          promotedInstanceId = database.runtimeDao().findAttendedInstance()?.id,
        )
      }
      validateAttendedScan(instance, mission)
      if (instance.revision != expectedRevision) throw ScanMissionException.RevisionConflict()
      if (instance.runtimeState != "MISSION_IN_PROGRESS" || mission.runtimeStatus != "IN_PROGRESS") {
        throw ScanMissionException.InvalidState()
      }

      val nowMs = nowMs()
      val progress = MissionProgress.restore(mission.target, mission.committedProgress)
        .commitVerified(mission.target)
      InstanceState(InstanceRuntimeState.MISSION_IN_PROGRESS)
        .completeVerified(progress)
        .also { check(it.terminalResult == TerminalResult.SUCCESS) }
      if (database.runtimeDao().completeScanMissionState(instanceId, nowMs) != 1) {
        throw ScanMissionException.InvalidState()
      }
      if (database.runtimeDao().completeVerifiedMission(instanceId, instance.revision, nowMs) != 1) {
        throw ScanMissionException.RevisionConflict()
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
          profileVersion = null,
          createdAtMs = nowMs,
        ),
      )
      insertEffect(instance.id, "STOP_ALARM_RUNTIME", nowMs)

      val promoted = database.runtimeDao().findOldestQueuedInstance()?.also {
        if (database.runtimeDao().promoteQueuedInstance(it.id, it.revision, nowMs) != 1) {
          throw ScanMissionException.InvalidState()
        }
        insertEffect(it.id, "START_ALARM_RUNTIME", nowMs)
        insertEffect(it.id, "PRESENT_ACTIVE_INSTANCE", nowMs)
      }
      ScanMissionResult(
        instanceId,
        Math.addExact(instance.revision, 1),
        completed = true,
        promotedInstanceId = promoted?.id,
      )
    }

  private fun validateAttendedScan(
    instance: AlarmInstanceEntity,
    mission: InstanceMissionEntity,
  ) {
    if (instance.attentionSlot != 1) throw ScanMissionException.NotAttended()
    if (mission.missionType != "QR") throw ScanMissionException.WrongMissionType()
    if (mission.target != 1) throw ScanMissionException.InvalidState()
  }

  private fun activeSnapshot(instanceId: String): ActiveRuntimeSnapshot {
    val snapshot = database.runtimeDao().loadActiveRuntimeSnapshot()
      ?: throw ScanMissionException.InvalidState()
    if (snapshot.instanceId != instanceId) throw ScanMissionException.NotAttended()
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
    ) != -1L) { "duplicate Scan Code completion effect identity" }
  }

  private fun nowMs(): Long = wallClock.nowEpochMillis().also {
    require(it >= 0) { "wall clock must not predate epoch" }
  }
}
