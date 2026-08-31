package com.missionalarm.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.missionalarm.app.R
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import java.util.concurrent.Executors

class AlarmForegroundService : Service() {
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "mission-alarm-runtime").apply { isDaemon = true }
  }
  private val databaseDelegate = lazy { MissionAlarmDatabaseFactory.persistent(applicationContext) }
  private val audioControllerDelegate = lazy {
    AlarmAudioController(MediaPlayerAlarmAudioSessionFactory(applicationContext))
  }
  private val database by databaseDelegate
  private val audioController by audioControllerDelegate
  private var wakeLock: PowerManager.WakeLock? = null
  @Volatile private var notificationInstanceId: String? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val stopId = validatedInstanceId(intent, ACTION_STOP_INSTANCE)
    if (intent?.action == ACTION_STOP_INSTANCE) {
      startAsForeground(notification())
      if (stopId == null) {
        stopSelfResult(startId)
        return START_NOT_STICKY
      }
      executor.execute { stopRequestedInstance(stopId, startId) }
      return START_NOT_STICKY
    }
    val requestedId = validatedInstanceId(intent, ACTION_START_INSTANCE)
    notificationInstanceId = requestedId
    startAsForeground(notification(requestedId))
    if (intent != null && requestedId == null) {
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    executor.execute { recoverOrStart(requestedId, startId) }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    executor.shutdownNow()
    if (audioControllerDelegate.isInitialized()) audioController.stop()
    releaseWakeLock()
    if (databaseDelegate.isInitialized()) database.close()
    super.onDestroy()
  }

  private fun recoverOrStart(requestedId: String?, startId: Int) {
    val requested = requestedId?.let(database.runtimeDao()::findInstanceById)
    val attended = database.runtimeDao().findAttendedInstance()
    val owner = AlarmRuntimeRecoveryResolver.resolve(
      requestedId,
      requested?.let { RuntimeOwnerCandidate(it.id, it.runtimeState, it.attentionSlot) },
      attended?.let { RuntimeOwnerCandidate(it.id, it.runtimeState, it.attentionSlot) },
    )
    val instance = when (owner?.instanceId) {
      requested?.id -> requested
      attended?.id -> attended
      else -> null
    }
    if (instance == null) {
      if (audioController.activeInstanceId() == null) stopSelfResult(startId)
      return
    }
    notificationInstanceId = instance.id
    acquireWakeLock()
    runCatching { audioController.start(instance.id, instance.soundIdSnapshot) }
      .onFailure { showAudioFailureNotification() }
  }

  private fun stopRequestedInstance(instanceId: String, startId: Int) {
    val activeId = if (audioControllerDelegate.isInitialized()) {
      audioController.activeInstanceId()
    } else {
      null
    }
    if (!ownsStopRequest(instanceId, activeId, notificationInstanceId)) return
    if (audioControllerDelegate.isInitialized()) audioController.stop()
    releaseWakeLock()
    if (Build.VERSION.SDK_INT >= 24) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelfResult(startId)
  }

  private fun validatedInstanceId(intent: Intent?, expectedAction: String): String? {
    if (intent?.action != expectedAction) return null
    val extra = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return null
    val uri = intent.data ?: return null
    if (uri.scheme != "missionalarm" || uri.authority != "instance") return null
    return extra.takeIf { it == uri.lastPathSegment && UUID_V4.matches(it) }
  }

  private fun notification(
    instanceId: String? = notificationInstanceId,
    audioUnavailable: Boolean = false,
  ): Notification {
    val hostIntent = instanceId
      ?.let { AlarmHostActivity.intent(this, it) }
      ?: Intent(this, AlarmHostActivity::class.java)
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      hostIntent.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    return builder
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle(getString(R.string.alarm_notification_title))
      .setContentText(
        getString(
          if (audioUnavailable) R.string.alarm_notification_audio_unavailable
          else R.string.alarm_notification_text,
        ),
      )
      .setCategory(Notification.CATEGORY_ALARM)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      .setFullScreenIntent(contentIntent, true)
      .build()
  }

  private fun showAudioFailureNotification() {
    getSystemService(NotificationManager::class.java)
      .notify(NOTIFICATION_ID, notification(audioUnavailable = true))
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < 26) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.alarm_notification_channel),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = getString(R.string.alarm_notification_channel_description)
      setSound(null, null)
      enableVibration(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun startAsForeground(notification: Notification) {
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  @SuppressLint("WakelockTimeout")
  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    wakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MissionAlarm:runtime")
      .apply {
        setReferenceCounted(false)
        acquire()
      }
  }

  private fun releaseWakeLock() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
  }

  companion object {
    const val ACTION_START_INSTANCE = "com.missionalarm.action.START_ALARM_RUNTIME"
    const val ACTION_STOP_INSTANCE = "com.missionalarm.action.STOP_ALARM_RUNTIME"
    const val EXTRA_INSTANCE_ID = "instanceId"
    const val CHANNEL_ID = "mission_alarm_active_v1"
    const val NOTIFICATION_ID = 41001
    private val UUID_V4 = Regex(
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    )
  }
}

internal fun ownsStopRequest(
  requestedInstanceId: String,
  audioInstanceId: String?,
  notificationInstanceId: String?,
): Boolean = (audioInstanceId ?: notificationInstanceId)?.let { it == requestedInstanceId } ?: true
