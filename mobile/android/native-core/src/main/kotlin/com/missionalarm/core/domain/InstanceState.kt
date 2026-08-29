package com.missionalarm.core.domain

enum class InstanceRuntimeState {
  TRIGGERED,
  PENDING_ATTENTION,
  MISSION_LOCKED,
  MISSION_IN_PROGRESS,
  RECOVERY_REQUIRED,
  TERMINAL,
}

enum class TerminalResult {
  SUCCESS,
  EMERGENCY_DISMISSED,
  FAILED,
  CANCELLED,
}

data class InstanceState(
  val runtime: InstanceRuntimeState,
  val terminalResult: TerminalResult? = null,
) {
  init {
    require((runtime == InstanceRuntimeState.TERMINAL) == (terminalResult != null)) {
      "terminal runtime and result must be set together"
    }
  }

  fun queue(): InstanceState = transition(
    expected = setOf(InstanceRuntimeState.TRIGGERED),
    next = InstanceRuntimeState.PENDING_ATTENTION,
  )

  fun lockMission(): InstanceState = transition(
    expected = setOf(
      InstanceRuntimeState.TRIGGERED,
      InstanceRuntimeState.PENDING_ATTENTION,
      InstanceRuntimeState.RECOVERY_REQUIRED,
    ),
    next = InstanceRuntimeState.MISSION_LOCKED,
  )

  fun startMission(): InstanceState = transition(
    expected = setOf(
      InstanceRuntimeState.MISSION_LOCKED,
      InstanceRuntimeState.RECOVERY_REQUIRED,
    ),
    next = InstanceRuntimeState.MISSION_IN_PROGRESS,
  )

  fun requireRecovery(): InstanceState = transition(
    expected = setOf(
      InstanceRuntimeState.MISSION_LOCKED,
      InstanceRuntimeState.MISSION_IN_PROGRESS,
    ),
    next = InstanceRuntimeState.RECOVERY_REQUIRED,
  )

  fun completeVerified(progress: MissionProgress): InstanceState {
    check(runtime == InstanceRuntimeState.MISSION_IN_PROGRESS) {
      "success requires a mission in progress"
    }
    check(progress.isComplete) { "success requires verified target completion" }
    return terminal(TerminalResult.SUCCESS)
  }

  fun emergencyDismiss(): InstanceState {
    check(
      runtime in setOf(
        InstanceRuntimeState.MISSION_LOCKED,
        InstanceRuntimeState.MISSION_IN_PROGRESS,
        InstanceRuntimeState.RECOVERY_REQUIRED,
      ),
    ) { "emergency dismissal is unavailable from $runtime" }
    return terminal(TerminalResult.EMERGENCY_DISMISSED)
  }

  fun failSafely(): InstanceState {
    check(runtime != InstanceRuntimeState.TERMINAL) { "terminal result is immutable" }
    return terminal(TerminalResult.FAILED)
  }

  fun cancelBeforeAttention(): InstanceState {
    check(runtime in setOf(InstanceRuntimeState.TRIGGERED, InstanceRuntimeState.PENDING_ATTENTION)) {
      "active mission cannot be cancelled normally"
    }
    return terminal(TerminalResult.CANCELLED)
  }

  private fun transition(
    expected: Set<InstanceRuntimeState>,
    next: InstanceRuntimeState,
  ): InstanceState {
    check(runtime in expected) { "invalid transition from $runtime to $next" }
    return InstanceState(next)
  }

  private fun terminal(result: TerminalResult) = InstanceState(
    runtime = InstanceRuntimeState.TERMINAL,
    terminalResult = result,
  )

  companion object {
    fun triggered() = InstanceState(InstanceRuntimeState.TRIGGERED)
  }
}
