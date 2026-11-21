package com.example.phone

import com.example.phone.weather.SmnWeatherReply
import com.example.phone.weather.WeatherQueries
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Deferred orders, weather conditions and undo: the pure part. No Android. */
class InstinctScheduleTest {
  private val parser = PhoneCommandParser()
  /** Friday 2026-11-20 at 21:00 local time. */
  private val now: Calendar = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 20, 21, 0, 0); set(Calendar.MILLISECOND, 0) }
  private fun check(order: String) = InstinctProposalValidator.check(order, parser, now)
  private fun at(hour: Int, minute: Int, dayOffset: Int = 0) = (now.clone() as Calendar).apply {
    add(Calendar.DAY_OF_MONTH, dayOffset); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
  }.timeInMillis

  // The wrapper

  @Test fun `a las HH MM with the colon defers the inner order, checked untouched`() {
    val valid = check("a las 22:00: poner el volumen al 10 por ciento") as ProposalCheck.Valid
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 10), valid.command)
    assertEquals(Deferred("poner el volumen al 10 por ciento", at(22, 0), null, "a las 22:00"), valid.deferred)
  }

  @Test fun `without the colon the line keeps its meaning as an ordinary order`() {
    val alarm = check("poner una alarma a las 07:00") as ProposalCheck.Valid
    assertNull(alarm.deferred)
    assertEquals(PhoneCommand.Alarm(7, 0), alarm.command)
    assertTrue(check("a las 22:00 poner el volumen al 10 por ciento") !is ProposalCheck.Valid)
    assertTrue(check("en 30 minutos apagar la linterna") !is ProposalCheck.Valid)
  }

  @Test fun `a time that already passed today is tomorrow`() {
    assertEquals(at(6, 30, dayOffset = 1), (check("a las 06:30: prender la linterna") as ProposalCheck.Valid).deferred!!.fireAt)
    assertEquals("the very minute is tomorrow too", at(21, 0, dayOffset = 1), (check("a las 21:00: prender la linterna") as ProposalCheck.Valid).deferred!!.fireAt)
    assertEquals(at(21, 1), (check("a las 21:01: prender la linterna") as ProposalCheck.Valid).deferred!!.fireAt)
  }

  @Test fun `en N minutos or horas, from 1 minute to 24 hours`() {
    assertEquals(now.timeInMillis + 60_000, (check("en 1 minuto: apagar la linterna") as ProposalCheck.Valid).deferred!!.fireAt)
    val inHours = (check("en 24 horas: apagar la linterna") as ProposalCheck.Valid).deferred!!
    assertEquals(now.timeInMillis + 86_400_000, inHours.fireAt)
    assertEquals("a las 21:00", inHours.label)
    assertEquals("a las 21:30", (check("en 30 minutos: apagar la linterna") as ProposalCheck.Valid).deferred!!.label)
    for (order in listOf("en 0 minutos: apagar la linterna", "en 25 horas: apagar la linterna", "en 1441 minutos: apagar la linterna",
      "a las 24:00: apagar la linterna", "a las 07:60: apagar la linterna"))
      assertTrue(order, check(order) is ProposalCheck.Invalid)
  }

  @Test fun `an invalid inner order makes the whole line invalid, with its reason`() {
    assertEquals(ProposalCheck.Reason.FORBIDDEN, (check("a las 22:00: llamar a Ana") as ProposalCheck.Invalid).reason)
    assertEquals(ProposalCheck.Reason.NOT_LOCAL, (check("en 5 minutos: poneme algo lindo") as ProposalCheck.Invalid).reason)
    assertTrue("no nesting", check("a las 22:00: en 5 minutos: apagar la linterna") is ProposalCheck.Invalid)
    assertTrue("no queries inside", check("en 5 minutos: ver catálogo") is ProposalCheck.Invalid)
  }

  // Conditions

  @Test fun `each weather condition, only with a clock time and only one`() {
    fun condition(order: String) = (check(order) as ProposalCheck.Valid).deferred!!.condition
    assertEquals(WeatherCondition.Rain(true), condition("a las 06:30 si llueve: prender la linterna"))
    assertEquals(WeatherCondition.Rain(false), condition("a las 06:30 si no llueve: prender la linterna"))
    assertEquals(WeatherCondition.Below(10), condition("a las 06:30 si hace menos de 10 grados: prender la linterna"))
    assertEquals(WeatherCondition.Above(30), condition("a las 06:30 si hace más de 30 grados: prender la linterna"))
    assertEquals(WeatherCondition.Above(30), condition("a las 06:30 si hace mas de 30 grados: prender la linterna"))
    assertEquals("a las 06:30 si llueve", (check("a las 06:30 si llueve: prender la linterna") as ProposalCheck.Valid).deferred!!.label)
    for (order in listOf("a las 06:30 si llueve y hace menos de 10 grados: prender la linterna",
      "a las 06:30 si llueve o nieva: prender la linterna", "a las 06:30 si hay sol: prender la linterna",
      "en 30 minutos si llueve: prender la linterna", "si llueve: prender la linterna"))
      assertTrue(order, check(order) !is ProposalCheck.Valid)
  }

  @Test fun `rain needs 50 percent for sure, and an ambiguous range is no data`() {
    val rain = WeatherCondition.Rain(true)
    val dry = WeatherCondition.Rain(false)
    assertEquals(50, WeatherConditions.RAIN_THRESHOLD_PERCENT)
    assertEquals(WeatherConditions.Verdict.MET, WeatherConditions.evaluate(rain, null, PercentRange(50, 70)))
    assertEquals(WeatherConditions.Verdict.NOT_MET, WeatherConditions.evaluate(rain, null, PercentRange(10, 40)))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.evaluate(rain, null, PercentRange(40, 70)))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.evaluate(rain, null, null))
    assertEquals(WeatherConditions.Verdict.MET, WeatherConditions.evaluate(dry, null, PercentRange(0, 10)))
    assertEquals(WeatherConditions.Verdict.NOT_MET, WeatherConditions.evaluate(dry, null, PercentRange(60, 60)))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.evaluate(dry, null, PercentRange(40, 70)))
  }

  @Test fun `temperature is strict and missing data is unknown`() {
    assertEquals(WeatherConditions.Verdict.MET, WeatherConditions.evaluate(WeatherCondition.Below(10), BigDecimal("9.9"), null))
    assertEquals(WeatherConditions.Verdict.NOT_MET, WeatherConditions.evaluate(WeatherCondition.Below(10), BigDecimal("10"), null))
    assertEquals(WeatherConditions.Verdict.MET, WeatherConditions.evaluate(WeatherCondition.Above(30), BigDecimal("30.1"), null))
    assertEquals(WeatherConditions.Verdict.NOT_MET, WeatherConditions.evaluate(WeatherCondition.Above(30), BigDecimal("30"), null))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.evaluate(WeatherCondition.Above(30), null, PercentRange(0, 0)))
  }

  /** A fake of the weather boundary: numbers, or nothing, or a hang. */
  private class FakeWeather(val temperature: BigDecimal? = null, val rain: PercentRange? = null, val hangMillis: Long = 0, val fail: Boolean = false) : WeatherQueries {
    var calls = 0
    override suspend fun answer(command: PhoneCommand.Platform) = SmnWeatherReply("no se usa", answered = false)
    override suspend fun currentTemperature(): BigDecimal? { calls++; if (hangMillis > 0) delay(hangMillis); if (fail) error("red"); return temperature }
    override suspend fun rainChance(): PercentRange? { calls++; if (hangMillis > 0) delay(hangMillis); if (fail) error("red"); return rain }
  }

  @Test fun `each condition with data present, absent, failing and timing out`() = runTest {
    val below = WeatherCondition.Below(10)
    val rain = WeatherCondition.Rain(true)
    assertEquals(WeatherConditions.Verdict.MET, WeatherConditions.checkWithin(below, FakeWeather(temperature = BigDecimal("8")), 20_000))
    assertEquals(WeatherConditions.Verdict.MET, WeatherConditions.checkWithin(rain, FakeWeather(rain = PercentRange(60, 80)), 20_000))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.checkWithin(below, FakeWeather(), 20_000))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.checkWithin(rain, FakeWeather(), 20_000))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.checkWithin(rain, FakeWeather(rain = PercentRange(90, 90), fail = true), 20_000))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.checkWithin(below, FakeWeather(temperature = BigDecimal("1"), hangMillis = 60_000), 20_000))
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.checkWithin(rain, FakeWeather(rain = PercentRange(90, 90), hangMillis = 60_000), 20_000))
    // A replacement that only answers text has no numbers: unavailable, never a default.
    val textOnly = WeatherQueries { SmnWeatherReply("Según el SMN, hay 5 grados.", answered = true) }
    assertEquals(WeatherConditions.Verdict.UNKNOWN, WeatherConditions.checkWithin(below, textOnly, 20_000))
  }

  // The store

  private fun store(clock: () -> Long = { now.timeInMillis }): InstinctScheduleStore {
    val dir = Files.createTempDirectory("insto-scheduled").toFile()
    return InstinctScheduleStore(File(dir, "instinct-scheduled.json"), clock)
  }

  private fun order(id: String, fireAt: Long = now.timeInMillis + 3_600_000, fingerprint: String = "f", inner: String = "prender la linterna",
    condition: WeatherCondition? = null) =
    ScheduledOrder(id, "en 60 minutos: $inner", inner, fireAt, fingerprint, condition, "a las 22:00", now.timeInMillis)

  @Test fun `at most ten scheduled and never more than 24 hours ahead`() {
    val store = store()
    (1..10).forEach { assertTrue(store.add(order("o$it")) is InstinctScheduleStore.Add.Added) }
    assertEquals(InstinctScheduleStore.Add.Full, store.add(order("o11")))
    assertEquals(10, store.pending().size)
    val other = store()
    assertEquals(InstinctScheduleStore.Add.OutOfRange, other.add(order("lejos", fireAt = now.timeInMillis + 25 * 3_600_000L)))
    assertEquals(InstinctScheduleStore.Add.OutOfRange, other.add(order("pasado", fireAt = now.timeInMillis - 1)))
  }

  @Test fun `orders round-trip with their condition and cancel all`() {
    val store = store()
    store.add(order("a", condition = WeatherCondition.Below(-3)))
    store.add(order("b", condition = WeatherCondition.Rain(false)))
    assertEquals(listOf(WeatherCondition.Below(-3), WeatherCondition.Rain(false)), store.pending().map { it.condition })
    assertEquals(2, store.cancelAll().size)
    assertTrue(store.pending().isEmpty())
  }

  @Test fun `consumed before running, once, and a process death leaves it uncertain, never repeated`() {
    val dir = Files.createTempDirectory("insto-scheduled").toFile()
    val file = File(dir, "instinct-scheduled.json")
    val first = InstinctScheduleStore(file) { now.timeInMillis }
    val o = order("x")
    first.add(o)
    assertNull("a wrong token consumes nothing", first.consume("x", "otro"))
    val consumed = first.consume("x", o.token)!!
    assertEquals(ScheduledOrder.State.CONSUMED, consumed.state)
    assertNull("a second firing finds nothing", first.consume("x", o.token))
    // The process dies here, before running or removing it. A fresh process reads the file:
    val restarted = InstinctScheduleStore(file) { now.timeInMillis }
    assertTrue(restarted.pending().isEmpty())
    assertNull(restarted.consume("x", o.token))
    assertEquals(listOf("x"), restarted.recoverInterrupted().map { it.id })
    assertTrue("reported once", restarted.recoverInterrupted().isEmpty())
    assertTrue(restarted.read().isEmpty())
  }

  @Test fun `an order being fired is not mistaken for an interrupted one`() {
    val store = store()
    val o = order("y")
    store.add(o)
    store.consume("y", o.token)
    assertTrue(store.recoverInterrupted(running = setOf("y")).isEmpty())
    assertEquals(1, store.recoverInterrupted().size)
  }

  // Firing

  private fun decide(o: ScheduledOrder, at: Long, enabled: Boolean = true, fingerprint: String? = "f") =
    ScheduleFirePolicy.decide(o, enabled, fingerprint, at) { InstinctProposalValidator.check(it, parser, now) }

  @Test fun `firing revalidates from scratch`() {
    val o = order("z")
    assertEquals(ScheduleFirePolicy.Decision.Run(PhoneCommand.Torch(true)), decide(o, o.fireAt))
    assertTrue(decide(o, o.fireAt, fingerprint = "otra").let { it is ScheduleFirePolicy.Decision.Drop && it.why.contains("configuración") })
    assertTrue(decide(o, o.fireAt, fingerprint = null) is ScheduleFirePolicy.Decision.Drop)
    assertTrue(decide(o, o.fireAt, enabled = false) is ScheduleFirePolicy.Decision.Drop)
    assertTrue("an inner order the parser no longer accepts", decide(order("w", inner = "llamar a Ana"), o.fireAt) is ScheduleFirePolicy.Decision.Drop)
  }

  @Test fun `a firing more than 15 minutes late waits for Confirmar`() {
    val o = order("late")
    assertTrue(decide(o, o.fireAt + InstinctDeferral.LATE_MILLIS) is ScheduleFirePolicy.Decision.Run)
    assertEquals(ScheduleFirePolicy.Decision.Late(PhoneCommand.Torch(true)), decide(o, o.fireAt + InstinctDeferral.LATE_MILLIS + 1))
  }

  // Undo

  @Test fun `only reversible states get an undo`() {
    assertTrue(UndoPolicy.reversible(PhoneCommand.Volume(VolumeOp.SET, 10)))
    assertTrue(UndoPolicy.reversible(PhoneCommand.Torch(true)))
    assertTrue(UndoPolicy.reversible(PhoneCommand.Platform(PlatformAction.DND_ON)))
    assertTrue(UndoPolicy.reversible(PhoneCommand.Platform(PlatformAction.VIBRATE_ON)))
    assertTrue(UndoPolicy.reversible(PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600)))
    for (command in listOf(PhoneCommand.Alarm(7, 0), PhoneCommand.Timer(60), PhoneCommand.OpenApp("spotify"), PhoneCommand.YouTubeSearch("jazz"),
      PhoneCommand.Media(MediaOp.PAUSE), PhoneCommand.ClockList(true)))
      assertFalse(command.toString(), UndoPolicy.reversible(command))
  }

  @Test fun `undo restores only while the state is what the order left`() {
    assertEquals(UndoPolicy.Decision.Restore(4), UndoPolicy.decide(previous = 4, applied = 9, current = 9))
    assertEquals(UndoPolicy.Decision.ManualChange, UndoPolicy.decide(previous = 4, applied = 9, current = 7))
  }

  @Test fun `an undo needs its token, works once and lapses after ten minutes`() {
    var clock = 1_000L
    val book = UndoBook({ clock })
    assertNull(book.offer(1, "x", emptyList()))
    val entry = book.offer(1, "poner el volumen al 10 por ciento", listOf(Reversal.Volume(PhoneCommand.Volume(VolumeOp.SET, 10), 4, 1)))!!
    assertEquals(UndoBook.Claim.Unknown, book.claim(entry.id, "adivinado"))
    assertTrue(book.claim(entry.id, entry.token) is UndoBook.Claim.Granted)
    assertEquals(UndoBook.Claim.Used, book.claim(entry.id, entry.token))
    val later = book.offer(2, "prender la linterna", listOf(Reversal.Torch(PhoneCommand.Torch(true), restore = false)))!!
    clock += ProposalBook.TTL_MILLIS + 1
    assertEquals(UndoBook.Claim.Expired, book.claim(later.id, later.token))
    assertEquals(UndoBook.Claim.Used, book.claim(later.id, later.token))
  }

  @Test fun `the undo summary says what could not be undone`() {
    assertEquals("Deshice: ${PhoneCommandCodec.summary(PhoneCommand.Torch(true))}. No se pueden deshacer: ${PhoneCommandCodec.summary(PhoneCommand.Timer(60))}.",
      UndoPolicy.summary(listOf(PhoneCommand.Torch(true)), emptyList(), listOf(PhoneCommand.Timer(60))))
    assertTrue(UndoPolicy.summary(emptyList(), listOf(PhoneCommand.Volume(VolumeOp.UP)), emptyList()).startsWith("No toqué lo que cambiaste después"))
  }

  // Replies

  @Test fun `a scheduled order is acknowledged and its later outcome follows while the handle is valid`() {
    var clock = 0L
    val ledger = InstinctAckLedger<String>({ clock }, { InstinctReplyMode.PROBLEMS })
    val ticket = ledger.open("h", 1)
    ledger.link(ticket, 0, -1L)
    assertEquals("#insto-programada en 5 minutos: prender la linterna → a las 21:05",
      ledger.report(-1L, AckEntry(AckKind.SCHEDULED, "en 5 minutos: prender la linterna", "a las 21:05"))!!.text)
    ledger.relink(-1L, 9L)
    clock += 5 * 60_000
    assertEquals("#insto-ok en 5 minutos: prender la linterna → Linterna encendida.",
      ledger.report(9L, AckEntry(AckKind.OK, "en 5 minutos: prender la linterna", "Linterna encendida."))!!.text)
    assertEquals("#insto-deshecho en 5 minutos: prender la linterna → Deshice.",
      ledger.note(9L, AckEntry(AckKind.UNDONE, "en 5 minutos: prender la linterna", "Deshice."))!!.text)
  }
}
