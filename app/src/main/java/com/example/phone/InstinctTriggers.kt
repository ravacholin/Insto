package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject

/**
 * «Disparadores por estado» and «órdenes recurrentes» (1.17.0): the pure part. Instinct leaves the logic armed and
 * the phone fires it alone: at the end or start of a calendar event, when headphones connect or disconnect, when the
 * phone is unlocked, or every day, weekday or weekend day at a time until a date. Each trigger is used once (a
 * recurrence, once per occurrence); the inner order is still checked by [InstinctProposalValidator] when it arrives
 * and again when it fires. Nothing here touches Android: the calendar, the alarms and the system callbacks are
 * adapters elsewhere. See docs/INSTINCT-PROPUESTAS.md, «Disparadores por estado» and «Órdenes recurrentes».
 */
sealed interface Trigger {
  /** A clock time or a delay (1.14.0). */
  data object At : Trigger

  /** The calendar instance in progress ends ([eventId] and [instanceId] as read when the order arrived; [at], its end). */
  data class EventEnd(val eventId: Long = 0, val instanceId: Long = 0, val at: Long = 0) : Trigger
  /** The next calendar instance starts at [at]. */
  data class EventStart(val eventId: Long = 0, val instanceId: Long = 0, val at: Long = 0) : Trigger
  data object HeadphonesOn : Trigger
  data object HeadphonesOff : Trigger
  data object Unlock : Trigger
  /** Every [days] at [minuteOfDay] (local), until the end of the day [until] (wall-clock millis, exclusive). */
  data class Recurring(val days: RecurrenceDays, val minuteOfDay: Int, val until: Long) : Trigger

  /** Fired by something the phone does, not by a time: their alarm only marks the expiry. */
  val byState: Boolean get() = this is HeadphonesOn || this is HeadphonesOff || this is Unlock
  val byEvent: Boolean get() = this is EventEnd || this is EventStart
  /** A placeholder from the parser: the calendar instance is still to be read. */
  val unresolved: Boolean get() = (this is EventEnd && eventId == 0L) || (this is EventStart && eventId == 0L)
}

enum class RecurrenceDays(val phrase: String, val label: String) {
  DAILY("todos los días", "todos los días"),
  WEEKDAYS("los días hábiles", "días hábiles"),
  WEEKENDS("los fines de semana", "fines de semana");

  /** [day] is a [Calendar.DAY_OF_WEEK]. Weekdays are Monday to Friday; holidays are not known. */
  fun includes(day: Int): Boolean = when (this) {
    DAILY -> true
    WEEKDAYS -> day in Calendar.MONDAY..Calendar.FRIDAY
    WEEKENDS -> day == Calendar.SATURDAY || day == Calendar.SUNDAY
  }
}

/** What the phone did, from the callbacks «Insto · notificaciones» already registers. */
enum class PhoneEvent { HEADPHONES_ON, HEADPHONES_OFF, UNLOCK }

/** A calendar instance as the triggers see it: never its title. */
data class CalendarInstance(val instanceId: Long, val eventId: Long, val begin: Long, val end: Long, val allDay: Boolean = false)

object InstinctTriggers {
  /** A state or event trigger without «hasta las HH:MM» expires after this. */
  const val DEFAULT_EXPIRY_MILLIS = 12 * 60 * 60_000L
  const val MAX_EXPIRY_MILLIS = 24 * 60 * 60_000L
  /** «hasta el DD/MM» at most this many days after today. */
  const val MAX_RECURRING_DAYS = 30
  const val MAX_RECURRING = 5
  /** An event that moved less than this is the same: the alarm fired on time. */
  const val MOVED_TOLERANCE_MILLIS = 60_000L

  /** The trigger in words, as the acknowledgements and «ver programadas» say it. */
  fun label(trigger: Trigger, expiresAt: Long?, fireAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault(),
    condition: WeatherCondition? = null): String {
    fun clock(millis: Long) = InstinctDeferral.clock(millis, Calendar.getInstance(zone))
    val until = expiresAt?.let { ", hasta las ${clock(it)}" }.orEmpty()
    val weather = condition?.let { " ${it.label}" }.orEmpty()
    return when (trigger) {
      Trigger.At -> "a las ${clock(fireAt)}$weather"
      is Trigger.EventEnd -> "al terminar el evento (${clock(trigger.at)})$until"
      is Trigger.EventStart -> "al empezar el próximo evento (${clock(trigger.at)})$until"
      Trigger.HeadphonesOn -> "al conectar auriculares$until"
      Trigger.HeadphonesOff -> "al desconectar auriculares$until"
      Trigger.Unlock -> "al desbloquear$until"
      is Trigger.Recurring -> "${trigger.days.label} ${QuietHours.clock(trigger.minuteOfDay)}$weather hasta el ${date(trigger.until - 1, zone)}, " +
        "próxima ${day(fireAt, now, zone)}"
    }
  }

  /** «hoy», «mañana» or «el DD/MM». */
  fun day(millis: Long, now: Long, zone: TimeZone): String {
    val diff = dayIndex(millis, zone) - dayIndex(now, zone)
    return when (diff) { 0L -> "hoy"; 1L -> "mañana"; else -> "el ${date(millis, zone)}" }
  }

  fun date(millis: Long, zone: TimeZone): String = Calendar.getInstance(zone).apply { timeInMillis = millis }
    .let { "%02d/%02d".format(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH) + 1) }

  private fun dayIndex(millis: Long, zone: TimeZone): Long = Math.floorDiv(millis + zone.getOffset(millis), 86_400_000L)

  /**
   * The next occurrence strictly after [after], on one of [days] at [minuteOfDay] in [zone], before [until]; null
   * when there is none. Computed in local wall time, so after a time zone change it is the same local hour.
   */
  fun next(days: RecurrenceDays, minuteOfDay: Int, after: Long, until: Long, zone: TimeZone): Long? {
    val c = Calendar.getInstance(zone).apply { timeInMillis = after }
    c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); c.set(Calendar.MINUTE, minuteOfDay % 60)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    repeat(MAX_RECURRING_DAYS + 9) {
      if (c.timeInMillis > after && days.includes(c.get(Calendar.DAY_OF_WEEK))) return c.timeInMillis.takeIf { it < until }
      c.add(Calendar.DAY_OF_MONTH, 1)
      c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); c.set(Calendar.MINUTE, minuteOfDay % 60)
    }
    return null
  }

  /**
   * «hasta el DD/MM»: the end of that day (exclusive), this year or, if it already passed, next year. Null when the
   * date does not exist or is more than [MAX_RECURRING_DAYS] days after today.
   */
  fun untilDate(day: Int, month: Int, now: Calendar): Long? {
    if (month !in 1..12 || day !in 1..31) return null
    fun endOf(year: Int): Long? {
      val c = (now.clone() as Calendar).apply { isLenient = false; clear(); set(year, month - 1, day) }
      return runCatching { c.timeInMillis; c.add(Calendar.DAY_OF_MONTH, 1); c.timeInMillis }.getOrNull()
    }
    val year = now.get(Calendar.YEAR)
    val today = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val end = endOf(year)?.takeIf { it > today.timeInMillis } ?: endOf(year + 1) ?: return null
    // The last allowed day ends MAX_RECURRING_DAYS + 1 days after today's midnight.
    val limit = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, MAX_RECURRING_DAYS + 1) }.timeInMillis
    return end.takeIf { it <= limit }
  }

  /** «hasta las HH:MM»: the next such time after [now] (tomorrow when it passed), never more than 24 hours ahead. */
  fun untilTime(hour: Int, minute: Int, now: Calendar): Long? {
    if (hour !in 0..23 || minute !in 0..59) return null
    val c = (now.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
      if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_MONTH, 1)
    }
    return c.timeInMillis.takeIf { it - now.timeInMillis in 1..MAX_EXPIRY_MILLIS }
  }

  sealed interface Pick {
    data class Found(val trigger: Trigger, val fireAt: Long) : Pick
    data class None(val why: String) : Pick
  }

  /**
   * When the order arrives: the instance in progress (the latest started) for «al terminar el evento», the next one
   * to start for «al empezar el próximo evento». All-day events never count. It has to fire before [expiresAt].
   */
  fun pickEvent(trigger: Trigger, events: List<CalendarInstance>, now: Long, expiresAt: Long): Pick {
    val timed = events.filterNot { it.allDay }
    return when (trigger) {
      is Trigger.EventEnd -> {
        val current = timed.filter { it.begin <= now && it.end > now }.maxByOrNull { it.begin }
          ?: return Pick.None("no hay ningún evento en curso.")
        if (current.end > expiresAt) Pick.None("el evento termina después del tope.")
        else Pick.Found(Trigger.EventEnd(current.eventId, current.instanceId, current.end), current.end)
      }
      is Trigger.EventStart -> {
        val next = timed.filter { it.begin > now && it.begin - now <= InstinctState.LOOKAHEAD_MILLIS }.minByOrNull { it.begin }
          ?: return Pick.None("no hay ningún evento en las próximas 12 horas.")
        if (next.begin > expiresAt) Pick.None("el próximo evento empieza después del tope.")
        else Pick.Found(Trigger.EventStart(next.eventId, next.instanceId, next.begin), next.begin)
      }
      else -> Pick.None("no es un disparador de calendario.")
    }
  }

  sealed interface EventCheck {
    data object Same : EventCheck
    data class Moved(val at: Long) : EventCheck
    data object Deleted : EventCheck
  }

  /**
   * When an event trigger fires: the instance read again. The same instance id, or else the instance of the same
   * event closest to the stored time. Gone means deleted; a time more than a minute away means moved.
   */
  fun matchEvent(trigger: Trigger, candidates: List<CalendarInstance>): EventCheck {
    val (eventId, instanceId, at) = when (trigger) {
      is Trigger.EventEnd -> Triple(trigger.eventId, trigger.instanceId, trigger.at)
      is Trigger.EventStart -> Triple(trigger.eventId, trigger.instanceId, trigger.at)
      else -> return EventCheck.Deleted
    }
    fun time(i: CalendarInstance) = if (trigger is Trigger.EventEnd) i.end else i.begin
    val same = candidates.filter { it.eventId == eventId && !it.allDay }
    val found = same.firstOrNull { it.instanceId == instanceId } ?: same.minByOrNull { kotlin.math.abs(time(it) - at) }
      ?: return EventCheck.Deleted
    val now = time(found)
    return if (kotlin.math.abs(now - at) <= MOVED_TOLERANCE_MILLIS) EventCheck.Same else EventCheck.Moved(now)
  }

  /** Whether [event] fires [trigger]. */
  fun fires(trigger: Trigger, event: PhoneEvent): Boolean = when (event) {
    PhoneEvent.HEADPHONES_ON -> trigger == Trigger.HeadphonesOn
    PhoneEvent.HEADPHONES_OFF -> trigger == Trigger.HeadphonesOff
    PhoneEvent.UNLOCK -> trigger == Trigger.Unlock
  }

  /** What an alarm means: a state trigger's alarm is its expiry; any other alarm past its expiry expires it too. */
  enum class AlarmAction { FIRE, EXPIRE }

  fun onAlarm(order: ScheduledOrder, now: Long): AlarmAction = when {
    order.trigger.byState -> AlarmAction.EXPIRE
    order.expiresAt != null && now > order.expiresAt -> AlarmAction.EXPIRE
    else -> AlarmAction.FIRE
  }

  /** The 6-character id of «ver programadas» and «cancelar programada <id>»: stable for a whole recurrence. */
  fun shortId(series: String): String = sha256(series).take(SHORT_ID_LENGTH)
  const val SHORT_ID_LENGTH = 6

  fun encode(trigger: Trigger): JSONObject = when (trigger) {
    Trigger.At -> JSONObject().put("kind", "at")
    is Trigger.EventEnd -> JSONObject().put("kind", "eventEnd").put("event", trigger.eventId).put("instance", trigger.instanceId).put("at", trigger.at)
    is Trigger.EventStart -> JSONObject().put("kind", "eventStart").put("event", trigger.eventId).put("instance", trigger.instanceId).put("at", trigger.at)
    Trigger.HeadphonesOn -> JSONObject().put("kind", "headphonesOn")
    Trigger.HeadphonesOff -> JSONObject().put("kind", "headphonesOff")
    Trigger.Unlock -> JSONObject().put("kind", "unlock")
    is Trigger.Recurring -> JSONObject().put("kind", "recurring").put("days", trigger.days.name).put("minute", trigger.minuteOfDay).put("until", trigger.until)
  }

  /** Unknown kinds fail: an order whose trigger Insto does not understand is never run. */
  fun decode(o: JSONObject?): Trigger = when (o?.optString("kind")) {
    null, "", "at" -> Trigger.At
    "eventEnd" -> Trigger.EventEnd(o.getLong("event"), o.getLong("instance"), o.getLong("at"))
    "eventStart" -> Trigger.EventStart(o.getLong("event"), o.getLong("instance"), o.getLong("at"))
    "headphonesOn" -> Trigger.HeadphonesOn
    "headphonesOff" -> Trigger.HeadphonesOff
    "unlock" -> Trigger.Unlock
    "recurring" -> Trigger.Recurring(RecurrenceDays.valueOf(o.getString("days")), o.getInt("minute").also { require(it in 0 until 1440) }, o.getLong("until"))
    else -> throw IllegalArgumentException("disparador desconocido")
  }
}
