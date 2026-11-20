package com.example.backup

import com.example.phone.ConfiguredRoutine
import com.example.phone.ConfiguredRoutineStore
import com.example.phone.PhoneCommand
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The manager with real section logic over in-memory stores: one «phone» per instance. */
class ConfigurationBackupManagerTest {
  private val directories = mutableListOf<File>()
  private val groqKey = "gsk_test_000000000000000000000000000000"
  private val geminiKey = "AIzaTest000000000000000000000000000000"
  private val password = "una contraseña larga".toCharArray()

  @After fun clean() { directories.forEach { it.deleteRecursively() } }

  class FakeCredentials : CredentialAccess {
    var groq: String? = null; var gemini: String? = null; var enabled = false; var local = false
    var failOnSave = false
    override fun groqKey() = groq
    override fun saveGroqKey(value: String) { if (failOnSave) error("disk full"); groq = value }
    override fun clearGroqKey() { groq = null }
    override fun geminiKey() = gemini
    override fun saveGeminiKey(value: String) { if (failOnSave) error("disk full"); gemini = value }
    override fun clearGeminiKey() { gemini = null; enabled = false }
    override fun geminiEnabled() = enabled
    override fun setGeminiEnabled(value: Boolean) { enabled = value }
    override fun localChoice() = local
    override fun setLocalChoice(value: Boolean) { local = value }
  }

  class FakeAssistant : AssistantAccess {
    val stored = linkedMapOf<String, Any>()
    override suspend fun values() = stored.toMap()
    override suspend fun write(set: Map<String, Any>, remove: Set<String>) { remove.forEach(stored::remove); stored.putAll(set) }
  }

  class FakePlaces : PlacesAccess {
    val stored = linkedMapOf<String, String>()
    override fun address(alias: String) = stored[alias]
    override fun save(alias: String, address: String) { stored[alias] = address }
    override fun remove(alias: String) { stored.remove(alias) }
  }

  /** A phrases category stand-in: exercises the manager with an opaque, validated JSON list. */
  class FakePhrases : BackupSection {
    override val category = BackupCategory.PHRASES
    val phrases = linkedMapOf<String, Boolean>() // phrase → manual
    override suspend fun export() = if (phrases.isEmpty()) null
      else JSONObject().put("phrases", JSONArray(phrases.map { (p, m) -> JSONObject().put("phrase", p).put("manual", m) }))
    override suspend fun describeCurrent() = phrases.takeIf { it.isNotEmpty() }?.let { "${it.size} frases" }
    private fun parse(json: JSONObject): Map<String, Boolean> {
      val array = json.optJSONArray("phrases") ?: JSONArray()
      val parsed = (0 until array.length()).map { array.getJSONObject(it) }.map { it.getString("phrase") to it.getBoolean("manual") }
      require(parsed.map { it.first }.distinct().size == parsed.size) { "El archivo repite frases." }
      return parsed.toMap()
    }
    override fun validate(json: JSONObject) = CategorySummary("${parse(json).size} frases")
    override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
      val incoming = parse(json)
      val final = if (mode == RestoreMode.REPLACE) incoming else
        LinkedHashMap(phrases).apply { incoming.forEach { (p, m) -> if (p !in this || (!this.getValue(p) && m)) put(p, m) } }
      return object : PreparedChange {
        override val changes = listOf("${final.size} frases")
        override val warnings = emptyList<String>()
        override val attention = emptyList<String>()
        override suspend fun snapshot(): Restorable { val before = LinkedHashMap(phrases); return Restorable { phrases.clear(); phrases.putAll(before) } }
        override suspend fun commit() { phrases.clear(); phrases.putAll(final) }
      }
    }
  }

  inner class Phone {
    val dir: File = Files.createTempDirectory("insto-phone").toFile().also { directories += it }
    val credentials = FakeCredentials()
    val assistant = FakeAssistant()
    val places = FakePlaces()
    val phrases = FakePhrases()
    val routines = ConfiguredRoutineStore(File(dir, "configured-routines.json"))
    val marker = FileRestoreMarker(File(dir, "restore-in-progress.json"))
    val manager = ConfigurationBackupManager(
      listOf(CredentialsSection(credentials), AssistantSection(assistant), phrases, RoutinesSection(routines), PlacesSection(places)),
      "1.12.0", 21, marker, clock = { 1_763_650_000_000 }, random = SecureRandom(), iterations = BackupCrypto.MIN_ITERATIONS)
  }

  private fun configured() = Phone().apply {
    credentials.groq = groqKey; credentials.gemini = geminiKey; credentials.enabled = true
    assistant.stored.putAll(mapOf("contact_name" to "Contacto Prueba", "contact_phone" to "+447700900123",
      "whatsapp_flavor" to "WHATSAPP_BUSINESS", "night_start_hour" to 22, "eyes_free_cues" to false))
    places.stored["casa"] = "Calle Falsa 123"
    phrases.phrases.putAll(mapOf("modo cine" to true, "luz del pasillo" to false))
    routines.save(listOf(ConfiguredRoutine("Estudio", listOf(PhoneCommand.Torch(true)))))
  }

  private fun all(mode: RestoreMode = RestoreMode.MERGE) = BackupCategory.entries.associateWith { mode }

  private fun open(phone: Phone, bytes: ByteArray, pw: CharArray? = password) =
    (phone.manager.open(bytes, pw) as OpenResult.Ready).plan

  @Test fun phoneAToCleanPhoneBRestoresEverythingIncludingKeys() = runBlocking {
    val a = configured()
    val file = a.manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone()
    assertEquals(OpenResult.NeedsPassword, b.manager.open(file, null))
    val plan = open(b, file)
    assertTrue(plan.includesCredentials)
    val report = b.manager.restore(plan, all(), RestoreOptions())
    assertTrue(report.failure, report.success)
    assertEquals(groqKey, b.credentials.groq)
    assertEquals(geminiKey, b.credentials.gemini)
    assertTrue(b.credentials.enabled)
    assertEquals(a.assistant.stored, b.assistant.stored)
    assertEquals(a.places.stored, b.places.stored)
    assertEquals(a.phrases.phrases, b.phrases.phrases)
    assertEquals(a.routines.read(), b.routines.read())
    assertNull(b.marker.pending())
  }

  @Test fun exportWithoutKeysNeverRestoresOrDeletesCredentials() = runBlocking {
    val a = configured()
    val file = a.manager.export(BackupCategory.entries.toSet() - BackupCategory.CREDENTIALS, password)
    val b = Phone().apply { credentials.groq = "gsk_existing" }
    val plan = open(b, file)
    assertFalse(plan.includesCredentials)
    assertFalse(BackupCategory.CREDENTIALS in plan.categories)
    assertTrue(b.manager.restore(plan, all(RestoreMode.REPLACE), RestoreOptions()).success)
    assertEquals("gsk_existing", b.credentials.groq)
    assertNull(b.credentials.gemini)
  }

  @Test fun mergeKeepsExistingValuesAndDifferentKeysNeedConsent() = runBlocking {
    val file = configured().manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone().apply {
      credentials.groq = "gsk_other"
      assistant.stored.putAll(mapOf("contact_name" to "Otro", "contact_phone" to "+447700900999", "night_start_hour" to 20))
      places.stored["casa"] = "Otra casa 1"
      phrases.phrases["modo cine"] = false
    }
    val plan = open(b, file)
    val preview = b.manager.preview(plan, all(), RestoreOptions())
    assertTrue(preview.getValue(BackupCategory.CREDENTIALS).attention.single().contains("Groq"))
    val report = b.manager.restore(plan, all(), RestoreOptions())
    assertTrue(report.success)
    assertEquals("Existing different key kept without consent", "gsk_other", b.credentials.groq)
    assertEquals(geminiKey, b.credentials.gemini)
    assertEquals("+447700900999", b.assistant.stored["contact_phone"])
    assertEquals(20, b.assistant.stored["night_start_hour"])
    assertEquals("WHATSAPP_BUSINESS", b.assistant.stored["whatsapp_flavor"])
    assertEquals("Otra casa 1", b.places.stored["casa"])
    assertEquals("A manual phrase from the backup replaces an automatic one", true, b.phrases.phrases["modo cine"])
    assertTrue(report.attention.any { it.contains("Groq") })
    // With explicit consent the key is replaced.
    assertTrue(b.manager.restore(plan, mapOf(BackupCategory.CREDENTIALS to RestoreMode.MERGE), RestoreOptions(replaceGroqKey = true)).success)
    assertEquals(groqKey, b.credentials.groq)
  }

  @Test fun replaceOnlyTouchesTheChosenCategories() = runBlocking {
    val file = configured().manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone().apply {
      assistant.stored.putAll(mapOf("contact_phone" to "+447700900999", "boomerang_return" to true))
      places.stored["trabajo"] = "Oficina 5"
      phrases.phrases["mía"] = true
    }
    val plan = open(b, file)
    val choices = mapOf(BackupCategory.ASSISTANT to RestoreMode.REPLACE)
    val preview = b.manager.preview(plan, choices, RestoreOptions())
    assertTrue(preview.getValue(BackupCategory.ASSISTANT).warnings.isNotEmpty())
    assertTrue(b.manager.restore(plan, choices, RestoreOptions()).success)
    assertEquals("+447700900123", b.assistant.stored["contact_phone"])
    assertFalse("Unset at export: back to default on replace", "boomerang_return" in b.assistant.stored)
    assertEquals(mapOf("trabajo" to "Oficina 5"), b.places.stored)
    assertEquals(mapOf("mía" to true), b.phrases.phrases)
    assertNull(b.credentials.groq)
  }

  @Test fun aFailureWhileWritingRollsBackWhatWasWritten() = runBlocking {
    val file = configured().manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone().apply {
      credentials.failOnSave = true
      places.stored["casa"] = "Original 1"
      phrases.phrases["mía"] = true
      assistant.stored["night_start_hour"] = 19
    }
    val plan = open(b, file)
    val report = b.manager.restore(plan, all(RestoreMode.REPLACE), RestoreOptions())
    assertFalse(report.success)
    assertTrue(report.failure!!.contains("Claves"))
    assertEquals(listOf(BackupCategory.CREDENTIALS, BackupCategory.ASSISTANT, BackupCategory.PHRASES, BackupCategory.ROUTINES,
      BackupCategory.PLACES), report.rolledBack)
    assertEquals(mapOf("casa" to "Original 1"), b.places.stored)
    assertEquals(mapOf("mía" to true), b.phrases.phrases)
    assertEquals(mapOf<String, Any>("night_start_hour" to 19), b.assistant.stored)
    assertTrue(b.routines.read().isEmpty())
    assertNull(b.credentials.groq)
    assertNull("Complete rollback clears the marker", b.marker.pending())
    assertFalse(report.failure!!.contains(groqKey))
  }

  @Test fun invalidContentChangesNothingAndIsReportedPerCategory() = runBlocking {
    val b = Phone().apply { places.stored["casa"] = "Original 1" }
    fun file(categories: JSONObject, schema: Int = 1) = BackupCrypto.encrypt(JSONObject().put("format", "insto-config")
      .put("schema", schema).put("categories", categories).toString().toByteArray(), password, SecureRandom(), BackupCrypto.MIN_ITERATIONS)
    // Duplicated phrases and a malformed place: those categories are unavailable, the rest stays restorable.
    val mixed = b.manager.open(file(JSONObject()
      .put("phrases", JSONObject().put("v", 1).put("phrases", JSONArray().put(JSONObject().put("phrase", "x").put("manual", true))
        .put(JSONObject().put("phrase", "x").put("manual", false))))
      .put("places", JSONObject().put("v", 1).put("casa", 42))
      .put("routines", JSONObject().put("v", 1).put("routines", JSONArray()))
      .put("future", JSONObject().put("v", 1))), password) as OpenResult.Ready
    assertEquals(setOf(BackupCategory.ROUTINES), mixed.plan.categories)
    assertEquals(setOf("Frases aprendidas", "Lugares guardados", "future"), mixed.plan.unavailable.keys)
    // A category from a newer Insto, an unknown schema and a future schema.
    assertTrue(b.manager.open(file(JSONObject().put("places", JSONObject().put("v", 9).put("casa", "x"))), password) is OpenResult.Invalid)
    assertTrue((b.manager.open(file(JSONObject(), schema = 2), password) as OpenResult.Invalid).message.contains("más nueva"))
    assertTrue(b.manager.open("hola".toByteArray(), password) is OpenResult.Invalid)
    assertTrue(b.manager.open(ByteArray(BackupCrypto.MAX_FILE + 10) { 1 }, password) is OpenResult.Invalid)
    val wrong = b.manager.open(configured().manager.export(setOf(BackupCategory.PLACES), password), "otra clave larga".toCharArray())
    assertTrue((wrong as OpenResult.Invalid).message.contains("Contraseña incorrecta"))
    assertEquals(mapOf("casa" to "Original 1"), b.places.stored)
  }

  @Test fun mergingTooManyRoutinesFailsBeforeWritingAnything() = runBlocking {
    val a = Phone().apply { routines.save((1..30).map { ConfiguredRoutine("A$it", listOf(PhoneCommand.Torch(true))) }) }
    val file = a.manager.export(setOf(BackupCategory.ROUTINES, BackupCategory.PLACES), password)
    val b = Phone().apply { routines.save((1..30).map { ConfiguredRoutine("B$it", listOf(PhoneCommand.Torch(false))) }) }
    val report = b.manager.restore(open(b, file), mapOf(BackupCategory.ROUTINES to RestoreMode.MERGE), RestoreOptions())
    assertFalse(report.success)
    assertTrue(report.failure!!.contains("No se cambió nada"))
    assertEquals(30, b.routines.read().size)
    assertTrue(b.manager.restore(open(b, file), mapOf(BackupCategory.ROUTINES to RestoreMode.REPLACE), RestoreOptions()).success)
    assertEquals(a.routines.read(), b.routines.read())
  }

  @Test fun noSecretInPlansReportsOrFilesAndNoTemporaryFiles() = runBlocking {
    val a = configured()
    val before = a.dir.listFiles()!!.map { it.name }.toSet()
    val file = a.manager.export(BackupCategory.entries.toSet(), password)
    assertFalse(String(file, Charsets.ISO_8859_1).contains(groqKey))
    val b = Phone()
    val plan = open(b, file)
    val preview = b.manager.preview(plan, all(), RestoreOptions())
    val report = b.manager.restore(plan, all(), RestoreOptions())
    for (text in listOf(plan.toString(), plan.summaries.toString(), preview.toString(), report.toString())) {
      assertFalse(text, text.contains(groqKey) || text.contains(geminiKey) || text.contains(String(password)))
    }
    assertEquals("Export writes nothing to the phone's storage", before, a.dir.listFiles()!!.map { it.name }.toSet())
    for (f in b.dir.walkTopDown().filter { it.isFile }) assertFalse(f.name, f.readText().contains(groqKey))
  }

  @Test fun interruptedRestoreIsReportedByTheMarker() {
    val b = Phone()
    b.marker.begin(listOf(BackupCategory.PHRASES, BackupCategory.ASSISTANT))
    assertEquals(listOf("Frases aprendidas", "Contactos y ajustes del asistente"), b.manager.interruptedRestore())
    assertFalse("The marker holds no data", File(b.dir, "restore-in-progress.json").readText().contains("Contacto"))
    b.manager.dismissInterruptedRestore()
    assertNull(b.manager.interruptedRestore())
  }

  @Test fun schemaMigrationsRunInOrder() {
    val old = JSONObject().put("format", "insto-config").put("schema", 0)
      .put("categories", JSONObject().put("places", JSONObject().put("v", 1).put("home", "Calle 1")))
    val migrated = BackupFormat.decode(old.toString().toByteArray(), mapOf(0 to { json ->
      val places = json.getJSONObject("categories").getJSONObject("places")
      places.put("casa", places.remove("home"))
      json.put("schema", 1)
    }))
    assertEquals("Calle 1", migrated.categories.getValue(BackupCategory.PLACES).getString("casa"))
    try { BackupFormat.decode(old.toString().toByteArray()); fail() } catch (e: BackupException) {
      assertEquals(BackupException.Kind.UNSUPPORTED_VERSION, e.kind)
    }
  }

  @Test fun aLearnedPhrasesFileFromInsto111OpensAsAPartialBackupWithoutPassword() {
    val legacy = JSONObject().put("format", "insto-learned-phrases").put("version", 1)
      .put("phrases", JSONArray().put(JSONObject().put("phrase", "luz").put("manual", true)))
    val plan = (Phone().manager.open(legacy.toString().toByteArray(), null) as OpenResult.Ready).plan
    assertTrue(plan.legacy)
    assertEquals(setOf(BackupCategory.PHRASES), plan.categories)
    assertFalse(plan.includesCredentials)
  }

  @Test fun noCategoryChosenAndCancelledPickerChangeNothing() = runBlocking {
    val b = Phone().apply { places.stored["casa"] = "Original 1" }
    val plan = open(b, configured().manager.export(setOf(BackupCategory.PLACES), password))
    assertFalse(b.manager.restore(plan, emptyMap(), RestoreOptions()).success)
    assertEquals(mapOf("casa" to "Original 1"), b.places.stored)
  }
}
