package com.alarmwofeasibility.pose

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PoseFeasibilityActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private lateinit var statusView: TextView
  private lateinit var cameraExecutor: ExecutorService
  private var poseLandmarker: PoseLandmarker? = null
  private val inferenceInFlight = AtomicBoolean(false)
  private val pushUpStateMachine = PushUpStateMachine()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    previewView = PreviewView(this)
    statusView =
      TextView(this).apply {
        setBackgroundColor(0xcc101828.toInt())
        setTextColor(0xffffffff.toInt())
        textSize = 18f
        setPadding(24, 20, 24, 20)
        text = "Initializing MediaPipe..."
      }
    setContentView(
      FrameLayout(this).apply {
        addView(
          previewView,
          FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
          ),
        )
        addView(
          statusView,
          FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
          ),
        )
      },
    )

    cameraExecutor = Executors.newSingleThreadExecutor()
    initializePoseLandmarker()
    startCamera()
  }

  private fun initializePoseLandmarker() {
    val options =
      PoseLandmarker.PoseLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
        .setRunningMode(RunningMode.LIVE_STREAM)
        .setNumPoses(1)
        .setMinPoseDetectionConfidence(0.5f)
        .setMinPosePresenceConfidence(0.5f)
        .setMinTrackingConfidence(0.5f)
        .setResultListener(::onPoseResult)
        .setErrorListener { error ->
          inferenceInFlight.set(false)
          runOnUiThread { statusView.text = "MediaPipe error: ${error.message}" }
        }
        .build()
    poseLandmarker = PoseLandmarker.createFromOptions(this, options)
  }

  private fun startCamera() {
    val providerFuture = ProcessCameraProvider.getInstance(this)
    providerFuture.addListener(
      {
        val provider = providerFuture.get()
        val preview =
          Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis =
          ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, ::analyze) }
        provider.unbindAll()
        provider.bindToLifecycle(
          this,
          CameraSelector.DEFAULT_FRONT_CAMERA,
          preview,
          analysis,
        )
      },
      ContextCompat.getMainExecutor(this),
    )
  }

  private fun analyze(imageProxy: ImageProxy) {
    if (!inferenceInFlight.compareAndSet(false, true)) {
      imageProxy.close()
      return
    }

    runCatching {
      val bitmap = rotate(imageProxy.toBitmap(), imageProxy.imageInfo.rotationDegrees.toFloat())
      val mpImage = BitmapImageBuilder(bitmap).build()
      poseLandmarker?.detectAsync(mpImage, imageProxy.imageInfo.timestamp / 1_000_000L)
        ?: inferenceInFlight.set(false)
    }.onFailure { error ->
      inferenceInFlight.set(false)
      runOnUiThread { statusView.text = "Frame error: ${error.message}" }
    }
    imageProxy.close()
  }

  private fun onPoseResult(result: PoseLandmarkerResult, inputImage: MPImage) {
    inferenceInFlight.set(false)
    val landmarks = result.landmarks().firstOrNull()
    if (landmarks == null) {
      runOnUiThread { statusView.text = "Body not detected" }
      return
    }

    val update = pushUpStateMachine.process(selectVisibleSide(landmarks))
    runOnUiThread {
      statusView.text =
        "Reps: ${update.reps} | ${update.state}\n${update.feedback}\n" +
          "Elbow: ${update.elbowAngle?.toInt() ?: "-"}°, " +
          "Body: ${update.bodyAngle?.toInt() ?: "-"}°"
    }
  }

  private fun selectVisibleSide(landmarks: List<NormalizedLandmark>): PushUpSample {
    val left = side(landmarks, 11, 13, 15, 23, 27)
    val right = side(landmarks, 12, 14, 16, 24, 28)
    return if (meanVisibility(left) >= meanVisibility(right)) left else right
  }

  private fun side(
    landmarks: List<NormalizedLandmark>,
    shoulder: Int,
    elbow: Int,
    wrist: Int,
    hip: Int,
    ankle: Int,
  ) =
    PushUpSample(
      landmarks[shoulder].asPoint(),
      landmarks[elbow].asPoint(),
      landmarks[wrist].asPoint(),
      landmarks[hip].asPoint(),
      landmarks[ankle].asPoint(),
    )

  private fun NormalizedLandmark.asPoint() = PosePoint(x(), y(), visibility().orElse(0f))

  private fun meanVisibility(sample: PushUpSample): Float =
    listOf(sample.shoulder, sample.elbow, sample.wrist, sample.hip, sample.ankle)
      .map { it.visibility }
      .average()
      .toFloat()

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

  override fun onDestroy() {
    cameraExecutor.shutdown()
    poseLandmarker?.close()
    poseLandmarker = null
    super.onDestroy()
  }

  companion object {
    private const val MODEL_ASSET = "pose_landmarker_lite.task"
  }
}
