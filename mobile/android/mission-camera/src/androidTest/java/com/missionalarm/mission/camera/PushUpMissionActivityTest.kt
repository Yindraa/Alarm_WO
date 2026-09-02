package com.missionalarm.mission.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.missionalarm.core.data.AlarmEntity
import com.missionalarm.core.data.AlarmInstanceEntity
import com.missionalarm.core.data.AlarmMissionConfigEntity
import com.missionalarm.core.data.AlarmOccurrenceEntity
import com.missionalarm.core.data.InstanceMissionEntity
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushUpMissionActivityTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before
  fun setUp() {
    context.deleteDatabase(DATABASE_NAME)
    seedActivePushUp()
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        .close()
    }
  }

  @After
  fun tearDown() {
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun activeMissionOpensPrivateCameraSurfaceAndCanCloseCleanly() {
    val scenario = ActivityScenario.launch<PushUpMissionActivity>(
      PushUpMissionActivity.intent(context, INSTANCE_ID),
    )

    scenario.onActivity { activity ->
      assertNotNull(activity.findViewById<PreviewView>(R.id.pushup_preview))
      assertNotNull(activity.findViewById<android.view.View>(R.id.pushup_progress))
      assertNotNull(activity.findViewById<android.view.View>(R.id.pushup_phase))
      assertNotNull(activity.findViewById<android.view.View>(R.id.pushup_quality))
      assertNotNull(activity.findViewById<android.view.View>(R.id.pushup_back))
      assertEquals(INSTANCE_ID, PushUpMissionActivity.validatedInstanceId(activity.intent))
    }
    scenario.moveToState(Lifecycle.State.CREATED)
    scenario.moveToState(Lifecycle.State.RESUMED)
    scenario.onActivity { activity ->
      assertNotNull(activity.findViewById<PreviewView>(R.id.pushup_preview))
      assertEquals(INSTANCE_ID, PushUpMissionActivity.validatedInstanceId(activity.intent))
    }
    scenario.close()
  }

  @Test
  fun packagedModelMatchesFrozenSpecificationChecksum() {
    val digest = context.assets.open(MODEL_ASSET).use { input ->
      MessageDigest.getInstance("SHA-256").digest(input.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }
    }

    assertEquals(MODEL_SHA_256, digest)
  }

  private fun seedActivePushUp() {
    val database = MissionAlarmDatabaseFactory.persistent(context)
    val alarm = AlarmEntity(
      ALARM_ID, 1, "Push-up test", false, "WEEKLY", 420, 127, null, "UTC", "classic", 1, 1,
    )
    database.runInTransaction {
      database.alarmDao().insertAlarm(alarm)
      database.alarmDao().insertMission(
        AlarmMissionConfigEntity(
          ALARM_ID, "PUSH_UP", 1, 1, PROFILE_VERSION, null, null, null, null, null,
        ),
      )
      database.alarmDao().updateAlarm(alarm.copy(enabled = true))
      database.runtimeDao().insertOccurrence(
        AlarmOccurrenceEntity(
          OCCURRENCE_ID,
          "occ:v1:$ALARM_ID:1:1",
          ALARM_ID,
          1,
          20_000,
          "2026-09-01",
          420,
          "UTC",
          0,
          "SCHEDULED_OS",
          null,
          1,
          1,
        ),
      )
      database.runtimeDao().getOrCreateTriggeredInstance(
        AlarmInstanceEntity(
          INSTANCE_ID,
          OCCURRENCE_ID,
          ALARM_ID,
          1,
          "TRIGGERED",
          1,
          1,
          20_000,
          19_000,
          1,
          "boot:1",
          null,
          null,
          null,
          null,
          "Push-up test",
          "classic",
          1,
          1,
        ),
        InstanceMissionEntity(
          INSTANCE_ID,
          "PUSH_UP",
          1,
          1,
          0,
          "READY",
          "pushup-engine-v1",
          PROFILE_VERSION,
          null,
          null,
          null,
          null,
          1,
        ),
      )
    }
    database.close()
  }

  private companion object {
    const val DATABASE_NAME = "mission-alarm.db"
    const val MODEL_ASSET = "pose_landmarker_lite.task"
    const val MODEL_SHA_256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
    const val PROFILE_VERSION = "pushup-profile-v0"
    const val ALARM_ID = "83ce0d8f-f638-4268-8906-2bc48a4d1b86"
    const val OCCURRENCE_ID = "26756a09-4084-49cf-bbaa-5a5ab33df7e4"
    const val INSTANCE_ID = "2365695a-0f14-4913-87bf-3331de132edf"
  }
}
