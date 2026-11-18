package com.example.ui

import android.app.Application
import com.example.assist.PingAutoSendAccessibilityService
import com.example.audio.RecordedAudio
import com.example.data.AssistantPreferences
import com.example.data.PinnedContact
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher

internal class InstinctDispatcher(private val application: Application) {
  fun hasTarget(prefs: AssistantPreferences): Boolean =
    WhatsAppDispatcher.resolveTargetPackage(application, prefs.preferredFlavor) != null

  fun audio(
    audio: RecordedAudio,
    contact: PinnedContact,
    prefs: AssistantPreferences,
    returnPackage: String?,
  ): DispatchResult = WhatsAppDispatcher.sendWhatsAppAudio(
    application, contact.phoneNumber, audio.file, prefs.preferredFlavor,
    PingAutoSendAccessibilityService.isEnabled(application), boomerangReturn = true,
    mimeType = audio.mimeType, recipientName = contact.displayName, returnPackage = returnPackage,
  )

  fun text(text: String, contact: PinnedContact, prefs: AssistantPreferences, returnPackage: String?): DispatchResult =
    WhatsAppDispatcher.sendWhatsAppText(
      application, contact.phoneNumber, text, prefs.preferredFlavor,
      PingAutoSendAccessibilityService.isEnabled(application), contact.displayName, returnPackage,
    )

  fun attachment(
    attachment: FloatingAttachment,
    text: String,
    contact: PinnedContact,
    prefs: AssistantPreferences,
    returnPackage: String?,
  ): DispatchResult = WhatsAppDispatcher.sendWhatsAppAttachment(
    application, contact.phoneNumber, attachment.uri, attachment.name, attachment.mimeType, text,
    prefs.preferredFlavor, contact.displayName, returnPackage,
  )

  fun open(text: String, contact: PinnedContact, prefs: AssistantPreferences): DispatchResult = when {
    text.isBlank() -> WhatsAppDispatcher.openWhatsAppChat(application, contact.phoneNumber, prefs.preferredFlavor)
    PingAutoSendAccessibilityService.isEnabled(application) && PingAutoSendAccessibilityService.isConnected() ->
      WhatsAppDispatcher.sendWhatsAppText(application, contact.phoneNumber, text, prefs.preferredFlavor,
        isAutoSendServiceEnabled = true, recipientName = contact.displayName)
    else -> WhatsAppDispatcher.openWhatsAppTextDraft(application, contact.phoneNumber, text, prefs.preferredFlavor)
  }
}
