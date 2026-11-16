package com.example.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import com.example.assist.PingAutoSendAccessibilityService

/** DeskClock-specific intents; availability is checked against the installed Google Clock. */
internal class NativeStopwatchController(private val context: Context) {
  @Suppress("DEPRECATION")
  fun execute(operation: StopwatchOp, confirmed: Boolean): PhoneActionResult {
    val pm = context.packageManager
    val clock = try { pm.getApplicationInfo(GOOGLE_CLOCK, 0) }
      catch (_: PackageManager.NameNotFoundException) { null }
    if (clock == null || !clock.enabled) {
      return PhoneActionResult("No encontré Reloj de Google habilitado en este teléfono.", outcome = PhoneActionOutcome.REJECTED)
    }
    if (operation == StopwatchOp.RESET && !confirmed) {
      return PhoneActionResult("¿Reinicio el cronómetro del Reloj de Google? Se perderá su tiempo actual.",
        confirmation = PhoneCommand.Stopwatch(operation), outcome = PhoneActionOutcome.NEEDS_INPUT)
    }
    val action = when (operation) {
      StopwatchOp.START, StopwatchOp.RESUME -> "START_STOPWATCH"
      StopwatchOp.PAUSE -> "PAUSE_STOPWATCH"
      StopwatchOp.RESET -> "RESET_STOPWATCH"
      StopwatchOp.STATUS -> "SHOW_STOPWATCH"
    }
    val intent = Intent("com.android.deskclock.action.$action").setPackage(GOOGLE_CLOCK)
      .putExtra(AlarmClock.EXTRA_SKIP_UI, operation != StopwatchOp.STATUS)
    val handler = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    if (handler == null || handler.packageName != GOOGLE_CLOCK || !handler.exported || !handler.enabled ||
      !handler.applicationInfo.enabled ||
      (!handler.permission.isNullOrEmpty() && context.checkSelfPermission(handler.permission) != PackageManager.PERMISSION_GRANTED)) {
      if (PingAutoSendAccessibilityService.isConnected()) {
        return PhoneActionResult("Operando el cronómetro en Reloj de Google…",
          stopwatchAutomation = operation, outcome = PhoneActionOutcome.NEEDS_INPUT)
      }
      return PhoneActionResult(
        "Activá Accesibilidad de Insto para que pueda ${operationLabel(operation)} el cronómetro en Reloj de Google.",
        needsClockAccessibility = true, outcome = PhoneActionOutcome.REJECTED,
      )
    }
    // Launch exactly the accessible handler we resolved; do not guess private components.
    intent.setClassName(handler.packageName, handler.name)
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return PhoneActionResult("Solicitud para ${operationLabel(operation)} el cronómetro enviada al Reloj de Google.",
      outcome = PhoneActionOutcome.HANDOFF)
  }

  private fun operationLabel(operation: StopwatchOp): String = when (operation) {
    StopwatchOp.START -> "iniciar"
    StopwatchOp.RESUME -> "continuar"
    StopwatchOp.PAUSE -> "pausar"
    StopwatchOp.RESET -> "reiniciar"
    StopwatchOp.STATUS -> "mostrar"
  }

  companion object { const val GOOGLE_CLOCK = "com.google.android.deskclock" }
}
