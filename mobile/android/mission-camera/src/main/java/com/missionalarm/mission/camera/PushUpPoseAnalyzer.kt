package com.missionalarm.mission.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

internal class PushUpPoseAnalyzer(
  context: android.content.Context,
  target: Int,
  initialCommittedReps: Int,
  private val sessionId: String,
  private val onUpdate: (PushUpUpdate) -> Unit,
  private val onVerifiedRep: (repSequence: Int) -> Unit,
  private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {
  private val closed = AtomicBoolean(false)
  private val inferenceInFlight = AtomicBoolean(false)
  private val stateMachine = PushUpStateMachine(target, initialCommittedReps)
  private var frameSequence = 0L
  private var lastTimestampMs = -1L
  private var pendingFrame: PendingFrame? = null
  private var dispatchedProgress = initialCommittedReps
  private val poseLandmarker: PoseLandmarker

  init {
    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
      .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
      .setRunningMode(RunningMode.LIVE_STREAM)
      .setNumPoses(1)
      .setMinPoseDetectionConfidence(0.5f)
      .setMinPosePresenceConfidence(0.5f)
      .setMinTrackingConfidence(0.5f)
      .setResultListener(::onPoseResult)
      .setErrorListener { error ->
        inferenceInFlight.set(false)
        if (!closed.get()) onError(error)
      }
      .build()
    poseLandmarker = PoseLandmarker.createFromOptions(context, options)
  }

  override fun analyze(imageProxy: ImageProxy) {
    if (closed.get() || !inferenceInFlight.compareAndSet(false, true)) {
      imageProxy.close()
      return
    }
    runCatching {
      val timestampMs = maxOf(lastTimestampMs + 1, imageProxy.imageInfo.timestamp / 1_000_000L)
      lastTimestampMs = timestampMs
      val bitmap = rotate(imageProxy.toBitmap(), imageProxy.imageInfo.rotationDegrees.toFloat())
      pendingFrame = PendingFrame(
        sequence = ++frameSequence,
        timestampMs = timestampMs,
        width = bitmap.width,
        height = bitmap.height,
        lowLight = meanLuma(imageProxy.planes.first().buffer) < LOW_LIGHT_LUMA,
      )
      val image = BitmapImageBuilder(bitmap).build()
      poseLandmarker.detectAsync(image, timestampMs)
    }.onFailure { error ->
      inferenceInFlight.set(false)
      pendingFrame = null
      if (!closed.get()) onError(error)
    }
    imageProxy.close()
  }

  private fun onPoseResult(result: PoseLandmarkerResult, @Suppress("UNUSED_PARAMETER") image: MPImage) {
    val frame = pendingFrame
    pendingFrame = null
    inferenceInFlight.set(false)
    if (closed.get() || frame == null) return
    val observation = PushUpFeatureExtractor.extract(sessionId, frame, result)
    val update = stateMachine.process(observation)
    onUpdate(update)
    if (update.committedReps > dispatchedProgress) {
      dispatchedProgress = update.committedReps
      onVerifiedRep(update.committedReps)
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    pendingFrame = null
    poseLandmarker.close()
  }

  private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bitmap
    return Bitmap.createBitmap(
      bitmap,
      0,
      0,
      bitmap.width,
      bitmap.height,
      Matrix().apply { postRotate(degrees) },
      true,
    )
  }

  private fun meanLuma(buffer: ByteBuffer): Float {
    val bytes = buffer.duplicate()
    if (!bytes.hasRemaining()) return 0f
    val step = maxOf(1, bytes.remaining() / LUMA_SAMPLE_COUNT)
    var sum = 0L
    var count = 0
    var index = bytes.position()
    while (index < bytes.limit()) {
      sum += bytes.get(index).toInt() and 0xff
      count += 1
      index += step
    }
    return if (count == 0) 0f else sum.toFloat() / count
  }

  internal data class PendingFrame(
    val sequence: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val lowLight: Boolean,
  )

  private companion object {
    const val MODEL_ASSET = "pose_landmarker_lite.task"
    const val LOW_LIGHT_LUMA = 35f
    const val LUMA_SAMPLE_COUNT = 1_024
  }
}

internal object PushUpFeatureExtractor {
  fun extract(
    sessionId: String,
    frame: PushUpPoseAnalyzer.PendingFrame,
    result: PoseLandmarkerResult,
  ): PushUpObservation {
    val landmarks = result.landmarks().firstOrNull()
      ?: return missingObservation(sessionId, frame)
    val world = result.worldLandmarks().firstOrNull()
      ?: return missingObservation(sessionId, frame)
    if (landmarks.size < LANDMARK_COUNT || world.size < LANDMARK_COUNT) {
      return missingObservation(sessionId, frame)
    }

    val left = side(landmarks, PushUpSide.LEFT, frame.width, frame.height)
    val right = side(landmarks, PushUpSide.RIGHT, frame.width, frame.height)
    val selected = if (left.quality >= right.quality) left else right
    val sideOnScore = sideOnScore(world)
    val fullBodyVisible = selected.points.all {
      it.visibility >= MIN_CONFIDENCE && it.presence >= MIN_CONFIDENCE &&
        it.normalizedX in FRAME_MIN..FRAME_MAX && it.normalizedY in FRAME_MIN..FRAME_MAX
    } && selected.bodyScale >= MIN_BODY_SCALE && selected.extent <= MAX_BODY_EXTENT
    val elbowAngle = angle(selected.shoulder, selected.elbow, selected.wrist)
    val hipAngle = angle(selected.shoulder, selected.hip, selected.ankle)
    val kneeAngle = angle(selected.hip, selected.knee, selected.ankle)
    val bodyTilt = foldedHorizontalAngle(selected.shoulder, selected.ankle)
    val alignmentValid = hipAngle >= MIN_HIP_ANGLE && kneeAngle >= MIN_KNEE_ANGLE &&
      bodyTilt <= MAX_BODY_TILT

    return PushUpObservation(
      sessionId = sessionId,
      frameSequence = frame.sequence,
      timestampMs = frame.timestampMs,
      poseDetected = true,
      fullBodyVisible = fullBodyVisible,
      sideOn = sideOnScore >= MIN_SIDE_ON_SCORE,
      lowLight = frame.lowLight,
      alignmentValid = alignmentValid,
      selectedSide = selected.side,
      elbowAngle = elbowAngle,
    )
  }

  private fun missingObservation(
    sessionId: String,
    frame: PushUpPoseAnalyzer.PendingFrame,
  ) = PushUpObservation(
    sessionId = sessionId,
    frameSequence = frame.sequence,
    timestampMs = frame.timestampMs,
    poseDetected = false,
    lowLight = frame.lowLight,
  )

  private fun side(
    landmarks: List<NormalizedLandmark>,
    side: PushUpSide,
    width: Int,
    height: Int,
  ): SideFeatures {
    val indices = if (side == PushUpSide.LEFT) LEFT_INDICES else RIGHT_INDICES
    val points = indices.map { landmarks[it].toPoint(width, height) }
    val shoulder = points[0]
    val elbow = points[1]
    val wrist = points[2]
    val hip = points[3]
    val knee = points[4]
    val ankle = points[5]
    val diagonal = sqrt(width.toDouble() * width + height.toDouble() * height)
    val bodyScale = distance(shoulder, ankle) / diagonal
    val extent = maxOf(
      (points.maxOf { it.x } - points.minOf { it.x }) / width,
      (points.maxOf { it.y } - points.minOf { it.y }) / height,
    )
    return SideFeatures(
      side,
      points,
      shoulder,
      elbow,
      wrist,
      hip,
      knee,
      ankle,
      points.minOf { minOf(it.visibility, it.presence) },
      bodyScale,
      extent,
    )
  }

  private fun sideOnScore(world: List<Landmark>): Double {
    val shoulder = depthDominance(world[11], world[12])
    val hip = depthDominance(world[23], world[24])
    return (shoulder + hip) / 2.0
  }

  private fun depthDominance(a: Landmark, b: Landmark): Double {
    val dx = (a.x() - b.x()).toDouble()
    val dy = (a.y() - b.y()).toDouble()
    val dz = (a.z() - b.z()).toDouble()
    val distance = sqrt(dx * dx + dy * dy + dz * dz)
    return if (distance < EPSILON) 0.0 else abs(dz) / distance
  }

  private fun NormalizedLandmark.toPoint(width: Int, height: Int) = Point(
    x = x().toDouble() * width,
    y = y().toDouble() * height,
    normalizedX = x().toDouble(),
    normalizedY = y().toDouble(),
    visibility = visibility().orElse(0f).toDouble(),
    presence = presence().orElse(1f).toDouble(),
  )

  private fun angle(a: Point, vertex: Point, c: Point): Double {
    val ax = a.x - vertex.x
    val ay = a.y - vertex.y
    val cx = c.x - vertex.x
    val cy = c.y - vertex.y
    val denominator = sqrt(ax * ax + ay * ay) * sqrt(cx * cx + cy * cy)
    if (denominator < EPSILON) return 0.0
    val cosine = ((ax * cx + ay * cy) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
  }

  private fun distance(a: Point, b: Point): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
  }

  private fun foldedHorizontalAngle(a: Point, b: Point): Double {
    val raw = abs(Math.toDegrees(atan2(b.y - a.y, b.x - a.x))) % 180.0
    return minOf(raw, 180.0 - raw)
  }

  private data class Point(
    val x: Double,
    val y: Double,
    val normalizedX: Double,
    val normalizedY: Double,
    val visibility: Double,
    val presence: Double,
  )

  private data class SideFeatures(
    val side: PushUpSide,
    val points: List<Point>,
    val shoulder: Point,
    val elbow: Point,
    val wrist: Point,
    val hip: Point,
    val knee: Point,
    val ankle: Point,
    val quality: Double,
    val bodyScale: Double,
    val extent: Double,
  )

  private val LEFT_INDICES = listOf(11, 13, 15, 23, 25, 27)
  private val RIGHT_INDICES = listOf(12, 14, 16, 24, 26, 28)
  private const val LANDMARK_COUNT = 29
  private const val MIN_CONFIDENCE = 0.60
  private const val FRAME_MIN = 0.02
  private const val FRAME_MAX = 0.98
  private const val MIN_BODY_SCALE = 0.35
  private const val MAX_BODY_EXTENT = 0.95
  private const val MIN_SIDE_ON_SCORE = 0.60
  private const val MIN_HIP_ANGLE = 150.0
  private const val MIN_KNEE_ANGLE = 155.0
  private const val MAX_BODY_TILT = 35.0
  private const val EPSILON = 1e-6
}
