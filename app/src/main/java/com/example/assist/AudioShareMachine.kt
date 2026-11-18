package com.example.assist

internal class AudioShareMachine(
  private val request: Long,
  private val file: String?,
  private val caption: String?,
  private val phone: String?,
  private val name: String?,
) {
  data class Surface(val window: Int, val key: String)
  data class StableChat(val window: Int, val since: Long)
  data class State(
    val confirming: Surface? = null,
    val sending: Surface? = null,
    val inFlight: SendStep? = null,
    val chat: StableChat? = null,
    val verifyingUntil: Long? = null,
    val finished: Boolean = false,
  )

  val generated = file?.startsWith("instinct_") == true && file.endsWith(".m4a")

  fun reduce(state: State, event: WhatsAppSendEvent): SendTransition<State> {
    if (state.finished) return SendTransition(state, WhatsAppSendAction.None)
    if (event is WhatsAppSendEvent.Gesture) return onGesture(state, event)
    if (state.inFlight != null) return wait(state)
    if (state.verifyingUntil != null) {
      return when {
        event is WhatsAppSendEvent.Screen && phone != null && event.reader.isEmptyChat(phone, name, requireMic = false) ->
          finish(state, WhatsAppSendAction.Confirm)
        event.at >= state.verifyingUntil -> finish(state, WhatsAppSendAction.Abort)
        else -> wait(state)
      }
    }
    if (event is WhatsAppSendEvent.Elapsed) {
      return wait(state.copy(chat = null), "No se pudo acceder a la vista de audio de WhatsApp.")
    }
    event as WhatsAppSendEvent.Screen
    if (file != null && event.launchRequest != request) return wait(state)
    return onScreen(state, event)
  }

  private fun onScreen(state: State, screen: WhatsAppSendEvent.Screen): SendTransition<State> {
    val reader = screen.reader
    if (generated && (state.confirming != null || state.sending != null) && phone != null &&
      screen.window >= 0 && reader.isEmptyChat(phone, name, requireMic = true)
    ) {
      val chat = state.chat
      return if (chat != null && chat.window == screen.window && screen.at >= chat.since) {
        if (screen.at - chat.since >= 500) finish(state, WhatsAppSendAction.Confirm) else wait(state)
      } else wait(state.copy(chat = StableChat(screen.window, screen.at)))
    }

    val scanning = state.copy(chat = null)
    if (generated) {
      val evidence = reader.audioPreview()
      positiveStep(scanning, screen, evidence)?.let { return it }
      if (ShareConfirmationPolicy.hasUnrelatedShareHeading(evidence.labels)) return wait(scanning)
    }
    if (phone != null && !reader.recipient().matches(phone, name, file != null)) {
      return wait(scanning, "El nombre o número del destinatario del audio no coincide con Instinct.")
    }
    val composer = reader.entryText()
    val exact = AutoSendGuard.payloadMatches(caption, file, composer, reader.attachmentLabels(file))
    val preview = AutoSendGuard.ownedAudioPreviewMatches(request, screen.launchRequest,
      screen.audioPreviewRequest, screen.audioPreviewWindow, screen.window, file, composer, caption)
    if (!exact && !preview) {
      return wait(scanning, "No se pudo verificar esta grabación en la vista de WhatsApp.")
    }
    val send = reader.sendControl() ?: return wait(scanning, "No se encontró el botón Enviar del audio.")
    val bounds = send.bounds.touchArea(screen.display) ?: return wait(scanning)
    return send(scanning, Surface(screen.window, send.surface), bounds)
  }

  private fun positiveStep(
    state: State,
    screen: WhatsAppSendEvent.Screen,
    evidence: WhatsAppScreenReader.AudioPreview,
  ): SendTransition<State>? {
    val reader = screen.reader
    val positive = reader.sharePositiveControl() ?: return null
    val surface = Surface(screen.window, positive.surface)
    val afterConfirmation = state.confirming != null && state.confirming != surface
    val afterSend = state.sending != null && state.sending != surface
    val allowed = ShareConfirmationPolicy.canConfirm(request, screen.launchRequest,
      screen.freshShareWindow || evidence.labels.any { it == file } || afterConfirmation || afterSend,
      file, caption, reader.composer("")?.text, name, phone.orEmpty(), evidence.labels, positive.label,
      evidence.hasAudioControls, recipientPreviouslyVerified = afterConfirmation || afterSend)
    if (!allowed) return null
    val bounds = positive.bounds.touchArea(screen.display) ?: return wait(state)
    if (state.confirming != null && !afterConfirmation) return wait(state)
    if (!ShareConfirmationPolicy.isConfirmationLabel(positive.label)) return send(state, surface, bounds)
    if (state.confirming != null || screen.window < 0) return wait(state)
    return SendTransition(
      state.copy(confirming = surface, inFlight = SendStep.SHARE_CONFIRMATION),
      WhatsAppSendAction.Touch(bounds, SendStep.SHARE_CONFIRMATION, disarm = false),
    )
  }

  private fun send(state: State, surface: Surface, bounds: UiBounds): SendTransition<State> {
    if (state.sending != null || state.confirming == surface) return wait(state)
    return SendTransition(
      state.copy(sending = surface, inFlight = SendStep.SHARE_SEND),
      WhatsAppSendAction.Touch(bounds, SendStep.SHARE_SEND, disarm = !generated),
    )
  }

  private fun onGesture(state: State, event: WhatsAppSendEvent.Gesture): SendTransition<State> {
    if (state.inFlight != event.step) return SendTransition(state, WhatsAppSendAction.None)
    if (!event.completed) return finish(state, WhatsAppSendAction.Abort)
    val completed = state.copy(inFlight = null)
    if (generated) return SendTransition(completed, WhatsAppSendAction.None)
    if (phone == null) return finish(completed, WhatsAppSendAction.Confirm)
    return SendTransition(completed.copy(verifyingUntil = event.at + CONFIRM_TIMEOUT_MS),
      WhatsAppSendAction.Wait(delayMs = 250))
  }

  private fun wait(state: State, reason: String? = null) = SendTransition(state, WhatsAppSendAction.Wait(reason))

  private fun finish(state: State, action: WhatsAppSendAction) = SendTransition(state.copy(finished = true), action)
}
