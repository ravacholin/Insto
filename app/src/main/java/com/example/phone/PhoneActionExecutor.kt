package com.example.phone

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Calendar


class PhoneActionExecutor(
  private val context: Context,
  private val nowHourMinute: () -> Pair<Int, Int> = { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) } },
  private val dateNow: () -> Calendar = { Calendar.getInstance() },
  /** The media app of the brief operational context, to choose among several sessions instead of asking. */
  private val preferredMediaPackage: () -> String? = { null },
) {
  fun execute(command: PhoneCommand, confirmed: Boolean = false, executionId: String? = null, foreground: Boolean = false): PhoneActionResult {
    // 1.20.0: only what needs a screen waits for the phone to be unlocked; settings and reads run locked.
    if (!LockScreenPolicy.runsLocked(command) && context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
      return PhoneActionResult(LOCKED, outcome = PhoneActionOutcome.REJECTED)
    return try {
      PhoneCommandCodec.decode(PhoneCommandCodec.encode(command))
      if(command is PhoneCommand.CalendarEvent && command.beginMillis<=System.currentTimeMillis()) return PhoneActionResult("La fecha del evento ya pasó. No preparé un evento con otra fecha.",outcome=PhoneActionOutcome.REJECTED)
      when (command) {
        is PhoneCommand.Platform -> if (command.action == PlatformAction.INSTINCT_CAPABILITY)
          InstinctDeviceCapabilities.execute(context, command, executionId, foreground)
        else PlatformActionExecutor(context).execute(command, confirmed)
        is PhoneCommand.Media -> MediaControllerAdapter(context).execute(command,
          if (command.packageName == null) preferredMediaPackage() else null)
        is PhoneCommand.TemporaryMode -> TemporaryModeManager(context).execute(command, confirmed)
        PhoneCommand.EndMode -> TemporaryModeManager(context).end(force = true)
        is PhoneCommand.Navigate, is PhoneCommand.MapSearch, is PhoneCommand.SavePlace,
        is PhoneCommand.Dial, is PhoneCommand.CalendarEvent, is PhoneCommand.Camera,
        is PhoneCommand.YouTubeSearch, is PhoneCommand.SpotifySearch, is PhoneCommand.MusicSearch -> EverydayActionExecutor(context).execute(command)
        is PhoneCommand.Timer, is PhoneCommand.Alarm, is PhoneCommand.ClockList -> {
          if (command is PhoneCommand.Alarm && command.date != null) {
            val current = dateNow()
            val next = (current.clone() as Calendar).apply {
              set(Calendar.HOUR_OF_DAY,command.hour);set(Calendar.MINUTE,command.minute)
              set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
              if(timeInMillis<=current.timeInMillis) add(Calendar.DAY_OF_MONTH,1)
            }
            val nextDate = "%04d-%02d-%02d".format(java.util.Locale.ROOT,next.get(Calendar.YEAR),next.get(Calendar.MONTH)+1,next.get(Calendar.DAY_OF_MONTH))
            if(nextDate != command.date || command.days.isNotEmpty()) return PhoneActionResult(
              "El Reloj permite preparar la próxima ocurrencia de esa hora, pero no puedo asegurar la fecha ${command.date}. No creé la alarma. Podés completarla en Reloj.",outcome=PhoneActionOutcome.REJECTED)
          }
          if (command is PhoneCommand.Alarm && command.date == null && command.days.isEmpty() && !confirmed) {
            val (hour, minute) = nowHourMinute()
            if (command.hour * 60 + command.minute <= hour * 60 + minute) {
              return PhoneActionResult("Esa hora ya pasó hoy. ¿La preparo para la próxima ocurrencia, mañana?", confirmation = command, outcome = PhoneActionOutcome.NEEDS_INPUT)
            }
          }
          val intent = checkNotNull(clockIntent(command))
          if (intent.resolveActivity(context.packageManager) == null) PhoneActionResult("No hay una aplicación Reloj compatible instalada.", outcome = PhoneActionOutcome.REJECTED)
          else { launch(intent); PhoneActionResult(if (command is PhoneCommand.ClockList) "Abriendo el Reloj." else "Solicitud enviada al Reloj. Revisá allí la alarma o el temporizador.", outcome = PhoneActionOutcome.HANDOFF) }
        }
        is PhoneCommand.Stopwatch -> NativeStopwatchController(context).execute(command.operation, confirmed)
        is PhoneCommand.OpenApp -> openApp(command)
        is PhoneCommand.Volume -> {
          val audio = context.getSystemService(AudioManager::class.java)
          if (audio.isVolumeFixed) PhoneActionResult("Este dispositivo no permite cambiar el volumen.", outcome = PhoneActionOutcome.REJECTED)
          else {
            if (command.operation == VolumeOp.SET) {
              audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * command.percent.coerceIn(0,100) / 100), 0)
            } else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (command.operation == VolumeOp.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            PhoneActionResult("Volumen multimedia ajustado.")
          }
        }
        is PhoneCommand.Torch -> {
          if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return PhoneActionResult("Permití el acceso a la cámara para usar la linterna.", needsCamera = true, outcome = PhoneActionOutcome.NEEDS_INPUT)
          val manager = context.getSystemService(CameraManager::class.java)
          val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
          if (id == null) PhoneActionResult("Este teléfono no tiene una linterna disponible.", outcome = PhoneActionOutcome.REJECTED)
          else { manager.setTorchMode(id, command.enabled); PhoneActionResult(if (command.enabled) "Linterna encendida." else "Linterna apagada.") }
        }
        is PhoneCommand.Settings -> {
          val action = when (command.kind) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "internet", "conectividad" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
          }
          launch(Intent(action))
          PhoneActionResult("Abrí los ajustes para que completes el cambio.", outcome = PhoneActionOutcome.HANDOFF)
        }
      }
    } catch (_: SecurityException) { PhoneActionResult("Android no permitió esa acción. Revisá los permisos o abrí los ajustes.", outcome = PhoneActionOutcome.REJECTED) }
      catch (_: Exception) { PhoneActionResult("No se pudo completar el pedido en este teléfono. Podés volver a intentarlo.", outcome = PhoneActionOutcome.REJECTED) }
  }

  internal fun matchingApps(name: String): List<AppOption> {
    val apps = launcherApps(context)
    val normalized = PhoneCommandParser.normalize(name)
    val exact = apps.filter { PhoneCommandParser.normalize(it.label) == normalized || it.packageName == name }
    return exact.ifEmpty { apps.filter { PhoneCommandParser.normalize(it.label).contains(normalized) } }
  }

  private fun openApp(command: PhoneCommand.OpenApp): PhoneActionResult {
    val name = command.name
    val matches = if (command.packageName == null) matchingApps(name)
      else matchingApps(command.packageName).filter { it.packageName == command.packageName }
    if (matches.isEmpty()) return PhoneActionResult("No encontré una aplicación llamada $name.", outcome = PhoneActionOutcome.REJECTED)
    if (matches.size > 1) return PhoneActionResult("Encontré varias aplicaciones. Elegí una.", appOptions = matches.take(20), outcome = PhoneActionOutcome.NEEDS_INPUT)
    val target = matches.single()
    val intent = context.packageManager.getLaunchIntentForPackage(target.packageName) ?: return PhoneActionResult("Esa aplicación no se puede abrir.", outcome = PhoneActionOutcome.REJECTED)
    launch(intent)
    return PhoneActionResult("Abriendo ${target.label}.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = target.packageName)
  }

  private fun launch(intent: Intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

  companion object {
    const val LOCKED = "Desbloqueá el teléfono para ejecutar este pedido."
    fun clockIntent(command: PhoneCommand): Intent? = when (command) {
      is PhoneCommand.Timer -> Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds).putExtra(AlarmClock.EXTRA_MESSAGE, command.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      is PhoneCommand.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, command.hour).putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
        .putExtra(AlarmClock.EXTRA_MESSAGE, command.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply {
          if (command.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(command.days))
        }
      is PhoneCommand.ClockList -> Intent(if (command.alarms) AlarmClock.ACTION_SHOW_ALARMS else AlarmClock.ACTION_SHOW_TIMERS)
      else -> null
    }
  }
}

@Suppress("DEPRECATION")
internal fun launcherApps(context: Context): List<AppOption> {
  val packageManager = context.packageManager
  val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return packageManager.queryIntentActivities(query, 0).map {
    AppOption(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
  }.distinctBy { it.packageName }
}
