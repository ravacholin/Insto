package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class EverydayRoutingSmokeTest {
  @Test fun navigationAndSearchRequestsAreHandledOnThePhone() {
    for (text in listOf("Llevame a Plaza de Mayo", "Buscá farmacias cerca", "Buscá jazz en YouTube")) {
      assertTrue(text, PhoneCommandParser().parse(text) is PhoneRoute.Local)
    }
  }
  @Test fun callsCalendarAndMusicAreNotSentToInstinct() {
    for (text in listOf("Llamá a Ana", "Agendá reunión mañana a las 17:00", "Poné música de Soda Stereo")) {
      assertTrue(text, PhoneCommandParser().parse(text) is PhoneRoute.Local)
    }
  }
}
