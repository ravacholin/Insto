package com.example.phone

import android.content.Context
import com.example.speech.GroqKeyStore
import java.io.File

/** Personal credentials and activation never enter the APK, preferences backup, or logs. */
class GeminiSettings(context: Context) {
  private val keys = GroqKeyStore(context, "gemini")
  private val enabled = File(context.noBackupFilesDir, "gemini-enabled")
  fun readKey(): String? = keys.read()
  fun isEnabled(): Boolean = runCatching { enabled.readText() == "true" }.getOrDefault(false) && readKey() != null
  fun saveKey(value: String) { keys.save(value); setEnabled(true) }
  fun setEnabled(value: Boolean) { enabled.writeText(value.toString()) }
  fun clear() { keys.clear(); if (enabled.exists()) check(enabled.delete()) }
}
