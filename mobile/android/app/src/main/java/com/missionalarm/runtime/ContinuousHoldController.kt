package com.missionalarm.runtime

fun interface MonotonicClock {
  fun nowMs(): Long
}

data class HoldProgress(
  val elapsedMs: Long,
  val requiredMs: Long,
  val completed: Boolean,
) {
  val fraction: Float get() = (elapsedMs.toDouble() / requiredMs).coerceIn(0.0, 1.0).toFloat()
  val remainingMs: Long get() = (requiredMs - elapsedMs).coerceAtLeast(0)
}

/** Pure continuous-hold state; pointer/lifecycle loss must call [cancel]. */
class ContinuousHoldController(
  private val clock: MonotonicClock,
  private val requiredMs: Long = 5_000L,
) {
  private var startedAtMs: Long? = null
  private var completed = false

  init {
    require(requiredMs > 0)
  }

  fun begin(): HoldProgress {
    if (startedAtMs == null) {
      startedAtMs = clock.nowMs().also { require(it >= 0) }
      completed = false
    }
    return progress()
  }

  fun progress(): HoldProgress {
    val startedAt = startedAtMs ?: return HoldProgress(0, requiredMs, false)
    val now = clock.nowMs().also { require(it >= startedAt) }
    val elapsed = (now - startedAt).coerceAtMost(requiredMs)
    if (elapsed >= requiredMs) completed = true
    return HoldProgress(elapsed, requiredMs, completed)
  }

  fun cancel(): HoldProgress {
    startedAtMs = null
    completed = false
    return HoldProgress(0, requiredMs, false)
  }
}
