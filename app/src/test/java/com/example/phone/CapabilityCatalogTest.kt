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

  @Test fun classifierCatalogKeepsEveryIdAndUsesCompactDescriptions() {
    val criteria = GeminiDecisionsClient.buildRequest("pedido").getJSONObject("questions")
      .getJSONObject("action").getJSONObject("criteria")
    val baselineIds = """
      accessibility_settings alarm alarm_dismiss alarm_snooze alarm_update alarms_show app_open
      app_settings battery bluetooth_off bluetooth_on brightness_set calendar calendar_agenda
      calendar_delete calendar_query calendar_update call_direct camera_capture camera_photo
      camera_record camera_video contact_phones contact_search dial dialer_open display_settings dnd_off
      dnd_on end_mode file_copy file_delete file_move file_open file_rename file_search file_share
      files_browse instinct maps media_next media_pause media_pause_spotify media_pause_youtube
      media_play media_previous media_status media_stop media_stop_spotify media_stop_youtube
      message_draft message_open message_send music navigate notification_dismiss notification_list
      notification_open open_app place_save recorder_open recorder_start recorder_stop routine_import
      routine_list routine_run settings settings_bluetooth settings_internet settings_open settings_wifi
      shortcut_run silent_off silent_on spotify stopwatch_pause stopwatch_reset stopwatch_resume
      stopwatch_show stopwatch_start temporary_mode temporary_mode_end text_share timer timer_dismiss
      timer_pause timer_resume timer_update timers_show torch_off torch_on unsupported vibrate_on
      volume_down volume_set volume_up wifi_off wifi_on workflow youtube
    """.trim().split(Regex("\\s+")).toSet()
    val actualIds = criteria.keys().asSequence().toSet()
    assertEquals(baselineIds, actualIds)
    for (id in actualIds) {
      val description = criteria.getString(id)
      assertTrue("$id: $description", description.isNotBlank() && description.length <= 150)
    }
  }

  @Test fun playbackChoicesDistinguishTheSessionTitleAndApplication() {
    val choices = GeminiDecisionsClient.choices
    assertTrue(choices.getValue("media_play").contains("ya estaba sonando"))
    assertTrue(choices.getValue("music").contains("canción, artista"))
    assertTrue(choices.getValue("music").endsWith("Obligatorios: query."))
    assertTrue(choices.getValue("media_stop_youtube").contains("Parar la reproducción de YouTube"))
    assertTrue(choices.getValue("media_pause_youtube").contains("Pausar la reproducción de YouTube"))
  }

  @Test fun volumeAndModeSchemasDoNotRequireInventedPercentages() {
    val schema = StructuredCommandClient.buildRequest("pedido", Calendar.getInstance())
      .getJSONObject("generationConfig").getJSONObject("responseJsonSchema").toString()
    // The schema must distinguish the closed relative operations from SET.
    assertTrue(schema, schema.contains("UP") && schema.contains("DOWN") && schema.contains("SET"))
    assertTrue(schema, schema.contains("MEDIA_VOLUME") && schema.contains("DND"))
  }
}
