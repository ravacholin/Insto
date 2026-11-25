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

/** Discovery, authorization, dispatch and read-back for the capabilities compiled into Insto. */
internal object InstinctDeviceCapabilities {
  data class Block(val reason: String, val pending: Boolean)
  data class Discovery(val blocker: Block?, val targets: List<String> = emptyList())

  private val registry = mapOf(
    InstinctCapabilities.Adapter.ANDROID_LOCAL to AndroidLocalCapabilities,
    InstinctCapabilities.Adapter.ANDROID_INTENT to AndroidIntentCapabilities,
    InstinctCapabilities.Adapter.OMNIREMOTE to OmniremoteAdapter(),
  )

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

  fun discover(context: Context, spec: InstinctCapabilities.Spec): Discovery =
    blockers(context, spec)?.let { Discovery(it) }
      ?: Discovery(null, registry.getValue(spec.adapter).targets(context, spec))

  fun blockers(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject? = null): Block? {
    val adapter = registry.getValue(spec.adapter)
    val blocker = adapter.blocker(context, spec, params)
    if (blocker != null && !blocker.pending) return blocker
    val missing = spec.permissions.firstOrNull { !granted(context, it) }
    return if (missing != null) Block("Falta el permiso ${missing.substringAfterLast('.')} en Ajustes.", pending = true)
    else blocker
  }

  fun execute(context: Context, command: PhoneCommand.Platform, executionId: String?, foreground: Boolean): PhoneActionResult {
    val (spec, params) = InstinctCapabilities.platform(command)
    if (executionId == null) return rejected("La acción requiere una ejecución registrada de Instinct.")
    blockers(context, spec, params)?.let { return PhoneActionResult(it.reason,
      outcome = if (it.pending) PhoneActionOutcome.NEEDS_INPUT else PhoneActionOutcome.REJECTED) }
    if (!spec.runsLocked && !foreground)
      return PhoneActionResult("Abrí Insto para continuar con esta acción.", outcome = PhoneActionOutcome.NEEDS_INPUT)
    return registry.getValue(spec.adapter).execute(context, spec, params, executionId)
  }

  /** Recovery can observe a provider only while its current authorization still permits the read. */
  fun observe(context: Context, command: PhoneCommand.Platform, executionId: String): PhoneActionResult? {
    val (spec, params) = InstinctCapabilities.platform(command)
    if (blockers(context, spec, params) != null) return null
    return registry.getValue(spec.adapter).observe(context, spec, params, executionId)?.takeIf { it.verifiedLocally }
  }
}

internal interface InstinctCapabilityAdapter {
  fun blocker(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject?): InstinctDeviceCapabilities.Block? = null
  fun targets(context: Context, spec: InstinctCapabilities.Spec): List<String> = emptyList()
  fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult
  fun observe(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult? = null
}

private object AndroidLocalCapabilities : InstinctCapabilityAdapter {
  override fun blocker(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject?): InstinctDeviceCapabilities.Block? {
    if (spec.category == InstinctCategory.CALENDARIO) {
      val grants = InstinctCalendarAccess(context).enabled(write = spec.id != "calendar_query")
      val calendar = params?.optLong("calendarId")
      if (grants.isEmpty() || calendar != null && calendar !in grants)
        return InstinctDeviceCapabilities.Block("El calendario no está habilitado para Instinct en este teléfono.", pending = false)
    }
    if (spec.id in setOf("insto_alarm", "insto_timer") && !InstinctLocalClock.notificationsEnabled(context))
      return InstinctDeviceCapabilities.Block("Activá el canal «Insto · alarmas y temporizadores» en Ajustes.", pending = true)
    if (spec.id == "torch" && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
      return InstinctDeviceCapabilities.Block("Este teléfono no tiene linterna.", pending = false)
    if (spec.id == "media" && !InstinctReadiness.granted(context, InstinctReadiness.Capability.NOTIFICATION_LISTENER))
      return InstinctDeviceCapabilities.Block("Habilitá el acceso a notificaciones para controlar la música.", pending = true)
    return null
  }

  override fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult =
    when (spec.category) {
      InstinctCategory.CALENDARIO -> InstinctCalendarActions(context.contentResolver).execute(spec.id, params)
      InstinctCategory.RELOJ -> InstinctLocalClock.execute(context, executionId, spec.id, params)
      else -> rejected("Capacidad local no registrada.")
    }

  override fun observe(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult? =
    when (spec.id) {
      // Use the step ID for a creation and the target ID for a cancellation.
      "insto_timer", "insto_alarm" -> InstinctLocalClock.recover(context, executionId)
      "insto_clock_cancel" -> InstinctLocalClock.observeCancellation(context, params.getString("clockId"))
      "calendar_update", "calendar_cancel" -> InstinctCalendarActions(context.contentResolver).observe(spec.id, params)
      else -> null
    }
}

private object AndroidIntentCapabilities : InstinctCapabilityAdapter {
  override fun blocker(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject?): InstinctDeviceCapabilities.Block? {
    val handler = when (spec.id) {
      "clock_timer" -> PhoneActionExecutor.clockIntent(PhoneCommand.Timer(1))
      "clock_alarm" -> PhoneActionExecutor.clockIntent(PhoneCommand.Alarm(0, 0))
      "open_app" -> {
        if (params == null) return null
        context.packageManager.getLaunchIntentForPackage(params.getString("package"))
      }
      "open_resource" -> resource(params?.getString("url") ?: "https://example.org")
      // Both searches can use the platform's HTTPS handler when their native app is absent.
      "youtube" -> resource("https://www.youtube.com")
      "spotify" -> resource("https://open.spotify.com")
      else -> return InstinctDeviceCapabilities.Block("Capacidad de Android no registrada.", false)
    }
    return if (handler?.resolveActivity(context.packageManager) == null)
      InstinctDeviceCapabilities.Block("No hay una aplicación compatible instalada.", false) else null
  }

  override fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult {
    val intent = when (spec.id) {
      "open_app" -> context.packageManager.getLaunchIntentForPackage(params.getString("package"))
      "open_resource" -> resource(params.getString("url"))
      else -> null
    } ?: return rejected("No hay una aplicación compatible instalada.")
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return PhoneActionResult("Solicitud entregada a la aplicación.", outcome = PhoneActionOutcome.HANDOFF)
  }

  private fun resource(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
}

internal fun unavailableOmniremote() = InstinctDeviceCapabilities.Block(
  "Falta un contrato local compatible de Omniremote.", pending = false)

private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
