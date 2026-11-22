package com.example.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Timbre» (1.17.0): the pure part of the UnifiedPush connector, the ring gate, the backup interval and the script. */
class InstinctDoorbellTest {
  private val endpoint = "https://ntfy.sh/upAbCdEf0123456789?up=1"
  private val registered = DoorbellState(token = "tok-1234", distributor = "io.heckel.ntfy", confirmed = true)

  // 1.19.0: the official library handles the broadcasts; what is left here is the labels and the script

  @Test fun `the spec's failure reasons are told apart`() {
    assertEquals("registro fallido: el distribuidor no tenía red", InstinctDoorbellProtocol.failureLabel("NETWORK"))
    assertEquals("registro fallido: el servidor de ntfy pide VAPID", InstinctDoorbellProtocol.failureLabel("VAPID_REQUIRED"))
    assertEquals("registro fallido", InstinctDoorbellProtocol.failureLabel(null))
  }

  @Test fun `the endpoint goes with the sealed doorbell, and the keys only with the order inside`() {
    val sealed = InstinctDoorbellScript.Sealed("A".repeat(200), "B".repeat(87), "C".repeat(22))
    val withOrder = org.json.JSONObject(InstinctDoorbellScript.informBody("t", endpoint, withOrder = true, sealed = sealed))
    assertEquals("A".repeat(200), withOrder.getJSONObject("cifrado").getString("timbre"))
    assertEquals("B".repeat(87), withOrder.getJSONObject("cifrado").getString("p256dh"))
    val plain = org.json.JSONObject(InstinctDoorbellScript.informBody("t", endpoint, sealed = InstinctDoorbellScript.Sealed("A".repeat(200))))
    assertFalse(plain.getJSONObject("cifrado").has("p256dh"))
    val deleted = org.json.JSONObject(InstinctDoorbellScript.informBody("t", "", withOrder = true, sealed = sealed))
    assertTrue("no endpoint, no sealed doorbell", deleted.isNull("cifrado"))
    assertFalse(deleted.getBoolean("conOrden"))
  }

  @Test fun `the script's answer says whether it encrypts, has a relay and rings per order`() {
    fun body(text: String) = InstinctLogClient.Raw.Body(text)
    assertEquals(InstinctDoorbellScript.Answer.Confirmed(true, false, sealed = true, relay = false, perOrder = true),
      InstinctDoorbellScript.parseInform(body("""{"ok":true,"timbre":"guardado","conOrden":false,"cifrado":true,"rele":false,"cadaOrden":true}""")))
    assertEquals("a 1.18.0 script", InstinctDoorbellScript.Answer.Confirmed(true, true),
      InstinctDoorbellScript.parseInform(body("""{"ok":true,"timbre":"guardado","conOrden":true}""")))
    assertEquals(InstinctDoorbellScript.Answer.Failed("el script rechazó el timbre cifrado", retry = false),
      InstinctDoorbellScript.parseInform(body("""{"ok":false,"error":"cifrado"}""")))
  }

  @Test fun `the distributor is the only one installed or the chosen one`() {
    assertNull(InstinctDoorbellProtocol.pick(emptyList(), null))
    assertEquals("io.heckel.ntfy", InstinctDoorbellProtocol.pick(listOf("io.heckel.ntfy"), null))
    assertNull("several and none chosen", InstinctDoorbellProtocol.pick(listOf("a", "io.heckel.ntfy"), null))
    assertEquals("a", InstinctDoorbellProtocol.pick(listOf("a", "io.heckel.ntfy"), "a"))
    assertEquals("the chosen one was uninstalled", "b", InstinctDoorbellProtocol.pick(listOf("b"), "a"))
  }

  // The endpoint

  @Test fun `the endpoint is masked to its last 6 characters and must be https within 500`() {
    assertEquals("••••?up=1".let { "••••" + endpoint.takeLast(6) }, InstinctDoorbellEndpoint.mask(endpoint))
    assertFalse(InstinctDoorbellEndpoint.mask(endpoint).contains("upAbCdEf"))
    assertEquals("sin endpoint", InstinctDoorbellEndpoint.mask(null))
    assertTrue(InstinctDoorbellEndpoint.valid(endpoint))
    assertFalse(InstinctDoorbellEndpoint.valid("http://ntfy.sh/up"))
    assertFalse(InstinctDoorbellEndpoint.valid("https://ntfy.sh/" + "a".repeat(500)))
    assertFalse(InstinctDoorbellEndpoint.valid("https://ntfy.sh/a b"))
  }

  @Test fun `the state survives a round trip and a broken file is a fresh one`() {
    val state = DoorbellState("tok", "io.heckel.ntfy", true, 1_790_000_000_000L, false, null, 1_790_000_100_000L, 3, 2)
    assertEquals(state, DoorbellState.decode(state.encode()))
    val direct = state.copy(withOrderConfirmed = true, directOrders = 5, badPayloads = 2)
    assertEquals(direct, DoorbellState.decode(direct.encode()))
    assertEquals("a 1.17.0 file has no counters of 1.18.0", state, DoorbellState.decode(org.json.JSONObject(state.encode())
      .apply { remove("withOrderConfirmed"); remove("directOrders"); remove("badPayloads") }.toString()))
    assertEquals(DoorbellState(), DoorbellState.decode("{roto"))
    assertFalse("the state file never holds the endpoint", state.encode().contains("ntfy.sh"))
  }

  // The gate: 5 seconds, and a ring inside the window is postponed, never dropped

  @Test fun `a ring polls now, after the running poll or when the 5-second window ends`() {
    var now = 100_000L
    val gate = InstinctMailboxGate { now }
    assertEquals(InstinctMailboxGate.Ring.PollNow, gate.ring(online = true))
    gate.begin()
    assertEquals(InstinctMailboxGate.Ring.AfterCurrent, gate.ring(online = true))
    assertTrue("the running poll polls once more", gate.end())
    assertFalse(gate.end())
    now += 1_000
    assertEquals(InstinctMailboxGate.Ring.Later(4_000L), gate.ring(online = true))
    now += 4_000
    assertEquals(InstinctMailboxGate.Ring.PollNow, gate.ring(online = true))
    assertEquals("a second ring before the reserved poll begins waits", InstinctMailboxGate.Ring.Later(5_000L), gate.ring(online = true))
    assertEquals(InstinctMailboxGate.Ring.WhenOnline, gate.ring(online = false))
  }

  @Test fun `a poll of another kind also counts for the window`() {
    var now = 0L
    val gate = InstinctMailboxGate { now }
    now = 50_000L
    gate.begin(); assertFalse(gate.end())
    now += 4_999
    assertEquals(InstinctMailboxGate.Ring.Later(1L), gate.ring(online = true))
    assertEquals(5_000L, InstinctMailboxGate.MIN_SPACING_MILLIS)
  }

  // The backup interval

  @Test fun `the backup poll is 15 minutes while the doorbell rang in the last 24 hours`() {
    val now = 1_790_000_000_000L
    assertEquals(15, InstinctDoorbellPolicy.backupMinutes(2, active = true, lastRingAt = now - 60_000, now = now))
    assertEquals(15, InstinctDoorbellPolicy.backupMinutes(5, active = true, lastRingAt = now - 23 * 3_600_000L, now = now))
    assertEquals(15, InstinctDoorbellPolicy.backupMinutes(15, active = true, lastRingAt = now, now = now))
    assertEquals("24 hours without a ring", 2, InstinctDoorbellPolicy.backupMinutes(2, active = true, lastRingAt = now - 24 * 3_600_000L, now = now))
    assertEquals("never rang", 5, InstinctDoorbellPolicy.backupMinutes(5, active = true, lastRingAt = null, now = now))
    assertEquals("off", 2, InstinctDoorbellPolicy.backupMinutes(2, active = false, lastRingAt = now, now = now))
    assertEquals("a ring from the future (clock change)", 2, InstinctDoorbellPolicy.backupMinutes(2, active = true, lastRingAt = now + 60_000, now = now))
  }

  // The script

  @Test fun `the script's answers, an old script included`() {
    fun body(text: String) = InstinctLogClient.Raw.Body(text)
    assertEquals(InstinctDoorbellScript.Answer.Confirmed(true), InstinctDoorbellScript.parseInform(body("""{"ok":true,"timbre":"guardado"}""")))
    assertEquals(InstinctDoorbellScript.Answer.Confirmed(false), InstinctDoorbellScript.parseInform(body("""{"ok":true,"timbre":"borrado"}""")))
    assertEquals("a 1.16.0 script answers the events branch", InstinctDoorbellScript.Answer.Failed(InstinctDoorbellScript.NOT_CONFIRMED, false),
      InstinctDoorbellScript.parseInform(body("""{"ok":true,"accepted":[]}""")))
    assertEquals(InstinctDoorbellScript.Answer.Failed("token rechazado", false), InstinctDoorbellScript.parseInform(body("""{"ok":false,"error":"token"}""")))
    assertEquals(InstinctDoorbellScript.Answer.Failed("sin red", true),
      InstinctDoorbellScript.parseInform(InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK)))
    assertNull(InstinctDoorbellScript.testFailure(body("""{"ok":true,"timbre":true,"codigo":200}""")))
    assertEquals("el script no pudo tocar el timbre (HTTP 404)", InstinctDoorbellScript.testFailure(body("""{"ok":true,"timbre":false,"codigo":404}""")))
    val denied = InstinctDoorbellScript.testFailure(body("""{"ok":true,"timbre":false,"codigo":-1,"detalle":"No tenés permiso para llamar a UrlFetchApp.fetch."}"""))!!
    assertTrue(denied, denied.startsWith("el script no pudo conectarse a ntfy (Google dice: No tenés permiso"))
    assertTrue(denied.contains("autorizarTimbre"))
    assertEquals("el script no conoce «probar-timbre»: actualizalo", InstinctDoorbellScript.testFailure(body("""{"ok":true,"accepted":[]}""")))
    assertEquals("el script no tiene el endpoint guardado", InstinctDoorbellScript.testFailure(body("""{"ok":false,"error":"sin-endpoint"}""")))
    assertTrue(InstinctDoorbellScript.informBody("t0ken-largo", endpoint).contains("\"action\":\"timbre\""))
    // 1.18.0: conOrden always goes; a deletion never asks for the order.
    assertEquals(true, org.json.JSONObject(InstinctDoorbellScript.informBody("t0ken-largo", endpoint, withOrder = true)).get("conOrden"))
    assertEquals(false, org.json.JSONObject(InstinctDoorbellScript.informBody("t0ken-largo", endpoint)).get("conOrden"))
    assertEquals(false, org.json.JSONObject(InstinctDoorbellScript.informBody("t0ken-largo", "", withOrder = true)).get("conOrden"))
    assertEquals(InstinctDoorbellScript.Answer.Confirmed(true, withOrder = true),
      InstinctDoorbellScript.parseInform(body("""{"ok":true,"timbre":"guardado","conOrden":true}""")))
    assertEquals("a 1.17.0 script does not repeat conOrden", InstinctDoorbellScript.Answer.Confirmed(true, withOrder = null),
      InstinctDoorbellScript.parseInform(body("""{"ok":true,"timbre":"guardado"}""")))
    org.json.JSONObject(InstinctDoorbellScript.testBody("t0ken-largo")).let {
      assertEquals("t0ken-largo", it.getString("token")); assertEquals("probar-timbre", it.getString("action")); assertEquals(2, it.length())
    }
  }

  // The order inside the doorbell (1.18.0): a strict parser

  private val payloadId = "3b241101-e2bb-4255-8caf-4136c566a962"
  private val payloadAt = "2026-11-21T09:30:00-03:00"
  private val payloadSig = "0118c3671f915c0c55cc4364838ce32afcc3ce33e984617ea69b60f2377859df"
  private fun payload(edit: org.json.JSONObject.() -> Unit = {}): String = org.json.JSONObject().put("v", 1).put("id", payloadId)
    .put("at", payloadAt).put("lines", org.json.JSONArray(listOf("#insto poner el volumen al 30 por ciento"))).put("sig", payloadSig)
    .apply(edit).toString()
  private fun parse(text: String) = InstinctDoorbellPayload.parse(text.toByteArray(Charsets.UTF_8))

  @Test fun `a payload with exactly the signed order parses, as the script writes it`() {
    val order = parse("""{"v":1,"id":"$payloadId","at":"$payloadAt","lines":["#insto poner el volumen al 30 por ciento","#insto avisarme Hola."],"sig":"$payloadSig"}""")
    assertEquals(MailboxOrder(payloadId, payloadAt, listOf("#insto poner el volumen al 30 por ciento", "#insto avisarme Hola."), payloadSig), order)
    assertEquals("three lines of 300 characters", 3, parse(payload { put("lines", org.json.JSONArray(List(3) { "#insto " + "x".repeat(293) })) })!!.lines.size)
    assertNotNull("upper-case UUID and milliseconds with Z", parse(payload { put("id", payloadId.uppercase()); put("at", "2026-11-21T12:30:00.123Z") }))
    assertNotNull("whitespace between tokens is JSON", parse(" " + payload() + "\n"))
    assertNotNull("UTF-8 in a line", parse(payload { put("lines", org.json.JSONArray(listOf("#insto avisarme Bajé el volumen, ¿todo bien?"))) }))
  }

  @Test fun `anything else is no order, extra or missing keys, other versions, long lines, garbage`() {
    val refused = listOf(
      payload { put("extra", 1) } to "an extra key",
      payload { put("token", "secreto") } to "a token",
      payload { remove("sig") } to "no signature",
      payload { put("v", 2) } to "v 2",
      payload { put("v", "1") } to "v as text",
      payload { put("v", 1.5) } to "v 1.5",
      payload { put("id", "no-es-un-uuid") } to "an id that is not a UUID",
      payload { put("at", "2026-11-21T09:30:00") } to "at without a zone",
      payload { put("at", " $payloadAt") } to "at with spaces",
      payload { put("sig", payloadSig.uppercase()) } to "upper-case signature",
      payload { put("sig", payloadSig.drop(1)) } to "a short signature",
      payload { put("lines", org.json.JSONArray()) } to "no lines",
      payload { put("lines", org.json.JSONArray(List(4) { "#insto subir el volumen" })) } to "four lines",
      payload { put("lines", org.json.JSONArray(listOf("#insto " + "x".repeat(294)))) } to "a line of 301 characters",
      payload { put("lines", org.json.JSONArray(listOf("#insto a\n#insto b"))) } to "a line break inside a line",
      payload { put("lines", org.json.JSONArray(listOf(1))) } to "a line that is not text",
      payload { put("lines", "#insto subir el volumen") } to "lines that are not a list",
      payload() + "x" to "trailing garbage",
      "[" + payload() + "]" to "an array",
      "insto-timbre" to "the plain doorbell",
      "" to "empty",
      "{roto" to "broken JSON",
      "#insto poner el volumen al 100 por ciento" to "a bare order",
    )
    refused.forEach { (text, why) -> assertNull(why, parse(text)) }
    assertNull(InstinctDoorbellPayload.parse(null))
    assertNull("not UTF-8", InstinctDoorbellPayload.parse(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)))
    assertNull("over 4096 bytes", InstinctDoorbellPayload.parse((payload() + " ".repeat(4_097)).toByteArray()))
  }

  @Test fun `a decrypted message hands over the parsed order, and counts only real garbage as malformed`() {
    assertEquals(InstinctPushMessage.Kind.Order(parse(payload())!!),
      InstinctPushMessage.classify(payload().toByteArray(), decrypted = true, encryptionRequired = true).kind)
    assertFalse("the plain doorbell is not malformed",
      InstinctPushMessage.classify("insto-timbre".toByteArray(), decrypted = true, encryptionRequired = true).malformed)
    assertTrue(InstinctPushMessage.classify(payload { put("v", 2) }.toByteArray(), decrypted = true, encryptionRequired = true).malformed)
  }

  @Test fun `the catalog says nothing changes for Instinct when the order travels in the doorbell`() {
    val log = InstinctCatalogLog("https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec", "token-largo-de-prueba")
    val direct = InstinctProtocolCatalog.text(emptyList(), log = log, mailbox = log, doorbell = true, doorbellDirect = true)
    assertTrue(direct.contains(InstinctDoorbellText.CATALOG_DIRECT_LINE))
    assertFalse(InstinctProtocolCatalog.text(emptyList(), log = log, mailbox = log, doorbell = true).contains(InstinctDoorbellText.CATALOG_DIRECT_LINE))
    assertFalse("without the doorbell active, no line", InstinctProtocolCatalog.text(emptyList(), log = log, mailbox = log, doorbellDirect = true)
      .contains(InstinctDoorbellText.CATALOG_DIRECT_LINE))
    assertFalse(direct.contains("ntfy"))
  }

  // Never the endpoint where it can travel

  @Test fun `the diagnosis and the status never carry the endpoint`() {
    val clock: (Long) -> String = { "09:30" }
    val rang = registered.copy(lastRingAt = 1L, rings = 4, ringPolls = 3, informedAt = 1L)
    assertEquals("Timbre: último 09:30 · timbres 4 · sondeos por timbre 3 · órdenes directas 0 · payload inválido 0", InstinctDoorbellText.diagnosis(rang, 1, clock))
    assertEquals("Timbre: último 09:30 · timbres 4 · sondeos por timbre 3 · órdenes directas 2 · payload inválido 1",
      InstinctDoorbellText.diagnosis(rang.copy(directOrders = 2, badPayloads = 1), 1, clock))
    assertEquals("Timbre: sin distribuidor (instalá ntfy) · timbres 0 · sondeos por timbre 0 · órdenes directas 0 · payload inválido 0", InstinctDoorbellText.diagnosis(DoorbellState(), 0, clock))
    assertEquals("Timbre: no funciona (motivo: el script no confirmó el endpoint) · timbres 0 · sondeos por timbre 0 · órdenes directas 0 · payload inválido 0",
      InstinctDoorbellText.diagnosis(registered.copy(failure = InstinctDoorbellScript.NOT_CONFIRMED), 1, clock))
    val status = InstinctDoorbellText.status(endpoint, rang, clock)
    assertEquals("Endpoint ••••" + endpoint.takeLast(6) + " · avisado al script 09:30", status)
    listOf(InstinctDoorbellText.diagnosis(rang, 1, clock), status).forEach { assertFalse(it, it.contains("ntfy.sh")) }
  }

  @Test fun `the catalog says it once, without the endpoint, in both copies`() {
    val log = InstinctCatalogLog("https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec", "token-largo-de-prueba")
    val on = InstinctProtocolCatalog.text(emptyList(), log = log, mailbox = log, doorbell = true)
    val chat = InstinctProtocolCatalog.text(emptyList(), forChat = true, log = InstinctCatalogLog.HIDDEN, mailbox = InstinctCatalogLog.HIDDEN, doorbell = true)
    val off = InstinctProtocolCatalog.text(emptyList(), log = log, mailbox = log)
    assertTrue(on.contains(InstinctDoorbellText.CATALOG_LINE))
    assertTrue(chat.contains(InstinctDoorbellText.CATALOG_LINE))
    assertFalse(off.contains(InstinctDoorbellText.CATALOG_LINE))
    assertFalse("no mailbox, no line", InstinctProtocolCatalog.text(emptyList(), doorbell = true).contains(InstinctDoorbellText.CATALOG_LINE))
    listOf(on, chat).forEach { assertFalse(it.contains("ntfy")); assertFalse(it.contains(endpoint)) }
    assertTrue("the chat copy still never reads as orders", InstinctAck.safe(chat))
  }

  @Test fun `the 1_19_0 state survives a restart and a 1_18_0 state reads with safe defaults`() {
    val state = DoorbellState(distributor = "io.heckel.ntfy", confirmed = true, encryptionConfirmed = true, relayConfirmed = true,
      perOrderRing = true, duplicates = 3, uncertain = 1, decryptFailures = 2, lastError = "x", pendingTaken = listOf("a", "b"),
      keysFingerprint = "0123456789ab", lastDirectAt = 5L)
    assertEquals(state, DoorbellState.decode(state.encode()))
    val old = DoorbellState.decode("""{"token":"tok-1234","distributor":"io.heckel.ntfy","confirmed":true,"rings":4,"withOrderConfirmed":true}""")
    assertEquals("tok-1234", old.token)
    assertFalse("encryption is never assumed", old.encryptionConfirmed)
    assertFalse(old.perOrderRing)
    assertTrue(old.pendingTaken.isEmpty())
    assertFalse(state.encode().contains("p256dh"))
  }

}
