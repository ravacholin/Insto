package com.example.phone

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

internal data class PlanInvocation(val capability: String, val parameters: String, val fromBlock: Int? = null) {
  val spec get() = InstinctCapabilities.specs.getValue(capability)

  fun review(): String {
    val params = JSONObject(parameters)
    fun instant(key: String) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
      .format(java.util.Date(params.getLong(key)))
    val target = when (capability) {
      ShortcutResolver.CAPABILITY -> "acceso directo autorizado"
      "open_app" -> params.getString("package")
      "open_resource" -> java.net.URI.create(params.getString("url")).host
      "youtube", "spotify" -> InstinctAck.cut(InstinctAck.flat(params.getString("query")), 50)
      "calendar_query", "calendar_create", "calendar_update", "calendar_cancel" -> listOfNotNull(
        "calendario ${params.getLong("calendarId")}",
        if (fromBlock != null) "evento de la consulta" else params.optLong("eventId").takeIf { it > 0 }?.let { "evento $it" },
        params.optString("title").takeIf(String::isNotEmpty)?.let { InstinctAck.cut(InstinctAck.flat(it), 35) },
        if (params.has("beginMillis")) instant("beginMillis") else null,
      ).joinToString(" · ")
      "insto_timer", "clock_timer" -> "${params.getInt("seconds")} s"
      "insto_alarm" -> instant("at")
      "clock_alarm" -> String.format(java.util.Locale.ROOT, "%02d:%02d", params.getInt("hour"), params.getInt("minute"))
      "insto_clock_cancel" -> params.getString("clockId")
      "volume" -> "${params.getInt("percent")} %"
      "omniremote_power" -> "${params.getString("device")} · ${if (params.getBoolean("on")) "encender" else "apagar"}"
      "omniremote_volume" -> "${params.getString("device")} · ${params.getInt("percent")} %"
      "omniremote_mute" -> "${params.getString("device")} · ${if (params.getBoolean("muted")) "silenciar" else "activar sonido"}"
      else -> ""
    }
    return listOf(capability, target).filter(String::isNotBlank).joinToString(" · ")
  }

  fun command(reference: JSONObject? = null, preview: Boolean = false): PhoneCommand {
    val params = JSONObject(parameters)
    if (fromBlock != null) {
      require(preview || reference != null && reference.getLong("calendarId") == params.getLong("calendarId"))
      params.put("eventId", if (preview) 1L else checkNotNull(reference).getLong("eventId"))
      params.put("revision", if (preview) "0".repeat(64) else checkNotNull(reference).getString("revision"))
    }
    return InstinctCapabilities.command(capability, params)
  }
}

/** Typed v1 plans. The JSON compiler produces the same instructions the legacy grammar uses. */
internal object InstinctStructuredPlan {
  const val PREFIX = "plan-json"
  const val MAX_LINE_BYTES = 8192
  const val MAX_ORDER_BYTES = 3 * MAX_LINE_BYTES
  const val MAX_STEPS = 24
  const val MAX_ACTIONS = 32
  const val MAX_DECISIONS = 2
  const val GUIDE = "Para resolver un objetivo, leé el catálogo vivo y el contexto con fecha. Elegí las capacidades autorizadas, " +
    "preguntá por datos imprescindibles y enviá una orden firmada #insto plan-json seguida de JSON compacto. " +
    "Consultá el progreso en la bitácora. No repitas un paso incierto."
  val MEETING_EXAMPLE = PREFIX + " " +
    """{"version":1,"steps":[{"id":"reminder","kind":"action","capability":"insto_timer","params":{"seconds":1500,"label":"Reunión"}},{"id":"start","kind":"wait","trigger":"EVENT_START"},{"id":"save","kind":"save","settings":["DND"]},{"id":"focus","kind":"action","capability":"dnd_on","params":{}},{"id":"end","kind":"wait","trigger":"EVENT_END"},{"id":"restore","kind":"restore","settings":["DND"]}]}"""

  val contract: Map<String, Any> = mapOf(
    "version" to 1, "forma" to "#insto $PREFIX <JSON compacto>", "transporte" to "buzon_firmado",
    "raiz" to "{version:1, expiresAt?: epochMs, steps: [...]}", "paso" to "{id, kind, requires?: [id anterior], ...}",
    "action" to "{capability, params, fallback?: {capability,params}, eventFrom?: id de calendar_query}",
    "routine" to "{name, revision}", "wait" to "{seconds} | {at: epochMs} | {trigger}",
    "disparadores" to PlanTrigger.entries.map { it.name },
    "branch" to "{conditions: texto de condiciones de «cuando», then: [action], else?: [action]}",
    "decision" to "{question, options: [{label, actions:[action]}]}",
    "save_restore" to "{settings: [VOLUME | RINGER | DND]}",
    "limites" to mapOf("pasos" to MAX_STEPS, "acciones" to MAX_ACTIONS, "decisiones" to MAX_DECISIONS, "bytes_linea" to MAX_LINE_BYTES),
    "objetivo_ejemplo" to "Prepará el teléfono para la reunión que empieza en media hora y dejalo como estaba al terminar.",
    "ejemplo" to "#insto $MEETING_EXAMPLE",
  )

  fun isWireLine(line: String): Boolean = line.trim().let {
    it.startsWith("#insto ") && isOrder(it.removePrefix("#insto ").trimStart())
  }

  fun isOrder(line: String): Boolean = line == PREFIX || line.startsWith("$PREFIX ")

  fun check(line: String, now: Calendar): PlanCheck = when (val parsed = parse(line, now)) {
    is InstinctPlanSpec.Parse.Valid -> PlanCheck(PREFIX, parsed.spec)
    is InstinctPlanSpec.Parse.Invalid -> PlanCheck(PREFIX, null, parsed.why)
  }

  fun parse(line: String, now: Calendar): InstinctPlanSpec.Parse = runCatching {
    require(isOrder(line) && line.toByteArray(Charsets.UTF_8).size + 7 <= MAX_LINE_BYTES && line.none { it == '\n' || it == '\r' })
    val root = strictObject(line.removePrefix(PREFIX).trimStart()).keys("version steps", "expiresAt")
    require(root.integral("version") == 1L)
    val createdAt = now.timeInMillis
    val expiresAt = if (root.has("expiresAt")) root.integral("expiresAt") else createdAt + InstinctPlanSpec.DEFAULT_EXPIRY_MILLIS
    require(expiresAt in (createdAt + 1)..(createdAt + InstinctPlanSpec.MAX_EXPIRY_MILLIS))
    val steps = root.getJSONArray("steps").objects(1..MAX_STEPS)
    val ids = LinkedHashMap<String, Int>()
    val dependencies = mutableMapOf<Int, Set<Int>>()
    val parsed = ArrayList<PlanStep>()
    val saved = mutableSetOf<PlanSetting>()
    steps.forEachIndexed { index, raw ->
      val kind = raw.string("kind", 12)
      val id = raw.string("id", 32)
      require(id.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) && id !in ids)
      val requires = if (raw.has("requires")) raw.getJSONArray("requires").strings(1..MAX_STEPS, 32).map { ids.getValue(it) } else emptyList()
      require(requires.distinct().size == requires.size)
      if (requires.isNotEmpty()) dependencies[index] = requires.toSet()
      val terminal = index == steps.lastIndex
      val step = when (kind) {
        "action" -> {
          val action = action(raw, ids, parsed, terminal, topLevel = true)
          action.invocation?.fromBlock?.let { dependencies[index] = dependencies[index].orEmpty() + it }
          PlanStep.Act(action, action.review())
        }
        "routine" -> {
          raw.stepKeys("name revision")
          val name = raw.string("name", 100)
          val revision = raw.string("revision", 64)
          require(revision.matches(Regex("[0-9a-f]{64}")))
          PlanStep.Routine(name, revision, "rutina $name")
        }
        "wait" -> wait(raw, createdAt, expiresAt)
        "branch" -> {
          raw.stepKeys("conditions then", "else")
          val conditions = requireNotNull(ContextRuleSpec.conditions(PhoneCommandParser.normalizeForClock(raw.string("conditions", 180))))
          require(conditions.none { it.signal == ContextSignal.UNLOCK })
          PlanStep.Branch(conditions, actions(raw.getJSONArray("then")), raw.optJSONArray("else")?.let(::actions).orEmpty(), "condición $id").also {
            require(!raw.has("else") || raw.optJSONArray("else") != null)
          }
        }
        "decision" -> {
          raw.stepKeys("question options")
          val options = raw.getJSONArray("options").objects(2..3).map {
            it.keys("label actions")
            it.string("label", InstinctQuestion.MAX_OPTION) to actions(it.getJSONArray("actions"))
          }
          require(options.map { PhoneCommandParser.normalize(it.first) }.distinct().size == options.size)
          PlanStep.Decide(raw.string("question", InstinctQuestion.MAX_TEXT), options.map { it.first }, options.map { it.second }, "decisión $id")
        }
        "save", "restore" -> {
          raw.stepKeys("settings")
          require(requires.isEmpty())
          val settings = raw.getJSONArray("settings").strings(1..PlanSetting.entries.size, 20).map(PlanSetting::valueOf)
          require(settings.distinct().size == settings.size)
          if (kind == "save") {
            require(settings.none { it in saved })
            saved.addAll(settings)
            PlanStep.Save(settings.toSet(), "guardar ajustes")
          } else {
            require(saved.containsAll(settings))
            saved.removeAll(settings.toSet())
            PlanStep.Restore(settings.toSet(), "restaurar ajustes")
          }
        }
        else -> error("tipo de paso desconocido")
      }
      parsed += step
      ids[id] = index
    }
    val spec = InstinctPlanSpec(line, parsed, expiresAt, dependencies)
    require(saved.isEmpty() && (spec.actionCount > 0 || parsed.any { it is PlanStep.Routine }) && spec.actionCount <= MAX_ACTIONS)
    require(parsed.count { it is PlanStep.Decide } <= MAX_DECISIONS)
    val waits = parsed.filterIsInstance<PlanStep.Wait>().sumOf { it.millis }
    require(waits <= InstinctPlanSpec.MAX_WAIT_MILLIS && waits < expiresAt - createdAt)
    InstinctPlanSpec.Parse.Valid(spec)
  }.getOrElse { InstinctPlanSpec.Parse.Invalid("JSON de plan v1 inválido o fuera de los límites del catálogo.") }

  private fun wait(raw: JSONObject, now: Long, expiresAt: Long): PlanStep {
    raw.stepKeys("", "seconds at trigger")
    require(listOf("seconds", "at", "trigger").count(raw::has) == 1)
    return when {
      raw.has("seconds") -> {
        val seconds = raw.integral("seconds")
        require(seconds in 1..InstinctPlanSpec.MAX_WAIT_MILLIS / 1000)
        PlanStep.Wait(seconds * 1000, "esperar $seconds segundos")
      }
      raw.has("at") -> {
        val at = raw.integral("at")
        require(at in now until expiresAt)
        PlanStep.WaitAt(at, "esperar hasta el instante indicado")
      }
      else -> PlanStep.WaitFor(PlanTrigger.valueOf(raw.string("trigger", 24)), "esperar ${raw.getString("trigger")}")
    }
  }

  private fun actions(array: JSONArray) = array.objects(1..MAX_ACTIONS).map { action(it) }

  private fun action(raw: JSONObject, ids: Map<String, Int> = emptyMap(), steps: List<PlanStep> = emptyList(),
    terminal: Boolean = false, topLevel: Boolean = false): PlanAction {
    if (topLevel) raw.stepKeys("capability params", "fallback eventFrom") else raw.keys("capability params", "fallback")
    val invocation = invocation(raw, ids, steps)
    val fallback = if (raw.has("fallback")) raw.getJSONObject("fallback").also {
      it.keys("capability params", if (topLevel) "eventFrom" else "")
    }.let { invocation(it, ids, steps) } else null
    if (!invocation.spec.runsLocked) require(terminal && fallback == null)
    require(fallback == null || fallback.spec.runsLocked)
    return PlanAction(invocation.capability, fallback?.capability, invocation, fallback)
  }

  private fun invocation(raw: JSONObject, ids: Map<String, Int>, steps: List<PlanStep>): PlanInvocation {
    val capability = raw.string("capability", 32)
    val params = raw.getJSONObject("params")
    val from = if (raw.has("eventFrom")) {
      require(capability in setOf("calendar_update", "calendar_cancel") && !params.has("eventId") && !params.has("revision"))
      val block = ids.getValue(raw.string("eventFrom", 32))
      val source = (steps[block] as? PlanStep.Act)?.action ?: error("referencia a una consulta inválida")
      require(source.invocation?.capability == "calendar_query" && source.fallback == null)
      require(JSONObject(source.invocation.parameters).getLong("calendarId") == params.getLong("calendarId"))
      block
    } else null
    return PlanInvocation(capability, params.toString(), from).also { it.command(preview = true) }
  }

  private fun JSONObject.keys(required: String, optional: String = ""): JSONObject {
    val expected = required.split(' ').filter(String::isNotEmpty)
    val possible = expected + optional.split(' ').filter(String::isNotEmpty)
    require(expected.all(::has) && keys().asSequence().all { it in possible })
    return this
  }

  private fun JSONObject.stepKeys(required: String, optional: String = "") = keys("id kind $required", "requires $optional")
  private fun JSONObject.integral(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
  private fun JSONObject.string(key: String, max: Int): String = text(get(key), max)
  private fun JSONArray.objects(size: IntRange): List<JSONObject> { require(length() in size); return (0 until length()).map(::getJSONObject) }
  private fun JSONArray.strings(size: IntRange, max: Int): List<String> {
    require(length() in size)
    return (0 until length()).map { text(get(it), max) }
  }

  private fun text(value: Any, max: Int): String {
    require(value is String && value.isNotBlank() && value.length <= max && value.none(Char::isISOControl) && InstinctAck.safe(value))
    return value
  }

  /** Gson's strict reader enforces JSON grammar. Duplicate keys, depth and integral number types are checked here. */
  private fun strictObject(text: String): JSONObject = JsonReader(StringReader(text)).use { reader ->
    reader.strictness = Strictness.STRICT
    val root = json(reader, 0)
    require(root is JSONObject && reader.peek() == JsonToken.END_DOCUMENT)
    root
  }

  private fun json(reader: JsonReader, depth: Int): Any {
    require(depth <= 12)
    return when (reader.peek()) {
      JsonToken.BEGIN_OBJECT -> JSONObject().apply {
        reader.beginObject()
        while (reader.hasNext()) {
          val name = reader.nextName()
          require(!has(name))
          put(name, json(reader, depth + 1))
        }
        reader.endObject()
      }
      JsonToken.BEGIN_ARRAY -> JSONArray().apply {
        reader.beginArray()
        while (reader.hasNext()) put(json(reader, depth + 1))
        reader.endArray()
      }
      JsonToken.STRING -> reader.nextString()
      JsonToken.NUMBER -> reader.nextString().let { it.toIntOrNull() ?: it.toLong() }
      JsonToken.BOOLEAN -> reader.nextBoolean()
      else -> error("valor JSON no admitido")
    }
  }
}
