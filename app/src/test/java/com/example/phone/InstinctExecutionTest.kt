package com.example.phone

import org.junit.Assert.*
import org.junit.Test

/** The journal's pure rules (1.20.0): identity, transitions, effects and the recovery's decisions. */
class InstinctExecutionTest {
  private fun row(state: ExecutionState, receivedAt: Long = 0, deadline: Long? = null) = ExecutionRecord(
    ExecutionIds.of("3b241101-e2bb-4255-8caf-4136c566a962", 0), "3b241101-e2bb-4255-8caf-4136c566a962", 0,
    ExecutionTransport.PUSH, "poner el volumen al 40 por ciento", receivedAt, receivedAt, state, deadline = deadline)

  @Test fun `ids are stable per order, independent within a message and distinct across messages`() {
    val a = "3b241101-e2bb-4255-8caf-4136c566a962"
    val b = "8f14e45f-ceea-467a-9575-31f2d6d0f3a1"
    assertEquals(ExecutionIds.of(a, 0), ExecutionIds.of(a.uppercase(), 0))
    assertEquals(3, (0..2).map { ExecutionIds.of(a, it) }.distinct().size)
    assertNotEquals(ExecutionIds.of(a, 0), ExecutionIds.of(b, 0))
    assertTrue(ExecutionIds.of(a, 0).matches(Regex("e[0-9a-f]{20}")))
  }

  @Test fun `short ids round-trip with or without their e`() {
    val id = ExecutionIds.of("x", 1)
    val short = ExecutionIds.short(id)
    assertEquals(9, short.length)
    assertEquals(short, ExecutionIds.parseShort(short))
    assertEquals(short, ExecutionIds.parseShort(short.drop(1).uppercase()))
    assertNull(ExecutionIds.parseShort("e123"))
    assertNull(ExecutionIds.parseShort("#insto"))
  }

  @Test fun `terminal states are never left`() {
    for (from in ExecutionState.entries.filter { it.terminal }) for (to in ExecutionState.entries)
      assertFalse("$from → $to", ExecutionTransitions.allowed(from, to))
  }

  @Test fun `dispatching is reached only from states that never touched the phone, and only once`() {
    val untouched = setOf(ExecutionState.RECEIVED, ExecutionState.VALIDATED, ExecutionState.WAITING, ExecutionState.SCHEDULED, ExecutionState.READY)
    for (from in ExecutionState.entries)
      assertEquals("$from", from in untouched, ExecutionTransitions.allowed(from, ExecutionState.DISPATCHING))
    assertFalse(ExecutionTransitions.allowed(ExecutionState.VERIFYING, ExecutionState.READY))
    assertFalse(ExecutionTransitions.allowed(ExecutionState.DISPATCHING, ExecutionState.READY))
    assertFalse(ExecutionTransitions.allowed(ExecutionState.VALIDATED, ExecutionState.RECEIVED))
  }

  @Test fun `only reads could be repeated - sets, steps and handoffs are classified`() {
    assertEquals(ActionEffect.IDEMPOTENT_SET, ActionEffect.of(PhoneCommand.Volume(VolumeOp.SET, 30)))
    assertEquals(ActionEffect.NON_IDEMPOTENT, ActionEffect.of(PhoneCommand.Volume(VolumeOp.UP)))
    assertEquals(ActionEffect.IDEMPOTENT_SET, ActionEffect.of(PhoneCommand.Torch(true)))
    assertEquals(ActionEffect.IDEMPOTENT_SET, ActionEffect.of(PhoneCommand.Platform(PlatformAction.DND_ON)))
    assertEquals(ActionEffect.NON_IDEMPOTENT, ActionEffect.of(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)))
    assertEquals(ActionEffect.EXTERNAL_HANDOFF, ActionEffect.of(PhoneCommand.Alarm(7, 0)))
    assertEquals(ActionEffect.EXTERNAL_HANDOFF, ActionEffect.of(PhoneCommand.OpenApp("Spotify")))
    assertEquals(ActionEffect.READ_ONLY, ActionEffect.of(PhoneCommand.Platform(PlatformAction.BATTERY)))
  }

  @Test fun `recovery resumes only what never touched the phone and is still in time`() {
    val now = 10 * 60_000L
    assertEquals(ExecutionRecoveryPolicy.Action.Resume, ExecutionRecoveryPolicy.decide(row(ExecutionState.RECEIVED), now))
    assertEquals(ExecutionRecoveryPolicy.Action.Resume, ExecutionRecoveryPolicy.decide(row(ExecutionState.READY), now))
    val late = ExecutionRecoveryPolicy.decide(row(ExecutionState.RECEIVED), now + ExecutionJournalPolicy.RESUME_WINDOW_MILLIS)
    assertEquals(ExecutionState.EXPIRED, (late as ExecutionRecoveryPolicy.Action.Settle).state)
    val pastDeadline = ExecutionRecoveryPolicy.decide(row(ExecutionState.VALIDATED, deadline = now - 1), now)
    assertEquals(ExecutionState.EXPIRED, (pastDeadline as ExecutionRecoveryPolicy.Action.Settle).state)
    assertEquals(ExecutionRecoveryPolicy.Action.Reconcile, ExecutionRecoveryPolicy.decide(row(ExecutionState.SCHEDULED), now))
    for (terminal in ExecutionState.TERMINAL) assertEquals(ExecutionRecoveryPolicy.Action.Leave, ExecutionRecoveryPolicy.decide(row(terminal), now))
  }

  @Test fun `a clock set back never resumes an old order`() {
    val future = row(ExecutionState.RECEIVED, receivedAt = 100_000)
    assertFalse(ExecutionRecoveryPolicy.inTime(future, 50_000))
  }

  @Test fun `two proposals with the same command and different executions are both offered, the same execution once`() {
    val book = ProposalBook({ 0L })
    val volume = PhoneCommand.Volume(VolumeOp.DOWN)
    assertNotNull(book.offer("bajar el volumen", volume, "f", executionId = "e1"))
    assertNotNull(book.offer("bajar el volumen", volume, "f", executionId = "e2"))
    assertNull(book.offer("bajar el volumen", volume, "f", executionId = "e1"))
    // Outside the journal, the rule of 1.19.0: the same command waits once.
    assertNotNull(book.offer("bajar el volumen", volume, "f"))
    assertNull(book.offer("bajar el volumen", volume, "f"))
  }

  @Test fun `ver ejecuciones lines carry no data of the phone and say when nothing ran`() {
    val r = row(ExecutionState.VERIFIED).copy(result = "Volumen multimedia al 40 %.", attribution = ExecutionAttribution.CAUSED, updatedAt = 1)
    val line = ExecutionText.line(r, 2) { "09:30" }
    assertTrue(line, line.startsWith(r.shortId + " verificada (09:30, timbre): poner el volumen al 40 por ciento"))
    assertTrue(line, line.contains("cambiado por esta ejecución"))
    assertEquals("No hay ejecuciones registradas en los últimos 7 días.", ExecutionText.list(emptyList(), 0) { "" })
    assertTrue(ExecutionText.status(null, "e12345678", 0) { "" }.contains("No hay ninguna ejecución e12345678"))
  }

  @Test fun `an interrupted dispatch is read back only when it is a set, and never repeated`() {
    val dispatching = row(ExecutionState.DISPATCHING)
    assertEquals(ExecutionRecoveryPolicy.Action.Verify, ExecutionRecoveryPolicy.decide(dispatching, 0, ActionEffect.IDEMPOTENT_SET))
    for (effect in listOf(ActionEffect.NON_IDEMPOTENT, ActionEffect.EXTERNAL_HANDOFF, null))
      assertEquals(ExecutionState.UNCERTAIN, (ExecutionRecoveryPolicy.decide(dispatching, 0, effect) as ExecutionRecoveryPolicy.Action.Settle).state)
    assertEquals(ExecutionState.FAILED, (ExecutionRecoveryPolicy.decide(dispatching, 0, ActionEffect.READ_ONLY) as ExecutionRecoveryPolicy.Action.Settle).state)
    assertEquals(ExecutionRecoveryPolicy.Action.Verify, ExecutionRecoveryPolicy.decide(row(ExecutionState.VERIFYING), 0))
    // A read-back never says «caused»: satisfied, handed off (it had returned) or uncertain.
    assertEquals(ExecutionState.VERIFIED, ExecutionRecoveryPolicy.afterVerify(dispatching, OutcomeVerdict.VERIFIED))
    assertEquals(ExecutionState.UNCERTAIN, ExecutionRecoveryPolicy.afterVerify(dispatching, OutcomeVerdict.NOT_OBSERVABLE))
    assertEquals(ExecutionState.HANDED_OFF, ExecutionRecoveryPolicy.afterVerify(row(ExecutionState.VERIFYING), OutcomeVerdict.NOT_OBSERVABLE))
    assertEquals(ExecutionState.UNCERTAIN, ExecutionRecoveryPolicy.afterVerify(row(ExecutionState.VERIFYING), OutcomeVerdict.NOT_ACHIEVED))
  }

  @Test fun `a pending confirmation keeps its expiry across a restart`() {
    val waiting = row(ExecutionState.WAITING).copy(updatedAt = 1_000)
    assertEquals(ExecutionRecoveryPolicy.Action.RestoreWaiting, ExecutionRecoveryPolicy.decide(waiting, 1_000 + ExecutionJournalPolicy.WAITING_TTL_MILLIS - 1))
    val late = ExecutionRecoveryPolicy.decide(waiting, 1_000 + ExecutionJournalPolicy.WAITING_TTL_MILLIS)
    assertEquals(ExecutionState.EXPIRED, (late as ExecutionRecoveryPolicy.Action.Settle).state)
  }
}
