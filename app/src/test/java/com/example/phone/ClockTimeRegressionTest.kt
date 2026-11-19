package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ClockTimeRegressionTest {
  private fun clock(hour: Int = 12, minute: Int = 0, zone: String = "UTC") =
    Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
      clear()
      set(2026, Calendar.NOVEMBER, 19, hour, minute)
    }

  @Test fun reportedSilenceRequestRunsLocallyWithOrWithoutGemini() = runTest {
    val now = clock()
    val text = "silenciar teléfono hasta 12.20 pm"
    val parsed = PhoneCommandParser { now }.parse(text)
    val expected = PhoneCommand.TemporaryMode(ModeKind.SILENT, 1200, deadlineMillis = now.timeInMillis + 1_200_000)
    assertEquals(PhoneRoute.Local(expected), parsed)
    for (enabled in listOf(true, false)) {
      val coordinator = GeminiCoordinator(
        HybridPhoneRouter({ enabled }, DecisionClassifier { error("No provider required") }),
        StructuredCommandExtractor { error("No extraction required") })
      assertEquals(InterpretationResult.Direct(expected),
        coordinator.interpret(InterpretationRequest(text, now = now), parsed))
    }
    assertEquals(expected, DraftPlan.fromCommands(listOf(expected), now).steps.single().complete(clock(12, 10)))
  }

  @Test fun modesDistinguishInvalidAmbiguousAndPastTimes() {
    val now = clock(12, 30)
    val parser = PhoneCommandParser { now }
    fun mode(text: String) = (parser.parse(text) as PhoneRoute.Local).command as PhoneCommand.TemporaryMode
    val expected = clock(12, 20).apply { add(Calendar.DATE, 1) }.timeInMillis
    assertEquals(expected, mode("silenciar teléfono hasta 12.20 pm").deadlineMillis)
    assertEquals(expected, mode("No molestar hasta las 12:20 p. m.").deadlineMillis)
    assertEquals(ModeKind.DND, mode("No molestar hasta las 12:20 p. m.").kind)
    assertEquals(20, mode("poné el volumen al 20% hasta las 12:20 pm").percent)
    val messages = listOf("25:00", "siete", "hoy a las 12:20 pm").map {
      when (val route = parser.parse("silenciar teléfono hasta $it")) {
        is PhoneRoute.Clarify -> route.prompt
        is PhoneRoute.Unsupported -> route.message
        else -> error("Expected a message for $it")
      }
    }
    assertEquals(3, messages.toSet().size)
    assertTrue(parser.parse("silenciar teléfono hasta mañana 23:00") is PhoneRoute.Unsupported)
  }

  @Test fun alarmsAndCalendarUseTheSameClockNotation() {
    val now = clock()
    val parser = PhoneCommandParser { now }
    for ((text, hour, minute) in listOf(Triple("7:30", 7, 30), Triple("07:30", 7, 30),
      Triple("12.20 pm", 12, 20), Triple("12:20 p. m.", 12, 20), Triple("12.20 a. m.", 0, 20),
      Triple("siete y veinticinco de la tarde", 19, 25))) {
      val canonical = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
      assertEquals(text, PhoneRoute.Local(PhoneCommand.Alarm(hour, minute)), parser.parse("alarma a las $text"))
      val event = (parser.parse("agendá reunión mañana a las $text") as PhoneRoute.Local).command as PhoneCommand.CalendarEvent
      assertEquals(clock(hour, minute).apply { add(Calendar.DATE, 1) }.timeInMillis, event.beginMillis)
      assertEquals(canonical, select(parser, "consultá mi agenda mañana a las $text").at)
      assertEquals(canonical, select(parser, "eliminá el evento de mañana a las $text").at)
      assertEquals(canonical, select(parser, "cambiá la reunión de mañana para las $text").newTime)
    }
    val edit = select(parser, "cambiá la reunión de mañana a las 7:30 para las 12.20 pm")
    assertEquals("07:30", edit.at)
    assertEquals("12:20", edit.newTime)
    assertTrue(parser.parse("alarma a las siete") is PhoneRoute.Clarify)
    assertTrue(parser.parse("alarma a las 13 pm") is PhoneRoute.Clarify)
  }

  @Test fun shortRepliesKeepTheOriginalScheduleFields() {
    val now = clock()
    val session = ConversationSession { now }
    assertTrue(session.accept("agendá  \"Reunión 2.0\" mañana durante una hora y treinta minutos, con descripción: Revisar v1.2.") is ConversationResult.Prompt)
    val event = (session.accept("12.20 pm") as ConversationResult.Plan).commands.single() as PhoneCommand.CalendarEvent
    assertEquals("Reunión 2.0", event.title)
    assertEquals("Revisar v1.2.", event.description)
    assertEquals(5_400_000L, event.endMillis - event.beginMillis)
    val registry = LocalIntentRegistry.default
    val current = Calendar.getInstance()
    val alarm = registry.reply("alarm", "12.20 pm", "alarma mañana a las doce llamada Trabajo" to current)
    assertEquals(PhoneRoute.Local(PhoneCommand.Alarm(12, 20, label = "trabajo",
      date = CalendarSelection.iso((current.clone() as Calendar).apply { add(Calendar.DATE, 1) }))), alarm)
    val deletion = registry.reply("calendar_delete", "18:30", "eliminá el evento de mañana a las seis" to current)
    assertEquals("18:30", CalendarSelection.decode((deletion as PhoneRoute.Local).command as PhoneCommand.Platform).at)
    val edit = registry.reply("calendar_update", "18.30",
      "cambiá la reunión de mañana a las 17:00 para las seis" to current)
    val selection = CalendarSelection.decode((edit as PhoneRoute.Local).command as PhoneCommand.Platform)
    assertEquals("17:00", selection.at)
    assertEquals("18:30", selection.newTime)
    val tomorrow = CalendarSelection.iso((current.clone() as Calendar).apply { add(Calendar.DATE, 1) })
    for ((action, source, expected) in listOf(
      Triple(PlatformAction.CALENDAR_QUERY, "consultá mi agenda mañana a las", CalendarSelection(from = tomorrow, at = "18:30")),
      Triple(PlatformAction.CALENDAR_DELETE, "eliminá el evento de mañana", CalendarSelection(from = tomorrow, at = "18:30")),
      Triple(PlatformAction.CALENDAR_UPDATE, "cambiá la reunión de mañana", CalendarSelection(title = "reunion", from = tomorrow, newTime = "18:30")),
      Triple(PlatformAction.CALENDAR_UPDATE, "cambiá la reunión de mañana a las 16:00 para las",
        CalendarSelection(title = "reunion", from = tomorrow, at = "16:00", newTime = "18:30")))) {
      val parser = PhoneCommandParser { current }
      assertTrue(source, parser.parse(source) is PhoneRoute.Clarify)
      val route = registry.reply(action.name.lowercase(java.util.Locale.ROOT), "18.30", source to current) as PhoneRoute.Local
      assertEquals(source, expected, CalendarSelection.decode(route.command as PhoneCommand.Platform))
    }
  }

  @Test fun structuredDraftsAcceptTheClockReplyAndKeepOtherFields() {
    val now = clock()
    val draft = DraftPlan(listOf(CommandDraft("s1", org.json.JSONObject()
      .put("type", "calendar_update").put("title", "Reunión").put("at", "07:30").put("from", "2026-11-20"))))
    val updated = draft.reply("12.20 pm", now) as InterpretationResult.Plan
    val selection = CalendarSelection.decode(updated.commands.single() as PhoneCommand.Platform)
    assertEquals(CalendarSelection(title = "Reunión", from = "2026-11-20", at = "07:30", newTime = "12:20"), selection)
  }

  @Test fun aClockReplyCompletesAnAmbiguousRestorationWithoutLosingModeOrDay() {
    val now = clock()
    val session = ConversationSession { now }
    assertTrue(session.accept("poné el volumen al 20% hasta mañana a las doce") is ConversationResult.Prompt)
    val plan = session.accept("12.20 a. m.") as ConversationResult.Plan
    val tomorrow = clock(0, 20).apply { add(Calendar.DATE, 1) }
    assertEquals(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 44_400, 20,
      deadlineMillis = tomorrow.timeInMillis), plan.commands.single())
    assertTrue(session.accept("silenciar teléfono hasta hoy") is ConversationResult.Prompt)
    assertTrue(session.accept("11:20") is ConversationResult.Prompt)
    val today = session.accept("12:20") as ConversationResult.Plan
    assertEquals(clock(12, 20).timeInMillis, (today.commands.single() as PhoneCommand.TemporaryMode).deadlineMillis)
  }

  @Test fun nonexistentAndRepeatedWallTimesAreRejected() {
    val now = clock(0, zone = "America/New_York").apply { set(2026, Calendar.MARCH, 8, 0, 0) }
    val parser = PhoneCommandParser { now }
    assertTrue(parser.parse("silenciar teléfono hasta 2.30 am") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("agendá reunión hoy a las 2.30 am") is PhoneRoute.Unsupported)
    now.set(2026, Calendar.NOVEMBER, 1, 0, 0)
    assertTrue(parser.parse("silenciar teléfono hasta 1.30 am") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("agendá reunión hoy a las 1.30 am") is PhoneRoute.Unsupported)
  }

  private fun select(parser: PhoneCommandParser, text: String) =
    CalendarSelection.decode((parser.parse(text) as PhoneRoute.Local).command as PhoneCommand.Platform)
}
