package com.missionalarm.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.missionalarm.core.data.AlarmRuntimeStopper
import com.missionalarm.core.data.PermanentRuntimeStopException

class AndroidAlarmRuntimeStopper(private val context: Context) : AlarmRuntimeStopper {
  override fun stop(instanceId: String) {
    val intent = Intent(context, AlarmForegroundService::class.java).apply {
      action = AlarmForegroundService.ACTION_STOP_INSTANCE
      data = Uri.Builder()
        .scheme("missionalarm")
        .authority("instance")
        .appendPath(instanceId)
        .build()
      putExtra(AlarmForegroundService.EXTRA_INSTANCE_ID, instanceId)
    }
    try {
      val component = if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
      if (component == null) throw IllegalStateException("alarm runtime service was not resolved")
    } catch (_: SecurityException) {
      throw PermanentRuntimeStopException()
    } catch (_: IllegalArgumentException) {
      throw PermanentRuntimeStopException()
    }
  }
}
