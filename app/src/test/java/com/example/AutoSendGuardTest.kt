package com.example

import com.example.assist.AutoSendGuard
import org.junit.Assert.*
import org.junit.Test

class AutoSendGuardTest {
  @Test fun `caption cannot authorize a different attachment`() {
    assertFalse(AutoSendGuard.payloadMatches("hola", "new.pdf", "hola", listOf("old.pdf")))
    assertFalse(AutoSendGuard.payloadMatches("hola", "new.pdf", "otro", listOf("new.pdf")))
    assertTrue(AutoSendGuard.payloadMatches("hola", "new.pdf", "hola", listOf("new.pdf")))
  }

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
  @Test fun `saved recipient is trusted after this audio share launch only`() {
    assertTrue(AutoSendGuard.launchMatches(7, null, "voice.m4a", -1, 7))
    assertFalse(AutoSendGuard.launchMatches(8, null, "voice.m4a", -1, 7))
    assertFalse(AutoSendGuard.launchMatches(7, null, "voice.m4a", -1, -1))
    assertFalse(AutoSendGuard.launchMatches(7, "hola", null, -1, 7))
    assertTrue(AutoSendGuard.launchMatches(7, "hola", null, 7, -1))
  }
  @Test fun `fresh owned audio preview can send when WhatsApp hides the filename`() {
    assertTrue(AutoSendGuard.ownedAudioPreviewMatches(8, 8, 8, 42, 42, "instinct_123456789.m4a", null, null))
    assertFalse(AutoSendGuard.ownedAudioPreviewMatches(8, 7, 8, 42, 42, "instinct_123456789.m4a", null, null))
    assertFalse(AutoSendGuard.ownedAudioPreviewMatches(8, 8, 7, 42, 42, "instinct_123456789.m4a", null, null))
    assertFalse(AutoSendGuard.ownedAudioPreviewMatches(8, 8, 8, 41, 42, "instinct_123456789.m4a", null, null))
    assertFalse(AutoSendGuard.ownedAudioPreviewMatches(8, 8, 8, 42, 42, "report.pdf", null, null))
    assertFalse(AutoSendGuard.ownedAudioPreviewMatches(8, 8, 8, 42, 42, "instinct_123456789.m4a", "old draft", null))
    assertFalse(AutoSendGuard.ownedAudioPreviewMatches(8, 8, 8, 42, 42, "instinct_123456789.m4a", null, "caption"))
  }
  @Test fun `preview must belong to the prepared launch and not a preexisting window`() {
    assertTrue(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 301, 43, setOf(41, 42), "com.whatsapp.audiopicker.AudioPreviewActivity"))
    assertFalse(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 299, 43, setOf(41, 42), "com.whatsapp.audiopicker.AudioPreviewActivity"))
    // Preparation and window creation can happen within the same uptime millisecond.
    assertTrue(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 300, 43, setOf(41, 42), "com.whatsapp.audiopicker.AudioPreviewActivity"))
    assertFalse(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 301, 42, setOf(41, 42), "com.whatsapp.audiopicker.AudioPreviewActivity"))
    assertFalse(AutoSendGuard.freshAudioPreviewMatches(8, 7, 300, 301, 43, setOf(41, 42), "com.whatsapp.audiopicker.AudioPreviewActivity"))
    assertTrue(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 301, 43, emptySet(), "com.whatsapp.audiopicker.AudioPreviewActivity"))
    assertFalse(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 301, 43, null, "com.whatsapp.audiopicker.AudioPreviewActivity"))
    assertFalse(AutoSendGuard.freshAudioPreviewMatches(8, 8, 300, 301, 43, setOf(41, 42), "com.whatsapp.HomeActivity"))
  }
  @Test fun `text must exactly match including unicode and newlines`() {
    val text = "Hola 😊\n& café"
    assertFalse(AutoSendGuard.payloadMatches(text, null, "previous message", emptyList()))
    assertTrue(AutoSendGuard.payloadMatches(text, null, text, emptyList()))
  }
}
