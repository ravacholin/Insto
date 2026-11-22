package com.example.branding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.20.0: the app is called Insto on every visible surface. Fails on an old name in a string resource, a layout text,
 * a manifest label, a Kotlin string literal or the user documentation. Internal identifiers keep their historical names
 * on purpose (class names, the theme's style name, the DataStore file, the applicationId): renaming them would break
 * updates, the Accessibility grant, widgets or stored data, and none of them is shown to the person.
 */
class InstoNameTest {
  private val old = Regex("PingAssist|Ping Assist|Voice Ping|PingAssist Voice|Auto-Send & Triggers|Auto-Send &amp; Triggers|\"Ping: ")

  /** Identifiers that are not text: allowed only in these forms. */
  private val allowedIdentifiers = listOf(
    Regex("""Theme\.PingAssist\.Translucent"""),
    Regex("""\.assist\.PingAssistWidgetProvider"""),
    // Code in Markdown (a class name in backticks) is an identifier, not text shown to the person.
    Regex("`[^`]*`"),
  )

  private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .first { File(it, "app/src/main").isDirectory || File(it, "src/main/AndroidManifest.xml").isFile }
    .let { if (File(it, "app/src/main").isDirectory) File(it, "app") else it }

  private fun offenders(file: File, text: String = file.readText()): List<String> = text.lineSequence().withIndex()
    .filter { (_, line) -> old.containsMatchIn(allowedIdentifiers.fold(line) { acc, r -> acc.replace(r, "") }) }
    .map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }.toList()

  @Test fun `string resources, layouts and the manifest say Insto`() {
    val res = File(root, "src/main/res")
    val files = res.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList() + File(root, "src/main/AndroidManifest.xml")
    assertTrue(files.size > 5)
    assertEquals(emptyList<String>(), files.flatMap { offenders(it) })
  }

  @Test fun `no Kotlin string literal shows an old name`() {
    val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
    val found = File(root, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
      file.readLines().withIndex().flatMap { (i, line) ->
        literal.findAll(line).map { it.value }.filter { old.containsMatchIn(it) }.map { "${file.relativeTo(root)}:${i + 1}: $it" }
      }
    }.toList()
    assertEquals(emptyList<String>(), found)
  }

  @Test fun `visible labels use the agreed names`() {
    val strings = File(root, "src/main/res/values/strings.xml").readText()
    for (name in listOf(">Insto<", ">Insto · accesibilidad<", ">Insto · voz<")) assertTrue(name, strings.contains(name))
    val manifest = File(root, "src/main/AndroidManifest.xml").readText()
    assertTrue(manifest.contains("android:label=\"Insto · notificaciones\""))
  }

  @Test fun `the user documentation says Insto`() {
    val docs = File(root.parentFile, "docs")
    if (!docs.isDirectory) return
    // The audit of 1.20.0 quotes the old names it removed; the release notes may too.
    val quoting = setOf("instinct-execution-journal.md", "Insto-1.20.0.md")
    val found = docs.walkTopDown().filter { it.isFile && it.extension == "md" && it.name !in quoting }.flatMap { offenders(it) }.toList()
    assertEquals(emptyList<String>(), found)
  }
}
