package com.missionalarm.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.missionalarm.core.data.ExactAlarmCapabilityException
import com.missionalarm.core.data.ExactAlarmScheduler
import com.missionalarm.core.data.PermanentSchedulingException
import com.missionalarm.core.domain.OccurrenceId

class AndroidExactAlarmScheduler(
  private val context: Context,
) : ExactAlarmScheduler {
  private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  override fun schedule(occurrenceId: OccurrenceId, scheduledAtUtcMs: Long) {
    if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
      throw ExactAlarmCapabilityException()
    }
    try {
      alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        scheduledAtUtcMs,
        checkNotNull(pendingIntent(occurrenceId, PendingIntent.FLAG_UPDATE_CURRENT)),
      )
    } catch (_: SecurityException) {
      throw ExactAlarmCapabilityException()
    } catch (_: IllegalArgumentException) {
      throw PermanentSchedulingException()
    }
  }

  override fun cancel(occurrenceId: OccurrenceId) {
    pendingIntent(occurrenceId, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
  }

  private fun pendingIntent(occurrenceId: OccurrenceId, behaviorFlag: Int): PendingIntent? {
    val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
      action = ACTION_FIRE_OCCURRENCE
      data = occurrenceUri(occurrenceId)
      putExtra(EXTRA_OCCURRENCE_ID, occurrenceId.value)
    }
    return PendingIntent.getBroadcast(
      context,
      REQUEST_CODE,
      intent,
      behaviorFlag or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun occurrenceUri(occurrenceId: OccurrenceId): Uri = Uri.Builder()
    .scheme("missionalarm")
    .authority("occurrence")
    .appendPath(occurrenceId.value)
    .build()

  companion object {
    const val ACTION_FIRE_OCCURRENCE = "com.missionalarm.action.FIRE_OCCURRENCE"
    const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    private const val REQUEST_CODE = 0
  }
}
