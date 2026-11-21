package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

/** Pure part of the replies to Instinct: formatting, modes, the anti-loop guard and the ledger. No Android. */
class InstinctRepliesTest {
  private var now = 1_000_000L
  private var mode = InstinctReplyMode.ALWAYS
  private val ledger = InstinctAckLedger<String>({ now }, { mode })

  private fun ok(order: String, message: String = "Listo.") = AckEntry(AckKind.OK, order, message)

  // Formatting

  @Test fun `each outcome has its fixed prefix and the arrow`() {
    assertEquals("#insto-ok poner el volumen al 30 por ciento → Volumen multimedia ajustado.",
      InstinctAck.line(ok("poner el volumen al 30 por ciento", "Volumen multimedia ajustado.")))
    assertEquals("#insto-espera prender la linterna → El teléfono estaba bloqueado.",
      InstinctAck.line(AckEntry(AckKind.WAITING, "prender la linterna", "El teléfono estaba bloqueado.")))
    assertEquals("#insto-error llamar a Ana → esa acción no está permitida para Instinct.",
      InstinctAck.line(AckEntry(AckKind.ERROR, "llamar a Ana", "esa acción no está permitida para Instinct.")))
    assertEquals("#insto-descartada pausar la música → x", InstinctAck.line(AckEntry(AckKind.DISCARDED, "pausar la música", "x")))
    assertEquals("#insto-vencida pausar la música → x", InstinctAck.line(AckEntry(AckKind.EXPIRED, "pausar la música", "x")))
  }

  @Test fun `an uncertain result says it is not repeated`() {
    val entry = InstinctAck.entry("silenciar el teléfono", PhoneActionResult("No pude confirmar el estado.", outcome = PhoneActionOutcome.UNCERTAIN))
    assertEquals("#insto-incierto silenciar el teléfono → No pude confirmar el estado. · no se repite", InstinctAck.line(entry))
  }

  @Test fun `results map to their kinds`() {
    assertEquals(AckKind.OK, InstinctAck.kindOf(PhoneActionResult("ok")))
    assertEquals(AckKind.OK, InstinctAck.kindOf(PhoneActionResult("ok", outcome = PhoneActionOutcome.HANDOFF)))
    assertEquals(AckKind.ERROR, InstinctAck.kindOf(PhoneActionResult("no", outcome = PhoneActionOutcome.REJECTED)))
    assertEquals(AckKind.UNCERTAIN, InstinctAck.kindOf(PhoneActionResult("?", outcome = PhoneActionOutcome.UNCERTAIN)))
    assertEquals(AckKind.WAITING, InstinctAck.kindOf(PhoneActionResult("¿Confirmás?", confirmation = PhoneCommand.Torch(true), outcome = PhoneActionOutcome.NEEDS_INPUT)))
    assertEquals(AckKind.WAITING, InstinctAck.kindOf(PhoneActionResult("permiso", needsNotifications = true)))
  }

  @Test fun `every invalid reason has a short Spanish sentence`() {
    for (reason in ProposalCheck.Reason.entries) {
      val line = InstinctAck.line(InstinctAck.invalid(ProposalCheck.Invalid("orden", reason)))
      assertTrue(line, line.startsWith("#insto-error orden → "))
      assertTrue(line, InstinctAck.reason(reason).length in 10..80)
    }
    val forbidden = InstinctValidatorProbe.invalid("llamar a Ana")
    assertEquals("#insto-error llamar a Ana → esa acción no está permitida para Instinct.", InstinctAck.line(InstinctAck.invalid(forbidden)))
  }

  private object InstinctValidatorProbe {
    fun invalid(order: String) = InstinctProposalValidator.check(order, PhoneCommandParser(), Calendar.getInstance()) as ProposalCheck.Invalid
  }

  @Test fun `the order is cut to 80 characters and the reply to 3 lines and 600 characters`() {
    val long = "buscar " + "a".repeat(200) + " en YouTube"
    val line = InstinctAck.line(ok(long, "m"))
    assertTrue(line, line.startsWith("#insto-ok buscar aaa"))
    assertEquals(80, line.removePrefix("#insto-ok ").substringBefore(" → ").length)
    val reply = InstinctAck.format((1..5).map { ok("orden $it", "x".repeat(400)) }, InstinctReplyMode.ALWAYS)!!
    assertEquals(3, reply.lines().size)
    assertTrue(reply.length <= 600)
    assertTrue(reply.lines().all { it.startsWith("#insto-ok orden ") })
  }

  // Anti-loop guard

  @Test fun `no line of a reply ever starts with an order for Insto`() {
    val hostile = listOf("#insto poner la alarma", "\n#insto prender la linterna", "x\r\n  #insto subir el volumen", "\t#insto\tbajar",
      "#insto", "a #insto llamar", "#insto-ok", "  #insto  pausar")
    for (order in hostile) for (message in hostile) for (kind in AckKind.entries) {
      val reply = InstinctAck.format(listOf(AckEntry(kind, order, message)), InstinctReplyMode.ALWAYS)!!
      assertTrue(reply, reply.lines().none { it.trimStart().startsWith("#insto ") || it.trim() == "#insto" })
      assertEquals(reply, emptyList<String>(), InstinctProposalProtocol.orders(reply))
      assertEquals(1, reply.lines().size)
    }
    assertFalse(InstinctAck.safe("#insto-ok a\n#insto poner"))
    assertTrue(InstinctAck.safe("#insto-ok a → b"))
  }

  // Modes

  @Test fun `Nunca sends nothing, Solo errores drops successes, Siempre sends everything`() {
    val entries = listOf(ok("a"), AckEntry(AckKind.WAITING, "b", "espera"), AckEntry(AckKind.ERROR, "c", "no"))
    assertNull(InstinctAck.format(entries, InstinctReplyMode.NEVER))
    assertEquals("#insto-espera b → espera\n#insto-error c → no", InstinctAck.format(entries, InstinctReplyMode.PROBLEMS))
    assertEquals(3, InstinctAck.format(entries, InstinctReplyMode.ALWAYS)!!.lines().size)
    assertNull(InstinctAck.format(listOf(ok("a")), InstinctReplyMode.PROBLEMS))
  }

  @Test fun `under Nunca no handle is kept at all`() {
    mode = InstinctReplyMode.NEVER
    assertNull(ledger.open("handle", 2))
    assertNull(ledger.latestHandle())
  }

  // Ledger: one reply per message, in order

  @Test fun `one reply per message once every line has an outcome, in arrival order`() {
    val ticket = ledger.open("h", 3)
    assertNull(ledger.resolve(ticket, 1, AckEntry(AckKind.ERROR, "llamar a Ana", "no")))
    ledger.link(ticket, 0, 10L)
    ledger.link(ticket, 2, 11L)
    assertNull(ledger.report(11L, ok("pausar la música")))
    val out = ledger.report(10L, ok("subir el volumen"))!!
    assertEquals("h", out.handle)
    assertEquals(listOf("#insto-ok subir el volumen → Listo.", "#insto-error llamar a Ana → no", "#insto-ok pausar la música → Listo."),
      out.text!!.lines())
    // Anything later about the same lines is not sent again.
    assertNull(ledger.report(10L, ok("subir el volumen")))
    assertNull(ledger.report(11L, AckEntry(AckKind.ERROR, "pausar la música", "x")))
  }

  @Test fun `without a reply action the reply is handed back without a handle, to be dropped and diagnosed`() {
    val ticket = ledger.open(null, 1)
    val out = ledger.resolve(ticket, 0, AckEntry(AckKind.ERROR, "x", "no"))!!
    assertNull(out.handle)
  }

  // Follow-ups

  @Test fun `an order that waited gets one follow-up when it is confirmed`() {
    val ticket = ledger.open("h", 1)
    ledger.link(ticket, 0, 7L)
    assertEquals("#insto-espera prender la linterna → bloqueado", ledger.report(7L, AckEntry(AckKind.WAITING, "prender la linterna", "bloqueado"))!!.text)
    now += 60_000
    assertEquals("#insto-ok prender la linterna → Linterna encendida.", ledger.report(7L, ok("prender la linterna", "Linterna encendida."))!!.text)
    assertNull(ledger.report(7L, AckEntry(AckKind.DISCARDED, "prender la linterna", "x")))
  }

  @Test fun `discarding or expiring a waiting order is followed up, also under Solo errores`() {
    mode = InstinctReplyMode.PROBLEMS
    val ticket = ledger.open("h", 2)
    ledger.link(ticket, 0, 1L); ledger.link(ticket, 1, 2L)
    ledger.report(1L, AckEntry(AckKind.WAITING, "a", "espera"))
    assertEquals("#insto-espera a → espera\n#insto-espera b → espera", ledger.report(2L, AckEntry(AckKind.WAITING, "b", "espera"))!!.text)
    assertEquals("#insto-descartada a → la persona la descartó", ledger.report(1L, AckEntry(AckKind.DISCARDED, "a", "la persona la descartó"))!!.text)
    now += ProposalBook.TTL_MILLIS - 1_500
    assertEquals(listOf(2L), ledger.waitingDue(2_000))
    assertEquals("#insto-vencida b → venció", ledger.report(2L, AckEntry(AckKind.EXPIRED, "b", "venció"))!!.text)
    assertTrue(ledger.waitingDue(2_000).isEmpty())
  }

  @Test fun `a waiting order confirmed under Solo errores is still followed up with ok`() {
    mode = InstinctReplyMode.PROBLEMS
    val ticket = ledger.open("h", 1)
    ledger.link(ticket, 0, 3L)
    ledger.report(3L, AckEntry(AckKind.WAITING, "a", "espera"))
    assertEquals("#insto-ok a → Listo.", ledger.report(3L, ok("a"))!!.text)
  }

  @Test fun `a waiting order that is confirmed before the reply goes out is reported as done`() {
    val ticket = ledger.open("h", 2)
    ledger.link(ticket, 0, 1L); ledger.link(ticket, 1, 2L)
    ledger.report(1L, AckEntry(AckKind.WAITING, "a", "ocupado"))
    ledger.report(1L, ok("a"))
    assertEquals("#insto-ok a → Listo.\n#insto-ok b → Listo.", ledger.report(2L, ok("b"))!!.text)
  }

  @Test fun `a proposal offered again under a new id keeps its line`() {
    val ticket = ledger.open("h", 1)
    ledger.link(ticket, 0, 1L)
    ledger.relink(1L, 2L)
    assertEquals("#insto-espera a → permiso", ledger.report(2L, AckEntry(AckKind.WAITING, "a", "permiso"))!!.text)
  }

  // Expired handle

  @Test fun `an expired handle sends nothing, neither the reply nor a follow-up`() {
    val ticket = ledger.open("h", 2)
    ledger.link(ticket, 0, 1L); ledger.link(ticket, 1, 2L)
    assertNull(ledger.report(1L, AckEntry(AckKind.WAITING, "a", "espera")))
    now += ProposalBook.TTL_MILLIS + 1
    assertNull(ledger.report(2L, ok("b")))
    assertNull(ledger.report(1L, ok("a")))
    assertNull(ledger.latestHandle())
    assertFalse(ledger.canAnswer(1L))
  }

  @Test fun `a follow-up after the handle lapsed is not sent`() {
    val ticket = ledger.open("h", 1)
    ledger.link(ticket, 0, 1L)
    assertNotNull(ledger.report(1L, AckEntry(AckKind.WAITING, "a", "espera")))
    now += ProposalBook.TTL_MILLIS + 1
    assertNull(ledger.report(1L, ok("a")))
  }

  @Test fun `switching to Nunca or clearing cuts pending replies`() {
    val ticket = ledger.open("h", 1)
    ledger.link(ticket, 0, 1L)
    mode = InstinctReplyMode.NEVER
    assertNull(ledger.report(1L, ok("a")))
    mode = InstinctReplyMode.ALWAYS
    val second = ledger.open("h", 1)
    ledger.link(second, 0, 2L)
    ledger.clear()
    assertNull(ledger.report(2L, ok("b")))
  }

  // Our own reply re-posted by WhatsApp

  private class Fake(
    override val packageName: String = "com.whatsapp",
    override val key: String = "k",
    private val sender: String? = "Instinct",
    private val messaging: Boolean = true,
    private val title: String = "Instinct",
    private val body: String,
  ) : ProposalNotification {
    override fun isGroup() = false
    override fun title() = title
    override fun latestMessage() = if (messaging) ProposalNotification.LatestMessage(sender) else null
    override fun text() = body
  }

  @Test fun `our own reply re-posted by WhatsApp is never processed as an order`() {
    val filter = InstinctProposalFilter()
    val config = ProposalFilterConfig("com.whatsapp", "Instinct", now)
    val reply = InstinctAck.format(listOf(ok("poner el volumen al 30 por ciento", "Volumen multimedia ajustado."),
      AckEntry(AckKind.ERROR, "#insto llamar a Ana", "no")), InstinctReplyMode.ALWAYS)!!
    // With MessagingStyle the newest message is ours: the sender is the user.
    assertEquals(InstinctProposalFilter.Stage.CONTACT, filter.evaluate(Fake(sender = null, body = reply), config).stage)
    // Without MessagingStyle the title is still the contact, but the text has no line for Insto.
    assertEquals(InstinctProposalFilter.Stage.NO_PROPOSAL, filter.evaluate(Fake(messaging = false, body = reply), config).stage)
    // Even if WhatsApp attributed it to the contact, nothing in it is an order.
    assertEquals(InstinctProposalFilter.Stage.NO_PROPOSAL, filter.evaluate(Fake(body = reply), config).stage)
  }
}
