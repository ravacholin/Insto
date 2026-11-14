package com.example.assist

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.R
import com.example.data.PinnedContact

object ShortcutHelper {

  private const val SHORTCUT_OPEN_CHAT = "shortcut_open_whatsapp_chat"
  private const val SHORTCUT_RECORD_VOICE = "shortcut_record_whatsapp_voice"

  fun syncShortcuts(context: Context, contact: PinnedContact?) {
    if (contact == null) {
      ShortcutManagerCompat.removeDynamicShortcuts(
        context,
        listOf(SHORTCUT_OPEN_CHAT, SHORTCUT_RECORD_VOICE),
      )
      return
    }

    val openChatIntent =
      Intent(context, AssistActivity::class.java).apply {
        action = AssistActivity.ACTION_OPEN_CHAT
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }

    val recordVoiceIntent =
      Intent(context, AssistActivity::class.java).apply {
        action = AssistActivity.ACTION_RECORD_AUDIO
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }

    val openShortcut =
      ShortcutInfoCompat.Builder(context, SHORTCUT_OPEN_CHAT)
        .setShortLabel("Chat ${contact.displayName}".take(18))
        .setLongLabel("Open ${contact.displayName} on WhatsApp")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_chat_bubble))
        .setIntent(openChatIntent)
        .build()

    val voiceShortcut =
      ShortcutInfoCompat.Builder(context, SHORTCUT_RECORD_VOICE)
        .setShortLabel("Voice ${contact.displayName}".take(18))
        .setLongLabel("Record & send audio to ${contact.displayName}")
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_mic_pulse))
        .setIntent(recordVoiceIntent)
        .build()

    ShortcutManagerCompat.setDynamicShortcuts(context, listOf(openShortcut, voiceShortcut))
  }
}
