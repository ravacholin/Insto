package com.example

import com.example.assist.AudioSendAttempt
import org.junit.Assert.*
import org.junit.Test

class AudioSendAttemptTest {
  @Test fun `targeted audio confirms the exact share dialog without a visible filename`() {
    val attempt = AudioSendAttempt(7, "Instinct", "+447700900123")
    assertTrue(attempt.beginConfirmation(7, listOf("Share with Instinct?"), true))
    assertFalse(attempt.beginConfirmation(7, listOf("Share with Instinct?"), true))
  }

  @Test fun `wrong recipient unrelated prompts stale requests and disabled buttons never confirm`() {
    for (prompt in listOf("Share with Someone else?", "Share with Instinct team?", "Delete Instinct?", "Instinct")) {
      assertFalse(AudioSendAttempt(7, "Instinct", "+447700900123")
        .beginConfirmation(7, listOf(prompt), true))
    }
    val attempt = AudioSendAttempt(7, "Instinct", "+447700900123")
    assertFalse(attempt.beginConfirmation(6, listOf("Share with Instinct?"), true))
    assertFalse(attempt.beginConfirmation(7, listOf("Share with Instinct?"), false))
    assertTrue(attempt.beginConfirmation(7, listOf("Share with Instinct?"), true))
  }

  @Test fun `Spanish confirmation and formatted phone recipient are recognized exactly`() {
    assertTrue(AudioSendAttempt(7, "Instinct", "+447700900123")
      .beginConfirmation(7, listOf("¿Compartir con \u200eInstinct\u200e?"), true))
    assertTrue(AudioSendAttempt(8, "Instinct", "+447700900123")
      .beginConfirmation(8, listOf("Share with +44 7700 900123?"), true))
  }

  @Test fun `confirmation can be followed by one preview send without duplicate touches`() {
    val attempt = AudioSendAttempt(7, "Instinct", "+447700900123")
    assertTrue(attempt.beginConfirmation(7, listOf("Share with Instinct?"), true))
    assertFalse(attempt.beginSend(7, true, false, true))
    assertFalse(attempt.beginSend(6, true, true, true))
    assertTrue(attempt.beginSend(7, true, true, true))
    assertFalse(attempt.beginSend(7, true, true, true))
  }

  @Test fun `cancelling a preview after OK never counts as a submitted recording`() {
    val attempt = AudioSendAttempt(7, "Instinct", "+447700900123")
    assertFalse(attempt.canConfirmSubmission())
    assertTrue(attempt.beginConfirmation(7, listOf("Share with Instinct?"), true))
    assertTrue(attempt.canConfirmSubmission())
    attempt.observePreview()
    assertFalse(attempt.canConfirmSubmission())
    assertTrue(attempt.beginSend(7, true, true, true))
    assertTrue(attempt.canConfirmSubmission())
  }
}
