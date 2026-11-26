package com.example.phone

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class OmniremoteAdapter(
  private val client: (Context) -> OmniremoteClient = OmniremoteClient.Companion::android,
  private val now: () -> Long = SystemClock::elapsedRealtime,
) : InstinctCapabilityAdapter {
  data class Catalog(val devices: List<OmniremoteClient.Device> = emptyList(), val error: String? = null)

  private data class Cached(val at: Long, val catalog: Catalog)
  private val refreshLock = Any()
  @Volatile private var cached: Cached? = null

  private fun fresh(): Catalog? = cached?.takeIf {
    now() - it.at < if (it.catalog.error == null) 60_000 else 5_000
  }?.catalog

  fun cachedCatalog(context: Context): Catalog? =
    if (OmniremoteClient.installed(context)) fresh() else Catalog(error = OmniremoteClient.NOT_INSTALLED)

  fun catalog(context: Context, refresh: Boolean = false): Catalog {
    // Some preview and availability surfaces run synchronously on the UI thread.
    if (Looper.myLooper() == Looper.getMainLooper())
      return cachedCatalog(context) ?: Catalog(error = "Consultá la conexión con OmniRemote desde Configuración.")
    return synchronized(refreshLock) {
      if (!refresh) fresh()?.let { return@synchronized it }
      readCatalog(context).also { cached = Cached(now(), it) }
    }
  }

  private fun readCatalog(context: Context): Catalog = try {
    Catalog(client(context).devices())
  } catch (error: OmniremoteFailure) {
    Catalog(error = error.reason)
  } catch (_: SecurityException) {
    Catalog(error = OmniremoteClient.UNAUTHORIZED)
  } catch (_: Exception) {
    Catalog(error = "No se pudo leer el catálogo local de OmniRemote.")
  }

  override fun targets(context: Context, spec: InstinctCapabilities.Spec): List<String> =
    catalog(context).devices.filter { it.advertises(OmniremoteCommands.operations.getValue(spec.id)) }.map { it.target }

  override fun blocker(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject?): InstinctDeviceCapabilities.Block? {
    val catalog = catalog(context)
    catalog.error?.let { return blocked(it) }
    if (params == null) return if (targets(context, spec).isEmpty())
      blocked("OmniRemote no anunció ningún dispositivo compatible con este control.") else null
    val id = OmniremoteCommands.targetId(params.getString("device"))
    val device = catalog.devices.firstOrNull { it.id == id }
      ?: return blocked("OmniRemote no encontró ese dispositivo. Revisá los dispositivos en Configuración.")
    val (wire, args) = OmniremoteCommands.request(spec.id, params)
    return if (device.supports(wire, args)) null
    else if (wire in device.commands && wire in setOf("SLEEP_FOR", "SLEEP_AT") && !device.exactAlarms)
      blocked("Habilitá las alarmas exactas para OmniRemote en Ajustes de Android.")
    else blocked("OmniRemote no anuncia ese control para ${device.name}.")
  }

  override fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult {
    val id = OmniremoteCommands.targetId(params.getString("device"))
      ?: return PhoneActionResult("El ID del dispositivo de OmniRemote es inválido.", outcome = PhoneActionOutcome.REJECTED)
    val (wire, args) = OmniremoteCommands.request(spec.id, params)
    val label = cached?.catalog?.devices?.firstOrNull { it.id == id }?.label
    val result = client(context).execute(executionId, id, wire, args)
    if (result.outcome == PhoneActionOutcome.REJECTED) cached = null
    return result.withTarget(label)
  }

  override fun observe(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult? {
    val (wire, _) = OmniremoteCommands.request(spec.id, params)
    val label = cached?.catalog?.devices?.firstOrNull { it.target == params.getString("device") }?.label
    return client(context).observe(executionId, wire)
      .takeIf { it.verifiedLocally || it.outcome == PhoneActionOutcome.HANDOFF }?.withTarget(label)
  }

  fun displayTarget(id: String): String? = cached?.catalog?.devices?.firstOrNull { it.target == id }?.label

  private fun PhoneActionResult.withTarget(label: String?): PhoneActionResult =
    if (label.isNullOrBlank()) this else copy(message = "$label · $message")

  private fun blocked(reason: String) = InstinctDeviceCapabilities.Block(reason, pending = false)
}

/** Local aliases point at provider IDs. The provider remains the only source of device configuration. */
internal class OmniremotePreferences(context: Context) {
  data class Settings(val default: Long? = null, val aliases: Map<Long, List<String>> = emptyMap())

  private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "omniremote-devices.json"))

  fun read(): Settings = synchronized(lock) {
    runCatching {
      val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
      val aliases = json.getJSONObject("aliases")
      Settings(json.optLong("default").takeIf { it > 0 }, aliases.keys().asSequence().associate { id ->
        val values = aliases.getJSONArray(id)
        id.toLong() to (0 until values.length()).map(values::getString)
      })
    }.getOrDefault(Settings())
  }

  fun save(id: Long, aliases: List<String>, isDefault: Boolean) = synchronized(lock) {
    require(id > 0)
    val clean = aliases.map(String::trim).filter(String::isNotEmpty).distinctBy(PhoneCommandParser::normalize)
    require(clean.size <= 10 && clean.all { it.length <= 80 && it.none(Char::isISOControl) &&
      PhoneCommandParser.normalize(it).isNotEmpty() && !OmniremoteRouting.reservedAlias(it) }) {
      "Ingresá hasta diez aliases distintos, de hasta 80 caracteres, que no sean nombres genéricos de dispositivos."
    }
    val previous = read()
    val other = previous.aliases.filterKeys { it != id }
    val existing = other.values.flatten().map(PhoneCommandParser::normalize).toSet()
    require(clean.none { PhoneCommandParser.normalize(it) in existing }) { "Ese alias ya pertenece a otro dispositivo." }
    val updated = if (clean.isEmpty()) other else other + (id to clean)
    val default = if (isDefault) id else previous.default?.takeUnless { it == id }
    val encoded = JSONObject().put("default", default ?: JSONObject.NULL)
      .put("aliases", JSONObject().apply { updated.forEach { (target, labels) -> put(target.toString(), JSONArray(labels)) } })
    val stream = file.startWrite()
    try {
      stream.write(encoded.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
    } catch (error: Exception) {
      file.failWrite(stream)
      throw error
    }
  }

  companion object { private val lock = Any() }
}
