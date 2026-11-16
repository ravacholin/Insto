package com.example.phone

import android.app.Application
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import com.example.testsupport.installGoogleClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeStopwatchControllerTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private fun executor() = PhoneActionExecutor(app)

  @Test fun operationsGoToTheGoogleClockHandlerRatherThanAnInternalCounter() {
    val cases = listOf(
      StopwatchOp.START to "com.android.deskclock.action.START_STOPWATCH",
      StopwatchOp.RESUME to "com.android.deskclock.action.START_STOPWATCH",
      StopwatchOp.PAUSE to "com.android.deskclock.action.PAUSE_STOPWATCH",
      StopwatchOp.STATUS to "com.android.deskclock.action.SHOW_STOPWATCH",
    )
    installGoogleClock(app, cases.map { it.second }.distinct())
    cases.forEach { (operation, action) ->
      val result = executor().execute(PhoneCommand.Stopwatch(operation))
      assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
      val intent = shadowOf(app).nextStartedActivity
      assertNotNull(intent)
      assertEquals(action, intent.action)
      assertEquals("com.google.android.deskclock", intent.`package`)
      assertFalse(result.retainRequest)
    }
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun unsupportedOperationKeepsRequestAndOffersTheInstalledClock() {
    installGoogleClock(app)
    val result = executor().execute(PhoneCommand.Stopwatch(StopwatchOp.PAUSE))
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertTrue(result.retainRequest)
    assertEquals("com.google.android.deskclock", result.appOptions.single().packageName)
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun absentGoogleClockCannotBecomeASuccessOrAnUnrelatedAppLaunch() {
    val result = executor().execute(PhoneCommand.Stopwatch(StopwatchOp.START))
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertTrue(result.retainRequest)
    assertTrue(result.appOptions.isEmpty())
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun privateHandlerCannotBeLaunched() {
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"), exported = false)
    val result = executor().execute(PhoneCommand.Stopwatch(StopwatchOp.START))
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun protectedHandlerCannotBeLaunchedWithoutItsPermission() {
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"), permission = "android.permission.READ_PRIVILEGED_PHONE_STATE")
    val result = executor().execute(PhoneCommand.Stopwatch(StopwatchOp.START))
    assertEquals(PhoneActionOutcome.REJECTED, result.outcome)
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun resetNeedsConfirmationEvenWhenInstoHasNoLocalTime() {
    installGoogleClock(app, listOf("com.android.deskclock.action.RESET_STOPWATCH"))
    val executor = executor()
    val result = executor.execute(PhoneCommand.Stopwatch(StopwatchOp.RESET))
    assertEquals(PhoneActionOutcome.NEEDS_INPUT, result.outcome)
    assertEquals(PhoneCommand.Stopwatch(StopwatchOp.RESET), result.confirmation)
    assertNull(shadowOf(app).nextStartedActivity)
    val confirmed = executor.execute(result.confirmation!!, confirmed = true)
    assertEquals(PhoneActionOutcome.HANDOFF, confirmed.outcome)
    assertEquals("com.android.deskclock.action.RESET_STOPWATCH", shadowOf(app).nextStartedActivity.action)
    assertNull(shadowOf(app).nextStartedActivity)
  }

  @Test fun lockedPhoneCannotLaunchClock() {
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"))
    shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
    assertEquals(PhoneActionOutcome.REJECTED, executor().execute(PhoneCommand.Stopwatch(StopwatchOp.START)).outcome)
    assertNull(shadowOf(app).nextStartedActivity)
  }
}
