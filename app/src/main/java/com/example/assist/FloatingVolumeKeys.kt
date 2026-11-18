package com.example.assist

import android.os.Handler
import android.view.KeyEvent
import com.example.data.VolumeKeyTrigger

internal class FloatingVolumeKeys(
  private val mainHandler: Handler,
  private val openFloatingAssistant: () -> Boolean,
) {
  // Volume key hold state
  private var volumeHoldRunnable: Runnable? = null
  private var volumeHoldTriggered = false

  fun handle(event: KeyEvent, trigger: VolumeKeyTrigger): Boolean? {
    // Observe only (never consume): lets the assist hotkey tell a tap from a hold.
    HotkeyHoldTracker.onKeyEvent(event)
    if (trigger == VolumeKeyTrigger.DISABLED) return null

    val targetKeyCode =
      when (trigger) {
        VolumeKeyTrigger.VOLUME_DOWN_HOLD -> KeyEvent.KEYCODE_VOLUME_DOWN
        VolumeKeyTrigger.VOLUME_UP_HOLD -> KeyEvent.KEYCODE_VOLUME_UP
        VolumeKeyTrigger.DISABLED -> return null
      }

    if (event.keyCode != targetKeyCode) return null

    when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount == 0) {
          volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
          volumeHoldTriggered = false
          val holdTask = Runnable {
            volumeHoldTriggered = openFloatingAssistant()
          }
          volumeHoldRunnable = holdTask
          mainHandler.postDelayed(holdTask, 420L)
          return false // Let normal short taps adjust volume seamlessly
        }
        return volumeHoldTriggered
      }
      KeyEvent.ACTION_UP -> {
        volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        volumeHoldRunnable = null
        if (volumeHoldTriggered) {
          volumeHoldTriggered = false
          return true
        }
      }
    }
    return null
  }

  fun reset() {
    volumeHoldRunnable?.let(mainHandler::removeCallbacks)
    volumeHoldRunnable = null
    volumeHoldTriggered = false
    HotkeyHoldTracker.reset()
  }
}
