package com.example.phone

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlatformActionExecutorTest {
  private val app get()=ApplicationProvider.getApplicationContext<Application>()
  @Test fun missingNotificationAccessIsALocalPermissionResult() {
    val result=PhoneActionExecutor(app).execute(PhoneCommand.Platform(PlatformAction.NOTIFICATION_LIST))
    assertEquals(PhoneActionOutcome.NEEDS_INPUT,result.outcome)
    assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,result.accessSettingsAction)
    assertTrue(result.retainRequest)
  }
  @Test fun invalidMessageTargetCannotLaunchAnActivity() {
    val result=PhoneActionExecutor(app).execute(PhoneCommand.Platform(PlatformAction.MESSAGE_DRAFT,"not a number",content="Hola"))
    assertEquals(PhoneActionOutcome.REJECTED,result.outcome)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun brightnessWithoutSpecialAccessDoesNotChangeTheSetting() {
    shadowOf(app.getSystemService(android.app.AppOpsManager::class.java)).setMode(
      android.app.AppOpsManager.OPSTR_WRITE_SETTINGS, android.os.Process.myUid(), app.packageName,
      android.app.AppOpsManager.MODE_ERRORED)
    assertFalse(Settings.System.canWrite(app))
    val before=Settings.System.getInt(app.contentResolver,Settings.System.SCREEN_BRIGHTNESS,100)
    val result=PhoneActionExecutor(app).execute(PhoneCommand.Platform(PlatformAction.BRIGHTNESS,percent=40))
    assertEquals(PhoneActionOutcome.NEEDS_INPUT,result.outcome)
    assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS,result.accessSettingsAction)
    assertEquals(before,Settings.System.getInt(app.contentResolver,Settings.System.SCREEN_BRIGHTNESS,100))
  }
  @Test fun alarmCancellationCannotPickAnUnspecifiedAlarm() {
    val result=PhoneActionExecutor(app).execute(PhoneCommand.Platform(PlatformAction.ALARM_DISMISS))
    assertEquals(PhoneActionOutcome.REJECTED,result.outcome)
    assertNull(shadowOf(app).nextStartedActivity)
  }
  @Test fun platformCommandsRoundTripAndRejectExtraneousFields() {
    val command=PhoneCommand.Platform(PlatformAction.MESSAGE_DRAFT,"+541112345678",content="¡Llegué, José!")
    assertEquals(command,PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
    try {
      PhoneCommandCodec.decode(PhoneCommandCodec.encode(command).put("component","arbitrary"));fail("Unknown fields")
    } catch (_:IllegalArgumentException) { }
  }
}
