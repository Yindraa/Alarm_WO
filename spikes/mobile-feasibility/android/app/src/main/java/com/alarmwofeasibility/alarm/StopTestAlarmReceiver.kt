package com.alarmwofeasibility.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopTestAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    AlarmForegroundService.stop(context)
  }
}
