package com.example

import android.view.KeyEvent
import com.example.assist.HotkeyHoldTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HotkeyHoldTrackerTest {

  private fun down(keyCode: Int) = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)

  private fun up(keyCode: Int) = KeyEvent(KeyEvent.ACTION_UP, keyCode)

  @Before
  fun setUp() {
    HotkeyHoldTracker.reset()
  }

  @Test
  fun `key that is still down is reported as a hold candidate`() {
    HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_000L)

    val held = HotkeyHoldTracker.heldCandidate(nowElapsedMs = 1_500L)

    assertEquals(KeyEvent.KEYCODE_ASSIST, held?.keyCode)
    assertEquals(1_000L, held?.downAtElapsedMs)
  }

  @Test
  fun `released key is a tap, not a hold`() {
    HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_000L)
    HotkeyHoldTracker.onKeyEvent(up(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_120L)

    assertNull(HotkeyHoldTracker.heldCandidate(nowElapsedMs = 1_200L))
  }

  @Test
  fun `auto-repeat keeps the original press time`() {
    HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_000L)
    HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_400L)

    assertEquals(1_000L, HotkeyHoldTracker.heldCandidate(nowElapsedMs = 1_500L)?.downAtElapsedMs)
  }

  @Test
  fun `stale key-down with a missed key-up is discarded`() {
    HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_000L)

    assertNull(HotkeyHoldTracker.heldCandidate(nowElapsedMs = 60_000L))
    assertNull(HotkeyHoldTracker.held.value)
  }

  @Test
  fun `volume keys never count as the assistant hotkey`() {
    HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_VOLUME_DOWN), nowElapsedMs = 1_000L)

    assertNull(HotkeyHoldTracker.heldCandidate(nowElapsedMs = 1_100L))
  }

  @Test
  fun `awaitRelease resumes only when the held key goes up`() =
    runTest(UnconfinedTestDispatcher()) {
      HotkeyHoldTracker.onKeyEvent(down(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_000L)
      val held = HotkeyHoldTracker.heldCandidate(nowElapsedMs = 1_100L)!!

      var released = false
      val job = launch {
        HotkeyHoldTracker.awaitRelease(held)
        released = true
      }

      HotkeyHoldTracker.onKeyEvent(up(KeyEvent.KEYCODE_BACK), nowElapsedMs = 1_200L)
      assertFalse(released)

      HotkeyHoldTracker.onKeyEvent(up(KeyEvent.KEYCODE_ASSIST), nowElapsedMs = 1_300L)
      assertTrue(released)
      job.join()
    }
}
