package com.example.backup

import com.example.phone.InstinctAckChannel
import com.example.phone.InstinctAutonomy
import com.example.phone.InstinctAutonomyPolicy
import com.example.phone.InstinctCategory
import com.example.phone.InstinctMailboxAccept
import com.example.phone.InstinctReplyMode
import com.example.phone.QuietHours
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** 1.20.0: Instinct in the `.insto` copy, over an in-memory phone. */
class InstinctBackupSectionTest {
  private val password = "una contraseña larga".toCharArray()
  private val dirs = mutableListOf<File>()
  @After fun clean() { dirs.forEach { it.deleteRecursively() } }

  private val token = "token-largo-de-prueba-123456"
  private val key = "clave-del-buzon-de-prueba-0123456789-abcdef"
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"

  class FakeInstinct(var data: InstinctBackupData = InstinctBackupData()) : InstinctBackupAccess {
    var reactivation: InstinctBackupData? = null
    var failWrite = false
    override fun read() = data
    override fun write(data: InstinctBackupData) { if (failWrite) error("disco lleno"); this.data = data }
    override fun requestReactivation(data: InstinctBackupData) { reactivation = data }
    override fun reactivationPending() = reactivation != null
    override fun clearReactivation() { reactivation = null }
  }

  inner class Phone(val instinct: FakeInstinct = FakeInstinct()) {
    val dir: File = Files.createTempDirectory("insto-phone").toFile().also { dirs += it }
    val credentials = ConfigurationBackupManagerTest.FakeCredentials()
    val manager = ConfigurationBackupManager(listOf(CredentialsSection(credentials), InstinctSection(instinct)),
      "1.20.0", 29, FileRestoreMarker(File(dir, "restore-in-progress.json")), random = SecureRandom(), iterations = BackupCrypto.MIN_ITERATIONS)
  }

  private val policy = InstinctAutonomyPolicy().with(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR).copy(quiet = QuietHours(23 * 60, 7 * 60))
  private val full = InstinctBackupData(
    proposalsEnabled = true, replyMode = InstinctReplyMode.ALWAYS, channel = InstinctAckChannel.BITACORA,
    autonomy = InstinctAutonomyPolicy.encode(policy), routineGrants = """{"estudio":"${"a".repeat(64)}"}""",
    mailboxEnabled = true, mailboxAccept = InstinctMailboxAccept.SOLO_BUZON, whatsappCap = InstinctAutonomy.CONFIRMAR, mailboxInterval = 2,
    mailboxKey = key, logUrl = url, logToken = token, doorbellEnabled = true, doorbellWithOrder = true, doorbellDistributor = "io.heckel.ntfy",
    recurrences = listOf("todos los días a las 07:00 hasta el 30/12: poner el volumen al 30 por ciento"))

  private fun open(manager: ConfigurationBackupManager, bytes: ByteArray) = (manager.open(bytes, password) as OpenResult.Ready).plan

  @Test fun `a full copy carries every Instinct setting and secret, and restores them on another phone without turning anything on`() = runBlocking {
    val a = Phone(FakeInstinct(full))
    val file = a.manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone()
    val plan = open(b.manager, file)
    assertTrue(plan.includesCredentials)
    val report = b.manager.restore(plan, mapOf(BackupCategory.INSTINCT to RestoreMode.MERGE), RestoreOptions())
    assertTrue(report.failure, report.success)
    val restored = b.instinct.data
    assertEquals(url, restored.logUrl); assertEquals(token, restored.logToken); assertEquals(key, restored.mailboxKey)
    assertEquals(policy, InstinctAutonomyPolicy.decode(restored.autonomy!!))
    assertEquals(InstinctMailboxAccept.SOLO_BUZON, restored.mailboxAccept)
    assertEquals(2, restored.mailboxInterval); assertEquals("io.heckel.ntfy", restored.doorbellDistributor)
    assertTrue(restored.doorbellWithOrder)
    // Nothing on: remote control waits for the person.
    assertFalse(restored.proposalsEnabled); assertFalse(restored.mailboxEnabled); assertFalse(restored.doorbellEnabled)
    assertTrue(restored.recurrences.isEmpty())
    val asked = b.instinct.reactivation!!
    assertTrue(asked.proposalsEnabled && asked.mailboxEnabled && asked.doorbellEnabled)
    assertEquals(full.recurrences, asked.recurrences)
    assertTrue(report.attention.any { it.contains("Reactivar Instinct") })
  }

  @Test fun `a copy without secrets has no API key, token or mailbox key, and says so`() = runBlocking {
    val a = Phone(FakeInstinct(full)).apply { credentials.groq = "gsk_test_000000000000000000000000000000" }
    val file = a.manager.export(BackupCategory.entries.toSet(), password, secrets = false)
    val plain = BackupCrypto.decrypt(file, password).toString(Charsets.UTF_8)
    for (secret in listOf(token, key, "gsk_test")) assertFalse(secret, plain.contains(secret))
    assertTrue("the URL is configuration, not a secret", plain.contains(url))
    val b = Phone()
    val plan = open(b.manager, file)
    assertFalse(plan.includesCredentials)
    assertFalse(BackupCategory.CREDENTIALS in plan.categories)
    val preview = b.manager.preview(plan, mapOf(BackupCategory.INSTINCT to RestoreMode.MERGE), RestoreOptions()).getValue(BackupCategory.INSTINCT)
    assertTrue(preview.attention.any { it.contains("no trae el token") })
  }

  @Test fun `merging keeps a different token and key unless replacing them is chosen`() = runBlocking {
    val file = Phone(FakeInstinct(full)).manager.export(BackupCategory.entries.toSet(), password)
    val mine = full.copy(proposalsEnabled = false, mailboxKey = "otra-clave-del-buzon-0123456789-0123456789", logToken = "otro-token-largo-123456")
    val b = Phone(FakeInstinct(mine))
    b.manager.restore(open(b.manager, file), mapOf(BackupCategory.INSTINCT to RestoreMode.MERGE), RestoreOptions())
    assertEquals(mine.mailboxKey, b.instinct.data.mailboxKey); assertEquals(mine.logToken, b.instinct.data.logToken)
    b.manager.restore(open(b.manager, file), mapOf(BackupCategory.INSTINCT to RestoreMode.MERGE), RestoreOptions(replaceInstinctSecrets = true))
    assertEquals(key, b.instinct.data.mailboxKey); assertEquals(token, b.instinct.data.logToken)
  }

  @Test fun `restoring on the same phone with Instinct on keeps it on and changes nothing else by itself`() = runBlocking {
    val a = Phone(FakeInstinct(full))
    val file = a.manager.export(BackupCategory.entries.toSet(), password)
    val report = a.manager.restore(open(a.manager, file), mapOf(BackupCategory.INSTINCT to RestoreMode.REPLACE), RestoreOptions())
    assertTrue(report.success)
    assertTrue(a.instinct.data.proposalsEnabled && a.instinct.data.mailboxEnabled && a.instinct.data.doorbellEnabled)
    // Already on and already scheduled: nothing to reactivate, no recurrence scheduled twice.
    assertNull(a.instinct.reactivation)
  }

  @Test fun `a failure writes nothing and rolls the reactivation back`() = runBlocking {
    val file = Phone(FakeInstinct(full)).manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone()
    b.credentials.failOnSave = true
    val withKeys = Phone(FakeInstinct(full)).apply { credentials.groq = "gsk_test_000000000000000000000000000000" }
      .manager.export(BackupCategory.entries.toSet(), password)
    val report = b.manager.restore(open(b.manager, withKeys), mapOf(BackupCategory.INSTINCT to RestoreMode.MERGE,
      BackupCategory.CREDENTIALS to RestoreMode.MERGE), RestoreOptions())
    assertFalse(report.success)
    assertEquals(InstinctBackupData(), b.instinct.data)
    assertNull(b.instinct.reactivation)
    assertTrue(file.isNotEmpty())
  }

  @Test fun `every field is validated with the rules Insto applies when typed`() {
    val good = InstinctSection.encode(full)
    for ((path, value) in listOf("mailbox.key" to "corta", "log.url" to "https://evil.example.com/x", "log.token" to "a b",
      "mailbox.interval" to 7, "mailbox.whatsappCap" to "NUNCA", "doorbell.distributor" to "no un paquete", "proposals.replyMode" to "SIEMPRE")) {
      val bad = JSONObject(good.toString())
      val (obj, field) = path.split('.')
      bad.getJSONObject(obj).put(field, value)
      assertTrue(path, runCatching { InstinctSection.decode(bad) }.exceptionOrNull() is IllegalArgumentException)
    }
    val order = JSONObject(good.toString()).put("recurrences", org.json.JSONArray(listOf("#insto poner el volumen")))
    assertTrue("a recurrence never carries a prefix that reads as an order", runCatching { InstinctSection.decode(order) }.isFailure)
    assertEquals(full, InstinctSection.decode(good))
  }

  @Test fun `previews, plans and summaries never show a secret`() = runBlocking {
    val a = Phone(FakeInstinct(full))
    val plan = open(a.manager, a.manager.export(BackupCategory.entries.toSet(), password))
    val texts = plan.summaries.values.map { it.description } + plan.toString() + full.toString() +
      a.manager.preview(plan, mapOf(BackupCategory.INSTINCT to RestoreMode.REPLACE), RestoreOptions()).values.flatMap { it.changes + it.warnings + it.attention }
    for (text in texts) for (secret in listOf(token, key)) assertFalse(text, text.contains(secret))
  }

  @Test fun `copies from 1_12_0 to 1_19_0 still restore - they simply have no Instinct category`() = runBlocking {
    // The payload of 1.19.0: schema 1, the five categories of 1.12.0.
    val old = JSONObject().put("format", "insto-config").put("schema", 1).put("createdAt", "2026-11-20T15:04:05Z")
      .put("app", JSONObject().put("versionName", "1.19.0").put("versionCode", 28)).put("includesCredentials", true)
      .put("categories", JSONObject().put("credentials", JSONObject().put("v", 1).put("groqKey", "gsk_test_000000000000000000000000000000")))
    val file = BackupCrypto.encrypt(old.toString().toByteArray(), password, SecureRandom(), BackupCrypto.MIN_ITERATIONS)
    val b = Phone()
    val plan = open(b.manager, file)
    assertEquals(setOf(BackupCategory.CREDENTIALS), plan.categories)
    assertTrue(b.manager.restore(plan, mapOf(BackupCategory.CREDENTIALS to RestoreMode.MERGE), RestoreOptions()).success)
    assertNull(b.instinct.reactivation)
  }

  @Test fun `a copy of 1_20_0 opened by an older Insto skips the Instinct category as unknown`() {
    val payload = BackupFormat.decode(JSONObject().put("format", "insto-config").put("schema", 1)
      .put("categories", JSONObject().put("instinct", JSONObject().put("v", 1)).put("futuro", JSONObject().put("v", 1))).toString().toByteArray())
    assertTrue(BackupCategory.INSTINCT in payload.categories)
    assertEquals(listOf("futuro"), payload.unknownCategories)
  }

  @Test fun `a wrong password and a damaged file are refused before anything is written`() = runBlocking {
    val a = Phone(FakeInstinct(full))
    val file = a.manager.export(BackupCategory.entries.toSet(), password)
    val b = Phone()
    assertTrue(b.manager.open(file, "otra contraseña larga".toCharArray()) is OpenResult.Invalid)
    val damaged = file.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
    assertTrue(b.manager.open(damaged, password) is OpenResult.Invalid)
    assertEquals(InstinctBackupData(), b.instinct.data)
  }
}
