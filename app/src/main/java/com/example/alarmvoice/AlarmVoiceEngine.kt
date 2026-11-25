package com.example.alarmvoice

import android.content.Context
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import kotlin.concurrent.thread

/**
 * Holds Vosk's model while an alarm rings, so the screen does not wait for it twice. Loaded on a background thread
 * when an alarm is detected, released when listening ends. Everything runs on the phone.
 */
object AlarmVoiceEngine {
  private val lock = Object()
  private var model: Model? = null
  private var loading = false

  fun preload(context: Context) {
    val dir = AlarmVoiceModel(context).takeIf { it.isInstalled() }?.directory ?: return
    synchronized(lock) { if (model != null || loading) return; loading = true }
    thread(name = "alarm-voice-model", isDaemon = true) {
      val loaded = runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS); Model(dir.absolutePath) }.getOrNull()
      synchronized(lock) { if (model == null) model = loaded else loaded?.close(); loading = false; lock.notifyAll() }
    }
  }

  /** The model, loading it here if needed; null when it is not installed or cannot be read. Blocking. */
  fun obtain(context: Context): Model? {
    preload(context)
    synchronized(lock) {
      while (loading) lock.wait(200)
      if (model == null) {
        val dir = AlarmVoiceModel(context).takeIf { it.isInstalled() }?.directory ?: return null
        model = runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS); Model(dir.absolutePath) }.getOrNull()
      }
      return model
    }
  }

  fun release() {
    synchronized(lock) { if (loading) return; runCatching { model?.close() }; model = null }
  }

  /** Words and confidences of a Vosk final result (with setWords(true)); falls back to its «text». */
  fun words(json: String?): List<Pair<String, Double?>> = runCatching {
    val obj = JSONObject(json ?: return emptyList())
    val result = obj.optJSONArray("result")
    if (result != null && result.length() > 0) List(result.length()) { i ->
      val w = result.getJSONObject(i)
      w.optString("word") to (if (w.has("conf")) w.optDouble("conf") else null)
    } else obj.optString("text").split(' ').filter { it.isNotBlank() }.map { it to null }
  }.getOrDefault(emptyList())

  fun partial(json: String?): String? = runCatching { JSONObject(json ?: return null).optString("partial") }.getOrNull()
}
