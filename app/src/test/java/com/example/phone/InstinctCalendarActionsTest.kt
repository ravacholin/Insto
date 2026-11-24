package com.example.phone

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstinctCalendarActionsTest {
  private val provider = CalendarProvider()
  private val actions get() = InstinctCalendarActions(ApplicationProvider.getApplicationContext<Application>().contentResolver)
  private val begin = System.currentTimeMillis() + 3_600_000
  private val end = begin + 1_800_000
  private fun params() = JSONObject().put("calendarId", 1).put("beginMillis", begin).put("endMillis", end)

  @Before fun register() {
    ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
  }

  @Test fun `guarded creation query modification undo and cancellation are verified by the provider`() {
    val created = actions.execute("calendar_create", params().put("title", "Reunión"))
    assertTrue(created.message, created.verifiedLocally)
    val result = actions.execute("calendar_query", params())
    val event = JSONObject(checkNotNull(result.executionData))
    assertEquals(12, event.getInt("eventId"))
    val updated = actions.execute("calendar_update", event.put("beginMillis", begin + 600_000).put("endMillis", end + 600_000))
    assertTrue(updated.message, updated.verifiedLocally)
    assertEquals(begin + 600_000, provider.row?.getAsLong(Events.DTSTART))
    assertTrue(actions.undo(JSONObject(checkNotNull(updated.executionData))).verifiedLocally)
    assertEquals(begin, provider.row?.getAsLong(Events.DTSTART))
    assertTrue(actions.execute("calendar_cancel", event).verifiedLocally)
    assertNull(provider.row)
    assertEquals(4, provider.writes)
  }

  @Test fun `a user edit protects the row and a missing readback does not cause another write`() {
    val created = actions.execute("calendar_create", params().put("title", "Reunión"))
    val ref = JSONObject(checkNotNull(created.executionData))
    provider.row?.put(Events.EVENT_LOCATION, "En otro lugar")
    assertEquals(PhoneActionOutcome.REJECTED, actions.execute("calendar_cancel", ref).outcome)
    assertEquals(PhoneActionOutcome.REJECTED, actions.undo(ref).outcome)
    assertEquals(1, provider.writes)
    provider.unreadableAfterInsert = true
    assertEquals(PhoneActionOutcome.UNCERTAIN, actions.execute("calendar_create", params().put("title", "Otra reunión")).outcome)
    assertEquals(2, provider.writes)
  }

  private class CalendarProvider : ContentProvider() {
    var row: ContentValues? = null
    var writes = 0
    var unreadableAfterInsert = false
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
      selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
      val columns = checkNotNull(projection)
      val cursor = MatrixCursor(columns)
      if (uri.pathSegments.first() == "calendars") {
        cursor.addRow(columns.map { when (it) {
          Calendars._ID -> 1
          Calendars.CALENDAR_DISPLAY_NAME -> "Personal"
          Calendars.CALENDAR_ACCESS_LEVEL -> Calendars.CAL_ACCESS_OWNER
          else -> null
        } })
      } else if (uri.pathSegments.first() == "instances") {
        row?.let { cursor.addRow(columns.map { column -> when (column) {
          Instances.EVENT_ID -> 12
          Instances.BEGIN -> it.get(Events.DTSTART)
          Instances.END -> it.get(Events.DTEND)
          else -> it.get(column)
        } }) }
      } else {
        if (unreadableAfterInsert) return null
        row?.let { cursor.addRow(columns.map(it::get)) }
      }
      return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
      writes++
      row = ContentValues(checkNotNull(values)).apply { put(Events.DELETED, 0); put(Events.ALL_DAY, 0) }
      return ContentUris.withAppendedId(Events.CONTENT_URI, 12)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
      val event = row ?: return 0
      check(selectionArgs?.firstOrNull() == "12")
      writes++
      event.putAll(values)
      return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
      if (row == null) return 0
      check(selectionArgs?.firstOrNull() == "12")
      writes++
      row = null
      return 1
    }
  }
}
