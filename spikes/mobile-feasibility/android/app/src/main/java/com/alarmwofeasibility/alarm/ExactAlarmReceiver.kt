package com.alarmwofeasibility.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ExactAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == TestAlarmScheduler.ACTION_FIRE_TEST_ALARM) {
      Log.i(TAG, "Exact test alarm received")
      AlarmForegroundService.start(context)
    }
  }

  companion object {
    private const val TAG = "AlarmWOReceiver"
  }
}
