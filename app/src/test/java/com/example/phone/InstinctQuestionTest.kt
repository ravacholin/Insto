package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class InstinctQuestionTest {
  private val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(2026, Calendar.NOVEMBER, 23, 23, 30)
  }
  private val hour = 3_600_000L
  private val order = "preguntar ¿Café o té? | Café | Té"
  private fun parse(line: String) = InstinctQuestion.parse(line, now)

  @Test fun `decidir binds one validated background action to each button`() {
    val line = "decidir ¿Qué preferís? | Silencio => silenciar el teléfono | No molestar => activar no molestar"
    val parser = PhoneCommandParser()
    val result = InstinctProposalValidator.check(line, parser) as ProposalCheck.Question
    assertEquals(listOf("Silencio", "No molestar"), result.question.options)
    assertEquals(listOf("silenciar el teléfono", "activar no molestar"), result.question.actions)
    assertEquals(result.question, InstinctQuestion.decode(result.question.encode(), result.question.expiresAt))
    for (invalid in listOf("abrir spotify", "cancelar programadas", "en una hora subir el volumen", "llamar a Ana")) {
      assertTrue(invalid, InstinctProposalValidator.check(line.replace("activar no molestar", invalid), parser) is ProposalCheck.Invalid)
    }
    assertTrue(InstinctProposalValidator.check("probar $line", parser) is ProposalCheck.Query)
  }

  @Test fun `preserves question and labels while parsing expiry`() {
    val question = requireNotNull(parse(order))
    assertEquals("¿Café o té?", question.text)
    assertEquals(listOf("Café", "Té"), question.options)
    assertEquals(now.timeInMillis + 2 * hour, question.expiresAt)
    assertEquals(now.timeInMillis + hour, parse("$order hasta las 00:30")?.expiresAt)
    assertEquals(now.timeInMillis + 24 * hour, parse("$order en 24 horas")?.expiresAt)
    assertEquals(question, InstinctQuestion.decode(question.encode(), question.expiresAt))
  }

  @Test fun `rejects malformed or oversized questions`() {
    for (line in listOf("preguntar", "preguntar Texto | Sí", "$order | Agua | Mate", "$order | ",
      "$order en 25 horas", "$order en 0 horas", "$order hasta las 29:00",
      "preguntar ${"x".repeat(141)} | Sí | No", "$order | ${"x".repeat(25)}",
      "preguntar Texto\nextra | Sí | No", "preguntar Texto | Sí | #insto volumen al 30 %")) assertNull(line, parse(line))
  }

  @Test fun `validator recognizes the action and its probe but forbids scheduling it`() {
    val parser = PhoneCommandParser()
    assertTrue(InstinctProposalValidator.check(order, parser, now) is ProposalCheck.Question)
    val probe = InstinctProposalValidator.check("probar $order", parser, now) as ProposalCheck.Query
    assertEquals(order, probe.argument)
    assertTrue(InstinctProposalValidator.check("en 10 minutos $order", parser, now) is ProposalCheck.Invalid)
  }

  @Test fun `limits use live pending questions and the rolling hour`() {
    assertNull(InstinctQuestion.limit(2, 4))
    assertNotNull(InstinctQuestion.limit(3, 4))
    assertNotNull(InstinctQuestion.limit(0, 5))
  }

  @Test fun `expiry wins over a late answer and terminal rows never reopen`() {
    val question = requireNotNull(parse(order))
    val until = question.expiresAt
    assertEquals(QuestionOutcome(ExecutionState.ANSWERED, "Té"), question.outcome(ExecutionState.ASKING, until - 1, 1))
    assertEquals(QuestionOutcome(ExecutionState.EXPIRED, "vencida"), question.outcome(ExecutionState.ASKING, until, 1))
    assertNull(question.outcome(ExecutionState.ANSWERED, until - 1, 0))
    assertNull(question.outcome(ExecutionState.QUESTION_CONFIRM, until - 1, 0))
    assertFalse(ExecutionTransitions.allowed(ExecutionState.ASKING, ExecutionState.DISPATCHING))
    assertFalse(ExecutionTransitions.allowed(ExecutionState.ANSWERED, ExecutionState.ASKING))
    assertTrue(ExecutionTransitions.allowed(ExecutionState.QUESTION_CONFIRM, ExecutionState.ASKING))
    assertTrue(ExecutionTransitions.allowed(ExecutionState.ASKING, ExecutionState.ANSWERED))
    val row = ExecutionRecord("e123", "id", 0, ExecutionTransport.PUSH, order, 1, 1, ExecutionState.ASKING, deadline = until)
    assertEquals(ExecutionRecoveryPolicy.Action.RefreshQuestion, ExecutionRecoveryPolicy.decide(row, until - 1))
    assertEquals(ExecutionRecoveryPolicy.Action.RefreshQuestion, ExecutionRecoveryPolicy.decide(row, until))
    assertEquals(ExecutionRecoveryPolicy.Action.Leave, ExecutionRecoveryPolicy.decide(row.copy(state = ExecutionState.ANSWERED), until))
  }

  @Test fun `quiet hours cross midnight and stop at their end`() {
    val quiet = QuietHours(22 * 60, 7 * 60)
    val end = now.timeInMillis + 7 * hour + hour / 2
    assertEquals(end, InstinctQuestion.quietEnd(quiet, now.timeInMillis, now.timeZone))
    assertEquals(end, InstinctQuestion.quietEnd(quiet, end - 1, now.timeZone))
    assertNull(InstinctQuestion.quietEnd(quiet, end, now.timeZone))
  }

  @Test fun `a reposted WhatsApp question keeps its journal claim`() {
    val hash = "0123456789ab"
    val sent = now.timeInMillis
    assertEquals(ExecutionIds.whatsapp(hash, sent, ""), ExecutionIds.whatsappQuestion(hash, sent))
    assertNotEquals(ExecutionIds.whatsappQuestion(hash, sent), ExecutionIds.whatsappQuestion(hash, sent + 1))
    assertEquals(ExecutionIds.whatsapp(hash, 0L, ""), ExecutionIds.whatsappQuestion(hash, null))
  }

  @Test fun `a retried response uses the execution id and the same minimal payload`() {
    val text = "¿" + "x".repeat(135) + "?"
    val question = requireNotNull(parse("preguntar $text | Café | Té"))
    val row = ExecutionRecord("e123", "private-message-id", 0, ExecutionTransport.WHATSAPP, order,
      now.timeInMillis, now.timeInMillis + 1_000, ExecutionState.ANSWERED,
      command = question.encode(), result = "Té", deadline = question.expiresAt)
    val event = requireNotNull(InstinctLogEvents.questionResponse(row))
    assertEquals("respuesta", event.kind)
    assertEquals(row.executionId, event.id)
    assertEquals(text, event.order)
    assertEquals("Té", event.detail)
    assertEquals("2026-11-23T23:30:01Z", event.at)
    assertEquals("", event.message)
    assertEquals("", event.protocol)
    assertEquals(event, InstinctLogEvents.questionResponse(row))
    assertTrue(ExecutionText.line(row, row.updatedAt) { "23:30" }.endsWith(" · Té"))
    assertFalse(ExecutionText.line(row, row.updatedAt, false) { "23:30" }.contains("Té"))
  }

  @Test fun `unanswered questions lower Solo without loosening Nunca`() {
    val category = InstinctCategory.PREGUNTAS
    val ledger = InstinctTrustLedger({ now.timeInMillis })
    ledger.record(category, TrustEvent.DESCARTADA)
    repeat(2) { ledger.record(category, TrustEvent.VENCIDA) }
    assertNotNull(ledger.lowering(category, InstinctAutonomy.SOLO))
    assertNull(ledger.lowering(category, InstinctAutonomy.NUNCA))
    assertEquals(InstinctAutonomy.SOLO, InstinctAutonomyPolicy().level(category))
  }
}
