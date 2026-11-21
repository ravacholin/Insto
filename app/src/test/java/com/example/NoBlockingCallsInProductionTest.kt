package com.example

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `runBlocking` in app code has meant reading DataStore on the main thread (ANR risk). Production code suspends or
 * reads [com.example.data.AssistantPreferencesCache] instead. The allowlist is empty on purpose: adding to it needs a
 * reason that no coroutine-based alternative exists.
 */
class NoBlockingCallsInProductionTest {
  private val allowlist = emptySet<String>()

  @Test fun productionSourcesNeverCallRunBlocking() {
    val main = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
      ?: error("app/src/main not found from ${File(".").absolutePath}")
    val sources = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
    assertTrue("No sources found under $main", sources.size > 50)
    val offenders = sources.flatMap { file ->
      val path = file.relativeTo(main).invariantSeparatorsPath
      file.readLines().mapIndexedNotNull { index, line ->
        "$path:${index + 1}: ${line.trim()}".takeIf { "runBlocking" in line && path !in allowlist }
      }
    }
    assertEquals("runBlocking in production code:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
  }
}
