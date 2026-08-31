package com.missionalarm.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

class MediaPlayerAlarmAudioSessionFactory(
  private val context: Context,
) : AlarmAudioSessionFactory {
  override fun create(soundId: String): AlarmAudioSession {
    require(soundId.isNotBlank())
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ALARM)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
    val player = preparedPackagedPlayer(soundId, attributes)
      ?: preparedSystemFallbackPlayer(attributes)
    return MediaPlayerAlarmAudioSession(context, player, attributes)
  }

  private fun preparedPackagedPlayer(
    soundId: String,
    attributes: AudioAttributes,
  ): MediaPlayer? {
    val asset = PackagedAlarmSoundCatalog.find(soundId) ?: return null
    val player = MediaPlayer()
    return runCatching {
      context.assets.openFd(asset.assetPath).use { descriptor ->
        player.setAudioAttributes(attributes)
        player.setDataSource(
          descriptor.fileDescriptor,
          descriptor.startOffset,
          descriptor.length,
        )
      }
      player.isLooping = true
      player.prepare()
      player
    }.getOrElse {
      player.release()
      null
    }
  }

  private fun preparedSystemFallbackPlayer(attributes: AudioAttributes): MediaPlayer {
    val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      ?: throw IllegalStateException("no packaged or system alarm sound is available")
    return MediaPlayer().apply {
      setAudioAttributes(attributes)
      setDataSource(context, uri)
      isLooping = true
      prepare()
    }
  }
}

private class MediaPlayerAlarmAudioSession(
  context: Context,
  private val player: MediaPlayer,
  attributes: AudioAttributes,
) : AlarmAudioSession {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
      AudioManager.AUDIOFOCUS_GAIN -> runCatching { if (!player.isPlaying) player.start() }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
      AudioManager.AUDIOFOCUS_LOSS,
      -> runCatching { if (player.isPlaying) player.pause() }
    }
  }
  private val focusRequest = if (Build.VERSION.SDK_INT >= 26) {
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
      .setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener(focusListener)
      .build()
  } else {
    null
  }
  private var started = false
  private var released = false

  override fun start() {
    check(!released)
    val focusResult = if (Build.VERSION.SDK_INT >= 26) {
      audioManager.requestAudioFocus(checkNotNull(focusRequest))
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        focusListener,
        AudioManager.STREAM_ALARM,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
      )
    }
    if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      throw IllegalStateException("alarm audio focus was denied")
    }
    player.start()
    started = true
  }

  override fun stop() {
    if (released) return
    released = true
    runCatching { if (started && player.isPlaying) player.stop() }
    player.release()
    if (Build.VERSION.SDK_INT >= 26) {
      focusRequest?.let(audioManager::abandonAudioFocusRequest)
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusListener)
    }
  }
}
