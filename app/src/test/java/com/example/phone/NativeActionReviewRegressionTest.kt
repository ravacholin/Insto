package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class NativeActionReviewRegressionTest {
  private val parser = PhoneCommandParser {
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, 16, 10, 0) }
  }
  @Test fun compoundCommandsCannotBecomeSavedAddressesOrDestinations() {
    for (s in listOf("guardá mi casa en Av. Córdoba 1234 y navegá a casa", "llevame a casa y guardá mi trabajo en Retiro", "llevame a Retiro y reproducí música de jazz", "guardá mi casa en Mitre 42 y llamá a Ana", "mi casa es Mitre 42 y navegá a casa", "guardá mi casa en Mitre 42; llamá a Ana", "llevame a casa y después abrí la cámara")) {
      assertTrue(s, parser.parse(s) is PhoneRoute.Unsupported)
    }
  }
  @Test fun travelModesAreNotPartOfTheDestinationAndUnsupportedRestrictionsStayLocal() {
    val bike = (parser.parse("llevame a Retiro en bicicleta") as PhoneRoute.Local).command as PhoneCommand.Navigate
    assertEquals("Retiro", bike.destination)
    assertEquals("BICYCLING", bike.mode.name)
    val transit = (parser.parse("llevame en colectivo a Retiro") as PhoneRoute.Local).command as PhoneCommand.Navigate
    assertEquals("Retiro", transit.destination)
    assertEquals("TRANSIT", transit.mode.name)
    assertTrue(parser.parse("llevame a Retiro sin peajes") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("llevame a casa pasando por la escuela") is PhoneRoute.Unsupported)
  }
  @Test fun deduplicationCannotDiscardAnExactNamesOtherNumber() {
    assertEquals(ContactResolution.Choose(listOf(ContactOption("Ana", "123456"), ContactOption("Ana", "654321"))),
      ContactSelection.resolve("Ana", listOf(ContactOption("A Ana", "123456"), ContactOption("Ana", "123456"), ContactOption("Ana", "654321"))))
  }
  @Test fun calendarCannotTreatUnsupportedRecurrenceAsAnIgnoredDurationLabel() {
    for (s in listOf("agendá reunión mañana a las 17:00 durante una hora para todos los lunes", "agendá reunión mañana a las 17:00 durante una hora llamado prueba")) {
      assertTrue(s, parser.parse(s) is PhoneRoute.Unsupported)
    }
  }
  @Test fun negationsAndConditionsCannotBecomeClarificationPayloads() {
    for (context in listOf("navigate", "dial", "music", "youtube", "maps")) {
      assertEquals(PhoneRoute.Cancel, parser.parseReply(context, "no"))
      assertEquals(PhoneRoute.Cancel, parser.parseReply(context, "nunca"))
      assertFalse(parser.parseReply(context, "si llueve") is PhoneRoute.Local)
      assertEquals(PhoneRoute.Instinct, parser.parseReply(context, "explicame qué es eso"))
    }
  }
}
