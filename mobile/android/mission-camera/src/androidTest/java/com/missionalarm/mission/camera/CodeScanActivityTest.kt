package com.missionalarm.mission.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeScanActivityTest {
  @Test
  fun validSessionOpensCameraSurfaceAndCanCloseCleanly() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        .close()
    }
    val scenario = ActivityScenario.launch<CodeScanActivity>(
      CodeScanActivity.intent(context, SESSION_TOKEN),
    )

    scenario.onActivity { activity ->
      assertNotNull(activity.findViewById<PreviewView>(R.id.code_scan_preview))
      assertEquals(
        View.GONE,
        activity.findViewById<View>(R.id.code_scan_permission_panel).visibility,
      )
    }
    scenario.moveToState(Lifecycle.State.CREATED)
    scenario.moveToState(Lifecycle.State.RESUMED)
    scenario.onActivity { activity ->
      assertNotNull(activity.findViewById<PreviewView>(R.id.code_scan_preview))
      assertEquals(
        View.GONE,
        activity.findViewById<View>(R.id.code_scan_permission_panel).visibility,
      )
    }
    scenario.close()
  }

  private companion object {
    const val SESSION_TOKEN = "d515097b-0211-4e5f-a890-a327eec31753"
  }
}
