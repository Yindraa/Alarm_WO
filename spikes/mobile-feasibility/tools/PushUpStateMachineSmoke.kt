package com.alarmwofeasibility.pose

import kotlin.math.cos
import kotlin.math.sin

fun main() {
  val machine = PushUpStateMachine()
  machine.process(sample(170.0))
  check(machine.state == PushUpState.TOP)
  machine.process(sample(120.0))
  check(machine.reps == 0) { "Partial movement must not count" }
  machine.process(sample(80.0))
  check(machine.state == PushUpState.DOWN)
  machine.process(sample(170.0))
  check(machine.reps == 1) { "A complete TOP-DOWN-TOP sequence must count once" }
  machine.process(sample(80.0, visibility = 0.2f))
  check(machine.reps == 1) { "Low visibility must not change progress" }
  println("PushUpStateMachine smoke test passed")
}

private fun sample(angle: Double, visibility: Float = 1f): PushUpSample {
  val radians = Math.toRadians(angle)
  return PushUpSample(
    shoulder = PosePoint(1f, 0f, visibility),
    elbow = PosePoint(0f, 0f, visibility),
    wrist = PosePoint(cos(radians).toFloat(), sin(radians).toFloat(), visibility),
    hip = PosePoint(0.5f, 0.5f, visibility),
    ankle = PosePoint(0f, 1f, visibility),
  )
}
