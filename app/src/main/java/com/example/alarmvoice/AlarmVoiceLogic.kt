package com.example.alarmvoice

import java.text.Normalizer

/**
 * «Callar alarma con la voz» (1.25.0): the pure part, without Android, so it can be tested on the JVM.
 *
 * Only structural data of a clock notification reaches here: its package, its category, whether it carries a
 * full-screen intent / is insistent, its channel id and the titles of its actions. Never its text or extras.
 */
enum class AlarmKind { ALARM, TIMER }

/** What [AlarmNotificationDetector] looks at. Nothing else of the notification is read. */
data class ClockNotificationShape(
  val packageName: String,
  val category: String?,
  val actionTitles: List<String?>,
  val hasFullScreenIntent: Boolean = false,
  val insistent: Boolean = false,
  val channelId: String? = null,
)

object ClockPackages {
  /**
   * Clock apps whose ringing alarms Insto may silence. Easy to extend: add the exact package here. Motorola packages
   * are also accepted by [MOTOROLA_PREFIX] when their name says clock or alarm.
   */
  val KNOWN: Set<String> = setOf(
    "com.google.android.deskclock",
    "com.android.deskclock",
    "com.motorola.blur.alarmclock",
    "com.motorola.deskclock",
  )
  const val MOTOROLA_PREFIX = "com.motorola."
  private val MOTOROLA_HINTS = listOf("clock", "alarm")

  fun isKnown(packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    if (packageName in KNOWN) return true
    val lower = packageName.lowercase()
    return lower.startsWith(MOTOROLA_PREFIX) && MOTOROLA_HINTS.any { lower.contains(it) }
  }

  /** For the diagnostic mode only: a package that looks like a clock but is not in the list yet. */
  fun looksLikeClock(packageName: String?): Boolean {
    val lower = packageName?.lowercase() ?: return false
    return isKnown(lower) || lower.contains("deskclock") || lower.contains("alarmclock") || lower.endsWith(".clock")
  }
}

object TextNormalizer {
  private val MARKS = Regex("\\p{Mn}+")
  private val SPACES = Regex("\\s+")
  private val EDGES = Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$")

  /** Lowercase, without accents, trimmed punctuation and single spaces: «  ¡Pará! » → «para». */
  fun normalize(text: String?): String {
    if (text == null) return ""
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
    return decomposed.lowercase().replace(SPACES, " ").replace(EDGES, "").trim()
  }
}

/** Chooses the «Detener» action of a ringing alarm. Never «Posponer» or «Snooze». */
object StopActionPicker {
  val STOP_WORDS = listOf("detener", "descartar", "parar", "stop", "dismiss")
  val SNOOZE_WORDS = listOf("posponer", "snooze", "repetir", "aplazar")
  /** «Descartar ahora» belongs to the upcoming-alarm notification, not to a ringing one. */
  private val NOT_NOW = listOf("ahora", "now")

  /** Index of the stop action in [titles], or null. */
  fun pick(titles: List<String?>): Int? {
    titles.forEachIndexed { index, title -> if (isStop(title)) return index }
    return null
  }

  fun isSnooze(title: String?): Boolean {
    val n = TextNormalizer.normalize(title)
    return n.isNotEmpty() && SNOOZE_WORDS.any { n == it || n.split(' ').contains(it) }
  }

  fun isStop(title: String?): Boolean {
    val n = TextNormalizer.normalize(title)
    if (n.isEmpty() || isSnooze(title)) return false
    val words = n.split(' ')
    if (words.any { it in NOT_NOW }) return false
    // The whole title or its first word: «Detener», «Stop», «Detener alarma», «Dismiss alarm».
    return n in STOP_WORDS || words.first() in STOP_WORDS
  }
}

object AlarmNotificationDetector {
  const val CATEGORY_ALARM = "alarm" // Notification.CATEGORY_ALARM, kept literal for the JVM tests.
  private val TIMER_ACTION_HINTS = listOf("+1", "+ 1", "1 min", "1:00", "agregar", "anadir", "add")

  /**
   * The kind of the ringing alarm or the expired timer, or null when [shape] is anything else: another app, the
   * upcoming-alarm reminder or a running timer (those do not ring: no full-screen intent, not insistent, no snooze).
   */
  fun classify(shape: ClockNotificationShape): AlarmKind? {
    if (!ClockPackages.isKnown(shape.packageName)) return null
    val hasSnooze = shape.actionTitles.any { StopActionPicker.isSnooze(it) }
    val ringing = shape.hasFullScreenIntent || shape.insistent || hasSnooze
    if (!ringing) return null
    val timer = looksLikeExpiredTimer(shape)
    if (shape.category != CATEGORY_ALARM && !timer) return null
    return if (timer && !hasSnooze) AlarmKind.TIMER else AlarmKind.ALARM
  }

  /** The «time's up» notification: a timer channel, or a stop action next to «+1 min» and no snooze. */
  fun looksLikeExpiredTimer(shape: ClockNotificationShape): Boolean {
    val channel = shape.channelId?.lowercase().orEmpty()
    if (channel.contains("timer") || channel.contains("temporizador")) return true
    val hasStop = StopActionPicker.pick(shape.actionTitles) != null
    val hasAddTime = shape.actionTitles.any { t ->
      val n = TextNormalizer.normalize(t).replace(" ", "")
      TIMER_ACTION_HINTS.any { n.contains(it.replace(" ", "")) }
    }
    return hasStop && hasAddTime && shape.actionTitles.none { StopActionPicker.isSnooze(it) }
  }
}

/** The words that silence the alarm and how Vosk's results are matched against them. */
object KeywordMatcher {
  val DEFAULT_KEYWORDS = listOf("basta", "listo", "pará", "ok")
  const val UNKNOWN = "[unk]"
  const val MIN_CONFIDENCE = 0.55
  const val MAX_KEYWORDS = 8

  /** Splits what the person typed: commas, semicolons or spaces; one word each, no duplicates after normalizing. */
  fun parse(input: String): List<String> = input.split(',', ';', '\n', ' ')
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() && TextNormalizer.normalize(it).isNotEmpty() }
    .distinctBy { TextNormalizer.normalize(it) }
    .take(MAX_KEYWORDS)

  /**
   * Vosk's grammar: each word as typed and without accents (the small Spanish model knows «para» but not «pará»;
   * Vosk ignores the form it does not know), plus «[unk]» so anything else is not forced into a keyword.
   */
  fun grammar(keywords: List<String>): List<String> {
    val words = LinkedHashSet<String>()
    keywords.forEach { k ->
      val lower = k.trim().lowercase()
      if (lower.isNotEmpty()) words += lower
      val plain = TextNormalizer.normalize(k)
      if (plain.isNotEmpty()) words += plain
    }
    words += UNKNOWN
    return words.toList()
  }

  fun grammarJson(keywords: List<String>): String =
    grammar(keywords).joinToString(prefix = "[", postfix = "]", separator = ",") { "\"" + it.replace("\"", "") + "\"" }

  /**
   * The keyword heard in a final result, or null. Whole words only, compared without accents; «[unk]» and words
   * under [MIN_CONFIDENCE] never count. «bastante» never matches «basta».
   */
  fun matchWords(words: List<Pair<String, Double?>>, keywords: List<String>): String? {
    val wanted = keywords.associateBy { TextNormalizer.normalize(it) }
    for ((word, conf) in words) {
      if (word == UNKNOWN) continue
      if (conf != null && conf < MIN_CONFIDENCE) continue
      wanted[TextNormalizer.normalize(word)]?.let { return it }
    }
    return null
  }

  /** Same as [matchWords] for a plain text (Vosk's «text» or «partial»), without confidences. */
  fun matchText(text: String?, keywords: List<String>): String? =
    matchWords(text.orEmpty().split(' ', '\t', '\n').filter { it.isNotBlank() }.map { it to null }, keywords)
}

/**
 * Partial results have no confidence: a keyword counts there only when two consecutive partials agree, which filters
 * the single noisy guesses the alarm's sound produces. Final results decide on their own.
 */
class PartialConfirmation(private val keywords: List<String>) {
  private var last: String? = null

  fun onPartial(text: String?): String? {
    val heard = KeywordMatcher.matchText(text, keywords)
    val confirmed = heard != null && heard == last
    last = heard
    return if (confirmed) heard else null
  }

  fun reset() { last = null }
}

/** The ducked alarm volume: [percent] of [original], never below [min] nor above the original. */
fun duckedVolume(original: Int, percent: Int, min: Int): Int {
  if (original <= min) return original
  val target = Math.round(original * percent.coerceIn(0, 100) / 100f)
  return target.coerceIn(min.coerceAtLeast(1).coerceAtMost(original), original)
}
