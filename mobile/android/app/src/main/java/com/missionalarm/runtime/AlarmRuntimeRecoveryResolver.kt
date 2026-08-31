package com.missionalarm.runtime

data class RuntimeOwnerCandidate(
  val instanceId: String,
  val runtimeState: String,
  val attentionSlot: Int?,
)

/** Chooses the canonical runtime owner after service/process recreation. */
object AlarmRuntimeRecoveryResolver {
  fun resolve(
    requestedInstanceId: String?,
    requested: RuntimeOwnerCandidate?,
    attended: RuntimeOwnerCandidate?,
  ): RuntimeOwnerCandidate? {
    if (requestedInstanceId != null && requested?.instanceId == requestedInstanceId &&
      requested.isEligibleOwner()
    ) {
      return requested
    }
    return attended?.takeIf { it.isEligibleOwner() }
  }

  private fun RuntimeOwnerCandidate.isEligibleOwner() =
    attentionSlot == 1 && runtimeState != "TERMINAL"
}
