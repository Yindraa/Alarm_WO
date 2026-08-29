package com.missionalarm.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.missionalarm.app.BuildConfig
import com.missionalarm.specs.NativeMissionAlarmSpec

class MissionAlarmModule(
  reactContext: ReactApplicationContext,
) : NativeMissionAlarmSpec(reactContext) {

  override fun getName(): String = NAME

  override fun getContractInfo(promise: Promise) {
    promise.resolve(
      Arguments.createMap().apply {
        putInt("contractVersion", CONTRACT_VERSION)
        putInt("minimumClientContractVersion", MINIMUM_CLIENT_CONTRACT_VERSION)
        putString("moduleName", NAME)
        putString("nativeBuildVersion", BuildConfig.VERSION_NAME)
      },
    )
  }

  companion object {
    const val NAME = "NativeMissionAlarm"
    const val CONTRACT_VERSION = 1
    const val MINIMUM_CLIENT_CONTRACT_VERSION = 1
  }
}
