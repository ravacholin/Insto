package com.example.phone

import java.util.Calendar

internal enum class ContextSignal {
  BATTERY, CHARGING, WIFI, ONLINE, MEDIA, CALENDAR, HEADPHONES, TIME, UNLOCK
}

internal data class ContextSnapshot(
  val battery: Int? = null,
  val charging: Boolean? = null,
  val wifi: Boolean? = null,
  val online: Boolean? = null,
  val media: Boolean? = null,
  val calendar: Boolean? = null,
  val headphones: Boolean? = null,
  val minute: Int = 0,
  val unlock: Boolean = false,
)

internal data class ContextCondition(
  val signal: ContextSignal,
  val expected: Boolean = true,
  val threshold: Int = 0,
  val hours: QuietHours? = null,
) {
  fun matches(state: ContextSnapshot): Boolean? = when (signal) {
    ContextSignal.BATTERY -> state.battery?.let { if (expected) it < threshold else it > threshold }
    ContextSignal.CHARGING -> state.charging?.let { it == expected }
    ContextSignal.WIFI -> state.wifi?.let { it == expected }
    ContextSignal.ONLINE -> state.online?.let { it == expected }
    ContextSignal.MEDIA -> state.media?.let { it == expected }
    ContextSignal.CALENDAR -> state.calendar?.let { it == expected }
    ContextSignal.HEADPHONES -> state.headphones?.let { it == expected }
    ContextSignal.TIME -> hours?.contains(state.minute)
    ContextSignal.UNLOCK -> state.unlock
  }
}

internal data class ContextRuleSpec(
  val line: String,
  val conditions: List<ContextCondition>,
  val action: String,
  val days: Int,
) {
  val id: String get() = sha256(conditions.sortedBy { it.signal }.joinToString() + "\u0000" +
    PhoneCommandParser.normalize(action)).take(24)

  fun evaluate(snapshot: ContextSnapshot): Boolean? {
    val matches = conditions.map { it.matches(snapshot) }
    return when {
      false in matches -> false
      null in matches -> null
      else -> true
    }
  }

  companion object {
    const val MAX_DAYS = 30
    const val MAX_RULES = 12
    const val FORM = "cuando <condición> [y <condición> [y <condición>]] [por N días]: <acción>"
    const val EXAMPLE = "cuando batería menor de 15 por ciento y no cargando: avisarme Batería baja"
    const val INVALID = "usá hasta tres condiciones y una acción sin interfaz o «avisarme»."
    const val DEBOUNCE = 5_000L
    const val COOLDOWN = 60_000L
    const val WINDOW = 10 * 60_000L
    const val MAX_FIRINGS = 6
    private val head = Regex("^cuando(?=\\s|$)", RegexOption.IGNORE_CASE)
    private val form = Regex("^cuando\\s+(.+?):(?=\\s)\\s+(\\S.*)$", RegexOption.IGNORE_CASE)
    private val duration = Regex("""\s+por (\d+) dias?$""")
    private val battery = Regex("""bateria (menor|mayor) de (\d{1,3}) por ciento""")
    private val time = Regex("""horario (\d{1,2}:\d{2}) a (\d{1,2}:\d{2})""")
    private val phrases = mapOf(
      "cargando" to ContextCondition(ContextSignal.CHARGING),
      "no cargando" to ContextCondition(ContextSignal.CHARGING, false),
      "wifi conectado" to ContextCondition(ContextSignal.WIFI),
      "sin wifi" to ContextCondition(ContextSignal.WIFI, false),
      "con internet" to ContextCondition(ContextSignal.ONLINE),
      "sin internet" to ContextCondition(ContextSignal.ONLINE, false),
      "reproduciendo multimedia" to ContextCondition(ContextSignal.MEDIA),
      "sin reproducción multimedia" to ContextCondition(ContextSignal.MEDIA, false),
      "evento en curso" to ContextCondition(ContextSignal.CALENDAR),
      "sin evento en curso" to ContextCondition(ContextSignal.CALENDAR, false),
      "auriculares conectados" to ContextCondition(ContextSignal.HEADPHONES),
      "sin auriculares" to ContextCondition(ContextSignal.HEADPHONES, false),
      "al desbloquear" to ContextCondition(ContextSignal.UNLOCK),
    )
    val conditionForms: List<String> = listOf("batería menor de N por ciento", "batería mayor de N por ciento",
      "horario HH:MM a HH:MM") + phrases.keys
    private val normalizedPhrases = phrases.mapKeys { PhoneCommandParser.normalize(it.key) }

    fun isOrder(line: String): Boolean = head.containsMatchIn(line.trimStart())

    fun parse(line: String): ContextRuleSpec? {
      if (line.length > 420 || line.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } || !InstinctAck.safe(line)) return null
      val match = form.matchEntire(line.trim()) ?: return null
      val expression = PhoneCommandParser.normalizeForClock(match.groupValues[1])
      val suffix = duration.find(expression)
      val days = if (suffix == null) MAX_DAYS else suffix.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_DAYS } ?: return null
      val conditions = expression.substring(0, suffix?.range?.first ?: expression.length).split(" y ")
        .takeIf { it.size in 1..3 }?.map { condition(it.trim()) ?: return null } ?: return null
      if (conditions.distinctBy { it.signal }.size != conditions.size) return null
      val action = match.groupValues[2].trim()
      if ('|' in action || isOrder(action) || InstinctQuestion.isRelated(action)) return null
      return ContextRuleSpec(line.trim(), conditions, action, days)
    }

    private fun condition(text: String): ContextCondition? {
      normalizedPhrases[text]?.let { return it }
      battery.matchEntire(text)?.let {
        val percent = it.groupValues[2].toInt().takeIf { value -> value in 0..100 } ?: return null
        return ContextCondition(ContextSignal.BATTERY, it.groupValues[1] == "menor", percent)
      }
      time.matchEntire(text)?.let {
        val start = QuietHours.parse(it.groupValues[1]) ?: return null
        val end = QuietHours.parse(it.groupValues[2]) ?: return null
        if (start == end) return null
        return ContextCondition(ContextSignal.TIME, hours = QuietHours(start, end))
      }
      return null
    }

    fun nextTime(now: Long, minute: Int): Long = InstinctTriggers.next(RecurrenceDays.DAILY, minute, now,
      Long.MAX_VALUE, Calendar.getInstance().timeZone)!!
  }
}

internal data class ContextRule(
  val spec: ContextRuleSpec,
  val expiresAt: Long,
  val enabled: Boolean = true,
  val matched: Boolean = false,
  val candidateAt: Long? = null,
  val lastAt: Long? = null,
  val status: String = "esperando condiciones",
) {
  val id: String get() = spec.id
  val shortId: String get() = id.take(InstinctTriggers.SHORT_ID_LENGTH)

  data class Evaluation(val rule: ContextRule, val fire: Boolean = false)

  private fun statusAfterFire() = if (lastAt == null) "límite de disparos alcanzado" else "disparo registrado"

  fun evaluate(snapshot: ContextSnapshot, now: Long, budget: Boolean): Evaluation {
    if (!enabled || now >= expiresAt) return Evaluation(this)
    if (spec.conditions.any { it.signal == ContextSignal.UNLOCK }) return unlock(snapshot, now, budget)
    val result = spec.evaluate(snapshot)
      ?: return Evaluation(copy(candidateAt = null, status = "condición sin datos o sin permiso"))
    if (result == matched) return Evaluation(
      if (candidateAt == null) this else copy(candidateAt = null, status = if (matched) statusAfterFire() else "esperando condiciones"))
    val candidate = candidateAt?.takeIf { it <= now } ?: now
    if (now - candidate < ContextRuleSpec.DEBOUNCE)
      return Evaluation(copy(candidateAt = candidate, status = "comprobando estabilidad"))
    if (!result) return Evaluation(copy(matched = false, candidateAt = null, status = "esperando condiciones"))
    return fire(now, budget)
  }

  private fun unlock(snapshot: ContextSnapshot, now: Long, budget: Boolean): Evaluation {
    when (spec.evaluate(snapshot.copy(unlock = true))) {
      false -> return Evaluation(copy(matched = false, candidateAt = null, status = "esperando condiciones"))
      null -> return Evaluation(copy(matched = false, candidateAt = null, status = "condición sin datos o sin permiso"))
      true -> Unit
    }
    val candidate = candidateAt?.takeIf { it <= now } ?: now
    val ready = spec.conditions.size == 1 || matched || now - candidate >= ContextRuleSpec.DEBOUNCE
    if (!ready) return Evaluation(copy(candidateAt = candidate, status = "comprobando estabilidad"))
    if (!snapshot.unlock) return Evaluation(copy(matched = true, candidateAt = null, status = "esperando desbloqueo"))
    return fire(now, budget)
  }

  private fun fire(now: Long, budget: Boolean): Evaluation {
    val allowed = budget && (lastAt == null || now - lastAt >= ContextRuleSpec.COOLDOWN)
    return Evaluation(copy(matched = true, candidateAt = null, lastAt = if (allowed) now else lastAt,
      status = if (allowed) "disparo registrado" else "límite de disparos alcanzado"), allowed)
  }
}
