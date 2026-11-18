package com.example.phone

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceCalendarTest {
  private val now = Calendar.getInstance(TimeZone.getTimeZone("America/Argentina/Buenos_Aires")).apply {
    clear()
    set(2026, Calendar.NOVEMBER, 18, 10, 0)
  }
  private val provider = CalendarProvider()
  private val store by lazy { DeviceCalendar(ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver, now) }
  private val parser = PhoneCommandParser { now }

  @Before fun register() {
    ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
  }

  @Test fun tomorrowAndWeekUseInstancesAndDisplayActualDates() {
    provider.instances += instance(1, "Clase de español", "2026-11-19", 9, calendarId = 1)
    provider.instances += instance(2, "Turno médico", "2026-11-20", 14, calendarId = 2)
    val tomorrow = store.agenda(selection("¿Qué tengo mañana?"))
    assertTrue(tomorrow.contains("19/11/2026 09:00 a 10:00 · Clase de español"))
    assertFalse(tomorrow.contains("Turno médico"))
    val week = store.agenda(selection("Mostrame mi agenda de esta semana"))
    assertTrue(week.contains("Clase de español"))
    assertTrue(week.contains("20/11/2026 14:00 a 15:00 · Turno médico"))
  }

  @Test fun everydayTodayQuestionReadsTodaysEntriesOnly() {
    provider.instances += instance(1, "Plaza", "2026-11-18", 17)
    provider.instances += instance(2, "Turno médico", "2026-11-19", 14)
    val agenda = store.agenda(selection("qué tengo hoy en la agenda?"))
    assertTrue(agenda.contains("18/11/2026 17:00 a 18:00 · Plaza"))
    assertFalse(agenda.contains("Turno médico"))
    assertEquals(0, provider.writes)
  }

  @Test fun recurringInstancesAndDuplicateLabelsFromDifferentCalendarsRemainSelectable() {
    provider.instances += instance(1, "Reunión de equipo", "2026-11-20", 9, calendarId = 1, recurring = true)
    provider.instances += instance(1, "Reunión de equipo", "2026-11-27", 9, calendarId = 1, recurring = true)
    provider.instances += instance(2, "Reunión de equipo", "2026-11-20", 9, calendarId = 2)
    val matches = store.find(selection("Cancelá la reunión del viernes"))
    assertEquals(listOf(1L, 2L), matches.map { it.calendarId })
    assertTrue(matches.first().series)
    assertFalse(matches.last().series)
    assertEquals(0, provider.writes)
    assertEquals(1, store.agenda(selection("¿Cuándo es mi próxima reunión?")).lines().size)
  }

  @Test fun emptyAndUnavailableProviderAreDistinct() {
    assertTrue(store.agenda(selection("¿Qué tengo mañana?")).contains("No encontré"))
    provider.unavailable = true
    assertThrows(IllegalStateException::class.java) { store.agenda(selection("¿Qué tengo mañana?")) }
  }

  @Test fun creationRetainsDescriptionAndExplicitDurationInReviewIntent() {
    val command = (parser.parse("Creá una clase de español el jueves a las nueve de la mañana durante dos horas, con descripción: Llevar el libro") as PhoneRoute.Local).command as PhoneCommand.CalendarEvent
    val spec = checkNotNull(NativeIntentSpec.forCommand(command))
    assertEquals("Llevar el libro", spec.stringExtras["description"])
    assertEquals(7_200_000L, command.endMillis - command.beginMillis)
    assertEquals("android.intent.action.INSERT", spec.action)
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
  }

  @Test fun updateRetainsDurationAndWritesOnlyChangedColumns() {
    val row = instance(1, "Reunión", "2026-11-20", 9)
    provider.instances += row
    val request = selection("Pasá la reunión del viernes a las 18:00")
    val event = store.find(request).single()
    val change = store.change(event, request)
    assertEquals(0, provider.writes)
    assertEquals(event.end - event.begin, change.end - change.begin)
    provider.beforeWrite = event
    assertTrue(store.update(event, change))
    assertEquals(setOf(Events.DTSTART, Events.DTEND), provider.values!!.keySet())
    val duration = selection("Hacé que la reunión dure dos horas")
    provider.beforeWrite = event.copy(begin = change.begin, end = change.end)
    val durationChange = store.change(provider.beforeWrite!!, duration)
    assertTrue(store.update(provider.beforeWrite!!, durationChange))
    assertEquals(setOf(Events.DTEND), provider.values!!.keySet())
  }

  @Test fun deletionRequiresSelectedSnapshotAndChecksProviderResult() {
    provider.instances += instance(3, "Turno médico", "2026-11-19", 14)
    val event = store.find(selection("Borrá el turno médico de mañana")).single()
    assertEquals(0, provider.writes)
    provider.beforeWrite = event
    assertTrue(store.delete(event))
    assertFalse(store.delete(event))
    assertEquals(1, provider.writes)
  }

  @Test fun deletionWithUnavailableVerificationDoesNotRepeatTheWrite() {
    provider.instances += instance(5, "Turno médico", "2026-11-19", 14)
    val event = store.find(selection("Borrá el turno médico de mañana")).single()
    provider.beforeWrite = event
    provider.unverified = true
    assertFalse(store.delete(event))
    assertEquals(1, provider.writes)
    assertFalse(store.delete(event))
    assertEquals(1, provider.writes)
  }

  @Test fun readOnlyOrPermissionFailureDoesNotChangeAnything() {
    provider.instances += instance(1, "Reunión", "2026-11-20", 9)
    val event = store.find(selection("Cancelá la reunión del viernes")).single()
    provider.writable = false
    assertThrows(IllegalArgumentException::class.java) { store.delete(event) }
    assertEquals(0, provider.writes)
    provider.denied = true
    assertThrows(SecurityException::class.java) { store.find(selection("¿Qué tengo mañana?")) }
  }

  @Test fun datesCrossDstAsCivilDays() {
    val eastern = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply { clear(); set(2026, Calendar.MARCH, 7, 12, 0) }
    val route = PhoneCommandParser { eastern }.parse("¿Qué tengo mañana?") as PhoneRoute.Local
    val (start, end) = CalendarSelection.decode(route.command as PhoneCommand.Platform).bounds(eastern)
    assertEquals(23 * 3_600_000L, end - start)
  }

  @Test fun recurringCancellationInsertsExceptionForOneInstanceAndCannotRepeat() {
    provider.instances += instance(1, "Reunión", "2026-11-20", 9, recurring = true)
    provider.instances += instance(1, "Reunión", "2026-11-27", 9, recurring = true)
    val request = selection("Cancelá la reunión del viernes")
    val event = store.find(request).single()
    assertTrue(store.changeOccurrence(event, request, null))
    assertEquals(event.begin, provider.values?.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
    assertEquals(Events.STATUS_CANCELED, provider.values?.getAsInteger(Events.STATUS))
    assertFalse(store.changeOccurrence(event, request, null))
    assertEquals(1, provider.instances.size)
    assertEquals(1, provider.writes)
  }

  private fun selection(text: String): CalendarSelection {
    val command = (parser.parse(text) as PhoneRoute.Local).command as PhoneCommand.Platform
    return CalendarSelection.decode(PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)) as PhoneCommand.Platform)
  }

  private fun instance(id: Long, title: String, date: String, hour: Int, calendarId: Long = 1, recurring: Boolean = false): Array<Any?> {
    val start = CalendarSelection.day(date, now).apply { set(Calendar.HOUR_OF_DAY, hour) }.timeInMillis
    return arrayOf(id, title, start, start + 3_600_000L, 0, calendarId, "Calendario $calendarId",
      Calendars.CAL_ACCESS_OWNER, "FREQ=WEEKLY".takeIf { recurring }, null, null, null)
  }

  private class CalendarProvider : ContentProvider() {
    val instances = mutableListOf<Array<Any?>>()
    var beforeWrite: DeviceCalendar.Occurrence? = null
    var values: ContentValues? = null
    var writes = 0
    var writable = true
    var unavailable = false
    var unverified = false
    var denied = false
    private var deleted = false
    private var originalId = 0L

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
      if (denied) throw SecurityException("simulated permission")
      if (unavailable || unverified && deleted && uri.pathSegments[0] == "events") return null
      val columns = checkNotNull(projection)
      return MatrixCursor(columns).apply {
        when (uri.pathSegments[0]) {
          "instances" -> instances.forEach { addRow(it) }
          "calendars" -> addRow(arrayOf(if (writable) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ))
          "events" -> when {
            uri.lastPathSegment == "99" -> addRow(arrayOf<Any?>(originalId, values!!.getAsLong(Events.ORIGINAL_INSTANCE_TIME), Events.STATUS_CANCELED, null, null))
            selection != null -> Unit
            !deleted && beforeWrite != null -> addRow(arrayOf(values?.getAsLong(Events.DTSTART) ?: beforeWrite!!.begin,
              values?.getAsLong(Events.DTEND) ?: beforeWrite!!.end, 0))
          }
        }
      }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
      check(uri.pathSegments[0] == "exception")
      this.values = ContentValues(values)
      originalId = ContentUris.parseId(uri)
      instances.removeAll { it[0] == originalId && it[2] == values!!.getAsLong(Events.ORIGINAL_INSTANCE_TIME) }
      writes++
      return ContentUris.withAppendedId(Events.CONTENT_URI, 99)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
      check(uri == Events.CONTENT_URI && selectionArgs?.first() == beforeWrite?.id.toString())
      this.values = ContentValues(values)
      writes++
      return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
      check(uri == Events.CONTENT_URI && selectionArgs?.first() == beforeWrite?.id.toString())
      if (deleted) return 0
      writes++
      deleted = true
      return 1
    }
  }
}
