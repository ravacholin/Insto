package com.example.assist

/** One confirmation and one optional preview send per successfully launched audio share. */
class AudioSendAttempt(
  val requestId: Long,
  private val recipientName: String?,
  private val recipientPhone: String,
) {
  var confirmationTouched = false
    private set
  var sendTouched = false
    private set
  private var previewObserved = false

  fun observePreview() { previewObserved = true }

  fun canConfirmSubmission(): Boolean = sendTouched || (confirmationTouched && !previewObserved)

  fun beginConfirmation(request: Long, visibleLabels: List<String>, buttonReady: Boolean): Boolean {
    if (request != requestId || confirmationTouched || sendTouched || !buttonReady) return false
    val matches = visibleLabels.any { label ->
      val clean = label.filterNot {
        it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
      }.trim()
      val recipient = Regex("(?:Share with|¿?Compartir con) (.+)\\?", RegexOption.IGNORE_CASE)
        .matchEntire(clean)?.groupValues?.get(1) ?: return@any false
      AutoSendGuard.recipientMatches(recipientPhone, listOf(recipient), recipientName, openedConversation = true)
    }
    if (!matches) return false
    confirmationTouched = true
    return true
  }

  fun beginSend(request: Long, recipientMatches: Boolean, payloadMatches: Boolean, buttonReady: Boolean): Boolean {
    if (request != requestId || sendTouched || !recipientMatches || !payloadMatches || !buttonReady) return false
    sendTouched = true
    return true
  }
}
