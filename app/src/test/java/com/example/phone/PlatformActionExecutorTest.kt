package com.example.phone

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Vibrator
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
  @Test fun silenceAndVibrationChangeTheRingerMode() {
    val audio = app.getSystemService(AudioManager::class.java)
    shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
    shadowOf(app.getSystemService(Vibrator::class.java)).setHasVibrator(true)
    val executor = PhoneActionExecutor(app)
    val parser = PhoneCommandParser()
    for ((phrase, mode) in listOf(
      "Poner en silenciar" to AudioManager.RINGER_MODE_SILENT,
      "Modo vibrar" to AudioManager.RINGER_MODE_VIBRATE,
      "Silenciar." to AudioManager.RINGER_MODE_SILENT,
      "Modo normal" to AudioManager.RINGER_MODE_NORMAL,
      "Modo Silenciar" to AudioManager.RINGER_MODE_SILENT,
    )) {
      val command = (parser.parse(phrase) as PhoneRoute.Local).command
      assertEquals(PhoneActionOutcome.COMPLETED, executor.execute(command).outcome)
      assertEquals(mode, audio.ringerMode)
    }
  }
  @Test fun volumeOrdersRaiseAndLowerTheMusicStream() {
    val audio = app.getSystemService(AudioManager::class.java)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    val executor = PhoneActionExecutor(app)
    val parser = PhoneCommandParser()
    for ((phrase, volume) in listOf("Subir volumen" to 4, "Bajar volumen" to 3)) {
      val command = (parser.parse(phrase) as PhoneRoute.Local).command
      assertEquals(PhoneActionOutcome.COMPLETED, executor.execute(command).outcome)
      assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
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
