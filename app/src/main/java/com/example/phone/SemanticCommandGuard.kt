package com.example.phone

import java.util.Calendar

/**
 * Final deterministic check between an interpretation and its execution.
 *
 * It compares the text the user wrote with the proposed commands, whatever produced them: the local grammar,
 * a learned phrase or structure, Gemini Decisions or Groq. No model or network call. Codecs already validate
 * formats and ranges; this checks meaning: every explicit duration, percentage, time, day, repetition, quoted
 * text, step and direction must be represented by the commands, and a device action may not come from a
 * mention inside a search or a message. Conditions, negations and questions are rejected earlier by
 * [RequestSafetyPolicy] at the same entry point, so they are not checked twice.
 *
 * Only explicit data is enforced. Courtesy, interjections, diminutives and labels are not essential: a command
 * without them still represents the request.
 */
object SemanticCommandGuard {
  sealed interface Verdict {
    data object Accepted : Verdict
    /** The request is understood but a datum is ambiguous; [context] continues the existing clarification. */
    data class NeedsClarification(val prompt: String, val context: String) : Verdict
    data class Rejected(val reason: String) : Verdict
  }

  /**
   * Applies the check to a new turn. Continuations of a draft reply to a question and are validated by the
   * draft itself; [InterpretationResult.Direct.learned] and contextual results come from the user's own
   * phrases or the brief operational context, so their payload need not appear in the text, but every
   * explicit datum still must.
   */
  fun review(request: InterpretationRequest, result: InterpretationResult): InterpretationResult {
    if (request.draft != null) return result
    val (commands, grounded) = when (result) {
      is InterpretationResult.Direct -> listOf(result.command) to !(result.learned || result.contextual)
      is InterpretationResult.Plan -> result.commands to true
      else -> return result
    }
    val verdict = check(request.text, commands, request.now, grounded && request.sourceContext == null)
    return when (verdict) {
      Verdict.Accepted -> result
      is Verdict.NeedsClarification -> InterpretationResult.Clarification(verdict.prompt, verdict.context)
      is Verdict.Rejected -> InterpretationResult.Rejected(verdict.reason)
    }
  }

  fun check(text: String, commands: List<PhoneCommand>, now: Calendar, grounded: Boolean = true): Verdict {
    if (commands.isEmpty()) return Verdict.Accepted
    val payloads = commands.flatMap(::payloads).map(PhoneCommandParser::normalize)
    fun literal(fact: String) = payloads.any { fact in it }
    val syntax = PhoneCommandParser.normalize(RequestSafetyPolicy.withoutQuotedText(text))

    // Quoted text is literal content: it must reach a command unchanged.
    for (quote in RequestSafetyPolicy.quotedTexts(text)) {
      val wanted = PhoneCommandParser.normalize(quote)
      if (wanted.isNotEmpty() && payloads.none { it == wanted || wanted in it })
        return Verdict.Rejected("La interpretación cambió o perdió el texto citado «$quote». No se ejecutó ni se envió.")
    }

    // «Ahora, poné…»: a leading discourse marker is not a step, as in the coordinator.
    val steps = OperationalContext.withoutDiscourseLead(ConversationSession.splitSteps(text))
    if (commands.size < steps.size)
      return Verdict.Rejected("La interpretación omitió pasos del pedido. No se ejecutó ninguno; indicá cada acción.")
    val pairs = if (commands.size == steps.size) steps.zip(commands) else steps.map { step -> step to null }
    for ((step, paired) in pairs) {
      val stepSyntax = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(RequestSafetyPolicy.withoutQuotedText(step)))
      val candidates = paired?.let(::listOf) ?: commands
      if (subjectHead.containsMatchIn(stepSyntax) && candidates.any(::isDeviceEffect))
        return Verdict.Rejected("La acción del teléfono aparece como tema de una búsqueda o un mensaje, no como orden. No se ejecutó.")
      direction(stepSyntax, candidates)?.let { return Verdict.Rejected(it) }
      foreignTarget(stepSyntax, candidates)?.let { return Verdict.Rejected(it) }
    }

    for (duration in durations(syntax)) {
      if (literal(duration.phrase) || commands.any { durationOf(it, now) == duration.seconds || opaqueTemporal(it) }) continue
      return Verdict.Rejected("La interpretación no conserva la duración o el plazo «${duration.phrase}». " +
        "No se ejecutó; indicá la acción con su duración.")
    }

    for (percent in percents(syntax)) {
      if (literal(percent.phrase) || commands.any { percentOf(it) == percent.value }) continue
      return Verdict.Rejected("La interpretación cambió o perdió el porcentaje «${percent.phrase}». No se ejecutó.")
    }

    for (time in times(syntax)) {
      if (literal(time.phrase) || commands.any(::opaqueTemporal)) continue
      val fixed = commands.mapNotNull { clockOf(it, now) }
      when (val parsed = time.clock) {
        is ClockTime.Exact -> if (fixed.none { it.first == parsed.hour && it.second == parsed.minute })
          return Verdict.Rejected("La interpretación cambió o perdió la hora «${time.phrase}». No se ejecutó.")
        ClockTime.Ambiguous -> {
          val hour = time.hour ?: continue
          if (fixed.none { it.first % 12 == hour % 12 })
            return Verdict.Rejected("La interpretación cambió o perdió la hora «${time.phrase}». No se ejecutó.")
          // A model or phrase chose the period the user did not say. Ask, as the local grammar does.
          if (commands.any { it is PhoneCommand.Alarm || it is PhoneCommand.TemporaryMode && it.deadlineMillis != null })
            return clarifyPeriod(text, now)
        }
        ClockTime.Invalid -> Unit
      }
    }

    for (day in days(syntax)) {
      val date = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, day.offset) }
      val iso = CalendarSelection.iso(date)
      if (literal(day.phrase) || commands.any(::opaqueTemporal)) continue
      val dated = commands.mapNotNull { dateOf(it, now) }
      val matches = iso in dated || day.offset == 0 && commands.any { it is PhoneCommand.Alarm && it.date == null && it.days.isEmpty() }
      if (!matches) return Verdict.Rejected("La interpretación no conserva el día pedido («${day.phrase}»). No se ejecutó.")
    }

    repetition(syntax)?.let { wanted ->
      val alarms = commands.filterIsInstance<PhoneCommand.Alarm>()
      if (alarms.isNotEmpty() && alarms.none { it.days.toSet() == wanted })
        return Verdict.Rejected("La interpretación cambió los días de repetición. No se ejecutó.")
    }

    if (grounded) {
      val words = PhoneCommandParser.normalize(text).split(' ').filter(String::isNotEmpty)
      for (command in commands) for (value in groundedPayloads(command)) {
        val missing = significant(value).firstOrNull { token -> token !in fillers && words.none { sameWord(it, token) } }
        if (missing != null)
          return Verdict.Rejected("La propuesta cambió un dato del pedido: «$value» no figura en lo que pediste. No se ejecutó.")
      }
    }
    return Verdict.Accepted
  }

  private fun clarifyPeriod(text: String, now: Calendar): Verdict {
    val local = PhoneCommandParser { now.clone() as Calendar }.parse(text)
    if (local is PhoneRoute.Clarify) {
      val dated = local.context == "alarm" && Regex("\\b(?:hoy|manana)\\b")
        .containsMatchIn(PhoneCommandParser.normalize(text).replace("de la manana", ""))
      return Verdict.NeedsClarification(local.prompt, if (dated) "intent:alarm" else local.context)
    }
    return Verdict.Rejected("¿De la mañana o de la tarde? Repetí el pedido con el período o una hora como 17:30. No se ejecutó.")
  }

  // ---- Explicit facts in the text -------------------------------------------------------------------------

  internal data class Duration(val phrase: String, val seconds: Int)
  internal data class Percent(val phrase: String, val value: Int)
  internal data class Time(val phrase: String, val clock: ClockTime, val hour: Int?)
  internal data class Day(val phrase: String, val offset: Int)

  private const val NUMBER = "(?:[0-9]+|[a-z]+(?: y [a-z]+)?)"
  private val durationPhrase = Regex("\\b((?:$NUMBER (?:horas?|minutos?|segundos?)(?: y )?)+)")
  private val explicitPercent = Regex("\\b($NUMBER) ?(?:%|por ciento)")
  private val contextualPercent = Regex("\\b(?:al?|en) ($NUMBER)\\b(?! ?(?:%|por ciento|horas?|minutos?|segundos?|de la|y media|y cuarto|:))")
  private val levelTopic = Regex("\\b(?:volumen|sonido|brillo)\\b")
  private val clockPhrase = Regex("\\b(?:a|para|hasta|desde|de) (?:las?) (${ClockTime.PATTERN})")
  private val dayPhrase = Regex("\\b(pasado manana|manana|hoy)\\b")

  internal fun durations(syntax: String): List<Duration> = durationPhrase.findAll(syntax).mapNotNull { match ->
    val phrase = match.groupValues[1].trim().removeSuffix(" y").trim()
    CommandDuration.seconds(phrase)?.let { Duration(phrase, it) }
  }.toList()

  internal fun percents(syntax: String): List<Percent> {
    val explicit = explicitPercent.findAll(syntax).mapNotNull { match ->
      PhoneCommandParser.number(match.groupValues[1])?.takeIf { it in 0..100 }?.let { Percent(match.value.trim(), it) }
    }.toList()
    if (explicit.isNotEmpty() || !levelTopic.containsMatchIn(syntax)) return explicit
    return contextualPercent.findAll(syntax).mapNotNull { match ->
      PhoneCommandParser.number(match.groupValues[1])?.takeIf { it in 0..100 }?.let { Percent(match.groupValues[1], it) }
    }.toList()
  }

  internal fun times(syntax: String): List<Time> = clockPhrase.findAll(syntax).mapNotNull { match ->
    // «hasta las 23 y un temporizador…», «a las 8 y apagá…»: the conjunction starts another clause, not minutes.
    val full = match.groupValues[1].trim().replace(Regex(" y (?:un|una|uno)\\b.*$"), "")
    listOf(full, full.substringBefore(" y ")).distinct().firstNotNullOfOrNull { phrase ->
      val clock = ClockTime.parse(phrase)
      if (clock == ClockTime.Invalid) null
      else Time(phrase, clock, PhoneCommandParser.number(phrase.substringBefore(' ').substringBefore(':')))
    }
  }.toList()

  internal fun days(syntax: String): List<Day> {
    // «de la mañana» is a period, not a day.
    val masked = syntax.replace(Regex("\\b(?:de|por|a) la manana\\b"), " ")
    return dayPhrase.findAll(masked).map { match ->
      Day(match.value, when (match.value) { "pasado manana" -> 2; "manana" -> 1; else -> 0 })
    }.toList()
  }

  private val weekdays = mapOf("domingo" to 1, "lunes" to 2, "martes" to 3, "miercoles" to 4,
    "jueves" to 5, "viernes" to 6, "sabado" to 7)
  private val everyDay = Regex("\\b(?:todos los dias|cada dia)\\b")
  private val everyWeekday = Regex("\\b(?:todos los|cada) ((?:domingos?|lunes|martes|miercoles|jueves|viernes|sabados?)(?:(?:, | y )(?:domingos?|lunes|martes|miercoles|jueves|viernes|sabados?))*)")

  internal fun repetition(syntax: String): Set<Int>? {
    if (everyDay.containsMatchIn(syntax)) return (1..7).toSet()
    val match = everyWeekday.find(syntax) ?: return null
    return match.groupValues[1].split(Regex(", | y ")).mapNotNull { weekdays[it] ?: weekdays[it.removeSuffix("s")] }.toSet()
  }

  // ---- What the commands represent ------------------------------------------------------------------------

  private fun payloads(command: PhoneCommand): List<String> = when (command) {
    is PhoneCommand.Platform -> listOfNotNull(command.argument, command.content)
    is PhoneCommand.Timer -> listOf(command.label)
    is PhoneCommand.Alarm -> listOf(command.label)
    is PhoneCommand.CalendarEvent -> listOfNotNull(command.title, command.description)
    is PhoneCommand.YouTubeSearch -> listOf(command.query)
    is PhoneCommand.SpotifySearch -> listOf(command.query)
    is PhoneCommand.MusicSearch -> listOf(command.query)
    is PhoneCommand.MapSearch -> listOf(command.query)
    is PhoneCommand.Navigate -> listOf(command.destination)
    is PhoneCommand.Dial -> listOf(command.target)
    is PhoneCommand.OpenApp -> listOf(command.name)
    is PhoneCommand.SavePlace -> listOf(command.address)
    else -> emptyList()
  }.filter(String::isNotBlank)

  /** Free text a provider copies from the request: it may not introduce words the user did not say. */
  private fun groundedPayloads(command: PhoneCommand): List<String> = when (command) {
    is PhoneCommand.YouTubeSearch -> listOf(command.query)
    is PhoneCommand.SpotifySearch -> listOf(command.query)
    is PhoneCommand.MusicSearch -> listOf(command.query)
    is PhoneCommand.MapSearch -> listOf(command.query)
    is PhoneCommand.Navigate -> listOf(command.destination)
    is PhoneCommand.OpenApp -> listOf(command.name)
    is PhoneCommand.Platform -> when {
      command.action !in messageActions -> emptyList()
      // A recipient by name must be the one the user said, never one the provider proposed.
      command.action == PlatformAction.MESSAGE_SEND && command.argument?.let(PhoneNumbers::canonical) == null ->
        listOfNotNull(command.argument, command.content)
      else -> listOfNotNull(command.content)
    }
    else -> emptyList()
  }

  private val messageActions = setOf(PlatformAction.MESSAGE_SEND, PlatformAction.TEXT_SHARE)

  /** Calendar and weather queries carry their own validated time structure. */
  private fun opaqueTemporal(command: PhoneCommand): Boolean = command is PhoneCommand.Platform &&
    (command.action in CalendarSelection.actions || command.action == PlatformAction.WEATHER_RAIN ||
      command.action == PlatformAction.WEATHER_TEMPERATURE || command.action == PlatformAction.CALENDAR_AGENDA)

  private fun durationOf(command: PhoneCommand, now: Calendar): Int? = when (command) {
    is PhoneCommand.Timer -> command.seconds
    is PhoneCommand.TemporaryMode -> command.seconds.takeIf { command.deadlineMillis == null }
    is PhoneCommand.CalendarEvent -> ((command.endMillis - command.beginMillis) / 1000).toInt()
    else -> null
  }

  private fun percentOf(command: PhoneCommand): Int? = when (command) {
    is PhoneCommand.Volume -> command.percent.takeIf { command.operation == VolumeOp.SET }
    is PhoneCommand.TemporaryMode -> command.percent.takeIf { command.kind == ModeKind.MEDIA_VOLUME }
    is PhoneCommand.Platform -> command.percent
    else -> null
  }

  private fun local(millis: Long, now: Calendar) = (now.clone() as Calendar).apply { timeInMillis = millis }

  private fun clockOf(command: PhoneCommand, now: Calendar): Pair<Int, Int>? = when (command) {
    is PhoneCommand.Alarm -> command.hour to command.minute
    is PhoneCommand.TemporaryMode -> command.deadlineMillis?.let { local(it, now) }
      ?.let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) }
    is PhoneCommand.CalendarEvent -> local(command.beginMillis, now).let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) }
    else -> null
  }

  private fun dateOf(command: PhoneCommand, now: Calendar): String? = when (command) {
    is PhoneCommand.Alarm -> command.date
    is PhoneCommand.TemporaryMode -> command.deadlineMillis?.let { CalendarSelection.iso(local(it, now)) }
    is PhoneCommand.CalendarEvent -> CalendarSelection.iso(local(command.beginMillis, now))
    else -> null
  }

  private fun isDeviceEffect(command: PhoneCommand): Boolean = when (command) {
    is PhoneCommand.Torch, is PhoneCommand.Volume, is PhoneCommand.TemporaryMode, PhoneCommand.EndMode,
    is PhoneCommand.Timer, is PhoneCommand.Alarm -> true
    is PhoneCommand.Stopwatch -> command.operation != StopwatchOp.STATUS
    is PhoneCommand.Media -> command.operation != MediaOp.STATUS
    is PhoneCommand.Platform -> command.action in setOf(PlatformAction.DND_ON, PlatformAction.DND_OFF,
      PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON, PlatformAction.BRIGHTNESS)
    else -> false
  }

  /** Lead verbs whose object is a topic or a text, never an order for the device. Matched after [PhoneCommandParser.canonicalRequest]. */
  private val subjectHead = Regex("^(?:me )?(?:busca|encontra|mostra|lee|manda(?:le|les)?|envia(?:le|les)?|escrib[ie](?:le|les|me)?|" +
    "redacta|comparti|decime|deci(?:le|les)?|conta(?:le|les|me)?|pregunta(?:le|les)?|explica(?:le|me)?)\\b")

  /** The direction the user said must survive: «apagá» is never Torch(true), «bajá» is never Volume(UP). */
  private fun direction(step: String, commands: List<PhoneCommand>): String? {
    val off = Regex("\\b(?:apag|desactiv)[a-z]*\\b").containsMatchIn(step)
    val on = Regex("\\b(?:prend|encend|enciend|activ)[a-z]*\\b").containsMatchIn(step)
    // «Dejá de iluminar» stops the light even without «apagá».
    val stop = Regex("\\bdeja(?:me)? de\\b").containsMatchIn(step)
    for (command in commands) when (command) {
      is PhoneCommand.Torch -> if (stop && !on && command.enabled && torchTarget.containsMatchIn(step))
        return "La interpretación invirtió la acción sobre la linterna. No se ejecutó."
      else if ("linterna" in step && (off && !on && command.enabled || on && !off && !command.enabled))
        return "La interpretación invirtió la acción sobre la linterna. No se ejecutó."
      is PhoneCommand.Volume -> if (Regex("\\b(?:volumen|sonido)\\b").containsMatchIn(step)) {
        val down = Regex("\\b(?:baj|reduc)[a-z]*\\b").containsMatchIn(step)
        val up = Regex("\\b(?:sub|aument)[a-z]*\\b").containsMatchIn(step)
        if (command.operation == VolumeOp.UP && down && !up || command.operation == VolumeOp.DOWN && up && !down)
          return "La interpretación invirtió el cambio de volumen. No se ejecutó."
      }
      is PhoneCommand.Platform -> if ("no molestar" in step && (
          command.action == PlatformAction.DND_ON && off && !on || command.action == PlatformAction.DND_OFF && on && !off))
        return "La interpretación invirtió el modo No molestar. No se ejecutó."
      else -> Unit
    }
    return null
  }

  private val torchTarget = Regex("\\b(?:linterna|luz|luces|ilumin[a-z]*|flash|oscur[a-z]*)\\b")
  private val volumeTarget = Regex("\\b(?:volumen|sonido|musica|audio|parlante|fuerte|bajito|baj[a-z]*|sub[a-z]*)\\b")
  private val otherDevice = Regex("\\b(?:wifi|wi fi|bluetooth|datos moviles|modo avion|brillo|alarmas?|timers?|temporizador(?:es)?|cronometro|no molestar|camara|linterna|volumen|sonido)\\b")

  /**
   * A torch or volume proposal from a step that explicitly names another device, and not its own, is a different
   * action: «apagá el wifi» is never the torch. Paraphrases that name no device («necesito luz») are unaffected.
   */
  private fun foreignTarget(step: String, commands: List<PhoneCommand>): String? {
    val named = otherDevice.findAll(step).map { it.value }.toSet()
    if (named.isEmpty()) return null
    for (command in commands) when (command) {
      is PhoneCommand.Torch -> if (!torchTarget.containsMatchIn(step) && named.any { it != "linterna" })
        return "La interpretación eligió la linterna, pero el pedido nombra otro dispositivo. No se ejecutó."
      is PhoneCommand.Volume -> if (!volumeTarget.containsMatchIn(step) && named.none { it == "volumen" || it == "sonido" })
        return "La interpretación eligió el volumen, pero el pedido nombra otro dispositivo. No se ejecutó."
      else -> Unit
    }
    return null
  }

  /**
   * Generic words a provider may add to a query without changing its object: «farmacias por acá» → «farmacias
   * cerca». A new name, place or topic is not in this list and is rejected.
   */
  private val fillers = setOf("cerca", "cercana", "cercanas", "cercano", "cercanos", "abierta", "abiertas", "abierto",
    "abiertos", "zona", "ahora", "musica", "cancion", "canciones", "temas", "album", "videos", "video", "playlist",
    "aplicacion", "app")

  private val stopwords = setOf("para", "como", "sobre", "desde", "hasta", "este", "esta", "esto", "algo", "unos", "unas",
    "with", "that", "this", "the")

  private fun significant(value: String) = PhoneCommandParser.normalize(value).split(' ')
    .filter { it.length >= 4 && it !in stopwords }

  /** Tolerates inflection and transcription variants (gender, number, a dropped accent) but not another word. */
  private fun sameWord(a: String, b: String): Boolean {
    if (a == b) return true
    val common = a.zip(b).takeWhile { (x, y) -> x == y }.size
    return common >= 5 || common >= minOf(a.length, b.length) && minOf(a.length, b.length) >= 4
  }
}
