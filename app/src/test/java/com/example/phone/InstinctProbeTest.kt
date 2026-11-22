package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

/** «#insto probar <orden>» (1.18.0) and the pure gate both paths share. */
class InstinctProbeTest {
  private val parser = PhoneCommandParser()
  private fun check(order: String) = InstinctProposalValidator.check(order, parser, Calendar.getInstance())
  private fun snapshot(locked: Boolean = false, accessibility: Boolean = true, foreground: Boolean = false, budget: Boolean = true,
    policy: InstinctAutonomyPolicy = InstinctAutonomyPolicy(), minute: Int = 12 * 60, source: ProposalSource = ProposalSource.BUZON,
    cap: InstinctAutonomy = InstinctAutonomy.SOLO) = GateSnapshot(locked, accessibility, foreground, budget, policy, minute, source, cap)

  @Test fun `probar is a read-only query that keeps the order as written`() {
    assertEquals(ProposalCheck.Query("probar poner el volumen al 30 por ciento", ProposalCheck.QueryKind.PROBE, "poner el volumen al 30 por ciento"),
      check("probar poner el volumen al 30 por ciento"))
    assertEquals("a las 22:00: apagar la linterna", (check("Probar a las 22:00: apagar la linterna") as ProposalCheck.Query).argument)
    assertEquals(ProposalCheck.Reason.EMPTY, (check("probar") as ProposalCheck.Invalid).reason)
    assertTrue(ProposalCheck.QueryKind.PROBE.readOnly)
    assertFalse("«probarse» is not a probe", check("probarse la linterna") is ProposalCheck.Query)
  }

  @Test fun `the preview decides exactly as the real gate, and never consumes the brake`() {
    val volume = PhoneCommand.Volume(VolumeOp.SET, 30)
    val alarm = PhoneCommand.Alarm(7, 0)
    val policies = listOf(InstinctAutonomyPolicy(), InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR),
      InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA), InstinctAutonomyPolicy(quiet = QuietHours(0, 24 * 60 - 1)))
    for (command in listOf<PhoneCommand>(volume, alarm)) for (policy in policies) for (locked in listOf(true, false))
      for (accessibility in listOf(true, false)) for (foreground in listOf(true, false)) for (budget in listOf(true, false))
        for (source in ProposalSource.entries) for (cap in listOf(InstinctAutonomy.SOLO, InstinctAutonomy.CONFIRMAR)) {
          val s = snapshot(locked, accessibility, foreground, budget, policy, source = source, cap = cap)
          var acquired = 0
          val real = InstinctGatePreview.decide(listOf(command), false, s) { acquired++; budget }
          assertEquals("$command $s", real, InstinctGatePreview.preview(listOf(command), false, s))
          assertTrue("the brake is only asked for an order that would run", acquired <= 1)
        }
    val brake = AutoRunBudget({ 0L }, max = 1)
    assertTrue(brake.available()); assertTrue(brake.available())
    assertTrue(brake.tryAcquire()); assertFalse(brake.available())
  }

  @Test fun `the policy only hardens, and the old blockers still win over Solo`() {
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctGatePreview.LOCKED), InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), false, snapshot(locked = true)))
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctGatePreview.NO_ACCESSIBILITY),
      InstinctGatePreview.preview(listOf(PhoneCommand.OpenApp("spotify")), false, snapshot(accessibility = false)))
    assertEquals("the open panel may open apps", InstinctAutonomyGate.Gate.Run,
      InstinctGatePreview.preview(listOf(PhoneCommand.OpenApp("spotify")), false, snapshot(accessibility = false, foreground = true)))
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctAutonomyGate.BUDGET), InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), false, snapshot(budget = false)))
    assertEquals(InstinctAutonomyGate.Gate.Refuse(InstinctAutonomyPolicy.REFUSED), InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), false,
      snapshot(locked = true, policy = InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.NUNCA))))
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctSourceCap.REASON), InstinctGatePreview.preview(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), false,
      snapshot(source = ProposalSource.WHATSAPP, cap = InstinctAutonomy.CONFIRMAR)))
    assertEquals(InstinctAutonomyGate.Gate.Wait(InstinctGatePreview.ROUTINE_CHANGED), InstinctGatePreview.preview(null, true, snapshot()))
  }

  @Test fun `what the action would ask of the person is said when it can be known`() {
    assertNotNull(InstinctGatePreview.needs(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600), 600))
    assertEquals("esa hora ya pasó hoy; la persona tiene que confirmar que es para mañana", InstinctGatePreview.needs(PhoneCommand.Alarm(7, 0), 8 * 60))
    assertNull(InstinctGatePreview.needs(PhoneCommand.Alarm(9, 0), 8 * 60))
    assertNull("a repeating alarm has no past", InstinctGatePreview.needs(PhoneCommand.Alarm(7, 0, days = listOf(2)), 8 * 60))
    assertNull(InstinctGatePreview.needs(PhoneCommand.Volume(VolumeOp.SET, 30), 8 * 60))
  }

  @Test fun `the answer has fixed forms`() {
    val volume = PhoneCommand.Volume(VolumeOp.SET, 30)
    assertEquals("válida · volumen · la haría sola", InstinctProbe.verdict(volume, InstinctAutonomyGate.Gate.Run, null).detail)
    assertEquals("válida · volumen · esperaría confirmación: horas de silencio",
      InstinctProbe.verdict(volume, InstinctAutonomyGate.Gate.Wait(InstinctAutonomyPolicy.QUIET), null).detail)
    assertEquals("válida · volumen · esperaría confirmación: el teléfono estaba bloqueado",
      InstinctProbe.verdict(volume, InstinctAutonomyGate.Gate.Wait(InstinctGatePreview.LOCKED), null).detail)
    assertEquals("válida · alarmas y temporizadores · esperaría confirmación: esa hora ya pasó hoy; la persona tiene que confirmar que es para mañana",
      InstinctProbe.verdict(PhoneCommand.Alarm(7, 0), InstinctAutonomyGate.Gate.Run, InstinctGatePreview.needs(PhoneCommand.Alarm(7, 0), 9 * 60)).detail)
    assertEquals("rechazada: la persona no permite esta acción",
      InstinctProbe.verdict(volume, InstinctAutonomyGate.Gate.Refuse(InstinctAutonomyPolicy.REFUSED), null).detail)
    assertEquals("válida · programada: a las 22:00", InstinctProbe.Verdict.Scheduled("a las 22:00").detail)
    assertEquals("no válida: no la entendí: usá una de las formas del catálogo",
      InstinctProbe.Verdict.Invalid(InstinctAck.reason(ProposalCheck.Reason.NOT_LOCAL)).detail)
    // Every form passes the anti-loop guard.
    listOf("válida · volumen · la haría sola", "no válida: x", "rechazada: y").forEach { assertTrue(InstinctAck.safe(it)) }
  }

  @Test fun `the catalog, text and JSON, offers the probe and its rule`() {
    val text = InstinctProtocolCatalog.text(emptyList())
    assertTrue(text.contains("#insto probar <orden>"))
    assertTrue(text.contains(InstinctProbe.RULE))
    val json = org.json.JSONObject(InstinctProtocolCatalog.json(emptyList(), instoVersion = "1.18.0").canonical)
    val queries = json.getJSONArray("consultas")
    assertTrue((0 until queries.length()).any { queries.getJSONObject(it).getString("orden") == "probar <orden>" })
    assertTrue(json.getJSONArray("reglas").toString().contains("probala antes"))
  }
}
