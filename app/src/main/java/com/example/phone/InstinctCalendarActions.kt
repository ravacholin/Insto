package com.example.phone

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.util.TimeZone
import org.json.JSONObject

internal class InstinctCalendarActions(private val resolver: ContentResolver) {
  data class Access(val id: Long, val label: String, val writable: Boolean)

  private data class Event(
    val id: Long, val calendar: Long, val begin: Long, val end: Long, val title: String?, val description: String?,
    val zone: String?, val status: Int?, val safeToChange: Boolean,
  ) {
    val revision: String
      get() = sha256(JSONObject().put("id", id).put("calendar", calendar).put("begin", begin).put("end", end)
        .put("title", title ?: JSONObject.NULL).put("description", description ?: JSONObject.NULL)
        .put("zone", zone ?: JSONObject.NULL).put("status", status ?: JSONObject.NULL).put("safe", safeToChange).toString())

    fun reference() = JSONObject().put("calendarId", calendar).put("eventId", id).put("revision", revision)
  }

  fun calendars(): List<Access> = checkNotNull(resolver.query(Calendars.CONTENT_URI,
    arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL), null, null,
    Calendars.CALENDAR_DISPLAY_NAME)).use { cursor ->
    buildList {
      while (size < 100 && cursor.moveToNext()) {
        add(Access(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getInt(2) >= Calendars.CAL_ACCESS_CONTRIBUTOR))
      }
    }
  }

  fun execute(capability: String, params: JSONObject): PhoneActionResult {
    val calendar = params.getLong("calendarId")
    val access = calendars().singleOrNull { it.id == calendar } ?: return reject("El calendario habilitado ya no está disponible.")
    if (capability != "calendar_query" && !access.writable) return reject("El calendario es de solo lectura.")
    return when (capability) {
      "calendar_query" -> query(calendar, params)
      "calendar_create" -> create(calendar, params)
      "calendar_update", "calendar_cancel" -> change(calendar, capability, params)
      else -> reject("Acción de calendario desconocida.")
    }
  }

  private fun query(calendar: Long, params: JSONObject): PhoneActionResult {
    val uri = Instances.CONTENT_URI.buildUpon().apply {
      ContentUris.appendId(this, params.getLong("beginMillis"))
      ContentUris.appendId(this, params.getLong("endMillis"))
    }.build()
    val filter = params.optString("title").takeIf(String::isNotEmpty)
    val matches = checkNotNull(resolver.query(uri, arrayOf(Instances.EVENT_ID, Events.TITLE),
      "${Events.CALENDAR_ID} = ? AND (${Events.STATUS} IS NULL OR ${Events.STATUS} != ?)",
      arrayOf(calendar.toString(), Events.STATUS_CANCELED.toString()), Instances.BEGIN)).use { cursor ->
      buildList {
        while (size <= 20 && cursor.moveToNext()) {
          if (filter == null || cursor.getString(1)?.equals(filter, ignoreCase = true) == true) add(cursor.getLong(0))
        }
      }
    }
    val single = matches.singleOrNull()?.let(::event)?.takeIf { it.calendar == calendar && it.safeToChange }
    val count = if (matches.size > 20) "más de 20" else matches.size.toString()
    val detail = if (single == null) "$count coincidencias."
      else "1 coincidencia · inicio ${single.begin} · fin ${single.end} · evento ${single.id}."
    return verified(detail, single?.reference())
  }

  private fun create(calendar: Long, params: JSONObject): PhoneActionResult {
    if (params.getLong("beginMillis") <= System.currentTimeMillis()) return reject("El inicio del evento ya pasó.")
    val values = times(params).apply {
      put(Events.CALENDAR_ID, calendar)
      put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
      put(Events.TITLE, params.getString("title"))
      if (params.has("description")) put(Events.DESCRIPTION, params.getString("description"))
    }
    return mutation {
      val uri = resolver.insert(Events.CONTENT_URI, values) ?: return@mutation uncertain()
      val row = event(ContentUris.parseId(uri))
      if (row != null && row.calendar == calendar && matches(row, params) &&
        row.description == params.optString("description").takeIf(String::isNotEmpty)) {
        verified("Evento guardado · ${row.id}.", row.reference().put("undo", "create"))
      } else uncertain()
    }
  }

  private fun change(calendar: Long, capability: String, params: JSONObject): PhoneActionResult {
    val row = event(params.getLong("eventId")) ?: return reject("No encontré el evento.")
    if (!row.safeToChange || row.calendar != calendar || row.revision != params.getString("revision"))
      return reject("El evento cambió, es recurrente, tiene invitados o no pertenece al calendario habilitado.")
    val (where, args) = compare(row)
    return mutation {
      if (capability == "calendar_cancel") {
        return@mutation when (resolver.delete(Events.CONTENT_URI, where, args)) {
          0 -> reject("El evento cambió antes de cancelarlo.")
          1 -> if (event(row.id) == null) verified("Evento cancelado.") else uncertain()
          else -> uncertain()
        }
      }
      val values = times(params).apply { if (params.has("title")) put(Events.TITLE, params.getString("title")) }
      when (resolver.update(Events.CONTENT_URI, values, where, args)) {
        0 -> reject("El evento cambió antes de modificarlo.")
        1 -> {
          val updated = event(row.id)
          if (updated != null && updated.calendar == calendar && matches(updated, params) && updated.description == row.description)
            verified("Evento modificado · ${row.id}.", updated.reference().put("undo", "update")
              .put("beforeBegin", row.begin).put("beforeEnd", row.end).put("beforeTitle", row.title ?: JSONObject.NULL))
          else uncertain()
        }
        else -> uncertain()
      }
    }
  }

  fun undo(data: JSONObject): PhoneActionResult {
    val row = event(data.getLong("eventId")) ?: return reject("El evento ya no existe.")
    if (!row.safeToChange || row.calendar != data.getLong("calendarId") || row.revision != data.getString("revision"))
      return reject("El evento cambió después de la ejecución.")
    if (calendars().none { it.id == row.calendar && it.writable }) return reject("El calendario es de solo lectura.")
    val (where, args) = compare(row)
    return mutation {
      if (data.getString("undo") == "create") {
        if (resolver.delete(Events.CONTENT_URI, where, args) == 1 && event(row.id) == null)
          verified("Alta deshecha.") else uncertain()
      } else {
        val values = ContentValues().apply {
          put(Events.DTSTART, data.getLong("beforeBegin"))
          put(Events.DTEND, data.getLong("beforeEnd"))
          if (data.isNull("beforeTitle")) putNull(Events.TITLE) else put(Events.TITLE, data.getString("beforeTitle"))
        }
        if (resolver.update(Events.CONTENT_URI, values, where, args) == 1 && event(row.id)?.revision ==
          row.copy(begin = data.getLong("beforeBegin"), end = data.getLong("beforeEnd"),
            title = if (data.isNull("beforeTitle")) null else data.getString("beforeTitle")).revision)
          verified("Cambio deshecho.") else uncertain()
      }
    }
  }

  companion object {
    fun undo(context: android.content.Context, id: String): String {
      val journal = InstinctExecutions.journal(context)
      val raw = journal.get(id)?.executionData ?: return "Deshacer ya no está disponible."
      val data = JSONObject(raw)
      if (data.optString("undo") !in setOf("create", "update")) return "La ejecución no tiene una operación de deshacer."
      if (listOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
          .any { !InstinctDeviceCapabilities.granted(context, it) }) return "Concedé acceso al calendario para deshacer."
      if (!journal.takeCalendarUndo(id, raw)) return "Deshacer ya no está disponible."
      return runCatching { InstinctCalendarActions(context.contentResolver).undo(data).message }
        .getOrDefault("No pude comprobar si se deshizo el cambio. Revisá el calendario.")
    }
  }

  private fun event(id: Long): Event? = checkNotNull(resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
    arrayOf(Events.CALENDAR_ID, Events.DTSTART, Events.DTEND, Events.TITLE, Events.DESCRIPTION, Events.EVENT_TIMEZONE,
      Events.STATUS, Events.DELETED, Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID,
      Events.ALL_DAY, Events.HAS_ATTENDEE_DATA), null, null, null)).use { cursor ->
    if (!cursor.moveToFirst() || cursor.getInt(7) != 0 || cursor.getInt(6) == Events.STATUS_CANCELED) return@use null
    Event(id, cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4),
      cursor.getString(5), if (cursor.isNull(6)) null else cursor.getInt(6),
      cursor.getString(8).isNullOrEmpty() && cursor.getString(9).isNullOrEmpty() && cursor.isNull(10) &&
        cursor.isNull(11) && cursor.getInt(12) == 0 && cursor.getInt(13) == 0)
  }

  private fun compare(row: Event): Pair<String, Array<String>> {
    val fields = linkedMapOf(Events._ID to row.id.toString(), Events.CALENDAR_ID to row.calendar.toString(),
      Events.DTSTART to row.begin.toString(), Events.DTEND to row.end.toString(), Events.TITLE to row.title,
      Events.DESCRIPTION to row.description, Events.EVENT_TIMEZONE to row.zone, Events.STATUS to row.status?.toString())
    val where = fields.entries.joinToString(" AND ") { (key, value) -> if (value == null) "$key IS NULL" else "$key = ?" }
    return ("$where AND ${Events.DELETED} = 0 AND COALESCE(${Events.RRULE}, '') = '' AND COALESCE(${Events.RDATE}, '') = ''" +
      " AND ${Events.ORIGINAL_ID} IS NULL AND ${Events.ORIGINAL_SYNC_ID} IS NULL AND ${Events.ALL_DAY} = 0" +
      " AND COALESCE(${Events.HAS_ATTENDEE_DATA}, 0) = 0") to fields.values.filterNotNull().toTypedArray()
  }

  private fun times(params: JSONObject) = ContentValues().apply {
    put(Events.DTSTART, params.getLong("beginMillis"))
    put(Events.DTEND, params.getLong("endMillis"))
  }

  private fun matches(row: Event, params: JSONObject) = row.begin == params.getLong("beginMillis") &&
    row.end == params.getLong("endMillis") && (!params.has("title") || row.title == params.getString("title"))

  private inline fun mutation(change: () -> PhoneActionResult) = try { change() } catch (_: Exception) { uncertain() }
  private fun uncertain() = PhoneActionResult("No pude comprobar el cambio en el calendario.", outcome = PhoneActionOutcome.UNCERTAIN)
  private fun reject(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
  private fun verified(message: String, data: JSONObject? = null) =
    PhoneActionResult(message, verifiedLocally = true, executionData = data?.toString())
}
