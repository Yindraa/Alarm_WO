package com.missionalarm.core.domain

enum class MissionType {
  PUSH_UP,
  MATH,
  QR,
}

data class MissionConfig(
  val type: MissionType,
  val version: Int,
  val target: Int,
) {
  init {
    require(version >= 1) { "mission config version must be positive" }
    when (type) {
      MissionType.PUSH_UP -> require(target in 1..50) { "push-up target must be 1..50" }
      MissionType.MATH -> require(target in 1..10) { "math target must be 1..10" }
      MissionType.QR -> require(target == 1) { "QR target must be 1" }
    }
  }
}

class MissionProgress private constructor(
  val target: Int,
  val committed: Int,
) {
  val isComplete: Boolean
    get() = committed == target

  fun commitVerified(next: Int): MissionProgress {
    require(next >= committed) { "verified progress cannot decrease" }
    require(next <= target) { "verified progress cannot exceed target" }
    return if (next == committed) this else MissionProgress(target = target, committed = next)
  }

  override fun equals(other: Any?): Boolean =
    other is MissionProgress && target == other.target && committed == other.committed

  override fun hashCode(): Int = 31 * target + committed

  override fun toString(): String = "MissionProgress(target=$target, committed=$committed)"

  companion object {
    fun start(target: Int): MissionProgress {
      require(target >= 1) { "target must be positive" }
      return MissionProgress(target = target, committed = 0)
    }

    fun restore(target: Int, committed: Int): MissionProgress {
      require(target >= 1) { "target must be positive" }
      require(committed in 0..target) { "committed progress must be within 0..target" }
      return MissionProgress(target = target, committed = committed)
    }
  }
}
