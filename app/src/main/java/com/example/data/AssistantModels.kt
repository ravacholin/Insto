package com.example.data

enum class AssistantActivationMode(val title: String, val subtitle: String) {
  TAP_CHAT_HOLD_AUDIO(
    title = "Tap = Chat · Hold = Voice Note",
    subtitle = "Press the hotkey once to open the chat; keep it held to record and send on release",
  ),
  DYNAMIC_VOICE_OR_CHAT(
    title = "Dynamic Smart Window (Auto)",
    subtitle = "Silent launch opens chat directly (~2s); speaking or holding records & sends audio automatically",
  ),
  INSTANT_OPEN_CHAT(
    title = "Always Open Chat Directly",
    subtitle = "System assist jumps straight into your WhatsApp contact with no delay",
  ),
  INSTANT_RECORD_AUDIO(
    title = "Always Record Audio Immediately",
    subtitle = "System assist starts recording right away and sends straight to your contact",
  ),
}

enum class OrbHoldBehavior(val title: String, val subtitle: String) {
  PROGRESSIVE_CHAT_THEN_AUDIO(
    title = "Progressive (0–2.5s Chat → 2.5s+ Audio)",
    subtitle = "Tap or hold up to 2.5s to open chat; keep holding longer to record & auto-send audio",
  ),
  INSTANT_HOLD_TO_RECORD(
    title = "Instant (Tap Chat · Hold 0.3s Audio)",
    subtitle = "Quick tap opens chat; holding immediately records audio and sends on release",
  ),
}

enum class WhatsAppFlavor(val label: String, val packageName: String?) {
  AUTO("Auto Detect", null),
  WHATSAPP("WhatsApp", "com.whatsapp"),
  WHATSAPP_BUSINESS("WhatsApp Business", "com.whatsapp.w4b"),
}

enum class VoiceRecordingMode(val label: String, val description: String) {
  NATIVE_WHATSAPP_PTT(
    label = "Native WhatsApp PTT",
    description = "Opens chat and holds WhatsApp's native voice note mic via Accessibility (falls back to audio file if unavailable)",
  ),
  AUDIO_FILE_AUTO_SEND(
    label = "Audio File + Auto-Send",
    description = "Records locally with waveform visualizer and dispatches straight to the contact's JID",
  ),
}

enum class VolumeKeyTrigger(val label: String) {
  DISABLED("Off"),
  VOLUME_DOWN_HOLD("Hold Vol -"),
  VOLUME_UP_HOLD("Hold Vol +"),
}

enum class FloatingPillMode(val label: String) {
  OFF("Off"),
  CAR_OR_BLUETOOTH_ONLY("Car / BT Only"),
  ALWAYS_ON("Always On"),
}

data class ContextRuleConfig(
  val switchToVoiceInCarOrBluetooth: Boolean = true,
  val drivingContact: PinnedContact? = null,
  val nightWindowEnabled: Boolean = false,
  val nightStartHour: Int = 21,
  val nightEndHour: Int = 7,
)

enum class PingActionType {
  OPEN_CHAT,
  AUDIO_SENT,
  NATIVE_PTT,
}

data class PinnedContact(
  val displayName: String,
  val phoneNumber: String,
) {
  val initials: String
    get() {
      val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
      }
    }
}

data class PingActionLog(
  val id: String,
  val type: PingActionType,
  val contactName: String,
  val timestampMillis: Long,
  val durationSeconds: Int? = null,
  val statusLabel: String,
)

data class AssistantPreferences(
  val pinnedContact: PinnedContact? = null,
  val activationMode: AssistantActivationMode = AssistantActivationMode.TAP_CHAT_HOLD_AUDIO,
  val orbHoldBehavior: OrbHoldBehavior = OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO,
  val preferredFlavor: WhatsAppFlavor = WhatsAppFlavor.AUTO,
  val voiceRecordingMode: VoiceRecordingMode = VoiceRecordingMode.AUDIO_FILE_AUTO_SEND,
  val volumeKeyTrigger: VolumeKeyTrigger = VolumeKeyTrigger.VOLUME_DOWN_HOLD,
  val floatingPillMode: FloatingPillMode = FloatingPillMode.OFF,
  val eyesFreeAudioCues: Boolean = true,
  val boomerangReturnEnabled: Boolean = false,
  val hasPromptedMicPermission: Boolean = false,
  val contextRule: ContextRuleConfig = ContextRuleConfig(),
  val recentLogs: List<PingActionLog> = emptyList(),
)
