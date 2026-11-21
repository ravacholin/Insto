package com.example

import com.example.testsupport.localActionDecisions

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.phone.PhoneActionOutcome
import com.example.testsupport.ConnectedAccessibility
import com.example.testsupport.installGoogleClock
import com.example.ui.FloatingMode
import com.example.ui.MainViewModel
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StopwatchAccessibilityRoutingTest {
  @Test fun unsupportedIntentActuallyOpensClockAndWaitsForObservedCompletion() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    installGoogleClock(app)
    val disconnect = ConnectedAccessibility.start(app)
    val vm = MainViewModel(app, hybridRouter = localActionDecisions())
    try {
      vm.updateFloatingText("iniciar un cronómetro")
      vm.sendFloatingText { fail("A stopwatch must not go to WhatsApp") }
      runCurrent()
      assertEquals(FloatingMode.SENDING, vm.floatingAssistant.value.mode)
      assertNull(vm.consumePhoneActionCompletion())
      shadowOf(Looper.getMainLooper()).idle()
      val intent = shadowOf(app).nextStartedActivity
      assertEquals("com.google.android.deskclock", intent.component!!.packageName)
      assertNull(vm.consumePhoneActionCompletion())
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
      assertEquals("iniciar un cronómetro", vm.floatingAssistant.value.text)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertNotNull(vm.floatingAssistant.value.error)
      assertNull(vm.consumePhoneActionCompletion())
    } finally { vm.dismissFloatingAssistant(); disconnect(); Dispatchers.resetMain() }
  }

  @Test fun retryAfterAnUnverifiedStopwatchChangeAsksForANewConfirmation() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    installGoogleClock(app)
    val disconnect = ConnectedAccessibility.start(app)
    val vm = MainViewModel(app, hybridRouter = localActionDecisions())
    try {
      vm.updateFloatingText("iniciar un cronómetro")
      vm.sendFloatingText { fail("A stopwatch must not go to WhatsApp") }
      runCurrent()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("com.google.android.deskclock", shadowOf(app).nextStartedActivity.component!!.packageName)
      // The Clock never shows the expected state: «no pude comprobar el cambio», as before.
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
      assertTrue(vm.floatingAssistant.value.error!!.startsWith("No pude comprobar el cambio del cronómetro."))
      assertFalse(vm.floatingAssistant.value.needsConfirmation)

      // Sending the same text again must not touch the Clock on its own: it may already be running.
      vm.sendFloatingText { fail("A stopwatch must not go to WhatsApp") }
      runCurrent()
      shadowOf(Looper.getMainLooper()).idle()
      assertNull(shadowOf(app).nextStartedActivity)
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.status!!.startsWith("El intento anterior pudo haberse realizado"))
    } finally { vm.dismissFloatingAssistant(); disconnect(); Dispatchers.resetMain() }
  }

  @Test fun closingBeforeTheClockLaunchCancelsTheQueuedOperation() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    installGoogleClock(app)
    val disconnect = ConnectedAccessibility.start(app)
    val vm = MainViewModel(app, hybridRouter = localActionDecisions())
    try {
      vm.updateFloatingText("iniciá el cronómetro")
      vm.sendFloatingText { fail("Local action") }
      runCurrent()
      vm.dismissFloatingAssistant()
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
      assertNull(shadowOf(app).nextStartedActivity)
      assertNull(vm.consumePhoneActionCompletion())
    } finally { disconnect(); Dispatchers.resetMain() }
  }
}
