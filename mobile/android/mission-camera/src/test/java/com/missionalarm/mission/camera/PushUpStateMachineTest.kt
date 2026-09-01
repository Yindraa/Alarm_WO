package com.missionalarm.mission.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpStateMachineTest {
  private val profile = PushUpProfile()

  @Test
  fun completeTopDownTopSequenceCommitsExactlyOneRep() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)

    fixture.establishTop()
    fixture.frame(angle = 140.0)
    fixture.stableBottom()
    fixture.frame(angle = 120.0)
    val result = fixture.stableReturnTop()

    assertEquals(PushUpPhase.COMPLETE, result.phase)
    assertEquals(1, result.committedReps)
    assertEquals(PushUpFeedback.MISSION_COMPLETE, result.feedback)
  }

  @Test
  fun partialDescentAndDirectBottomDoNotCount() {
    val machine = PushUpStateMachine(target = 2, profile = profile)
    val fixture = Fixture(machine)

    fixture.setup()
    fixture.frame(angle = 90.0)
    fixture.frame(angle = 170.0, advanceMs = 800)

    assertEquals(0, fixture.last.committedReps)
    assertEquals(PushUpPhase.SEEKING_TOP, fixture.last.phase)
  }

  @Test
  fun badAlignmentFreezesTransitionWithoutProgress() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)

    fixture.establishTop()
    fixture.frame(angle = 140.0)
    fixture.frame(angle = 90.0, alignment = false)
    fixture.frame(angle = 90.0, alignment = false, advanceMs = 200)

    assertEquals(PushUpPhase.DESCENDING, fixture.last.phase)
    assertEquals(0, fixture.last.committedReps)
    assertEquals(PushUpFeedback.STRAIGHTEN_BODY, fixture.last.feedback)
  }

  @Test
  fun bodyLossDiscardsHalfRepButPreservesCommittedProgress() {
    val machine = PushUpStateMachine(target = 2, initialCommittedReps = 1, profile = profile)
    val fixture = Fixture(machine)

    fixture.establishTop()
    fixture.frame(angle = 140.0)
    fixture.frame(poseDetected = false)
    fixture.frame(poseDetected = false, advanceMs = profile.lostBodyResetMs)

    assertEquals(PushUpPhase.SEEKING_BODY, fixture.last.phase)
    assertEquals(1, fixture.last.committedReps)
    assertEquals(PushUpFeedback.BODY_NOT_DETECTED, fixture.last.feedback)
  }

  @Test
  fun sessionChangeAndStaleFramesCannotCreateProgress() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)
    fixture.establishTop()
    fixture.frame(angle = 140.0)

    fixture.sessionId = "replacement"
    val replacement = fixture.frame(angle = 90.0)
    assertEquals(PushUpPhase.SEEKING_BODY, replacement.phase)

    val stale = machine.process(fixture.observation(angle = 170.0, sequence = fixture.sequence))
    assertFalse(stale.accepted)
    assertEquals(0, stale.committedReps)
  }

  @Test
  fun cooldownPreventsImmediateDoubleCount() {
    val machine = PushUpStateMachine(target = 2, profile = profile)
    val fixture = Fixture(machine)

    fixture.completeRep()
    assertEquals(PushUpPhase.COOLDOWN, fixture.last.phase)
    assertEquals(1, fixture.last.committedReps)

    fixture.frame(angle = 90.0, advanceMs = 50)
    fixture.frame(angle = 170.0, advanceMs = 50)
    assertEquals(1, fixture.last.committedReps)
    assertFalse(fixture.last.completed)

    val ready = fixture.frame(angle = 170.0, advanceMs = profile.cooldownMs)
    assertEquals(PushUpPhase.TOP_CONFIRMED, ready.phase)
  }

  @Test
  fun movementFasterThanMinimumRepDurationIsNotCommitted() {
    val strictProfile = profile.copy(minimumRepDurationMs = 2_000)
    val machine = PushUpStateMachine(target = 1, profile = strictProfile)
    val fixture = Fixture(machine)

    fixture.completeRep()

    assertEquals(PushUpPhase.ASCENDING, fixture.last.phase)
    assertEquals(0, fixture.last.committedReps)
  }

  @Test
  fun qualityFeedbackIsActionableAndDoesNotMutateProgress() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)

    assertEquals(PushUpFeedback.BODY_NOT_DETECTED, fixture.frame(poseDetected = false).feedback)
    assertEquals(PushUpFeedback.FULL_BODY_REQUIRED, fixture.frame(fullBody = false).feedback)
    assertEquals(PushUpFeedback.TURN_SIDEWAYS, fixture.frame(sideOn = false).feedback)
    assertEquals(PushUpFeedback.LOW_LIGHT, fixture.frame(lowLight = true).feedback)
    assertTrue(fixture.last.quality.poseDetected)
    assertTrue(fixture.last.quality.fullBodyVisible)
    assertTrue(fixture.last.quality.sideOn)
    assertFalse(fixture.last.quality.lightSufficient)
    assertTrue(fixture.last.quality.alignmentValid)
    assertTrue(fixture.last.accepted)
    assertEquals(0, fixture.last.committedReps)
  }

  @Test
  fun staleFrameRetainsLastAcceptedCalibrationStatus() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)
    val accepted = fixture.frame(sideOn = false)

    val stale = machine.process(
      fixture.observation(angle = 170.0, sequence = fixture.sequence, sideOn = true),
    )

    assertFalse(stale.accepted)
    assertEquals(accepted.quality, stale.quality)
    assertFalse(stale.quality.sideOn)
  }

  @Test
  fun calibratedBottomBoundaryCanCompleteAValidRep() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)

    fixture.establishTop()
    fixture.frame(angle = 135.0)
    fixture.frame(angle = 104.0, advanceMs = 1)
    fixture.frame(angle = 104.0, advanceMs = profile.stableBottomMs)
    fixture.frame(angle = 121.0)
    val result = fixture.stableReturnTop()

    assertEquals(PushUpPhase.COMPLETE, result.phase)
    assertEquals(1, result.committedReps)
  }

  @Test
  fun oneTransientQualityFailureDoesNotDiscardStableTopCandidate() {
    val machine = PushUpStateMachine(target = 1, profile = profile)
    val fixture = Fixture(machine)
    fixture.setup()

    fixture.frame(angle = 160.0, advanceMs = 1)
    fixture.frame(angle = 160.0, advanceMs = 100, poseDetected = false)
    val result = fixture.frame(angle = 160.0, advanceMs = 100)

    assertEquals(PushUpPhase.TOP_CONFIRMED, result.phase)
    assertEquals(PushUpFeedback.LOWER_BODY, result.feedback)
  }

  @Test
  fun preferredBodySideRemainsStickyDuringRep() {
    assertEquals(
      PushUpSide.RIGHT,
      PushUpFeatureExtractor.selectSide(null, leftQuality = 0.70, rightQuality = 0.90),
    )
    assertEquals(
      PushUpSide.RIGHT,
      PushUpFeatureExtractor.selectSide(PushUpSide.RIGHT, leftQuality = 0.95, rightQuality = 0.65),
    )
  }

  private class Fixture(private val machine: PushUpStateMachine) {
    var sessionId = "session-a"
    var sequence = 0L
    private var timestampMs = 0L
    lateinit var last: PushUpUpdate

    fun setup() {
      frame(advanceMs = 0)
      frame(advanceMs = 100)
      frame(advanceMs = 100)
      last = frame(advanceMs = 100)
      assertEquals(PushUpPhase.SEEKING_TOP, last.phase)
    }

    fun establishTop() {
      setup()
      frame(angle = 170.0, advanceMs = 1)
      frame(angle = 170.0, advanceMs = 125)
      last = frame(angle = 170.0, advanceMs = 125)
      assertEquals(PushUpPhase.TOP_CONFIRMED, last.phase)
    }

    fun stableBottom() {
      frame(angle = 90.0, advanceMs = 1)
      last = frame(angle = 90.0, advanceMs = 150)
      assertEquals(PushUpPhase.BOTTOM_CONFIRMED, last.phase)
    }

    fun stableReturnTop(): PushUpUpdate {
      frame(angle = 170.0, advanceMs = 1)
      frame(angle = 170.0, advanceMs = 125)
      return frame(angle = 170.0, advanceMs = 225)
    }

    fun completeRep() {
      establishTop()
      frame(angle = 140.0)
      stableBottom()
      frame(angle = 120.0)
      stableReturnTop()
    }

    fun frame(
      angle: Double = 170.0,
      advanceMs: Long = 100,
      poseDetected: Boolean = true,
      fullBody: Boolean = true,
      sideOn: Boolean = true,
      lowLight: Boolean = false,
      alignment: Boolean = true,
    ): PushUpUpdate {
      timestampMs += advanceMs
      sequence += 1
      last = machine.process(
        observation(
          angle = angle,
          poseDetected = poseDetected,
          fullBody = fullBody,
          sideOn = sideOn,
          lowLight = lowLight,
          alignment = alignment,
        ),
      )
      return last
    }

    fun observation(
      angle: Double,
      sequence: Long = this.sequence + 1,
      poseDetected: Boolean = true,
      fullBody: Boolean = true,
      sideOn: Boolean = true,
      lowLight: Boolean = false,
      alignment: Boolean = true,
    ) = PushUpObservation(
      sessionId = sessionId,
      frameSequence = sequence,
      timestampMs = timestampMs,
      poseDetected = poseDetected,
      fullBodyVisible = fullBody,
      sideOn = sideOn,
      lowLight = lowLight,
      alignmentValid = alignment,
      selectedSide = if (poseDetected && fullBody) PushUpSide.LEFT else null,
      elbowAngle = if (poseDetected && fullBody) angle else null,
    )
  }
}
