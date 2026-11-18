package com.example.phone

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** Immutable, validated partial command. Missing data never becomes an invented executable value. */
class CommandDraft internal constructor(
  val id: String,
  fields: JSONObject,
  private val localDeadlineMillis: Long?,
) {
  constructor(id: String, fields: JSONObject) : this(id, fields, null)

  private val payload: String
  val type: String
  init {
    require(Regex("s(?:[1-9]|10)").matches(id))
    require(fields.get("type") is String)
    type = fields.getString("type")
    val spec = requireNotNull(CapabilityCatalog.specs[type])
    fields.keys().forEach { name ->
      if (name != "type") {
        val field = requireNotNull(spec.fields[name]) { "Campo no admitido" }
        val value = fields.get(name)
        when (field.kind) {
          "string" -> require(value is String && value.length <= 2000 && (value.isNotBlank() || name == "label") && (field.values.isEmpty() || value in field.values))
          "boolean" -> require(value is Boolean)
          "integer" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() &&
            value.toLong() >= (field.min ?: Long.MIN_VALUE) && value.toLong() <= (field.max ?: Long.MAX_VALUE))
          "array" -> {
            require(value is JSONArray && value.length() <= 7)
            for (index in 0 until value.length()) {
              val item = value.get(index)
              require(item is Number && item.toDouble() == item.toInt().toDouble() && item.toInt() in 1..7)
            }
          }
        }
      }
    }
    if (type == "alarm" && fields.has("hour") && fields.optString("period") in listOf("AM", "PM")) require(fields.getInt("hour") in 1..12)
    payload = fields.toString()
  }
  fun json(): JSONObject = JSONObject(payload)
  fun jsonWithId(): JSONObject = JSONObject().put("id", id).put("command", json())
  fun missing(): Set<String> {
    val o = json()
    val required = CapabilityCatalog.specs.getValue(type).required.toMutableSet()
    if (type == "volume" && o.optString("operation") == "SET" || type == "temporary_mode" && o.optString("kind") == "MEDIA_VOLUME") required += "percent"
    if (type == "alarm" && o.has("hour") && o.getInt("hour") in 1..12) required += "period"
    if (type in CalendarSelection.types) {
      if (type != "calendar_query" && !o.has("title") && (!o.has("from") || !o.has("at") || o.has("until"))) required += "title"
      if (type == "calendar_query" && listOf("title", "from", "next").none(o::has)) required += "from"
      if (type == "calendar_update" && listOf("newDate", "newTime", "durationSeconds").none(o::has)) required += "newTime"
    }
    return required.filterNot(o::has).toSet()
  }
  fun complete(now: Calendar): PhoneCommand? {
    if (missing().isNotEmpty()) return null
    val o = json()
    if (type in CalendarSelection.types) {
      val action = PlatformAction.valueOf(type.uppercase(java.util.Locale.ROOT))
      o.remove("type")
      val title = o.remove("title") as String?
      val selection = CalendarSelection.fromFields(o, title)
      return PhoneCommandCodec.decode(PhoneCommandCodec.encode(selection.command(action)))
    }
    if (type == "calendar" && o.has("date")) {
      val title = o.getString("title")
      require(title.none { it in "\"“”" }) { "Título con comillas no representable" }
      val route = PhoneCommandParser { now.clone() as Calendar }.parse(
        "agendá \"$title\" ${o.getString("date")} a las ${o.getString("time")} durante ${o.optInt("durationSeconds", 3600)} segundos")
      return when (route) {
        is PhoneRoute.Local -> (route.command as PhoneCommand.CalendarEvent).copy(title = title, description = o.optString("description").takeIf(String::isNotEmpty))
        is PhoneRoute.Clarify -> null
        else -> throw IllegalArgumentException("Fecha u hora inválida")
      }
    }
    if (type == "alarm") {
      val hour = o.getInt("hour")
      when (o.optString("period")) { "AM" -> o.put("hour", hour % 12); "PM" -> o.put("hour", hour % 12 + 12) }
      o.remove("period")
    }
    localDeadlineMillis?.let {
      require(it > now.timeInMillis)
      o.put("deadlineMillis", it)
    }
    return PhoneCommandCodec.decode(o).also {
      require(it !is PhoneCommand.CalendarEvent || it.beginMillis > now.timeInMillis)
    }
  }
  fun patch(fields: JSONObject): CommandDraft {
    val o = json()
    fields.keys().forEach { name -> require(name != "type" || fields.getString(name) == type); o.put(name, fields.get(name)) }
    val deadline = localDeadlineMillis.takeUnless { fields.has("seconds") && fields.optInt("seconds") != json().optInt("seconds") }
    return CommandDraft(id, o, deadline)
  }
}

data class DraftPlan(
  val steps: List<CommandDraft>,
  val revision: Long = 0,
  val pendingEdit: String? = null,
  val selectedStepId: String? = null,
) {
  init {
    require(steps.size in 1..10 && steps.map { it.id }.distinct().size == steps.size)
    require(selectedStepId == null || steps.any { it.id == selectedStepId })
  }
  fun prompt(): String {
    if (pendingEdit != null) return "¿Qué paso querés corregir? Indicá su número."
    val index = steps.indexOfFirst { it.missing().isNotEmpty() }.coerceAtLeast(0)
    val field = steps[index].missing().firstOrNull()
    val question = when (field) {
      "seconds" -> "¿Durante cuánto tiempo?"
      "hour", "time" -> "¿A qué hora? Indicá mañana/tarde o una hora de 24 horas."
      "period" -> "¿De la mañana o de la tarde?"
      "minute" -> "¿A qué minuto?"
      "date", "from" -> "¿Qué día o intervalo?"
      "newTime" -> "¿Cuál debe ser la nueva fecha, hora o duración?"
      "title" -> "¿Qué título le pongo?"
      "percent" -> "¿Qué porcentaje, entre cero y cien?"
      "destination" -> "¿A dónde querés ir?"
      "target" -> "¿A quién querés llamar?"
      "query" -> "¿Qué querés buscar?"
      "name" -> "¿Qué aplicación querés abrir?"
      "kind" -> "¿Qué modo o ajuste querés usar?"
      else -> "Completá o corregí el dato pendiente."
    }
    return if (steps.size == 1) question else "Paso ${index + 1}: $question"
  }
  fun result(now: Calendar, fallback: Boolean = false): InterpretationResult {
    val complete = steps.map { it.complete(now) }
    return if (complete.all { it != null }) InterpretationResult.Plan(complete.filterNotNull(), fallback, this)
      else InterpretationResult.Incomplete(this, prompt(), fallback)
  }
  fun reply(text: String, now: Calendar): InterpretationResult? {
    val s = PhoneCommandParser.normalize(text)
    if (s in listOf("si", "dale", "ok", "confirmo", "ejecutar")) return InterpretationResult.Incomplete(this)
    RequestSafetyPolicy.rejection(text)?.let { return InterpretationResult.Rejected(it) }
    val selected = PhoneCommandParser.choiceIndex(s)
    if (pendingEdit != null) {
      if (selectedStepId != null) return null
      if (selected == null || selected !in steps.indices) return InterpretationResult.Incomplete(this)
      return update(selected, pendingEdit, now) ?: InterpretationResult.Incomplete(copy(selectedStepId = steps[selected].id))
    }
    val incomplete = steps.indices.filter { steps[it].complete(now) == null }
    if (steps.size > 1 && incomplete.size != 1) {
      return InterpretationResult.Incomplete(copy(pendingEdit = text, revision = revision + 1))
    }
    return update(incomplete.singleOrNull() ?: 0, text, now)
  }
  private fun update(index: Int, text: String, now: Calendar): InterpretationResult? {
    val step = steps[index]
    val s = PhoneCommandParser.normalize(text).removePrefix("mejor ")
    val o = step.json()
    val patch = JSONObject()
    when (step.type) {
      "timer", "temporary_mode" -> {
        val seconds = CommandDuration.seconds(s.removePrefix("durante ")) ?: return null
        patch.put("seconds", seconds)
      }
      "volume" -> {
        val number = PhoneCommandParser.number(s.removePrefix("al ").removeSuffix(" por ciento").removeSuffix("%")) ?: return null
        patch.put("operation", "SET").put("percent", number)
      }
      "navigate" -> {
        val mode = mapOf("a pie" to "WALKING", "en auto" to "DRIVING", "en bici" to "BICYCLING", "en transporte publico" to "TRANSIT")[s]
        if (mode != null) patch.put("mode", mode) else if (!o.has("destination")) patch.put("destination", text.trim()) else return null
      }
      "alarm" -> {
        val period = mapOf("de la manana" to "AM", "de la tarde" to "PM", "am" to "AM", "pm" to "PM")[s]
        if (period != null) patch.put("period", period) else {
          val route = PhoneCommandParser { now }.parse("alarma $s")
          val command = (route as? PhoneRoute.Local)?.command as? PhoneCommand.Alarm ?: return null
          patch.put("hour", command.hour).put("minute", command.minute).put("period", "24H")
        }
      }
      "calendar" -> when {
        !o.has("title") -> patch.put("title", text.trim())
        Regex("^(?:hoy|manana|pasado manana|(?:el )?[0-9]{1,2}/[0-9]{1,2}(?:/[0-9]{4})?)$").matches(s) -> patch.put("date", s.removePrefix("el "))
        s in listOf("de la manana", "de la tarde", "de la noche") && o.has("time") -> {
          val oldTime = PhoneCommandParser.normalize(o.getString("time")).replace(Regex(" (?:de la (?:manana|tarde|noche)|am|pm)$"), "")
          patch.put("time", "$oldTime $s")
        }
        s.startsWith("durante ") || s.startsWith("que dure ") -> patch.put("durationSeconds", CommandDuration.seconds(s.removePrefix("durante ").removePrefix("que dure ")) ?: return null)
        Regex("^(?:a las? )?(?:[0-9]{1,2}(?::[0-9]{2})?|una?|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)(?: y (?:media|cuarto|[a-z]+(?: y [a-z]+)?))?(?: de la (?:manana|tarde|noche)| am| pm| horas)?$").matches(s) ->
          patch.put("time", s.removePrefix("a las ").removePrefix("a la "))
        else -> return null
      }
      "dial", "maps", "music", "youtube", "open_app" -> {
        val field = mapOf("dial" to "target", "open_app" to "name")[step.type] ?: "query"
        if (o.has(field)) return null
        if (PhoneCommandParser().parse(text) != PhoneRoute.Instinct) return null
        patch.put(field, text.trim())
      }
      else -> return null
    }
    val revised = steps.toMutableList().apply { set(index, step.patch(patch)) }
    return copy(steps = revised, revision = revision + 1, pendingEdit = null, selectedStepId = null).result(now)
  }
  /** Provider corrections preserve ids, order and all previously known omitted fields. */
  fun merge(proposed: DraftPlan): DraftPlan {
    require(steps.map { it.id } == proposed.steps.map { it.id })
    val merged = steps.zip(proposed.steps).map { (old, next) ->
      val patched = old.patch(next.json())
      if (selectedStepId != null && old.id != selectedStepId) {
        val previous = old.json(); val updated = patched.json()
        require(previous.keys().asSequence().toSet() == updated.keys().asSequence().toSet())
        previous.keys().forEach { key -> require(previous.get(key).toString() == updated.get(key).toString()) { "Se modificó otro paso" } }
      }
      patched
    }
    return copy(steps = merged, revision = revision + 1, pendingEdit = null, selectedStepId = null)
  }
  companion object {
    fun fromCommands(commands: List<PhoneCommand>, now: Calendar = Calendar.getInstance()): DraftPlan = DraftPlan(commands.mapIndexed { index, command ->
      if (command is PhoneCommand.Platform && command.action in CalendarSelection.actions) {
        val selection = CalendarSelection.decode(command)
        require(selection.legacyBegin == null)
        val fields = JSONObject(selection.command(command.action).content!!).apply {
          remove("version")
          put("type", command.action.name.lowercase(java.util.Locale.ROOT))
          selection.title?.let { put("title", it) }
        }
        return@mapIndexed CommandDraft("s${index + 1}", fields)
      }
      val fields = PhoneCommandCodec.encode(command)
      if (command is PhoneCommand.Alarm) fields.put("period", "24H")
      if (command is PhoneCommand.TemporaryMode) {
        require(!command.approximate)
        fields.remove("approximate")
        fields.remove("deadlineMillis")
      }
      if (command is PhoneCommand.CalendarEvent) {
        val date = java.util.Date(command.beginMillis)
        fields.remove("beginMillis"); fields.remove("endMillis")
        fields.put("date", java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ROOT).apply { timeZone = now.timeZone }.format(date))
        fields.put("time", java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).apply { timeZone = now.timeZone }.format(date))
        fields.put("durationSeconds", (command.endMillis - command.beginMillis) / 1000)
      }
      CommandDraft("s${index + 1}", fields, (command as? PhoneCommand.TemporaryMode)?.deadlineMillis)
    })
  }
}
