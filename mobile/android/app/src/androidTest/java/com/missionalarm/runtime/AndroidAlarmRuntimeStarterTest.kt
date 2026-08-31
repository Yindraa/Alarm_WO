package com.missionalarm.runtime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAlarmRuntimeStarterTest {
  @Test
  fun repeatedStartUsesStableExplicitInstanceIntent() {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val recording = RecordingContext(base)
    val starter = AndroidAlarmRuntimeStarter(recording)

    starter.start(INSTANCE_ID)
    starter.start(INSTANCE_ID)

    assertEquals(2, recording.intents.size)
    assertEquals(recording.intents[0].filterEquals(recording.intents[1]), true)
    val intent = recording.intents.single { it === recording.intents.first() }
    assertEquals(AlarmForegroundService.ACTION_START_INSTANCE, intent.action)
    assertEquals(INSTANCE_ID, intent.getStringExtra(AlarmForegroundService.EXTRA_INSTANCE_ID))
    assertEquals("missionalarm://instance/$INSTANCE_ID", intent.data.toString())
    assertEquals(AlarmForegroundService::class.java.name, intent.component!!.className)
  }

  @Test
  fun repeatedStopUsesStableExplicitInstanceScopedIntent() {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val recording = RecordingContext(base)
    val stopper = AndroidAlarmRuntimeStopper(recording)

    stopper.stop(INSTANCE_ID)
    stopper.stop(INSTANCE_ID)

    assertEquals(2, recording.intents.size)
    assertTrue(recording.intents[0].filterEquals(recording.intents[1]))
    val intent = recording.intents.first()
    assertEquals(AlarmForegroundService.ACTION_STOP_INSTANCE, intent.action)
    assertEquals(INSTANCE_ID, intent.getStringExtra(AlarmForegroundService.EXTRA_INSTANCE_ID))
    assertEquals("missionalarm://instance/$INSTANCE_ID", intent.data.toString())
    assertEquals(AlarmForegroundService::class.java.name, intent.component!!.className)
  }

  @Test
  fun serviceAndRequiredForegroundPermissionsAreDeclared() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val service = context.packageManager.getServiceInfo(
      ComponentName(context, AlarmForegroundService::class.java),
      0,
    )
    assertFalse(service.exported)
    assertTrue(
      service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
    )
    val requested = context.packageManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS,
    ).requestedPermissions.orEmpty().toSet()
    assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
    assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in requested)
    assertTrue(Manifest.permission.WAKE_LOCK in requested)
  }

  private class RecordingContext(base: Context) : ContextWrapper(base) {
    val intents = mutableListOf<Intent>()

    override fun startForegroundService(service: Intent): ComponentName? {
      intents += Intent(service)
      return service.component
    }
  }

  private companion object {
    const val INSTANCE_ID = "92d9035c-adfe-422e-949b-b96877cec786"
  }
}
