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
  private val stopwatch: StopwatchRepository,
  private val nowHourMinute: () -> Pair<Int, Int> = { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) } },
) {
  fun execute(command: PhoneCommand, confirmed: Boolean = false): PhoneActionResult {
    if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return PhoneActionResult("Desbloqueá el teléfono para ejecutar este pedido.", outcome = PhoneActionOutcome.REJECTED)
    return try {
      when (command) {
        is PhoneCommand.Timer, is PhoneCommand.Alarm, is PhoneCommand.ClockList -> {
          if (command is PhoneCommand.Alarm && command.days.isEmpty() && !confirmed) {
            val (hour, minute) = nowHourMinute()
            if (command.hour * 60 + command.minute <= hour * 60 + minute) {
              return PhoneActionResult("Esa hora ya pasó hoy. ¿La preparo para la próxima ocurrencia, mañana?", confirmation = command, outcome = PhoneActionOutcome.NEEDS_INPUT)
            }
          }
          val intent = checkNotNull(clockIntent(command))
          if (intent.resolveActivity(context.packageManager) == null) PhoneActionResult("No hay una aplicación Reloj compatible instalada.", outcome = PhoneActionOutcome.REJECTED)
          else { launch(intent); PhoneActionResult(if (command is PhoneCommand.ClockList) "Abriendo el Reloj." else "Solicitud enviada al Reloj. Revisá allí la alarma o el temporizador.", outcome = PhoneActionOutcome.HANDOFF) }
        }
        is PhoneCommand.Stopwatch -> {
          if (command.operation == StopwatchOp.RESET && !confirmed && stopwatch.snapshot().first > 0) {
            return PhoneActionResult("¿Borro el tiempo actual y dejo el cronómetro en cero?", confirmation = command, outcome = PhoneActionOutcome.NEEDS_INPUT)
          }
          stopwatch.apply(command.operation)
          PhoneActionResult(when (command.operation) {
            StopwatchOp.START, StopwatchOp.RESUME -> "Cronómetro en marcha."
            StopwatchOp.PAUSE -> "Cronómetro pausado."
            StopwatchOp.RESET -> "Cronómetro en cero."
            StopwatchOp.STATUS -> "Cronómetro."
          })
        }
        is PhoneCommand.OpenApp -> openApp(command.name)
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
          PhoneActionResult("Abrí los ajustes para que completes el cambio.")
        }
      }
    } catch (_: SecurityException) { PhoneActionResult("Android no permitió esa acción. Revisá los permisos o abrí los ajustes.", outcome = PhoneActionOutcome.REJECTED) }
      catch (_: Exception) { PhoneActionResult("No se pudo completar el pedido en este teléfono. Podés volver a intentarlo.", outcome = PhoneActionOutcome.REJECTED) }
  }

  @Suppress("DEPRECATION")
  private fun openApp(name: String): PhoneActionResult {
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = context.packageManager.queryIntentActivities(query, 0).map {
      AppOption(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName)
    }.distinctBy { it.packageName }
    val normalized = PhoneCommandParser.normalize(name)
    val exact = apps.filter { PhoneCommandParser.normalize(it.label) == normalized || it.packageName == name }
    val matches = exact.ifEmpty { apps.filter { PhoneCommandParser.normalize(it.label).contains(normalized) } }
    if (matches.isEmpty()) return PhoneActionResult("No encontré una aplicación llamada $name.", outcome = PhoneActionOutcome.REJECTED)
    if (matches.size > 1) return PhoneActionResult("Encontré varias aplicaciones. Elegí una.", appOptions = matches.take(20), outcome = PhoneActionOutcome.NEEDS_INPUT)
    val target = matches.single()
    val intent = context.packageManager.getLaunchIntentForPackage(target.packageName) ?: return PhoneActionResult("Esa aplicación no se puede abrir.", outcome = PhoneActionOutcome.REJECTED)
    launch(intent)
    return PhoneActionResult("Abriendo ${target.label}.")
  }

  private fun launch(intent: Intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

  companion object {
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
