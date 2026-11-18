package com.example.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyHintsTest {
  @Test fun preservesSourcePriorityAndOrder() {
    val prompt = VocabularyHints.build(
      routines = listOf("Arranque"),
      places = listOf("casa"),
      learnedPhrases = listOf("abrí el reproductor", "sacá una foto"),
      apps = listOf("VLC"),
      contacts = listOf("Inés"),
      includeContacts = true,
    )!!
    assertTrue(prompt.endsWith(
      "Instinct, WhatsApp, Spotify, YouTube, linterna, cronómetro, temporizador, alarma, No molestar, " +
        "Arranque, casa, abrí el reproductor, sacá una foto, VLC, Inés",
    ))
  }

  @Test fun stopsAtTheFirstWholeTermThatDoesNotFit() {
    val baseline = VocabularyHints.build()!!
    val fullTerm = "a".repeat(600 - baseline.length - 2)
    val prompt = VocabularyHints.build(routines = listOf(fullTerm, "Corte"), apps = listOf("VLC"))
    assertEquals(600, prompt?.length)
    assertEquals("$baseline, $fullTerm", prompt)
    assertEquals(baseline, VocabularyHints.build(routines = listOf(fullTerm + "a"), apps = listOf("VLC")))
  }

  @Test fun retainsTheFirstSpellingForNormalizedDuplicates() {
    val prompt = VocabularyHints.build(routines = listOf("SPOTÍFY", "Café"), apps = listOf("CAFE!", "VLC"))!!
    assertEquals(VocabularyHints.build(routines = listOf("Café"), apps = listOf("VLC")), prompt)
  }

  @Test fun sanitizesControlCharactersAndDiscardsTermsWithFiveDigits() {
    val prompt = VocabularyHints.build(routines = listOf(
      "Modo\r\nNoche\u0000", "Guardia 1234", "Guardia 12345", "11 23-4", "١٢٣٤٥", "\t\u0000",
    ))
    assertEquals(VocabularyHints.build(routines = listOf("Modo Noche", "Guardia 1234")), prompt)
  }

  @Test fun returnsNullWhenEveryTermIsFiltered() {
    assertNull(VocabularyHints.build(fixedTerms = listOf("\n", "12345", "...")))
  }

  @Test fun onlyIncludesContactsWhenEnabled() {
    val contacts = listOf("Inés", "Marcos")
    val defaultPrompt = VocabularyHints.build(contacts = contacts)!!
    assertFalse(defaultPrompt.contains("Inés"))
    assertFalse(defaultPrompt.contains("Marcos"))
    assertTrue(VocabularyHints.build(contacts = contacts, includeContacts = true)!!.endsWith(", Inés, Marcos"))
  }
}
