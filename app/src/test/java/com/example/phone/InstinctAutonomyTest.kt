package com.example.phone

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** «Autonomía por acción»: categories, levels, quiet hours, the gate and the trust ledger. Pure, no Android. */
class InstinctAutonomyTest {
  private val alarm = PhoneCommand.Alarm(7, 0)
  private val volume = PhoneCommand.Volume(VolumeOp.SET, 30)
  private val torch = PhoneCommand.Torch(true)
  private val silence = PhoneCommand.Platform(PlatformAction.SILENT_ON)
  private val dndOff = PhoneCommand.Platform(PlatformAction.DND_OFF)
  private val noon = 12 * 60

  // Categories

  @Test fun `every allowed example has a category, and a deferred order uses its inner one`() {
    val parser = PhoneCommandParser()
    for (example in InstinctProposalValidator.examples) {
      // Recurring examples on a fixed day within their 30 days; the rest on today («mañana» uses the real clock).
      val day = if (example.contains(" hasta el ")) java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 21, 10, 0, 0) }
        else java.util.Calendar.getInstance()
      val valid = InstinctProposalValidator.check(example, parser, day) as ProposalCheck.Valid
      assertNotNull(example, InstinctCategory.of(valid.command))
    }
    val deferred = InstinctProposalValidator.check("a las 22:00: poner el volumen al 10 por ciento", parser) as ProposalCheck.Valid
    assertEquals(InstinctCategory.VOLUMEN, InstinctCategory.of(deferred.command))
  }

  @Test fun `categories follow the spec`() {
    assertEquals(InstinctCategory.RELOJ, InstinctCategory.of(alarm))
    assertEquals(InstinctCategory.RELOJ, InstinctCategory.of(PhoneCommand.Timer(60)))
    assertEquals(InstinctCategory.SILENCIO, InstinctCategory.of(PhoneCommand.TemporaryMode(ModeKind.DND, 3600)))
    assertNull(InstinctCategory.of(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 3600, 20)))
    assertEquals(InstinctCategory.RUTINAS, InstinctCategory.of(PhoneCommand.Platform(PlatformAction.ROUTINE_RUN, "Estudio")))
    assertEquals(InstinctCategory.APPS, InstinctCategory.of(PhoneCommand.YouTubeSearch("jazz")))
    assertNull(InstinctCategory.of(PhoneCommand.Dial("+541112345678")))
    assertTrue(InstinctCategory.lowersNoise(silence))
    assertTrue(InstinctCategory.lowersNoise(PhoneCommand.Platform(PlatformAction.VIBRATE_ON)))
    assertFalse(InstinctCategory.lowersNoise(dndOff))
  }

  // Policy

  @Test fun `the default is Solo for everything, the behavior before 1_16_0`() {
    val policy = InstinctAutonomyPolicy()
    for (c in InstinctCategory.entries) assertEquals(InstinctAutonomy.SOLO, policy.level(c))
    assertEquals(AutonomyDecision.Run, policy.decide(listOf(alarm), routine = false, minuteOfDay = noon))
  }

  @Test fun `a category asks or refuses with a concrete reason`() {
    val policy = InstinctAutonomyPolicy().with(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR).with(InstinctCategory.LINTERNA, InstinctAutonomy.NUNCA)
    assertEquals(AutonomyDecision.Confirm("la persona pide confirmación para alarmas y temporizadores"), policy.decide(listOf(alarm), false, noon))
    assertEquals(AutonomyDecision.Refuse("la persona no permite esta acción"), policy.decide(listOf(torch), false, noon))
    assertEquals(AutonomyDecision.Run, policy.decide(listOf(volume), false, noon))
    assertTrue(policy.refuses(listOf(torch), false))
    assertFalse(policy.refuses(listOf(alarm), false))
  }

  @Test fun `a routine uses RUTINAS and the strictest of its steps`() {
    val steps = listOf(volume, alarm, torch)
    assertEquals(AutonomyDecision.Run, InstinctAutonomyPolicy().decide(steps, routine = true, minuteOfDay = noon))
    val clock = InstinctAutonomyPolicy().with(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR)
    assertTrue(clock.decide(steps, true, noon) is AutonomyDecision.Confirm)
    val routines = InstinctAutonomyPolicy().with(InstinctCategory.RUTINAS, InstinctAutonomy.CONFIRMAR)
    assertEquals(AutonomyDecision.Confirm("la persona pide confirmación para rutinas"), routines.decide(steps, true, noon))
    // The strictest wins: one step at Nunca refuses the whole routine even if routines are at Confirmar.
    val mixed = routines.with(InstinctCategory.LINTERNA, InstinctAutonomy.NUNCA)
    assertTrue(mixed.decide(steps, true, noon) is AutonomyDecision.Refuse)
    assertTrue(mixed.refuses(steps, true))
  }

  @Test fun `migration from askFirst keeps confirmation for existing categories`() {
    val migrated = InstinctAutonomyPolicy.migrated(askFirst = true)
    for (c in InstinctCategory.entries.filterNot { it == InstinctCategory.PREGUNTAS }) assertEquals(InstinctAutonomy.CONFIRMAR, migrated.level(c))
    assertEquals(InstinctAutonomyPolicy(), InstinctAutonomyPolicy.migrated(askFirst = false))
  }

  @Test fun `the policy round-trips and ignores what it does not know`() {
    val policy = InstinctAutonomyPolicy().with(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR, adjustedAt = 1_700_000_000_000)
      .with(InstinctCategory.APPS, InstinctAutonomy.NUNCA).copy(quiet = QuietHours(23 * 60, 7 * 60), silenceInQuiet = false)
    assertEquals(policy, InstinctAutonomyPolicy.decode(InstinctAutonomyPolicy.encode(policy)))
    val odd = InstinctAutonomyPolicy.decode("""{"levels":{"RELOJ":"CONFIRMAR","XYZ":"SOLO","APPS":"QUIZAS"},"quiet":{"start":9999,"end":1}}""")
    assertEquals(mapOf(InstinctCategory.RELOJ to InstinctAutonomy.CONFIRMAR), odd.levels)
    assertNull(odd.quiet)
  }

  @Test fun `choosing a level by hand clears the automatic mark`() {
    val lowered = InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR, adjustedAt = 5)
    assertEquals(5L, lowered.adjusted[InstinctCategory.VOLUMEN])
    assertNull(lowered.with(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO).adjusted[InstinctCategory.VOLUMEN])
  }

  // The policy only hardens

  @Test fun `the policy can never loosen an existing blocker`() {
    val blockers = listOf("El teléfono estaba bloqueado.", "Para abrir otra app sin tocar nada, Android necesita el servicio de Accesibilidad de Insto activo.",
      "Llegó tarde", "La rutina ya no está habilitada para Instinct o cambió.")
    val decisions = listOf(AutonomyDecision.Run, AutonomyDecision.Confirm("x"), AutonomyDecision.Refuse("y"))
    for (blocker in blockers) for (decision in decisions) {
      var acquired = false
      val gate = InstinctAutonomyGate.combine(blocker, decision) { acquired = true; true }
      assertNotEquals("$blocker / $decision", InstinctAutonomyGate.Gate.Run, gate)
      assertFalse("the budget is never spent on an order that does not run", acquired)
      if (decision is AutonomyDecision.Refuse) assertTrue(gate is InstinctAutonomyGate.Gate.Refuse)
      else assertEquals(InstinctAutonomyGate.Gate.Wait(blocker), gate)
    }
  }

  @Test fun `without blockers the policy decides and the budget comes last`() {
    var acquired = 0
    assertEquals(InstinctAutonomyGate.Gate.Run, InstinctAutonomyGate.combine(null, AutonomyDecision.Run) { acquired++; true })
    assertEquals(InstinctAutonomyGate.Gate.Wait("horas de silencio"),
      InstinctAutonomyGate.combine(null, AutonomyDecision.Confirm("horas de silencio")) { acquired++; true })
    assertEquals(1, acquired)
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctAutonomyGate.BUDGET), InstinctAutonomyGate.combine(null, AutonomyDecision.Run) { false })
  }

  // Quiet hours

  @Test fun `quiet hours that cross midnight`() {
    val night = QuietHours(23 * 60, 7 * 60)
    assertTrue(night.contains(23 * 60))
    assertTrue(night.contains(0))
    assertTrue(night.contains(6 * 60 + 59))
    assertFalse(night.contains(7 * 60))
    assertFalse(night.contains(noon))
    assertFalse(night.contains(22 * 60 + 59))
    val early = QuietHours(0, 7 * 60)
    assertTrue(early.contains(0)); assertFalse(early.contains(7 * 60)); assertFalse(early.contains(23 * 60 + 59))
    assertFalse(QuietHours(5, 5).contains(5))
    assertEquals(7 * 60 + 5, QuietHours.parse("07:05"))
    assertNull(QuietHours.parse("24:00"))
    assertEquals("de 23:00 a 07:00", night.label)
  }

  @Test fun `inside quiet hours Solo waits, except silencing the phone`() {
    val policy = InstinctAutonomyPolicy(quiet = QuietHours(23 * 60, 7 * 60))
    val threeAm = 3 * 60
    assertEquals(AutonomyDecision.Confirm("horas de silencio"), policy.decide(listOf(volume), false, threeAm))
    assertEquals(AutonomyDecision.Run, policy.decide(listOf(volume), false, noon))
    assertEquals(AutonomyDecision.Run, policy.decide(listOf(silence), false, threeAm))
    assertEquals(AutonomyDecision.Run, policy.decide(listOf(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)), false, threeAm))
    // Raising the noise is not exempt, and neither is a routine.
    assertEquals(AutonomyDecision.Confirm("horas de silencio"), policy.decide(listOf(dndOff), false, threeAm))
    assertEquals(AutonomyDecision.Confirm("horas de silencio"), policy.decide(listOf(silence), true, threeAm))
    // Without the exception, silencing waits too.
    assertEquals(AutonomyDecision.Confirm("horas de silencio"), policy.copy(silenceInQuiet = false).decide(listOf(silence), false, threeAm))
    // Quiet hours never loosen Nunca nor change the Confirmar reason.
    val strict = policy.with(InstinctCategory.SILENCIO, InstinctAutonomy.NUNCA)
    assertTrue(strict.decide(listOf(silence), false, threeAm) is AutonomyDecision.Refuse)
  }

  // Trust ledger

  private var now = 1_800_000_000_000L
  private val utc = TimeZone.getTimeZone("UTC")
  private fun ledger() = InstinctTrustLedger({ now }, { utc })
  private fun InstinctTrustLedger.add(category: InstinctCategory, event: TrustEvent, times: Int) = repeat(times) { record(category, event) }
  private val day = InstinctTrustLedger.DAY_MILLIS

  @Test fun `three undos in seven days lower a category, two do not`() {
    val l = ledger()
    l.add(InstinctCategory.VOLUMEN, TrustEvent.EJECUTADA, 20)
    l.add(InstinctCategory.VOLUMEN, TrustEvent.DESHECHA, 2)
    assertNull(l.lowering(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO))
    l.record(InstinctCategory.VOLUMEN, TrustEvent.DESHECHA)
    assertEquals(InstinctTrustLedger.Lowering(3, 20), l.lowering(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO))
    assertNull("another category is untouched", l.lowering(InstinctCategory.LINTERNA, InstinctAutonomy.SOLO))
  }

  @Test fun `thirty percent with at least five executed lowers, below does not`() {
    val l = ledger()
    l.add(InstinctCategory.RELOJ, TrustEvent.EJECUTADA, 7)
    l.add(InstinctCategory.RELOJ, TrustEvent.DESHECHA, 2) // 28.6 %
    assertNull(l.lowering(InstinctCategory.RELOJ, InstinctAutonomy.SOLO))
    val m = ledger()
    m.add(InstinctCategory.RELOJ, TrustEvent.EJECUTADA, 6)
    m.add(InstinctCategory.RELOJ, TrustEvent.DESHECHA, 2) // 33 %
    assertEquals(InstinctTrustLedger.Lowering(2, 6), m.lowering(InstinctCategory.RELOJ, InstinctAutonomy.SOLO))
    val few = ledger()
    few.add(InstinctCategory.RELOJ, TrustEvent.EJECUTADA, 4)
    few.add(InstinctCategory.RELOJ, TrustEvent.DESHECHA, 2) // 50 %, but fewer than 5 executed
    assertNull(few.lowering(InstinctCategory.RELOJ, InstinctAutonomy.SOLO))
    // Exactly 30 %: 3 of 10 (also 3 undos).
    val exact = ledger()
    exact.add(InstinctCategory.MUSICA, TrustEvent.EJECUTADA, 10)
    exact.add(InstinctCategory.MUSICA, TrustEvent.DESHECHA, 3)
    assertNotNull(exact.lowering(InstinctCategory.MUSICA, InstinctAutonomy.SOLO))
  }

  @Test fun `undos older than seven days do not count`() {
    val l = ledger()
    l.add(InstinctCategory.VOLUMEN, TrustEvent.DESHECHA, 3)
    now += 7 * day
    assertNull(l.lowering(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO))
    assertEquals(3, l.count(InstinctCategory.VOLUMEN, TrustEvent.DESHECHA, 8))
  }

  @Test fun `only Solo is lowered and Nunca is never touched`() {
    val l = ledger()
    l.add(InstinctCategory.VOLUMEN, TrustEvent.DESHECHA, 5)
    l.add(InstinctCategory.VOLUMEN, TrustEvent.CONFIRMADA, 20)
    assertNull(l.lowering(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR))
    assertNull(l.lowering(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA))
    assertFalse(l.suggestsRaise(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA))
    assertFalse(l.suggestsRaise(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO))
  }

  @Test fun `going back to Solo restarts the counters of that category`() {
    val l = ledger()
    l.add(InstinctCategory.VOLUMEN, TrustEvent.DESHECHA, 3)
    l.add(InstinctCategory.LINTERNA, TrustEvent.DESHECHA, 3)
    l.reset(InstinctCategory.VOLUMEN)
    assertNull(l.lowering(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO))
    assertNotNull(l.lowering(InstinctCategory.LINTERNA, InstinctAutonomy.SOLO))
  }

  @Test fun `eight confirmations without discards suggest raising once`() {
    val l = ledger()
    l.add(InstinctCategory.RELOJ, TrustEvent.CONFIRMADA, 7)
    assertFalse(l.suggestsRaise(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR))
    l.record(InstinctCategory.RELOJ, TrustEvent.CONFIRMADA)
    assertTrue(l.suggestsRaise(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR))
    l.markSuggested(InstinctCategory.RELOJ)
    l.record(InstinctCategory.RELOJ, TrustEvent.CONFIRMADA)
    assertFalse("a single suggestion", l.suggestsRaise(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR))
    val discarded = ledger()
    discarded.add(InstinctCategory.RELOJ, TrustEvent.CONFIRMADA, 12)
    discarded.record(InstinctCategory.RELOJ, TrustEvent.DESCARTADA)
    assertFalse(discarded.suggestsRaise(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR))
  }

  @Test fun `a No silences the suggestion for thirty days`() {
    val l = ledger()
    l.add(InstinctCategory.RELOJ, TrustEvent.CONFIRMADA, 8)
    l.snooze(InstinctCategory.RELOJ)
    now += 29 * day
    l.add(InstinctCategory.RELOJ, TrustEvent.CONFIRMADA, 8)
    assertFalse(l.suggestsRaise(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR))
    now += day + 1
    assertTrue(l.suggestsRaise(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR))
  }

  @Test fun `tokens are single use, survive a reload and never cross actions`() {
    val l = ledger()
    val token = l.issue(InstinctTrustLedger.Action.RESTORE, InstinctCategory.VOLUMEN)
    val reloaded = ledger().apply { load(l.encode()) }
    assertNull(reloaded.redeem(token, InstinctTrustLedger.Action.SUGGEST))
    assertEquals(InstinctCategory.VOLUMEN, reloaded.redeem(token, InstinctTrustLedger.Action.RESTORE)?.category)
    assertNull(reloaded.redeem(token, InstinctTrustLedger.Action.RESTORE))
    assertNull(reloaded.redeem("adivinado", InstinctTrustLedger.Action.RESTORE))
    val old = l.issue(InstinctTrustLedger.Action.SUGGEST, InstinctCategory.RELOJ)
    now += InstinctTrustLedger.TOKEN_TTL_MILLIS + 1
    assertNull(l.redeem(old, InstinctTrustLedger.Action.SUGGEST))
  }

  @Test fun `the ledger keeps thirty days at most and only counters`() {
    val l = ledger()
    l.add(InstinctCategory.VOLUMEN, TrustEvent.EJECUTADA, 2)
    now += 30 * day
    l.record(InstinctCategory.VOLUMEN, TrustEvent.EJECUTADA)
    assertEquals(1, l.count(InstinctCategory.VOLUMEN, TrustEvent.EJECUTADA, 60))
    val text = l.encode()
    assertFalse(text.contains("poner"))
    val copy = ledger().apply { load(text) }
    assertEquals(1, copy.count(InstinctCategory.VOLUMEN, TrustEvent.EJECUTADA, 60))
  }

  // Catalog

  @Test fun `the catalog says what runs alone and what waits, from the policy in force`() {
    val policy = InstinctAutonomyPolicy().with(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR)
      .with(InstinctCategory.APPS, InstinctAutonomy.NUNCA).copy(quiet = QuietHours(0, 7 * 60))
    val text = InstinctProtocolCatalog.text(emptyList(), autonomy = policy)
    assertTrue(text.contains("Qué hace Insto solo y qué espera confirmación"))
    assertTrue(text.contains("- Espera confirmación (espera): alarmas y temporizadores."))
    assertTrue(text.contains("- Lo rechaza siempre (error): abrir apps y búsquedas."))
    assertTrue(text.contains("Horas de silencio, de 00:00 a 07:00"))
    assertTrue(InstinctAck.safe(text.lines().filterNot { it.startsWith("#insto ") }.joinToString("\n")))
    val default = InstinctProtocolCatalog.text(emptyList())
    assertTrue(default.contains("- Lo hace solo: volumen, linterna"))
    assertFalse(default.contains("Horas de silencio"))
  }
}

/** 1.20.0: a locked phone stops only what needs a screen. */
class InstinctLockScreenTest {
  private fun snapshot(locked: Boolean, foreground: Boolean = false, accessibility: Boolean = true) =
    GateSnapshot(locked, accessibility, foreground, true, InstinctAutonomyPolicy(), 600)

  private val settings = listOf(PhoneCommand.Volume(VolumeOp.SET, 30), PhoneCommand.Volume(VolumeOp.UP), PhoneCommand.Torch(true),
    PhoneCommand.Torch(false), PhoneCommand.Media(MediaOp.PAUSE), PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600), PhoneCommand.EndMode,
    PhoneCommand.Platform(PlatformAction.SILENT_ON), PhoneCommand.Platform(PlatformAction.VIBRATE_ON), PhoneCommand.Platform(PlatformAction.DND_ON),
    PhoneCommand.Platform(PlatformAction.DND_OFF), PhoneCommand.Platform(PlatformAction.BATTERY))
  private val screens = listOf(PhoneCommand.Alarm(7, 0), PhoneCommand.Timer(60), PhoneCommand.OpenApp("Spotify"),
    PhoneCommand.YouTubeSearch("lluvia"), PhoneCommand.Platform(PlatformAction.CALENDAR_AGENDA))

  @Test fun `a locked phone never blocks every action at once - settings run locked`() {
    // The regression this guards: «!foreground && locked → LOCKED» for any command.
    for (command in settings) {
      assertTrue("$command", LockScreenPolicy.runsLocked(command))
      assertNull("$command", InstinctGatePreview.blocker(command, snapshot(locked = true)))
      assertEquals("$command", InstinctAutonomyGate.Gate.Run, InstinctGatePreview.preview(listOf(command), false, snapshot(locked = true)))
    }
  }

  @Test fun `what opens a screen still waits with the phone locked, even with Accessibility`() {
    for (command in screens) {
      assertFalse("$command", LockScreenPolicy.runsLocked(command))
      assertEquals("$command", InstinctGatePreview.LOCKED, InstinctGatePreview.blocker(command, snapshot(locked = true)))
    }
    assertNull("unlocked and with Accessibility it runs", InstinctGatePreview.blocker(PhoneCommand.Alarm(7, 0), snapshot(locked = false)))
  }

  @Test fun `a routine waits as a whole when one of its steps needs a screen`() {
    val gate = InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 10), PhoneCommand.OpenApp("Spotify")), true, snapshot(locked = true))
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctGatePreview.LOCKED), gate)
    assertEquals(InstinctAutonomyGate.Gate.Run, InstinctGatePreview.preview(settings.take(3), true, snapshot(locked = true)))
  }

  @Test fun `locking never turns Nunca or Confirmar into Solo`() {
    val never = GateSnapshot(true, true, false, true, InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA), 600)
    assertTrue(InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), false, never) is InstinctAutonomyGate.Gate.Refuse)
    val confirm = never.copy(policy = InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR))
    assertTrue(InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), false, confirm) is InstinctAutonomyGate.Gate.Wait)
  }

  @Test fun `only a temporary mode's own question is pre-authorized`() {
    assertTrue(InstinctConfirmations.preAuthorized(PhoneCommand.TemporaryMode(ModeKind.DND, 3600)))
    for (command in settings.filterNot { it is PhoneCommand.TemporaryMode } + screens) assertFalse("$command", InstinctConfirmations.preAuthorized(command))
  }
}
