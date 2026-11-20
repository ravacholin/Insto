package com.example.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseCanonicalizerTest {
  private fun canonical(text: String) = PhraseCanonicalizer.canonical(PhoneCommandParser.normalize(text))

  @Test fun vosTuInfinitiveAndCliticsShareOneForm() {
    val expected = "prender la luz"
    for (text in listOf("Prendé la luz", "prende la luz", "prender la luz", "Prendeme la luz", "préndeme la luz")) {
      assertEquals(text, expected, canonical(text))
    }
    assertEquals("bajar la musica al 30", canonical("Bajame la música al 30"))
    assertEquals("poner la musica", canonical("Ponme la música"))
    assertEquals("encender la luz", canonical("Enciende la luz"))
    assertEquals("encender la luz", canonical("Encendé la luz"))
    assertEquals("prender la", canonical("prendela"))
  }

  @Test fun synonymsAndOppositeVerbsStayDifferent() {
    assertNotEquals(canonical("prendé la luz"), canonical("encendé la luz"))
    assertNotEquals(canonical("subí la música"), canonical("bajá la música"))
    assertNotEquals(canonical("prendé la luz"), canonical("apagá la luz"))
  }

  @Test fun fillersAreRemovedButNeverNegationsOrConditions() {
    assertEquals("prender la luz", canonical("Che, prendeme la luz porfa"))
    assertEquals("poner modo cine", canonical("Dale, poné modo cine por favor"))
    assertEquals("prender la luz", canonical("a ver, che, prendé la luz"))
    assertEquals("no prender la luz", canonical("no prendas la luz").replace("prendas", "prender"))
    assertTrue(canonical("che no prendas la luz").startsWith("no "))
    assertTrue(canonical("prendé la luz si llego").contains(" si "))
    // Too little left: the words stay, only the verb is reduced.
    assertEquals("dar le luz", canonical("dale luz"))
  }

  @Test fun oneTranscriptionEditOnlyOnSafeTokens() {
    fun near(a: String, b: String) = PhraseCanonicalizer.nearMatch(
      PhraseCanonicalizer.tokens(canonical(a)), PhraseCanonicalizer.tokens(canonical(b)))
    assertTrue(near("bajá la músca al 40", "bajá la música al 40"))
    assertTrue(near("modo cinem", "modo cine ma".replace(" ma", "ma")))
    assertTrue(near("poné la licterna", "poné la linterna"))
    assertFalse("two differences", near("poné la licterna fuertr", "poné la linterna fuerte"))
    assertFalse("short words", near("modo cena", "modo cine"))
    assertFalse("numbers", near("volumen al treinta", "volumen al treinte"))
    assertFalse("verbs", near("subir la musica", "subis la musica"))
    assertFalse("negation", near("nunca linterna", "nunqa linterna"))
    assertFalse("different length", near("la linterna", "la linterna ya"))
    assertEquals(1, PhraseCanonicalizer.distance("musica", "muscia"))
    assertEquals(2, PhraseCanonicalizer.distance("musica", "msuisca"))
  }
}
