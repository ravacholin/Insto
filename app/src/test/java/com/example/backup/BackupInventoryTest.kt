package com.example.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.20.0: every store in the sources is classified in [BackupInventory]. A new file, preference key, DataStore or
 * SharedPreferences fails here until it is listed, so the backup never forgets a setting silently.
 */
class BackupInventoryTest {
  private val main: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .first { File(it, "app/src/main/java").isDirectory || File(it, "src/main/java").isDirectory }
    .let { if (File(it, "app/src/main/java").isDirectory) File(it, "app/src/main/java") else File(it, "src/main/java") }

  private val sources by lazy { main.walkTopDown().filter { it.isFile && it.extension == "kt" }.associateWith { it.readText() } }

  private fun found(vararg patterns: Regex): Set<String> =
    sources.values.flatMap { text -> patterns.flatMap { p -> p.findAll(text).map { it.groupValues[1] }.toList() } }.toSet()

  @Test fun `every stored file in the sources is in the inventory`() {
    val files = found(
      Regex("""noBackupFilesDir, *"([^"]+)""""),
      Regex("""const val [A-Z_]*FILE[A-Z_]* *= *"([^"]+)""""),
      Regex("""const val MARKER *= *"([^"]+)""""),
      Regex("""WorkflowStore\([^,)]+(?:, *name *=)? *"([^"]+)"\)"""),
      Regex("""class WorkflowStore\([^)]*name: String = "([^"]+)""""),
    ) + found(Regex("""GroqKeyStore\([^,)]+, *"([^"]+)"\)""")).map { "$it-key.enc" } + "groq-key.enc"
    assertTrue("the scan finds the stores: $files", files.size > 30)
    val missing = files.filter { BackupInventory.named(it) == null }.sorted()
    assertEquals("Clasificá en BackupInventory: $missing", emptyList<String>(), missing)
  }

  @Test fun `every assistant preference key, DataStore and SharedPreferences is in the inventory`() {
    val repository = sources.entries.first { it.key.name == "AssistantPreferencesRepository.kt" }.value
    val keys = Regex("""(?:string|boolean|int|long|float)PreferencesKey\("([^"]+)"\)""").findAll(repository).map { it.groupValues[1] }.toSet()
    assertTrue(keys.size >= 15)
    val stores = found(Regex("""preferencesDataStore\(name = "([^"]+)""""), Regex("""getSharedPreferences\("([^"]+)""""))
    val missing = (keys + stores).filter { BackupInventory.named(it) == null }.sorted()
    assertEquals("Clasificá en BackupInventory: $missing", emptyList<String>(), missing)
  }

  @Test fun `what is exportable has a category, what is not has none`() {
    for (item in BackupInventory.items) when (item.cls) {
      BackupInventory.Class.CONFIG, BackupInventory.Class.SECRET -> assertNotNull(item.name, item.category)
      else -> assertNull(item.name, item.category)
    }
  }

  @Test fun `every secret travels only in a category the copy without secrets drops or strips`() {
    val secrets = BackupInventory.items.filter { it.cls == BackupInventory.Class.SECRET }
    assertTrue(secrets.any { it.name == "instinct-log-token" } && secrets.any { it.name == "instinct-mailbox-key" })
    assertTrue(secrets.all { it.category == BackupCategory.CREDENTIALS || it.category == BackupCategory.INSTINCT })
  }

  @Test fun `device-bound and history data are never exported`() {
    for (name in listOf("instinct-doorbell-endpoint", "instinct-doorbell-sealed", "instinct-doorbell-state.json", "instinct-executions.db"))
      assertNull(name, BackupInventory.named(name)!!.category)
  }
}
