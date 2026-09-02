package com.missionalarm.scheduling

import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.missionalarm.core.data.DirectBootDatabaseFactory
import com.missionalarm.core.domain.OccurrenceId
import java.util.UUID
import java.util.concurrent.Executors

class BootReconciliationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!supports(intent.action)) return
    val pendingResult = goAsync()
    EXECUTOR.execute {
      try {
        when (intent.action) {
          Intent.ACTION_LOCKED_BOOT_COMPLETED -> restoreFromDeviceProtectedMirror(context)
          else -> if (isUserUnlocked(context)) reconcileAfterUnlock(context, intent.action)
        }
      } finally {
        pendingResult.finish()
      }
    }
  }

  private fun restoreFromDeviceProtectedMirror(context: Context) {
    val database = DirectBootDatabaseFactory.persistent(context)
    try {
      val scheduler = AndroidExactAlarmScheduler(context.createDeviceProtectedStorageContext())
      database.directBootDao().findActiveSchedules().forEach { schedule ->
        runCatching {
          scheduler.schedule(OccurrenceId.parse(schedule.occurrenceId), schedule.scheduledAtUtcMs)
        }
      }
    } finally {
      database.close()
    }
  }

  private fun reconcileAfterUnlock(context: Context, action: String?) {
    val reconciliationId = action?.takeIf(::requiresScheduleReconciliation)?.let {
      "system-${it.substringAfterLast('.')}-${UUID.randomUUID()}"
    }
    val capabilityObservationId = action
      ?.takeIf { it == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED }
      ?.let { "system-exact-alarm-${UUID.randomUUID()}" }
    val exactAlarmGranted = capabilityObservationId?.let {
      android.os.Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    UnlockedAlarmReconciler.reconcile(
      context,
      scheduleReconciliationId = reconciliationId,
      exactAlarmCapabilityGranted = exactAlarmGranted,
      capabilityObservationId = capabilityObservationId,
    )
  }

  private fun isUserUnlocked(context: Context): Boolean =
    context.getSystemService(UserManager::class.java).isUserUnlocked

  companion object {
    internal fun supports(action: String?): Boolean = action in SUPPORTED_ACTIONS

    internal fun requiresScheduleReconciliation(action: String): Boolean =
      action in SCHEDULE_RECONCILIATION_ACTIONS

    val SUPPORTED_ACTIONS = setOf(
      Intent.ACTION_LOCKED_BOOT_COMPLETED,
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_USER_UNLOCKED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_DATE_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    )
    private val SCHEDULE_RECONCILIATION_ACTIONS = setOf(
      Intent.ACTION_MY_PACKAGE_REPLACED,
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_DATE_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    )
    val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "mission-alarm-boot").apply { isDaemon = true }
    }
  }
}
