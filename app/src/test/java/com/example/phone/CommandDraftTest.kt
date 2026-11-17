package com.example.phone

import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandDraftTest {
  private fun step(id: String, json: String) = CommandDraft(id, JSONObject(json))
  @Test fun missingDurationKeepsLabelAndCompletesWithoutNetwork() {
    val plan = DraftPlan(listOf(step("s1", """{"type":"timer","label":"Fideos"}""")))
    val result = plan.reply("diez minutos", Calendar.getInstance()) as InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Timer(600, "Fideos")), result.commands)
  }
  @Test fun ambiguousCorrectionChoosesAStepBeforeChangingAnything() {
    val plan = DraftPlan.fromCommands(listOf(PhoneCommand.Timer(600, "uno"), PhoneCommand.Timer(900, "dos")))
    val question = plan.reply("mejor veinte minutos", Calendar.getInstance()) as InterpretationResult.Incomplete
    assertEquals(PhoneCommand.Timer(600, "uno"), question.draft.steps[0].complete(Calendar.getInstance()))
    val result = question.draft.reply("el segundo", Calendar.getInstance()) as InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Timer(600, "uno"), PhoneCommand.Timer(1200, "dos")), result.commands)
  }
  @Test fun arbitraryFieldsAndOutOfRangePartialValuesAreRejected() {
    for (body in listOf("""{"type":"timer","seconds":-1}""", """{"type":"torch","intent":"evil"}""", """{"type":"volume","operation":"SET","percent":"20"}""", """{"type":"alarm","hour":19,"minute":0,"period":"AM"}""")) {
      assertThrows(Exception::class.java) { step("s1", body) }
    }
  }
  @Test fun percentageIsRequiredOnlyForSetVolumeAndMediaVolumeMode() {
    assertEquals(PhoneCommand.Volume(VolumeOp.DOWN), step("s1", """{"type":"volume","operation":"DOWN"}""").complete(Calendar.getInstance()))
    assertNull(step("s1", """{"type":"volume","operation":"SET"}""").complete(Calendar.getInstance()))
    assertEquals(PhoneCommand.TemporaryMode(ModeKind.SILENT, 60), step("s1", """{"type":"temporary_mode","kind":"SILENT","seconds":60}""").complete(Calendar.getInstance()))
    assertNull(step("s1", """{"type":"temporary_mode","kind":"MEDIA_VOLUME","seconds":60}""").complete(Calendar.getInstance()))
  }
  @Test fun calendarPreviewCanBeCorrectedWithoutKeepingStaleEpochFields() {
    val now = Calendar.getInstance().apply { clear(); set(2026, 10, 17, 12, 0) }
    val begin = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 17) }.timeInMillis
    val draft = DraftPlan.fromCommands(listOf(PhoneCommand.CalendarEvent("Reunión", begin, begin + 3600000)))
    val result = draft.reply("mejor a las 18:00", now) as InterpretationResult.Plan
    val event = result.commands.single() as PhoneCommand.CalendarEvent
    assertEquals(begin + 3600000, event.beginMillis)
    assertEquals("Reunión", event.title)
  }
  @Test fun temporaryModeFromValidatedCommandKeepsItsDuration() {
    val draft = DraftPlan.fromCommands(listOf(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)))
    assertEquals(listOf(PhoneCommand.TemporaryMode(ModeKind.SILENT, 7200)),
      (draft.reply("mejor dos horas", Calendar.getInstance()) as InterpretationResult.Plan).commands)
  }
  @Test fun unknownCalendarCorrectionReachesSemanticsInsteadOfBecomingAnInvalidTime() {
    val now = Calendar.getInstance().apply { clear(); set(2026, 10, 17, 12, 0) }
    val plan = DraftPlan(listOf(step("s1", """{"type":"calendar","title":"Reunión","date":"18/11/2026","time":"17:00"}""")))
    assertNull(plan.reply("cambiá el título a Cumpleaños", now))
  }
  @Test fun calendarPeriodCorrectionReplacesThePreviousPeriod() {
    val now = Calendar.getInstance().apply { clear(); set(2026, 10, 17, 12, 0) }
    val plan = DraftPlan(listOf(step("s1", """{"type":"calendar","title":"Reunión","date":"18/11/2026","time":"cinco de la tarde"}""")))
    val result = plan.reply("mejor de la mañana", now) as InterpretationResult.Plan
    assertEquals(5, Calendar.getInstance().apply { timeInMillis = (result.commands.single() as PhoneCommand.CalendarEvent).beginMillis }.get(Calendar.HOUR_OF_DAY))
  }
}
