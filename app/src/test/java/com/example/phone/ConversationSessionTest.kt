package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
  @Test fun alarmPeriodReplyPreservesMinutesRecurrenceAndLabel() {
    val s = session()
    assertTrue(s.accept("alarma a las siete y media todos los días llamada Trabajo") is ConversationResult.Prompt)
    val result = s.accept("de la mañana") as ConversationResult.Plan
    assertEquals(PhoneCommand.Alarm(7,30,(1..7).toList(),"trabajo"), result.commands.single())
  }
  @Test fun incompleteAlarmAndTimerKeepKnownFieldsAcrossShortReplies() {
    val s = session()
    assertTrue(s.accept("alarma a las siete") is ConversationResult.Prompt)
    val alarm = s.accept("de la tarde") as ConversationResult.Plan
    assertEquals(PhoneCommand.Alarm(19, 0), alarm.commands.single())
    s.clear()
    assertTrue(s.accept("temporizador de un rato para Fideos") is ConversationResult.Prompt)
    val timer = s.accept("diez minutos") as ConversationResult.Plan
    assertEquals(PhoneCommand.Timer(600, "Fideos"), timer.commands.single())
  }
  @Test fun incompleteTemporaryModeKeepsItsKindWithoutNeedingGemini() {
    val s = session()
    assertTrue(s.accept("silenciá el teléfono durante un rato") is ConversationResult.Prompt)
    val plan = s.accept("una hora") as ConversationResult.Plan
    assertEquals(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600), plan.commands.single())
  }
  @Test fun unresolvedCalendarReplyCanUseSemanticsWithOnlyTheActiveDraft() {
    val s = session()
    s.accept("agendá reunión mañana")
    val result = s.accept("a eso de las cinco de la tarde", allowSemantic = true) as ConversationResult.Semantic
    val step = result.draft!!.steps.single().json()
    assertEquals("reunión", step.getString("title"))
    assertEquals("17/11/2026", step.getString("date"))
    assertFalse(step.has("time"))
  }
  private fun session() = ConversationSession { Calendar.getInstance().apply { set(2026, 10, 16, 12, 0, 0) } }
  @Test fun relativeDayIsAnchoredBeforeAReplyAfterMidnight() {
    val clock=Calendar.getInstance().apply {set(2026,10,16,23,0,0)}
    val s=ConversationSession {clock.clone() as Calendar}
    s.accept("agendá reunión mañana")
    clock.add(Calendar.DAY_OF_MONTH,1);clock.set(Calendar.HOUR_OF_DAY,0)
    val plan=s.accept("a las 17:00") as ConversationResult.Plan
    val event=plan.commands.single() as PhoneCommand.CalendarEvent
    assertEquals(17,Calendar.getInstance().apply {timeInMillis=event.beginMillis}.get(Calendar.DAY_OF_MONTH))
  }
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
  @Test fun correctionInheritsPeriodAndNeverDropsExtraDetails() {
    val s=session()
    s.accept("agendá reunión mañana a las cinco de la tarde")
    val corrected=s.accept("mejor a las seis") as ConversationResult.Plan
    val event=corrected.commands.single() as PhoneCommand.CalendarEvent
    assertEquals(18,Calendar.getInstance().apply {timeInMillis=event.beginMillis}.get(Calendar.HOUR_OF_DAY))
    assertTrue(session().accept("agendá reunión mañana a las 17:00 con repetición semanal") is ConversationResult.Prompt)
  }

  @Test fun shortCorrectionWithDurationRetainsAfternoon() {
    val s=session();s.accept("agendá reunión mañana a las cinco de la tarde")
    val revised=s.accept("mejor a las seis y que dure media hora") as ConversationResult.Plan
    val event=revised.commands.single() as PhoneCommand.CalendarEvent
    assertEquals(1800000L,event.endMillis-event.beginMillis)
    assertEquals(18,Calendar.getInstance().apply{timeInMillis=event.beginMillis}.get(Calendar.HOUR_OF_DAY))
  }

  @Test fun quotedTitleCannotBecomeDateOrCondition() {
    for(title in listOf("Hoy y mañana","Informe mañana","Si vuelve")) {
      val result=session().accept("agendá \"$title\" pasado mañana a las 17:00") as ConversationResult.Plan
      val event=result.commands.single() as PhoneCommand.CalendarEvent
      assertEquals(title,event.title)
      assertEquals(18,Calendar.getInstance().apply{timeInMillis=event.beginMillis}.get(Calendar.DAY_OF_MONTH))
    }
  }

  @Test fun ordinalChoiceRefersToVisibleOption() {
    assertEquals(1,PhoneCommandParser.choiceIndex("el segundo"))
    assertEquals(0,PhoneCommandParser.choiceIndex("la primera"))
    assertNull(PhoneCommandParser.choiceIndex("cero"))
  }

  @Test fun affirmationCannotEscapeAnIncompleteCalendarDraft() {
    val s=session();s.accept("agendá reunión mañana a las 17:00")
    assertTrue(s.accept("mejor a las 99:00") is ConversationResult.Prompt)
    assertTrue(s.accept("dale") is ConversationResult.Prompt)
  }

  @Test fun morningAnswerDoesNotReplaceExplicitDate() {
    val s=session();s.accept("agendá reunión el 20/11/2026")
    assertTrue(s.accept("a las cinco") is ConversationResult.Prompt)
    val result=s.accept("de la mañana") as ConversationResult.Plan
    val date=Calendar.getInstance().apply{timeInMillis=(result.commands.single() as PhoneCommand.CalendarEvent).beginMillis}
    assertEquals(20,date.get(Calendar.DAY_OF_MONTH));assertEquals(5,date.get(Calendar.HOUR_OF_DAY))
  }

}
