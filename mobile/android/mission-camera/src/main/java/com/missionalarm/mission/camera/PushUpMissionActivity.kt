package com.missionalarm.mission.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.missionalarm.core.data.CommitPushUpRepCommand
import com.missionalarm.core.data.EffectIdGenerator
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.PushUpMissionCoordinator
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.WallClock
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Native, offline Push-up verification surface. No frame or landmark leaves this Activity. */
class PushUpMissionActivity : ComponentActivity() {
  private val databaseDelegate = lazy { MissionAlarmDatabaseFactory.persistent(applicationContext) }
  private val database by databaseDelegate
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val sessionId = UUID.randomUUID().toString()
  private var cameraProvider: ProcessCameraProvider? = null
  private var analyzer: PushUpPoseAnalyzer? = null
  private var instanceId: String? = null
  private var profileVersion: String? = null
  private var expectedRevision = 0
  private var target = 0
  private var committedProgress = 0
  private var missionLoaded = false
  private var terminal = false

  private lateinit var preview: PreviewView
  private lateinit var progressText: TextView
  private lateinit var statusText: TextView
  private lateinit var phaseText: TextView
  private lateinit var qualityText: TextView
  private lateinit var progressBar: ProgressBar
  private lateinit var permissionPanel: LinearLayout
  private lateinit var recoveryButton: Button

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      permissionPanel.visibility = View.GONE
      if (missionLoaded) startCamera()
    } else {
      showPermissionRecovery()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    configureLockScreenPresentation()
    instanceId = validatedInstanceId(intent)
    if (instanceId == null) {
      finish()
      return
    }
    buildContent()
    loadMission()
  }

  override fun onResume() {
    super.onResume()
    if (missionLoaded && analyzer == null && hasCameraPermission() && !terminal) startCamera()
  }

  override fun onDestroy() {
    stopCamera()
    cameraExecutor.shutdownNow()
    persistenceExecutor.shutdownNow()
    if (databaseDelegate.isInitialized()) database.close()
    super.onDestroy()
  }

  private fun configureLockScreenPresentation() {
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
      )
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun loadMission() {
    statusText.setText(R.string.pushup_initializing)
    persistenceExecutor.execute {
      val id = instanceId ?: return@execute
      val loaded = runCatching {
        val snapshot = coordinator().start(id)
        val mission = database.runtimeDao().findMission(id) ?: error("mission snapshot missing")
        require(mission.pushupProfileVersion == PROFILE_VERSION) { "unsupported Push-up profile" }
        LoadedMission(snapshot.revision, snapshot.target, snapshot.committedProgress, PROFILE_VERSION)
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        loaded.fold(
          onSuccess = {
            expectedRevision = it.revision
            target = it.target
            committedProgress = it.progress
            profileVersion = it.profileVersion
            missionLoaded = true
            renderProgress()
            if (hasCameraPermission()) startCamera()
            else permissionLauncher.launch(Manifest.permission.CAMERA)
          },
          onFailure = { showTerminalError(R.string.pushup_mission_error) },
        )
      }
    }
  }

  private fun startCamera() {
    if (analyzer != null || terminal || !missionLoaded) return
    statusText.setText(R.string.pushup_find_body)
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      runCatching {
        val provider = future.get()
        cameraProvider = provider
        val cameraPreview = Preview.Builder().build().also {
          it.surfaceProvider = preview.surfaceProvider
        }
        val poseAnalyzer = PushUpPoseAnalyzer(
          context = applicationContext,
          target = target,
          initialCommittedReps = committedProgress,
          sessionId = sessionId,
          onUpdate = ::renderUpdate,
          onVerifiedRep = ::persistVerifiedRep,
          onError = { showTerminalError(R.string.pushup_model_error) },
        )
        analyzer = poseAnalyzer
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(cameraExecutor, poseAnalyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(
          this,
          CameraSelector.DEFAULT_FRONT_CAMERA,
          cameraPreview,
          analysis,
        )
      }.onFailure { showTerminalError(R.string.pushup_camera_error) }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun renderUpdate(update: PushUpUpdate) {
    runOnUiThread {
      if (terminal || isFinishing || isDestroyed) return@runOnUiThread
      statusText.setText(feedbackString(update.feedback))
      phaseText.setText(phaseString(update.phase))
      qualityText.text = qualityString(update.quality)
    }
  }

  private fun persistVerifiedRep(repSequence: Int) {
    persistenceExecutor.execute {
      if (terminal) return@execute
      val id = instanceId ?: return@execute
      val profile = profileVersion ?: return@execute
      val result = runCatching {
        coordinator().commitVerifiedRep(
          CommitPushUpRepCommand(
            commandId = CommandId.parse(UUID.randomUUID().toString()),
            instanceId = id,
            expectedRevision = expectedRevision,
            sessionId = sessionId,
            repSequence = repSequence,
            profileVersion = profile,
          ),
        )
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        result.fold(
          onSuccess = {
            expectedRevision = it.instanceRevision
            committedProgress = it.committedProgress
            renderProgress()
            if (it.completed) deliverCompletion(it.promotedInstanceId)
          },
          onFailure = { showTerminalError(R.string.pushup_progress_error) },
        )
      }
    }
  }

  private fun deliverCompletion(promotedInstanceId: String?) {
    if (terminal) return
    terminal = true
    stopCamera()
    statusText.setText(R.string.pushup_complete)
    setResult(RESULT_OK, Intent().apply {
      putExtra(EXTRA_INSTANCE_ID, instanceId)
      putExtra(EXTRA_FINAL_PROGRESS, committedProgress)
      putExtra(EXTRA_PROMOTED_INSTANCE_ID, promotedInstanceId)
    })
    statusText.postDelayed({ if (!isFinishing) finish() }, COMPLETION_DELAY_MS)
  }

  private fun stopCamera() {
    cameraProvider?.unbindAll()
    cameraProvider = null
    analyzer?.close()
    analyzer = null
  }

  private fun showPermissionRecovery() {
    permissionPanel.visibility = View.VISIBLE
    recoveryButton.setText(R.string.pushup_open_settings)
    recoveryButton.setOnClickListener {
      if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
      } else {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.fromParts("package", packageName, null)
        })
      }
    }
  }

  private fun showTerminalError(message: Int) {
    runOnUiThread {
      if (terminal || isFinishing || isDestroyed) return@runOnUiThread
      stopCamera()
      statusText.setText(message)
      permissionPanel.visibility = View.VISIBLE
      recoveryButton.setText(R.string.pushup_retry)
      recoveryButton.setOnClickListener {
        permissionPanel.visibility = View.GONE
        startCamera()
      }
    }
  }

  private fun renderProgress() {
    progressText.text = getString(R.string.pushup_progress, committedProgress, target)
    progressBar.max = maxOf(1, target)
    progressBar.progress = committedProgress
  }

  private fun feedbackString(feedback: PushUpFeedback): Int = when (feedback) {
    PushUpFeedback.BODY_NOT_DETECTED -> R.string.pushup_find_body
    PushUpFeedback.FULL_BODY_REQUIRED -> R.string.pushup_full_body
    PushUpFeedback.TURN_SIDEWAYS -> R.string.pushup_turn_sideways
    PushUpFeedback.LOW_LIGHT -> R.string.pushup_low_light
    PushUpFeedback.STRAIGHTEN_BODY -> R.string.pushup_straighten
    PushUpFeedback.FIND_TOP_POSITION -> R.string.pushup_find_top
    PushUpFeedback.LOWER_BODY -> R.string.pushup_lower
    PushUpFeedback.PUSH_UP -> R.string.pushup_rise
    PushUpFeedback.REP_COUNTED -> R.string.pushup_rep_counted
    PushUpFeedback.MISSION_COMPLETE -> R.string.pushup_complete
  }

  private fun phaseString(phase: PushUpPhase): Int = when (phase) {
    PushUpPhase.SEEKING_BODY -> R.string.pushup_phase_seeking_body
    PushUpPhase.SEEKING_TOP -> R.string.pushup_phase_seeking_top
    PushUpPhase.TOP_CONFIRMED -> R.string.pushup_phase_top_confirmed
    PushUpPhase.DESCENDING -> R.string.pushup_phase_descending
    PushUpPhase.BOTTOM_CONFIRMED -> R.string.pushup_phase_bottom_confirmed
    PushUpPhase.ASCENDING -> R.string.pushup_phase_ascending
    PushUpPhase.COOLDOWN -> R.string.pushup_phase_cooldown
    PushUpPhase.COMPLETE -> R.string.pushup_phase_complete
  }

  private fun qualityString(quality: PushUpQualityStatus): String {
    val ready = quality.poseDetected && quality.fullBodyVisible && quality.sideOn &&
      quality.lightSufficient && quality.alignmentValid
    if (ready) return getString(R.string.pushup_quality_ready)
    fun mark(passed: Boolean) = getString(
      if (passed) R.string.pushup_gate_pass else R.string.pushup_gate_wait,
    )
    return getString(
      R.string.pushup_quality_body,
      mark(quality.poseDetected),
      mark(quality.fullBodyVisible),
      mark(quality.sideOn),
      mark(quality.lightSufficient),
      mark(quality.alignmentValid),
    )
  }

  private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.CAMERA,
  ) == PackageManager.PERMISSION_GRANTED

  private fun buildContent() {
    preview = PreviewView(this).apply {
      id = R.id.pushup_preview
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    progressText = textView(24, Color.WHITE).apply {
      id = R.id.pushup_progress
      setTypeface(typeface, Typeface.BOLD)
    }
    statusText = textView(16, Color.WHITE).apply {
      id = R.id.pushup_status
      gravity = Gravity.CENTER
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    phaseText = textView(12, Color.rgb(255, 181, 71)).apply {
      id = R.id.pushup_phase
      setTypeface(typeface, Typeface.BOLD)
      setText(R.string.pushup_phase_seeking_body)
    }
    qualityText = textView(12, Color.rgb(225, 232, 237)).apply {
      id = R.id.pushup_quality
      gravity = Gravity.CENTER
      text = qualityString(PushUpQualityStatus())
    }
    progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      progressTintList = ColorStateList.valueOf(Color.rgb(255, 181, 71))
      progressBackgroundTintList = ColorStateList.valueOf(Color.argb(120, 255, 255, 255))
    }
    recoveryButton = Button(this).apply {
      minHeight = dp(50)
      setTextColor(Color.WHITE)
      background = rounded(Color.rgb(82, 109, 130), 16)
    }
    permissionPanel = LinearLayout(this).apply {
      id = R.id.pushup_recovery_panel
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(24), dp(20), dp(24), dp(20))
      background = rounded(Color.argb(238, 16, 24, 32), 20)
      visibility = View.GONE
      addView(textView(20, Color.WHITE).apply {
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setText(R.string.pushup_recovery_title)
      })
      addView(textView(14, Color.rgb(205, 216, 224)).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(14))
        setText(R.string.pushup_recovery_body)
      })
      addView(recoveryButton, LinearLayout.LayoutParams(-1, dp(50)))
    }
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(10), dp(16), dp(12))
      background = rounded(Color.argb(190, 16, 24, 32), 16)
      addView(textView(9, Color.rgb(255, 181, 71)).apply {
        letterSpacing = 0.14f
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.pushup_eyebrow)
      })
      addView(progressText)
      addView(progressBar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(4) })
    }
    val footer = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(20), dp(9), dp(20), dp(10))
      background = rounded(Color.argb(190, 16, 24, 32), 18)
      addView(phaseText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
      addView(statusText, LinearLayout.LayoutParams(-1, dp(30)))
    }
    val calibration = FrameLayout(this).apply {
      setPadding(dp(16), dp(10), dp(16), dp(10))
      background = rounded(Color.argb(178, 16, 24, 32), 16)
      addView(qualityText, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }
    val backButton = Button(this).apply {
      id = R.id.pushup_back
      minHeight = dp(46)
      setText(R.string.pushup_back_to_alarm)
      setTextColor(Color.WHITE)
      textSize = 13f
      background = rounded(Color.argb(190, 30, 43, 54), 15)
      setOnClickListener { finish() }
    }
    val topHud = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.TOP
      addView(header, LinearLayout.LayoutParams(dp(190), -2))
      addView(calibration, LinearLayout.LayoutParams(0, -2, 1f).apply {
        leftMargin = dp(10)
        rightMargin = dp(10)
      })
      addView(backButton, LinearLayout.LayoutParams(dp(170), dp(46)))
    }
    setContentView(FrameLayout(this).apply {
      setBackgroundColor(Color.rgb(16, 24, 32))
      addView(preview, FrameLayout.LayoutParams(-1, -1))
      addView(View(this@PushUpMissionActivity).apply {
        setBackgroundColor(Color.argb(35, 0, 0, 0))
      }, FrameLayout.LayoutParams(-1, -1))
      addView(topHud, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
        leftMargin = dp(14)
        rightMargin = dp(14)
        topMargin = dp(14)
      })
      addView(footer, FrameLayout.LayoutParams(dp(480), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        bottomMargin = dp(14)
      })
      addView(permissionPanel, FrameLayout.LayoutParams(dp(420), -2, Gravity.CENTER))
    })
  }

  private fun coordinator() = PushUpMissionCoordinator(
    database,
    WallClock { System.currentTimeMillis() },
    EffectIdGenerator { UUID.randomUUID().toString() },
  )

  private fun textView(sizeSp: Int, color: Int) = TextView(this).apply {
    textSize = sizeSp.toFloat()
    setTextColor(color)
  }

  private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private data class LoadedMission(
    val revision: Int,
    val target: Int,
    val progress: Int,
    val profileVersion: String,
  )

  companion object {
    private const val EXTRA_INSTANCE_ID = "pushUpInstanceId"
    private const val EXTRA_FINAL_PROGRESS = "pushUpFinalProgress"
    private const val EXTRA_PROMOTED_INSTANCE_ID = "pushUpPromotedInstanceId"
    private const val PROFILE_VERSION = "pushup-profile-v0"
    private const val COMPLETION_DELAY_MS = 650L

    fun intent(context: Context, instanceId: String): Intent {
      UUID.fromString(instanceId)
      return Intent(context, PushUpMissionActivity::class.java).apply {
        putExtra(EXTRA_INSTANCE_ID, instanceId)
      }
    }

    fun validatedInstanceId(intent: Intent?): String? = intent?.getStringExtra(EXTRA_INSTANCE_ID)
      ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }

    fun completedInstanceId(intent: Intent?): String? = validatedInstanceId(intent)

    fun finalProgress(intent: Intent?): Int? = intent?.takeIf {
      it.hasExtra(EXTRA_FINAL_PROGRESS)
    }?.getIntExtra(EXTRA_FINAL_PROGRESS, -1)?.takeIf { it >= 0 }

    fun promotedInstanceId(intent: Intent?): String? = intent
      ?.getStringExtra(EXTRA_PROMOTED_INSTANCE_ID)
      ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
  }
}
