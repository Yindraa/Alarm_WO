package com.missionalarm.scheduling

import android.app.AlarmManager
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReconciliationReceiverTest {
  @Test
  fun supportedSystemActionsIncludeClockDateTimezoneAndPackageChanges() {
    listOf(
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_DATE_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      Intent.ACTION_LOCKED_BOOT_COMPLETED,
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_USER_UNLOCKED,
      AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    ).forEach { assertTrue(BootReconciliationReceiver.supports(it)) }
    assertFalse(BootReconciliationReceiver.supports(Intent.ACTION_AIRPLANE_MODE_CHANGED))
  }

  @Test
  fun onlyScheduleInvalidatingActionsRequestOccurrenceReconciliation() {
    listOf(
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_DATE_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    ).forEach { assertTrue(BootReconciliationReceiver.requiresScheduleReconciliation(it)) }
    assertFalse(
      BootReconciliationReceiver.requiresScheduleReconciliation(Intent.ACTION_BOOT_COMPLETED),
    )
  }
}
