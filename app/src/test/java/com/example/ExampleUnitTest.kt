package com.example

import com.example.context.ContextRoutingEngine
import com.example.whatsapp.PhoneNumberUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun `phone number normalization strips punctuation and 00 prefix`() {
    assertEquals("15550192834", PhoneNumberUtils.normalizeDigits("+1 (555) 019-2834"))
    assertEquals("393401234567", PhoneNumberUtils.normalizeDigits("0039 340 123 4567"))
  }

  @Test
  fun `whatsapp jid and direct scheme url validation`() {
    assertEquals("15550192834@s.whatsapp.net", PhoneNumberUtils.toWhatsAppJid("+1 555 019 2834"))
    assertEquals(
      "whatsapp://send?phone=15550192834",
      PhoneNumberUtils.toWhatsAppDirectSchemeUrl("+1 555 019 2834"),
    )
    assertEquals(
      "https://wa.me/15550192834",
      PhoneNumberUtils.toWhatsAppChatUrl("+1 555 019 2834"),
    )
    assertTrue(PhoneNumberUtils.isValidPhoneNumber("+15550192834"))
    assertFalse(PhoneNumberUtils.isValidPhoneNumber("123"))
    assertNull(PhoneNumberUtils.toWhatsAppJid("123"))
  }

  @Test
  fun `hour window handles overnight and daytime spans`() {
    assertTrue(ContextRoutingEngine.isWithinHourWindow(hour = 22, startHour = 21, endHour = 7))
    assertTrue(ContextRoutingEngine.isWithinHourWindow(hour = 3, startHour = 21, endHour = 7))
    assertFalse(ContextRoutingEngine.isWithinHourWindow(hour = 12, startHour = 21, endHour = 7))
    assertTrue(ContextRoutingEngine.isWithinHourWindow(hour = 10, startHour = 9, endHour = 17))
    assertFalse(ContextRoutingEngine.isWithinHourWindow(hour = 17, startHour = 9, endHour = 17))
  }
}
