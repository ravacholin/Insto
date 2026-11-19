package com.example.phone

import java.util.Calendar
import java.util.Locale

/** A local wall-clock time. The caller decides on which date to use it. */
internal sealed interface ClockTime {
  data class Exact(val hour: Int, val minute: Int) : ClockTime {
    fun canonical(): String = "%02d:%02d".format(Locale.ROOT, hour, minute)

    /** Reject unavailable and repeated local times instead of letting Calendar pick an instant. */
    fun onDate(day: Calendar): Long? {
      val target = (day.clone() as Calendar).apply {
        isLenient = false
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      val instant = runCatching { target.timeInMillis }.getOrNull() ?: return null
      val zone = target.timeZone
      val offset = zone.getOffset(instant)
      for (distance in listOf(-129_600_000L, 129_600_000L)) {
        val other = zone.getOffset(instant + distance)
        val alternative = instant + offset - other
        if (other != offset && zone.getOffset(alternative) == other) return null
      }
      return instant
    }
  }
  data object Ambiguous : ClockTime
  data object Invalid : ClockTime

  companion object {
    private const val PERIOD = "(?:[ap](?:\\.\\s*|\\s*)m\\.?|de la (?:manana|tarde|noche)|horas)"
    private const val COLON_MINUTE = "[0-9]{1,2}"
    private const val DOT_MINUTE = "[0-9]{2}"
    private const val SPOKEN_MINUTE = "(?:media|cuarto|(?!que\\b)[a-z]+(?: y (?!que\\b)[a-z]+)?)"
    private const val HOUR = "(?:[0-9]+|[a-z]+)"
    const val PATTERN = "$HOUR(?::$COLON_MINUTE|\\.$DOT_MINUTE| y $SPOKEN_MINUTE)?(?: ?$PERIOD)?"
    private val time = Regex("^($HOUR)(?::($COLON_MINUTE)|\\.($DOT_MINUTE)| y ($SPOKEN_MINUTE))?(?: ?($PERIOD))?$")
    private val replyPrefix = Regex("^(?:mejor )?(?:(?:a |para )?las? )?")
    private val numericTime = Regex("^[0-9]+[:.]")
    private val period = Regex(PERIOD)

    fun parse(text: String): ClockTime {
      val match = time.matchEntire(text) ?: return Invalid
      val (hourText, colonMinute, dotMinute, spokenMinute, periodText) = match.destructured
      val hour = PhoneCommandParser.number(hourText) ?: return Invalid
      val numericMinute = colonMinute.ifEmpty { dotMinute }
      val minute = when (spokenMinute) {
        "media" -> 30
        "cuarto" -> 15
        "" -> numericMinute.toIntOrNull() ?: 0
        else -> PhoneCommandParser.number(spokenMinute) ?: return Invalid
      }
      if (hour !in 0..23 || minute !in 0..59) return Invalid
      val meridiem = compactPeriod(periodText)
      if (meridiem in listOf("", "horas")) {
        return if (meridiem.isEmpty() && numericMinute.isEmpty() && hour in 1..12) Ambiguous else Exact(hour, minute)
      }
      if (hour !in 1..12) return Invalid
      val adjusted = when (meridiem) {
        "am", "de la manana" -> hour % 12
        "de la noche" -> if (hour == 12) 0 else hour + 12
        else -> hour % 12 + 12
      }
      return Exact(adjusted, minute)
    }

    fun reply(text: String): String {
      val folded = FoldedRequest.of(text).text.trimEnd('.', '!', '?', ' ')
      return folded.replaceFirst(replyPrefix, "")
    }

    fun isPeriod(text: String): Boolean = text != "horas" && period.matches(text)

    fun isClockReply(text: String): Boolean = isPeriod(text) || numericTime.containsMatchIn(text) || parse(text) != Invalid

    fun shape(text: String): Boolean = time.matches(text)

    fun replacePeriod(original: String, replacement: String): String {
      val previous = time.matchEntire(original)?.groups?.get(5) ?: return "$original $replacement"
      return original.substring(0, previous.range.first).trimEnd() + " " + replacement
    }

    fun periodOf(text: String): String? = time.matchEntire(text)?.groups?.get(5)?.value

    fun compactPeriod(text: String): String = if (text.startsWith("de la ")) text else text.replace(".", "").replace(" ", "")
  }
}
