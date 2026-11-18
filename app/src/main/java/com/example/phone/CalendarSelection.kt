package com.example.phone

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

/** Search filters and requested changes only. Provider rows never enter the interpretation draft. */
internal data class CalendarSelection(
  val title: String? = null,
  val from: String? = null,
  val until: String? = null,
  val at: String? = null,
  val afternoon: Boolean = false,
  val next: Boolean = false,
  val newDate: String? = null,
  val newTime: String? = null,
  val durationSeconds: Int? = null,
  val legacyBegin: Long? = null,
) {
  fun command(action: PlatformAction): PhoneCommand.Platform {
    val fields = JSONObject()
    mapOf("from" to from, "until" to until, "at" to at, "afternoon" to afternoon.takeIf { it },
      "next" to next.takeIf { it }, "newDate" to newDate, "newTime" to newTime,
      "durationSeconds" to durationSeconds).forEach { (key, value) -> if (value != null) fields.put(key, value) }
    return PhoneCommand.Platform(action, title ?: "*", content = fields.put("version", 1).toString())
  }

  fun bounds(now: Calendar): Pair<Long, Long> {
    val begin = from?.let { day(it, now) } ?: (now.clone() as Calendar).apply {
      if (!next && title != null) add(Calendar.DAY_OF_MONTH, -30)
    }
    val end = until?.let { day(it, now) } ?: (begin.clone() as Calendar).apply {
      add(Calendar.DAY_OF_MONTH, if (from != null) 1 else if (next) 30 else 90)
    }
    require(end.timeInMillis > begin.timeInMillis && end.timeInMillis - begin.timeInMillis <= 91L * 86_400_000)
    return begin.timeInMillis to end.timeInMillis
  }

  companion object {
    val actions = setOf(PlatformAction.CALENDAR_QUERY, PlatformAction.CALENDAR_UPDATE, PlatformAction.CALENDAR_DELETE)
    val types = actions.map { it.name.lowercase(Locale.ROOT) }.toSet()
    val fields = setOf("from", "until", "at", "afternoon", "next", "newDate", "newTime", "durationSeconds")
    fun day(value: String, now: Calendar): Calendar {
      val parts = value.split('-')
      require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
      return (now.clone() as Calendar).apply {
        clear()
        isLenient = false
        set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
        timeInMillis // Validate the civil date in the device zone.
      }
    }

    fun iso(day: Calendar): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
      .apply { timeZone = day.timeZone }.format(day.time)

    fun decode(command: PhoneCommand.Platform): CalendarSelection {
      val data = command.content?.let(::JSONObject) ?: JSONObject()
      val argument = requireNotNull(command.argument)
      if (!data.has("version")) {
        return when (command.action) {
          PlatformAction.CALENDAR_QUERY -> {
            require(data.length() == 0)
            day(argument, Calendar.getInstance())
            CalendarSelection(from = argument)
          }
          PlatformAction.CALENDAR_DELETE -> {
            require(data.length() == 0)
            CalendarSelection(title = argument)
          }
          PlatformAction.CALENDAR_UPDATE -> {
            require(data.keys().asSequence().all { it in setOf("beginMillis", "durationSeconds") })
            val begin = if (command.content == null) null else data.get("beginMillis").let {
              require(it is Long || it is Int)
              (it as Number).toLong().also { value -> require(value > 0) }
            }
            CalendarSelection(title = argument, legacyBegin = begin, durationSeconds = duration(data))
          }
          else -> error("Acción de calendario inválida")
        }
      }
      require(data.get("version") == 1)
      val selection = fromFields(data, argument.takeUnless { it == "*" })
      when (command.action) {
        PlatformAction.CALENDAR_QUERY -> require(selection.newDate == null && selection.newTime == null && selection.durationSeconds == null)
        PlatformAction.CALENDAR_DELETE -> require(!selection.next && selection.newDate == null && selection.newTime == null && selection.durationSeconds == null)
        PlatformAction.CALENDAR_UPDATE -> require(!selection.next)
        else -> error("Acción de calendario inválida")
      }
      if (command.action != PlatformAction.CALENDAR_QUERY) {
        require(selection.title != null || selection.from != null && selection.until == null && selection.at != null)
      }
      return selection
    }

    private fun duration(data: JSONObject): Int? = if (!data.has("durationSeconds")) null else {
      val value = data.get("durationSeconds")
      require(value is Int && value > 0)
      value
    }

    fun fromFields(data: JSONObject, title: String?): CalendarSelection {
      require(data.keys().asSequence().all { it in fields || it == "version" })
      fun string(key: String) = if (data.has(key)) (data.get(key) as String) else null
      fun date(key: String) = string(key)?.also { day(it, Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))) }
      fun time(key: String) = string(key)?.also { require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it)) }
      fun flag(key: String) = data.has(key) && data.get(key) as Boolean
      val from = date("from")
      val until = date("until")
      require(until == null || from != null && until > from)
      val at = time("at")
      val afternoon = flag("afternoon")
      require(at == null || !afternoon)
      return CalendarSelection(title, from, until, at, afternoon, flag("next"), date("newDate"), time("newTime"), duration(data))
    }
  }
}
