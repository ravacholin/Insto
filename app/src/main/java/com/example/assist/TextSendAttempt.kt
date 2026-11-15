package com.example.assist

/** Created by our explicit successful chat launch, never by an incidental WhatsApp UI event. */
class TextSendAttempt(val requestId: Long) {
  private var touched = false

  fun beginTouch(request: Long, recipientMatches: Boolean, textMatches: Boolean, buttonReady: Boolean): Boolean {
    if (request != requestId || touched || !recipientMatches || !textMatches || !buttonReady) return false
    touched = true
    return true
  }
}
