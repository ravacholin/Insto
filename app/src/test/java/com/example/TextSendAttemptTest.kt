package com.example

import com.example.assist.TextSendAttempt
import org.junit.Assert.*
import org.junit.Test

class TextSendAttemptTest {
  @Test fun `explicit launch permits a verified send without Activity or text-change events`() {
    val attempt = TextSendAttempt(7)
    assertTrue(attempt.beginTouch(7, true, true, true))
  }
  @Test fun `recipient message and button must all be verified before touching`() {
    val attempt = TextSendAttempt(7)
    assertFalse(attempt.beginTouch(7, false, true, true))
    assertFalse(attempt.beginTouch(7, true, false, true))
    assertFalse(attempt.beginTouch(7, true, true, false))
    assertFalse(attempt.beginTouch(8, true, true, true))
    assertTrue(attempt.beginTouch(7, true, true, true))
  }
  @Test fun `never touches twice even if the first touch outcome is uncertain`() {
    val attempt = TextSendAttempt(7)
    assertTrue(attempt.beginTouch(7, true, true, true))
    assertFalse(attempt.beginTouch(7, true, true, true))
  }
}
