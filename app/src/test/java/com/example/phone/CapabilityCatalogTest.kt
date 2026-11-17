package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class CapabilityCatalogTest {
  @Test fun existingPhoneCapabilitiesAreAvailableToTheClassifier() {
    for (choice in listOf("media_play", "media_pause", "media_next", "media_previous", "media_status",
      "temporary_mode", "end_mode", "open_app", "settings", "workflow")) {
      assertTrue("Missing semantic capability: $choice", choice in GeminiDecisionsClient.choices)
    }
  }

  @Test fun volumeAndModeSchemasDoNotRequireInventedPercentages() {
    val schema = StructuredCommandClient.buildRequest("pedido", Calendar.getInstance())
      .getJSONObject("generationConfig").getJSONObject("responseJsonSchema").toString()
    // The schema must distinguish the closed relative operations from SET.
    assertTrue(schema, schema.contains("UP") && schema.contains("DOWN") && schema.contains("SET"))
    assertTrue(schema, schema.contains("MEDIA_VOLUME") && schema.contains("DND"))
  }
}
