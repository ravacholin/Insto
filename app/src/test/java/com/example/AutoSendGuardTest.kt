package com.example

import com.example.assist.AutoSendGuard
import org.junit.Assert.*
import org.junit.Test

class AutoSendGuardTest {
  @Test fun `duplicate display names never authorize sending`() {
    assertFalse(AutoSendGuard.recipientMatches("+447700900123", listOf("Instinct")))
    assertFalse(AutoSendGuard.recipientMatches("+447700900123", listOf("+447700900999")))
    assertTrue(AutoSendGuard.recipientMatches("+447700900123", listOf("+44 7700 900123")))
  }
  @Test fun `saved recipient name is accepted only in the conversation opened for this dispatch`() {
    val phone = "+447700900123"
    assertTrue(AutoSendGuard.recipientMatches(phone, listOf("Instinct"), "Instinct", true))
    assertFalse(AutoSendGuard.recipientMatches(phone, listOf("Another contact"), "Instinct", true))
    assertFalse(AutoSendGuard.recipientMatches(phone, listOf("Instinct"), "Instinct", false))
    assertFalse(AutoSendGuard.recipientMatches(phone, listOf("Instinct"), "", true))
    assertTrue(AutoSendGuard.recipientMatches(phone, listOf("\u200eInstinct\u200e"), "Instinct", true))
  }
  @Test fun `old composer draft is never mistaken for a new audio attachment`() {
    assertFalse(AutoSendGuard.payloadMatches(null, "instinct_new.wav", "old draft", listOf("old.wav")))
    assertFalse(AutoSendGuard.payloadMatches(null, "instinct_new.wav", "old draft", listOf("instinct_new.wav")))
    assertTrue(AutoSendGuard.payloadMatches(null, "instinct_new.wav", null, listOf("instinct_new.wav")))
  }
  @Test fun `text must exactly match including unicode and newlines`() {
    val text = "Hola 😊\n& café"
    assertFalse(AutoSendGuard.payloadMatches(text, null, "previous message", emptyList()))
    assertTrue(AutoSendGuard.payloadMatches(text, null, text, emptyList()))
  }
}
