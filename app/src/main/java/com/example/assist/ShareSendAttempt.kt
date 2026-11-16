package com.example.assist

/** Send and OK/Accept may appear in either order. Each control is touched at most once. */
class ShareSendAttempt(val requestId: Long) {
  private var confirmationWindow: Int? = null
  private var confirmationSurface: String = ""
  private var confirmed = false
  private var sent = false
  private var sendFinished = false
  private var sendWindow = -1
  private var sendSurface = ""
  private var chatWindow = -1
  private var chatSince = -1L
  val hasConfirmed: Boolean get() = confirmed
  val hasCompletedStep: Boolean get() = confirmed || sendFinished
  val confirmationInFlight: Boolean get() = confirmationWindow != null && !confirmed
  val stepInFlight: Boolean get() = confirmationInFlight || (sent && !sendFinished)

  fun beginConfirmation(request: Long, window: Int, matches: Boolean, surface: String = ""): Boolean {
    if (request != requestId || confirmationWindow != null || stepInFlight || !matches || window < 0) return false
    chatSince = -1
    confirmationWindow = window
    confirmationSurface = surface
    return true
  }
  fun confirmationCompleted(request: Long) { if (request == requestId && confirmationWindow != null) confirmed = true }
  fun sendCompleted(request: Long) { if (request == requestId && sent) sendFinished = true }
  fun isPostConfirmationWindow(window: Int): Boolean = confirmed && confirmationWindow != window
  fun isPostConfirmationSurface(window: Int, surface: String): Boolean = confirmed &&
    (confirmationWindow != window || (surface.isNotEmpty() && surface != confirmationSurface))
  fun isPostSendSurface(window: Int, surface: String): Boolean = sendFinished &&
    (sendWindow != window || (surface.isNotEmpty() && surface != sendSurface))

  fun observeSubmittedChat(window: Int, now: Long, eligible: Boolean): Boolean {
    if (!hasCompletedStep || stepInFlight || !eligible || window < 0) {
      chatWindow = -1
      chatSince = -1
      return false
    }
    if (chatWindow != window || chatSince < 0 || now < chatSince) {
      chatWindow = window
      chatSince = now
      return false
    }
    return now - chatSince >= 500
  }

  fun beginSend(request: Long, recipientMatches: Boolean, payloadMatches: Boolean, buttonReady: Boolean,
    window: Int = -1, surface: String = ""): Boolean {
    if (request != requestId || sent || stepInFlight || !recipientMatches || !payloadMatches || !buttonReady) return false
    chatSince = -1
    sent = true
    sendWindow = window
    sendSurface = surface
    return true
  }
}
