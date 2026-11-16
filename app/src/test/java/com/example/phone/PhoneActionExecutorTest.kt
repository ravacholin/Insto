package com.example.phone

import android.app.Application
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhoneActionExecutorTest {
  @Test fun clockIntentsCarryExactDurationAndTime() {
    val timer = PhoneActionExecutor.clockIntent(PhoneCommand.Timer(600, "fideos"))!!
    assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
    assertEquals(600, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
    assertEquals("fideos", timer.getStringExtra(AlarmClock.EXTRA_MESSAGE))
    val alarm = PhoneActionExecutor.clockIntent(PhoneCommand.Alarm(7, 30, listOf(2, 4)))!!
    assertEquals(7, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
    assertEquals(30, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
    assertEquals(arrayListOf(2, 4), alarm.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
  }
  @Test fun pastTimeRequiresConfirmationBeforeLaunching() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val executor = PhoneActionExecutor(app, nowHourMinute = { 20 to 0 })
    assertNotNull(executor.execute(PhoneCommand.Alarm(7, 0)).confirmation)
  }
}
