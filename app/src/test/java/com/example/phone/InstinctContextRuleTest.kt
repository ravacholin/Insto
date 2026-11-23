package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class InstinctContextRuleTest {
  @Test fun `the documented command validates with AND and rejects nested actions`() {
    val parser = PhoneCommandParser()
    val spec = (InstinctProposalValidator.check(ContextRuleSpec.EXAMPLE, parser) as ContextualCheck).spec
    assertEquals(30, spec.days)
    assertEquals("avisarme Batería baja", spec.action)
    assertEquals(true, spec.evaluate(ContextSnapshot(battery = 14, charging = false)))
    assertEquals(false, spec.evaluate(ContextSnapshot(battery = 15, charging = false)))
    assertEquals(false, spec.evaluate(ContextSnapshot(battery = 14, charging = true)))
    assertNull(spec.evaluate(ContextSnapshot(battery = 14)))
    for (line in listOf(
      "cuando cargando y no cargando: subir el volumen",
      "cuando wifi conectado y con internet y cargando y sin auriculares: subir el volumen",
      "cuando al desbloquear: cuando sin internet: subir el volumen",
      "cuando al desbloquear: preguntar ¿Café? | Sí | No",
      "cuando al desbloquear: llamar a Ana",
      "cuando cargando por 31 días: subir el volumen",
    )) assertTrue(line, InstinctProposalValidator.check(line, parser) is ProposalCheck.Invalid)
  }

  @Test fun `conditions support hours crossing midnight and local Android signals`() {
    val spec = requireNotNull(ContextRuleSpec.parse(
      "cuando horario 22:00 a 07:00 y wifi conectado y reproduciendo multimedia por 2 días: pausar la música"))
    assertEquals(2, spec.days)
    assertEquals(true, spec.evaluate(ContextSnapshot(minute = 6 * 60, wifi = true, media = true)))
    assertEquals(false, spec.evaluate(ContextSnapshot(minute = 7 * 60, wifi = true, media = true)))
    val unlock = requireNotNull(ContextRuleSpec.parse("cuando al desbloquear y evento en curso y auriculares conectados: apagar la linterna"))
    val snapshot = ContextSnapshot(calendar = true, headphones = true)
    assertEquals(false, unlock.evaluate(snapshot))
    assertEquals(true, unlock.evaluate(snapshot.copy(unlock = true)))
  }

  @Test fun `a brief change to either side cannot produce a second edge`() {
    val spec = requireNotNull(ContextRuleSpec.parse(ContextRuleSpec.EXAMPLE))
    val rule = ContextRule(spec, expiresAt = 300_000)
    val yes = ContextSnapshot(battery = 13, charging = false)
    val no = yes.copy(charging = true)
    val first = rule.evaluate(yes, 1_000, budget = true)
    assertFalse(first.fire)
    val fired = first.rule.evaluate(yes, 6_000, budget = true)
    assertTrue(fired.fire)
    val bounce = fired.rule.evaluate(no, 120_000, budget = true)
    assertFalse(bounce.fire)
    assertTrue(bounce.rule.matched)
    assertFalse(bounce.rule.evaluate(yes, 121_000, budget = true).fire)
    assertFalse(rule.evaluate(yes, 300_000, budget = true).fire)
    assertFalse(first.rule.evaluate(yes, 6_000, budget = false).fire)
  }

  @Test fun `unlock requires companion state to be stable`() {
    val spec = requireNotNull(ContextRuleSpec.parse("cuando al desbloquear y cargando: apagar la linterna"))
    val rule = ContextRule(spec, expiresAt = 120_000)
    val entered = rule.evaluate(ContextSnapshot(charging = true, unlock = true), 1_000, budget = true)
    assertFalse(entered.fire)
    assertFalse(entered.rule.evaluate(ContextSnapshot(charging = false, unlock = true), 5_999, budget = true).fire)
    assertTrue(entered.rule.evaluate(ContextSnapshot(charging = true, unlock = true), 6_000, budget = true).fire)
  }

  @Test fun `unlock pulses obey durable cooldown without requiring a second sample`() {
    val spec = requireNotNull(ContextRuleSpec.parse("cuando al desbloquear: apagar la linterna"))
    val rule = ContextRule(spec, expiresAt = 120_000)
    val pulse = ContextSnapshot(unlock = true)
    val fired = rule.evaluate(pulse, 1, budget = true)
    assertTrue(fired.fire)
    assertFalse(fired.rule.evaluate(pulse, 15_000, budget = true).fire)
    assertTrue(fired.rule.evaluate(pulse, 65_000, budget = true).fire)
  }
}
