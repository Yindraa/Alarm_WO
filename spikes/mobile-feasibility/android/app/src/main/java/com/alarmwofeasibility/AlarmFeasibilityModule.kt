package com.alarmwofeasibility

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.alarmwofeasibility.alarm.AlarmForegroundService
import com.alarmwofeasibility.alarm.TestAlarmScheduler
import com.alarmwofeasibility.pose.PoseFeasibilityActivity
import com.alarmwofeasibility.specs.NativeAlarmFeasibilitySpec
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext

class AlarmFeasibilityModule(
  reactContext: ReactApplicationContext,
) : NativeAlarmFeasibilitySpec(reactContext) {
  private val scheduler = TestAlarmScheduler(reactContext)

  override fun getName(): String = NAME

  override fun getCapabilities(promise: Promise) {
    val alarmManager = reactApplicationContext.getSystemService(AlarmManager::class.java)
    val notificationManager =
      reactApplicationContext.getSystemService(NotificationManager::class.java)
    val canSchedule =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val canUseFullScreen =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        notificationManager.canUseFullScreenIntent()

    promise.resolve(
      Arguments.createMap().apply {
        putInt("androidApi", Build.VERSION.SDK_INT)
        putBoolean("canScheduleExactAlarms", canSchedule)
        putBoolean("canUseFullScreenIntent", canUseFullScreen)
      },
    )
  }

  override fun openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    reactApplicationContext.startActivity(
      Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${reactApplicationContext.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      },
    )
  }

  override fun scheduleTestAlarm(triggerAtMillis: Double, promise: Promise) {
    runCatching { scheduler.schedule(triggerAtMillis.toLong()) }
      .onSuccess { promise.resolve(true) }
      .onFailure { promise.reject("SCHEDULE_FAILED", it.message, it) }
  }

  override fun stopTestAlarm() {
    scheduler.cancel()
    AlarmForegroundService.stop(reactApplicationContext)
  }

  override fun openPoseSpike() {
    reactApplicationContext.startActivity(
      Intent(reactApplicationContext, PoseFeasibilityActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      },
    )
  }

  companion object {
    const val NAME = "NativeAlarmFeasibility"
  }
}
