package com.example.assist

internal class TextSendMachine(
  private val request: Long,
  private val text: String,
  private val phone: String,
  private val name: String?,
) {
  sealed interface State {
    data object Waiting : State
    data object Touching : State
    data class Verifying(val deadline: Long) : State
    data object Finished : State
  }

  fun reduce(state: State, event: WhatsAppSendEvent): SendTransition<State> = when (state) {
    State.Waiting -> when (event) {
      is WhatsAppSendEvent.Screen -> findTouch(event)
      is WhatsAppSendEvent.Elapsed -> wait(state, "No se pudo acceder al chat visible de WhatsApp.")
      is WhatsAppSendEvent.Gesture -> SendTransition(state, WhatsAppSendAction.None)
    }
    State.Touching -> when {
      event !is WhatsAppSendEvent.Gesture || event.step != SendStep.TEXT -> SendTransition(state, WhatsAppSendAction.None)
      event.completed -> SendTransition(State.Verifying(event.at + CONFIRM_TIMEOUT_MS), WhatsAppSendAction.Wait(delayMs = 250))
      else -> SendTransition(State.Finished, WhatsAppSendAction.Abort)
    }
    is State.Verifying -> when {
      event is WhatsAppSendEvent.Gesture -> SendTransition(state, WhatsAppSendAction.None)
      event is WhatsAppSendEvent.Screen && event.reader.recipient().matches(phone, name, true) &&
        event.reader.composer(text)?.let { it.text.isNullOrEmpty() } == true ->
        SendTransition(State.Finished, WhatsAppSendAction.Confirm)
      event.at >= state.deadline -> SendTransition(State.Finished, WhatsAppSendAction.Abort)
      else -> wait(state)
    }
    State.Finished -> SendTransition(state, WhatsAppSendAction.None)
  }

  private fun findTouch(screen: WhatsAppSendEvent.Screen): SendTransition<State> {
    if (screen.launchRequest != request) return wait(State.Waiting)
    if (!screen.reader.recipient().matches(phone, name, true)) {
      return wait(State.Waiting, "El nombre o número del chat no coincide con el contacto configurado.")
    }
    if (screen.reader.composer(text)?.text != text) {
      return wait(State.Waiting, "No se pudo verificar el mensaje en el cuadro de WhatsApp.")
    }
    val control = screen.reader.sendControl()
      ?: return wait(State.Waiting, "No se encontró el botón Enviar de WhatsApp.")
    val bounds = control.bounds.touchArea(screen.display) ?: return wait(State.Waiting)
    return SendTransition(State.Touching, WhatsAppSendAction.Touch(bounds, SendStep.TEXT, disarm = true))
  }

  private fun wait(state: State, reason: String? = null) = SendTransition(state, WhatsAppSendAction.Wait(reason))
}
