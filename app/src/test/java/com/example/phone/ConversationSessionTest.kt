package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
  private fun session() = ConversationSession { Calendar.getInstance().apply { set(2026, 10, 16, 12, 0, 0) } }
  @Test fun calendarRetainsFieldsAcrossAnswersAndCorrections() {
    val s = session()
    assertTrue(s.accept("Agendá reunión con Laura mañana") is ConversationResult.Prompt)
    val first = s.accept("a las cinco de la tarde") as ConversationResult.Plan
    assertEquals("reunión con Laura", (first.commands.single() as PhoneCommand.CalendarEvent).title)
    val second = s.accept("mejor a las seis de la tarde y que dure media hora") as ConversationResult.Plan
    val event = second.commands.single() as PhoneCommand.CalendarEvent
    assertEquals(1800000L, event.endMillis - event.beginMillis)
    assertEquals(18, Calendar.getInstance().apply { timeInMillis = event.beginMillis }.get(Calendar.HOUR_OF_DAY))
  }
  @Test fun ambiguousHourAsksWithoutLosingDate() {
    val s = session()
    s.accept("Agendá reunión mañana")
    assertTrue(s.accept("a las cinco") is ConversationResult.Prompt)
    assertTrue(s.accept("de la tarde") is ConversationResult.Plan)
  }
  @Test fun completeSequenceIsPreviewedAndConjunctionInTitleIsNotSplit() {
    val s = session()
    val input = "Bajá el volumen, poné un timer de diez minutos y abrí Spotify"
    val result = s.accept(input) as ConversationResult.Plan
    assertEquals(3, result.commands.size)
    assertTrue(result.commands[1] is PhoneCommand.Timer)
    val quoted = s.accept("Agendá \"Reunión y abrí debate\" mañana a las 17:00") as ConversationResult.Plan
    assertEquals("Reunión y abrí debate", (quoted.commands.single() as PhoneCommand.CalendarEvent).title)
  }
  @Test fun conditionsUnknownStepsAndExcessivePlansCannotPartiallyExecute() {
    for (input in listOf("Bajá el volumen y comprá leche", "Si llego prendé la linterna y abrí Spotify", List(11) { "bajá el volumen" }.joinToString(" y "))) {
      assertTrue(input, session().accept(input) is ConversationResult.Prompt)
    }
  }
  @Test fun cancelClearsDraftAndNewRequestReplacesIt() {
    val s = session()
    s.accept("Agendá reunión mañana")
    assertTrue(s.accept("cancelar") is ConversationResult.Prompt)
    assertEquals(ConversationResult.Pass, s.accept("a las cinco de la tarde"))
  }
}
