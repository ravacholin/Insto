package com.example.phone

import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** «Buzón firmado»: the key, the canonical string, HMAC, verification, seen ids, the poll and the catalog. Pure. */
class InstinctMailboxTest {
  private val key = "clave-de-prueba-0123456789-abcdefghijkl"
  private val id = "3b241101-e2bb-4255-8caf-4136c566a962"
  private val at = "2026-11-21T09:30:00-03:00"
  private val lines = listOf("#insto poner el volumen al 30 por ciento", "#insto avisarme Te bajé el volumen para la clase.")
  /** Computed apart, with Python's hmac module, for exactly this key and canonical string. */
  private val vector = "0118c3671f915c0c55cc4364838ce32afcc3ce33e984617ea69b60f2377859df"
  private val atMillis = InstinctMailboxSignature.instant(at)!!
  private fun dir(): File = Files.createTempDirectory("insto-mailbox").toFile()

  private fun signed(id: String = this.id, at: String = this.at, lines: List<String> = this.lines, key: String = this.key) =
    MailboxOrder(id, at, lines, InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, at, lines)))

  // Key

  @Test fun `a generated key is 32 random bytes in base64url and a pasted one needs 32 to 200 characters without spaces`() {
    val a = InstinctMailboxKey.generate(); val b = InstinctMailboxKey.generate()
    assertEquals(43, a.length)
    assertTrue(a.matches(Regex("[A-Za-z0-9_-]{43}")))
    assertNotEquals(a, b)
    assertTrue(InstinctMailboxKey.valid(a))
    assertFalse(InstinctMailboxKey.valid("corta"))
    assertFalse(InstinctMailboxKey.valid("x".repeat(31)))
    assertTrue(InstinctMailboxKey.valid("x".repeat(32)))
    assertFalse(InstinctMailboxKey.valid("x".repeat(201)))
    assertFalse(InstinctMailboxKey.valid("x".repeat(20) + " " + "y".repeat(20)))
    assertEquals("••••ijkl", InstinctMailboxKey.mask(key))
    assertEquals("sin clave", InstinctMailboxKey.mask(null))
  }

  // Canonical string and HMAC

  @Test fun `the canonical string and HMAC match fixed vectors`() {
    assertEquals("insto-v4\n$id\n$at\n${lines[0]}\n${lines[1]}", InstinctMailboxSignature.canonical(id, at, lines))
    assertEquals(vector, InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, at, lines)))
    // RFC-style sanity vector for HMAC-SHA256.
    assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
      InstinctMailboxSignature.sign("key", "The quick brown fox jumps over the lazy dog"))
  }

  @Test fun `signatures are compared in constant time and only as lowercase hex`() {
    assertTrue(InstinctMailboxSignature.matches(vector, vector))
    assertFalse(InstinctMailboxSignature.matches(vector, vector.uppercase()))
    assertFalse(InstinctMailboxSignature.matches(vector, vector.dropLast(1)))
    assertFalse(InstinctMailboxSignature.matches(vector, vector.dropLast(1) + "0"))
    val source = File("app/src/main/java/com/example/phone/InstinctMailbox.kt").takeIf { it.exists() }
      ?: File("src/main/java/com/example/phone/InstinctMailbox.kt")
    assertTrue("MessageDigest.isEqual", source.readText().contains("MessageDigest.isEqual("))
  }

  // Verification

  @Test fun `a valid order passes`() {
    assertEquals(InstinctMailboxSignature.Verdict.VALID, InstinctMailboxSignature.verify(key, signed(), atMillis + 60_000) { false })
  }

  @Test fun `a bad signature, a changed line or another key is refused`() {
    val now = atMillis
    assertEquals(InstinctMailboxSignature.Verdict.BAD_SIGNATURE, InstinctMailboxSignature.verify(key, signed().copy(sig = "0".repeat(64)), now) { false })
    assertEquals(InstinctMailboxSignature.Verdict.BAD_SIGNATURE,
      InstinctMailboxSignature.verify(key, signed().copy(lines = listOf("#insto poner el volumen al 100 por ciento", lines[1])), now) { false })
    assertEquals(InstinctMailboxSignature.Verdict.BAD_SIGNATURE, InstinctMailboxSignature.verify(key, signed(key = "otra-clave-0123456789-abcdefghijklmn"), now) { false })
    assertEquals(InstinctMailboxSignature.Verdict.BAD_SIGNATURE, InstinctMailboxSignature.verify(key, signed(id = "no-es-uuid"), now) { false })
    assertEquals(InstinctMailboxSignature.Verdict.BAD_SIGNATURE, InstinctMailboxSignature.verify(key, signed(at = "ayer"), now) { false })
  }

  @Test fun `an order older than 15 minutes or more than 2 minutes ahead is refused`() {
    val order = signed()
    assertEquals(InstinctMailboxSignature.Verdict.VALID, InstinctMailboxSignature.verify(key, order, atMillis + 15 * 60_000) { false })
    assertEquals(InstinctMailboxSignature.Verdict.EXPIRED, InstinctMailboxSignature.verify(key, order, atMillis + 15 * 60_000 + 1) { false })
    assertEquals(InstinctMailboxSignature.Verdict.VALID, InstinctMailboxSignature.verify(key, order, atMillis - 2 * 60_000) { false })
    assertEquals(InstinctMailboxSignature.Verdict.EXPIRED, InstinctMailboxSignature.verify(key, order, atMillis - 2 * 60_000 - 1) { false })
  }

  @Test fun `ISO instants need a zone`() {
    assertEquals(InstinctMailboxSignature.instant("2026-11-21T12:30:00Z"), atMillis)
    assertEquals(InstinctMailboxSignature.instant("2026-11-21T12:30:00.000Z"), atMillis)
    assertNull(InstinctMailboxSignature.instant("2026-11-21T09:30:00"))
    assertNull(InstinctMailboxSignature.instant("2026-13-40T09:30:00Z"))
  }

  // Seen ids

  @Test fun `a repeated id is refused, also after a restart that rereads the file`() {
    val file = File(dir(), InstinctMailboxSeen.FILE)
    var now = atMillis
    val seen = InstinctMailboxSeen(file) { now }
    val first = InstinctMailboxPoll.process(listOf(signed()), key, now, seen)
    assertEquals(1, first.valid.size)
    // «Reiniciar»: a new instance reading the same file.
    val reborn = InstinctMailboxSeen(file) { now }
    assertTrue(reborn.contains(id.uppercase()))
    val again = InstinctMailboxPoll.process(listOf(signed()), key, now, reborn)
    assertEquals(0, again.valid.size)
    assertEquals(1, again.repeated)
    // Kept 48 hours.
    now += InstinctMailboxSeen.RETENTION_MILLIS + 1
    assertFalse(InstinctMailboxSeen(file) { now }.contains(id))
  }

  @Test fun `the id is seen on disk before anything is dispatched`() {
    val file = File(dir(), InstinctMailboxSeen.FILE)
    val seen = InstinctMailboxSeen(file) { atMillis }
    val outcome = InstinctMailboxPoll.process(listOf(signed()), key, atMillis, seen)
    // What the caller dispatches next is already on disk.
    assertTrue(outcome.valid.isNotEmpty())
    assertTrue(file.readText().contains(id))
  }

  @Test fun `at most 10 orders per poll, every id read is taken and invalid ones are only counted`() {
    val orders = (0 until 12).map { i -> signed(id = "3b241101-e2bb-4255-8caf-4136c566a9%02d".format(i)) } +
      listOf(signed().copy(sig = "f".repeat(64)))
    val outcome = InstinctMailboxPoll.process(listOf(signed().copy(sig = "f".repeat(64))) + orders, key, atMillis,
      InstinctMailboxSeen(File(dir(), "s.json")) { atMillis })
    assertEquals(9, outcome.valid.size)
    assertEquals(1, outcome.badSignatures)
    assertEquals(10, outcome.taken.size)
    // Two copies of one id in the same poll run once.
    val twice = InstinctMailboxPoll.process(listOf(signed(), signed()), key, atMillis, InstinctMailboxSeen(File(dir(), "t.json")) { atMillis })
    assertEquals(1, twice.valid.size)
    assertEquals(1, twice.repeated)
  }

  @Test fun `at most three lines of an order are read, like a WhatsApp message`() {
    val many = (1..5).map { "#insto poner un temporizador de $it minutos" }
    val order = signed(lines = many)
    assertEquals(InstinctMailboxSignature.Verdict.VALID, InstinctMailboxSignature.verify(key, order, atMillis) { false })
    assertEquals(3, InstinctProposalProtocol.orders(order.lines.joinToString("\n")).size)
  }

  // Wire

  @Test fun `the mailbox answer is parsed strictly`() {
    val text = """{"ok":true,"ordenes":[{"orden_id":"$id","at":"$at","lineas":["${lines[0]}"],"firma":"$vector","recibido":"x"}]}"""
    assertEquals(listOf(MailboxOrder(id, at, listOf(lines[0]), vector)), InstinctMailboxPoll.parse(text))
    assertNull(InstinctMailboxPoll.parse("""{"ok":false,"error":"token"}"""))
    assertTrue(InstinctMailboxPoll.tokenRejected("""{"ok":false,"error":"token"}"""))
    assertNull(InstinctMailboxPoll.parse("basura"))
    assertEquals(emptyList<MailboxOrder>(), InstinctMailboxPoll.parse("""{"ok":true,"ordenes":[]}"""))
  }

  @Test fun `the client reads and takes through the log transport`() {
    val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
    val fake = FakeHttp { ex ->
      when {
        ex.request.method == "GET" && ex.url.contains("action=ordenes") ->
          FakeHttp.Reply(200, """{"ok":true,"ordenes":[{"orden_id":"$id","at":"$at","lineas":["${lines[0]}"],"firma":"$vector"}]}""")
        ex.request.method == "POST" -> FakeHttp.Reply(200, """{"ok":true}""")
        else -> FakeHttp.Reply(404)
      }
    }
    val client = InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build()))
    val fetched = client.fetch(url, "token-largo", 10) as InstinctMailboxClient.Fetch.Orders
    assertEquals(1, fetched.orders.size)
    assertTrue(fake.last.url.contains("token=token-largo"))
    assertTrue(client.take(url, "token-largo", listOf(id)))
    assertTrue(fake.last.bodyText().contains("\"action\":\"tomadas\""))
    val rejected = InstinctMailboxClient(InstinctLogClient(OkHttpClient.Builder().addInterceptor(FakeHttp { FakeHttp.Reply(200, """{"ok":false,"error":"token"}""") }).build()))
    assertEquals(InstinctMailboxClient.Fetch.Failed("token rechazado"), rejected.fetch(url, "x".repeat(10)))
  }

  // avisarme

  @Test fun `avisarme is a notice of 1 to 200 plain characters`() {
    val parser = PhoneCommandParser()
    assertEquals(ProposalCheck.Notice("avisarme Llegó el paquete.", "Llegó el paquete."), InstinctProposalValidator.check("avisarme Llegó el paquete.", parser))
    assertEquals("a b", InstinctNotice.text("avisarme a\nb"))
    assertTrue(InstinctProposalValidator.check("avisarme", parser) is ProposalCheck.Invalid)
    assertTrue(InstinctProposalValidator.check("avisarme " + "x".repeat(201), parser) is ProposalCheck.Invalid)
    assertTrue(InstinctProposalValidator.check("avisarme " + "x".repeat(200), parser) is ProposalCheck.Notice)
    // Never deferred.
    assertTrue(InstinctProposalValidator.check("en 10 minutos: avisarme hola", parser) is ProposalCheck.Invalid)
  }

  // The WhatsApp cap

  @Test fun `the cap for WhatsApp only hardens unsigned orders`() {
    assertEquals(AutonomyDecision.Confirm(InstinctSourceCap.REASON),
      InstinctSourceCap.apply(AutonomyDecision.Run, ProposalSource.WHATSAPP, InstinctAutonomy.CONFIRMAR))
    assertEquals(AutonomyDecision.Run, InstinctSourceCap.apply(AutonomyDecision.Run, ProposalSource.BUZON, InstinctAutonomy.CONFIRMAR))
    assertEquals(AutonomyDecision.Run, InstinctSourceCap.apply(AutonomyDecision.Run, ProposalSource.WHATSAPP, InstinctAutonomy.SOLO))
    val refuse = AutonomyDecision.Refuse("x")
    assertEquals(refuse, InstinctSourceCap.apply(refuse, ProposalSource.WHATSAPP, InstinctAutonomy.CONFIRMAR))
  }

  @Test fun `a scheduled order keeps its source`() {
    val order = ScheduledOrder("i", "o", "inner", 1, "f", null, "a las 10:00", 0, source = ProposalSource.BUZON)
    assertEquals(ProposalSource.BUZON, InstinctScheduleStore.decode(InstinctScheduleStore.encode(listOf(order))).single().source)
    assertEquals(ProposalSource.WHATSAPP, InstinctScheduleStore.decode(InstinctScheduleStore.encode(listOf(order.copy(source = ProposalSource.WHATSAPP)))).single().source)
  }

  @Test fun `mailbox acknowledgements go to the log even with the WhatsApp channel`() {
    var now = 0L
    val ledger = InstinctAckLedger<String>({ now }, { InstinctReplyMode.ALWAYS }, channel = { InstinctAckChannel.WHATSAPP })
    val ticket = ledger.open("handle", 1, "abc123abc123", logOnly = true)
    val out = ledger.resolve(ticket, 0, AckEntry(AckKind.OK, "poner el volumen al 30 por ciento", "Listo."))!!
    assertNull("never the chat", out.text)
    assertNull(out.handle)
    assertEquals(1, out.events.size)
    assertEquals("abc123abc123", out.message)
    // Kept long enough for a deferred follow-up.
    ledger.link(ledger.open(null, 1, "m", logOnly = true), 0, 7)
    assertTrue(ledger.logs(7))
    now += 2 * ProposalBook.TTL_MILLIS
    assertTrue(ledger.tracks(7))
  }

  @Test fun `the mailbox message id comes from the SHA-256 of the order id`() {
    assertEquals(sha256(id).take(12), InstinctMailboxSignature.messageId(id))
    assertEquals(InstinctMailboxSignature.messageId(id), InstinctMailboxSignature.messageId(id.uppercase()))
  }

  // Catalog

  @Test fun `the catalog explains how to sign and never contains the key`() {
    val log = InstinctCatalogLog("https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec", "token-largo")
    val paste = InstinctProtocolCatalog.text(emptyList(), log = log, mailbox = log)
    assertTrue(paste.contains("Buzón firmado"))
    assertTrue(paste.contains("insto-v4\n$id\n$at\n"))
    assertTrue(paste.contains("hmac_sha256(clave.utf8, cadena.utf8).hex()"))
    assertTrue(paste.contains("POST ${log.url}"))
    assertFalse(paste.contains(key))
    assertFalse(paste.contains(key.takeLast(8)))
    val chat = InstinctProtocolCatalog.text(emptyList(), forChat = true, log = InstinctCatalogLog.HIDDEN, mailbox = InstinctCatalogLog.HIDDEN)
    assertFalse(chat.contains(key)); assertFalse(chat.contains("token-largo")); assertFalse(chat.contains("script.google.com"))
    assertTrue(InstinctAck.safe(chat))
    assertTrue(InstinctProposalProtocol.orders(chat).isEmpty())
    assertTrue(chat.contains("› #insto avisarme"))
  }

  /** The reference copy in docs is what the code generates. Set INSTO_WRITE_CATALOG=1 to regenerate it. */
  @Test fun `the reference copy of the mailbox variant matches the generated catalog`() {
    val file = listOf(File("docs/instinct-catalog-buzon.txt"), File("../docs/instinct-catalog-buzon.txt")).first { it.parentFile?.isDirectory == true }
    val expected = InstinctProtocolCatalog.text(emptyList(), forChat = true, log = InstinctCatalogLog.HIDDEN, mailbox = InstinctCatalogLog.HIDDEN) + "\n"
    if (System.getenv("INSTO_WRITE_CATALOG") == "1") file.writeText(expected)
    assertEquals("Regenerá docs/instinct-catalog-buzon.txt con INSTO_WRITE_CATALOG=1", expected, file.readText())
  }
}
