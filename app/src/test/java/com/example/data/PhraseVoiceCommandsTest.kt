package com.example.data

import org.junit.Assert.*
import org.junit.Test

class PhraseVoiceCommandsTest {
  @Test fun reconoceOlvidarYMostrar() {
    for (text in listOf("Olvidá esta frase", "olvidate de esa frase", "borrá la última frase.")) {
      assertEquals(text, PhraseVoiceCommand.ForgetLast, PhraseVoiceCommands.parse(text))
    }
    for (text in listOf("Mostrame las frases aprendidas", "frases aprendidas", "¿Qué frases aprendiste?")) {
      assertEquals(text, PhraseVoiceCommand.Show, PhraseVoiceCommands.parse(text))
    }
    assertNull(PhraseVoiceCommands.parse("¿olvidá esta frase?"))
  }

  @Test fun reconoceEnsenarConSeparadorYAccionLocal() {
    assertEquals(PhraseVoiceCommand.Teach("luz", "prende la linterna"),
      PhraseVoiceCommands.parse("Cuando diga «luz», prendé la linterna."))
    assertEquals(PhraseVoiceCommand.Teach("modo estudio", "ejecuta la rutina estudio"),
      PhraseVoiceCommands.parse("Si te digo modo estudio: ejecutá la rutina Estudio"))
    assertEquals(PhraseVoiceCommand.Teach("modo cine", "pone el volumen al 20"),
      PhraseVoiceCommands.parse("Aprendé que modo cine es poné el volumen al 20"))
    assertEquals(PhraseVoiceCommand.Teach("musica", "abri spotify"),
      PhraseVoiceCommands.parse("Acordate que música significa abrí Spotify"))
    // Una condición con algo que no es una acción local sigue siendo una consulta.
    assertNull(PhraseVoiceCommands.parse("Si digo hola en Japón, decime cómo responden"))
    assertNull(PhraseVoiceCommands.parse("Cuando diga luz prendé la linterna"))
  }

  @Test fun reconoceCorregirLaUltimaFrase() {
    for (text in listOf("Corregí esta frase: abrí YouTube", "corregí la frase por abrí YouTube",
      "No, esa frase significa abrí YouTube", "cambiá la última frase a abrí YouTube")) {
      assertEquals(text, PhraseVoiceCommand.CorrectLast("abri youtube"), PhraseVoiceCommands.parse(text))
    }
  }

  @Test fun noInterceptaPedidosComunes() {
    for (text in listOf("prendé la linterna", "poné una alarma a las 7.30", "abrí Spotify", "frases célebres de Borges",
      "cambiá la frase del estado a hola", "esa frase es de Borges")) {
      assertNull(text, PhraseVoiceCommands.parse(text))
    }
  }
}
