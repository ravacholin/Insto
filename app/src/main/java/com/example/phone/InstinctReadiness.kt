package com.example.phone

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import org.json.JSONObject

/**
 * «Preparar Insto para funcionar bloqueado» (1.20.0): what Android requires the person to grant by hand for Instinct's
 * orders to run with the phone locked, the screen off or Insto in the background. It only reads: it never shows a
 * dialog in the background, never reopens Settings by itself and never grants anything through Accessibility. A
 * capability an order needed and did not have is noted ([noteMissing]) and shown the next time the person opens the
 * settings, where each one has its own button. What is granted is never asked again.
 */
object InstinctReadiness {
  enum class Capability(val label: String, val why: String) {
    NOTIFICATIONS("Notificaciones", "para avisarte lo que Instinct hizo, pedir confirmaciones y mostrar los modos temporales."),
    DND_ACCESS("Acceso a No molestar", "para silenciar, poner en vibración y activar o desactivar No molestar."),
    EXACT_ALARMS("Alarmas y recordatorios", "para restaurar un modo temporal y disparar órdenes programadas a la hora exacta."),
    BATTERY("Sin optimización de batería", "para que Android no demore ni corte a Insto en segundo plano (Doze) y pueda mostrar el aviso de un modo temporal."),
    NOTIFICATION_LISTENER("Acceso a notificaciones", "para leer los mensajes de Instinct por WhatsApp y controlar la música de otras apps."),
    CAMERA("Cámara", "para la linterna."),
    ACCESSIBILITY("Accesibilidad de Insto (opcional)", "para abrir otras apps sin tocar nada; no hace falta para volumen, linterna, silencio, No molestar ni música."),
  }

  data class Item(val capability: Capability, val granted: Boolean, val neededAt: Long?)

  private const val FILE = "instinct-readiness-needed.json"

  fun status(context: Context): List<Item> {
    val app = context.applicationContext
    val needed = needed(app)
    return Capability.entries.map { Item(it, granted(app, it), needed[it]) }
  }

  /** What is missing, the ones an order already needed first. */
  fun missing(context: Context): List<Item> = status(context).filter { !it.granted }.sortedBy { it.neededAt?.let { at -> -at } ?: 0L }

  fun granted(context: Context, capability: Capability): Boolean = runCatching {
    when (capability) {
      Capability.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      Capability.DND_ACCESS -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
      Capability.EXACT_ALARMS -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
      Capability.BATTERY -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
      Capability.NOTIFICATION_LISTENER -> Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        .contains(ComponentName(context, MediaAccessService::class.java).flattenToString())
      Capability.CAMERA -> context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
      Capability.ACCESSIBILITY -> com.example.assist.PingAutoSendAccessibilityService.isEnabled(context)
    }
  }.getOrDefault(false)

  /**
   * Where the person grants [capability]: Insto's own page in Settings when Android has one. Opened only from a tap
   * in Insto's settings. Notifications and the camera are runtime permissions the screen asks with the system dialog.
   */
  fun settingsIntent(context: Context, capability: Capability): Intent? {
    val pkg = Uri.fromParts("package", context.packageName, null)
    return when (capability) {
      Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
      Capability.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
      Capability.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
      // The list, not the direct request: Play policies allow the direct one only to some apps.
      Capability.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
      Capability.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      Capability.CAMERA -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
      Capability.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  /** The capability a result says is missing; null when it needs none. Pure but for the Android constants. */
  fun of(result: PhoneActionResult): Capability? = when {
    result.needsNotifications -> Capability.NOTIFICATIONS
    result.needsCamera -> Capability.CAMERA
    result.accessSettingsAction == Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS -> Capability.DND_ACCESS
    result.accessSettingsAction == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS -> Capability.NOTIFICATION_LISTENER
    result.accessSettingsAction == Settings.ACTION_ACCESSIBILITY_SETTINGS -> Capability.ACCESSIBILITY
    (result.confirmation as? PhoneCommand.TemporaryMode)?.approximate == true -> Capability.EXACT_ALARMS
    else -> null
  }

  /** An order needed [result]'s capability: noted for Settings, nothing shown now. */
  fun noteMissing(context: Context, result: PhoneActionResult) {
    val capability = of(result) ?: return
    val app = context.applicationContext
    runCatching {
      val all = needed(app).toMutableMap().apply { put(capability, InstinctScheduler.wallClock()) }
      writeAtomically(File(app.noBackupFilesDir, FILE), JSONObject().apply { all.forEach { (k, v) -> put(k.name, v) } }.toString(), "insto-readiness-")
    }
  }

  private fun needed(context: Context): Map<Capability, Long> = runCatching {
    val o = JSONObject(File(context.noBackupFilesDir, FILE).readText())
    o.keys().asSequence().mapNotNull { key -> Capability.entries.firstOrNull { it.name == key }?.let { it to o.getLong(key) } }.toMap()
  }.getOrDefault(emptyMap())

  /** Once granted, the note goes: nothing granted is ever asked again. */
  fun forgetGranted(context: Context) {
    val app = context.applicationContext
    val needed = needed(app)
    val left = needed.filterKeys { !granted(app, it) }
    if (left.size == needed.size) return
    runCatching {
      writeAtomically(File(app.noBackupFilesDir, FILE), JSONObject().apply { left.forEach { (k, v) -> put(k.name, v) } }.toString(), "insto-readiness-")
    }
  }
}
