package com.example.alarmvoice

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * «Callar alarma con la voz» settings. Off by default. Kept in noBackupFilesDir like Insto's other switches, so a
 * restore on another phone never turns it on by itself. [isEnabled] is one small file, read on every posted
 * notification before anything of it is touched.
 */
class AlarmVoiceSettings(context: Context) {
  private val app = context.applicationContext
  private val enabledFile = File(app.noBackupFilesDir, "alarm-voice-enabled")
  private val propsFile = File(app.noBackupFilesDir, "alarm-voice.properties")

  fun isEnabled(): Boolean = runCatching { enabledFile.readText() == "true" }.getOrDefault(false)

  /** Turning it on needs the model; the screen checks it first, this refuses anyway. */
  fun setEnabled(value: Boolean): Boolean {
    if (value) {
      if (!AlarmVoiceModel(app).isInstalled()) return false
      enabledFile.writeText("true")
    } else {
      if (enabledFile.exists()) enabledFile.delete()
      AlarmVoiceCoordinator.cancelAll(app, "función desactivada")
    }
    return true
  }

  private fun props(): Properties = Properties().apply { runCatching { propsFile.inputStream().use { load(it) } } }
  private fun save(change: Properties.() -> Unit) {
    val p = props(); p.change()
    runCatching { propsFile.outputStream().use { p.store(it, null) } }
  }

  fun keywords(): List<String> = props().getProperty("keywords")?.let { KeywordMatcher.parse(it) }?.takeIf { it.isNotEmpty() }
    ?: KeywordMatcher.DEFAULT_KEYWORDS
  fun setKeywords(words: List<String>) = save { setProperty("keywords", words.joinToString(",")) }

  fun windowSeconds(): Int = props().getProperty("window")?.toIntOrNull()?.coerceIn(MIN_WINDOW, MAX_WINDOW) ?: DEFAULT_WINDOW
  fun setWindowSeconds(value: Int) = save { setProperty("window", value.coerceIn(MIN_WINDOW, MAX_WINDOW).toString()) }

  fun duckEnabled(): Boolean = props().getProperty("duck")?.toBooleanStrictOrNull() ?: true
  fun setDuckEnabled(value: Boolean) = save { setProperty("duck", value.toString()) }

  fun duckPercent(): Int = props().getProperty("duckPercent")?.toIntOrNull()?.coerceIn(10, 90) ?: DEFAULT_DUCK_PERCENT
  fun setDuckPercent(value: Int) = save { setProperty("duckPercent", value.coerceIn(10, 90).toString()) }

  /** Diagnostic mode: also records clock-looking packages that are not in [ClockPackages.KNOWN] yet. */
  fun diagnostics(): Boolean = props().getProperty("diagnostics")?.toBooleanStrictOrNull() ?: false
  fun setDiagnostics(value: Boolean) = save { setProperty("diagnostics", value.toString()) }

  companion object {
    const val DEFAULT_WINDOW = 60
    const val MIN_WINDOW = 10
    const val MAX_WINDOW = 180
    const val DEFAULT_DUCK_PERCENT = 40
    val WINDOW_CHOICES = listOf(15, 30, 60, 90, 120)
    val DUCK_CHOICES = listOf(20, 30, 40, 50, 60)
  }
}

/** The last clock notification seen, for the diagnostic panel. Only structural data; never the text. */
data class ClockDiagnostic(
  val at: Long,
  val packageName: String,
  val category: String?,
  val channelId: String?,
  val actionTitles: List<String>,
  val fullScreen: Boolean,
  val insistent: Boolean,
  val verdict: String,
)

object AlarmVoiceDiagnostics {
  private const val FILE = "alarm-voice-last-clock.properties"

  fun record(context: Context, d: ClockDiagnostic) {
    val p = Properties()
    p.setProperty("at", d.at.toString()); p.setProperty("package", d.packageName)
    p.setProperty("category", d.category ?: ""); p.setProperty("channel", d.channelId ?: "")
    p.setProperty("actions", d.actionTitles.joinToString("\u001F")); p.setProperty("fullScreen", d.fullScreen.toString())
    p.setProperty("insistent", d.insistent.toString()); p.setProperty("verdict", d.verdict)
    runCatching { File(context.noBackupFilesDir, FILE).outputStream().use { p.store(it, null) } }
  }

  fun last(context: Context): ClockDiagnostic? = runCatching {
    val f = File(context.noBackupFilesDir, FILE)
    if (!f.exists()) return null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    ClockDiagnostic(p.getProperty("at").toLong(), p.getProperty("package"), p.getProperty("category").ifEmpty { null },
      p.getProperty("channel").ifEmpty { null }, p.getProperty("actions").split('\u001F').filter { it.isNotEmpty() },
      p.getProperty("fullScreen").toBoolean(), p.getProperty("insistent").toBoolean(), p.getProperty("verdict"))
  }.getOrNull()

  fun clear(context: Context) { File(context.noBackupFilesDir, FILE).delete() }
}

/**
 * Local log of what happened: detected, launch strategy, listened, how it ended. Only events; no audio, no text of
 * notifications, never sent to Instinct or WhatsApp. The last [MAX] lines.
 */
object AlarmVoiceLog {
  private const val FILE = "alarm-voice-log.txt"
  private const val MAX = 60
  private val lock = Any()

  fun add(context: Context, event: String) = synchronized(lock) {
    runCatching {
      val f = File(context.applicationContext.noBackupFilesDir, FILE)
      val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
      val lines = (if (f.exists()) f.readLines() else emptyList()) + "$stamp · ${event.replace('\n', ' ')}"
      f.writeText(lines.takeLast(MAX).joinToString("\n"))
    }
    Unit
  }

  fun lines(context: Context): List<String> = synchronized(lock) {
    runCatching { File(context.applicationContext.noBackupFilesDir, FILE).readLines() }.getOrDefault(emptyList())
  }

  fun clear(context: Context) = synchronized(lock) { File(context.applicationContext.noBackupFilesDir, FILE).delete(); Unit }
}
