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
    InstinctCapabilities.Adapter.OMNIREMOTE to OmniremoteAdapter(listOf(OmniremoteLocalProvider())),
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

  fun discover(context: Context, spec: InstinctCapabilities.Spec): Discovery {
    val discovery = registry.getValue(spec.adapter).discover(context, spec)
    val blocker = permissionBlocker(context, spec, discovery.blocker)
    return if (blocker != null) Discovery(blocker) else discovery
  }

  fun blockers(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject? = null): Block? =
    permissionBlocker(context, spec, registry.getValue(spec.adapter).blocker(context, spec, params))

  private fun permissionBlocker(context: Context, spec: InstinctCapabilities.Spec, blocker: Block?): Block? {
    if (blocker != null && !blocker.pending) return blocker
    val missing = spec.permissions.firstOrNull { !granted(context, it) }
    return if (missing != null) Block("Falta el permiso ${missing.substringAfterLast('.')} en Ajustes.", pending = true)
    else blocker
  }

  fun execute(context: Context, command: PhoneCommand.Platform, executionId: String?, foreground: Boolean,
    canDispatch: () -> Boolean = { true }): PhoneActionResult {
    val (spec, params) = InstinctCapabilities.platform(command)
    if (executionId == null) return rejected("Falta el identificador de ejecución de la acción.")
    blockers(context, spec, params)?.let { return PhoneActionResult(it.reason,
      outcome = if (it.pending) PhoneActionOutcome.NEEDS_INPUT else PhoneActionOutcome.REJECTED) }
    if (!spec.runsLocked && !foreground)
      return PhoneActionResult("Abrí Insto para continuar con esta acción.", outcome = PhoneActionOutcome.NEEDS_INPUT)
    if (!canDispatch()) return rejected("Se canceló el pedido.")
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
  fun discover(context: Context, spec: InstinctCapabilities.Spec): InstinctDeviceCapabilities.Discovery =
    blocker(context, spec, null)?.let { InstinctDeviceCapabilities.Discovery(it) }
      ?: InstinctDeviceCapabilities.Discovery(null, targets(context, spec))
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
    if (spec.id == ShortcutResolver.CAPABILITY) {
      val shortcuts = AndroidShortcutEngine.of(context)
      if (params != null && shortcuts.remoteTarget(params.getString("shortcut")) == null)
        return InstinctDeviceCapabilities.Block("El acceso directo no está autorizado o disponible en este teléfono.", false)
      return null
    }
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

  override fun targets(context: Context, spec: InstinctCapabilities.Spec): List<String> =
    if (spec.id == ShortcutResolver.CAPABILITY) AndroidShortcutEngine.of(context).remoteTargets() else emptyList()

  override fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult {
    if (spec.id == ShortcutResolver.CAPABILITY)
      return AndroidShortcutEngine.of(context).execute(params.getString("shortcut"), confirmed = true, remote = true)
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

private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
