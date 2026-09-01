package com.missionalarm.mission.camera

/** Provisional M6 profile. Values must be qualified on the physical-device dataset before release. */
data class PushUpProfile(
  val version: String = "pushup-profile-v0",
  val setupStableMs: Long = 200,
  val setupStableFrames: Int = 3,
  val topEnterAngle: Double = 150.0,
  val topExitAngle: Double = 140.0,
  val bottomEnterAngle: Double = 105.0,
  val bottomExitAngle: Double = 120.0,
  val stableTopMs: Long = 180,
  val stableTopFrames: Int = 2,
  val stableBottomMs: Long = 100,
  val stableBottomFrames: Int = 2,
  val minimumRepDurationMs: Long = 600,
  val cooldownMs: Long = 300,
  val lostBodyResetMs: Long = 1_000,
  val transientQualityFailureToleranceFrames: Int = 1,
) {
  init {
    require(version.isNotBlank())
    require(setupStableMs >= 0 && setupStableFrames > 0)
    require(bottomEnterAngle < bottomExitAngle)
    require(bottomExitAngle < topExitAngle)
    require(topExitAngle < topEnterAngle)
    require(stableTopMs >= 0 && stableTopFrames > 0)
    require(stableBottomMs >= 0 && stableBottomFrames > 0)
    require(minimumRepDurationMs >= 0 && cooldownMs >= 0 && lostBodyResetMs >= 0)
    require(transientQualityFailureToleranceFrames >= 0)
  }
}

enum class PushUpSide { LEFT, RIGHT }

/** Sanitized feature input. Camera frames and MediaPipe objects never enter the state machine. */
data class PushUpObservation(
  val sessionId: String,
  val frameSequence: Long,
  val timestampMs: Long,
  val poseDetected: Boolean,
  val fullBodyVisible: Boolean = false,
  val sideOn: Boolean = false,
  val lowLight: Boolean = false,
  val alignmentValid: Boolean = false,
  val selectedSide: PushUpSide? = null,
  val elbowAngle: Double? = null,
) {
  init {
    require(sessionId.isNotBlank())
    require(frameSequence >= 0)
    require(timestampMs >= 0)
    require(elbowAngle == null || elbowAngle in 0.0..180.0)
  }
}

enum class PushUpPhase {
  SEEKING_BODY,
  SEEKING_TOP,
  TOP_CONFIRMED,
  DESCENDING,
  BOTTOM_CONFIRMED,
  ASCENDING,
  COOLDOWN,
  COMPLETE,
}

enum class PushUpFeedback {
  BODY_NOT_DETECTED,
  FULL_BODY_REQUIRED,
  TURN_SIDEWAYS,
  LOW_LIGHT,
  STRAIGHTEN_BODY,
  FIND_TOP_POSITION,
  LOWER_BODY,
  PUSH_UP,
  REP_COUNTED,
  MISSION_COMPLETE,
}

/** Privacy-safe live calibration state. No angles, landmarks, or frame data are exposed. */
data class PushUpQualityStatus(
  val poseDetected: Boolean = false,
  val fullBodyVisible: Boolean = false,
  val sideOn: Boolean = false,
  val lightSufficient: Boolean = false,
  val alignmentValid: Boolean = false,
)

data class PushUpUpdate(
  val phase: PushUpPhase,
  val committedReps: Int,
  val target: Int,
  val feedback: PushUpFeedback,
  val accepted: Boolean,
  val quality: PushUpQualityStatus,
) {
  val completed: Boolean get() = phase == PushUpPhase.COMPLETE
}

/**
 * Deterministic Push-up verification authority.
 *
 * Only committed repetitions escape this transient engine. A new session, stale frame, invalid
 * setup, or body loss can never infer progress.
 */
class PushUpStateMachine(
  private val target: Int,
  initialCommittedReps: Int = 0,
  private val profile: PushUpProfile = PushUpProfile(),
) {
  private var phase = if (initialCommittedReps == target) PushUpPhase.COMPLETE else PushUpPhase.SEEKING_BODY
  private var committedReps = initialCommittedReps
  private var sessionId: String? = null
  private var lastFrameSequence = -1L
  private var lastTimestampMs = -1L
  private var lostBodySinceMs: Long? = null
  private var selectedSide: PushUpSide? = null
  private var firstTopAtMs: Long? = null
  private var cooldownStartedAtMs: Long? = null
  private var stableCandidate: StableCandidate? = null
  private var transientQualityFailureFrames = 0
  private var quality = PushUpQualityStatus()
  private var feedback = if (phase == PushUpPhase.COMPLETE) {
    PushUpFeedback.MISSION_COMPLETE
  } else {
    PushUpFeedback.BODY_NOT_DETECTED
  }

  init {
    require(target in 1..50) { "push-up target must be 1..50" }
    require(initialCommittedReps in 0..target) { "committed reps must be within target" }
  }

  fun process(observation: PushUpObservation): PushUpUpdate {
    if (phase == PushUpPhase.COMPLETE) return update(accepted = false)
    if (sessionId != observation.sessionId) beginSession(observation.sessionId)
    if (observation.frameSequence <= lastFrameSequence || observation.timestampMs <= lastTimestampMs) {
      return update(accepted = false)
    }
    lastFrameSequence = observation.frameSequence
    lastTimestampMs = observation.timestampMs
    quality = PushUpQualityStatus(
      poseDetected = observation.poseDetected,
      fullBodyVisible = observation.fullBodyVisible,
      sideOn = observation.sideOn,
      lightSufficient = !observation.lowLight,
      alignmentValid = observation.alignmentValid,
    )

    val qualityFeedback = qualityFeedback(observation)
    if (qualityFeedback != null) {
      transientQualityFailureFrames += 1
      if (transientQualityFailureFrames > profile.transientQualityFailureToleranceFrames) {
        stableCandidate = null
      }
      feedback = qualityFeedback
      handleBodyLoss(observation)
      return update(accepted = true)
    }
    transientQualityFailureFrames = 0
    lostBodySinceMs = null

    if (phase == PushUpPhase.SEEKING_BODY) {
      feedback = PushUpFeedback.FIND_TOP_POSITION
      if (stable("setup", observation, profile.setupStableMs, profile.setupStableFrames)) {
        phase = PushUpPhase.SEEKING_TOP
        stableCandidate = null
      }
      return update(accepted = true)
    }

    val angle = requireNotNull(observation.elbowAngle)
    when (phase) {
      PushUpPhase.SEEKING_TOP -> {
        feedback = PushUpFeedback.FIND_TOP_POSITION
        if (observation.alignmentValid && angle >= profile.topEnterAngle) {
          if (stable("top", observation, profile.stableTopMs, profile.stableTopFrames)) {
            phase = PushUpPhase.TOP_CONFIRMED
            selectedSide = observation.selectedSide
            firstTopAtMs = observation.timestampMs
            stableCandidate = null
            feedback = PushUpFeedback.LOWER_BODY
          }
        } else {
          stableCandidate = null
          if (!observation.alignmentValid) feedback = PushUpFeedback.STRAIGHTEN_BODY
        }
      }
      PushUpPhase.TOP_CONFIRMED -> {
        feedback = PushUpFeedback.LOWER_BODY
        if (sameSide(observation) && angle <= profile.topExitAngle) {
          phase = PushUpPhase.DESCENDING
        }
      }
      PushUpPhase.DESCENDING -> {
        feedback = PushUpFeedback.LOWER_BODY
        if (sameSide(observation) && observation.alignmentValid && angle <= profile.bottomEnterAngle) {
          if (stable("bottom", observation, profile.stableBottomMs, profile.stableBottomFrames)) {
            phase = PushUpPhase.BOTTOM_CONFIRMED
            stableCandidate = null
            feedback = PushUpFeedback.PUSH_UP
          }
        } else {
          stableCandidate = null
          if (!observation.alignmentValid) feedback = PushUpFeedback.STRAIGHTEN_BODY
        }
      }
      PushUpPhase.BOTTOM_CONFIRMED -> {
        feedback = PushUpFeedback.PUSH_UP
        if (sameSide(observation) && angle >= profile.bottomExitAngle) {
          phase = PushUpPhase.ASCENDING
        }
      }
      PushUpPhase.ASCENDING -> {
        feedback = PushUpFeedback.PUSH_UP
        if (sameSide(observation) && observation.alignmentValid && angle >= profile.topEnterAngle) {
          val topAtMs = firstTopAtMs
          if (stable("return-top", observation, profile.stableTopMs, profile.stableTopFrames) &&
            topAtMs != null && observation.timestampMs - topAtMs >= profile.minimumRepDurationMs
          ) {
            commitRep(observation.timestampMs)
          }
        } else {
          stableCandidate = null
          if (!observation.alignmentValid) feedback = PushUpFeedback.STRAIGHTEN_BODY
        }
      }
      PushUpPhase.COOLDOWN -> {
        feedback = PushUpFeedback.REP_COUNTED
        val cooldownAtMs = requireNotNull(cooldownStartedAtMs)
        if (observation.timestampMs - cooldownAtMs >= profile.cooldownMs &&
          observation.alignmentValid && angle >= profile.topEnterAngle
        ) {
          phase = PushUpPhase.TOP_CONFIRMED
          firstTopAtMs = observation.timestampMs
          cooldownStartedAtMs = null
          feedback = PushUpFeedback.LOWER_BODY
        }
      }
      PushUpPhase.SEEKING_BODY,
      PushUpPhase.COMPLETE,
      -> Unit
    }
    return update(accepted = true)
  }

  private fun beginSession(nextSessionId: String) {
    sessionId = nextSessionId
    lastFrameSequence = -1
    lastTimestampMs = -1
    resetTransient(PushUpPhase.SEEKING_BODY)
  }

  private fun qualityFeedback(observation: PushUpObservation): PushUpFeedback? = when {
    !observation.poseDetected -> PushUpFeedback.BODY_NOT_DETECTED
    !observation.fullBodyVisible || observation.selectedSide == null || observation.elbowAngle == null ->
      PushUpFeedback.FULL_BODY_REQUIRED
    !observation.sideOn -> PushUpFeedback.TURN_SIDEWAYS
    observation.lowLight -> PushUpFeedback.LOW_LIGHT
    else -> null
  }

  private fun handleBodyLoss(observation: PushUpObservation) {
    val bodyEvidenceUsable = observation.poseDetected && observation.fullBodyVisible &&
      observation.selectedSide != null && observation.elbowAngle != null
    if (bodyEvidenceUsable) {
      lostBodySinceMs = null
      return
    }
    val lostSince = lostBodySinceMs ?: observation.timestampMs.also { lostBodySinceMs = it }
    if (observation.timestampMs - lostSince >= profile.lostBodyResetMs) {
      resetTransient(PushUpPhase.SEEKING_BODY)
      lostBodySinceMs = observation.timestampMs
    }
  }

  private fun stable(
    key: String,
    observation: PushUpObservation,
    minimumDurationMs: Long,
    minimumFrames: Int,
  ): Boolean {
    val current = stableCandidate
    val candidate = if (current?.key == key) {
      current.copy(frameCount = current.frameCount + 1)
    } else {
      StableCandidate(key, observation.timestampMs, 1)
    }
    stableCandidate = candidate
    return candidate.frameCount >= minimumFrames &&
      observation.timestampMs - candidate.startedAtMs >= minimumDurationMs
  }

  private fun sameSide(observation: PushUpObservation): Boolean =
    selectedSide != null && observation.selectedSide == selectedSide

  private fun commitRep(timestampMs: Long) {
    committedReps = Math.addExact(committedReps, 1)
    stableCandidate = null
    if (committedReps == target) {
      phase = PushUpPhase.COMPLETE
      feedback = PushUpFeedback.MISSION_COMPLETE
      cooldownStartedAtMs = null
    } else {
      phase = PushUpPhase.COOLDOWN
      feedback = PushUpFeedback.REP_COUNTED
      cooldownStartedAtMs = timestampMs
    }
  }

  private fun resetTransient(nextPhase: PushUpPhase) {
    phase = nextPhase
    selectedSide = null
    firstTopAtMs = null
    cooldownStartedAtMs = null
    stableCandidate = null
    transientQualityFailureFrames = 0
  }

  private fun update(accepted: Boolean) = PushUpUpdate(
    phase = phase,
    committedReps = committedReps,
    target = target,
    feedback = feedback,
    accepted = accepted,
    quality = quality,
  )

  private data class StableCandidate(
    val key: String,
    val startedAtMs: Long,
    val frameCount: Int,
  )
}
