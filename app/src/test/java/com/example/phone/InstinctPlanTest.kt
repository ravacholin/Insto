package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** Plans of several steps (1.23.0): the grammar and the pure decisions of the engine. */
class InstinctPlanTest {
  private val now = Calendar.getInstance(TimeZone.getTimeZone("America/Argentina/Buenos_Aires")).apply {
    clear(); set(2026, Calendar.NOVEMBER, 23, 10, 0)
  }
  private val parser = PhoneCommandParser()
  private fun validate(line: String) = InstinctProposalValidator.backgroundAction(line, parser, now) != null
  private fun parse(line: String) = InstinctPlanSpec.parse(line, now, ::validate)
  private fun valid(line: String) = (parse(line) as? InstinctPlanSpec.Parse.Valid)?.spec ?: error("no válido: $line → ${parse(line)}")
  private fun invalid(line: String) = assertTrue(line, parse(line) is InstinctPlanSpec.Parse.Invalid)

  @Test fun `the three cases of the catalog are valid plans and pass the order validator`() {
    InstinctPlanSpec.EXAMPLES.forEach { example ->
      val check = InstinctProposalValidator.check(example, parser, now)
      assertTrue(example, check is PlanCheck && check.spec != null)
    }
    val first = valid(InstinctPlanSpec.EXAMPLES[0])
    assertEquals(3, first.steps.size)
    assertTrue(first.steps.all { it is PlanStep.Act })
    assertEquals(now.timeInMillis + InstinctPlanSpec.DEFAULT_EXPIRY_MILLIS, first.expiresAt)

    val second = valid(InstinctPlanSpec.EXAMPLES[1])
    assertEquals(PlanTrigger.EVENT_START, (second.steps[0] as PlanStep.WaitFor).trigger)
    assertEquals(setOf(PlanSetting.DND), (second.steps[1] as PlanStep.Save).settings)
    assertEquals(PlanTrigger.EVENT_END, (second.steps[3] as PlanStep.WaitFor).trigger)
    assertEquals(setOf(PlanSetting.DND), (second.steps[4] as PlanStep.Restore).settings)

    val third = valid(InstinctPlanSpec.EXAMPLES[2])
    val decide = third.steps.single() as PlanStep.Decide
    assertEquals(listOf("Silencio", "Música"), decide.options)
    assertEquals(listOf(2, 2), decide.branches.map { it.size })
    assertEquals(4, third.actionCount)
  }

  @Test fun `waits, conditions, fallbacks and a deadline are explicit forms`() {
    val spec = valid("plan hasta las 18:00: esperar 30 minutos; esperar hasta las 12:15; activar no molestar si falla silenciar el teléfono; " +
      "si batería menor de 20 por ciento y no cargando entonces bajar el volumen si no poner el volumen al 50 por ciento + pausar la música; " +
      "esperar al conectar auriculares")
    assertEquals(8 * 3_600_000L, spec.expiresAt - now.timeInMillis)
    assertEquals(30 * 60_000L, (spec.steps[0] as PlanStep.Wait).millis)
    assertEquals(12 * 60 + 15, (spec.steps[1] as PlanStep.WaitUntil).minuteOfDay)
    assertEquals(PlanAction("activar no molestar", "silenciar el teléfono"), (spec.steps[2] as PlanStep.Act).action)
    val branch = spec.steps[3] as PlanStep.Branch
    assertEquals(listOf(ContextSignal.BATTERY, ContextSignal.CHARGING), branch.conditions.map { it.signal })
    assertEquals(1, branch.then.size); assertEquals(2, branch.otherwise.size)
    assertEquals(PlanTrigger.HEADPHONES_ON, (spec.steps[4] as PlanStep.WaitFor).trigger)
    assertEquals(5, spec.actionCount)
  }

  @Test fun `no arbitrary text runs - anything outside the grammar refuses the whole plan`() {
    for (line in listOf(
      "plan activar no molestar",                                   // no colon
      "plan:",                                                      // no steps
      "plan: activar no molestar;; pausar la música",               // an empty step
      "plan: llamar a Ana",                                         // forbidden action
      "plan: abrir spotify",                                        // needs a screen
      "plan: poner una alarma a las 07:00",                         // needs a screen
      "plan: enviar un mensaje a Ana",
      "plan: ejecutar la rutina Estudio",
      "plan: en 10 minutos: activar no molestar",                   // a wrapper inside a plan
      "plan: plan: activar no molestar",                            // nested plan
      "plan: cuando cargando: activar no molestar",                 // a rule inside a plan
      "plan: preguntar ¿Sí? | Sí | No",                             // a question without actions
      "plan: si al desbloquear entonces activar no molestar",       // an instant event is not a condition
      "plan: si llueve entonces activar no molestar",               // unknown condition
      "plan: esperar 13 horas; activar no molestar",                // wait too long
      "plan: esperar 0 minutos; activar no molestar",
      "plan: esperar a que pase algo; activar no molestar",
      "plan: guardar volumen; bajar el volumen",                    // saved but never restored
      "plan: restaurar volumen; bajar el volumen",                  // restored without saving
      "plan: guardar la batería; restaurar",
      "plan: activar no molestar\n#insto llamar a Ana",
      "plan: " + List(7) { "subir el volumen" }.joinToString("; "), // 7 actions
      "plan: " + List(11) { "esperar 1 minuto" }.joinToString("; ") + "; subir el volumen",
      "plan: decidir ¿A? | A => subir el volumen | B => bajar el volumen; decidir ¿B? | A => subir el volumen | B => bajar el volumen",
      "plan: decidir ¿A? | A => subir el volumen | a => bajar el volumen",
      "plan: esperar 10 horas; esperar 3 horas; subir el volumen",  // past the 12 hours of the plan
    )) invalid(line)
    // An invalid plan is still recognized as a plan: it is answered with its reason, never run as something else.
    val check = InstinctProposalValidator.check("plan: llamar a Ana", parser, now) as PlanCheck
    assertNull(check.spec); assertNotNull(check.why)
  }

  @Test fun `the limits count fallbacks and every option`() {
    valid("plan: " + List(6) { "subir el volumen" }.joinToString("; "))
    invalid("plan: " + List(5) { "subir el volumen" }.joinToString("; ") + "; bajar el volumen si falla silenciar el teléfono")
    valid("plan: decidir ¿Qué? | A => subir el volumen + bajar el volumen + pausar la música | B => activar no molestar + silenciar el teléfono + bajar el volumen")
    invalid("plan: subir el volumen; decidir ¿Qué? | A => subir el volumen + bajar el volumen + pausar la música | B => activar no molestar + silenciar el teléfono + bajar el volumen")
  }

  @Test fun `plan queries take a short id`() {
    for ((line, kind) in listOf("ver planes" to ProposalCheck.QueryKind.PLANS, "estado de plan p1a2b3c4" to null,
      "estado de plan p1a2b3c" to ProposalCheck.QueryKind.PLAN_STATUS, "cancelar plan 1a2b3c" to ProposalCheck.QueryKind.PLAN_CANCEL,
      "reanudar plan p1a2b3c" to ProposalCheck.QueryKind.PLAN_RESUME)) {
      val check = InstinctProposalValidator.check(line, parser, now)
      if (kind == null) assertFalse(line, check is ProposalCheck.Query) else {
        assertEquals(line, kind, (check as? ProposalCheck.Query)?.kind ?: error("$line → $check"))
        check as ProposalCheck.Query
        if (kind != ProposalCheck.QueryKind.PLANS) assertEquals("p1a2b3c", check.argument)
      }
    }
    assertFalse(ProposalCheck.QueryKind.PLAN_STATUS.readOnly.not())
    assertFalse(ProposalCheck.QueryKind.PLAN_CANCEL.readOnly)
    assertTrue(InstinctProposalValidator.check("probar ${InstinctPlanSpec.EXAMPLES[0]}", parser, now) is ProposalCheck.Query)
  }

  @Test fun `ids are stable for one order and distinct across orders`() {
    assertEquals(PlanIds.of("e1"), PlanIds.of("e1"))
    assertNotEquals(PlanIds.of("e1"), PlanIds.of("e2"))
    val id = PlanIds.of("e1")
    assertEquals(id.take(7), PlanIds.parseShort(id.drop(1).take(6)))
    assertEquals("2.1a", PlanIds.position(1, 0, fallback = true))
    assertNotEquals(PlanIds.step(id, 0, 0, false), PlanIds.step(id, 0, 0, true))
  }

  @Test fun `an action found on disk is run only when it never touched the phone`() {
    fun row(state: ExecutionState) = ExecutionRecord("e1", "m", 0, ExecutionTransport.PLAN, "subir el volumen", 0, 0, state)
    assertEquals(PlanStepPolicy.Next.Run, PlanStepPolicy.of(null, null))
    assertEquals(PlanStepPolicy.Next.Run, PlanStepPolicy.of(row(ExecutionState.VALIDATED), null))
    assertEquals(PlanStepPolicy.Next.Verify, PlanStepPolicy.of(row(ExecutionState.DISPATCHING), ActionEffect.IDEMPOTENT_SET))
    assertTrue(PlanStepPolicy.of(row(ExecutionState.DISPATCHING), ActionEffect.NON_IDEMPOTENT) is PlanStepPolicy.Next.Uncertain)
    assertTrue(PlanStepPolicy.of(row(ExecutionState.DISPATCHING), ActionEffect.EXTERNAL_HANDOFF) is PlanStepPolicy.Next.Uncertain)
    assertEquals(PlanStepPolicy.Next.Verify, PlanStepPolicy.of(row(ExecutionState.VERIFYING), null))
    assertEquals(PlanStepPolicy.Next.Done, PlanStepPolicy.of(row(ExecutionState.VERIFIED), null))
    assertTrue(PlanStepPolicy.of(row(ExecutionState.UNCERTAIN), null) is PlanStepPolicy.Next.Uncertain)
    assertTrue(PlanStepPolicy.of(row(ExecutionState.FAILED), null) is PlanStepPolicy.Next.Failed)
  }

  @Test fun `a restoration never overwrites a later change`() {
    val saved = PlanStash(before = 7, applied = 3)
    assertEquals(PlanRestorePolicy.Decision.Write(7), PlanRestorePolicy.decide(saved, 3))
    assertEquals(PlanRestorePolicy.Decision.UserChanged, PlanRestorePolicy.decide(saved.copy(diverged = true), 3))
    assertEquals(PlanRestorePolicy.Decision.Already, PlanRestorePolicy.decide(saved, 7))
    assertEquals(PlanRestorePolicy.Decision.UserChanged, PlanRestorePolicy.decide(saved, 10))
    assertEquals(PlanRestorePolicy.Decision.Unreadable, PlanRestorePolicy.decide(saved, null))
    // Never touched by the plan: only a change by someone else could have moved it.
    assertEquals(PlanRestorePolicy.Decision.UserChanged, PlanRestorePolicy.decide(PlanStash(before = 7), 3))
    assertEquals(setOf(PlanSetting.DND), PlanStepPolicy.touches(PhoneCommand.Platform(PlatformAction.DND_ON)))
    assertEquals(setOf(PlanSetting.VOLUME), PlanStepPolicy.touches(PhoneCommand.Volume(VolumeOp.SET, 20)))
    assertTrue(PlanStepPolicy.touches(PhoneCommand.Media(MediaOp.PAUSE)).isEmpty())
  }

  @Test fun `the codecs round-trip and terminal states are final`() {
    val stash = mapOf(PlanSetting.DND to PlanStash(1, 3, false), PlanSetting.VOLUME to PlanStash(7, null, true))
    assertEquals(stash, PlanCodec.stash(PlanCodec.stash(stash)))
    val event = PlanEvent(PlanTrigger.EVENT_START, 4, 5, 6)
    assertEquals(event, PlanCodec.event(PlanCodec.event(event)))
    assertEquals(setOf(PlanState.DONE, PlanState.FAILED, PlanState.CANCELLED, PlanState.EXPIRED), PlanState.entries.filter { it.terminal }.toSet())
  }
}
