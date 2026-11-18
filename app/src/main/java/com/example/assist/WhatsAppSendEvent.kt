package com.example.assist

internal sealed interface WhatsAppSendEvent {
  val at: Long

  data class Screen(
    override val at: Long,
    val window: Int,
    val reader: WhatsAppScreenReader,
    val display: UiBounds,
    val launchRequest: Long,
    val freshShareWindow: Boolean = false,
    val audioPreviewRequest: Long = -1,
    val audioPreviewWindow: Int = -1,
  ) : WhatsAppSendEvent

  data class Gesture(override val at: Long, val step: SendStep, val completed: Boolean) : WhatsAppSendEvent
  data class Elapsed(override val at: Long) : WhatsAppSendEvent
}

internal enum class SendStep { TEXT, SHARE_CONFIRMATION, SHARE_SEND }

internal sealed interface WhatsAppSendAction {
  data class Wait(val reason: String? = null, val delayMs: Long = 180) : WhatsAppSendAction
  data class Touch(val bounds: UiBounds, val step: SendStep, val disarm: Boolean) : WhatsAppSendAction
  data object Confirm : WhatsAppSendAction
  data object Abort : WhatsAppSendAction
  data object None : WhatsAppSendAction
}

internal data class SendTransition<S>(val state: S, val action: WhatsAppSendAction)

internal const val CONFIRM_TIMEOUT_MS = 3_000L
