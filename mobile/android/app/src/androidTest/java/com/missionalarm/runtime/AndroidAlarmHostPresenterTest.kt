package com.missionalarm.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAlarmHostPresenterTest {
  @Test
  fun repeatedPresentationUsesStableExplicitInstanceIntent() {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val recording = RecordingContext(base)
    val presenter = AndroidAlarmHostPresenter(recording)

    presenter.present(INSTANCE_ID)
    presenter.present(INSTANCE_ID)

    assertEquals(2, recording.intents.size)
    assertTrue(recording.intents[0].filterEquals(recording.intents[1]))
    val intent = recording.intents.first()
    assertEquals(AlarmHostActivity.ACTION_PRESENT_INSTANCE, intent.action)
    assertEquals(INSTANCE_ID, intent.getStringExtra(AlarmHostActivity.EXTRA_INSTANCE_ID))
    assertEquals("missionalarm://instance/$INSTANCE_ID", intent.data.toString())
    assertEquals(AlarmHostActivity::class.java.name, intent.component!!.className)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
  }

  @Test
  fun alarmHostIsPrivateSingleTaskAndExcludedFromRecents() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val activity = context.packageManager.getActivityInfo(
      ComponentName(context, AlarmHostActivity::class.java),
      0,
    )

    assertFalse(activity.exported)
    assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activity.launchMode)
    assertTrue(activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
  }

  private class RecordingContext(base: Context) : ContextWrapper(base) {
    val intents = mutableListOf<Intent>()

    override fun startActivity(intent: Intent) {
      intents += Intent(intent)
    }
  }

  private companion object {
    const val INSTANCE_ID = "92d9035c-adfe-422e-949b-b96877cec786"
  }
}
