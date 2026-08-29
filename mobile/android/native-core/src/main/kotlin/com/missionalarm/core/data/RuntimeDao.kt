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

  @Query("SELECT * FROM alarm_instance WHERE occurrence_id = :occurrenceId")
  abstract fun findInstanceByOccurrence(occurrenceId: String): AlarmInstanceEntity?

  @Query("SELECT * FROM instance_mission WHERE instance_id = :instanceId")
  abstract fun findMission(instanceId: String): InstanceMissionEntity?

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
}

@Dao
abstract class ReliabilityDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract fun insertEffect(effect: RuntimeEffectEntity): Long

  @Query("SELECT * FROM runtime_effect WHERE effect_key = :effectKey")
  abstract fun findEffect(effectKey: String): RuntimeEffectEntity?

  @Query("SELECT * FROM runtime_effect WHERE id = :effectId")
  abstract fun findEffectById(effectId: String): RuntimeEffectEntity?

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
}
