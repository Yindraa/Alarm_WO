package com.alarmwofeasibility.pose

import kotlin.math.acos
import kotlin.math.sqrt

data class PosePoint(val x: Float, val y: Float, val visibility: Float)

data class PushUpSample(
  val shoulder: PosePoint,
  val elbow: PosePoint,
  val wrist: PosePoint,
  val hip: PosePoint,
  val ankle: PosePoint,
)

data class PushUpUpdate(
  val state: PushUpState,
  val reps: Int,
  val feedback: String,
  val elbowAngle: Double?,
  val bodyAngle: Double?,
)

enum class PushUpState { READY, TOP, DOWN }

class PushUpStateMachine(
  private val minVisibility: Float = 0.6f,
  private val topElbowAngle: Double = 150.0,
  private val downElbowAngle: Double = 95.0,
  private val minBodyAngle: Double = 150.0,
) {
  var state: PushUpState = PushUpState.READY
    private set
  var reps: Int = 0
    private set

  fun process(sample: PushUpSample): PushUpUpdate {
    val points = listOf(sample.shoulder, sample.elbow, sample.wrist, sample.hip, sample.ankle)
    if (points.any { it.visibility < minVisibility }) {
      return update("Keep your full side visible", null, null)
    }

    val elbowAngle = angle(sample.shoulder, sample.elbow, sample.wrist)
    val bodyAngle = angle(sample.shoulder, sample.hip, sample.ankle)
    if (bodyAngle < minBodyAngle) {
      return update("Keep your body straight", elbowAngle, bodyAngle)
    }

    val feedback =
      when (state) {
        PushUpState.READY -> {
          if (elbowAngle >= topElbowAngle) {
            state = PushUpState.TOP
            "Top position found"
          } else {
            "Start in the top position"
          }
        }
        PushUpState.TOP -> {
          if (elbowAngle <= downElbowAngle) {
            state = PushUpState.DOWN
            "Depth reached; push up"
          } else {
            "Lower your body"
          }
        }
        PushUpState.DOWN -> {
          if (elbowAngle >= topElbowAngle) {
            state = PushUpState.TOP
            reps += 1
            "Valid rep"
          } else {
            "Return to the top"
          }
        }
      }
    return update(feedback, elbowAngle, bodyAngle)
  }

  private fun update(feedback: String, elbowAngle: Double?, bodyAngle: Double?) =
    PushUpUpdate(state, reps, feedback, elbowAngle, bodyAngle)

  private fun angle(a: PosePoint, vertex: PosePoint, c: PosePoint): Double {
    val abX = a.x - vertex.x
    val abY = a.y - vertex.y
    val cbX = c.x - vertex.x
    val cbY = c.y - vertex.y
    val denominator =
      sqrt((abX * abX + abY * abY).toDouble()) *
        sqrt((cbX * cbX + cbY * cbY).toDouble())
    if (denominator == 0.0) return 0.0
    val cosine = ((abX * cbX + abY * cbY) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
  }
}
