package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
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
@Config(sdk = [28], application = ClockHandoffApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativePhoneActionLifecycleTest {
  @Test fun clockHandoffFinishesEvenWhenTheOverlayStopsBeforeItsNextFrame() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<ClockHandoffApplication>()
    shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"))
    val controller = Robolectric.buildActivity(AssistActivity::class.java).create().start().resume()
    // Capture on the actual application used by the Activity's ViewModel, after Android returns.
    app.launches.clear()
    try {
      val activity = controller.get()
      val vm = ViewModelProvider(activity)[MainViewModel::class.java]
      vm.updateFloatingText("iniciá el cronómetro")
      vm.sendFloatingText { fail("Local request must not send WhatsApp") }
      val clockLaunches = app.launches.filter { it.action == "com.android.deskclock.action.START_STOPWATCH" }
      assertEquals("Clock handoff missing or duplicated; assistant state: ${vm.floatingAssistant.value}", 1, clockLaunches.size)
      assertEquals("com.google.android.deskclock", clockLaunches.single().component?.packageName)
      assertEquals("com.android.deskclock.HandleDeskClockApiCalls", clockLaunches.single().component?.className)
      assertFalse(activity.isFinishing)
      controller.pause().stop()
      // Drain current Android callbacks only after stopping; do not advance a Compose frame.
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()
      assertTrue(activity.isFinishing)
      assertNull(vm.consumePhoneActionCompletion())
      assertEquals("Completion must not launch another activity", 1, app.launches.size)
    } finally {
      controller.destroy()
      Dispatchers.resetMain()
    }
  }
}

/** Observes the real Android call without fabricating executor results or suppressing failures. */
class ClockHandoffApplication : Application() {
  val launches = mutableListOf<Intent>()

  override fun startActivity(intent: Intent) {
    super.startActivity(intent)
    launches.add(Intent(intent))
  }
}
