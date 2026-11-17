package com.example.context

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedTextIntentsTest {
  @Test fun readsSelectedTextWithoutRequiringWriteAccess() {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
      .putExtra(Intent.EXTRA_PROCESS_TEXT, "Una fecha mañana a las 15")
      .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    val result = SharedTextIntents.read(intent)!!
    assertEquals(SharedTextContext.Source.SELECTED, result.source)
    assertEquals("Una fecha mañana a las 15", result.text)
  }
  @Test fun sharedUrlRemainsALiteralUrl() {
    val result = SharedTextIntents.read(Intent(Intent.ACTION_SEND).setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, "https://example.com/documento"))!!
    assertEquals("https://example.com/documento", result.text)
    assertEquals(SharedTextContext.Source.SHARED, result.source)
  }
  @Test fun ignoresUnsupportedActionsAndBinaryAttachments() {
    assertNull(SharedTextIntents.read(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_TEXT, "ignored")))
    assertNull(SharedTextIntents.read(Intent(Intent.ACTION_ASSIST).putExtra(Intent.EXTRA_TEXT, "ignored")))
  }
  @Test fun missingTextIsAnExplicitError() {
    try { SharedTextIntents.read(Intent(Intent.ACTION_SEND).setType("text/plain")); fail("Missing content") }
    catch (_: IllegalArgumentException) { }
  }
}
