package com.missionalarm.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.missionalarm.core.data.AlarmRuntimeStarter
import com.missionalarm.core.data.PermanentRuntimeStartException

class AndroidAlarmRuntimeStarter(private val context: Context) : AlarmRuntimeStarter {
  override fun start(instanceId: String) {
    val intent = Intent(context, AlarmForegroundService::class.java).apply {
      action = AlarmForegroundService.ACTION_START_INSTANCE
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
      throw PermanentRuntimeStartException()
    } catch (_: IllegalArgumentException) {
      throw PermanentRuntimeStartException()
    }
  }
}
