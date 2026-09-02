package com.missionalarm.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class RuntimeDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  abstract fun insertOccurrence(occurrence: AlarmOccurrenceEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  protected abstract fun insertInstance(instance: AlarmInstanceEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  protected abstract fun insertMission(mission: InstanceMissionEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  abstract fun insertHistory(history: AlarmHistoryEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  abstract fun insertMathQuestions(questions: List<MathQuestionEntity>)

  @Query("SELECT * FROM alarm_instance WHERE occurrence_id = :occurrenceId")
  abstract fun findInstanceByOccurrence(occurrenceId: String): AlarmInstanceEntity?

  @Query("SELECT * FROM alarm_instance WHERE id = :instanceId")
  abstract fun findInstanceById(instanceId: String): AlarmInstanceEntity?

  @Query("SELECT * FROM alarm_history WHERE instance_id = :instanceId")
  abstract fun findHistoryByInstanceId(instanceId: String): AlarmHistoryEntity?

  @Query(
    """
    SELECT * FROM alarm_history
    ORDER BY ended_at_ms DESC, instance_id DESC
    LIMIT :limit
    """,
  )
  abstract fun findRecentHistory(limit: Int): List<AlarmHistoryEntity>

  @Query("SELECT * FROM instance_mission WHERE instance_id = :instanceId")
  abstract fun findMission(instanceId: String): InstanceMissionEntity?

  @Query(
    """
    SELECT ordinal, operation, operand_a, operand_b
    FROM math_question
    WHERE instance_id = :instanceId AND answered = 0
    ORDER BY ordinal
    LIMIT 1
    """,
  )
  abstract fun findCurrentMathQuestion(instanceId: String): MathQuestionPrompt?

  @Query(
    """
    SELECT * FROM math_question
    WHERE instance_id = :instanceId AND ordinal = :ordinal
    """,
  )
  abstract fun findMathQuestion(instanceId: String, ordinal: Int): MathQuestionEntity?

  @Query(
    """
    UPDATE alarm_instance
    SET revision = revision + 1, runtime_state = 'MISSION_LOCKED', updated_at_ms = :updatedAtMs
    WHERE id = :instanceId AND revision = :expectedRevision AND attention_slot = 1
      AND runtime_state = 'TRIGGERED'
    """,
  )
  abstract fun lockMission(
    instanceId: String,
    expectedRevision: Int,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE instance_mission
    SET runtime_status = 'IN_PROGRESS', updated_at_ms = :updatedAtMs
    WHERE instance_id = :instanceId AND mission_type = 'MATH' AND runtime_status = 'READY'
    """,
  )
  abstract fun startMathMissionState(instanceId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE instance_mission
    SET runtime_status = 'IN_PROGRESS', updated_at_ms = :updatedAtMs
    WHERE instance_id = :instanceId AND mission_type = 'QR' AND runtime_status = 'READY'
    """,
  )
  abstract fun startScanMissionState(instanceId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE instance_mission
    SET runtime_status = 'IN_PROGRESS', updated_at_ms = :updatedAtMs
    WHERE instance_id = :instanceId AND mission_type = 'PUSH_UP' AND runtime_status = 'READY'
    """,
  )
  abstract fun startPushUpMissionState(instanceId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE alarm_instance
    SET revision = revision + 1, runtime_state = 'MISSION_IN_PROGRESS', updated_at_ms = :updatedAtMs
    WHERE id = :instanceId AND revision = :expectedRevision AND attention_slot = 1
      AND runtime_state = 'MISSION_LOCKED'
    """,
  )
  abstract fun startMission(
    instanceId: String,
    expectedRevision: Int,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE math_question
    SET answered = 1, answered_at_ms = :answeredAtMs
    WHERE instance_id = :instanceId AND ordinal = :ordinal AND answered = 0
    """,
  )
  abstract fun markMathQuestionAnswered(
    instanceId: String,
    ordinal: Int,
    answeredAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE instance_mission
    SET committed_progress = :nextProgress, runtime_status = :runtimeStatus,
      updated_at_ms = :updatedAtMs
    WHERE instance_id = :instanceId AND mission_type = 'MATH'
      AND committed_progress = :expectedProgress AND runtime_status = 'IN_PROGRESS'
    """,
  )
  abstract fun advanceMathProgress(
    instanceId: String,
    expectedProgress: Int,
    nextProgress: Int,
    runtimeStatus: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE instance_mission
    SET committed_progress = :nextProgress, runtime_status = :runtimeStatus,
      updated_at_ms = :updatedAtMs
    WHERE instance_id = :instanceId AND mission_type = 'PUSH_UP'
      AND committed_progress = :expectedProgress AND runtime_status = 'IN_PROGRESS'
    """,
  )
  abstract fun advancePushUpProgress(
    instanceId: String,
    expectedProgress: Int,
    nextProgress: Int,
    runtimeStatus: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE instance_mission
    SET committed_progress = target, runtime_status = 'COMPLETED', updated_at_ms = :updatedAtMs
    WHERE instance_id = :instanceId AND mission_type = 'QR' AND target = 1
      AND committed_progress = 0 AND runtime_status = 'IN_PROGRESS'
    """,
  )
  abstract fun completeScanMissionState(instanceId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE alarm_instance
    SET revision = revision + 1, updated_at_ms = :updatedAtMs
    WHERE id = :instanceId AND revision = :expectedRevision AND attention_slot = 1
      AND runtime_state = 'MISSION_IN_PROGRESS'
    """,
  )
  abstract fun commitMissionProgressRevision(
    instanceId: String,
    expectedRevision: Int,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_instance
    SET revision = revision + 1, runtime_state = 'TERMINAL', attention_slot = NULL,
      terminal_at_ms = :terminalAtMs, terminal_result = 'SUCCESS',
      dismiss_method = 'VERIFIED_MISSION', error_reason_code = NULL,
      updated_at_ms = :terminalAtMs
    WHERE id = :instanceId AND revision = :expectedRevision AND attention_slot = 1
      AND runtime_state = 'MISSION_IN_PROGRESS'
    """,
  )
  abstract fun completeVerifiedMission(
    instanceId: String,
    expectedRevision: Int,
    terminalAtMs: Long,
  ): Int

  @Query(
    """
    SELECT COUNT(*) FROM alarm_instance
    WHERE runtime_state = 'PENDING_ATTENTION' AND attention_slot IS NULL
    """,
  )
  abstract fun countQueuedInstances(): Int

  @Query(
    """
    SELECT * FROM alarm_instance
    WHERE runtime_state = 'PENDING_ATTENTION' AND attention_slot IS NULL
    ORDER BY queue_order, scheduled_at_utc_ms, created_at_ms, id
    LIMIT 1
    """,
  )
  abstract fun findOldestQueuedInstance(): AlarmInstanceEntity?

  @Query(
    """
    UPDATE alarm_instance
    SET revision = revision + 1, runtime_state = 'TERMINAL', attention_slot = NULL,
      terminal_at_ms = :terminalAtMs, terminal_result = 'EMERGENCY_DISMISSED',
      dismiss_method = 'EMERGENCY_HOLD', error_reason_code = NULL, updated_at_ms = :terminalAtMs
    WHERE id = :instanceId AND revision = :expectedRevision AND attention_slot = 1
      AND runtime_state IN ('TRIGGERED', 'MISSION_LOCKED', 'MISSION_IN_PROGRESS', 'RECOVERY_REQUIRED')
    """,
  )
  abstract fun markEmergencyDismissed(
    instanceId: String,
    expectedRevision: Int,
    terminalAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_instance
    SET revision = revision + 1, runtime_state = 'TRIGGERED', attention_slot = 1,
      updated_at_ms = :updatedAtMs
    WHERE id = :instanceId AND revision = :expectedRevision
      AND runtime_state = 'PENDING_ATTENTION' AND attention_slot IS NULL
      AND NOT EXISTS (
        SELECT 1 FROM alarm_instance
        WHERE attention_slot = 1 AND runtime_state <> 'TERMINAL'
      )
    """,
  )
  abstract fun promoteQueuedInstance(
    instanceId: String,
    expectedRevision: Int,
    updatedAtMs: Long,
  ): Int

  @Query("SELECT * FROM alarm_occurrence WHERE id = :occurrenceId")
  abstract fun findOccurrenceById(occurrenceId: String): AlarmOccurrenceEntity?

  @Query("SELECT COALESCE(MAX(queue_order), 0) + 1 FROM alarm_instance")
  abstract fun nextQueueOrder(): Long

  @Query(
    """
    SELECT COUNT(*) FROM alarm_instance
    WHERE attention_slot = 1 AND runtime_state <> 'TERMINAL'
    """,
  )
  abstract fun countAttendedInstances(): Int

  @Query(
    """
    SELECT * FROM alarm_instance
    WHERE attention_slot = 1 AND runtime_state <> 'TERMINAL'
    LIMIT 1
    """,
  )
  abstract fun findAttendedInstance(): AlarmInstanceEntity?

  @Transaction
  open fun loadActiveRuntimeSnapshot(): ActiveRuntimeSnapshot? {
    val instance = findAttendedInstance() ?: return null
    val mission = checkNotNull(findMission(instance.id)) { "active instance mission is missing" }
    return ActiveRuntimeSnapshot(
      instanceId = instance.id,
      revision = instance.revision,
      runtimeState = instance.runtimeState,
      label = instance.labelSnapshot,
      occurrenceId = instance.occurrenceId,
      scheduledAtUtcMs = instance.scheduledAtUtcMs,
      actualTriggerAtMs = instance.actualTriggerAtMs,
      missionType = mission.missionType,
      target = mission.target,
      committedProgress = mission.committedProgress,
      missionRuntimeStatus = mission.runtimeStatus,
      mathQuestion = if (mission.missionType == "MATH") {
        findCurrentMathQuestion(instance.id)
      } else {
        null
      },
      queuedCount = countQueuedInstances(),
    )
  }

  @Query(
    """
    SELECT * FROM alarm_occurrence
    WHERE alarm_id = :alarmId AND state IN ('PENDING_OS', 'SCHEDULED_OS')
    ORDER BY scheduled_at_utc_ms, id
    """,
  )
  abstract fun findSchedulableOccurrences(alarmId: String): List<AlarmOccurrenceEntity>

  @Query(
    """
    SELECT * FROM alarm_occurrence
    WHERE state = 'SCHEDULED_OS'
    ORDER BY scheduled_at_utc_ms, id
    """,
  )
  abstract fun findScheduledOccurrences(): List<AlarmOccurrenceEntity>

  @Query(
    """
    UPDATE alarm_occurrence
    SET state = 'CANCELLED', last_error_code = NULL, updated_at_ms = :updatedAtMs
    WHERE id = :occurrenceId AND state IN ('PENDING_OS', 'SCHEDULED_OS')
    """,
  )
  abstract fun markOccurrenceCancelled(occurrenceId: String, updatedAtMs: Long): Int

  @Query(
    """
    SELECT COUNT(*) FROM alarm_instance
    WHERE alarm_id = :alarmId AND runtime_state <> 'TERMINAL'
    """,
  )
  abstract fun countNonTerminalInstances(alarmId: String): Int

  @Query(
    """
    UPDATE alarm_instance
    SET alarm_id = NULL, revision = revision + 1, updated_at_ms = :updatedAtMs
    WHERE alarm_id = :alarmId AND runtime_state = 'TERMINAL'
    """,
  )
  abstract fun detachTerminalInstancesFromAlarm(alarmId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE alarm_occurrence
    SET alarm_id = NULL, updated_at_ms = :updatedAtMs
    WHERE alarm_id = :alarmId
    """,
  )
  abstract fun detachOccurrencesFromAlarm(alarmId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE alarm_occurrence
    SET state = 'SCHEDULED_OS', last_error_code = NULL, updated_at_ms = :updatedAtMs
    WHERE id = :occurrenceId AND state IN ('PENDING_OS', 'SCHEDULED_OS')
    """,
  )
  abstract fun markOccurrenceScheduled(occurrenceId: String, updatedAtMs: Long): Int

  @Query(
    """
    UPDATE alarm_occurrence
    SET state = 'PENDING_OS', last_error_code = :errorCode, updated_at_ms = :updatedAtMs
    WHERE id = :occurrenceId AND state IN ('PENDING_OS', 'SCHEDULED_OS')
    """,
  )
  abstract fun markOccurrenceSchedulingError(
    occurrenceId: String,
    errorCode: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_occurrence
    SET state = 'PENDING_OS', last_error_code = :errorCode, updated_at_ms = :updatedAtMs
    WHERE state = 'SCHEDULED_OS'
    """,
  )
  abstract fun markScheduledOccurrencesCapabilityBlocked(
    errorCode: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_occurrence
    SET state = 'FAILED', last_error_code = :errorCode, updated_at_ms = :updatedAtMs
    WHERE id = :occurrenceId AND state IN ('PENDING_OS', 'SCHEDULED_OS')
    """,
  )
  abstract fun markOccurrenceSchedulingFailed(
    occurrenceId: String,
    errorCode: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_occurrence
    SET state = 'FIRED', updated_at_ms = :updatedAtMs
    WHERE id = :occurrenceId
      AND state IN ('PENDING_OS', 'SCHEDULED_OS', 'FIRED')
    """,
  )
  protected abstract fun markOccurrenceFired(occurrenceId: String, updatedAtMs: Long): Int

  @Transaction
  open fun getOrCreateTriggeredInstance(
    instance: AlarmInstanceEntity,
    mission: InstanceMissionEntity,
  ): AlarmInstanceEntity {
    findInstanceByOccurrence(instance.occurrenceId)?.let { return it }
    require(instance.id == mission.instanceId) { "instance and mission identity must match" }
    check(markOccurrenceFired(instance.occurrenceId, instance.updatedAtMs) == 1) {
      "occurrence is missing or cannot be fired"
    }
    insertInstance(instance)
    insertMission(mission)
    return instance
  }

  @Query(
    """
    SELECT id FROM alarm_occurrence
    WHERE state IN ('PENDING_OS', 'SCHEDULED_OS')
      AND scheduled_at_utc_ms <= :nowMs
    ORDER BY scheduled_at_utc_ms, id
    """,
  )
  abstract fun findDueOccurrenceIds(nowMs: Long): List<String>

  @Query(
    """
    SELECT * FROM alarm_occurrence
    WHERE alarm_id = :alarmId AND state IN ('PENDING_OS', 'SCHEDULED_OS')
    ORDER BY scheduled_at_utc_ms, id
    LIMIT 1
    """,
  )
  abstract fun findNextOccurrence(alarmId: String): AlarmOccurrenceEntity?
}

data class MathQuestionPrompt(
  val ordinal: Int,
  val operation: String,
  @androidx.room.ColumnInfo(name = "operand_a") val operandA: Int,
  @androidx.room.ColumnInfo(name = "operand_b") val operandB: Int,
)

data class ActiveRuntimeSnapshot(
  val instanceId: String,
  val occurrenceId: String,
  val revision: Int,
  val runtimeState: String,
  val label: String,
  val scheduledAtUtcMs: Long,
  val actualTriggerAtMs: Long?,
  val missionType: String,
  val target: Int,
  val committedProgress: Int,
  val missionRuntimeStatus: String,
  val mathQuestion: MathQuestionPrompt?,
  val queuedCount: Int,
)

@Dao
abstract class ReliabilityDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract fun insertEffect(effect: RuntimeEffectEntity): Long

  @Query("SELECT * FROM runtime_effect WHERE effect_key = :effectKey")
  abstract fun findEffect(effectKey: String): RuntimeEffectEntity?

  @Query("SELECT * FROM runtime_effect WHERE id = :effectId")
  abstract fun findEffectById(effectId: String): RuntimeEffectEntity?

  @Query(
    """
    SELECT COUNT(*) FROM runtime_effect
    WHERE effect_type = :effectType AND status <> 'ACKNOWLEDGED'
    """,
  )
  abstract fun countUnacknowledgedEffects(effectType: String): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract fun insertReceipt(receipt: CommandReceiptEntity): Long

  @Query("SELECT * FROM command_receipt WHERE command_id = :commandId")
  abstract fun findReceipt(commandId: String): CommandReceiptEntity?

  @Query("DELETE FROM command_receipt WHERE expires_at_ms <= :nowMs")
  abstract fun deleteExpiredReceipts(nowMs: Long): Int

  @Query(
    """
    SELECT id FROM runtime_effect
    WHERE (
      (status IN ('PENDING', 'RETRYABLE') AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :nowMs))
      OR (status = 'LEASED' AND lease_until_ms <= :nowMs)
    )
    ORDER BY created_at_ms, id
    LIMIT 1
    """,
  )
  protected abstract fun findClaimCandidateId(nowMs: Long): String?

  @Query(
    """
    SELECT id FROM runtime_effect
    WHERE effect_type = :effectType AND (
      (status IN ('PENDING', 'RETRYABLE') AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :nowMs))
      OR (status = 'LEASED' AND lease_until_ms <= :nowMs)
    )
    ORDER BY created_at_ms, id
    LIMIT 1
    """,
  )
  protected abstract fun findClaimCandidateId(effectType: String, nowMs: Long): String?

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'LEASED', lease_owner = :owner, lease_until_ms = :leaseUntilMs,
      attempt_count = attempt_count + 1, updated_at_ms = :nowMs
    WHERE id = :effectId AND (
      (status IN ('PENDING', 'RETRYABLE') AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :nowMs))
      OR (status = 'LEASED' AND lease_until_ms <= :nowMs)
    )
    """,
  )
  protected abstract fun acquireLease(
    effectId: String,
    owner: String,
    nowMs: Long,
    leaseUntilMs: Long,
  ): Int

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'LEASED', lease_owner = :owner, lease_until_ms = :leaseUntilMs,
      attempt_count = attempt_count + 1, updated_at_ms = :nowMs
    WHERE id = :effectId AND effect_type = :effectType AND (
      (status IN ('PENDING', 'RETRYABLE') AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :nowMs))
      OR (status = 'LEASED' AND lease_until_ms <= :nowMs)
    )
    """,
  )
  protected abstract fun acquireLease(
    effectId: String,
    effectType: String,
    owner: String,
    nowMs: Long,
    leaseUntilMs: Long,
  ): Int

  @Transaction
  open fun claimNext(owner: String, nowMs: Long, leaseDurationMs: Long): RuntimeEffectEntity? {
    require(owner.isNotBlank()) { "lease owner must not be blank" }
    require(leaseDurationMs > 0 && nowMs <= Long.MAX_VALUE - leaseDurationMs) {
      "lease duration must be positive and bounded"
    }
    val effectId = findClaimCandidateId(nowMs) ?: return null
    val leaseUntilMs = nowMs + leaseDurationMs
    check(acquireLease(effectId, owner, nowMs, leaseUntilMs) == 1) { "effect lease race" }
    return checkNotNull(findEffectById(effectId))
  }

  @Transaction
  open fun claimNext(
    effectType: String,
    owner: String,
    nowMs: Long,
    leaseDurationMs: Long,
  ): RuntimeEffectEntity? {
    require(effectType.isNotBlank()) { "effect type must not be blank" }
    require(owner.isNotBlank()) { "lease owner must not be blank" }
    require(leaseDurationMs > 0 && nowMs <= Long.MAX_VALUE - leaseDurationMs) {
      "lease duration must be positive and bounded"
    }
    val effectId = findClaimCandidateId(effectType, nowMs) ?: return null
    val leaseUntilMs = nowMs + leaseDurationMs
    check(acquireLease(effectId, effectType, owner, nowMs, leaseUntilMs) == 1) {
      "effect lease race"
    }
    return checkNotNull(findEffectById(effectId))
  }

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'ACKNOWLEDGED', lease_owner = NULL, lease_until_ms = NULL,
      acknowledged_at_ms = :acknowledgedAtMs, updated_at_ms = :acknowledgedAtMs
    WHERE id = :effectId AND status = 'LEASED' AND lease_owner = :owner
    """,
  )
  abstract fun acknowledge(effectId: String, owner: String, acknowledgedAtMs: Long): Int

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'RETRYABLE', lease_owner = NULL, lease_until_ms = NULL,
      next_attempt_at_ms = :nextAttemptAtMs, last_error_code = :errorCode,
      updated_at_ms = :updatedAtMs
    WHERE id = :effectId AND status = 'LEASED' AND lease_owner = :owner
    """,
  )
  abstract fun retry(
    effectId: String,
    owner: String,
    nextAttemptAtMs: Long,
    errorCode: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'BLOCKED_CAPABILITY', lease_owner = NULL, lease_until_ms = NULL,
      next_attempt_at_ms = NULL, last_error_code = :errorCode, updated_at_ms = :updatedAtMs
    WHERE id = :effectId AND status = 'LEASED' AND lease_owner = :owner
    """,
  )
  abstract fun blockCapability(
    effectId: String,
    owner: String,
    errorCode: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'RETRYABLE', lease_owner = NULL, lease_until_ms = NULL,
      next_attempt_at_ms = :updatedAtMs, updated_at_ms = :updatedAtMs
    WHERE effect_type = 'SCHEDULE_OCCURRENCE'
      AND status = 'BLOCKED_CAPABILITY'
      AND last_error_code = :errorCode
    """,
  )
  abstract fun releaseSchedulesAfterCapabilityRecovery(
    errorCode: String,
    updatedAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE runtime_effect
    SET status = 'DEAD_LETTER', lease_owner = NULL, lease_until_ms = NULL,
      next_attempt_at_ms = NULL, last_error_code = :errorCode, updated_at_ms = :updatedAtMs
    WHERE id = :effectId AND status = 'LEASED' AND lease_owner = :owner
    """,
  )
  abstract fun deadLetter(
    effectId: String,
    owner: String,
    errorCode: String,
    updatedAtMs: Long,
  ): Int
}
