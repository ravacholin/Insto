package com.example.phone

import java.util.Calendar
import java.util.Locale

internal class CalendarSelectionParser(private val now: () -> Calendar = { Calendar.getInstance() }) {
  private val weekdays = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")
  private val months = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
  val datePattern = "(?:pasado manana|manana|hoy|(?:el )?(?:${weekdays.joinToString("|")}|[0-9]{1,2}/[0-9]{1,2}(?:/[0-9]{4})?|[0-9]{1,2} de (?:${months.joinToString("|")})(?: de [0-9]{4})?))"

  private val agendaName = "(?:(?:mi|la|el) )?(?:agenda|calendario)"
  private val agendaCommand = Regex("^(?:consulta|mostra|ver|lista|revisa|decime) $agendaName(?: (.+))?$")
  private val agendaQuestion = Regex("^(?:que tengo|que hay|tengo algo|que eventos tengo|hay (?:algo|eventos))(?: (.+))?$")
  private val agendaReference = Regex("(?:^| )en $agendaName(?= |$)")

  fun date(value: String, current: Calendar = now()): Calendar? = runCatching {
    val text = value.removePrefix("el ")
    val result = current.clone() as Calendar
    when {
      text == "hoy" -> Unit
      text == "manana" -> result.add(Calendar.DATE, 1)
      text == "pasado manana" -> result.add(Calendar.DATE, 2)
      text in weekdays -> result.add(Calendar.DATE, (weekdays.indexOf(text) + 1 - current.get(Calendar.DAY_OF_WEEK) + 7) % 7)
      else -> {
        val parts = text.split(if ('/' in text) "/" else " de ")
        val month = if ('/' in text) parts[1].toInt() else months.indexOf(parts[1]) + 1
        result.clear()
        result.isLenient = false
        result.set(parts.getOrNull(2)?.toInt() ?: current.get(Calendar.YEAR), month - 1, parts[0].toInt())
      }
    }
    CalendarSelection.day(CalendarSelection.iso(result), current)
  }.getOrNull()

  fun parse(request: FoldedRequest): PhoneRoute? = parse(request, ::readClock)

  fun reply(request: FoldedRequest, text: String, action: PlatformAction): PhoneRoute.Local? {
    val answer = ClockTime.reply(text)
    val exact = readClock(answer)
    if (exact == null && !ClockTime.isPeriod(answer)) return null
    var corrected = 0
    val route = parse(request) { original ->
      readClock(original) ?: if (original.isEmpty() || ClockTime.shape(original)) {
        (exact ?: readClock(ClockTime.replacePeriod(original, answer)))?.also { corrected++ }
      } else null
    } as? PhoneRoute.Local ?: return null
    return route.takeIf { corrected == 1 && (it.command as? PhoneCommand.Platform)?.action == action }
  }

  private fun parse(request: FoldedRequest, time: (String) -> String?): PhoneRoute? {
    val text = request.text.trimEnd('.', '!', '?', ' ')
    fun result(action: PlatformAction, selection: CalendarSelection) =
      PhoneRoute.Local(selection.command(action))
    val current = now()
    val mutation = Regex("^(?:cancela|elimina|borra|quita|pasa|cambia|move|reprograma|edita|modifica|hace que) ")
    val syntax = RequestSafetyPolicy.withoutQuotedText(text)
    if (mutation.containsMatchIn(syntax) && Regex("\\b(?:agenda|calendario|eventos?|reuniones?|clases?|turnos?|citas?|serie)\\b").containsMatchIn(syntax) &&
      Regex("\\b(?:todos? (?:los|las|el)|todas? (?:las|la)|serie|ocurrencias|cada (?:evento|reunion|clase|turno|cita))\\b").containsMatchIn(syntax)) {
      return PhoneRoute.Unsupported("Indicá un solo evento o una ocurrencia concreta para modificar o eliminar.")
    }
    val commandPeriod = agendaCommand.matchEntire(text)?.groupValues?.get(1)
    val questionTail = agendaQuestion.matchEntire(text)?.groupValues?.get(1).orEmpty()
    val calendarReference = agendaReference.find(questionTail)
    val explicitAgenda = commandPeriod != null || calendarReference != null
    val questionPeriod = if (calendarReference != null) questionTail.replaceRange(calendarReference.range, "").trim()
      else questionTail
    val implicitAgenda = text.startsWith("que tengo ") || text.startsWith("tengo algo ")
    if (explicitAgenda || implicitAgenda) {
      val whenText = (commandPeriod ?: questionPeriod).removePrefix("de ").removePrefix("para ")
        .ifEmpty { if (explicitAgenda) "hoy" else "" }
      val afternoon = whenText.endsWith(" a la tarde") || whenText.endsWith(" por la tarde")
      val period = whenText.removeSuffix(" a la tarde").removeSuffix(" por la tarde")
      if (period == "esta semana") {
        val monday = (current.clone() as Calendar).apply { add(Calendar.DATE, -((get(Calendar.DAY_OF_WEEK) + 5) % 7)) }
        val nextMonday = (monday.clone() as Calendar).apply { add(Calendar.DATE, 7) }
        return result(PlatformAction.CALENDAR_QUERY, CalendarSelection(from = CalendarSelection.iso(monday), until = CalendarSelection.iso(nextMonday), afternoon = afternoon))
      }
      Regex("^(?:desde|entre) ($datePattern) (?:hasta|y) ($datePattern)$").matchEntire(period)?.let {
        val begin = date(it.groupValues[1], current)
        val end = date(it.groupValues[2], current)?.apply { add(Calendar.DATE, 1) }
        if (begin != null && end != null && end.after(begin)) {
          val selection = CalendarSelection(from = CalendarSelection.iso(begin), until = CalendarSelection.iso(end), afternoon = afternoon)
          return result(PlatformAction.CALENDAR_QUERY, selection)
        }
        return PhoneRoute.Clarify("Indicá un intervalo válido para consultar tu agenda.", "intent:calendar_query")
      }
      Regex("^($datePattern) a las?(?: (.+))?$").matchEntire(period)?.let {
        val day = date(it.groupValues[1], current)
        val hour = time(it.groupValues[2])
        if (day != null && hour != null) {
          return result(PlatformAction.CALENDAR_QUERY, CalendarSelection(from = CalendarSelection.iso(day), at = hour))
        }
        return PhoneRoute.Clarify("Indicá el día y la hora completa, con mañana o tarde.", "intent:calendar_query")
      }
      date(period, current)?.let { return result(PlatformAction.CALENDAR_QUERY, CalendarSelection(from = CalendarSelection.iso(it), afternoon = afternoon)) }
      if (explicitAgenda) return PhoneRoute.Unsupported(
        "No pude interpretar ese período de la agenda. Podés indicar hoy, mañana, esta semana o un intervalo entre dos fechas."
      )
      if (period.isNotEmpty() && !Regex("^(?:hoy|manana|pasado|el |esta |la proxima|[0-9])").containsMatchIn(period)) return null
      if (period.isNotEmpty()) return PhoneRoute.Unsupported("Indicá qué día o intervalo de agenda querés consultar.", recoverable = true)
      return null
    }
    if (Regex("^(?:(?:cuales son|mostra|lista|consulta) )?(?:mis |los )?proximos eventos$").matches(text))
      return result(PlatformAction.CALENDAR_QUERY, CalendarSelection(next = true))
    Regex("^(?:cuando es|a que hora es) (?:mi )?proxim[ao] (.+)$").matchEntire(text)?.let {
      return result(PlatformAction.CALENDAR_QUERY, CalendarSelection(title = request.capture(it, 1), next = true))
    }
    Regex("^(?:cuando es|a que hora es) (.+)$").matchEntire(text)?.let {
      val target = it.groupValues[1]
      val selection = selector(target, current, time)
      if (selection == null) {
        val beforeTime = Regex("^(.+?) a las?(?: .+)?$").matchEntire(target)?.groupValues?.get(1)
        if (beforeTime != null && selector(beforeTime, current, time)?.from != null)
          return PhoneRoute.Clarify("Indicá el horario completo, por ejemplo 17:30.", "intent:calendar_query")
        return null
      }
      if (selection.from == null) return null
      return result(PlatformAction.CALENDAR_QUERY, selection)
    }
    Regex("^(?:cancela|elimina|borra|quita) (.+)$").matchEntire(text)?.let {
      val target = it.groupValues[1]
      val selection = selector(target, current, time)
      if (selection == null) {
        val beforeTime = Regex("^(.+?) a las?(?: .+)?$").matchEntire(target)?.groupValues?.get(1)
        if (beforeTime != null && selector(beforeTime, current, time) != null)
          return PhoneRoute.Clarify("Indicá el horario completo del evento que querés eliminar.", "intent:calendar_delete")
        return null
      }
      if (selection.title == null && selection.at == null) {
        val at = if (selection.from != null) time("") else null
        if (at != null) return result(PlatformAction.CALENDAR_DELETE, selection.copy(at = at))
        return PhoneRoute.Clarify("Indicá el título o el día y el horario exactos del evento.", "intent:calendar_delete")
      }
      return result(PlatformAction.CALENDAR_DELETE, selection)
    }
    Regex("^hace que (.+) dure (.+)$").matchEntire(text)?.let {
      val selection = selector(it.groupValues[1], current, time) ?: return null
      val duration = CommandDuration.seconds(it.groupValues[2]) ?: return invalidChange()
      return result(PlatformAction.CALENDAR_UPDATE, selection.copy(durationSeconds = duration))
    }
    Regex("^(?:pasa|cambia|move|reprograma|edita|modifica) (.+)$").matchEntire(text)?.let {
      val body = it.groupValues[1]
      val changeSyntax = Regex("^(?:$datePattern(?: a las?(?: ${ClockTime.PATTERN})?)?|las?(?: ${ClockTime.PATTERN})?|${ClockTime.PATTERN})$")
      val boundaries = Regex(" (?:a|al|para) ").findAll(body).toList()
      fun targetBefore(boundary: MatchResult) = body.substring(0, boundary.range.first)
      fun changeAfter(boundary: MatchResult) = body.substring(boundary.range.last + 1)
      // The first complete change clause can include a date and time. An old time
      // followed by another change clause cannot be mistaken for the new time.
      val boundary = boundaries.firstOrNull { candidate ->
        changeSyntax.matches(changeAfter(candidate)) &&
          selector(targetBefore(candidate), current) { oldTime -> "00:00".takeIf { oldTime.isEmpty() || ClockTime.shape(oldTime) } } != null
      }
      if (boundary == null) {
        val selection = selector(body, current, time)
        if (selection != null && text.contains('"') && (text.startsWith("edita ") || text.startsWith("modifica ")))
          return result(PlatformAction.CALENDAR_UPDATE, selection)
        if (selection != null) time("")?.let { return result(PlatformAction.CALENDAR_UPDATE, selection.copy(newTime = it)) }
        if (selection != null || boundaries.any { selector(targetBefore(it), current, ::readClock) != null }) return invalidChange()
        return null
      }
      val selection = selector(targetBefore(boundary), current, time) ?: return invalidChange()
      val change = changeAfter(boundary)
      val dayAndTime = Regex("^($datePattern)(?: a (las?)(?: (.+))?)?$").matchEntire(change)
      val newDay = dayAndTime?.let { match -> date(match.groupValues[1], current)?.let(CalendarSelection::iso) ?: return invalidChange() }
      val timeText = dayAndTime?.groupValues?.get(3) ?: change.replace(Regex("^las?(?: |$)"), "")
      val expectsTime = dayAndTime == null || dayAndTime.groupValues[2].isNotEmpty()
      val newTime = if (expectsTime) time(timeText) ?: return invalidChange() else null
      return result(PlatformAction.CALENDAR_UPDATE, selection.copy(newDate = newDay, newTime = newTime))
    }
    return null
  }

  private fun invalidChange() = PhoneRoute.Clarify(
    "Indicá el cambio y, si hay una hora ambigua, mañana o tarde. Por ejemplo, cambiá la reunión del viernes para las 18:00.",
    "intent:calendar_update")

  private fun selector(raw: String, current: Calendar, time: (String) -> String?): CalendarSelection? {
    val match = Regex("^(.+?)(?:(?: d?el | de | )($datePattern))?(?: (a las?)(?: (.+))?)?$").matchEntire(raw) ?: return null
    val target = match.groupValues[1].replace(Regex("^(?:el|la|mi|un|una) "), "")
    val quoted = Regex("^(?:evento (?:llamado )?)?\"([^\"]+)\"$").matchEntire(target)
    val isEvent = Regex("^(?:reunion|clase|turno|cita|evento|cumpleanos)\\b(?: [a-z0-9]+)*$").matches(target)
    if (quoted == null && !isEvent) return null
    val title = quoted?.groupValues?.get(1) ?: target.takeUnless { it == "evento" }
    if (title == null && match.groupValues[2].isEmpty()) return null
    val from = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { date(it, current)?.let(CalendarSelection::iso) ?: return null }
    val at = if (match.groupValues[3].isNotEmpty()) time(match.groupValues[4]) ?: return null else null
    return CalendarSelection(title = title, from = from, at = at)
  }

  private fun readClock(text: String): String? = (ClockTime.parse(text) as? ClockTime.Exact)?.canonical()
}
