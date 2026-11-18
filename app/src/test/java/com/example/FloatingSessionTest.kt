package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.ui.FloatingMode
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FloatingSessionTest {
  private class Capture : FloatingAudioCapture {
    override var snapshot = FloatingVoiceCapture.Snapshot()
    var cancelled = false
    override fun start(): Result<Unit> { cancelled = false; return Result.success(Unit) }
    override fun finish(): RecordedAudio? = null
    override fun cancel() { cancelled = true }
  }

  @Test fun `typing cancels active microphone and pending silence job`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val capture = Capture()
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler))
      var dispatched = false
      vm.startFloatingListening { dispatched = true }
      runCurrent()
      vm.enterFloatingTextMode()
      vm.updateFloatingText("Hola Instinct")
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.SEND, 8000)
      advanceTimeBy(8000)
      runCurrent()
      assertTrue(capture.cancelled)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertEquals("Hola Instinct", vm.floatingAssistant.value.text)
      assertFalse(dispatched)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

  @Test fun `initial silence stops mic and leaves panel idle without dispatch`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val capture = Capture()
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler))
      var dispatched = false
      vm.startFloatingListening { dispatched = true }
      runCurrent()
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.IDLE, 4000)
      advanceTimeBy(40)
      runCurrent()
      assertTrue(capture.cancelled)
      assertEquals(FloatingMode.IDLE, vm.floatingAssistant.value.mode)
      assertFalse(dispatched)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

  @Test fun `capture started by a permission result polls before resume`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val capture = Capture()
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler))
      vm.pauseFloatingAssistant()
      vm.startFloatingListening { fail("Starting the microphone must not dispatch") }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent()
      assertEquals(FloatingMode.RECORDING, vm.floatingAssistant.value.mode)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

  @Test fun `encoder failure on finish returns to idle instead of leaving dead recording controls`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val capture = Capture()
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler))
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent()
      vm.sendFloatingAudio { fail("Failed encoding must never launch WhatsApp") }
      advanceUntilIdle()
      assertEquals(FloatingMode.IDLE, vm.floatingAssistant.value.mode)
      assertNotNull(vm.floatingAssistant.value.error)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

}
