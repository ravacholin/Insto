package com.example.phone

import java.util.Calendar

/**
 * Generalizes learned requests that differ only in one typed parameter: «tirame una alarma para las {hora}».
 *
 * Patterns are anchored literal text around a single slot. The slot is never trusted on its own: its text must have
 * the exact shape of its type, and the value is extracted by [PhoneCommandParser] from a canonical request that the
 * parser already understands. No user or model text is compiled as a regular expression.
 */
object PhrasePatterns {
  enum class Slot(val placeholder: String) { TIME("{hora}"), DURATION("{duracion}"), PERCENT("{porcentaje}") }

  /** Only deterministic, non-sensitive actions. Messages, calls, files and calendars are never generalized. */
  enum class Action(val id: String, val slot: Slot, val label: String) {
    ALARM("alarm", Slot.TIME, "Poner una alarma a la hora indicada"),
    TIMER("timer", Slot.DURATION, "Poner un temporizador con la duración indicada"),
    VOLUME("volume", Slot.PERCENT, "Fijar el volumen multimedia en el porcentaje indicado");

    /** The request the existing parser validates. The slot text is inserted after the shape check. */
    internal fun canonical(slot: String): String = when (this) {
      ALARM -> "poner una alarma a las $slot"
      TIMER -> "poner un temporizador de $slot"
      VOLUME -> "poner el volumen al $slot"
    }

    companion object {
      fun of(id: String): Action? = entries.firstOrNull { it.id == id }
    }
  }

  /** Distinct parameter values a structure needs before it is used. Conservative on purpose. */
  const val MIN_EXAMPLES = 3
  private const val MAX_SLOT_TOKENS = 6
  private val stopWords = setOf(
    "a", "al", "el", "la", "las", "los", "lo", "un", "una", "de", "del", "en", "por", "para", "me", "te", "que", "y",
    "con", "mi", "tu", "se", "le", "porfa", "favor",
  )
  // A second time, date, repetition or label next to the slot would be silently dropped by the pattern.
  internal val forbiddenLiteral = Regex(
    "\\b(?:hoy|manana|pasado|ayer|todos|todas|cada|dias?|semana|lunes|martes|miercoles|jueves|viernes|sabados?|domingos?|" +
      "llamad[ao]|nombre|etiqueta|minutos?|segundos?|horas?|por ciento|durante|hasta|desde|dentro)\\b|[0-9%:]",
  )
  private val percentShape = Regex("^([a-z0-9]+(?: y [a-z]+)?)(?: ?%| por ciento)?$")

  /** A structure observed in one executed request, with its slot value. */
  data class Observation(val prefix: String, val suffix: String, val action: Action, val value: String, val example: String) {
    val template: String get() = prefix + action.slot.placeholder + suffix
  }

  sealed interface Match {
    val template: String
    data class Command(val command: PhoneCommand, override val template: String) : Match
    /** The structure is known but the value is incomplete, e.g. «a las 8» without morning or evening. */
    data class Clarify(val prompt: String, val context: String, val source: String, override val template: String) : Match
  }

  fun actionFor(command: PhoneCommand): Action? = when (command) {
    is PhoneCommand.Alarm -> Action.ALARM.takeIf { command.days.isEmpty() && command.label.isEmpty() && command.date == null }
    is PhoneCommand.Timer -> Action.TIMER.takeIf { command.label.isEmpty() && command.seconds > 0 }
    is PhoneCommand.Volume -> Action.VOLUME.takeIf { command.operation == VolumeOp.SET }
    else -> null
  }

  /**
   * Finds the one span of [phrase] (already normalized by the learned-phrase key) that the existing parser decodes to
   * exactly [command]. Returns null when there is no such span, when several different spans qualify, or when the
   * remaining literal text is too generic or carries other parameters.
   */
  fun observe(phrase: String, command: PhoneCommand, now: () -> Calendar = { Calendar.getInstance() }): Observation? {
    val action = actionFor(command) ?: return null
    val tokens = phrase.split(' ').filter(String::isNotEmpty)
    if (tokens.size < 3) return null
    val parser = PhoneCommandParser(now)
    val spans = buildList {
      for (start in tokens.indices) for (end in start + 1..minOf(tokens.size, start + MAX_SLOT_TOKENS)) {
        val slot = tokens.subList(start, end).joinToString(" ")
        val value = valueOf(action, slot, command, parser) ?: continue
        add(Triple(start, end, value))
      }
    }
    val longest = spans.maxOfOrNull { it.second - it.first } ?: return null
    val best = spans.filter { it.second - it.first == longest }
    if (best.size != 1) return null
    val (start, end, value) = best.single()
    val before = tokens.subList(0, start)
    val after = tokens.subList(end, tokens.size)
    if (!literalAllowed(before + after)) return null
    val prefix = if (before.isEmpty()) "" else before.joinToString(" ") + " "
    val suffix = if (after.isEmpty()) "" else " " + after.joinToString(" ")
    return Observation(prefix, suffix, action, value, tokens.subList(start, end).joinToString(" "))
  }

  internal fun literalAllowed(literal: List<String>): Boolean =
    literal.size >= 2 && literal.any { it.length >= 4 && it !in stopWords } &&
      !forbiddenLiteral.containsMatchIn(literal.joinToString(" ")) &&
      literal.none { it !in setOf("un", "una") && PhoneCommandParser.number(it) != null }

  /** The canonical value of [slot] when it decodes to [command]; null otherwise. */
  private fun valueOf(action: Action, slot: String, command: PhoneCommand, parser: PhoneCommandParser): String? {
    if (!shapeAllowed(action, slot)) return null
    return when (val route = parser.parse(action.canonical(slot))) {
      is PhoneRoute.Local -> route.command.takeIf { it == command }?.let { canonicalValue(it) }
      is PhoneRoute.Clarify -> {
        // «a las 8» is a structural example of an alarm even when the hour needed a period.
        val alarm = command as? PhoneCommand.Alarm ?: return null
        if (route.context != "alarm" || ClockTime.parse(slot) != ClockTime.Ambiguous) return null
        val readings = listOf("de la manana", "de la tarde", "de la noche").mapNotNull {
          ClockTime.parse("$slot $it") as? ClockTime.Exact
        }
        if (readings.any { it.hour == alarm.hour && it.minute == alarm.minute }) canonicalValue(alarm) else null
      }
      else -> null
    }
  }

  private fun canonicalValue(command: PhoneCommand): String? = when (command) {
    is PhoneCommand.Alarm -> "%02d:%02d".format(java.util.Locale.ROOT, command.hour, command.minute)
    is PhoneCommand.Timer -> command.seconds.toString()
    is PhoneCommand.Volume -> command.percent.toString()
    else -> null
  }

  /** Strict type shape: extra words, dates, labels or a second action make the slot invalid. */
  internal fun shapeAllowed(action: Action, slot: String): Boolean {
    if (slot.isBlank() || slot.length > 40) return false
    return when (action.slot) {
      Slot.TIME -> ClockTime.shape(slot) && ClockTime.parse(slot) != ClockTime.Invalid &&
        !Regex("\\b(?:manana|hoy|pasado)\\b").containsMatchIn(slot.replace("de la manana", ""))
      Slot.DURATION -> CommandDuration.seconds(slot) != null
      Slot.PERCENT -> percentShape.matchEntire(slot)?.let { PhoneCommandParser.number(it.groupValues[1]) } in 0..100
    }
  }

  /** A learned structure ready to match. [enabled] is false when the user turned it off. */
  data class Pattern(val prefix: String, val suffix: String, val action: Action, val enabled: Boolean = true) {
    val template: String get() = prefix + action.slot.placeholder + suffix
  }

  /** Built once per store update. Lookups only touch patterns that share the first word of the request. */
  class Index(patterns: List<Pattern>) {
    private val byHead: Map<String, List<Pattern>>
    private val withoutPrefix: List<Pattern>
    val size: Int

    init {
      val active = patterns.filter { it.enabled }
      size = active.size
      byHead = active.filter { it.prefix.isNotEmpty() }.groupBy { it.prefix.substringBefore(' ') }
      withoutPrefix = active.filter { it.prefix.isEmpty() }
    }

    /**
     * Matches a normalized request. A structure shared by different actions, or a request matched by two
     * different structures with different results, is ambiguous and returns null: the normal flow decides.
     */
    fun match(phrase: String, now: () -> Calendar = { Calendar.getInstance() }): Match? {
      if (size == 0) return null
      val candidates = byHead[phrase.substringBefore(' ')].orEmpty() + withoutPrefix
      if (candidates.isEmpty()) return null
      val parser = PhoneCommandParser(now)
      val matches = candidates.mapNotNull { pattern ->
        if (!phrase.startsWith(pattern.prefix) || !phrase.endsWith(pattern.suffix) ||
          phrase.length <= pattern.prefix.length + pattern.suffix.length) return@mapNotNull null
        val slot = phrase.substring(pattern.prefix.length, phrase.length - pattern.suffix.length)
        if (slot != slot.trim() || !shapeAllowed(pattern.action, slot)) return@mapNotNull null
        val canonical = pattern.action.canonical(slot)
        when (val route = parser.parse(canonical)) {
          is PhoneRoute.Local -> route.command.takeIf { actionFor(it) == pattern.action }
            ?.let { Match.Command(it, pattern.template) }
          is PhoneRoute.Clarify -> Match.Clarify(route.prompt, route.context, canonical, pattern.template)
            .takeIf { route.context == pattern.action.id }
          else -> null
        }
      }
      val distinct = matches.distinctBy { if (it is Match.Command) it.command else (it as Match.Clarify).source }
      return distinct.singleOrNull()
    }
  }
}
