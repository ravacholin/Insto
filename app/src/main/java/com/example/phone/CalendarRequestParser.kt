package com.example.phone

import java.util.Calendar

internal class CalendarRequestParser(private val now: () -> Calendar) {
  fun parse(request: FoldedRequest): PhoneRoute? {
    val m = Regex("^(?:agenda|programa|crea)\\s+(.+?)\\s+(hoy|pasado manana|manana|(?:el\\s+)?[0-9]{1,2}/[0-9]{1,2}(?:/[0-9]{4})?)\\s+a\\s+las?\\s+(.+)$").matchEntire(request.text)
    if (m == null) return if (Regex("^(?:agenda|crea (?:un )?evento)\\b").containsMatchIn(request.text))
      PhoneRoute.Clarify("Indicá título, fecha y hora: agendá reunión mañana a las 17:00.", "calendar") else null
    val title = request.capture(m, 1)
    val timeAndDuration = m.groupValues[3].split(" durante ", limit = 2)
    val durationSeconds = if (timeAndDuration.size == 1) 3600 else {
      CommandDuration.seconds(timeAndDuration[1])
        ?: return PhoneRoute.Unsupported("Indicá una duración válida para el evento; no se preparó el calendario.")
    }
    val time = Regex("^([a-z0-9]+)(?::([0-9]{2})| y (media|cuarto|[a-z]+(?: y [a-z]+)?))?(?: (de la manana|de la tarde|de la noche|am|pm|horas))?[.!?]*$").matchEntire(timeAndDuration[0])
      ?: return PhoneRoute.Unsupported("No pude interpretar toda la hora del evento. Usá, por ejemplo, 17:30.")
    var hour = PhoneCommandParser.number(time.groupValues[1]) ?: -1
    val minute = when (val word = time.groupValues[3]) {
      "media" -> 30; "cuarto" -> 15; "" -> time.groupValues[2].toIntOrNull() ?: 0
      else -> PhoneCommandParser.number(word) ?: -1
    }
    val period = time.groupValues[4]
    if (hour !in 0..23 || minute !in 0..59 || (period.isNotEmpty() && period != "horas" && hour !in 1..12)) {
      return PhoneRoute.Unsupported("La hora del evento no es válida. Usá una hora entre 00:00 y 23:59.")
    }
    if (period.isEmpty() && time.groupValues[2].isEmpty() && hour in 1..12) {
      return PhoneRoute.Clarify("¿De la mañana o de la tarde? Repetí el evento con la hora completa, por ejemplo 17:00.", "calendar")
    }
    if (period in listOf("pm", "de la tarde")) hour = hour % 12 + 12
    if (period == "de la noche") hour = if (hour == 12) 0 else hour % 12 + 12
    if (period in listOf("am", "de la manana")) hour %= 12
    val current = now()
    val date = (current.clone() as Calendar).apply { isLenient = false }
    try {
      when (val day = m.groupValues[2]) {
        "hoy" -> Unit
        "manana" -> date.add(Calendar.DAY_OF_MONTH, 1)
        "pasado manana" -> date.add(Calendar.DAY_OF_MONTH, 2)
        else -> {
          val parts = day.removePrefix("el ").trim().split('/').map { it.toInt() }
          // Clear first: invalid dates must not normalize using fields from the current day.
          date.clear()
          date.set(parts.getOrNull(2) ?: current.get(Calendar.YEAR), parts[1] - 1, parts[0])
        }
      }
      date.set(Calendar.HOUR_OF_DAY, hour); date.set(Calendar.MINUTE, minute)
      date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
      val begin = date.timeInMillis
      val offset = date.timeZone.getOffset(begin)
      for (distance in listOf(-129600000L, 129600000L)) {
        val otherOffset = date.timeZone.getOffset(begin + distance)
        if (otherOffset == offset) continue
        val alternative = (date.clone() as Calendar).apply { timeInMillis = begin + offset - otherOffset }
        if (listOf(Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.HOUR_OF_DAY, Calendar.MINUTE).all { alternative.get(it) == date.get(it) }) {
          return PhoneRoute.Unsupported("Esa hora ocurre dos veces por un cambio de horario. Elegí otra hora o prepará el evento directamente en Calendario.")
        }
      }
      if (begin <= current.timeInMillis) return PhoneRoute.Unsupported("Ese horario ya pasó. Indicá una fecha y hora futuras para el evento.")
      return PhoneRoute.Local(PhoneCommand.CalendarEvent(title, begin, Math.addExact(begin, durationSeconds.toLong() * 1000)))
    } catch (_: IllegalArgumentException) {
      return PhoneRoute.Unsupported("Esa fecha u hora no existe en la zona horaria del teléfono. Revisá el evento.")
    }
  }
}
