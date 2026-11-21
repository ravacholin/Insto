package com.example.phone

import java.io.File
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

/** Routines Instinct may run and the generated catalog: the pure part. No Android. */
class InstinctRoutinesTest {
  private val parser = PhoneCommandParser()
  private val estudio = ConfiguredRoutine("Estudio", listOf(PhoneCommand.Platform(PlatformAction.DND_ON), PhoneCommand.Volume(VolumeOp.SET, 20),
    PhoneCommand.Timer(1500)))
  private var routines = listOf(estudio)

  private fun access(vararg enabled: ConfiguredRoutine) =
    InstinctRoutineAccess(enabled.associate { InstinctRoutines.key(it.name) to InstinctRoutines.hash(it) }) { routines }

  private fun check(order: String, access: InstinctRoutineAccess = InstinctRoutineAccess.NONE) =
    // A fixed day (21/11/2026): the recurring examples end on 13/12 and 15/12, within 30 days.
    InstinctProposalValidator.check(order, parser, Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21, 10, 0, 0) }, access)

  // Enabling and the validator

  @Test fun `a routine is forbidden unless the user enabled it`() {
    assertEquals(ProposalCheck.Reason.FORBIDDEN, (check("ejecutar la rutina Estudio") as ProposalCheck.Invalid).reason)
    val valid = check("ejecutar la rutina Estudio", access(estudio)) as ProposalCheck.Valid
    assertEquals(PhoneCommand.Platform(PlatformAction.ROUTINE_RUN, "Estudio"), valid.command)
    assertTrue(check("ejecutar la rutina estudio", access(estudio)) is ProposalCheck.Valid) // normalized like routine_run
  }

  @Test fun `disabling a routine forbids it again`() {
    val grants = mutableMapOf(InstinctRoutines.key("Estudio") to InstinctRoutines.hash(estudio))
    assertTrue(check("ejecutar la rutina Estudio", InstinctRoutineAccess(grants) { routines }) is ProposalCheck.Valid)
    grants.clear()
    assertTrue(check("ejecutar la rutina Estudio", InstinctRoutineAccess(grants) { routines }) is ProposalCheck.Invalid)
  }

  @Test fun `a similar but not exact name is forbidden`() {
    val access = access(estudio)
    for (order in listOf("ejecutar la rutina Estudios", "ejecutar la rutina Estudio intensivo", "ejecutar la rutina Estud"))
      assertTrue(order, check(order, access) is ProposalCheck.Invalid)
    assertNull(access.resolve("Estudio2"))
  }

  @Test fun `a routine whose steps changed stays off until enabled again`() {
    val access = access(estudio)
    assertNotNull(access.resolve("Estudio"))
    routines = listOf(estudio.copy(commands = estudio.commands + PhoneCommand.Torch(true)))
    assertNull(access.resolve("Estudio"))
    assertTrue(check("ejecutar la rutina Estudio", access) is ProposalCheck.Invalid)
    assertNotEquals(InstinctRoutines.hash(estudio), InstinctRoutines.hash(routines.single()))
  }

  @Test fun `the hash depends on the steps, not on the key order of their encoding`() {
    assertEquals(InstinctRoutines.hash(estudio), InstinctRoutines.hash(ConfiguredRoutine("Otro nombre", estudio.commands)))
    assertEquals(64, InstinctRoutines.hash(estudio).length)
  }

  @Test fun `a routine with a forbidden step or more than six steps cannot be enabled`() {
    val call = ConfiguredRoutine("Llamar", listOf(PhoneCommand.Torch(true), PhoneCommand.Dial("+541112345678")))
    assertEquals(InstinctRoutines.Eligibility.ForbiddenStep(2, PhoneCommandCodec.summary(PhoneCommand.Dial("+541112345678"))),
      InstinctRoutines.eligibility(call))
    assertTrue(InstinctRoutines.blocker(call)!!.startsWith("El paso 2 ("))
    val long = ConfiguredRoutine("Larga", (1..7).map { PhoneCommand.Timer(it * 60) })
    assertEquals(InstinctRoutines.Eligibility.TooManySteps(7), InstinctRoutines.eligibility(long))
    assertNull(InstinctRoutines.blocker(estudio))
    // Even with a forged grant, the validator refuses it.
    routines = listOf(call)
    assertNull(access(call).resolve("Llamar"))
    assertTrue(check("ejecutar la rutina Llamar", access(call)) is ProposalCheck.Invalid)
  }

  @Test fun `grants round-trip and ignore malformed entries`() {
    val grants = mapOf("estudio" to InstinctRoutines.hash(estudio))
    assertEquals(grants, InstinctRoutines.decodeGrants(InstinctRoutines.encodeGrants(grants)))
    assertEquals(emptyMap<String, String>(), InstinctRoutines.decodeGrants("{\"x\":\"no-es-un-hash\"}"))
    assertEquals(emptyMap<String, String>(), InstinctRoutines.decodeGrants("basura"))
  }

  // Running

  private fun run(results: List<PhoneActionResult>, blocked: (PhoneCommand) -> String? = { null }): Pair<InstinctRoutineRun.Report, List<PhoneCommand>> {
    val executed = mutableListOf<PhoneCommand>()
    val saved = mutableListOf<WorkflowSnapshot?>()
    var index = 0
    val report = InstinctRoutineRun(WorkflowRunner(save = { saved += it }), blocked) { executed += it; results[index++] }.run(estudio)
    // The pre-dispatch write: every executed step was saved as EXECUTING before it ran.
    assertTrue(saved.count { s -> s?.steps?.any { it.status == StepStatus.EXECUTING } == true } >= executed.size)
    return report to executed
  }

  @Test fun `a complete run reports every step`() {
    val (report, executed) = run(List(3) { PhoneActionResult("ok") })
    assertEquals(estudio.commands, executed)
    assertTrue(report.finished)
    assertEquals("Instinct ejecutó la rutina Estudio: 3 de 3 pasos.", report.summary)
    assertEquals(AckKind.OK, report.kind)
  }

  @Test fun `a handed off step counts as done and the run goes on`() {
    val (report, executed) = run(listOf(PhoneActionResult("ok"), PhoneActionResult("ok"), PhoneActionResult("Reloj", outcome = PhoneActionOutcome.HANDOFF)))
    assertEquals(3, executed.size)
    assertTrue(report.finished)
  }

  @Test fun `an uncertain step 2 stops the run, cancels the rest and nothing is repeated`() {
    val (report, executed) = run(listOf(PhoneActionResult("ok"), PhoneActionResult("No pude confirmar.", outcome = PhoneActionOutcome.UNCERTAIN)))
    assertEquals(estudio.commands.take(2), executed)
    assertEquals(2, report.stoppedAt)
    assertTrue(report.uncertain)
    assertEquals(AckKind.UNCERTAIN, report.kind)
    assertEquals(listOf(StepStatus.COMPLETED, StepStatus.UNCERTAIN, StepStatus.CANCELLED), report.statuses)
    assertEquals("Instinct ejecutó la rutina Estudio: se detuvo en el paso 2: No pude confirmar. · no se repite", report.summary)
  }

  @Test fun `a step that fails or needs input stops the run`() {
    val (failed, executed) = run(listOf(PhoneActionResult("No se pudo.", outcome = PhoneActionOutcome.REJECTED)))
    assertEquals(1, executed.size)
    assertEquals(1, failed.stoppedAt)
    assertEquals(AckKind.ERROR, failed.kind)
    assertEquals(listOf(StepStatus.CANCELLED, StepStatus.CANCELLED, StepStatus.CANCELLED), failed.statuses)
    val (input, _) = run(listOf(PhoneActionResult("ok"), PhoneActionResult("Permití", needsNotifications = true, outcome = PhoneActionOutcome.NEEDS_INPUT)))
    assertEquals(2, input.stoppedAt)
  }

  @Test fun `a step blocked at its turn does not run, nor do the following ones`() {
    val (report, executed) = run(List(3) { PhoneActionResult("ok") }) { if (it is PhoneCommand.Timer) "El teléfono estaba bloqueado." else null }
    assertEquals(2, executed.size)
    assertEquals(3, report.stoppedAt)
    assertEquals("Instinct ejecutó la rutina Estudio: se detuvo en el paso 3: El teléfono estaba bloqueado.", report.summary)
  }

  @Test fun `a failing pre-dispatch write runs nothing`() {
    val executed = mutableListOf<PhoneCommand>()
    var writes = 0
    val report = InstinctRoutineRun(WorkflowRunner(save = { if (++writes > 2) error("disco lleno") }), { null }) { executed += it; PhoneActionResult("ok") }
      .run(estudio)
    assertTrue(executed.isEmpty())
    assertEquals(1, report.stoppedAt)
  }

  @Test fun `a run interrupted by a process death is uncertain and never resumed`() {
    val interrupted = WorkflowSnapshot(steps = listOf(WorkflowStep(PhoneCommand.Torch(true), StepStatus.EXECUTING), WorkflowStep(PhoneCommand.Timer(60))),
      confirmed = true)
    val runner = WorkflowRunner(interrupted)
    assertEquals(StepStatus.UNCERTAIN, runner.snapshot!!.steps[0].status)
    assertNull(runner.next())
    val executed = mutableListOf<PhoneCommand>()
    InstinctRoutineRun(runner, { null }) { executed += it; PhoneActionResult("ok") }.run(estudio)
    assertEquals("only the new routine runs, from its first step", estudio.commands, executed)
  }

  // Catalog

  @Test fun `every example of the catalog is a valid order`() {
    for (example in InstinctProposalValidator.examples)
      assertTrue(example, check(example) is ProposalCheck.Valid)
    val catalog = InstinctProtocolCatalog.text(emptyList())
    val orders = catalog.lines().filter { it.startsWith("#insto ") && it != "#insto <orden>" }.map { it.removePrefix("#insto ") }
    val queries = ProposalCheck.QueryKind.entries.map { it.form }
    assertEquals(InstinctProposalValidator.examples + queries, orders)
  }

  @Test fun `enabled routines appear by name only, and their lines are valid`() {
    val second = ConfiguredRoutine("Noche", listOf(PhoneCommand.Torch(false)))
    routines = listOf(estudio, second)
    val access = access(estudio, second)
    val catalog = InstinctProtocolCatalog.text(access.enabledNames())
    assertTrue(catalog.contains("#insto ejecutar la rutina Estudio\n"))
    assertTrue(catalog.contains("#insto ejecutar la rutina Noche\n"))
    assertFalse("steps are never listed", catalog.contains("temporizador de 25 minutos\n#insto ejecutar"))
    for (line in catalog.lines().filter { it.startsWith("#insto ejecutar la rutina ") })
      assertTrue(line, check(line.removePrefix("#insto "), access) is ProposalCheck.Valid)
    assertTrue(catalog.contains(InstinctProtocolCatalog.TITLE))
    for (kind in AckKind.entries) assertTrue(kind.prefix, catalog.contains(kind.prefix))
  }

  @Test fun `the chat copy never reads as orders for Insto`() {
    val chat = InstinctProtocolCatalog.text(listOf("Estudio"), forChat = true)
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders(chat))
    assertTrue(InstinctAck.safe(chat))
    assertTrue(chat.contains("› #insto poner una alarma a las 07:00"))
  }

  @Test fun `ver catálogo is a read-only query`() {
    assertEquals(ProposalCheck.Query("ver catálogo", ProposalCheck.QueryKind.CATALOG), check("ver catálogo"))
    assertEquals(ProposalCheck.QueryKind.CATALOG, (check("Ver catalogo.") as ProposalCheck.Query).kind)
    assertEquals("Protocolo Insto v5 · rutinas habilitadas: ninguna.", InstinctProtocolCatalog.summary(emptyList()))
    assertEquals("Protocolo Insto v5 · rutinas habilitadas: Estudio, Noche.", InstinctProtocolCatalog.summary(listOf("Estudio", "Noche")))
    assertTrue(check("ver catálogo completo") is ProposalCheck.Invalid)
  }

  /** The reference copy in docs is what the code generates. Set INSTO_WRITE_CATALOG=1 to regenerate it. */
  @Test fun `the reference copy in docs matches the generated catalog`() {
    val file = listOf(File("docs/instinct-catalog.txt"), File("../docs/instinct-catalog.txt")).first { it.parentFile?.isDirectory == true }
    val expected = InstinctProtocolCatalog.text(emptyList()) + "\n"
    if (System.getenv("INSTO_WRITE_CATALOG") == "1") file.writeText(expected)
    assertEquals("Regenerá docs/instinct-catalog.txt con INSTO_WRITE_CATALOG=1", expected, file.readText())
  }

  /** The log variant, as sent through WhatsApp: placeholders, never an address or a token. */
  @Test fun `the reference copy of the log variant matches the generated catalog`() {
    val file = listOf(File("docs/instinct-catalog-bitacora.txt"), File("../docs/instinct-catalog-bitacora.txt")).first { it.parentFile?.isDirectory == true }
    val expected = InstinctProtocolCatalog.text(emptyList(), forChat = true, log = InstinctCatalogLog.HIDDEN) + "\n"
    if (System.getenv("INSTO_WRITE_CATALOG") == "1") file.writeText(expected)
    assertEquals("Regenerá docs/instinct-catalog-bitacora.txt con INSTO_WRITE_CATALOG=1", expected, file.readText())
  }
}
