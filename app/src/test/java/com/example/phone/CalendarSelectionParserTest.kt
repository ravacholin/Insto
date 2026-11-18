package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CalendarSelectionParserTest {
  private val now = Calendar.getInstance(TimeZone.getTimeZone("America/Argentina/Buenos_Aires")).apply {
    clear()
    set(2026, Calendar.NOVEMBER, 18, 10, 0)
  }
  private val parser = PhoneCommandParser { now }

  @Test fun dailyWeeklyAfternoonAndNextQueriesResolveWithoutProviders() {
    assertEquals(CalendarSelection(from = "2026-11-19"), selection("¿Qué tengo mañana?"))
    assertEquals(CalendarSelection(from = "2026-11-16", until = "2026-11-23"), selection("Mostrame mi agenda de esta semana"))
    assertEquals(CalendarSelection(from = "2026-11-23", afternoon = true), selection("¿Tengo algo el lunes a la tarde?"))
    assertEquals(CalendarSelection(next = true), selection("¿Cuáles son mis próximos eventos?"))
    assertEquals(CalendarSelection(title = "clase", next = true), selection("¿Cuándo es mi próxima clase?"))
    assertEquals(CalendarSelection(title = "reunion", from = "2026-11-20"), selection("¿A qué hora es la reunión del viernes?"))
  }

  @Test fun selectorsSeparateExistingDateFromNewFields() {
    assertEquals(CalendarSelection(title = "reunion", from = "2026-11-20", newTime = "18:00"),
      selection("Pasá la reunión del viernes a las 18:00"))
    assertEquals(CalendarSelection(title = "turno medico", newDate = "2026-11-19"),
      selection("Mové el turno médico al jueves"))
    assertEquals(CalendarSelection(title = "reunion", durationSeconds = 7200),
      selection("Hacé que la reunión dure dos horas"))
    assertEquals(CalendarSelection(from = "2026-11-19", at = "16:00"),
      selection("Eliminá el evento del jueves a las cuatro de la tarde"))
    assertTrue(parser.parse("Pasá la reunión del viernes a las seis") is PhoneRoute.Clarify)
    assertTrue(parser.parse("Cancelá todas las reuniones") is PhoneRoute.Unsupported)
  }

  @Test fun creationKeepsTheDescriptionAndSupportsNamedDates() {
    val route = parser.parse("Agregá un turno médico el 25 de noviembre a las 14:30, con descripción: Llevar DNI") as PhoneRoute.Local
    val command = route.command as PhoneCommand.CalendarEvent
    assertEquals("un turno médico", command.title)
    assertEquals("Llevar DNI", command.description)
    assertEquals(3_600_000L, command.endMillis - command.beginMillis)
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
  }

  @Test fun semanticSelectorUsesSameCodecAndRejectsUnscopedDeletes() {
    val fields = JSONObject().put("type", "calendar_update").put("title", "Reunión")
      .put("from", "2026-11-20").put("newTime", "18:00")
    val command = CommandDraft("s1", fields).complete(now) as PhoneCommand.Platform
    assertEquals(PlatformAction.CALENDAR_UPDATE, command.action)
    assertEquals(CalendarSelection(title = "Reunión", from = "2026-11-20", newTime = "18:00"),
      CalendarSelection.decode(command))
    val incomplete = CommandDraft("s1", JSONObject().put("type", "calendar_delete").put("from", "2026-11-20"))
    assertNull(incomplete.complete(now))
  }

  private fun selection(text: String): CalendarSelection {
    val command = (parser.parse(text) as PhoneRoute.Local).command as PhoneCommand.Platform
    return CalendarSelection.decode(command)
  }
}
