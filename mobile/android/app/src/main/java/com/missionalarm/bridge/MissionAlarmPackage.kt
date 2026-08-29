package com.missionalarm.bridge

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class MissionAlarmPackage : BaseReactPackage() {
  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext,
  ): NativeModule? =
    if (name == MissionAlarmModule.NAME) MissionAlarmModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      MissionAlarmModule.NAME to
        ReactModuleInfo(
          MissionAlarmModule.NAME,
          MissionAlarmModule.NAME,
          false,
          false,
          false,
          true,
        ),
    )
  }
}
