package com.alarmwofeasibility.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class PushUpStateMachineTest {
  private val machine = PushUpStateMachine()

  @Test
  fun `counts only a complete top down top sequence`() {
    machine.process(sampleWithElbowAngle(170.0))
    machine.process(sampleWithElbowAngle(80.0))
    val result = machine.process(sampleWithElbowAngle(170.0))

    assertEquals(PushUpState.TOP, result.state)
    assertEquals(1, result.reps)
  }

  @Test
  fun `does not count a partial repetition`() {
    machine.process(sampleWithElbowAngle(170.0))
    machine.process(sampleWithElbowAngle(120.0))
    val result = machine.process(sampleWithElbowAngle(170.0))

    assertEquals(0, result.reps)
  }

  @Test
  fun `rejects low visibility without changing progress`() {
    machine.process(sampleWithElbowAngle(170.0))
    val result = machine.process(sampleWithElbowAngle(80.0, visibility = 0.2f))

    assertEquals(PushUpState.TOP, result.state)
    assertEquals(0, result.reps)
  }

  private fun sampleWithElbowAngle(
    angle: Double,
    visibility: Float = 1f,
  ): PushUpSample {
    val radians = Math.toRadians(angle)
    val elbow = PosePoint(0f, 0f, visibility)
    val shoulder = PosePoint(1f, 0f, visibility)
    val wrist =
      PosePoint(
        kotlin.math.cos(radians).toFloat(),
        kotlin.math.sin(radians).toFloat(),
        visibility,
      )
    return PushUpSample(
      shoulder,
      elbow,
      wrist,
      PosePoint(0.5f, 0.5f, visibility),
      PosePoint(0f, 1f, visibility),
    )
  }
}
