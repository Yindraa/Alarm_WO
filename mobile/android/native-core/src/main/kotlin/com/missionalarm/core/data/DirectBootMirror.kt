package com.missionalarm.core.data

import com.missionalarm.core.domain.WallClock

data class BootScheduleSnapshot(
  val occurrenceId: String,
  val scheduledAtUtcMs: Long,
  val soundId: String,
  val missionType: String,
  val target: Int,
  val alarmRevision: Int,
)

fun interface DirectBootMirrorStore {
  fun rebuild(schedules: List<BootScheduleSnapshot>, updatedAtMs: Long)
}

class RoomDirectBootMirrorStore(private val database: DirectBootDatabase) : DirectBootMirrorStore {
  override fun rebuild(schedules: List<BootScheduleSnapshot>, updatedAtMs: Long) {
    require(updatedAtMs >= 0)
    require(schedules.map { it.occurrenceId }.distinct().size == schedules.size)
    database.runInTransaction {
      val dao = database.directBootDao()
      val existing = dao.findAllSchedules().associateBy { it.occurrenceId }
      val pendingJournalOccurrences = dao.findPendingJournal().mapTo(mutableSetOf()) { it.occurrenceId }
      val desiredIds = schedules.mapTo(mutableSetOf()) { it.occurrenceId }
      existing.keys.filterNot(desiredIds::contains).forEach(dao::deleteSchedule)
      schedules.forEach { snapshot ->
        val revision = Math.addExact(existing[snapshot.occurrenceId]?.mirrorRevision ?: 0L, 1L)
        dao.upsertSchedule(
          BootScheduleEntity(
            occurrenceId = snapshot.occurrenceId,
            dedupeKey = "occurrence:${snapshot.occurrenceId}",
            scheduledAtUtcMs = snapshot.scheduledAtUtcMs,
            soundId = snapshot.soundId,
            missionType = snapshot.missionType,
            target = snapshot.target,
            alarmRevision = snapshot.alarmRevision,
            mirrorRevision = revision,
            state = if (
              existing[snapshot.occurrenceId]?.state == "FIRED" &&
              snapshot.occurrenceId in pendingJournalOccurrences
            ) {
              "FIRED"
            } else {
              "ACTIVE"
            },
            updatedAtMs = updatedAtMs,
          ),
        )
      }
    }
  }
}

class DirectBootMirrorEffectRunner(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val leaseOwnerGenerator: LeaseOwnerGenerator,
  private val mirrorStore: DirectBootMirrorStore,
) {
  fun drain(maxEffects: Int = 16): Int {
    require(maxEffects in 1..64)
    val owner = leaseOwnerGenerator.next().also { require(it.isNotBlank()) }
    var processed = 0
    while (processed < maxEffects) {
      val now = nowMs()
      val effect = database.reliabilityDao().claimNext(EFFECT_TYPE, owner, now, 30_000L) ?: break
      if (!valid(effect)) {
        check(database.reliabilityDao().deadLetter(effect.id, owner, INVALID_PAYLOAD, nowMs()) == 1)
        processed += 1
        continue
      }
      try {
        val desired = database.alarmDao().findAllEnabled().mapNotNull { stored ->
          val occurrence = database.runtimeDao().findNextOccurrence(stored.alarm.id)
            ?: return@mapNotNull null
          BootScheduleSnapshot(
            occurrence.id,
            occurrence.scheduledAtUtcMs,
            stored.alarm.soundId,
            stored.mission.missionType,
            stored.mission.target,
            stored.alarm.revision,
          )
        }
        mirrorStore.rebuild(desired, nowMs())
        check(database.reliabilityDao().acknowledge(effect.id, owner, nowMs()) == 1)
      } catch (_: RuntimeException) {
        val failedAt = nowMs()
        if (effect.attemptCount >= 5) {
          check(database.reliabilityDao().deadLetter(effect.id, owner, RETRY_EXHAUSTED, failedAt) == 1)
        } else {
          val delay = (1_000L shl (effect.attemptCount - 1).coerceAtLeast(0)).coerceAtMost(300_000L)
          check(database.reliabilityDao().retry(effect.id, owner, failedAt + delay, TRANSIENT, failedAt) == 1)
        }
      }
      processed += 1
    }
    return processed
  }

  private fun valid(effect: RuntimeEffectEntity): Boolean {
    if (effect.effectType != EFFECT_TYPE || effect.aggregateType != "ALARM" || effect.payloadVersion != 1) return false
    val match = PAYLOAD.matchEntire(effect.payloadJson) ?: return false
    return match.groupValues[1] == effect.aggregateId && match.groupValues[2].toIntOrNull() != null
  }

  private fun nowMs() = wallClock.nowEpochMillis().also { require(it >= 0) }

  private companion object {
    const val EFFECT_TYPE = "SYNC_DIRECT_BOOT_MIRROR"
    const val INVALID_PAYLOAD = "INVALID_EFFECT_PAYLOAD"
    const val TRANSIENT = "DIRECT_BOOT_MIRROR_TRANSIENT_FAILURE"
    const val RETRY_EXHAUSTED = "DIRECT_BOOT_MIRROR_RETRY_EXHAUSTED"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    val PAYLOAD = Regex("""^\{"alarmId":"($UUID)","alarmRevision":([1-9][0-9]{0,9}),"occurrenceId":("$UUID"|null)\}$""")
  }
}
