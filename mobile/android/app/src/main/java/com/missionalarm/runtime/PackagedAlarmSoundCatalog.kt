package com.missionalarm.runtime

data class PackagedAlarmSound(
  val soundId: String,
  val assetPath: String,
)

/** Stable allowlist. Adding the binary at this path requires no persistence migration. */
object PackagedAlarmSoundCatalog {
  private val sounds = mapOf(
    "classic" to PackagedAlarmSound(
      soundId = "classic",
      assetPath = "alarms/classic.ogg",
    ),
  )

  fun find(soundId: String): PackagedAlarmSound? = sounds[soundId]
}
