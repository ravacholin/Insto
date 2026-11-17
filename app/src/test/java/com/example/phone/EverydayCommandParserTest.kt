package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class EverydayCommandParserTest {
  private fun now(zone: String = "America/Argentina/Buenos_Aires"): Calendar = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
    clear(); set(2026, Calendar.NOVEMBER, 16, 10, 0, 0)
  }
  private val parser = PhoneCommandParser { now() }
  private fun local(text: String): PhoneCommand = (parser.parse(text) as PhoneRoute.Local).command

  @Test fun navigationPreservesAddressesAndUnderstandsWalking() {
    assertEquals(PhoneCommand.Navigate("Av. Córdoba 1234, Buenos Aires", TravelMode.DRIVING), local("Llevame a Av. Córdoba 1234, Buenos Aires"))
    assertEquals(PhoneCommand.Navigate("Plaza de Mayo", TravelMode.WALKING), local("quiero que me lleves a pie a Plaza de Mayo"))
    assertEquals(PhoneCommand.Navigate("Plaza de Mayo", TravelMode.WALKING), local("llevame a Plaza de Mayo a pie"))
    assertEquals(PhoneCommand.Navigate("casa", TravelMode.DRIVING), local("llevame a casa"))
    assertEquals(PhoneCommand.SavePlace("casa", "Av. San Martín 42, Córdoba"), local("Guardá mi casa en Av. San Martín 42, Córdoba"))
    assertEquals(PhoneCommand.SavePlace("trabajo", "Escuela Nº 2"), local("mi trabajo es Escuela Nº 2"))
  }
  @Test fun searchesPreserveAccentsCaseAndQueryPunctuation() {
    assertEquals(PhoneCommand.MapSearch("farmacias cerca"), local("buscá farmacias cerca"))
    assertEquals(PhoneCommand.MapSearch("café & té"), local("buscá en Maps café & té"))
    assertEquals(PhoneCommand.YouTubeSearch("¿Qué es ELE?"), local("buscá ¿Qué es ELE? en YouTube"))
    assertEquals(PhoneCommand.MusicSearch("Soda Stereo"), local("Poné música de Soda Stereo"))
    assertEquals(PhoneCommand.MusicSearch("De Música Ligera"), local("reproducí la canción De Música Ligera"))
  }
  @Test fun explicitSearchesPreserveThePlatformQueryAndSpokenPunctuation() {
    assertEquals(PhoneCommand.YouTubeSearch("rebord"), local("buscar rebord en YouTube"))
    assertEquals(PhoneCommand.SpotifySearch("rebord"), local("buscar rebord en spotify"))
    assertEquals(PhoneCommand.SpotifySearch("Björk & Jazz"), local("buscá en Spotify Björk & Jazz"))
    assertEquals(PhoneCommand.YouTubeSearch("rebord"), local("Buscá rebord en YouTube."))
    assertEquals(PhoneCommand.SpotifySearch("rebord"), local("¿Podés buscar rebord en Spotify?"))
    assertEquals(PhoneCommand.SpotifySearch("rebord"), parser.parseReply("spotify", "rebord").let { (it as PhoneRoute.Local).command })
  }
  @Test fun decomposedAccentsArePreservedAtTheEndOfAPayload() {
    assertEquals(PhoneCommand.MapSearch("Cafe\u0301"), local("buscá en Maps Cafe\u0301"))
  }
  @Test fun callsAndCameraAreSpecificNativeRequests() {
    assertEquals(PhoneCommand.Dial("Ana María"), local("llamá a Ana María"))
    assertEquals(PhoneCommand.Dial("+54 (11) 5555-0100"), local("llamá al +54 (11) 5555-0100"))
    assertEquals(PhoneCommand.Camera(false), local("abrí la cámara"))
    assertEquals(PhoneCommand.Camera(true), local("abrí la cámara para grabar"))
  }
  @Test fun negativesAndExplanationsStillGoToInstinct() {
    for (s in listOf("no llames a Ana", "quiero que no me lleves a casa", "explicame cómo agendar una reunión", "qué canción es De Música Ligera")) {
      assertEquals(s, PhoneRoute.Instinct, parser.parse(s))
    }
    assertTrue(parser.parse("llevame a casa y llamá a Ana") is PhoneRoute.Unsupported)
  }
  @Test fun missingParametersClarifyInsteadOfOpeningUnrelatedApps() {
    for (s in listOf("llevame", "llamá", "buscá en YouTube", "poné música", "agendá una reunión")) {
      assertTrue(s, parser.parse(s) is PhoneRoute.Clarify)
    }
    assertEquals(PhoneRoute.Local(PhoneCommand.Navigate("Plaza de Mayo")), parser.parseReply("navigate", "Plaza de Mayo"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Dial("Ana")), parser.parseReply("dial", "Ana"))
    assertEquals(PhoneRoute.Instinct, parser.parseReply("navigate", "explicame qué es una dirección"))
  }
  @Test fun calendarUsesLocalDateAndRetainsTheWholeTitle() {
    val event = local("Agendá Reunión con María mañana a las 17:30 durante cuarenta y cinco minutos") as PhoneCommand.CalendarEvent
    assertEquals("Reunión con María", event.title)
    assertEquals(1794947400000L, event.beginMillis) // 2026-11-17T17:30-03:00
    assertEquals(2700000L, event.endMillis - event.beginMillis)
    val defaultDuration = local("agendá Clase de español el 20/11/2026 a las siete de la tarde") as PhoneCommand.CalendarEvent
    assertEquals(3600000L, defaultDuration.endMillis - defaultDuration.beginMillis)
    assertEquals("Clase de español", defaultDuration.title)
  }
  @Test fun calendarRejectsInvalidPastAmbiguousOrIgnoredDetails() {
    for (s in listOf("agendá cita el 31/02/2027 a las 17:00", "agendá cita hoy a las 09:00", "agendá cita mañana a las 25:00", "agendá cita mañana a las cinco", "agendá cita mañana a las 17:00 todos los lunes", "agendá cita mañana a las 17:00 durante cero minutos")) {
      assertFalse(s, parser.parse(s) is PhoneRoute.Local)
      assertNotEquals(s, PhoneRoute.Instinct, parser.parse(s))
    }
  }
  @Test fun calendarRejectsTheRepeatedHourDuringDaylightSaving() {
    val autumn = PhoneCommandParser { now("America/New_York").apply { set(2027, Calendar.NOVEMBER, 6, 10, 0) } }
    assertFalse(autumn.parse("agendá cita mañana a las 01:30") is PhoneRoute.Local)
  }
  @Test fun calendarRejectsNonexistentLocalTimeDuringDaylightSaving() {
    val spring = PhoneCommandParser { now("America/New_York").apply { set(2027, Calendar.MARCH, 13, 10, 0) } }
    assertFalse(spring.parse("agendá cita mañana a las 02:30") is PhoneRoute.Local)
  }
}
