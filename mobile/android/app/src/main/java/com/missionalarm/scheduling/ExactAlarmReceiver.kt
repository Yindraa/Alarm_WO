package com.missionalarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.data.DirectBootDatabaseFactory
import com.missionalarm.core.data.TriggerTiming
import java.util.concurrent.Executors

class ExactAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != AndroidExactAlarmScheduler.ACTION_FIRE_OCCURRENCE) return
    val extraId = intent.getStringExtra(AndroidExactAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
    val uriId = intent.data?.takeIf {
      it.scheme == "missionalarm" && it.authority == "occurrence"
    }?.lastPathSegment ?: return
    if (extraId != uriId) return
    runCatching { OccurrenceId.parse(extraId) }.getOrNull() ?: return

    val pendingResult = goAsync()
    EXECUTOR.execute {
      try {
        val database = DirectBootDatabaseFactory.persistent(context)
        try {
          // The mirror is the authority available before unlock; unknown intents fail closed.
          val occurredAtMs = System.currentTimeMillis()
          val recorded = database.directBootDao().recordTriggered(extraId, occurredAtMs)
          if (recorded && context.getSystemService(UserManager::class.java).isUserUnlocked) {
            val elapsed = SystemClock.elapsedRealtime()
            val bootCount = Settings.Global.getInt(
              context.contentResolver,
              Settings.Global.BOOT_COUNT,
              0,
            )
            UnlockedAlarmReconciler.reconcile(
              context,
              mapOf(extraId to TriggerTiming(occurredAtMs, elapsed, "boot:$bootCount")),
            )
          }
        } finally {
          database.close()
        }
      } finally {
        pendingResult.finish()
      }
    }
  }

  private companion object {
    val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "mission-alarm-trigger").apply { isDaemon = true }
    }
  }
}
