package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class NativeIntentSpecTest {
  @Test fun mapQueryCannotInjectAnotherDestinationOrParameter() {
    val spec = NativeIntentSpec.forCommand(PhoneCommand.Navigate("Café & destino=otro", TravelMode.WALKING))!!
    assertEquals("android.intent.action.VIEW", spec.action)
    assertEquals("https://www.google.com/maps/dir/?api=1&destination=Caf%C3%A9+%26+destino%3Dotro&travelmode=walking&dir_action=navigate", spec.dataUri)
    assertEquals("https://www.google.com/maps/search/?api=1&query=farmacias+cerca", NativeIntentSpec.forCommand(PhoneCommand.MapSearch("farmacias cerca"))!!.dataUri)
  }
  @Test fun invalidOrOversizedPayloadsNeverProduceAHandoff() {
    assertNull(NativeIntentSpec.forCommand(PhoneCommand.Navigate(" ")))
    assertNull(NativeIntentSpec.forCommand(PhoneCommand.MapSearch("a".repeat(3000))))
    assertNull(NativeIntentSpec.forCommand(PhoneCommand.CalendarEvent("cita", 5000, 4000)))
  }
  @Test fun dialerOnlyReceivesAValidatedNumberAndNeverPlacesTheCall() {
    val spec = NativeIntentSpec.forCommand(PhoneCommand.Dial("+54 (11) 5555-0100"))!!
    assertEquals("android.intent.action.DIAL", spec.action)
    assertEquals("tel:+541155550100", spec.dataUri)
    for (bad in listOf("*123#", "123;456", "+54+11", "Ana", "12", "123 ext 4")) {
      assertNull(bad, NativeIntentSpec.forCommand(PhoneCommand.Dial(bad)))
    }
  }
  @Test fun calendarContractCarriesTitleAndBothInstants() {
    val spec = NativeIntentSpec.forCommand(PhoneCommand.CalendarEvent("Clase de español", 1000L, 3601000L))!!
    assertEquals("android.intent.action.INSERT", spec.action)
    assertEquals("content://com.android.calendar/events", spec.dataUri)
    assertEquals("Clase de español", spec.stringExtras["title"])
    assertEquals(1000L, spec.longExtras["beginTime"])
    assertEquals(3601000L, spec.longExtras["endTime"])
  }
  @Test fun cameraMusicAndYouTubeUseTheirNativeContracts() {
    assertEquals("android.media.action.STILL_IMAGE_CAMERA", NativeIntentSpec.forCommand(PhoneCommand.Camera(false))!!.action)
    assertEquals("android.media.action.VIDEO_CAMERA", NativeIntentSpec.forCommand(PhoneCommand.Camera(true))!!.action)
    val music = NativeIntentSpec.forCommand(PhoneCommand.MusicSearch("Björk & Jazz"))!!
    assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", music.action)
    assertEquals("Björk & Jazz", music.stringExtras["query"])
    assertEquals("vnd.android.cursor.item/*", music.stringExtras["android.intent.extra.focus"])
    assertEquals("https://www.youtube.com/results?search_query=ELE+%26+jazz", NativeIntentSpec.forCommand(PhoneCommand.YouTubeSearch("ELE & jazz"))!!.dataUri)
  }
  @Test fun spotifySearchOpensEncodedResultsWithoutRequestingPlayback() {
    val route = PhoneCommandParser().parse("buscar Björk & jazz en spotify")
    assertTrue(route is PhoneRoute.Local)
    val command = (route as PhoneRoute.Local).command
    val spec = NativeIntentSpec.forCommand(command)!!
    assertEquals("android.intent.action.VIEW", spec.action)
    assertEquals("https://open.spotify.com/search/Bj%C3%B6rk%20%26%20jazz", spec.dataUri)
    assertTrue(spec.stringExtras.isEmpty())
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
  }

}
