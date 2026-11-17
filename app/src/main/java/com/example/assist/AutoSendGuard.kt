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

  fun launchMatches(request: Long, text: String?, fileName: String?, textLaunch: Long, shareLaunch: Long): Boolean =
    when {
      text != null -> textLaunch == request
      fileName != null -> shareLaunch == request
      else -> false
    }

  /** Some WhatsApp audio previews hide filenames. Trust only our current targeted share's new AudioPreview window. */
  fun ownedAudioPreviewMatches(request: Long, shareLaunch: Long, previewRequest: Long,
    expectedWindow: Int, actualWindow: Int, fileName: String?, composer: String?, caption: String?): Boolean =
    request == shareLaunch && request == previewRequest && expectedWindow >= 0 && expectedWindow == actualWindow &&
      fileName?.startsWith("instinct_") == true && fileName.endsWith(".m4a") &&
      composer.isNullOrBlank() && caption == null

  fun freshShareWindowMatches(request: Long, shareLaunch: Long, launchStartedAt: Long, eventAt: Long,
    windowId: Int, prelaunchWindows: Set<Int>?): Boolean =
    request == shareLaunch && launchStartedAt >= 0 && eventAt >= launchStartedAt && windowId >= 0 &&
      prelaunchWindows != null && windowId !in prelaunchWindows

  fun freshAudioPreviewMatches(request: Long, shareLaunch: Long, launchStartedAt: Long, eventAt: Long,
    windowId: Int, prelaunchWindows: Set<Int>?, className: String?): Boolean =
    freshShareWindowMatches(request, shareLaunch, launchStartedAt, eventAt, windowId, prelaunchWindows) &&
      className?.endsWith(".AudioPreviewActivity") == true

  private fun cleanHeader(value: String): String = value
    .filterNot { it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    .trim()

  fun payloadMatches(text: String?, audioFileName: String?, composerText: String?, attachmentLabels: List<String>): Boolean =
    when {
      audioFileName != null -> attachmentLabels.any { it == audioFileName } &&
        (if (text != null) composerText == text else composerText.isNullOrBlank())
      text != null -> composerText == text
      else -> true // Older explicit, non-floating entrypoints.
    }
}
