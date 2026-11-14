package com.example.assist

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Tracks which hardware key is physically held right now, fed by the accessibility service's key
 * event filter.
 *
 * Android never tells an assistant app whether its hotkey is still down, so this is the only way to
 * tell a tap ("open the chat") from a hold ("record a voice note until I let go").
 */
object HotkeyHoldTracker {

  data class HeldKey(val keyCode: Int, val downAtElapsedMs: Long)

  /** A key-down older than this is assumed to be a missed key-up, not a real hold. */
  private const val MAX_DOWN_AGE_MS = 4_000L

  private val heldState = MutableStateFlow<HeldKey?>(null)
  val held: StateFlow<HeldKey?> = heldState

  fun onKeyEvent(event: KeyEvent, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
    if (isIgnoredKey(event.keyCode)) return
    when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        // Auto-repeats of the same key must not reset the original press time.
        if (heldState.value?.keyCode != event.keyCode) {
          heldState.value = HeldKey(event.keyCode, nowElapsedMs)
        }
      }
      KeyEvent.ACTION_UP -> {
        if (heldState.value?.keyCode == event.keyCode) {
          heldState.value = null
        }
      }
    }
  }

  /** The key that is plausibly the assistant hotkey still being held, or null. */
  fun heldCandidate(nowElapsedMs: Long = SystemClock.elapsedRealtime()): HeldKey? {
    val current = heldState.value ?: return null
    if (nowElapsedMs - current.downAtElapsedMs > MAX_DOWN_AGE_MS) {
      heldState.value = null
      return null
    }
    return current
  }

  /** Suspends until [key] is released (or replaced by a different press). */
  suspend fun awaitRelease(key: HeldKey) {
    heldState.first { it != key }
  }

  fun reset() {
    heldState.value = null
  }

  // Volume keys can't summon the assistant and already drive their own hold trigger.
  private fun isIgnoredKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
      keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
      keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
}
