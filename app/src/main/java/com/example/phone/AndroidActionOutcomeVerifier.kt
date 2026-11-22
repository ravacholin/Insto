package com.example.phone

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Reads back what [ActionOutcomeRules] judges (1.20.0): the music stream, the ringer mode and the effective
 * interruption filter (not what the setter returned), the torch through the camera's callback, the playback of the
 * active media session, and Insto's own temporary mode. Reads only: it never changes anything. Never waits on the main
 * thread: there, what Android has not reported yet reads as unknown.
 */
class AndroidActionOutcomeVerifier(private val context: Context) : ActionOutcomeVerifier {
  override fun read(command: PhoneCommand): DeviceReading? = when (command) {
    is PhoneCommand.Volume -> context.getSystemService(AudioManager::class.java)?.let {
      DeviceReading(volume = it.getStreamVolume(AudioManager.STREAM_MUSIC), volumeMax = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }
    is PhoneCommand.Torch -> DeviceReading(torch = TorchState.read(context))
    is PhoneCommand.Platform -> DeviceReading(
      ringerMode = context.getSystemService(AudioManager::class.java)?.ringerMode,
      interruptionFilter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter,
    )
    is PhoneCommand.TemporaryMode, PhoneCommand.EndMode -> {
      val manager = TemporaryModeManager(context)
      val lease = runCatching { manager.read() }.getOrNull()
      DeviceReading(temporaryActive = lease != null, temporaryHeld = lease?.let { manager.current(it.kind) == it.applied } ?: false)
    }
    is PhoneCommand.Media -> DeviceReading(playing = playing(command.packageName))
    else -> null
  }

  /** The active session's playback (the named app's, else the first one); null without notification access. */
  private fun playing(packageName: String?): Boolean? = runCatching {
    val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
    val sessions = manager.getActiveSessions(ComponentName(context, MediaAccessService::class.java))
    val session = (if (packageName != null) sessions.firstOrNull { it.packageName == packageName } else sessions.firstOrNull()) ?: return null
    when (session.playbackState?.state) {
      PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
      PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> false
      else -> null
    }
  }.getOrNull()

  /**
   * After the action, media sessions and the torch report asynchronously: off the main thread, up to [waitMillis] for
   * the reading to settle. On the main thread it reads once.
   */
  override fun verify(command: PhoneCommand, before: DeviceReading?, result: PhoneActionResult): Observation {
    var observation = super.verify(command, before, result)
    if (Looper.myLooper() == Looper.getMainLooper() || (command !is PhoneCommand.Media && command !is PhoneCommand.Torch)) return observation
    val deadline = System.nanoTime() + waitMillis * 1_000_000
    while (observation.verdict != OutcomeVerdict.VERIFIED && System.nanoTime() < deadline) {
      Thread.sleep(100)
      observation = super.verify(command, before, result)
    }
    return observation
  }

  companion object { internal var waitMillis = 1_500L }
}

/**
 * The torch, as the camera reports it: Android has no getter, only [CameraManager.TorchCallback], which says the
 * current state right after registering and every change after. Registered once per process, on its own thread.
 */
object TorchState {
  @Volatile private var known: Boolean? = null
  @Volatile private var registered = false
  private val thread by lazy { HandlerThread("insto-torch").apply { start() } }

  fun read(context: Context): Boolean? {
    if (!registered) register(context.applicationContext)
    return known
  }

  @Synchronized private fun register(context: Context) {
    if (registered) return
    val manager = context.getSystemService(CameraManager::class.java) ?: return
    val id = runCatching {
      manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull() ?: return
    runCatching {
      manager.registerTorchCallback(object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) known = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id) known = null }
      }, Handler(thread.looper))
      registered = true
    }
  }
}
