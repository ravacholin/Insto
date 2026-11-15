package com.example.assist

import com.example.whatsapp.PhoneNumberUtils

/** Fail closed when WhatsApp does not expose enough evidence for this exact dispatch. */
object AutoSendGuard {
  fun recipientMatches(
    phone: String,
    recipientHeaders: List<String>,
    expectedName: String? = null,
    openedConversation: Boolean = false,
  ): Boolean {
    val digits = PhoneNumberUtils.normalizeDigits(phone)
    if (digits.length !in 6..15) return false
    val name = expectedName?.let(::cleanHeader).orEmpty()
    return recipientHeaders.any { raw ->
      val header = cleanHeader(raw)
      val matchesPhone = header.any { it.isDigit() } &&
        header.all { it.isDigit() || it in "+- ()." } &&
        PhoneNumberUtils.normalizeDigits(header) == digits
      // A saved name is accepted only in the conversation opened for this dispatch.
      // The caller also checks the complete composer text before clicking Send.
      matchesPhone || (openedConversation && name.any { it.isLetter() } &&
        header.equals(name, ignoreCase = true))
    }
  }

  private fun cleanHeader(value: String): String = value
    .filterNot { it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    .trim()

  fun payloadMatches(text: String?, audioFileName: String?, composerText: String?, attachmentLabels: List<String>): Boolean =
    when {
      text != null -> composerText == text
      audioFileName != null -> composerText.isNullOrBlank() && attachmentLabels.any { it == audioFileName }
      else -> true // Older explicit, non-floating entrypoints.
    }
}
