package com.missionalarm.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.missionalarm.core.domain.WallClock
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

  private lateinit var loading: ProgressBar
  private lateinit var time: TextView
  private lateinit var title: TextView
  private lateinit var detail: TextView
  private lateinit var progress: TextView
  private lateinit var queue: TextView
  private lateinit var mathWorkspace: LinearLayout
  private lateinit var mathQuestion: TextView
  private lateinit var mathAnswer: EditText
  private lateinit var mathFeedback: TextView
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
    if (::loading.isInitialized) refreshFromCanonicalState()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    refreshFromCanonicalState()
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
    if (destination == MissionDestination.EMBEDDED_MATH) {
      startMathMission(snapshot)
      return
    }
    activeSnapshot = snapshot
    detail.text = when (destination) {
      MissionDestination.EMBEDDED_MATH -> error("Math route is handled before placeholder routing")
      MissionDestination.NATIVE_PUSH_UP -> getString(R.string.alarm_host_pushup_route_ready)
      MissionDestination.NATIVE_QR -> getString(R.string.alarm_host_qr_route_ready)
    }
    primaryAction.setText(R.string.alarm_host_route_reserved)
    primaryAction.isEnabled = false
    primaryAction.setOnClickListener(null)
    emergencyStatus.setText(R.string.alarm_host_route_safety_notice)
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
    mathAnswer.text.clear()
    mathAnswer.isEnabled = true
    mathFeedback.setText(
      if (correctFeedback) R.string.alarm_host_math_correct else R.string.alarm_host_math_feedback_ready,
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
      return
    }
    val ordinal = snapshot.mathQuestion?.ordinal ?: run {
      refreshFromCanonicalState()
      return
    }
    mathAnswer.isEnabled = false
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
            mathAnswer.isEnabled = true
            mathAnswer.selectAll()
            mathFeedback.setText(R.string.alarm_host_math_incorrect)
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
      setPadding(24.dp, 48.dp, 24.dp, 32.dp)
    }
    loading = ProgressBar(this).apply {
      id = R.id.alarm_host_loading
      contentDescription = getString(R.string.alarm_host_loading)
    }
    time = TextView(this).apply {
      id = R.id.alarm_host_time
      textSize = 42f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
    }
    title = TextView(this).apply {
      id = R.id.alarm_host_title
      textSize = 28f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
      setPadding(0, 24.dp, 0, 8.dp)
    }
    detail = bodyText(R.id.alarm_host_detail)
    progress = bodyText(R.id.alarm_host_progress)
    queue = bodyText(R.id.alarm_host_queue)
    mathQuestion = TextView(this).apply {
      id = R.id.alarm_host_math_question
      textSize = 36f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
      setPadding(0, 20.dp, 0, 12.dp)
    }
    mathAnswer = EditText(this).apply {
      id = R.id.alarm_host_math_answer
      inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
      textSize = 24f
      gravity = Gravity.CENTER
      hint = getString(R.string.alarm_host_math_answer_hint)
      contentDescription = getString(R.string.alarm_host_math_answer_description)
      maxLines = 1
    }
    mathFeedback = bodyText(R.id.alarm_host_math_feedback).apply {
      textSize = 15f
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }
    mathWorkspace = LinearLayout(this).apply {
      id = R.id.alarm_host_math_workspace
      orientation = LinearLayout.VERTICAL
      visibility = View.GONE
      addView(mathQuestion, matchWrap())
      addView(mathAnswer, matchWrap())
      addView(mathFeedback, matchWrap())
    }
    primaryAction = Button(this).apply {
      id = R.id.alarm_host_primary_action
      isAllCaps = false
      setPadding(24.dp, 12.dp, 24.dp, 12.dp)
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
    }
    content.addView(loading)
    content.addView(time, matchWrap())
    content.addView(title, matchWrap())
    content.addView(detail, matchWrap())
    content.addView(progress, matchWrap())
    content.addView(queue, matchWrap())
    content.addView(mathWorkspace, matchWrap(topMargin = 16.dp))
    content.addView(primaryAction, matchWrap(topMargin = 32.dp))
    content.addView(emergencyStatus, matchWrap())
    content.addView(emergencyAction, matchWrap(topMargin = 24.dp))
    return ScrollView(this).apply { addView(content) }
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
  }

  private fun matchWrap(topMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  ).apply { this.topMargin = topMargin }

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
}
