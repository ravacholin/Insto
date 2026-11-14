package com.example.whatsapp

object PhoneNumberUtils {

  /**
   * Extracts digits suitable for WhatsApp deep links and JIDs. Strips formatting characters and
   * leading international "00" prefix.
   */
  fun normalizeDigits(rawPhone: String): String {
    val trimmed = rawPhone.trim()
    val digitsOnly = trimmed.filter { it.isDigit() }
    return if (trimmed.startsWith("00") && digitsOnly.length > 2) {
      digitsOnly.drop(2)
    } else {
      digitsOnly
    }
  }

  /** Converts a raw phone number into WhatsApp's JID format (`<digits>@s.whatsapp.net`). */
  fun toWhatsAppJid(rawPhone: String): String? {
    val digits = normalizeDigits(rawPhone)
    return if (digits.length >= 6) "$digits@s.whatsapp.net" else null
  }

  /** Direct deep link URI that opens WhatsApp chat immediately without browser redirect. */
  fun toWhatsAppDirectSchemeUrl(rawPhone: String): String? {
    val digits = normalizeDigits(rawPhone)
    return if (digits.length >= 6) "whatsapp://send?phone=$digits" else null
  }

  /** Fallback web URL if whatsapp:// scheme is unavailable. */
  fun toWhatsAppChatUrl(rawPhone: String): String? {
    val digits = normalizeDigits(rawPhone)
    return if (digits.length >= 6) "https://wa.me/$digits" else null
  }

  fun isValidPhoneNumber(rawPhone: String): Boolean {
    val digits = normalizeDigits(rawPhone)
    return digits.length in 6..15
  }

  fun formatForDisplay(rawPhone: String): String {
    val trimmed = rawPhone.trim()
    if (trimmed.startsWith("+")) return trimmed
    val digits = normalizeDigits(trimmed)
    return if (digits.isNotEmpty()) "+$digits" else trimmed
  }
}
