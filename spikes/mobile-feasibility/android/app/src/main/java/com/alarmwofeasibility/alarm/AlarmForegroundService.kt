package com.alarmwofeasibility.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.alarmwofeasibility.MainActivity

class AlarmForegroundService : Service() {
  private var mediaPlayer: MediaPlayer? = null

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "Alarm foreground service created")
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "Alarm foreground service starting")
    val notification = createNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    startAlarmAudio()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    Log.i(TAG, "Alarm foreground service destroyed")
    mediaPlayer?.runCatching {
      stop()
      release()
    }
    mediaPlayer = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Alarm feasibility",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Temporary channel for the Mission Alarm feasibility spike"
        setSound(null, null)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
    manager.createNotificationChannel(channel)
  }

  private fun createNotification(): Notification {
    val launchPendingIntent =
      PendingIntent.getActivity(
        this,
        4102,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
          putExtra(EXTRA_OPENED_FROM_ALARM, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopPendingIntent =
      PendingIntent.getBroadcast(
        this,
        4103,
        Intent(this, StopTestAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Mission Alarm feasibility")
      .setContentText("Test alarm fired. Open the app or stop the safe test.")
      .setCategory(Notification.CATEGORY_ALARM)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setAutoCancel(false)
      .setContentIntent(launchPendingIntent)
      .setFullScreenIntent(launchPendingIntent, true)
      .addAction(Notification.Action.Builder(null, "STOP TEST", stopPendingIntent).build())
      .build()
  }

  private fun startAlarmAudio() {
    if (mediaPlayer != null) return
    val alarmUri =
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    runCatching {
      MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
        setDataSource(applicationContext, alarmUri)
        isLooping = true
        prepare()
        start()
      }
    }.onSuccess {
      mediaPlayer = it
      Log.i(TAG, "Alarm audio started")
    }.onFailure {
      Log.e(TAG, "Alarm audio unavailable; service remains recoverable", it)
    }
  }

  companion object {
    private const val TAG = "AlarmWOService"
    private const val CHANNEL_ID = "alarm_feasibility_v1"
    private const val NOTIFICATION_ID = 4104
    const val EXTRA_OPENED_FROM_ALARM = "opened_from_alarm"

    fun start(context: Context) {
      val intent = Intent(context, AlarmForegroundService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, AlarmForegroundService::class.java))
    }
  }
}
