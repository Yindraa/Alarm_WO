package com.alarmwofeasibility.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class TestAlarmScheduler(private val context: Context) {
  private val alarmManager = context.getSystemService(AlarmManager::class.java)

  fun schedule(triggerAtMillis: Long) {
    check(triggerAtMillis > System.currentTimeMillis()) { "Trigger time must be in the future" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      check(alarmManager.canScheduleExactAlarms()) { "Exact alarm access is not granted" }
    }

    alarmManager.setExactAndAllowWhileIdle(
      AlarmManager.RTC_WAKEUP,
      triggerAtMillis,
      alarmPendingIntent(),
    )
  }

  fun cancel() {
    alarmManager.cancel(alarmPendingIntent())
  }

  private fun alarmPendingIntent(): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      TEST_REQUEST_CODE,
      Intent(context, ExactAlarmReceiver::class.java).setAction(ACTION_FIRE_TEST_ALARM),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  companion object {
    const val ACTION_FIRE_TEST_ALARM = "com.alarmwofeasibility.FIRE_TEST_ALARM"
    const val TEST_REQUEST_CODE = 4101
  }
}
