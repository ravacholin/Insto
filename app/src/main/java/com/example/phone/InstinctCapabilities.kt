package com.example.phone

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** The allowlist for structured orders. These IDs are independent of the local voice command grammar. */
internal object InstinctCapabilities {
  const val MAX_LABEL = 120
  const val MAX_DURATION = 86_400
  private const val READ_CALENDAR = "android.permission.READ_CALENDAR"
  private const val WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"
  private const val EXACT = "android.permission.SCHEDULE_EXACT_ALARM"
  private const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

  enum class Adapter { ANDROID_LOCAL, ANDROID_INTENT, OMNIREMOTE }

  data class Spec(
    val id: String,
    val category: InstinctCategory,
    val parameters: CapabilityCatalog.Spec,
    val permissions: List<String>,
    val runsLocked: Boolean,
    val verification: String,
    val recovery: String,
    val undo: String,
    val restriction: String,
    val decode: (JSONObject) -> PhoneCommand?,
    val adapter: Adapter,
  ) {
    fun catalog(availability: String, targets: List<String> = emptyList()): JSONObject = JSONObject()
      .put("id", id).put("categoria", category.name).put("parametros", parameters.schema(includeType = false))
      .put("permisos", JSONArray(permissions)).put("bloqueado", runsLocked).put("disponibilidad", availability)
      .apply { if (targets.isNotEmpty()) put("destinos", JSONArray(targets)) }
      .put("verificacion", verification).put("recuperacion", recovery).put("deshacer", undo).put("restriccion", restriction)
  }

  private fun text(max: Int = MAX_LABEL, vararg values: String) =
    CapabilityCatalog.Field("string", values.toList(), maxLength = max)
  private fun number(min: Long = 1, max: Long = 9_007_199_254_740_991L) = CapabilityCatalog.Field("integer", min = min, max = max)
  private val flag = CapabilityCatalog.Field("boolean")
  private val id = number()
  private val epoch = number(max = 253_402_300_799_999L)
  private val label = "label" to text()
  private val scope = "calendarId" to id
  private val title = "title" to text(200)
  private val start = "beginMillis" to epoch
  private val end = "endMillis" to epoch
  private val event = "eventId" to id
  private val etag = "revision" to text(64)
  private val pkg = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")
  private val hash = Regex("[0-9a-f]{64}")

  private fun spec(
    id: String, category: InstinctCategory, required: String, vararg fields: Pair<String, CapabilityCatalog.Field>,
    permissions: List<String> = emptyList(), locked: Boolean = true,
    verification: String = "relectura local", recovery: String = "releer si hay evidencia, suspender si es incierto",
    undo: String = "ninguno", restriction: String = "", adapter: Adapter = Adapter.ANDROID_LOCAL, decode: (JSONObject) -> PhoneCommand? = { null },
  ) = Spec(id, category, CapabilityCatalog.Spec(id, linkedMapOf(*fields), required.split(' ').filter(String::isNotEmpty).toSet()),
    permissions, locked, verification, recovery, undo, restriction, decode, adapter)

  private fun silence(id: String, action: PlatformAction, dnd: Boolean = false) =
    spec(id, InstinctCategory.SILENCIO, "", permissions = if (dnd) listOf("android.permission.ACCESS_NOTIFICATION_POLICY") else emptyList(),
      undo = "guardar y restaurar si sigue aplicado") { PhoneCommand.Platform(action) }

  private fun calendar(id: String, required: String, vararg fields: Pair<String, CapabilityCatalog.Field>, undo: String = "ninguno") =
    spec(id, InstinctCategory.CALENDARIO, required, *fields,
      permissions = if (id == "calendar_query") listOf(READ_CALENDAR) else listOf(READ_CALENDAR, WRITE_CALENDAR),
      restriction = "calendario habilitado en el teléfono; cambios solo en eventos individuales sin invitados",
      verification = "lectura de CalendarContract", undo = undo)

  private fun external(id: String, category: InstinctCategory, required: String, vararg fields: Pair<String, CapabilityCatalog.Field>,
    decode: (JSONObject) -> PhoneCommand? = { null }) =
    spec(id, category, required, *fields, locked = false, verification = "entrega del intent",
      recovery = "si se interrumpe la entrega, suspender", restriction = "último paso; requiere un toque con el teléfono desbloqueado",
      permissions = if (category == InstinctCategory.RELOJ) listOf("com.android.alarm.permission.SET_ALARM") else emptyList(),
      adapter = Adapter.ANDROID_INTENT, decode = decode)

  private fun omniremote(id: String, parameter: Pair<String, CapabilityCatalog.Field>) =
    spec(id, InstinctCategory.APPS, "device ${parameter.first}", "device" to text(120), parameter,
      verification = "estado observado por el proveedor autorizado",
      restriction = "requiere una API local compatible de Omniremote y un dispositivo registrado",
      adapter = Adapter.OMNIREMOTE)

  const val OMNIREMOTE_UNAVAILABLE = "no disponible: falta un contrato local compatible de Omniremote"

  val specs = listOf(
    spec("volume", InstinctCategory.VOLUMEN, "percent", "percent" to number(0, 100),
      undo = "guardar y restaurar si sigue aplicado") { PhoneCommand.Volume(VolumeOp.SET, it.getInt("percent")) },
    spec("torch", InstinctCategory.LINTERNA, "enabled", "enabled" to flag, permissions = listOf("android.permission.CAMERA")) {
      PhoneCommand.Torch(it.getBoolean("enabled"))
    },
    silence("silent_on", PlatformAction.SILENT_ON, true), silence("silent_off", PlatformAction.SILENT_OFF, true),
    silence("vibrate_on", PlatformAction.VIBRATE_ON),
    silence("dnd_on", PlatformAction.DND_ON, true), silence("dnd_off", PlatformAction.DND_OFF, true),
    spec("media", InstinctCategory.MUSICA, "operation",
      "operation" to text(MAX_LABEL, *MediaOp.entries.map { it.name }.toTypedArray()),
      verification = "estado de reproducción cuando la sesión lo informa",
      restriction = "requiere una sesión multimedia accesible") { PhoneCommand.Media(MediaOp.valueOf(it.getString("operation"))) },
    calendar("calendar_query", "calendarId beginMillis endMillis", scope, start, end, title),
    calendar("calendar_create", "calendarId title beginMillis endMillis", scope, title, start, end,
      "description" to text(500), undo = "eliminar el alta si su revisión coincide"),
    calendar("calendar_update", "calendarId eventId revision beginMillis endMillis", scope, event, etag, start, end, title,
      undo = "reponer los valores si su revisión coincide"),
    calendar("calendar_cancel", "calendarId eventId revision", scope, event, etag),
    spec("insto_timer", InstinctCategory.RELOJ, "seconds", "seconds" to number(1, MAX_DURATION.toLong()), label,
      permissions = listOf(EXACT, NOTIFICATIONS), undo = "cancelar por id antes de disparar",
      verification = "registro de programación y del disparo de Insto"),
    spec("insto_alarm", InstinctCategory.RELOJ, "at", "at" to epoch, label,
      permissions = listOf(EXACT, NOTIFICATIONS), undo = "cancelar por id antes de disparar",
      verification = "registro de programación y del disparo de Insto", restriction = "alarma de una sola vez"),
    spec("insto_clock_cancel", InstinctCategory.RELOJ, "clockId", "clockId" to text(64),
      verification = "registro de cancelación", restriction = "solo alarmas y temporizadores propios pendientes"),
    external("clock_timer", InstinctCategory.RELOJ, "seconds", "seconds" to number(1, MAX_DURATION.toLong()), label) {
      PhoneCommand.Timer(it.getInt("seconds"), it.optString("label"))
    },
    external("clock_alarm", InstinctCategory.RELOJ, "hour minute", "hour" to number(0, 23), "minute" to number(0, 59),
      "date" to text(10), "days" to CapabilityCatalog.Field("array"), label) {
      val days = it.optJSONArray("days")?.let { values -> (0 until values.length()).map(values::getInt) }.orEmpty()
      require(days.distinct().size == days.size && (!it.has("date") || days.isEmpty()))
      PhoneCommand.Alarm(it.getInt("hour"), it.getInt("minute"), days, it.optString("label"),
        it.optString("date").takeIf(String::isNotEmpty))
    },
    external(ShortcutResolver.CAPABILITY, InstinctCategory.APPS, "").copy(parameters = CapabilityCatalog.appShortcut),
    external("open_app", InstinctCategory.APPS, "package", "package" to text(160)),
    external("open_resource", InstinctCategory.APPS, "url", "url" to text(2000)),
    external("youtube", InstinctCategory.APPS, "query", "query" to text(200)) { PhoneCommand.YouTubeSearch(it.getString("query")) },
    external("spotify", InstinctCategory.APPS, "query", "query" to text(200)) { PhoneCommand.SpotifySearch(it.getString("query")) },
    omniremote("omniremote_power", "on" to flag),
    omniremote("omniremote_volume", "percent" to number(0, 100)),
    omniremote("omniremote_mute", "muted" to flag),
  ).associateBy(Spec::id)

  fun isOmniremote(command: PhoneCommand): Boolean = command is PhoneCommand.Platform &&
    command.action == PlatformAction.INSTINCT_CAPABILITY && specs[command.argument]?.adapter == Adapter.OMNIREMOTE

  /** Decode only after validating strict types, bounds and the capability's cross-field invariants. */
  fun command(id: String, params: JSONObject): PhoneCommand {
    val spec = requireNotNull(specs[id])
    val fields = params.keys().asSequence().toSet()
    require(fields.containsAll(spec.parameters.required) && spec.parameters.fields.keys.containsAll(fields))
    fields.forEach { field ->
      val rule = spec.parameters.fields.getValue(field)
      val value = params.get(field)
      require(when (rule.kind) {
        "boolean" -> value is Boolean
        "integer" -> (value is Int || value is Long) && (value as Number).toLong() in rule.min!!..rule.max!!
        "array" -> value is JSONArray && value.length() in 1..7 &&
          (0 until value.length()).all { index -> (value.get(index) as? Int)?.let { it in 1..7 } == true }
        "string" -> value is String && value.isNotBlank() && value.length <= rule.maxLength &&
          value.none { it.isISOControl() } && (rule.values.isEmpty() || value in rule.values)
        else -> false
      }) { "Parámetro inválido: $field" }
    }
    if (id in setOf("calendar_query", "calendar_create", "calendar_update")) {
      val span = params.getLong("endMillis") - params.getLong("beginMillis")
      require(span in 1..31L * 86_400_000)
    }
    if (id in setOf("calendar_update", "calendar_cancel")) require(hash.matches(params.getString("revision")))
    if (id == "insto_clock_cancel") require(Regex("e[0-9a-f]{20}").matches(params.getString("clockId")))
    if (id == ShortcutResolver.CAPABILITY) require(ShortcutResolver.token.matches(params.getString("shortcut")))
    if (id == "open_app") require(pkg.matches(params.getString("package")))
    if (id == "open_resource") {
      val uri = URI.create(params.getString("url"))
      require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null)
    }
    val decoded = spec.decode(params)
    if (decoded != null) return PhoneCommandCodec.decode(PhoneCommandCodec.encode(decoded))
    return PhoneCommand.Platform(PlatformAction.INSTINCT_CAPABILITY, id, content = params.toString())
  }

  fun platform(command: PhoneCommand.Platform): Pair<Spec, JSONObject> {
    require(command.action == PlatformAction.INSTINCT_CAPABILITY && command.percent == null)
    val spec = specs.getValue(requireNotNull(command.argument))
    val params = JSONObject(requireNotNull(command.content))
    require(command(spec.id, params) is PhoneCommand.Platform && spec.decode(params) == null)
    return spec to params
  }
}
