package com.missionalarm.mission.camera

internal enum class CameraLifecycleAction {
  START,
  KEEP,
  STOP,
  STOP_AND_SHOW_PERMISSION,
}

/** Pure decision boundary shared by camera missions across permission and lifecycle changes. */
internal object CameraLifecyclePolicy {
  fun decide(
    missionReady: Boolean,
    terminal: Boolean,
    permissionGranted: Boolean,
    cameraStartingOrRunning: Boolean,
    recoveryBlocked: Boolean,
  ): CameraLifecycleAction = when {
    terminal || !missionReady || recoveryBlocked -> CameraLifecycleAction.STOP
    !permissionGranted -> CameraLifecycleAction.STOP_AND_SHOW_PERMISSION
    cameraStartingOrRunning -> CameraLifecycleAction.KEEP
    else -> CameraLifecycleAction.START
  }
}
