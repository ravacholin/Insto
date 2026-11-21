package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

/** Pure part of «Instinct con manos»: protocol, validation, filters and the proposal book. No Android. */
class InstinctProposalsTest {
  private val parser = PhoneCommandParser()
  private fun check(order: String) = InstinctProposalValidator.check(order, parser, Calendar.getInstance())

  // Protocol

  @Test fun `a message without proposal lines has no orders`() {
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders("Listo, ya te contesto lo que me preguntaste."))
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders(""))
  }

  @Test fun `one proposal line among ordinary text`() {
    val message = "Te dejo esto preparado:\n#insto poner una alarma a las 07:00\nAvisame si lo querés cambiar."
    assertEquals(listOf("poner una alarma a las 07:00"), InstinctProposalProtocol.orders(message))
  }

  @Test fun `three proposal lines keep their order`() {
    val message = "#insto poner una alarma a las 07:00\n#insto poner un temporizador de 25 minutos\n  #insto poner el volumen al 30 por ciento"
    assertEquals(listOf("poner una alarma a las 07:00", "poner un temporizador de 25 minutos", "poner el volumen al 30 por ciento"),
      InstinctProposalProtocol.orders(message))
  }

  @Test fun `only the first three of five proposal lines are read`() {
    val message = (1..5).joinToString("\n") { "#insto poner un temporizador de $it minutos" }
    assertEquals((1..3).map { "poner un temporizador de $it minutos" }, InstinctProposalProtocol.orders(message))
  }

  @Test fun `insto inside a sentence or glued to a word is ordinary text`() {
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders("Podrías escribir #insto poner una alarma a las 07:00"))
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders("#instopuso una alarma"))
    assertEquals(emptyList<String>(), InstinctProposalProtocol.orders("#INSTO poner una alarma a las 07:00"))
  }

  @Test fun `a malformed proposal line is reported, not executed`() {
    val orders = InstinctProposalProtocol.orders("#insto\n#insto poneme algo lindo")
    assertEquals(listOf("", "poneme algo lindo"), orders)
    assertEquals(ProposalCheck.Reason.EMPTY, (check(orders[0]) as ProposalCheck.Invalid).reason)
    assertEquals(ProposalCheck.Reason.NOT_LOCAL, (check(orders[1]) as ProposalCheck.Invalid).reason)
  }

  // Validation: explicit grammar, allowlist and forbidden actions

  @Test fun `canonical examples resolve to the allowed local commands`() {
    assertEquals(PhoneCommand.Alarm(7, 0), (check("poner una alarma a las 07:00") as ProposalCheck.Valid).command)
    assertEquals(PhoneCommand.Timer(1500), (check("poner un temporizador de 25 minutos") as ProposalCheck.Valid).command)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 30), (check("poner el volumen al 30 por ciento") as ProposalCheck.Valid).command)
    assertEquals(PhoneCommand.Torch(true), (check("prender la linterna") as ProposalCheck.Valid).command)
    assertEquals(PhoneCommand.Platform(PlatformAction.SILENT_ON), (check("silenciar el teléfono") as ProposalCheck.Valid).command)
    assertTrue((check("silenciar el teléfono durante una hora") as ProposalCheck.Valid).command is PhoneCommand.TemporaryMode)
    assertEquals(PhoneCommand.Media(MediaOp.PAUSE), (check("pausar la música") as ProposalCheck.Valid).command)
    assertEquals(PhoneCommand.ClockList(true), (check("mostrar las alarmas") as ProposalCheck.Valid).command)
    assertEquals(PhoneCommand.YouTubeSearch("jazz"), (check("buscar jazz en YouTube") as ProposalCheck.Valid).command)
    assertTrue((check("agendar reunión mañana a las 17:00 durante una hora") as ProposalCheck.Valid).command is PhoneCommand.CalendarEvent)
  }

  /** The allowed examples of the block pasted into Instinct's prompt (docs/INSTINCT-PROPUESTAS.md) must all be valid. */
  @Test fun `every example given to Instinct is a valid proposal`() {
    val examples = listOf("poner una alarma a las 07:00", "poner una alarma a las 07:00 todos los días",
      "poner un temporizador de 25 minutos", "poner un temporizador de 1 minuto", "poner el volumen al 30 por ciento",
      "subir el volumen", "bajar el volumen", "prender la linterna", "apagar la linterna", "silenciar el teléfono",
      "silenciar el teléfono durante una hora", "activar no molestar", "poner en vibración", "pausar la música",
      "siguiente canción", "abrir spotify", "mostrar las alarmas", "mostrar los temporizadores",
      "poner música de Soda Stereo", "buscar jazz en YouTube", "buscar Cerati en Spotify",
      "agendar reunión mañana a las 17:00 durante una hora")
    for (example in examples) {
      val message = "#insto $example"
      val order = InstinctProposalProtocol.orders(message).single()
      assertTrue("$example → ${check(order)}", check(order) is ProposalCheck.Valid)
    }
  }

  @Test fun `calls messages places and routines are forbidden even when the grammar understands them`() {
    for (order in listOf("llamar a Ana", "mandale un mensaje a Ana que llego tarde",
      "guardar mi casa en Av. Córdoba 1234", "ejecutar la rutina mañana")) {
      assertEquals(order, ProposalCheck.Reason.FORBIDDEN, (check(order) as ProposalCheck.Invalid).reason)
    }
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.Dial("1122334455")))
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.Platform(PlatformAction.CALL_DIRECT, "Ana")))
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.Platform(PlatformAction.FILE_SHARE, "a.pdf")))
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.Platform(PlatformAction.TEXT_SHARE, content = "hola")))
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.SavePlace("casa", "Córdoba 1234")))
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.Navigate("Retiro")))
    assertFalse(InstinctProposalValidator.allowed(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 600, 30)))
  }

  @Test fun `several steps, conditions, negations and questions never become a proposal`() {
    assertEquals(ProposalCheck.Reason.NOT_LOCAL, (check("poner una alarma a las 7 y prender la linterna") as ProposalCheck.Invalid).reason)
    assertEquals(ProposalCheck.Reason.UNSAFE, (check("si llueve poner una alarma a las 7") as ProposalCheck.Invalid).reason)
    assertEquals(ProposalCheck.Reason.UNSAFE, (check("no poner alarma") as ProposalCheck.Invalid).reason)
    assertEquals(ProposalCheck.Reason.UNSAFE, (check("qué hora es") as ProposalCheck.Invalid).reason)
    // An ambiguous hour is a clarification, not a single local command.
    assertEquals(ProposalCheck.Reason.NOT_LOCAL, (check("poner una alarma a las 7") as ProposalCheck.Invalid).reason)
  }

  @Test fun `the semantic guard reviews the grammar output with the line as the request`() {
    val drifted = InstinctProposalValidator.check("poner un temporizador de 25 minutos",
      { PhoneRoute.Local(PhoneCommand.Timer(600)) }, Calendar.getInstance())
    assertEquals(ProposalCheck.Reason.SEMANTIC, (drifted as ProposalCheck.Invalid).reason)
    val percent = InstinctProposalValidator.check("poner el volumen al 30 por ciento",
      { PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, 80)) }, Calendar.getInstance())
    assertEquals(ProposalCheck.Reason.SEMANTIC, (percent as ProposalCheck.Invalid).reason)
  }

  // Filters

  private class FakeNotification(
    override val packageName: String = "com.whatsapp",
    override val key: String = "0|com.whatsapp|1|chat|10001",
    val group: Boolean = false,
    val titleValue: String? = "Instinct",
    val latest: ProposalNotification.LatestMessage? = ProposalNotification.LatestMessage("Instinct"),
    val textValue: String? = "#insto poner una alarma a las 07:00",
  ) : ProposalNotification {
    val reads = mutableListOf<String>()
    override fun isGroup(): Boolean { reads += "group"; return group }
    override fun title(): String? { reads += "title"; return titleValue }
    override fun latestMessage(): ProposalNotification.LatestMessage? { reads += "sender"; return latest }
    override fun text(): String? { reads += "text"; return textValue }
  }

  private fun config(now: Long = 61_000L) = ProposalFilterConfig("com.whatsapp", "Instinct", now)

  @Test fun `another package is dropped without reading anything of the notification`() {
    val filter = InstinctProposalFilter()
    val notification = FakeNotification(packageName = "com.example.chat")
    assertEquals(InstinctProposalFilter.Stage.PACKAGE, filter.evaluate(notification, config()).stage)
    assertEquals(emptyList<String>(), notification.reads)
    assertEquals(InstinctProposalFilter.Stage.PACKAGE,
      filter.evaluate(FakeNotification(), config().copy(expectedPackage = null)).stage)
  }

  @Test fun `a group conversation is dropped before names or text are read`() {
    val notification = FakeNotification(group = true)
    assertEquals(InstinctProposalFilter.Stage.GROUP, InstinctProposalFilter().evaluate(notification, config()).stage)
    assertEquals(listOf("group"), notification.reads)
  }

  @Test fun `the contact name must match exactly`() {
    val filter = InstinctProposalFilter()
    for (name in listOf("Instinct ", "instinct", "Instinct 2", "Instinkt", "Instinct (2 mensajes)")) {
      val bySender = FakeNotification(latest = ProposalNotification.LatestMessage(name))
      assertEquals(name, InstinctProposalFilter.Stage.CONTACT, filter.evaluate(bySender, config()).stage)
      assertFalse("text" in bySender.reads)
      val byTitle = FakeNotification(titleValue = name, latest = null)
      assertEquals(name, InstinctProposalFilter.Stage.CONTACT, filter.evaluate(byTitle, config()).stage)
    }
    // The user's own reply in the same chat is not Instinct's message.
    assertEquals(InstinctProposalFilter.Stage.CONTACT,
      filter.evaluate(FakeNotification(latest = ProposalNotification.LatestMessage(null)), config()).stage)
    assertEquals(InstinctProposalFilter.Stage.ACCEPTED, filter.evaluate(FakeNotification(titleValue = "Instinct", latest = null), config()).stage)
  }

  @Test fun `Instinct can write on its own initiative`() {
    // No previous message from Insto is needed: the newest message of the contact is enough.
    val notification = FakeNotification()
    assertEquals(InstinctProposalFilter.Stage.ACCEPTED, InstinctProposalFilter().evaluate(notification, config()).stage)
    assertEquals(listOf("group", "sender", "text"), notification.reads)
  }

  @Test fun `the anti-loop brake allows six automatic runs every ten minutes`() {
    val clock = Clock()
    val budget = AutoRunBudget({ clock.now })
    repeat(6) { assertTrue(budget.tryAcquire()) }
    assertFalse(budget.tryAcquire())
    clock.now = 10 * 60_000L
    assertTrue(budget.tryAcquire())
  }

  @Test fun `the same message is processed once and a new one again`() {
    val filter = InstinctProposalFilter()
    assertEquals(listOf("poner una alarma a las 07:00"), (filter.evaluate(FakeNotification(), config()) as InstinctProposalFilter.Outcome.Accepted).orders)
    assertEquals(InstinctProposalFilter.Stage.DUPLICATE, filter.evaluate(FakeNotification(), config()).stage)
    assertEquals(InstinctProposalFilter.Stage.ACCEPTED,
      filter.evaluate(FakeNotification(textValue = "#insto poner un temporizador de 25 minutos"), config()).stage)
    assertEquals(1, filter.counters()[InstinctProposalFilter.Stage.DUPLICATE])
  }

  @Test fun `a message without proposal lines is not remembered`() {
    val filter = InstinctProposalFilter()
    assertEquals(InstinctProposalFilter.Stage.NO_PROPOSAL, filter.evaluate(FakeNotification(textValue = "Hola"), config()).stage)
    assertEquals(InstinctProposalFilter.Stage.NO_PROPOSAL, filter.evaluate(FakeNotification(textValue = "Hola"), config()).stage)
  }

  // Proposal book

  private class Clock(var now: Long = 0L)

  @Test fun `a proposal expires after ten minutes and cannot run`() {
    val clock = Clock()
    val book = ProposalBook({ clock.now })
    val proposal = book.offer("poner una alarma a las 07:00", PhoneCommand.Alarm(7, 0), "fp")!!
    clock.now = ProposalBook.TTL_MILLIS + 1
    assertEquals(ProposalBook.Claim.Expired, book.claim(proposal.id, "fp"))
    assertEquals(ProposalBook.Claim.Expired, book.claim(proposal.id, "fp"))
    assertTrue(book.pending().isEmpty())
  }

  @Test fun `confirming twice executes once`() {
    val book = ProposalBook({ 0L })
    val proposal = book.offer("prender la linterna", PhoneCommand.Torch(true), "fp")!!
    assertEquals(ProposalBook.Claim.Granted(proposal), book.claim(proposal.id, "fp"))
    assertEquals(ProposalBook.Claim.Used, book.claim(proposal.id, "fp"))
    assertFalse(book.discard(proposal.id))
  }

  @Test fun `a discarded proposal can never be confirmed`() {
    val book = ProposalBook({ 0L })
    val proposal = book.offer("prender la linterna", PhoneCommand.Torch(true), "fp")!!
    assertTrue(book.discard(proposal.id))
    assertEquals(ProposalBook.Claim.Used, book.claim(proposal.id, "fp"))
    assertEquals(ProposalBook.Claim.Unknown, book.claim(999, "fp"))
  }

  @Test fun `a changed configuration refuses the proposal for good`() {
    val book = ProposalBook({ 0L })
    val proposal = book.offer("prender la linterna", PhoneCommand.Torch(true), "fp")!!
    assertEquals(ProposalBook.Claim.Changed, book.claim(proposal.id, "otro"))
    assertEquals(ProposalBook.Claim.Used, book.claim(proposal.id, "fp"))
    val off = book.offer("apagar la linterna", PhoneCommand.Torch(false), "fp")!!
    assertEquals(ProposalBook.Claim.Changed, book.claim(off.id, null))
    assertNotEquals(ProposalBook.fingerprint("Instinct", "+54 11", "com.whatsapp"), ProposalBook.fingerprint("Instinct", "+54 12", "com.whatsapp"))
  }

  @Test fun `the notification path needs the proposal token`() {
    val book = ProposalBook({ 0L })
    val proposal = book.offer("prender la linterna", PhoneCommand.Torch(true), "fp")!!
    assertEquals(ProposalBook.Claim.Unknown, book.claim(proposal.id, "fp", token = "adivinado", requireToken = true))
    assertEquals(ProposalBook.Claim.Unknown, book.claim(proposal.id, "fp", token = null, requireToken = true))
    assertEquals(ProposalBook.Claim.Granted(proposal), book.claim(proposal.id, "fp", proposal.token, requireToken = true))
  }

  @Test fun `the same command is not offered twice while pending`() {
    val book = ProposalBook({ 0L })
    val first = book.offer("prender la linterna", PhoneCommand.Torch(true), "fp")!!
    assertNull(book.offer("prender la linterna", PhoneCommand.Torch(true), "fp"))
    book.claim(first.id, "fp")
    assertNotNull(book.offer("prender la linterna", PhoneCommand.Torch(true), "fp"))
    assertEquals(2, book.discardAll().size + 1)
  }
}
