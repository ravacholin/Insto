package com.example.phone

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class DeviceCalendar(private val resolver: ContentResolver, private val now: Calendar = Calendar.getInstance()) {
  data class Occurrence(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarId: Long,
    val calendar: String,
    val writable: Boolean,
    val recurring: Boolean,
    val series: Boolean = false,
  )

  data class Change(val begin: Long, val end: Long)

  fun find(selection: CalendarSelection, limit: Int = 101): List<Occurrence> {
    val (begin, end) = selection.bounds(now)
    // All-day instances use UTC dates, while their displayed dates are civil dates in every zone.
    val padding = 86_400_000L
    val uri = Instances.CONTENT_URI.buildUpon().apply {
      ContentUris.appendId(this, begin - padding)
      ContentUris.appendId(this, end + padding)
    }.build()
    val columns = arrayOf(Instances.EVENT_ID, Events.TITLE, Instances.BEGIN, Instances.END, Events.ALL_DAY,
      Events.CALENDAR_ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL,
      Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID)
    val cursor = checkNotNull(resolver.query(uri, columns, "${Events.STATUS} IS NULL OR ${Events.STATUS} != ?",
      arrayOf(Events.STATUS_CANCELED.toString()), "${Instances.BEGIN} ASC, ${Instances.EVENT_ID} ASC"))
    return cursor.use {
      buildList {
        while (size < limit && it.moveToNext()) {
          val event = Occurrence(it.getLong(0), it.getString(1).orEmpty(), it.getLong(2), it.getLong(3),
            it.getInt(4) != 0, it.getLong(5), it.getString(6).orEmpty(),
            it.getInt(7) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
            !it.getString(8).isNullOrEmpty() || !it.getString(9).isNullOrEmpty() || !it.isNull(10) || !it.isNull(11),
            !it.getString(8).isNullOrEmpty() && it.isNull(10) && it.isNull(11))
          val localBegin = if (event.allDay) allDayLocal(event.begin) else event.begin
          val localEnd = if (event.allDay) allDayLocal(event.end) else event.end
          val clock = clock(event.begin)
          if (localBegin < end && (localEnd > begin || localEnd == localBegin && localBegin >= begin) &&
            (selection.title == null || titleMatches(event.title, selection.title)) &&
            (selection.at == null || !event.allDay && clock == selection.at) &&
            (!selection.afternoon || !event.allDay && clock < "20:00" &&
              (format(event.end, "yyyy-MM-dd") > format(event.begin, "yyyy-MM-dd") || format(event.end, "HH:mm") > "12:00"))) {
            add(event)
          }
        }
      }
    }
  }

  fun change(event: Occurrence, selection: CalendarSelection): Change {
    val zone = if (event.allDay) TimeZone.getTimeZone("UTC") else now.timeZone
    val original = Calendar.getInstance(zone).apply { timeInMillis = event.begin }
    require(!event.allDay || selection.newTime == null && selection.durationSeconds == null)
    val begin = selection.legacyBegin ?: if (selection.newDate == null && selection.newTime == null) event.begin else {
      val date = CalendarSelection.day(selection.newDate ?: CalendarSelection.iso(original), original)
      val time = selection.newTime
      date.set(Calendar.HOUR_OF_DAY, time?.substringBefore(':')?.toInt() ?: original.get(Calendar.HOUR_OF_DAY))
      date.set(Calendar.MINUTE, time?.substringAfter(':')?.toInt() ?: original.get(Calendar.MINUTE))
      if (time == null) {
        date.set(Calendar.SECOND, original.get(Calendar.SECOND))
        date.set(Calendar.MILLISECOND, original.get(Calendar.MILLISECOND))
      }
      val millis = date.timeInMillis
      val offset = zone.getOffset(millis)
      require(listOf(millis - 129_600_000, millis + 129_600_000).none { other ->
        val alternativeOffset = zone.getOffset(other)
        alternativeOffset != offset && zone.getOffset(millis + offset - alternativeOffset) == alternativeOffset
      })
      millis
    }
    val duration = selection.durationSeconds?.times(1000L) ?: Math.subtractExact(event.end, event.begin)
    require(duration >= 0)
    return Change(begin, Math.addExact(begin, duration))
  }

  fun update(event: Occurrence, change: Change): Boolean {
    require(event.writable && isWritable(event.calendarId) && !event.recurring)
    val values = ContentValues().apply {
      if (change.begin != event.begin) put(Events.DTSTART, change.begin)
      if (change.end != event.end) put(Events.DTEND, change.end)
    }
    if (values.size() == 0) return false
    val uri = eventUri(event.id)
    val (where, args) = existing(event)
    if (resolver.update(Events.CONTENT_URI, values, where, args) != 1) return false
    return resolver.query(uri, arrayOf(Events.DTSTART, Events.DTEND, Events.DELETED), null, null, null)
      ?.use { it.moveToFirst() && it.getLong(0) == change.begin && it.getLong(1) == change.end && it.getInt(2) == 0 } == true
  }

  fun delete(event: Occurrence): Boolean {
    require(event.writable && isWritable(event.calendarId) && !event.recurring)
    val uri = eventUri(event.id)
    val (where, args) = existing(event)
    if (resolver.delete(Events.CONTENT_URI, where, args) != 1) return false
    return resolver.query(uri, arrayOf(Events.DELETED), null, null, null)
      ?.use { !it.moveToFirst() || it.getInt(0) != 0 } == true
  }

  /** A single exception; the recurrence rule and parent row are never changed by Insto. */
  fun changeOccurrence(event: Occurrence, selection: CalendarSelection, change: Change?): Boolean = synchronized(exceptionLock) {
    require(event.series && event.writable && isWritable(event.calendarId))
    require(change == null || change.end - change.begin == event.end - event.begin)
    if (event !in find(selection)) return false
    val priorExceptions = checkNotNull(resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
      "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} = 0",
      arrayOf(event.id.toString(), event.begin.toString()), null))
    if (priorExceptions.use { it.moveToFirst() }) return false
    val values = ContentValues().apply {
      put(Events.ORIGINAL_INSTANCE_TIME, event.begin)
      if (change == null) put(Events.STATUS, Events.STATUS_CANCELED)
      else put(Events.DTSTART, change.begin)
    }
    val exceptionUri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, event.id)
    val inserted = resolver.insert(exceptionUri, values) ?: return false
    val id = ContentUris.parseId(inserted)
    if (id == event.id || id < 0) return false
    resolver.query(eventUri(id),
      arrayOf(Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.STATUS, Events.DTSTART, Events.DTEND),
      null, null, null)?.use {
        it.moveToFirst() && it.getLong(0) == event.id && it.getLong(1) == event.begin &&
          if (change == null) it.getInt(2) == Events.STATUS_CANCELED && find(selection).none { row -> row.id == event.id && row.begin == event.begin }
          else it.getLong(3) == change.begin && it.getLong(4) == change.end
      } == true
  }

  private fun existing(event: Occurrence): Pair<String, Array<String>> =
    "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} = ? AND ${Events.DTEND} = ? AND " +
      "${Events.DELETED} = 0 AND (${Events.TITLE} = ? OR ${Events.TITLE} IS NULL AND ? = '') AND " +
      "COALESCE(${Events.RRULE}, '') = '' AND COALESCE(${Events.RDATE}, '') = '' AND " +
      "${Events.ORIGINAL_ID} IS NULL AND ${Events.ORIGINAL_SYNC_ID} IS NULL" to
      arrayOf(event.id.toString(), event.calendarId.toString(), event.begin.toString(), event.end.toString(), event.title, event.title)

  private fun isWritable(calendarId: Long): Boolean =
    (accessLevel(calendarId) ?: 0) >= Calendars.CAL_ACCESS_CONTRIBUTOR

  fun accessLevel(calendarId: Long): Int? =
    resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
      arrayOf(Calendars.CALENDAR_ACCESS_LEVEL), null, null, null)
      ?.use { if (it.moveToFirst()) it.getInt(0) else null }

  fun agenda(selection: CalendarSelection): String {
    val matches = find(selection, if (selection.next && selection.title != null) 1 else 21)
    if (matches.isEmpty()) return "No encontré eventos para ese pedido."
    return matches.take(20).joinToString("\n") { label(it) } +
      if (matches.size > 20) "\nHay más resultados. Indicá un intervalo más corto." else ""
  }

  fun label(event: Occurrence, begin: Long = event.begin, end: Long = event.end): String {
    val start = if (event.allDay) format(begin, "dd/MM/yyyy", TimeZone.getTimeZone("UTC")) else format(begin, "dd/MM/yyyy HH:mm")
    val finish = when {
      event.allDay -> " (todo el día)"
      end == begin -> ""
      format(begin, "yyyy-MM-dd") == format(end, "yyyy-MM-dd") -> " a ${format(end, "HH:mm")}"
      else -> " a ${format(end, "dd/MM/yyyy HH:mm")}"
    }
    return "$start$finish · ${event.title.ifBlank { "Sin título" }} · ${event.calendar}" +
      if (event.recurring) " · recurrente" else ""
  }

  private fun clock(time: Long) = format(time, "HH:mm")
  private fun format(time: Long, pattern: String, zone: TimeZone = now.timeZone) =
    SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }.format(Date(time))

  private fun allDayLocal(time: Long): Long =
    CalendarSelection.day(format(time, "yyyy-MM-dd", TimeZone.getTimeZone("UTC")), now).timeInMillis

  private fun titleMatches(title: String, requested: String): Boolean {
    val folded = PhoneCommandParser.normalize(title)
    val filter = PhoneCommandParser.normalize(requested)
    return filter.isNotBlank() && Regex("(?:^|[^\\p{L}\\p{N}])${Regex.escape(filter)}(?:$|[^\\p{L}\\p{N}])").containsMatchIn(folded)
  }

  companion object {
    private val exceptionLock = Any()

    fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)
  }
}
