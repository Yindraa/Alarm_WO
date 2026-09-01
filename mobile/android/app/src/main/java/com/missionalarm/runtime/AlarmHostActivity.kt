package com.missionalarm.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.missionalarm.app.R
import com.missionalarm.core.data.ActiveRuntimeSnapshot
import com.missionalarm.core.data.DirectBootDatabaseFactory
import com.missionalarm.core.data.EffectIdGenerator
import com.missionalarm.core.data.EmergencyDismissCoordinator
import com.missionalarm.core.data.EmergencyDismissException
import com.missionalarm.core.data.LeaseOwnerGenerator
import com.missionalarm.core.data.MathMissionCoordinator
import com.missionalarm.core.data.MathMissionException
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.PresentationEffectRunner
import com.missionalarm.core.data.RuntimeEffectRunner
import com.missionalarm.core.data.RuntimeStopEffectRunner
import com.missionalarm.core.data.ScanMissionCoordinator
import com.missionalarm.core.data.ScanMissionException
import com.missionalarm.core.domain.WallClock
import com.missionalarm.mission.camera.CodeScanActivity
import com.missionalarm.mission.camera.PushUpMissionActivity
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/** Native, database-backed recovery shell for the currently attended alarm instance. */
class AlarmHostActivity : Activity() {
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "mission-alarm-host").apply { isDaemon = true }
  }
  private val databaseDelegate = lazy { MissionAlarmDatabaseFactory.persistent(applicationContext) }
  private val database by databaseDelegate
  private var backCallback: OnBackInvokedCallback? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var activeSnapshot: ActiveRuntimeSnapshot? = null
  private var emergencyDialog: AlertDialog? = null
  private var pendingScan: PendingScan? = null
  private var pendingPushUpInstanceId: String? = null
  private var completingScan = false
  private var completingPushUp = false

  private lateinit var loading: ProgressBar
  private lateinit var time: TextView
  private lateinit var title: TextView
  private lateinit var detail: TextView
  private lateinit var progress: TextView
  private lateinit var queue: TextView
  private lateinit var mathWorkspace: LinearLayout
  private lateinit var mathEyebrow: TextView
  private lateinit var mathProgressTrack: LinearLayout
  private lateinit var mathQuestion: TextView
  private lateinit var mathAnswer: EditText
  private lateinit var mathFeedback: TextView
  private lateinit var mathKeypad: LinearLayout
  private val mathKeypadButtons = mutableListOf<Button>()
  private lateinit var primaryAction: Button
  private lateinit var emergencyStatus: TextView
  private lateinit var emergencyAction: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureLockScreenPresentation()
    registerBackGuard()
    setContentView(buildContent())
    refreshFromCanonicalState()
  }

  override fun onResume() {
    super.onResume()
    if (::loading.isInitialized && pendingScan == null && pendingPushUpInstanceId == null &&
      !completingScan && !completingPushUp
    ) {
      refreshFromCanonicalState()
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    refreshFromCanonicalState()
  }

  @Deprecated("Legacy result API is intentionally scoped to this internal native Activity pair")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    when (requestCode) {
      REQUEST_SCAN_CODE -> {
        val pending = pendingScan
        pendingScan = null
        if (resultCode != RESULT_OK || pending == null ||
          CodeScanActivity.sessionToken(data) != pending.sessionToken ||
          CodeScanActivity.codeFormat(data) == null
        ) {
          refreshFromCanonicalState()
          return
        }
        completeScanMission(pending)
      }
      REQUEST_PUSH_UP -> handlePushUpResult(resultCode, data)
    }
  }

  override fun onPause() {
    emergencyDialog?.dismiss()
    super.onPause()
  }

  @SuppressLint("GestureBackNavigation")
  @Deprecated("Fallback for Android releases before OnBackInvokedDispatcher")
  override fun onBackPressed() = Unit

  override fun onDestroy() {
    mainHandler.removeCallbacksAndMessages(null)
    emergencyDialog?.dismiss()
    emergencyDialog = null
    if (Build.VERSION.SDK_INT >= 33) {
      backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
      backCallback = null
    }
    executor.shutdownNow()
    if (databaseDelegate.isInitialized()) database.close()
    super.onDestroy()
  }

  private fun registerBackGuard() {
    if (Build.VERSION.SDK_INT < 33) return
    backCallback = OnBackInvokedCallback { }.also {
      onBackInvokedDispatcher.registerOnBackInvokedCallback(
        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        it,
      )
    }
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
    window.statusBarColor = getColor(R.color.alarm_host_background)
    window.navigationBarColor = getColor(R.color.alarm_host_background)
  }

  private fun refreshFromCanonicalState() {
    loading.visibility = View.VISIBLE
    executor.execute {
      val requestedId = validatedRequestedInstanceId(intent)
      val result = runCatching { database.runtimeDao().loadActiveRuntimeSnapshot() }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        loading.visibility = View.GONE
        result.fold(
          onSuccess = { snapshot ->
            if (snapshot == null) renderNoActiveAlarm()
            else renderActive(snapshot, requestedId != null && requestedId != snapshot.instanceId)
          },
          onFailure = { renderRecoveryFailure() },
        )
      }
    }
  }

  private fun renderActive(snapshot: ActiveRuntimeSnapshot, recoveredDifferentInstance: Boolean) {
    activeSnapshot = snapshot
    mathWorkspace.visibility = View.GONE
    time.textSize = 48f
    queue.visibility = View.VISIBLE
    val routeDecision = MissionRouteResolver.resolve(snapshot)
    time.text = DateFormat.getTimeFormat(this).format(Date())
    title.text = snapshot.label
    detail.text = getString(
      R.string.alarm_host_mission_detail,
      missionName(snapshot.missionType),
    )
    progress.text = getString(
      R.string.alarm_host_progress,
      snapshot.committedProgress,
      snapshot.target,
    )
    queue.text = if (snapshot.queuedCount == 0) {
      getString(R.string.alarm_host_queue_empty)
    } else {
      resources.getQuantityString(
        R.plurals.alarm_host_queue_count,
        snapshot.queuedCount,
        snapshot.queuedCount,
      )
    }
    primaryAction.text = missionAction(snapshot)
    when (routeDecision) {
      is MissionRouteDecision.Ready -> {
        primaryAction.isEnabled = true
        primaryAction.setOnClickListener { routeMission(snapshot.instanceId) }
        emergencyStatus.text = if (recoveredDifferentInstance) {
          getString(R.string.alarm_host_newer_instance_recovered)
        } else {
          getString(R.string.alarm_host_recovery_ready)
        }
      }
      is MissionRouteDecision.Recovery -> {
        primaryAction.setText(R.string.alarm_host_retry_mission)
        primaryAction.isEnabled = true
        primaryAction.setOnClickListener { refreshFromCanonicalState() }
        emergencyStatus.setText(R.string.alarm_host_mission_recovery_required)
      }
    }
    emergencyAction.visibility = View.VISIBLE
    emergencyAction.isEnabled = true
    emergencyAction.setOnClickListener { showEmergencyHoldDialog(snapshot) }
  }

  private fun routeMission(expectedInstanceId: String) {
    primaryAction.isEnabled = false
    loading.visibility = View.VISIBLE
    executor.execute {
      val result = runCatching { database.runtimeDao().loadActiveRuntimeSnapshot() }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        loading.visibility = View.GONE
        val latest = result.getOrNull()
        if (latest == null || latest.instanceId != expectedInstanceId) {
          refreshFromCanonicalState()
          return@runOnUiThread
        }
        when (val decision = MissionRouteResolver.resolve(latest)) {
          is MissionRouteDecision.Ready -> renderMissionRouteHook(latest, decision.destination)
          is MissionRouteDecision.Recovery -> renderMissionRouteRecovery(latest)
        }
      }
    }
  }

  private fun renderMissionRouteHook(
    snapshot: ActiveRuntimeSnapshot,
    destination: MissionDestination,
  ) {
    when (destination) {
      MissionDestination.EMBEDDED_MATH -> startMathMission(snapshot)
      MissionDestination.NATIVE_PUSH_UP -> startPushUpMission(snapshot)
      MissionDestination.NATIVE_QR -> startScanMission(snapshot)
    }
  }

  private fun startScanMission(snapshot: ActiveRuntimeSnapshot) {
    primaryAction.isEnabled = false
    loading.visibility = View.VISIBLE
    executor.execute {
      val result = runCatching { scanCoordinator().start(snapshot.instanceId) }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        loading.visibility = View.GONE
        result.fold(
          onSuccess = { started ->
            val token = UUID.randomUUID().toString()
            pendingScan = PendingScan(token, started.instanceId, started.revision)
            startActivityForResult(CodeScanActivity.intent(this, token), REQUEST_SCAN_CODE)
          },
          onFailure = { renderMissionRouteRecovery(snapshot) },
        )
      }
    }
  }

  private fun completeScanMission(pending: PendingScan) {
    completingScan = true
    loading.visibility = View.VISIBLE
    executor.execute {
      val result = runCatching {
        scanCoordinator().complete(pending.instanceId, pending.expectedRevision)
      }
      val outcome = result.getOrNull()
      if (outcome?.completed == true) drainTerminalRuntimeEffects()
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        completingScan = false
        loading.visibility = View.GONE
        when {
          result.exceptionOrNull() is ScanMissionException -> refreshFromCanonicalState()
          result.isFailure || outcome == null -> renderRecoveryFailure()
          outcome.promotedInstanceId != null -> refreshFromCanonicalState()
          else -> renderScanSuccess()
        }
      }
    }
  }

  private fun renderScanSuccess() {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    title.setText(R.string.alarm_host_scan_success_title)
    detail.setText(R.string.alarm_host_scan_success_detail)
    progress.text = getString(R.string.alarm_host_progress, 1, 1)
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_close)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { finish() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.GONE
  }

  private fun startPushUpMission(snapshot: ActiveRuntimeSnapshot) {
    pendingPushUpInstanceId = snapshot.instanceId
    startActivityForResult(
      PushUpMissionActivity.intent(this, snapshot.instanceId),
      REQUEST_PUSH_UP,
    )
  }

  private fun handlePushUpResult(resultCode: Int, data: Intent?) {
    val pendingInstanceId = pendingPushUpInstanceId
    pendingPushUpInstanceId = null
    val completedId = PushUpMissionActivity.completedInstanceId(data)
    val finalProgress = PushUpMissionActivity.finalProgress(data)
    if (resultCode != RESULT_OK || completedId == null || finalProgress == null ||
      (pendingInstanceId != null && completedId != pendingInstanceId)
    ) {
      refreshFromCanonicalState()
      return
    }
    completingPushUp = true
    loading.visibility = View.VISIBLE
    val promotedId = PushUpMissionActivity.promotedInstanceId(data)
    executor.execute {
      val drained = runCatching { drainTerminalRuntimeEffects() }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        completingPushUp = false
        loading.visibility = View.GONE
        when {
          drained.isFailure -> renderRecoveryFailure()
          promotedId != null -> refreshFromCanonicalState()
          else -> renderPushUpSuccess(finalProgress)
        }
      }
    }
  }

  private fun renderPushUpSuccess(progressValue: Int) {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    title.setText(R.string.alarm_host_pushup_success_title)
    detail.setText(R.string.alarm_host_pushup_success_detail)
    progress.text = getString(R.string.alarm_host_progress, progressValue, progressValue)
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_close)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { finish() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.GONE
  }

  private fun startMathMission(snapshot: ActiveRuntimeSnapshot) {
    primaryAction.isEnabled = false
    loading.visibility = View.VISIBLE
    executor.execute {
      val result = runCatching { mathCoordinator().start(snapshot.instanceId) }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        loading.visibility = View.GONE
        result.fold(
          onSuccess = { renderMathMission(it) },
          onFailure = { refreshFromCanonicalState() },
        )
      }
    }
  }

  private fun renderMathMission(snapshot: ActiveRuntimeSnapshot, correctFeedback: Boolean = false) {
    val question = snapshot.mathQuestion
    if (question == null || snapshot.missionType != "MATH") {
      renderMissionRouteRecovery(snapshot)
      return
    }
    activeSnapshot = snapshot
    time.textSize = 20f
    queue.visibility = View.GONE
    detail.setText(R.string.alarm_host_math_instruction)
    progress.text = getString(
      R.string.alarm_host_progress,
      snapshot.committedProgress,
      snapshot.target,
    )
    mathQuestion.text = getString(
      R.string.alarm_host_math_question,
      question.operandA,
      operationSymbol(question.operation),
      question.operandB,
    )
    mathEyebrow.text = getString(
      R.string.alarm_host_math_eyebrow,
      snapshot.committedProgress + 1,
      snapshot.target,
    )
    renderMathProgress(snapshot.committedProgress, snapshot.target)
    mathAnswer.text.clear()
    setMathInputEnabled(true)
    mathFeedback.setText(
      if (correctFeedback) R.string.alarm_host_math_correct else R.string.alarm_host_math_feedback_ready,
    )
    mathFeedback.setTextColor(
      getColor(if (correctFeedback) R.color.alarm_host_success else R.color.alarm_host_secondary),
    )
    mathWorkspace.visibility = View.VISIBLE
    primaryAction.setText(R.string.alarm_host_math_submit)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { submitMathAnswer(snapshot) }
    emergencyStatus.setText(R.string.alarm_host_math_safety_notice)
  }

  private fun submitMathAnswer(snapshot: ActiveRuntimeSnapshot) {
    val answer = mathAnswer.text.toString().trim().toIntOrNull()
    if (answer == null) {
      mathFeedback.setText(R.string.alarm_host_math_answer_required)
      mathFeedback.setTextColor(getColor(R.color.alarm_host_danger))
      return
    }
    val ordinal = snapshot.mathQuestion?.ordinal ?: run {
      refreshFromCanonicalState()
      return
    }
    setMathInputEnabled(false)
    primaryAction.isEnabled = false
    loading.visibility = View.VISIBLE
    executor.execute {
      val result = runCatching {
        mathCoordinator().submitAnswer(
          snapshot.instanceId,
          snapshot.revision,
          ordinal,
          answer,
        )
      }
      val outcome = result.getOrNull()
      val latest = if (outcome?.correct == true && !outcome.completed) {
        runCatching { database.runtimeDao().loadActiveRuntimeSnapshot() }.getOrNull()
      } else {
        null
      }
      if (outcome?.completed == true) drainTerminalRuntimeEffects()
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        loading.visibility = View.GONE
        when {
          result.exceptionOrNull() is MathMissionException -> refreshFromCanonicalState()
          result.isFailure -> renderMissionRouteRecovery(snapshot)
          outcome == null -> renderMissionRouteRecovery(snapshot)
          !outcome.correct -> {
            setMathInputEnabled(true)
            mathAnswer.selectAll()
            mathFeedback.setText(R.string.alarm_host_math_incorrect)
            mathFeedback.setTextColor(getColor(R.color.alarm_host_danger))
            primaryAction.isEnabled = true
          }
          outcome.completed && outcome.promotedInstanceId != null -> refreshFromCanonicalState()
          outcome.completed -> renderMathSuccess(outcome.committedProgress, snapshot.target)
          latest != null -> renderMathMission(latest, correctFeedback = true)
          else -> refreshFromCanonicalState()
        }
      }
    }
  }

  private fun renderMathSuccess(progressValue: Int, target: Int) {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    title.setText(R.string.alarm_host_math_success_title)
    detail.setText(R.string.alarm_host_math_success_detail)
    progress.text = getString(R.string.alarm_host_progress, progressValue, target)
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_close)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { finish() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.GONE
  }

  private fun mathCoordinator() = MathMissionCoordinator(
    database,
    WallClock { System.currentTimeMillis() },
    EffectIdGenerator { UUID.randomUUID().toString() },
  )

  private fun scanCoordinator() = ScanMissionCoordinator(
    database,
    WallClock { System.currentTimeMillis() },
    EffectIdGenerator { UUID.randomUUID().toString() },
  )

  private fun drainTerminalRuntimeEffects() {
    val clock = WallClock { System.currentTimeMillis() }
    val owner = LeaseOwnerGenerator { UUID.randomUUID().toString() }
    RuntimeStopEffectRunner(
      database,
      clock,
      owner,
      AndroidAlarmRuntimeStopper(applicationContext),
    ).drain()
    RuntimeEffectRunner(
      database,
      clock,
      owner,
      AndroidAlarmRuntimeStarter(applicationContext),
    ).drain()
    PresentationEffectRunner(
      database,
      clock,
      owner,
      AndroidAlarmHostPresenter(applicationContext),
    ).drain()
  }

  private fun renderMissionRouteRecovery(snapshot: ActiveRuntimeSnapshot) {
    activeSnapshot = snapshot
    mathWorkspace.visibility = View.GONE
    detail.setText(R.string.alarm_host_mission_recovery_required)
    primaryAction.setText(R.string.alarm_host_retry_mission)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { refreshFromCanonicalState() }
    emergencyStatus.setText(R.string.alarm_host_route_safety_notice)
  }

  private fun renderNoActiveAlarm() {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    time.text = DateFormat.getTimeFormat(this).format(Date())
    title.setText(R.string.alarm_host_no_active_title)
    detail.setText(R.string.alarm_host_no_active_detail)
    progress.text = ""
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_close)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { finish() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.GONE
  }

  private fun renderRecoveryFailure() {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    time.text = DateFormat.getTimeFormat(this).format(Date())
    title.setText(R.string.alarm_host_recovery_failed_title)
    detail.setText(R.string.alarm_host_recovery_failed_detail)
    progress.text = ""
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_retry)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { refreshFromCanonicalState() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.VISIBLE
    emergencyAction.isEnabled = false
  }

  private fun renderEmergencyResult() {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    time.text = DateFormat.getTimeFormat(this).format(Date())
    title.setText(R.string.alarm_host_emergency_complete_title)
    detail.setText(R.string.alarm_host_emergency_complete_detail)
    progress.text = ""
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_close)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { finish() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.GONE
  }

  private fun renderEmergencyFallback() {
    activeSnapshot = null
    mathWorkspace.visibility = View.GONE
    title.setText(R.string.alarm_host_emergency_fallback_title)
    detail.setText(R.string.alarm_host_emergency_fallback_detail)
    progress.text = ""
    queue.text = ""
    primaryAction.setText(R.string.alarm_host_close)
    primaryAction.isEnabled = true
    primaryAction.setOnClickListener { finish() }
    emergencyStatus.text = ""
    emergencyAction.visibility = View.GONE
  }

  private fun buildContent(): View {
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(20.dp, 40.dp, 20.dp, 32.dp)
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    loading = ProgressBar(this).apply {
      id = R.id.alarm_host_loading
      contentDescription = getString(R.string.alarm_host_loading)
      indeterminateTintList = ColorStateList.valueOf(getColor(R.color.alarm_host_primary))
    }
    time = TextView(this).apply {
      id = R.id.alarm_host_time
      textSize = 48f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
      setTextColor(getColor(R.color.alarm_host_hero))
      if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    title = TextView(this).apply {
      id = R.id.alarm_host_title
      textSize = 26f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
      setPadding(0, 24.dp, 0, 8.dp)
      setTextColor(getColor(R.color.alarm_host_text))
      if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    detail = bodyText(R.id.alarm_host_detail)
    progress = bodyText(R.id.alarm_host_progress).apply {
      setTextColor(getColor(R.color.alarm_host_primary))
      setTypeface(typeface, Typeface.BOLD)
      setPadding(16.dp, 10.dp, 16.dp, 10.dp)
      background = roundedBackground(R.color.alarm_host_primary_surface, radiusDp = 999)
    }
    queue = bodyText(R.id.alarm_host_queue).apply { textSize = 15f }
    mathQuestion = TextView(this).apply {
      id = R.id.alarm_host_math_question
      textSize = 36f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
      setPadding(0, 20.dp, 0, 12.dp)
      setTextColor(getColor(R.color.alarm_host_text))
      if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    mathEyebrow = TextView(this).apply {
      id = R.id.alarm_host_math_eyebrow
      textSize = 13f
      gravity = Gravity.CENTER
      letterSpacing = 0.08f
      setTypeface(typeface, Typeface.BOLD)
      setTextColor(getColor(R.color.alarm_host_primary))
      if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    mathProgressTrack = LinearLayout(this).apply {
      id = R.id.alarm_host_math_progress_track
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      contentDescription = getString(R.string.alarm_host_progress, 0, 1)
    }
    mathAnswer = EditText(this).apply {
      id = R.id.alarm_host_math_answer
      inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
      textSize = 24f
      gravity = Gravity.CENTER
      hint = getString(R.string.alarm_host_math_answer_hint)
      contentDescription = getString(R.string.alarm_host_math_answer_description)
      maxLines = 1
      filters = arrayOf(InputFilter.LengthFilter(11))
      showSoftInputOnFocus = false
      minHeight = 56.dp
      setPadding(16.dp, 10.dp, 16.dp, 10.dp)
      setTextColor(getColor(R.color.alarm_host_text))
      setHintTextColor(getColor(R.color.alarm_host_secondary))
      background = roundedBackground(
        R.color.alarm_host_surface,
        R.color.alarm_host_border,
        radiusDp = 12,
      )
    }
    mathQuestion.labelFor = mathAnswer.id
    mathFeedback = bodyText(R.id.alarm_host_math_feedback).apply {
      textSize = 15f
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }
    mathKeypad = buildMathKeypad()
    mathWorkspace = LinearLayout(this).apply {
      id = R.id.alarm_host_math_workspace
      orientation = LinearLayout.VERTICAL
      visibility = View.GONE
      setPadding(18.dp, 18.dp, 18.dp, 18.dp)
      background = roundedBackground(
        R.color.alarm_host_surface,
        R.color.alarm_host_border,
        radiusDp = 18,
      )
      addView(mathEyebrow, matchWrap())
      addView(mathProgressTrack, matchWrap(topMargin = 14.dp))
      addView(mathQuestion, matchWrap(topMargin = 4.dp))
      addView(mathAnswer, matchWrap(topMargin = 8.dp))
      addView(mathFeedback, matchWrap())
      addView(mathKeypad, matchWrap(topMargin = 8.dp))
    }
    primaryAction = Button(this).apply {
      id = R.id.alarm_host_primary_action
      isAllCaps = false
      setPadding(24.dp, 12.dp, 24.dp, 12.dp)
      minHeight = 52.dp
      setTextColor(getColor(R.color.alarm_host_on_primary))
      setTypeface(typeface, Typeface.BOLD)
      background = interactiveBackground(
        R.color.alarm_host_primary,
        R.color.alarm_host_surface_muted,
        radiusDp = 16,
      )
    }
    emergencyStatus = bodyText(R.id.alarm_host_recovery_status).apply {
      textSize = 14f
      setPadding(0, 24.dp, 0, 0)
    }
    emergencyAction = Button(this).apply {
      id = R.id.alarm_host_emergency_action
      isAllCaps = false
      setText(R.string.alarm_host_emergency_action)
      contentDescription = getString(R.string.alarm_host_emergency_action_description)
      minHeight = 52.dp
      setTextColor(getColor(R.color.alarm_host_danger))
      background = interactiveBackground(
        R.color.alarm_host_surface,
        R.color.alarm_host_primary_surface,
        R.color.alarm_host_danger,
        radiusDp = 16,
      )
    }
    content.addView(loading)
    content.addView(time, matchWrap())
    content.addView(title, matchWrap())
    content.addView(detail, matchWrap())
    content.addView(progress, matchWrap(topMargin = 12.dp, horizontalMargin = 48.dp))
    content.addView(queue, matchWrap())
    content.addView(mathWorkspace, matchWrap(topMargin = 16.dp))
    content.addView(primaryAction, matchWrap(topMargin = 32.dp))
    content.addView(emergencyStatus, matchWrap())
    content.addView(emergencyAction, matchWrap(topMargin = 24.dp))
    return ScrollView(this).apply {
      setBackgroundColor(getColor(R.color.alarm_host_background))
      isFillViewport = true
      addView(content)
    }
  }

  private fun showEmergencyHoldDialog(snapshot: ActiveRuntimeSnapshot) {
    if (emergencyDialog?.isShowing == true || activeSnapshot?.instanceId != snapshot.instanceId) return
    val holdController = ContinuousHoldController(MonotonicClock(SystemClock::elapsedRealtime))
    val progressText = bodyText(View.generateViewId()).apply {
      setText(R.string.alarm_host_emergency_hold_ready)
    }
    val holdButton = Button(this).apply {
      isAllCaps = false
      setText(R.string.alarm_host_emergency_hold_button)
      contentDescription = getString(R.string.alarm_host_emergency_hold_description)
    }
    val dialogContent = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(24.dp, 8.dp, 24.dp, 8.dp)
      addView(TextView(this@AlarmHostActivity).apply {
        textSize = 17f
        setText(R.string.alarm_host_emergency_warning)
      }, matchWrap())
      addView(progressText, matchWrap(topMargin = 16.dp))
      addView(holdButton, matchWrap(topMargin = 16.dp))
    }
    val dialog = AlertDialog.Builder(this)
      .setTitle(R.string.alarm_host_emergency_dialog_title)
      .setView(dialogContent)
      .setNegativeButton(R.string.alarm_host_emergency_cancel, null)
      .create()
    var activePointerId = MotionEvent.INVALID_POINTER_ID
    var completed = false
    val ticker = object : Runnable {
      override fun run() {
        if (!dialog.isShowing || activePointerId == MotionEvent.INVALID_POINTER_ID || completed) return
        val state = holdController.progress()
        progressText.text = getString(
          R.string.alarm_host_emergency_hold_progress,
          ((state.remainingMs + 999) / 1_000).coerceAtLeast(0),
        )
        holdButton.text = getString(
          R.string.alarm_host_emergency_hold_percent,
          (state.fraction * 100).toInt(),
        )
        if (state.completed) {
          completed = true
          holdButton.isEnabled = false
          executeEmergencyDismiss(snapshot)
          return
        }
        mainHandler.postDelayed(this, HOLD_TICK_MS)
      }
    }
    holdButton.setOnTouchListener { view, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          activePointerId = event.getPointerId(0)
          holdController.begin()
          view.isPressed = true
          mainHandler.post(ticker)
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val index = event.findPointerIndex(activePointerId)
          val inside = index >= 0 && event.getX(index) in 0f..view.width.toFloat() &&
            event.getY(index) in 0f..view.height.toFloat()
          if (!inside) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            holdController.cancel()
            view.isPressed = false
            mainHandler.removeCallbacks(ticker)
            progressText.setText(R.string.alarm_host_emergency_hold_ready)
          }
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (event.actionMasked == MotionEvent.ACTION_UP && completed) view.performClick()
          activePointerId = MotionEvent.INVALID_POINTER_ID
          view.isPressed = false
          mainHandler.removeCallbacks(ticker)
          if (!completed) {
            holdController.cancel()
            progressText.setText(R.string.alarm_host_emergency_hold_ready)
            holdButton.setText(R.string.alarm_host_emergency_hold_button)
          }
          true
        }
        MotionEvent.ACTION_POINTER_UP -> {
          if (event.getPointerId(event.actionIndex) == activePointerId) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            view.isPressed = false
            mainHandler.removeCallbacks(ticker)
            holdController.cancel()
            progressText.setText(R.string.alarm_host_emergency_hold_ready)
            holdButton.setText(R.string.alarm_host_emergency_hold_button)
          }
          true
        }
        else -> true
      }
    }
    dialog.setOnDismissListener {
      activePointerId = MotionEvent.INVALID_POINTER_ID
      holdController.cancel()
      mainHandler.removeCallbacks(ticker)
      if (emergencyDialog === dialog) emergencyDialog = null
    }
    emergencyDialog = dialog
    dialog.show()
  }

  private fun executeEmergencyDismiss(snapshot: ActiveRuntimeSnapshot) {
    emergencyAction.isEnabled = false
    executor.execute {
      val clock = WallClock { System.currentTimeMillis() }
      val stopAdapter = AndroidAlarmRuntimeStopper(applicationContext)
      val result = runCatching {
        val dismissal = EmergencyDismissCoordinator(
          database,
          clock,
          EffectIdGenerator { UUID.randomUUID().toString() },
        ).dismiss(snapshot.instanceId)
        val owner = LeaseOwnerGenerator { UUID.randomUUID().toString() }
        RuntimeStopEffectRunner(database, clock, owner, stopAdapter).drain()
        RuntimeEffectRunner(
          database,
          clock,
          owner,
          AndroidAlarmRuntimeStarter(applicationContext),
        ).drain()
        PresentationEffectRunner(
          database,
          clock,
          owner,
          AndroidAlarmHostPresenter(applicationContext),
        ).drain()
        dismissal
      }
      if (result.isFailure && result.exceptionOrNull() !is EmergencyDismissException) {
        runCatching { stopAdapter.stop(snapshot.instanceId) }
        runCatching {
          val boot = DirectBootDatabaseFactory.persistent(applicationContext)
          try {
            boot.directBootDao().recordEmergencyFallback(
              snapshot.occurrenceId,
              System.currentTimeMillis(),
            )
          } finally {
            boot.close()
          }
        }
      }
      runOnUiThread {
        emergencyDialog?.dismiss()
        when {
          result.isSuccess && result.getOrThrow().promotedInstanceId != null ->
            refreshFromCanonicalState()
          result.isSuccess -> renderEmergencyResult()
          result.exceptionOrNull() is EmergencyDismissException -> refreshFromCanonicalState()
          else -> renderEmergencyFallback()
        }
      }
    }
  }

  private fun bodyText(viewId: Int) = TextView(this).apply {
    id = viewId
    textSize = 18f
    gravity = Gravity.CENTER
    setPadding(0, 8.dp, 0, 8.dp)
    setTextColor(getColor(R.color.alarm_host_secondary))
  }

  private fun buildMathKeypad() = LinearLayout(this).apply {
    id = R.id.alarm_host_math_keypad
    orientation = LinearLayout.VERTICAL
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    listOf(
      listOf("1", "2", "3"),
      listOf("4", "5", "6"),
      listOf("7", "8", "9"),
      listOf("−", "0", "⌫"),
    ).forEach { keys ->
      addView(LinearLayout(this@AlarmHostActivity).apply {
        orientation = LinearLayout.HORIZONTAL
        keys.forEach { key ->
          addView(mathKeyButton(key), LinearLayout.LayoutParams(0, 54.dp, 1f).apply {
            setMargins(4.dp, 4.dp, 4.dp, 4.dp)
          })
        }
      }, matchWrap())
    }
  }

  private fun mathKeyButton(key: String) = Button(this).apply {
    isAllCaps = false
    text = key
    textSize = 20f
    minHeight = 48.dp
    setTextColor(
      getColor(if (key == "−" || key == "⌫") R.color.alarm_host_primary else R.color.alarm_host_text),
    )
    contentDescription = when (key) {
      "−" -> getString(R.string.alarm_host_math_toggle_sign)
      "⌫" -> getString(R.string.alarm_host_math_delete_digit)
      else -> key
    }
    background = interactiveBackground(
      R.color.alarm_host_surface_muted,
      R.color.alarm_host_primary_surface,
      radiusDp = 14,
    )
    setOnClickListener {
      when (key) {
        "−" -> toggleMathSign()
        "⌫" -> deleteMathDigit()
        else -> replaceSelectedMathText(key)
      }
    }
    mathKeypadButtons += this
  }

  private fun replaceSelectedMathText(value: String) {
    if (!mathAnswer.isEnabled) return
    val editable = mathAnswer.text
    val first = mathAnswer.selectionStart.takeIf { it >= 0 } ?: editable.length
    val second = mathAnswer.selectionEnd.takeIf { it >= 0 } ?: editable.length
    val start = minOf(first, second)
    val end = maxOf(first, second)
    editable.replace(start, end, value)
    mathAnswer.setSelection((start + value.length).coerceAtMost(editable.length))
  }

  private fun toggleMathSign() {
    if (!mathAnswer.isEnabled) return
    val current = mathAnswer.text.toString()
    val updated = if (current.startsWith("-")) current.drop(1) else "-$current"
    mathAnswer.setText(updated)
    mathAnswer.setSelection(updated.length)
  }

  private fun deleteMathDigit() {
    if (!mathAnswer.isEnabled) return
    val editable = mathAnswer.text
    val first = mathAnswer.selectionStart.takeIf { it >= 0 } ?: editable.length
    val second = mathAnswer.selectionEnd.takeIf { it >= 0 } ?: editable.length
    val start = minOf(first, second)
    val end = maxOf(first, second)
    when {
      start != end -> editable.delete(start, end)
      start > 0 -> editable.delete(start - 1, start)
    }
  }

  private fun setMathInputEnabled(enabled: Boolean) {
    mathAnswer.isEnabled = enabled
    mathKeypadButtons.forEach { button ->
      button.isEnabled = enabled
      button.alpha = if (enabled) 1f else 0.45f
    }
  }

  private fun renderMathProgress(committed: Int, target: Int) {
    mathProgressTrack.removeAllViews()
    repeat(target) { index ->
      val color = when {
        index < committed -> R.color.alarm_host_primary
        index == committed -> R.color.alarm_host_amber
        else -> R.color.alarm_host_border
      }
      mathProgressTrack.addView(View(this).apply {
        background = roundedBackground(color, radiusDp = 999)
      }, LinearLayout.LayoutParams(0, 7.dp, 1f).apply {
        setMargins(3.dp, 0, 3.dp, 0)
      })
    }
    mathProgressTrack.contentDescription = getString(R.string.alarm_host_progress, committed, target)
  }

  private fun matchWrap(topMargin: Int = 0, horizontalMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  ).apply {
    this.topMargin = topMargin
    marginStart = horizontalMargin
    marginEnd = horizontalMargin
  }

  private fun roundedBackground(
    fillColor: Int,
    strokeColor: Int? = null,
    radiusDp: Int,
  ) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusDp.dp.toFloat()
    setColor(getColor(fillColor))
    strokeColor?.let { setStroke(1.dp, getColor(it)) }
  }

  private fun interactiveBackground(
    fillColor: Int,
    rippleColor: Int,
    strokeColor: Int? = null,
    radiusDp: Int,
  ) = RippleDrawable(
    ColorStateList.valueOf(getColor(rippleColor)),
    roundedBackground(fillColor, strokeColor, radiusDp),
    null,
  )

  private fun missionName(type: String) = when (type) {
    "MATH" -> getString(R.string.mission_name_math)
    "PUSH_UP" -> getString(R.string.mission_name_pushup)
    "QR" -> getString(R.string.mission_name_qr)
    else -> getString(R.string.mission_name_unknown)
  }

  private fun missionAction(snapshot: ActiveRuntimeSnapshot): String {
    val continuing = snapshot.runtimeState == "MISSION_LOCKED" ||
      snapshot.runtimeState == "MISSION_IN_PROGRESS" || snapshot.committedProgress > 0
    if (continuing) return getString(R.string.alarm_host_continue_mission)
    return when (snapshot.missionType) {
      "MATH" -> getString(R.string.alarm_host_start_math)
      "PUSH_UP" -> getString(R.string.alarm_host_start_pushup)
      "QR" -> getString(R.string.alarm_host_start_qr)
      else -> getString(R.string.alarm_host_start_mission)
    }
  }

  private fun operationSymbol(operation: String) = when (operation) {
    "ADD" -> "+"
    "SUBTRACT" -> "−"
    "MULTIPLY" -> "×"
    else -> "?"
  }

  private fun validatedRequestedInstanceId(intent: Intent?): String? {
    if (intent?.action != ACTION_PRESENT_INSTANCE) return null
    val extra = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return null
    val uri = intent.data ?: return null
    if (uri.scheme != "missionalarm" || uri.authority != "instance") return null
    return extra.takeIf { it == uri.lastPathSegment && UUID_V4.matches(it) }
  }

  private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

  companion object {
    private const val HOLD_TICK_MS = 50L
    private const val REQUEST_SCAN_CODE = 4102
    private const val REQUEST_PUSH_UP = 4103
    const val ACTION_PRESENT_INSTANCE = "com.missionalarm.action.PRESENT_ACTIVE_INSTANCE"
    const val EXTRA_INSTANCE_ID = "instanceId"
    private val UUID_V4 = Regex(
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    )

    fun intent(context: Context, instanceId: String): Intent =
      Intent(context, AlarmHostActivity::class.java).apply {
        action = ACTION_PRESENT_INSTANCE
        data = Uri.Builder()
          .scheme("missionalarm")
          .authority("instance")
          .appendPath(instanceId)
          .build()
        putExtra(EXTRA_INSTANCE_ID, instanceId)
      }
  }

  private data class PendingScan(
    val sessionToken: String,
    val instanceId: String,
    val expectedRevision: Int,
  )
}
