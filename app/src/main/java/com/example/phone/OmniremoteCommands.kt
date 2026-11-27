package com.example.phone

import android.content.Context
import android.util.AtomicFile
import com.example.phone.OmniremoteAdapter.Control
import com.example.phone.OmniremoteAdapter.Setting
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object OmniremoteCommands {
  data class Order(val target: String, val control: Control, val setting: Setting?)

  private val power = Regex("^(enciende|prende|apaga) (.+)$")
  private val volume = Regex("^(?:subi|baja|aumenta|reduce|pone|ajusta|fija) (?:el )?volumen (.+?) (?:al?|en) (.+)$")
  private val mute = Regex("^(silencia|mutea|mutear|mute|desilencia|desilenciar|desmutea|desmutear) (.+)$")
  private val tvPrefix = Regex("^(?:tv|tele|televisor) ")
  private val articles = Regex("^(?:de la|de los|de las|del|de|el|la|los|las) ")

  fun parse(text: String): Order? {
    if ('?' in text || '¿' in text || RequestSafetyPolicy.rejection(text) != null) return null
    val syntax = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalizeForClock(text))
    if (RequestSafetyPolicy.hasJoinedActions(text) || ConversationSession.splitSteps(text).size != 1) return null
    power.matchEntire(syntax)?.destructured?.let { (verb, target) ->
      return Order(target, Control.POWER, Setting.Power(verb != "apaga"))
    }
    volume.matchEntire(syntax)?.destructured?.let { (target, amount) ->
      val percent = PhoneCommandParser.number(amount.removeSuffix("%").removeSuffix(" por ciento").trim())
      return Order(target, Control.VOLUME, percent?.takeIf { it in 0..100 }?.let { Setting.Volume(it) })
    }
    mute.matchEntire(syntax)?.destructured?.let { (verb, target) ->
      return Order(target, Control.MUTE, Setting.Mute(!verb.startsWith("des")))
    }
    return null
  }

  /** Resolve against the authorized catalogue on a worker thread. */
  fun resolve(context: Context, order: Order): PhoneRoute? {
    val devices = try {
      OmniremoteLocalProvider().devices(context)
    } catch (error: OmniremoteAdapter.DiscoveryFailure) {
      return PhoneRoute.Unsupported("${error.message} Revisá Ajustes → Omniremote.")
    }
    val aliases = OmniremoteAliases(context).read()
    val target = key(order.target)
    val matches = devices.filter { device ->
      (aliases[device.id].orEmpty() + listOf(device.name, device.room)).any { it.isNotBlank() && key(it) == target }
    }
    if (matches.isEmpty()) return if (order.control == Control.VOLUME) PhoneRoute.Unsupported(
      "No encontré «${order.target}» en Omniremote. Revisá el nombre o el alias en Ajustes → Omniremote.",
    ) else null
    val device = matches.singleOrNull() ?: return PhoneRoute.Unsupported(
      "«${order.target}» coincide con ${matches.joinToString { it.label }}. Usá un nombre único o elegí un alias en Ajustes → Omniremote.",
    )
    if (order.control !in device.controls) return PhoneRoute.Unsupported("${device.label} no admite ese control en Omniremote.")
    val params = JSONObject().put("device", device.id)
    when (val setting = order.setting) {
      is Setting.Power -> params.put("on", setting.on)
      is Setting.Volume -> params.put("percent", setting.percent)
      is Setting.Mute -> params.put("muted", setting.muted)
      null -> return PhoneRoute.Unsupported("Indicá un volumen entre 0 y 100 para ${device.label}.")
    }
    return PhoneRoute.Local(InstinctCapabilities.command(order.control.capability, params))
  }

  fun key(value: String): String {
    val name = PhoneCommandParser.normalize(value).replaceFirst(articles, "")
    return name.replaceFirst(tvPrefix, "").replaceFirst(articles, "")
  }
}

/** Aliases belong to this Android profile and reference Omniremote's local device IDs. */
internal class OmniremoteAliases(context: Context) {
  private val file = AtomicFile(File(context.noBackupFilesDir, "omniremote-aliases.json"))

  fun read(): Map<String, List<String>> = synchronized(lock) {
    val stored = runCatching { JSONObject(file.readFully().toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }
    stored.keys().asSequence().mapNotNull { id ->
      val aliases = stored.optJSONArray(id) ?: return@mapNotNull null
      id to (0 until aliases.length()).mapNotNull { aliases.opt(it) as? String }
    }.toMap()
  }

  fun save(id: String, input: String): List<String> = synchronized(lock) {
    val aliases = input.split(',').map(String::trim).filter(String::isNotEmpty)
    require(aliases.size <= 12 && aliases.all { it.length <= 60 && aliasPattern.matches(it) }) {
      "Usá hasta 12 alias de 60 caracteres, separados por comas."
    }
    val names = aliases.distinctBy(OmniremoteCommands::key)
    val stored = read().toMutableMap()
    val otherKeys = stored.filterKeys { it != id }.values.flatten().mapTo(mutableSetOf(), OmniremoteCommands::key)
    require(names.none { OmniremoteCommands.key(it) in otherKeys }) {
      "Uno de esos alias ya está asignado a otro dispositivo."
    }

    if (names.isEmpty()) stored.remove(id) else stored[id] = names
    val data = JSONObject().apply { stored.forEach { (device, values) -> put(device, JSONArray(values)) } }
    val stream = file.startWrite()
    try {
      stream.write(data.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
    } catch (error: Exception) {
      file.failWrite(stream)
      throw error
    }
    names
  }

  private companion object {
    val lock = Any()
    val aliasPattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} _-]*")
  }
}
