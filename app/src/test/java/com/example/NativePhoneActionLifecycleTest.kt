package com.example

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AssistActivity
import com.example.testsupport.installGoogleClock
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class NativePhoneActionLifecycleTest {
  @Test fun clockHandoffFinishesEvenWhenTheOverlayStopsBeforeItsNextFrame() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"))
    val controller = Robolectric.buildActivity(AssistActivity::class.java).create().start().resume()
    try {
      val activity = controller.get()
      val vm = ViewModelProvider(activity)[MainViewModel::class.java]
      vm.updateFloatingText("iniciá el cronómetro")
      vm.sendFloatingText { fail("Local request must not send WhatsApp") }
      // Exact intents and single dispatch are verified by NativeStopwatchControllerTest and
      // FloatingPhoneRoutingTest. Here the real handoff must produce completion before stopping.
      val handedOff = vm.floatingAssistant.value
      assertNull(handedOff.error)
      assertNotNull("Native handoff did not produce completion: $handedOff", handedOff.phoneActionCompletion)
      assertFalse(handedOff.needsClockAccessibility)
      assertFalse(activity.isFinishing)
      controller.pause().stop()
      // Drain current Android callbacks only after stopping; do not advance a Compose frame.
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()
      assertTrue(activity.isFinishing)
      assertNull(vm.consumePhoneActionCompletion())
    } finally {
      controller.destroy()
      Dispatchers.resetMain()
    }
  }
}
