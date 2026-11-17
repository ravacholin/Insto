package com.example

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AssistActivity
import com.example.testsupport.installGoogleClock
import com.example.testsupport.resetApplicationViewModelFactory
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
import org.junit.Before
import org.junit.After
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
  @Before @After fun resetApplicationFactory() { resetApplicationViewModelFactory() }

  @Test fun clockHandoffFinishesEvenWhenTheOverlayStopsBeforeItsNextFrame() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"))
    val controller = Robolectric.buildActivity(AssistActivity::class.java).create().start().resume()
    try {
      val activity = controller.get()
      val vm = ViewModelProvider(activity)[MainViewModel::class.java]
      assertSame("The activity ViewModel must use this test's Application", app, vm.getApplication<Application>())
      vm.updateFloatingText("iniciá el cronómetro")
      vm.sendFloatingText { fail("Local request must not send WhatsApp") }
      val launched = shadowOf(app).nextStartedActivity
      assertNotNull("Clock handoff missing: ${vm.floatingAssistant.value}", launched)
      assertEquals("com.android.deskclock.action.START_STOPWATCH", launched!!.action)
      assertEquals("com.google.android.deskclock", launched.component?.packageName)
      assertEquals("com.android.deskclock.HandleDeskClockApiCalls", launched.component?.className)
      assertNotNull(vm.floatingAssistant.value.phoneActionCompletion)
      assertFalse(activity.isFinishing)
      controller.pause().stop()
      // Drain current Android callbacks only after stopping; do not advance a Compose frame.
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()
      assertTrue(activity.isFinishing)
      assertNull(vm.consumePhoneActionCompletion())
      assertNull("Stopping the overlay must not dispatch again", shadowOf(app).nextStartedActivity)
    } finally {
      controller.destroy()
      Dispatchers.resetMain()
    }
  }
}
