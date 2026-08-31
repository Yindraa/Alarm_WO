package com.missionalarm.runtime

import com.missionalarm.core.data.ActiveRuntimeSnapshot

enum class MissionDestination {
  EMBEDDED_MATH,
  NATIVE_PUSH_UP,
  NATIVE_QR,
}

sealed interface MissionRouteDecision {
  data class Ready(val destination: MissionDestination) : MissionRouteDecision
  data class Recovery(val reasonCode: String) : MissionRouteDecision
}

/** Selects a mission surface only from the latest canonical active-instance snapshot. */
object MissionRouteResolver {
  fun resolve(snapshot: ActiveRuntimeSnapshot): MissionRouteDecision {
    if (snapshot.runtimeState == "RECOVERY_REQUIRED" ||
      snapshot.missionRuntimeStatus == "RECOVERY_REQUIRED"
    ) {
      return MissionRouteDecision.Recovery("MISSION_RECOVERY_REQUIRED")
    }
    if (snapshot.runtimeState !in ROUTABLE_INSTANCE_STATES ||
      snapshot.missionRuntimeStatus !in ROUTABLE_MISSION_STATES ||
      snapshot.committedProgress !in 0 until snapshot.target
    ) {
      return MissionRouteDecision.Recovery("MISSION_STATE_INCONSISTENT")
    }
    return when (snapshot.missionType) {
      "MATH" -> if (snapshot.mathQuestion == null) {
        MissionRouteDecision.Recovery("MATH_PROMPT_UNAVAILABLE")
      } else {
        MissionRouteDecision.Ready(MissionDestination.EMBEDDED_MATH)
      }
      "PUSH_UP" -> MissionRouteDecision.Ready(MissionDestination.NATIVE_PUSH_UP)
      "QR" -> MissionRouteDecision.Ready(MissionDestination.NATIVE_QR)
      else -> MissionRouteDecision.Recovery("MISSION_TYPE_UNSUPPORTED")
    }
  }

  private val ROUTABLE_INSTANCE_STATES = setOf(
    "TRIGGERED",
    "MISSION_LOCKED",
    "MISSION_IN_PROGRESS",
  )
  private val ROUTABLE_MISSION_STATES = setOf("READY", "IN_PROGRESS")
}
