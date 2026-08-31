package com.missionalarm.runtime

interface AlarmAudioSession {
  fun start()
  fun stop()
}

fun interface AlarmAudioSessionFactory {
  fun create(soundId: String): AlarmAudioSession
}

/** Owns exactly one playback session; duplicate starts for the active instance are no-ops. */
class AlarmAudioController(private val factory: AlarmAudioSessionFactory) {
  private val stateLock = Any()
  private var current: ActiveSession? = null

  fun start(instanceId: String, soundId: String): Boolean {
    require(instanceId.isNotBlank())
    require(soundId.isNotBlank())
    synchronized(stateLock) {
      if (current?.instanceId == instanceId) return false
    }

    val replacement = factory.create(soundId)
    val previous = synchronized(stateLock) {
      if (current?.instanceId == instanceId) return@synchronized null
      current.also { current = ActiveSession(instanceId, replacement) }
    }
    if (previous == null) {
      val accepted = synchronized(stateLock) { current?.session === replacement }
      if (!accepted) {
        replacement.stop()
        return false
      }
    }
    previous?.session?.stop()
    try {
      replacement.start()
    } catch (error: RuntimeException) {
      synchronized(stateLock) {
        if (current?.session === replacement) current = null
      }
      replacement.stop()
      throw error
    }
    return true
  }

  fun stop() {
    val stopped = synchronized(stateLock) { current.also { current = null } }
    stopped?.session?.stop()
  }

  fun activeInstanceId(): String? = synchronized(stateLock) { current?.instanceId }

  private data class ActiveSession(val instanceId: String, val session: AlarmAudioSession)
}
