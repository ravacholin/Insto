package com.example.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptSearchCommandTest {
  private val parser = PhoneCommandParser()

  @Test fun explicitSearchVerbsPreserveTheQuery() {
    for (verb in listOf("buscar", "busca", "Buscá", "Busca\u0301", "SEARCH")) {
      assertEquals("Córdoba & C++", parser.chatGptSearchQuery("  $verb Córdoba & C++  "))
    }
    assertEquals("Córdoba", parser.chatGptSearchQuery("Search: Córdoba"))
  }

  @Test fun ordinaryRequestsAndMissingQueriesDoNotTriggerSearch() {
    for (text in listOf("Córdoba", "¿Busca Córdoba?", "No busques Córdoba",
      "Cómo buscar Córdoba", "Instinct, search Córdoba", "research Córdoba", "buscador Córdoba", "buscar", "search:")) {
      assertNull(text, parser.chatGptSearchQuery(text))
    }
  }

  @Test fun existingTargetedSearchesKeepTheirRoutes() {
    for (text in listOf("buscá jazz en YouTube", "buscar en Spotify jazz", "busca en Maps farmacias", "buscá el contacto Ana")) {
      assertNull(text, parser.chatGptSearchQuery(text))
    }
  }
}
