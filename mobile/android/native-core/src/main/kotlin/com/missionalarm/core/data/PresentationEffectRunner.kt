package com.missionalarm.core.data

import com.missionalarm.core.domain.WallClock

fun interface AlarmHostPresenter {
  @Throws(PermanentAlarmPresentationException::class)
  fun present(instanceId: String)
}

class PermanentAlarmPresentationException :
  IllegalStateException("alarm presentation request is invalid")

/** Executes only PRESENT_ACTIVE_INSTANCE and never consumes other reliability effects. */
class PresentationEffectRunner(
  private val database: MissionAlarmDatabase,
  private val wallClock: WallClock,
  private val leaseOwnerGenerator: LeaseOwnerGenerator,
  private val presenter: AlarmHostPresenter,
) {
  fun drain(maxEffects: Int = DEFAULT_MAX_DRAIN): Int {
    require(maxEffects in 1..MAX_DRAIN_LIMIT)
    val owner = leaseOwnerGenerator.next().also { require(it.isNotBlank()) }
    var processed = 0
    while (processed < maxEffects) {
      val effect = database.reliabilityDao().claimNext(
        EFFECT_TYPE,
        owner,
        nowMs(),
        LEASE_DURATION_MS,
      ) ?: break
      val instanceId = decode(effect)
      if (instanceId == null) {
        deadLetter(effect, owner, INVALID_PAYLOAD)
        processed += 1
        continue
      }
      val instance = database.runtimeDao().findInstanceById(instanceId)
      if (instance == null || (instance.runtimeState != "TERMINAL" && instance.attentionSlot != 1)) {
        deadLetter(effect, owner, INVALID_RUNTIME_STATE)
        processed += 1
        continue
      }
      if (instance.runtimeState == "TERMINAL") {
        acknowledge(effect, owner)
        processed += 1
        continue
      }
      try {
        presenter.present(instanceId)
        acknowledge(effect, owner)
      } catch (_: PermanentAlarmPresentationException) {
        deadLetter(effect, owner, PERMANENT_FAILURE)
      } catch (_: RuntimeException) {
        retryOrDeadLetter(effect, owner)
      }
      processed += 1
    }
    return processed
  }

  private fun decode(effect: RuntimeEffectEntity): String? {
    if (effect.effectType != EFFECT_TYPE || effect.aggregateType != "INSTANCE" || effect.payloadVersion != 1) {
      return null
    }
    val match = PAYLOAD.matchEntire(effect.payloadJson) ?: return null
    return match.groupValues[1].takeIf { it == effect.aggregateId }
  }

  private fun acknowledge(effect: RuntimeEffectEntity, owner: String) {
    check(database.reliabilityDao().acknowledge(effect.id, owner, nowMs()) == 1) {
      "presentation effect lease was lost"
    }
  }

  private fun retryOrDeadLetter(effect: RuntimeEffectEntity, owner: String) {
    val failedAt = nowMs()
    if (effect.attemptCount >= MAX_ATTEMPTS) {
      check(database.reliabilityDao().deadLetter(
        effect.id,
        owner,
        RETRY_EXHAUSTED,
        failedAt,
      ) == 1)
      return
    }
    val delay = (INITIAL_RETRY_MS shl (effect.attemptCount - 1).coerceAtLeast(0))
      .coerceAtMost(MAX_RETRY_MS)
    check(database.reliabilityDao().retry(
      effect.id,
      owner,
      Math.addExact(failedAt, delay),
      TRANSIENT_FAILURE,
      failedAt,
    ) == 1)
  }

  private fun deadLetter(effect: RuntimeEffectEntity, owner: String, errorCode: String) {
    check(database.reliabilityDao().deadLetter(effect.id, owner, errorCode, nowMs()) == 1)
  }

  private fun nowMs() = wallClock.nowEpochMillis().also { require(it >= 0) }

  private companion object {
    const val EFFECT_TYPE = "PRESENT_ACTIVE_INSTANCE"
    const val LEASE_DURATION_MS = 30_000L
    const val INITIAL_RETRY_MS = 1_000L
    const val MAX_RETRY_MS = 300_000L
    const val MAX_ATTEMPTS = 5
    const val DEFAULT_MAX_DRAIN = 16
    const val MAX_DRAIN_LIMIT = 64
    const val INVALID_PAYLOAD = "INVALID_EFFECT_PAYLOAD"
    const val INVALID_RUNTIME_STATE = "INVALID_RUNTIME_STATE"
    const val PERMANENT_FAILURE = "ALARM_PRESENTATION_PERMANENT_FAILURE"
    const val TRANSIENT_FAILURE = "ALARM_PRESENTATION_TRANSIENT_FAILURE"
    const val RETRY_EXHAUSTED = "ALARM_PRESENTATION_RETRY_EXHAUSTED"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    val PAYLOAD = Regex("""^\{\"instanceId\":\"($UUID)\"\}$""")
  }
}
