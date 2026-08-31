package com.missionalarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.missionalarm.core.data.DirectBootDatabaseFactory
import com.missionalarm.core.domain.OccurrenceId
import java.util.concurrent.Executors

class BootReconciliationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in SUPPORTED_ACTIONS) return
    val pendingResult = goAsync()
    EXECUTOR.execute {
      try {
        when (intent.action) {
          Intent.ACTION_LOCKED_BOOT_COMPLETED -> restoreFromDeviceProtectedMirror(context)
          else -> if (isUserUnlocked(context)) reconcileAfterUnlock(context)
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

  private fun reconcileAfterUnlock(context: Context) {
    UnlockedAlarmReconciler.reconcile(context)
  }

  private fun isUserUnlocked(context: Context): Boolean =
    context.getSystemService(UserManager::class.java).isUserUnlocked

  private companion object {
    val SUPPORTED_ACTIONS = setOf(
      Intent.ACTION_LOCKED_BOOT_COMPLETED,
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_USER_UNLOCKED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
    )
    val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "mission-alarm-boot").apply { isDaemon = true }
    }
  }
}
