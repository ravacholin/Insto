package com.example.phone

import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** «Catálogo vivo» (1.18.0): the catalog as JSON, its canonical hash, its sources and what it never carries. Pure. */
class InstinctCatalogJsonTest {
  private val parser = PhoneCommandParser()
  private fun json(routines: List<String> = emptyList(), autonomy: InstinctAutonomyPolicy = InstinctAutonomyPolicy(),
    channels: InstinctCatalogChannels = InstinctCatalogChannels()) = InstinctProtocolCatalog.json(routines, autonomy, channels, "1.23.0")

  /** Every «orden» in the catalog, in order. */
  private fun orders(o: JSONObject): List<String> {
    fun list(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("orden") }
    val forms = o.getJSONObject("formas")
    return list(o.getJSONArray("acciones")) + listOf("diferidas", "clima", "disparadores", "recurrentes").flatMap { list(forms.getJSONArray(it)) } +
      listOf("preguntas", "decisiones").map { o.getJSONObject(it).getString("orden") } + list(o.getJSONObject("planes").getJSONArray("ejemplos")) +
      o.getJSONObject("reglas_contextuales").getString("orden") + list(o.getJSONArray("rutinas")) + list(o.getJSONArray("consultas"))
  }

  @Test fun `every example is a valid order, without the prefix, from the same source as the text`() {
    val o = JSONObject(json().canonical)
    val all = orders(o)
    val queries = ProposalCheck.QueryKind.entries.map { it.form }
    assertEquals(InstinctProposalValidator.examples + listOf(InstinctProtocolCatalog.QUESTION_EXAMPLE, InstinctProtocolCatalog.DECISION_EXAMPLE) +
      InstinctPlanSpec.EXAMPLES + ContextRuleSpec.EXAMPLE + queries, all)
    for (order in InstinctProposalValidator.examples) {
      assertFalse(order, order.startsWith("#insto"))
      // Recurring examples end on 13/12 and 15/12: checked on 21/11/2026, like the text catalog.
      val now = if (order.contains(" hasta el ")) Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 21, 10, 0, 0) } else Calendar.getInstance()
      assertTrue(order, InstinctProposalValidator.check(order, parser, now) is ProposalCheck.Valid)
    }
    val question = o.getJSONObject("preguntas")
    assertTrue(InstinctProposalValidator.check(question.getString("orden"), parser) is ProposalCheck.Question)
    assertEquals("bitacora", question.getString("respuesta_por"))
    // Every immediate action says its category, the same as the autonomy uses.
    val acciones = o.getJSONArray("acciones")
    (0 until acciones.length()).forEach { assertTrue(acciones.getJSONObject(it).toString(), acciones.getJSONObject(it).optString("categoria") in InstinctCategory.entries.map { c -> c.label }) }
    // The text catalog lists the very same orders.
    val text = InstinctProtocolCatalog.text(emptyList()).lines().filter { it.startsWith("#insto ") && it != "#insto <orden>" }.map { it.removePrefix("#insto ") }
    assertEquals(text, all)
  }

  @Test fun `catalog distinguishes mutations and scopes the lock screen blocker`() {
    val catalog = JSONObject(json().canonical)
    val queries = catalog.getJSONArray("consultas")
    val mutations = (0 until queries.length()).map { queries.getJSONObject(it) }
      .filter { !it.getBoolean("solo_lectura") }.map { it.getString("orden") }
    assertEquals(setOf("cancelar programada <id>", "cancelar programadas", "activar regla <id>",
      "desactivar regla <id>", "eliminar regla <id>", "cancelar plan <id>", "reanudar plan <id>"), mutations.toSet())
    assertFalse(InstinctProtocolCatalog.FIXED_BLOCKERS.contains("el teléfono está bloqueado"))
    assertTrue(InstinctProtocolCatalog.FIXED_BLOCKERS.any { "abre una pantalla" in it })
  }

  @Test fun `the hash is the SHA-256 of the canonical form, stable and without publicado`() {
    val a = json(); val b = json()
    assertEquals(a, b)
    assertEquals(sha256(a.canonical), a.hash)
    assertTrue(a.hash.matches(Regex("[0-9a-f]{64}")))
    assertFalse(a.canonical.contains("\"hash\"")); assertFalse(a.canonical.contains("publicado"))
    val published = JSONObject(a.published("2026-11-22T08:40:00-03:00"))
    assertEquals(a.hash, published.getString("hash"))
    assertEquals("2026-11-22T08:40:00-03:00", published.getString("publicado"))
    // Taking hash and publicado out gives back exactly the canonical form.
    published.remove("hash"); published.remove("publicado")
    assertEquals(a.canonical, InstinctCatalogCanonical.write(toMap(published)))
    // Keys sorted, no spaces.
    assertTrue(a.canonical.startsWith("{\"acciones\":["))
    assertFalse(a.canonical.contains("\": ")); assertFalse(a.canonical.contains("\n"))
  }

  private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
    when (val v = o.get(k)) { is JSONObject -> toMap(v); is JSONArray -> toList(v); JSONObject.NULL -> null; else -> v }
  }
  private fun toList(a: JSONArray): List<Any?> = (0 until a.length()).map { i ->
    when (val v = a.get(i)) { is JSONObject -> toMap(v); is JSONArray -> toList(v); JSONObject.NULL -> null; else -> v }
  }

  @Test fun `the hash changes with what goes into it, and only with that`() {
    val base = json().hash
    assertNotEquals(base, json(routines = listOf("Estudio")).hash)
    assertNotEquals(base, json(autonomy = InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR)).hash)
    assertNotEquals(base, json(autonomy = InstinctAutonomyPolicy(quiet = QuietHours(0, 7 * 60))).hash)
    assertNotEquals(base, json(channels = InstinctCatalogChannels(mailbox = true)).hash)
    assertNotEquals(base, json(channels = InstinctCatalogChannels(doorbell = true, doorbellDirect = true)).hash)
    assertNotEquals(base, InstinctProtocolCatalog.json(emptyList(), instoVersion = "1.18.1").hash)
    // The «ajustada automáticamente» mark is not a rule: it does not change the hash.
    assertEquals(base, json(autonomy = InstinctAutonomyPolicy(adjusted = mapOf(InstinctCategory.VOLUMEN to 1L))).hash)
  }

  @Test fun `policy, quiet hours, fixed blockers, routines by name and channels`() {
    val policy = InstinctAutonomyPolicy().with(InstinctCategory.RELOJ, InstinctAutonomy.CONFIRMAR).with(InstinctCategory.LINTERNA, InstinctAutonomy.NUNCA)
      .copy(quiet = QuietHours(23 * 60, 7 * 60), silenceInQuiet = false)
    val o = JSONObject(json(listOf("Estudio"), policy, InstinctCatalogChannels(whatsapp = false, log = true, mailbox = true, doorbell = true,
      doorbellDirect = true, replyMode = InstinctReplyMode.ALWAYS, ackChannel = InstinctAckChannel.BITACORA)).canonical)
    val autonomy = o.getJSONObject("autonomia")
    assertEquals("confirmar", autonomy.getString("alarmas y temporizadores"))
    assertEquals("nunca", autonomy.getString("linterna"))
    assertEquals("solo", autonomy.getString("volumen"))
    assertEquals(InstinctCategory.entries.size, autonomy.length())
    assertEquals("23:00", o.getJSONObject("horas_de_silencio").getString("desde"))
    assertFalse(o.getJSONObject("horas_de_silencio").getBoolean("permite_silenciar"))
    assertTrue(JSONObject(json().canonical).isNull("horas_de_silencio"))
    assertEquals(InstinctProtocolCatalog.FIXED_BLOCKERS.size, o.getJSONArray("bloqueos_fijos").length())
    assertEquals("ejecutar la rutina Estudio", o.getJSONArray("rutinas").getJSONObject(0).getString("orden"))
    val c = o.getJSONObject("canales")
    assertFalse(c.getBoolean("whatsapp")); assertTrue(c.getBoolean("bitacora")); assertTrue(c.getBoolean("buzon"))
    assertTrue(c.getBoolean("timbre")); assertTrue(c.getBoolean("orden_en_timbre"))
    assertEquals("siempre", c.getString("acuses")); assertEquals("bitacora", c.getString("acuses_por"))
    assertEquals("Protocolo Insto v8", o.getString("protocolo")); assertEquals(8, o.getInt("version_protocolo"))
    assertEquals("1.23.0", o.getString("version_insto"))
    assertTrue("well under the script's 45 000 characters", json(listOf("Estudio"), policy).published("2026-11-22T08:40:00-03:00").length < 20_000)
  }

  @Test fun `never an address, a token, a key nor an endpoint`() {
    val everything = json(listOf("Estudio"), channels = InstinctCatalogChannels(log = true, mailbox = true, doorbell = true, doorbellDirect = true))
      .published("2026-11-22T08:40:00-03:00")
    listOf("https://", "script.google", "ntfy", "token", "clave", "endpoint", "\"sig\"").forEach {
      assertFalse(it, everything.lowercase().contains(it))
    }
  }

  @Test fun `ver catálogo says the published hash`() {
    val hash = "a".repeat(64)
    assertEquals("Protocolo Insto v8 · rutinas habilitadas: ninguna. Catálogo publicado: hash $hash.", InstinctProtocolCatalog.summary(emptyList(), hash))
    assertEquals("Protocolo Insto v8 · rutinas habilitadas: ninguna.", InstinctProtocolCatalog.summary(emptyList()))
  }

  @Test fun `the text catalog points at the live one only with the log, without address in the chat copy`() {
    val log = InstinctCatalogLog("https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec", "token-largo-de-prueba")
    val paste = InstinctProtocolCatalog.text(emptyList(), log = log)
    assertTrue(paste.contains("GET ${log.url}?token=${log.token}&action=catalogo"))
    val chat = InstinctProtocolCatalog.text(emptyList(), forChat = true, log = InstinctCatalogLog.HIDDEN)
    assertTrue(chat.contains(InstinctProtocolCatalog.LIVE_RULE))
    assertFalse(chat.contains("script.google"))
    assertTrue(InstinctAck.safe(chat))
    assertFalse("without the log there is nothing to read", InstinctProtocolCatalog.text(emptyList()).contains("action=catalogo"))
  }

  /** The reference copy in docs is what the code generates (default policy, no routines, all channels off). */
  @Test fun `the reference JSON catalog in docs matches the generated one`() {
    val file = listOf(File("docs/instinct-catalog.json"), File("../docs/instinct-catalog.json")).first { it.parentFile?.isDirectory == true }
    val expected = InstinctCatalogCanonical.pretty(json().published()) + "\n"
    if (System.getenv("INSTO_WRITE_CATALOG") == "1") file.writeText(expected)
    assertEquals("Regenerá docs/instinct-catalog.json con INSTO_WRITE_CATALOG=1", expected, file.readText())
  }
}
