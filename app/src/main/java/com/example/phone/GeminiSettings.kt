package com.example.phone

import android.content.Context
import com.example.speech.GroqKeyStore
import java.io.File

/** Personal credentials and activation never enter the APK, preferences backup, or logs. */
class GeminiSettings(context: Context) {
  private val keys = GroqKeyStore(context, "gemini")
  private val localChoice = File(context.noBackupFilesDir, "gemini-local-choice")
  private val enabled = File(context.noBackupFilesDir, "gemini-enabled")
  fun readKey(): String? = keys.read()
  fun shouldPromptSetup(): Boolean = GeminiSetupPolicy.shouldPrompt(readKey() != null, localChoice.exists())
  fun continueLocally() { localChoice.writeText("true") }
  fun isEnabled(): Boolean = runCatching { enabled.readText() == "true" }.getOrDefault(false) && readKey() != null
  fun saveKey(value: String) { keys.save(value); setEnabled(true) }
  fun setEnabled(value: Boolean) { enabled.writeText(value.toString()) }
  fun clear() { keys.clear(); if (enabled.exists()) check(enabled.delete()) }
  /** The stored switch, independent of whether a key exists. For backup and restore only. */
  fun enabledFlag(): Boolean = runCatching { enabled.readText() == "true" }.getOrDefault(false)
  fun hasLocalChoice(): Boolean = localChoice.exists()
  fun setLocalChoice(value: Boolean) { if (value) continueLocally() else if (localChoice.exists()) check(localChoice.delete()) }
  /** Saves a restored key keeping the restored switch, unlike [saveKey], which turns Decisions on. */
  fun restoreKey(value: String) { keys.save(value) }
}
