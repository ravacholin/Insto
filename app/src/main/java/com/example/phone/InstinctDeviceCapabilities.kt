package com.example.phone

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject

/** Grants are tied to this phone's provider IDs and are intentionally absent from portable backups. */
internal class InstinctCalendarAccess(private val context: Context) {
  private val file = android.util.AtomicFile(java.io.File(context.noBackupFilesDir, "instinct-calendar-access.json"))

  fun enabled(write: Boolean = false): Set<Long> {
    val array = read().optJSONArray(if (write) "writable" else "calendars") ?: return emptySet()
    return (0 until array.length()).mapNotNull { index -> array.optLong(index).takeIf { it > 0 } }.toSet()
  }

  fun set(id: Long, enabled: Boolean, write: Boolean = false) = synchronized(lock) {
    if (write && enabled && id !in this.enabled()) return
    val current = this.enabled(write)
    val key = if (write) "writable" else "calendars"
    val settings = read().put(key, org.json.JSONArray(if (enabled) current + id else current - id))
    if (!write && !enabled) settings.put("writable", org.json.JSONArray(this.enabled(write = true) - id))
    val stream = file.startWrite()
    try {
      stream.write(settings.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
    } catch (error: Exception) {
      file.failWrite(stream)
      throw error
    }
    InstinctCatalogPublisher.changed(context)
  }

  private fun read() = runCatching { JSONObject(file.readFully().toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }

  companion object { private val lock = Any() }
}

/** A missing prerequisite is distinct from a failed or uncertain action. Null means the action can be dispatched. */
internal object InstinctDeviceCapabilities {
  data class Block(val reason: String, val pending: Boolean)

  fun granted(context: Context, permission: String): Boolean = when (permission) {
    Manifest.permission.SCHEDULE_EXACT_ALARM -> Build.VERSION.SDK_INT < 31 ||
      context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    Manifest.permission.POST_NOTIFICATIONS -> (Build.VERSION.SDK_INT < 33 ||
      context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) &&
      context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    Manifest.permission.ACCESS_NOTIFICATION_POLICY ->
      context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    else -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  }

  fun blockers(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject? = null): Block? {
    if (spec.id.startsWith("calendar_")) {
      val grants = InstinctCalendarAccess(context).enabled(write = spec.id != "calendar_query")
      val calendar = params?.optLong("calendarId")
      if (grants.isEmpty() || calendar != null && calendar !in grants) {
        return Block("El calendario no está habilitado para Instinct en este teléfono.", pending = false)
      }
    }
    val missing = spec.permissions.firstOrNull { !granted(context, it) }
    if (missing != null) return Block("Falta el permiso ${missing.substringAfterLast('.')} en Ajustes.", pending = true)
    if (spec.id.startsWith("insto_") && spec.id != "insto_clock_cancel" && !InstinctLocalClock.notificationsEnabled(context)) {
      return Block("Activá el canal «Insto · alarmas y temporizadores» en Ajustes.", pending = true)
    }
    if (spec.id == "torch" && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
      return Block("Este teléfono no tiene linterna.", pending = false)
    }
    if (spec.id == "media" && !InstinctReadiness.granted(context, InstinctReadiness.Capability.NOTIFICATION_LISTENER)) {
      return Block("Habilitá el acceso a notificaciones para controlar la música.", pending = true)
    }
    val handler = when (spec.id) {
      "clock_timer" -> PhoneActionExecutor.clockIntent(PhoneCommand.Timer(1))
      "clock_alarm" -> PhoneActionExecutor.clockIntent(PhoneCommand.Alarm(0, 0))
      "open_app" -> params?.let {
        return if (context.packageManager.getLaunchIntentForPackage(it.getString("package")) == null)
          Block("La aplicación elegida no se puede abrir.", false) else null
      }
      "open_resource" -> Intent(Intent.ACTION_VIEW, Uri.parse(params?.getString("url") ?: "https://example.org"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
      else -> null
    }
    return if (handler != null && handler.resolveActivity(context.packageManager) == null)
      Block("No hay una aplicación compatible instalada.", false) else null
  }

  fun execute(context: Context, command: PhoneCommand.Platform, executionId: String?, foreground: Boolean): PhoneActionResult {
    val (spec, params) = InstinctCapabilities.platform(command)
    if (executionId == null) return rejected("La acción requiere una ejecución registrada de Instinct.")
    blockers(context, spec, params)?.let { return PhoneActionResult(it.reason,
      outcome = if (it.pending) PhoneActionOutcome.NEEDS_INPUT else PhoneActionOutcome.REJECTED) }
    return when {
      spec.id.startsWith("calendar_") -> InstinctCalendarActions(context.contentResolver).execute(spec.id, params)
      spec.id.startsWith("insto_") -> InstinctLocalClock.execute(context, executionId, spec.id, params)
      !foreground -> PhoneActionResult("Abrí Insto para continuar con esta acción.", outcome = PhoneActionOutcome.NEEDS_INPUT)
      else -> {
        val intent = when (spec.id) {
          "open_app" -> context.packageManager.getLaunchIntentForPackage(params.getString("package"))
          "open_resource" -> Intent(Intent.ACTION_VIEW, Uri.parse(params.getString("url"))).addCategory(Intent.CATEGORY_BROWSABLE)
          else -> null
        } ?: return rejected("No hay una aplicación compatible instalada.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        PhoneActionResult("Solicitud entregada a la aplicación.", outcome = PhoneActionOutcome.HANDOFF)
      }
    }
  }

  private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
}

/** No compatible local interface is registered today. A real integration must supply both dispatch and observation. */
internal interface OmniremoteAdapter {
  fun devices(): List<String>
  fun supports(device: String, control: Control): Boolean
  fun execute(device: String, control: Control): PhoneActionResult
  enum class Control { POWER_ON, POWER_OFF, VOLUME_UP, VOLUME_DOWN, MUTE }
}
