package com.example.phone

import org.json.JSONObject

/** Insto capability IDs and their exact v1 parameters. Device and app IDs come from the provider. */
internal object OmniremoteCommands {
  data class Operation(val id: String, val wire: String, val examples: List<String>,
    val fields: Map<String, CapabilityCatalog.Field> = emptyMap()) {
    val capability get() = "omniremote_$id"
  }

  private fun text(length: Int, values: List<String> = emptyList()) =
    CapabilityCatalog.Field("string", values, maxLength = length)
  private fun integer(min: Long, max: Long) = CapabilityCatalog.Field("integer", min = min, max = max)
  private val boolean = CapabilityCatalog.Field("boolean")
  private val keys = listOf("VOL_UP", "VOL_DOWN", "MUTE", "CH_UP", "CH_DOWN", "NUM_PRE_CH",
    "UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "HOME", "MENU", "INFO", "SOURCE", "SETTINGS", "EXIT",
    "PLAY", "PAUSE", "PLAY_PAUSE", "STOP", "REWIND", "FAST_FORWARD", "COLOR_RED", "COLOR_GREEN",
    "COLOR_YELLOW", "COLOR_BLUE") + (0..9).map { "NUM_$it" }
  private fun op(id: String, wire: String, a: String, b: String, vararg fields: Pair<String, CapabilityCatalog.Field>) =
    Operation(id, wire, listOf(a, b), linkedMapOf(*fields))

  val operations = listOf(
    op("state", "GET_STATE", "mostrá el estado de la tele", "consultá el estado del televisor"),
    op("power", "POWER_ON", "prendé la tele", "apagá la tele", "on" to boolean),
    op("key", "KEY", "bajá el volumen de la tele", "poné pausa en el televisor", "key" to text(16, keys)),
    op("text", "TEXT", "escribí «hola» en la tele", "escribí «buscar» en el televisor", "text" to text(1024)),
    op("volume", "SET_VOLUME", "poné el volumen de la tele al 40%", "ajustá el volumen del televisor a 30%", "percent" to integer(0, 100)),
    op("mute", "SET_MUTE", "silenciá la tele", "activá el sonido del televisor", "muted" to boolean),
    op("open_app", "OPEN_APP", "abrí Netflix en la tele", "abrí YouTube en el televisor", "appId" to integer(1, Long.MAX_VALUE)),
    op("open_brightness", "OPEN_BRIGHTNESS", "abrí el brillo de la tele", "mostrá el brillo del televisor"),
    op("open_brightness_adb", "OPEN_BRIGHTNESS_ADB", "abrí TVDimmer por ADB en la tele", "abrí el brillo por ADB en el televisor"),
    op("open_bluetooth_audio", "OPEN_BLUETOOTH_AUDIO", "abrí el audio bluetooth en la tele", "mostrá los altavoces bluetooth del televisor"),
    op("sleep_for", "SLEEP_FOR", "apagá la tele en 30 minutos", "programá el apagado del televisor en 45 minutos", "minutes" to integer(1, 1440)),
    op("sleep_at", "SLEEP_AT", "apagá la tele a las 23:30", "programá el apagado del televisor a las 22:15", "hour" to integer(0, 23), "minute" to integer(0, 59)),
    op("sleep_extend", "SLEEP_EXTEND", "extendé el apagado de la tele", "extendé el temporizador del televisor"),
    op("sleep_cancel", "SLEEP_CANCEL", "cancelá el apagado de la tele", "cancelá el temporizador del televisor"),
    op("mac_open_link", "MAC_OPEN_LINK", "abrí https://example.org en la Mac", "abrí https://example.com en la Mac", "url" to text(4096)),
    op("mac_media_list", "MAC_MEDIA_LIST", "mostrá las fuentes de medios de la Mac", "listá los reproductores de la Mac"),
    op("mac_media_state", "MAC_MEDIA_STATE", "consultá el estado de una fuente de la Mac", "mostrá la reproducción de una fuente de la Mac", "sourceId" to text(128)),
    op("mac_media_key", "MAC_MEDIA_KEY", "pausá una fuente de la Mac", "reproducí una fuente de la Mac",
      "sourceId" to text(128), "key" to text(16, listOf("PLAY", "PAUSE", "PLAY_PAUSE", "STOP", "REWIND", "FAST_FORWARD"))),
    op("mac_media_reveal", "MAC_MEDIA_REVEAL", "mostrá una fuente de la Mac", "traé al frente una fuente de la Mac", "sourceId" to text(128)),
  ).associateBy(Operation::capability)

  fun target(id: Long) = "omni:$id"
  fun targetId(value: String): Long? = value.removePrefix("omni:").toLongOrNull()
    ?.takeIf { it > 0 && target(it) == value }

  fun isRemote(command: PhoneCommand): Boolean = command is PhoneCommand.Platform &&
    command.action == PlatformAction.INSTINCT_CAPABILITY && command.argument in operations

  fun request(capability: String, params: JSONObject): Pair<String, JSONObject> {
    val args = JSONObject()
    val operation = operations.getValue(capability)
    val wire = when (operation.id) {
      "power" -> if (params.getBoolean("on")) "POWER_ON" else "POWER_OFF"
      "volume" -> operation.wire.also { args.put("level", params.getInt("percent")) }
      else -> operation.wire.also { operation.fields.keys.forEach { args.put(it, params.get(it)) } }
    }
    return wire to args
  }
}

/** Ready and waiting execution rows may already have a resolved, installation-local target. */
internal fun ExecutionRecord.remoteCommand(): PhoneCommand? =
  command?.let { runCatching { PhoneCommandCodec.decode(JSONObject(it)) }.getOrNull() }
    ?.takeIf(OmniremoteCommands::isRemote)
