package com.example.phone

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.TimeZone
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Bitácora» (1.15.0): the event, the guard, the outbox, the client with Apps Script's redirect, and the ledger's channels. */
class InstinctLogTest {
  private val zone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
  private val at = 1_795_000_000_000L // 2026-11-18T…-03:00
  private val hash = MessageDedupe.id("0|com.whatsapp|1|chat|10001", "#insto subir el volumen")
  private val url = "https://script.google.com/macros/s/AKfycbx1234567890abcdef/exec"
  private val token = "s3cr3t-token-9876"

  private fun event(entry: AckEntry, id: String = "e1") = InstinctLogEvents.of(entry, hash, at, zone, id)
  private fun plain(id: String, order: String = "subir el volumen", detail: String = "Listo.") =
    InstinctLogEvent(id, "2026-11-18T09:00:00-03:00", "ok", order, detail, "abc123abc123", InstinctProtocolCatalog.TITLE)

  // Event

  @Test fun `an event carries the same order and result text as the chat reply`() {
    val e = event(AckEntry(AckKind.OK, "subir el volumen", "Volumen multimedia ajustado."))!!
    assertEquals("e1", e.id)
    assertEquals(InstinctLogEvents.iso(at, zone), e.at)
    assertTrue(e.at, e.at.matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d-03:00")))
    assertEquals("ok", e.kind)
    assertEquals("subir el volumen", e.order)
    assertEquals("Volumen multimedia ajustado.", e.detail)
    assertEquals(hash.take(12), e.message)
    assertEquals("Protocolo Insto v8", e.protocol)
    assertEquals(setOf("id", "at", "kind", "order", "detail", "message", "protocol"), e.json().keys().asSequence().toSet())
    assertEquals(e, InstinctLogEvent.from(e.json()))
  }

  @Test fun `every kind is its prefix without #insto-`() {
    assertEquals(listOf("ok", "espera", "error", "incierto", "descartada", "vencida", "programada", "deshecho"), AckKind.entries.map { it.code })
    for (kind in AckKind.entries) assertEquals(kind.code, event(AckEntry(kind, "a", "b"))!!.kind)
  }

  @Test fun `the order is cut to 80 characters and line breaks and controls are flattened, as in the chat`() {
    val long = "agendar " + "x".repeat(200)
    val entry = AckEntry(AckKind.ERROR, long + "\nsegunda", "uno\n\tdos\r\ntres\u0007")
    val e = event(entry)!!
    assertEquals(80, e.order.length)
    assertTrue(e.order.endsWith("…"))
    assertEquals(InstinctAck.line(entry).removePrefix("#insto-error ").substringBefore(" → "), e.order)
    assertEquals("uno dos tres", e.detail)
    assertEquals(InstinctAck.line(entry).substringAfter(" → "), e.detail)
    assertTrue(e.fields.none { f -> f.any { it.isISOControl() } })
    val huge = event(AckEntry(AckKind.OK, "a", "y".repeat(5_000)))!!
    assertEquals(InstinctAck.MAX_CHARS, huge.detail.length)
  }

  @Test fun `the message id is the first 12 hex digits of the dedupe hash, never text`() {
    assertEquals(12, InstinctLogEvents.messageId(hash).length)
    assertEquals(hash.take(12), InstinctLogEvents.messageId(hash))
    assertEquals("", InstinctLogEvents.messageId("#insto subir el volumen"))
    assertEquals("", InstinctLogEvents.of(AckEntry(AckKind.OK, "a", "b"), "hola mundo", at, zone)!!.message)
  }

  @Test fun `the filter hands the short hash of an accepted message`() {
    val n = object : ProposalNotification {
      override val packageName = "com.whatsapp"
      override val key = "k1"
      override fun isGroup() = false
      override fun title() = "Instinct"
      override fun latestMessage(): ProposalNotification.LatestMessage? = null
      override fun text() = "Hola\n#insto subir el volumen"
    }
    val outcome = InstinctProposalFilter().evaluate(n, ProposalFilterConfig("com.whatsapp", "Instinct", 0L))
    val accepted = outcome as InstinctProposalFilter.Outcome.Accepted
    assertEquals(MessageDedupe.id("k1", "Hola\n#insto subir el volumen").take(12), accepted.message)
    assertFalse(accepted.message.contains("insto"))
  }

  // Anti-loop guard

  @Test fun `no field may start with #insto, in any field`() {
    val good = plain("e1")
    assertTrue(InstinctLogEvents.safe(good))
    val bad = "#insto subir el volumen"
    val variants = listOf(good.copy(id = bad), good.copy(at = bad), good.copy(kind = bad), good.copy(order = bad),
      good.copy(detail = bad), good.copy(message = bad), good.copy(protocol = bad), good.copy(order = "  $bad"),
      good.copy(detail = "ok\n$bad"))
    for (v in variants) assertFalse(v.toString(), InstinctLogEvents.safe(v))
    // An order that would read as one is never turned into an event.
    assertNull(event(AckEntry(AckKind.OK, bad, "Listo.")))
    assertNull(event(AckEntry(AckKind.OK, "a", bad)))
    // A flattened «#insto» in the middle is text, not an order.
    assertEquals("x #insto y", event(AckEntry(AckKind.OK, "a", "x\n#insto y"))!!.detail)
    // The outbox refuses unsafe events too.
    val box = InstinctLogOutbox(tempFile())
    box.add(listOf(good.copy(id = "e2", order = bad), good))
    assertEquals(listOf("e1"), box.batch().map { it.id })
  }

  // Outbox

  private fun tempFile(): File = File(Files.createTempDirectory("insto-outbox").toFile(), InstinctLogOutbox.FILE)

  @Test fun `adding the same id twice keeps one copy, in order`() {
    val box = InstinctLogOutbox(tempFile())
    box.add(listOf(plain("a"), plain("b")))
    box.add(listOf(plain("b"), plain("c"), plain("c")))
    assertEquals(listOf("a", "b", "c"), box.batch().map { it.id })
  }

  @Test fun `only accepted ids are removed`() {
    val box = InstinctLogOutbox(tempFile())
    box.add((1..5).map { plain("e$it") })
    box.remove(setOf("e2", "e4", "otro"))
    assertEquals(listOf("e1", "e3", "e5"), box.batch().map { it.id })
    box.remove(emptySet())
    assertEquals(3, box.size())
  }

  @Test fun `at most 200 events, the oldest dropped and counted, in batches of 50`() {
    val box = InstinctLogOutbox(tempFile())
    box.add((1..150).map { plain("e$it") })
    assertEquals(30, box.add((151..230).map { plain("e$it") }))
    val snapshot = box.read()
    assertEquals(200, snapshot.events.size)
    assertEquals(30, snapshot.dropped)
    assertEquals("e31", snapshot.events.first().id)
    assertEquals("e230", snapshot.events.last().id)
    assertEquals(50, box.batch().size)
    assertEquals("e31", box.batch().first().id)
    box.clearDropped()
    assertEquals(0, box.dropped())
  }

  @Test fun `writes are atomic, a complete file with no temporary leftovers, and garbage reads as empty`() {
    val file = tempFile()
    val box = InstinctLogOutbox(file)
    repeat(20) { box.add(listOf(plain("e$it"))) }
    box.remove(setOf("e0"))
    assertEquals(19, InstinctLogOutbox.decode(file.readText()).events.size)
    assertEquals(listOf(InstinctLogOutbox.FILE), file.parentFile.list()!!.toList())
    // A file cut in the middle (never produced by the store) is ignored, never half-read.
    file.writeText(file.readText().take(40))
    assertEquals(0, InstinctLogOutbox(file).size())
    box.remove(box.batch().map { it.id }.toSet())
    InstinctLogOutbox(tempFile()).apply { add(listOf(plain("x"))); remove(setOf("x")) }.also { assertEquals(0, it.size()) }
  }

  @Test fun `an empty outbox leaves no file`() {
    val file = tempFile()
    val box = InstinctLogOutbox(file)
    box.add(listOf(plain("a")))
    assertTrue(file.exists())
    box.remove(setOf("a"))
    assertFalse(file.exists())
  }

  // Configuration

  @Test fun `the URL must be an Apps Script deployment ending in exec`() {
    assertTrue(InstinctLogConfig.validUrl(url))
    assertTrue(InstinctLogConfig.validUrl("  $url "))
    for (bad in listOf("", "http://script.google.com/macros/s/AKfycbx1234567890/exec",
      "https://script.google.com/macros/s/AKfycbx1234567890/dev", "https://script.google.com/macros/s//exec",
      "https://evil.com/macros/s/AKfycbx1234567890/exec", "https://script.google.com.evil.com/macros/s/AKfycbx1234567890/exec",
      "https://script.google.com/macros/s/AKfycbx1234567890/exec?x=1", "https://script.google.com/macros/s/AKfy/cbx1234567890/exec",
      "https://script.google.com/macros/s/AKfycbx1234567890/exec/"))
      assertFalse(bad, InstinctLogConfig.validUrl(bad))
  }

  @Test fun `the token is only ever shown by its last four characters`() {
    assertEquals("••••9876", InstinctLogConfig.mask(token))
    assertFalse(InstinctLogConfig.mask(token).contains(token.dropLast(4)))
    assertEquals("sin token", InstinctLogConfig.mask(null))
    assertTrue(InstinctLogConfig.validToken(token))
    assertFalse(InstinctLogConfig.validToken("corto"))
    assertFalse(InstinctLogConfig.validToken("con espacio adentro"))
  }

  // Client

  private fun client(respond: (FakeHttp.Exchange) -> FakeHttp.Reply): Pair<InstinctLogClient, FakeHttp> {
    val fake = FakeHttp(respond)
    return InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build()) to fake
  }

  private val final = "https://script.googleusercontent.com/macros/echo?user_content_key=abc&lib=xyz"

  @Test fun `one POST with token and events, then the 302 is followed with a GET and the final JSON read`() {
    val (c, fake) = client { ex ->
      if (ex.request.method == "POST") FakeHttp.Reply(302, "", mapOf("Location" to final))
      else FakeHttp.Reply(200, """{"ok":true,"accepted":["e1","e2","ajeno"]}""")
    }
    val result = c.send(url, token, listOf(plain("e1"), plain("e2"), plain("e3")))
    assertEquals(InstinctLogClient.Result.Accepted(setOf("e1", "e2")), result)
    assertEquals(2, fake.exchanges.size)
    val post = fake.exchanges[0]
    assertEquals("POST", post.request.method)
    assertEquals(url, post.url)
    val body = JSONObject(post.bodyText())
    assertEquals(setOf("token", "events"), body.keys().asSequence().toSet())
    assertEquals(token, body.getString("token"))
    assertEquals(listOf("e1", "e2", "e3"), (0 until 3).map { body.getJSONArray("events").getJSONObject(it).getString("id") })
    val get = fake.exchanges[1]
    assertEquals("GET", get.request.method)
    assertEquals(final, get.url)
    assertEquals(0, get.body.size)
    assertEquals(15_000, post.readMillis)
    assertTrue(fake.closed)
  }

  @Test fun `a redirect outside Google's hosts or to plain HTTP is never followed`() {
    for (location in listOf("https://evil.example/collect", "http://script.googleusercontent.com/x")) {
      val (c, fake) = client { FakeHttp.Reply(302, "", mapOf("Location" to location)) }
      assertEquals(InstinctLogClient.Result.Failed(InstinctLogClient.Failure.INVALID), c.send(url, token, listOf(plain("e1"))))
      assertEquals(1, fake.exchanges.size)
    }
    val (loop, fake) = client { FakeHttp.Reply(302, "", mapOf("Location" to final)) }
    assertEquals(InstinctLogClient.Result.Failed(InstinctLogClient.Failure.INVALID), loop.send(url, token, listOf(plain("e1"))))
    assertEquals(3, fake.exchanges.size)
  }

  @Test fun `failures are classified without content`() {
    fun result(reply: FakeHttp.Reply) = client { reply }.first.send(url, token, listOf(plain("e1")))
    assertEquals(InstinctLogClient.Failure.TOKEN, (result(FakeHttp.Reply(200, """{"ok":false,"error":"token"}""")) as InstinctLogClient.Result.Failed).failure)
    assertEquals(InstinctLogClient.Failure.TOKEN, (result(FakeHttp.Reply(403)) as InstinctLogClient.Result.Failed).failure)
    assertEquals("HTTP 500", (result(FakeHttp.Reply(500)) as InstinctLogClient.Result.Failed).reason)
    assertEquals("respuesta inválida", (result(FakeHttp.Reply(200, "<html>login</html>")) as InstinctLogClient.Result.Failed).reason)
    assertEquals(InstinctLogClient.Failure.INVALID, (result(FakeHttp.Reply(200, """{"ok":true}""")) as InstinctLogClient.Result.Failed).failure)
    val offline = client { throw IOException("unreachable") }.first.send(url, token, listOf(plain("e1")))
    assertEquals("sin red", (offline as InstinctLogClient.Result.Failed).reason)
    val (unconfigured, fake) = client { FakeHttp.Reply(200, "{}") }
    assertEquals(InstinctLogClient.Failure.NOT_CONFIGURED, (unconfigured.send("https://example.com/exec", token, listOf(plain("e1"))) as InstinctLogClient.Result.Failed).failure)
    assertTrue("nothing is sent to an address that is not an Apps Script", fake.exchanges.isEmpty())
  }

  // Ledger channels

  private var now = 0L
  private var mode = InstinctReplyMode.ALWAYS
  private var channel = InstinctAckChannel.BITACORA
  private val ledger = InstinctAckLedger<String>({ now }, { mode }, channel = { channel })
  private fun ok(order: String) = AckEntry(AckKind.OK, order, "Listo.")

  @Test fun `with Bitácora the handle is never kept nor returned, and nothing goes to the chat`() {
    val ticket = ledger.open("respuesta-de-whatsapp", 2, hash.take(12))
    ledger.link(ticket, 0, 1L)
    assertNull(ledger.resolve(ticket, 1, AckEntry(AckKind.ERROR, "llamar a Ana", "no")))
    val out = ledger.report(1L, ok("subir el volumen"))!!
    assertNull(out.handle)
    assertNull(out.text)
    assertEquals(listOf("subir el volumen", "llamar a Ana"), out.events.map { it.order })
    assertEquals(hash.take(12), out.message)
    assertNull(ledger.latestHandle())
    assertFalse(ledger.canAnswer(1L))
    // Switching to Ambos later never revives a handle that was not kept.
    channel = InstinctAckChannel.AMBOS
    assertNull(ledger.latestHandle())
  }

  @Test fun `with Bitácora a follow-up hours later is still recorded`() {
    val ticket = ledger.open(null, 1, "abc123abc123")
    ledger.link(ticket, 0, 7L)
    assertEquals(AckKind.WAITING, ledger.report(7L, AckEntry(AckKind.WAITING, "prender la linterna", "bloqueado"))!!.events.single().kind)
    now += 3 * 60 * 60_000L
    val out = ledger.report(7L, AckEntry(AckKind.DISCARDED, "prender la linterna", "la persona la descartó"))!!
    assertEquals(AckKind.DISCARDED, out.events.single().kind)
    assertNull(out.text)
    now += 30 * 60_000L
    assertEquals(AckKind.UNDONE, ledger.note(7L, AckEntry(AckKind.UNDONE, "prender la linterna", "Deshice."))!!.events.single().kind)
    now += InstinctAckLedger.LOG_TTL_MILLIS
    ledger.open(null, 1) // prunes
    assertFalse(ledger.tracks(7L))
  }

  @Test fun `Solo errores y esperas decides what, the channel decides where`() {
    mode = InstinctReplyMode.PROBLEMS
    val ticket = ledger.open(null, 2)
    ledger.resolve(ticket, 0, ok("subir el volumen"))
    val out = ledger.resolve(ticket, 1, AckEntry(AckKind.ERROR, "llamar a Ana", "no"))!!
    assertEquals(listOf(AckKind.ERROR), out.events.map { it.kind })
    val quiet = ledger.open(null, 1)
    assertNull(ledger.resolve(quiet, 0, ok("bajar el volumen")))
    mode = InstinctReplyMode.NEVER
    assertNull(ledger.open(null, 1))
  }

  @Test fun `with Ambos the chat gets the text while the handle lives, the log always`() {
    channel = InstinctAckChannel.AMBOS
    val ticket = ledger.open("h", 1, "abc123abc123")
    ledger.link(ticket, 0, 3L)
    val first = ledger.report(3L, AckEntry(AckKind.WAITING, "a", "espera"))!!
    assertEquals("h", first.handle)
    assertEquals("#insto-espera a → espera", first.text)
    assertEquals(1, first.events.size)
    now += ProposalBook.TTL_MILLIS + 1
    val later = ledger.report(3L, ok("a"))!!
    assertNull(later.text)
    assertNull(later.handle)
    assertEquals(AckKind.OK, later.events.single().kind)
  }

  @Test fun `with WhatsApp nothing goes to the log, as before`() {
    channel = InstinctAckChannel.WHATSAPP
    val ticket = ledger.open("h", 1)
    val out = ledger.resolve(ticket, 0, ok("a"))!!
    assertEquals("#insto-ok a → Listo.", out.text)
    assertTrue(out.events.isEmpty())
    assertFalse(ledger.adopt(9L, "", AckEntry(AckKind.SCHEDULED, "a", "a las 22:00")))
  }

  @Test fun `a deferred order adopted after a restart records its outcome`() {
    assertTrue(ledger.adopt(-5L, "abc123abc123", AckEntry(AckKind.SCHEDULED, "a las 22:00: prender la linterna", "a las 22:00")))
    assertTrue(ledger.tracks(-5L))
    ledger.relink(-5L, 40L)
    val out = ledger.report(40L, ok("a las 22:00: prender la linterna"))!!
    assertEquals("abc123abc123", out.message)
    assertEquals(AckKind.OK, out.events.single().kind)
    assertNull(out.text)
  }

  @Test fun `a scheduled order keeps the message id across the store`() {
    val order = ScheduledOrder("id", "a las 22:00: prender la linterna", "prender la linterna", 1L, "f", null, "a las 22:00", 0L,
      message = "abc123abc123")
    assertEquals("abc123abc123", InstinctScheduleStore.decode(InstinctScheduleStore.encode(listOf(order))).single().message)
    val old = InstinctScheduleStore.encode(listOf(order.copy(message = "")))
    assertFalse(old.contains("\"message\""))
    assertEquals("", InstinctScheduleStore.decode(old).single().message)
  }

  // Catalog

  @Test fun `the log catalog says how to read it and the chat copy never carries the address nor the token`() {
    val copy = InstinctProtocolCatalog.text(emptyList(), log = InstinctCatalogLog(url, token))
    assertTrue(copy.contains("Protocolo Insto v8"))
    assertTrue(copy.contains("GET $url?token=$token&since=<ISO>&limit=50"))
    assertTrue(copy.contains("{ok, events:[{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}]}"))
    assertTrue(copy.contains("Antes de repetir o corregir una orden, consultá la bitácora."))
    assertTrue(copy.contains("salvo una sola orden corregida después de un error"))
    assertTrue(copy.contains("Nunca repitas una orden después de incierto ni de espera."))
    val chat = InstinctProtocolCatalog.text(emptyList(), forChat = true, log = InstinctCatalogLog.HIDDEN)
    assertFalse(chat.contains(token))
    assertFalse(chat.contains(url))
    assertTrue(chat.contains(InstinctCatalogLog.ASK))
    assertTrue(InstinctAck.safe(chat))
    assertTrue(InstinctProposalProtocol.orders(chat).isEmpty())
    val whatsapp = InstinctProtocolCatalog.text(emptyList())
    assertTrue(whatsapp.contains("Requiere bitácora. Las respuestas se anotan ahí aunque hayan llegado por chat"))
    assertFalse(whatsapp.contains("action=estado"))
    assertNotNull(whatsapp.lines().firstOrNull { it.startsWith("#insto-ok ") })
  }
}
