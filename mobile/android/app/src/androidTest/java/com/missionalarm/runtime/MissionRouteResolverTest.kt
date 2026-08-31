package com.missionalarm.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.missionalarm.core.data.ActiveRuntimeSnapshot
import com.missionalarm.core.data.MathQuestionPrompt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MissionRouteResolverTest {
  @Test
  fun routesSupportedMissionTypesFromCanonicalSnapshot() {
    assertEquals(
      MissionRouteDecision.Ready(MissionDestination.EMBEDDED_MATH),
      MissionRouteResolver.resolve(snapshot("MATH")),
    )
    assertEquals(
      MissionRouteDecision.Ready(MissionDestination.NATIVE_PUSH_UP),
      MissionRouteResolver.resolve(snapshot("PUSH_UP", mathQuestion = null)),
    )
    assertEquals(
      MissionRouteDecision.Ready(MissionDestination.NATIVE_QR),
      MissionRouteResolver.resolve(snapshot("QR", mathQuestion = null)),
    )
  }

  @Test
  fun missingMathPromptFailsClosedToRecovery() {
    assertEquals(
      MissionRouteDecision.Recovery("MATH_PROMPT_UNAVAILABLE"),
      MissionRouteResolver.resolve(snapshot("MATH", mathQuestion = null)),
    )
  }

  @Test
  fun unknownMissionTypeFailsClosedToRecovery() {
    assertEquals(
      MissionRouteDecision.Recovery("MISSION_TYPE_UNSUPPORTED"),
      MissionRouteResolver.resolve(snapshot("UNKNOWN", mathQuestion = null)),
    )
  }

  @Test
  fun recoveryStateCannotLaunchAnyMissionSurface() {
    assertEquals(
      MissionRouteDecision.Recovery("MISSION_RECOVERY_REQUIRED"),
      MissionRouteResolver.resolve(snapshot("QR", "RECOVERY_REQUIRED", mathQuestion = null)),
    )
  }

  @Test
  fun terminalOrCompletedSnapshotCannotLaunchMissionSurface() {
    assertEquals(
      MissionRouteDecision.Recovery("MISSION_STATE_INCONSISTENT"),
      MissionRouteResolver.resolve(snapshot("PUSH_UP", "TERMINAL", mathQuestion = null)),
    )
    assertEquals(
      MissionRouteDecision.Recovery("MISSION_STATE_INCONSISTENT"),
      MissionRouteResolver.resolve(
        snapshot("QR", mathQuestion = null, missionRuntimeStatus = "COMPLETED"),
      ),
    )
  }

  private fun snapshot(
    missionType: String,
    runtimeState: String = "TRIGGERED",
    mathQuestion: MathQuestionPrompt? = MathQuestionPrompt(0, "ADD", 2, 3),
    missionRuntimeStatus: String = "READY",
  ) = ActiveRuntimeSnapshot(
    instanceId = "92d9035c-adfe-422e-949b-b96877cec786",
    occurrenceId = "ee788d89-cbf2-4f04-9897-0fca4961836e",
    revision = 1,
    runtimeState = runtimeState,
    label = "Alarm",
    scheduledAtUtcMs = 1_000,
    actualTriggerAtMs = 1_001,
    missionType = missionType,
    target = 3,
    committedProgress = 0,
    missionRuntimeStatus = missionRuntimeStatus,
    mathQuestion = mathQuestion,
    queuedCount = 0,
  )
}
