package com.example.data

import com.example.phone.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhraseTeachingTest {
  private val torch = PhoneCommand.Torch(true)

  @Test fun laAccionTieneQueSerUnPedidoLocalAdmitido() {
    val valid = PhraseTeaching.resolveTarget("prendé la linterna") as PhraseTeaching.Target.Valid
    assertEquals(torch, valid.command)
    assertTrue(PhraseTeaching.resolveTarget("ejecutá la rutina Estudio") is PhraseTeaching.Target.Valid)
    assertTrue(PhraseTeaching.resolveTarget("poné un timer de diez minutos") is PhraseTeaching.Target.Valid)
    for (text in listOf("", "contame un chiste", "llamá a 1155551234")) {
      assertTrue(text, PhraseTeaching.resolveTarget(text) is PhraseTeaching.Target.Invalid)
    }
  }

  @Test fun laFraseTieneQueLlegarAlBuscadorDeFrases() = runTest {
    val ok = PhraseTeaching.checkPhrase("Luz", torch) as PhraseTeaching.Phrase.Valid
    assertEquals("luz", ok.key)
    assertFalse(ok.replaces)
    assertTrue(PhraseTeaching.checkPhrase("luz", torch, existing = listOf("luz")).let { it is PhraseTeaching.Phrase.Valid && it.replaces })
    assertFalse((PhraseTeaching.checkPhrase("luz", torch, listOf("luz"), editing = "luz") as PhraseTeaching.Phrase.Valid).replaces)
    for (text in listOf("", "prendé la linterna", "¿luz?", "no luz", "luz si llego", "cancelar", "dale",
      "olvidá esta frase", "luz y subí el volumen", "explicame la luz")) {
      assertTrue(text, PhraseTeaching.checkPhrase(text, torch) is PhraseTeaching.Phrase.Invalid)
    }
  }

  /** The examples in docs/releases/Insto-frases-aprendidas.md must work as written. */
  @Test fun losEjemplosDeLaGuiaSePuedenGuardar() = runTest {
    for ((phrase, target) in listOf("luz" to "prendé la linterna", "modo estudio" to "ejecutá la rutina Estudio",
      "música" to "abrí Spotify", "té" to "poné un timer de cuatro minutos", "a casa" to "llevame a casa",
      "modo cine" to "poné el volumen al 20")) {
      val resolved = PhraseTeaching.resolveTarget(target)
      assertTrue("$target: $resolved", resolved is PhraseTeaching.Target.Valid)
      val check = PhraseTeaching.checkPhrase(phrase, (resolved as PhraseTeaching.Target.Valid).command)
      assertTrue("$phrase: $check", check is PhraseTeaching.Phrase.Valid)
    }
  }
}
