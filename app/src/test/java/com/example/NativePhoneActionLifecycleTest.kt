package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.intent.IntentCallback
import androidx.test.runner.intent.IntentMonitorRegistry
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
    // Observe Android's instrumentation boundary without advancing a Compose frame.
    val launches = mutableListOf<Intent>()
    val monitor = IntentMonitorRegistry.getInstance()
    val callback = IntentCallback { intent -> launches.add(Intent(intent)) }
    monitor.addIntentCallback(callback)
    try {
      val activity = controller.get()
      val vm = ViewModelProvider(activity)[MainViewModel::class.java]
      vm.updateFloatingText("iniciá el cronómetro")
      vm.sendFloatingText { fail("Local request must not send WhatsApp") }
      val clockLaunches = launches.filter { it.action == "com.android.deskclock.action.START_STOPWATCH" }
      assertEquals("Clock handoff missing or duplicated; assistant state: ${vm.floatingAssistant.value}", 1, clockLaunches.size)
      assertEquals("com.google.android.deskclock", clockLaunches.single().component?.packageName)
      assertEquals("com.android.deskclock.HandleDeskClockApiCalls", clockLaunches.single().component?.className)
      controller.pause().stop()
      // No Compose frame or return to the assistant is needed to consume completion.
      runCurrent()
      assertTrue(activity.isFinishing)
      assertNull(vm.consumePhoneActionCompletion())
      assertEquals("Completion must not launch another activity", 1, launches.size)
    } finally {
      monitor.removeIntentCallback(callback)
      controller.destroy()
      Dispatchers.resetMain()
    }
  }
}
