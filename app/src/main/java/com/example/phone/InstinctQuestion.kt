package com.example.phone

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

internal data class QuestionOutcome(val state: ExecutionState, val response: String, val action: ExecutionRecord? = null)

data class InstinctQuestion(val text: String, val options: List<String>, val expiresAt: Long, val actions: List<String> = emptyList()) {
  internal fun encode(): String = JSONObject().put("text", text).put("options", JSONArray(options)).apply {
    if (actions.isNotEmpty()) put("actions", JSONArray(actions))
  }.toString()

  internal fun notificationText(): String = if (actions.isEmpty()) text else
    text + "\n" + options.zip(actions).joinToString("\n") { (label, action) -> "$label → $action" }

  internal fun outcome(state: ExecutionState, now: Long, choice: Int?): QuestionOutcome? {
    if (state !in PENDING) return null
    if (now >= expiresAt) return QuestionOutcome(ExecutionState.EXPIRED, "vencida")
    if (choice == null) return null
    if (choice == DISCARD) return QuestionOutcome(ExecutionState.CANCELLED, "descartada")
    if (state != ExecutionState.ASKING) return null
    return options.getOrNull(choice)?.let { QuestionOutcome(ExecutionState.ANSWERED, it) }
  }

  companion object {
    const val FORM = "preguntar <texto> | <opción 1> | <opción 2> [| <opción 3>]"
    const val DECISION_FORM = "decidir <texto> | <opción> => <acción> | <opción> => <acción> [| <opción> => <acción>]"
    const val INVALID = "separá la pregunta y 2 o 3 opciones con «|» y revisá el plazo. En decidir, cada opción lleva «=>» y una acción permitida sin interfaz."
    const val NEEDS_LOG = "necesita bitácora"
    const val MAX_TEXT = 140
    const val MAX_OPTION = 24
    const val MAX_PENDING = 3
    const val PER_HOUR = 5
    const val DEFAULT_HOURS = 2
    const val MAX_HOURS = 24
    internal const val HOUR = 3_600_000L
    internal const val DISCARD = -1
    internal val PENDING = setOf(ExecutionState.QUESTION_CONFIRM, ExecutionState.ASKING)
    private val head = Regex("^(?:preguntar|decidir)(?=\\s|$)", RegexOption.IGNORE_CASE)
    private val suffix = Regex("""\s+(?:hasta las(?:\s+(.*))?|en\s+([+-]?\d+)\s+horas?)$""", RegexOption.IGNORE_CASE)

    internal fun isOrder(line: String): Boolean = head.containsMatchIn(line.trimStart())

    internal fun isRelated(line: String): Boolean = isOrder(line) ||
      InstinctProposalValidator.probe(line)?.let(::isOrder) == true

    fun parse(line: String, now: Calendar): InstinctQuestion? {
      val verb = head.find(line.trimStart()) ?: return null
      if (line.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
      val fields = line.trimStart().substring(verb.range.last + 1).split('|').map(String::trim).toMutableList()
      if (fields.size !in 3..4) return null
      var expiry = now.timeInMillis + DEFAULT_HOURS * HOUR
      suffix.find(fields.last())?.let { match ->
        val relative = match.groups[2]?.value
        expiry = if (relative != null) {
          val hours = relative.toIntOrNull()?.takeIf { it in 1..MAX_HOURS } ?: return null
          now.timeInMillis + hours * HOUR
        } else {
          val minute = QuietHours.parse(match.groupValues[1]) ?: return null
          (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
          }.timeInMillis
        }
        fields[fields.lastIndex] = fields.last().substring(0, match.range.first).trimEnd()
      }
      if (expiry - now.timeInMillis !in 1..MAX_HOURS * HOUR) return null
      val text = fields.first()
      val choices = fields.drop(1)
      val decision = verb.value.equals("decidir", ignoreCase = true)
      val parts = if (decision) choices.map { it.split("=>").map(String::trim) } else choices.map { listOf(it) }
      if (decision && parts.any { it.size != 2 || !validField(it[1], 200) }) return null
      val options = parts.map { it.first() }
      if (!validField(text, MAX_TEXT) || options.any { !validField(it, MAX_OPTION) }) return null
      if (decision && options.map(PhoneCommandParser::normalize).distinct().size != options.size) return null
      return InstinctQuestion(text, options, expiry, if (decision) parts.map { it[1] } else emptyList())
    }

    private fun validField(value: String, max: Int): Boolean = value.length in 1..max && InstinctAck.safe(value)

    internal fun decode(encoded: String?, deadline: Long?): InstinctQuestion? {
      if (encoded == null || deadline == null) return null
      return runCatching {
        val data = JSONObject(encoded)
        val options = data.getJSONArray("options")
        val actions = data.optJSONArray("actions")
        require(actions == null || options.length() == actions.length())
        InstinctQuestion(data.getString("text"), List(options.length()) { options.getString(it) }, deadline,
          actions?.let { List(it.length()) { index -> it.getString(index) } }.orEmpty())
      }.getOrNull()
    }

    internal fun quietEnd(quiet: QuietHours?, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
      val local = Calendar.getInstance(zone).apply { timeInMillis = now }
      val minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE)
      if (quiet == null || !quiet.contains(minute)) return null
      return local.apply {
        set(Calendar.HOUR_OF_DAY, quiet.end / 60)
        set(Calendar.MINUTE, quiet.end % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
      }.timeInMillis
    }

    internal fun limit(pending: Int, recent: Int): String? = when {
      pending >= MAX_PENDING -> "ya hay $MAX_PENDING preguntas pendientes"
      recent >= PER_HOUR -> "ya hubo $PER_HOUR preguntas en la última hora"
      else -> null
    }
  }
}
