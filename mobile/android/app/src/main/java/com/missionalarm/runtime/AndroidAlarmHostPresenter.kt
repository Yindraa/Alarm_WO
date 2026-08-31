package com.missionalarm.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.missionalarm.core.data.AlarmHostPresenter
import com.missionalarm.core.data.PermanentAlarmPresentationException

class AndroidAlarmHostPresenter(private val context: Context) : AlarmHostPresenter {
  override fun present(instanceId: String) {
    val intent = AlarmHostActivity.intent(context, instanceId).apply {
      addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TOP or
          Intent.FLAG_ACTIVITY_SINGLE_TOP,
      )
    }
    try {
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      throw PermanentAlarmPresentationException()
    } catch (_: SecurityException) {
      throw PermanentAlarmPresentationException()
    } catch (_: IllegalArgumentException) {
      throw PermanentAlarmPresentationException()
    }
  }
}
