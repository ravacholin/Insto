package com.example.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstinctCapabilitiesTest {
  @Test fun `local clock parameters are strict and the dispatch is headless`() {
    val valid = JSONObject().put("seconds", 60)
    assertEquals("insto_timer", (InstinctCapabilities.command("insto_timer", valid) as PhoneCommand.Platform).argument)
    assertTrue(InstinctCapabilities.specs.getValue("insto_timer").runsLocked)
    assertTrue(runCatching { InstinctCapabilities.command("insto_timer", JSONObject().put("seconds", "60")) }.isFailure)
    assertTrue(runCatching { InstinctCapabilities.command("insto_timer", valid.put("extra", true)) }.isFailure)
  }

  @Test fun `external resources only accept HTTPS without credentials`() {
    val spec = InstinctCapabilities.specs.getValue("open_resource")
    assertFalse(spec.runsLocked)
    for (uri in listOf("file:///etc/passwd", "intent://x", "https://user@example.org")) {
      assertTrue(runCatching { InstinctCapabilities.command(spec.id, JSONObject().put("url", uri)) }.isFailure)
    }
    assertEquals(InstinctCategory.APPS, spec.category)
  }
}
