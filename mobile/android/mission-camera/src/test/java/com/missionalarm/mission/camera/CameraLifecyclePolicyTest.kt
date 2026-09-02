package com.missionalarm.mission.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLifecyclePolicyTest {
  @Test
  fun readyGrantedMissionStartsOnlyWhenCameraIsInactive() {
    assertEquals(
      CameraLifecycleAction.START,
      decide(permissionGranted = true),
    )
    assertEquals(
      CameraLifecycleAction.KEEP,
      decide(permissionGranted = true, cameraStartingOrRunning = true),
    )
  }

  @Test
  fun revokedPermissionStopsCameraAndShowsRecovery() {
    assertEquals(
      CameraLifecycleAction.STOP_AND_SHOW_PERMISSION,
      decide(permissionGranted = false, cameraStartingOrRunning = true),
    )
  }

  @Test
  fun unreadyTerminalAndBlockedRecoveryNeverStartCamera() {
    assertEquals(CameraLifecycleAction.STOP, decide(missionReady = false))
    assertEquals(CameraLifecycleAction.STOP, decide(terminal = true))
    assertEquals(CameraLifecycleAction.STOP, decide(recoveryBlocked = true))
  }

  private fun decide(
    missionReady: Boolean = true,
    terminal: Boolean = false,
    permissionGranted: Boolean = true,
    cameraStartingOrRunning: Boolean = false,
    recoveryBlocked: Boolean = false,
  ) = CameraLifecyclePolicy.decide(
    missionReady,
    terminal,
    permissionGranted,
    cameraStartingOrRunning,
    recoveryBlocked,
  )
}
