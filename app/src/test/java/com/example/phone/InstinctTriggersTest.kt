package com.example.phone

import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Triggers by state and recurring orders (1.17.0): the grammar, the occurrences, expiry, events and the store. */
class InstinctTriggersTest {
  private val parser = PhoneCommandParser()
  private val zone: TimeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
  /** Saturday 2026-11-21 at 10:00 in Buenos Aires. */
  private val now: Calendar = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, 21, 10, 0, 0) }
  private fun check(order: String, at: Calendar = now) = InstinctProposalValidator.check(order, parser, at)
  private fun valid(order: String) = check(order) as ProposalCheck.Valid
  private fun invalid(order: String) = assertTrue(order, check(order) is ProposalCheck.Invalid)
  private fun local(y: Int, m: Int, d: Int, h: Int, min: Int, z: TimeZone = zone) =
    Calendar.getInstance(z).apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

  // The grammar

  @Test fun `each state and event trigger, with its inner order checked untouched`() {
    val headphones = valid("al conectar auriculares: abrir spotify")
    assertEquals(Trigger.HeadphonesOn, headphones.deferred!!.trigger)
    assertEquals(PhoneCommand.OpenApp("spotify"), headphones.command)
    assertEquals("12 hours by default", now.timeInMillis + 12 * 3_600_000L, headphones.deferred!!.expiresAt)
    assertEquals(Trigger.HeadphonesOff, valid("al desconectar auriculares: pausar la música").deferred!!.trigger)
    assertEquals(Trigger.HeadphonesOff, valid("al desconectar los auriculares: pausar la música").deferred!!.trigger)
    assertEquals(Trigger.Unlock, valid("al desbloquear: mostrar las alarmas").deferred!!.trigger)
    assertEquals(Trigger.EventEnd(), valid("al terminar el evento: desactivar no molestar").deferred!!.trigger)
    assertEquals(Trigger.EventStart(), valid("al empezar el próximo evento: activar no molestar").deferred!!.trigger)
    assertEquals(Trigger.EventStart(), valid("Al empezar el proximo evento: activar no molestar").deferred!!.trigger)
    assertTrue("event triggers read the calendar when stored", valid("al terminar el evento: desactivar no molestar").deferred!!.trigger.unresolved)
    invalid("al conectar auriculares: llamar a Ana")
    invalid("al desbloquear: a las 22:00: prender la linterna")
  }

  @Test fun `hasta las HH MM sets the expiry, at most 24 hours, and a bad one is refused`() {
    val until = valid("al conectar auriculares hasta las 20:00: abrir spotify").deferred!!
    assertEquals(local(2026, 11, 21, 20, 0), until.expiresAt)
    assertEquals("al conectar auriculares, hasta las 20:00", until.label)
    assertEquals("a time that passed is tomorrow", local(2026, 11, 22, 9, 0),
      valid("al desbloquear hasta las 09:00: mostrar las alarmas").deferred!!.expiresAt)
    assertEquals(local(2026, 11, 22, 10, 0), valid("al desbloquear hasta las 10:00: mostrar las alarmas").deferred!!.expiresAt)
    invalid("al conectar auriculares hasta las 25:00: abrir spotify")
    invalid("al conectar auriculares hasta las 7: abrir spotify")
    invalid("al conectar auriculares hasta el 15/12: abrir spotify")
  }

  @Test fun `one condition per line, never two`() {
    invalid("al conectar auriculares y al desbloquear: abrir spotify")
    invalid("al conectar auriculares si llueve: abrir spotify")
    invalid("al conectar auriculares o al desbloquear: abrir spotify")
    invalid("todos los días a las 06:30 si llueve y hace menos de 10 grados hasta el 15/12: poner una alarma a las 06:45")
    invalid("a las 06:30 si llueve y hace frío: poner una alarma a las 06:45")
  }

  @Test fun `a recurrence needs hasta el DD MM within 30 days`() {
    val daily = valid("todos los días a las 07:30 hasta el 15/12: poner el volumen al 20 por ciento").deferred!!
    assertEquals(Trigger.Recurring(RecurrenceDays.DAILY, 7 * 60 + 30, local(2026, 12, 16, 0, 0)), daily.trigger)
    assertEquals(local(2026, 11, 22, 7, 30), daily.fireAt)
    assertEquals("todos los días 07:30 hasta el 15/12, próxima mañana", daily.label)
    invalid("todos los días a las 07:30: poner el volumen al 20 por ciento")
    invalid("los días hábiles a las 08:00: activar no molestar")
    invalid("los fines de semana a las 10:00 hasta el 31/12: pausar la música")
    invalid("todos los días a las 07:30 hasta el 30/02: poner el volumen al 20 por ciento")
    invalid("todos los días a las 25:30 hasta el 15/12: poner el volumen al 20 por ciento")
    // The 30th day after today is allowed, the 31st is not.
    valid("todos los días a las 07:30 hasta el 21/12: poner el volumen al 20 por ciento")
    invalid("todos los días a las 07:30 hasta el 22/12: poner el volumen al 20 por ciento")
    // A date that passed this year is next year, so more than 30 days.
    invalid("todos los días a las 07:30 hasta el 20/11: poner el volumen al 20 por ciento")
  }

  @Test fun `a recurrence takes the weather condition, and its label says it`() {
    val rain = valid("todos los días a las 06:30 si llueve hasta el 15/12: poner una alarma a las 06:45").deferred!!
    assertEquals(WeatherCondition.Rain(true), rain.condition)
    assertEquals("todos los días 06:30 si llueve hasta el 15/12, próxima mañana", rain.label)
    val weekdays = valid("los días hábiles a las 08:00 hasta el 15/12: activar no molestar").deferred!!
    assertEquals("Saturday: the next weekday is Monday", local(2026, 11, 23, 8, 0), weekdays.fireAt)
    assertEquals("días hábiles 08:00 hasta el 15/12, próxima el 23/11", weekdays.label)
  }

  @Test fun `immediate orders keep their meaning`() {
    val alarm = valid("poner una alarma a las 07:00")
    assertNull(alarm.deferred)
    assertEquals(PhoneCommand.Alarm(7, 0), alarm.command)
    assertNull(valid("poner una alarma a las 07:00 todos los días").deferred)
    assertNull(valid("abrir spotify").deferred)
  }

  @Test fun `every new example is valid on 21 11`() {
    InstinctProposalValidator.examples.filter { it.startsWith("al ") || it.contains(" hasta el ") }.also { assertEquals(9, it.size) }
      .forEach { assertTrue(it, check(it) is ProposalCheck.Valid) }
  }

  // Occurrences

  @Test fun `next occurrences of each recurrence across a week`() {
    val until = local(2026, 12, 31, 0, 0)
    val friday = local(2026, 11, 27, 9, 0)
    assertEquals(local(2026, 11, 28, 8, 0), InstinctTriggers.next(RecurrenceDays.DAILY, 480, friday, until, zone))
    assertEquals("weekdays from Friday to Monday", local(2026, 11, 30, 8, 0), InstinctTriggers.next(RecurrenceDays.WEEKDAYS, 480, friday, until, zone))
    assertEquals(local(2026, 11, 28, 8, 0), InstinctTriggers.next(RecurrenceDays.WEEKENDS, 480, friday, until, zone))
    val sunday = local(2026, 11, 29, 9, 0)
    assertEquals("weekends from Sunday to the next Saturday", local(2026, 12, 5, 8, 0), InstinctTriggers.next(RecurrenceDays.WEEKENDS, 480, sunday, until, zone))
    assertEquals("the same day when the hour has not come", local(2026, 11, 27, 10, 0), InstinctTriggers.next(RecurrenceDays.WEEKDAYS, 600, friday, until, zone))
    assertEquals("exactly the hour is the next one", local(2026, 11, 30, 9, 0), InstinctTriggers.next(RecurrenceDays.WEEKDAYS, 540, friday, until, zone))
    assertNull("none before until", InstinctTriggers.next(RecurrenceDays.WEEKDAYS, 480, friday, local(2026, 11, 30, 0, 0), zone))
  }

  @Test fun `a recurrence is a local time, also after a time zone change`() {
    val madrid = TimeZone.getTimeZone("Europe/Madrid")
    val after = local(2026, 11, 21, 10, 0, madrid)
    val next = InstinctTriggers.next(RecurrenceDays.DAILY, 480, after, after + 30L * 86_400_000L, madrid)!!
    assertEquals(local(2026, 11, 22, 8, 0, madrid), next)
    assertNotEquals("08:00 in Madrid is not 08:00 in Buenos Aires", local(2026, 11, 22, 8, 0), next)
    val c = Calendar.getInstance(madrid).apply { timeInMillis = next }
    assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
    // Across a daylight saving change (Madrid, 29/03/2026), still 08:00.
    val spring = InstinctTriggers.next(RecurrenceDays.DAILY, 480, local(2026, 3, 28, 9, 0, madrid), local(2026, 4, 10, 0, 0, madrid), madrid)!!
    assertEquals(local(2026, 3, 29, 8, 0, madrid), spring)
    assertEquals(8, Calendar.getInstance(madrid).apply { timeInMillis = spring }.get(Calendar.HOUR_OF_DAY))
  }

  // Events

  @Test fun `the event in progress or the next one, never an all-day one`() {
    val t = now.timeInMillis
    val events = listOf(CalendarInstance(1, 10, t - 3_600_000, t + 86_400_000, allDay = true),
      CalendarInstance(2, 20, t - 1_800_000, t + 1_800_000), CalendarInstance(3, 30, t + 7_200_000, t + 10_800_000))
    val expiry = t + InstinctTriggers.DEFAULT_EXPIRY_MILLIS
    assertEquals(InstinctTriggers.Pick.Found(Trigger.EventEnd(20, 2, t + 1_800_000), t + 1_800_000),
      InstinctTriggers.pickEvent(Trigger.EventEnd(), events, t, expiry))
    assertEquals(InstinctTriggers.Pick.Found(Trigger.EventStart(30, 3, t + 7_200_000), t + 7_200_000),
      InstinctTriggers.pickEvent(Trigger.EventStart(), events, t, expiry))
    assertTrue(InstinctTriggers.pickEvent(Trigger.EventEnd(), events.take(1), t, expiry) is InstinctTriggers.Pick.None)
    assertTrue("beyond 12 hours", InstinctTriggers.pickEvent(Trigger.EventStart(), listOf(CalendarInstance(4, 40, t + 13 * 3_600_000L, t + 14 * 3_600_000L)), t, t + 24 * 3_600_000L) is InstinctTriggers.Pick.None)
    assertTrue("after the cap", InstinctTriggers.pickEvent(Trigger.EventEnd(), events, t, t + 600_000) is InstinctTriggers.Pick.None)
  }

  @Test fun `a fired event is the same, moved or deleted`() {
    val trigger = Trigger.EventEnd(20, 2, 1_000_000L)
    assertEquals(InstinctTriggers.EventCheck.Same, InstinctTriggers.matchEvent(trigger, listOf(CalendarInstance(2, 20, 0, 1_030_000L))))
    assertEquals(InstinctTriggers.EventCheck.Moved(2_800_000L), InstinctTriggers.matchEvent(trigger, listOf(CalendarInstance(2, 20, 0, 2_800_000L))))
    assertEquals("a new instance id of the same event", InstinctTriggers.EventCheck.Moved(2_800_000L),
      InstinctTriggers.matchEvent(trigger, listOf(CalendarInstance(99, 20, 0, 2_800_000L))))
    assertEquals(InstinctTriggers.EventCheck.Deleted, InstinctTriggers.matchEvent(trigger, listOf(CalendarInstance(2, 21, 0, 1_000_000L))))
    assertEquals(InstinctTriggers.EventCheck.Deleted, InstinctTriggers.matchEvent(trigger, emptyList()))
    assertEquals(InstinctTriggers.EventCheck.Same, InstinctTriggers.matchEvent(Trigger.EventStart(20, 2, 500L), listOf(CalendarInstance(2, 20, 500L, 9_000L))))
  }

  // Expiry and state

  @Test fun `a state trigger's alarm is its expiry, and any trigger past it expires`() {
    fun order(trigger: Trigger, expiresAt: Long?) = ScheduledOrder("i", "o", "abrir spotify", 5_000L, "f", null, "l", 0L, trigger = trigger, expiresAt = expiresAt)
    assertEquals(InstinctTriggers.AlarmAction.EXPIRE, InstinctTriggers.onAlarm(order(Trigger.HeadphonesOn, 5_000L), 5_000L))
    assertEquals(InstinctTriggers.AlarmAction.FIRE, InstinctTriggers.onAlarm(order(Trigger.EventEnd(1, 1, 5_000L), 9_000L), 5_000L))
    assertEquals(InstinctTriggers.AlarmAction.EXPIRE, InstinctTriggers.onAlarm(order(Trigger.EventEnd(1, 1, 5_000L), 9_000L), 9_001L))
    assertEquals(InstinctTriggers.AlarmAction.FIRE, InstinctTriggers.onAlarm(order(Trigger.At, null), 50_000L))
    assertTrue(InstinctTriggers.fires(Trigger.HeadphonesOn, PhoneEvent.HEADPHONES_ON))
    assertFalse(InstinctTriggers.fires(Trigger.HeadphonesOn, PhoneEvent.HEADPHONES_OFF))
    assertFalse(InstinctTriggers.fires(Trigger.At, PhoneEvent.UNLOCK))
    assertTrue(InstinctTriggers.fires(Trigger.Unlock, PhoneEvent.UNLOCK))
  }

  // The store

  /** What 1.16.0 wrote: a bare array, no trigger, no version. */
  private val v116 = """[{"id":"a1b2","order":"a las 22:00: poner el volumen al 10 por ciento","inner":"poner el volumen al 10 por ciento",
    "fireAt":1790000000000,"fingerprint":"huella","label":"a las 22:00","createdAt":1789990000000,"token":"tok","state":"PENDING",
    "condition":"rain","message":"3fa94c0b12de","source":"BUZON"},
    {"id":"c3d4","order":"en 5 minutos: prender la linterna","inner":"prender la linterna","fireAt":1790000300000,"fingerprint":"huella",
    "label":"a las 22:05","createdAt":1789990000000,"token":"tok2","state":"CONSUMED"}]"""

  @Test fun `the 1_16_0 file migrates without losing anything`() {
    val orders = InstinctScheduleStore.decode(v116)
    assertEquals(2, orders.size)
    val first = orders[0]
    assertEquals(Trigger.At, first.trigger)
    assertEquals(WeatherCondition.Rain(true), first.condition)
    assertEquals("3fa94c0b12de", first.message)
    assertEquals(ProposalSource.BUZON, first.source)
    assertEquals(1_790_000_000_000L, first.fireAt)
    assertNull(first.expiresAt)
    assertEquals(first.id, first.series)
    assertEquals(ScheduledOrder.State.CONSUMED, orders[1].state)
    val written = InstinctScheduleStore.encode(orders)
    assertEquals(InstinctScheduleStore.VERSION, JSONObject(written).getInt("version"))
    assertEquals(orders, InstinctScheduleStore.decode(written))
    // Through the store: read as 1.16.0 left it, written back in the new format.
    val file = Files.createTempDirectory("insto-v116").resolve("instinct-scheduled.json").toFile().apply { writeText(v116) }
    val store = InstinctScheduleStore(file) { 1_789_995_000_000L }
    assertEquals(listOf("a1b2"), store.pending().map { it.id })
    assertNotNull(store.consume("a1b2", "tok"))
    assertEquals(2, JSONObject(file.readText()).getInt("version"))
    assertEquals(2, store.read().size)
  }

  @Test fun `every trigger round-trips`() {
    listOf(Trigger.At, Trigger.EventEnd(1, 2, 3), Trigger.EventStart(4, 5, 6), Trigger.HeadphonesOn, Trigger.HeadphonesOff, Trigger.Unlock,
      Trigger.Recurring(RecurrenceDays.WEEKENDS, 600, 99L)).forEach { assertEquals(it, InstinctTriggers.decode(InstinctTriggers.encode(it))) }
    assertThrows(IllegalArgumentException::class.java) { InstinctTriggers.decode(JSONObject().put("kind", "teletransporte")) }
  }

  @Test fun `consuming an occurrence writes the next one, and the series cancels whole`() {
    val t = local(2026, 11, 22, 7, 30)
    var clock = t
    val file = Files.createTempDirectory("insto-recurring").resolve("instinct-scheduled.json").toFile()
    val store = InstinctScheduleStore(file) { clock }
    val r = Trigger.Recurring(RecurrenceDays.DAILY, 450, local(2026, 11, 25, 0, 0))
    val first = ScheduledOrder("s1", "todos…", "poner el volumen al 20 por ciento", t, "f", null, "l", t - 60_000, trigger = r)
    clock = t - 60_000
    assertTrue(store.add(first) is InstinctScheduleStore.Add.Added)
    clock = t + 20 * 60_000L
    val consumed = store.consumeOccurrence("s1", first.token, clock, zone)!!
    assertEquals(ScheduledOrder.State.CONSUMED, consumed.order.state)
    val next = consumed.successor!!
    assertEquals(local(2026, 11, 23, 7, 30), next.fireAt)
    assertEquals("s1", next.series)
    assertNotEquals(first.token, next.token)
    assertEquals(first.shortId, next.shortId)
    assertNull("a second delivery finds nothing", store.consumeOccurrence("s1", first.token, clock, zone))
    // A process death: this occurrence is uncertain, never repeated; the next one is still pending.
    assertEquals(listOf("s1"), store.recoverInterrupted().map { it.id })
    assertEquals(listOf(next.id), store.pending().map { it.id })
    // The last occurrence has no successor.
    val last = store.consumeOccurrence(next.id, next.token, local(2026, 11, 23, 7, 31), zone)!!
    assertEquals(local(2026, 11, 24, 7, 30), last.successor!!.fireAt)
    assertNull(store.consumeOccurrence(last.successor!!.id, null, local(2026, 11, 24, 7, 31), zone)!!.successor)
    // Cancelling by short id.
    clock = t
    val other = first.copy(id = "s2", series = "s2", token = "x", fireAt = t + 60_000)
    store.add(other)
    assertEquals(listOf("s2"), store.cancelShort(other.shortId).map { it.id })
    assertTrue(store.cancelShort("ffffff").isEmpty())
  }

  @Test fun `at most 5 recurrences, apart from the 10 scheduled`() {
    val t = now.timeInMillis
    val store = InstinctScheduleStore(Files.createTempDirectory("insto-limit").resolve("s.json").toFile()) { t }
    val r = Trigger.Recurring(RecurrenceDays.DAILY, 450, t + 10 * 86_400_000L)
    repeat(5) { assertTrue(store.add(ScheduledOrder("r$it", "o", "i", t + 3_600_000, "f", null, "l", t, trigger = r)) is InstinctScheduleStore.Add.Added) }
    assertEquals(InstinctScheduleStore.Add.FullRecurring, store.add(ScheduledOrder("r6", "o", "i", t + 3_600_000, "f", null, "l", t, trigger = r)))
    repeat(10) { assertTrue(store.add(ScheduledOrder("a$it", "o", "i", t + 60_000, "f", null, "l", t)) is InstinctScheduleStore.Add.Added) }
    assertEquals(InstinctScheduleStore.Add.Full, store.add(ScheduledOrder("a11", "o", "i", t + 60_000, "f", null, "l", t)))
    assertEquals("a recurrence may start more than 24 hours ahead", true,
      store.cancelShort(InstinctTriggers.shortId("r0")).isNotEmpty() &&
        store.add(ScheduledOrder("r7", "o", "i", t + 3 * 86_400_000L, "f", null, "l", t, trigger = r)) is InstinctScheduleStore.Add.Added)
    assertEquals(InstinctScheduleStore.Add.OutOfRange,
      store.add(ScheduledOrder("r8", "o", "i", t + 11 * 86_400_000L, "f", null, "l", t, trigger = r)))
  }

  // Queries

  @Test fun `cancelar programada takes a 6-character id and cancelar programadas still cancels all`() {
    assertEquals(ProposalCheck.Query("cancelar programada a1b2c3", ProposalCheck.QueryKind.CANCEL_ONE, "a1b2c3"), check("cancelar programada a1b2c3"))
    assertEquals(ProposalCheck.QueryKind.CANCEL_SCHEDULED, (check("cancelar programadas") as ProposalCheck.Query).kind)
    invalid("cancelar programada a1b2")
    invalid("cancelar programada zzzzzz")
    assertEquals(6, InstinctTriggers.shortId("cualquier-serie").length)
  }

  @Test fun `labels say the trigger in words`() {
    val t = now.timeInMillis
    assertEquals("al conectar auriculares, hasta las 20:00", InstinctTriggers.label(Trigger.HeadphonesOn, local(2026, 11, 21, 20, 0), 0, t, zone))
    assertEquals("días hábiles 08:00 hasta el 15/12, próxima hoy",
      InstinctTriggers.label(Trigger.Recurring(RecurrenceDays.WEEKDAYS, 480, local(2026, 12, 16, 0, 0)), null, local(2026, 11, 21, 18, 0), t, zone))
    assertEquals("al terminar el evento (11:00), hasta las 22:00",
      InstinctTriggers.label(Trigger.EventEnd(1, 1, local(2026, 11, 21, 11, 0)), local(2026, 11, 21, 22, 0), 0, t, zone))
  }
}
