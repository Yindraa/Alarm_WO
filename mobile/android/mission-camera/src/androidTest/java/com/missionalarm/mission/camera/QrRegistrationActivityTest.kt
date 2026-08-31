package com.missionalarm.mission.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrRegistrationActivityTest {
  @Test
  fun validRequestOpensCameraSurfaceAndCanCloseCleanly() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        .close()
    }
    val scenario = ActivityScenario.launch<QrRegistrationActivity>(
      QrRegistrationActivity.intent(
        context,
        REQUEST_ID,
        ALARM_ID,
        expectedRevision = 1,
      ),
    )

    scenario.onActivity { activity ->
      assertNotNull(activity.findViewById<PreviewView>(R.id.qr_registration_preview))
      assertEquals(
        View.GONE,
        activity.findViewById<View>(R.id.qr_registration_permission_panel).visibility,
      )
    }
    scenario.close()
  }

  private companion object {
    const val REQUEST_ID = "d515097b-0211-4e5f-a890-a327eec31753"
    const val ALARM_ID = "1da53135-64d8-4455-8539-e0a5172141ba"
  }
}
