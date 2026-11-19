package com.example.phone

import java.util.Calendar

internal class CalendarRequestParser(private val now: () -> Calendar) {
  fun parse(request: FoldedRequest): PhoneRoute? {
    val dates = CalendarSelectionParser(now)
    val description = Regex(" (?:con descripcion|descripcion)[: ]+(.+)$").find(request.text)
    val syntax = description?.let { request.text.substring(0, it.range.first).trimEnd(',', ' ') } ?: request.text
    val m = Regex("^(?:agenda|programa|crea|agrega)\\s+(.+?)\\s+(${dates.datePattern})\\s+a\\s+las?\\s+(.+)$").matchEntire(syntax)
    if (m == null) return if (Regex("^(?:agenda|(?:crea|agrega) (?:un |una )?(?:evento|reunion|clase|turno|cita))\\b").containsMatchIn(request.text))
      PhoneRoute.Clarify("Indicá título, fecha y hora: agendá reunión mañana a las 17:00.", "calendar") else null
    val title = request.capture(m, 1).removeSurrounding("\"").removeSurrounding("“", "”").removeSurrounding("«", "»")
    val timeAndDuration = m.groupValues[3].split(" durante ", limit = 2)
    val durationSeconds = if (timeAndDuration.size == 1) 3600 else {
      CommandDuration.seconds(timeAndDuration[1])
        ?: return PhoneRoute.Unsupported("Indicá una duración válida para el evento; no se preparó el calendario.")
    }
    return create(title, m.groupValues[2], timeAndDuration[0], durationSeconds, description?.let { request.capture(it, 1) })
  }

  fun create(title: String, dateText: String, timeText: String, durationSeconds: Int, description: String?): PhoneRoute {
    if (durationSeconds <= 0) return PhoneRoute.Unsupported("Indicá una duración válida para el evento.")
    val time = when (val value = ClockTime.parse(FoldedRequest.of(timeText).text.trimEnd('.', '!', '?', ' '))) {
      is ClockTime.Exact -> value
      ClockTime.Ambiguous -> return PhoneRoute.Clarify("¿De la mañana o de la tarde? Indicá el período o una hora como 17:30.", "calendar")
      ClockTime.Invalid -> return PhoneRoute.Unsupported("La hora del evento no es válida. Usá, por ejemplo, 17:30.")
    }
    val current = now()
    val date = CalendarSelectionParser(now).date(FoldedRequest.of(dateText).text, current)
      ?: return PhoneRoute.Unsupported("Esa fecha no existe en la zona horaria del teléfono.")
    val begin = time.onDate(date)
      ?: return PhoneRoute.Unsupported("Esa hora no existe o se repite por un cambio de horario. Indicá otra hora.")
    if (begin <= current.timeInMillis) return PhoneRoute.Unsupported("Ese horario ya pasó. Indicá una fecha y hora futuras para el evento.")
    return PhoneRoute.Local(PhoneCommand.CalendarEvent(title, begin, Math.addExact(begin, durationSeconds.toLong() * 1000), description))
  }
}
