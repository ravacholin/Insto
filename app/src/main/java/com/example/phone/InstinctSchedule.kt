package com.example.phone

import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deferred orders from Instinct and their only condition, the weather: the pure part. The wrapper only says *when*
 * (and, with a clock time, *if*); the inner order is still checked by [InstinctProposalValidator] untouched, now and
 * again when it fires. Kept apart from the Instinct notification code so another feature can reuse the evaluator
 * and the store. See docs/INSTINCT-PROPUESTAS.md, «Órdenes diferidas».
 */
sealed interface WeatherCondition {
  /** Rain expected ([raining] true, «si llueve») or not expected («si no llueve») in the next hours. */
  data class Rain(val raining: Boolean) : WeatherCondition
  /** Current temperature strictly below («si hace menos de N grados») or above («si hace más de N grados») [celsius]. */
  data class Below(val celsius: Int) : WeatherCondition
  data class Above(val celsius: Int) : WeatherCondition

  val label: String get() = when (this) {
    is Rain -> if (raining) "si llueve" else "si no llueve"
    is Below -> "si hace menos de $celsius grados"
    is Above -> "si hace más de $celsius grados"
  }
}

/**
 * When a wrapped order runs: wall-clock [fireAt], an optional [condition], and its inner order as written. Since
 * 1.17.0 also [trigger] (a time by default) and [expiresAt]: a state or event trigger that has not fired by then
 * lapses as «vencida». For a state trigger [fireAt] is its expiry; for an event one, a placeholder until the
 * calendar is read when the order is stored.
 */
data class Deferred(val inner: String, val fireAt: Long, val condition: WeatherCondition?, val label: String,
  val trigger: Trigger = Trigger.At, val expiresAt: Long? = null)

object InstinctDeferral {
  const val MIN_DELAY_MILLIS = 60_000L
  const val MAX_DELAY_MILLIS = 24 * 60 * 60_000L
  /** A firing later than this (Doze, a phone that was off) does not run alone: it waits for Confirmar. */
  const val LATE_MILLIS = 15 * 60_000L
  const val MAX_SCHEDULED = 10

  // «a las HH:MM[ si …]: <orden>» and «en N minutos|horas: <orden>». The colon after the time is required: without it
  // the line is an ordinary order, so «poner una alarma a las 07:00» keeps its meaning.
  private val atTime = Regex("^a las (\\d{1,2}):(\\d{2})(?: (si [^:]+?))?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
  private val inDelay = Regex("^en (\\d{1,4}) (minutos?|horas?)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
  // 1.17.0: «al <algo>[ hasta las HH:MM]: <orden>» and «<días> a las HH:MM[ si …] hasta el DD/MM: <orden>». One
  // condition per line: anything else that starts like a trigger is refused, never taken as an immediate order.
  private val stateTrigger = Regex("^al (terminar el evento|empezar el pr[oó]ximo evento|conectar (?:los )?auriculares|" +
    "desconectar (?:los )?auriculares|desbloquear(?: el tel[eé]fono)?)(?: hasta las (\\d{1,2}):(\\d{2}))?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
  private val triggerLike = Regex("^al (terminar|empezar|conectar|desconectar|desbloquear)\\b[^:]*:", RegexOption.IGNORE_CASE)
  private val recurring = Regex("^(todos los d[ií]as|los d[ií]as h[aá]biles|los fines de semana) a las (\\d{1,2}):(\\d{2})" +
    "(?: (si .+?))? hasta el (\\d{1,2})/(\\d{1,2})\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
  private val recurringLike = Regex("^(todos los d[ií]as|los d[ií]as h[aá]biles|los fines de semana)\\b.*:", RegexOption.IGNORE_CASE)
  private val rain = Regex("^si (no )?llueve$")
  private val temperature = Regex("^si hace (menos|mas) de (-?\\d{1,2}) grados$")

  sealed interface Parse {
    /** Not a wrapper: the line is an ordinary order. */
    data object None : Parse
    data class Wrapped(val deferred: Deferred) : Parse
    /** A wrapper with a time or a condition Insto does not accept. Never treated as an immediate order. */
    data class Rejected(val why: String) : Parse
  }

  fun parse(order: String, now: Calendar): Parse {
    val text = order.trim()
    stateTrigger.matchEntire(text)?.let { m -> return stateTrigger(m, now) }
    if (triggerLike.containsMatchIn(text))
      return Parse.Rejected("cada línea admite un solo disparador, sin «y», «o» ni condiciones: «al conectar auriculares[ hasta las HH:MM]: …».")
    recurring.matchEntire(text)?.let { m -> return recurring(m, now) }
    if (recurringLike.containsMatchIn(text))
      return Parse.Rejected("una orden recurrente necesita la hora y «hasta el DD/MM» (como mucho ${InstinctTriggers.MAX_RECURRING_DAYS} días): «todos los días a las 07:30 hasta el 15/12: …».")
    atTime.matchEntire(text)?.let { m ->
      val hour = m.groupValues[1].toInt(); val minute = m.groupValues[2].toInt()
      if (hour !in 0..23 || minute !in 0..59) return Parse.Rejected("la hora tiene que ser de 00:00 a 23:59.")
      val condition = m.groups[3]?.value?.let { raw -> condition(raw) ?: return Parse.Rejected("solo se acepta una condición del clima: si llueve, si no llueve, si hace menos o más de N grados.") }
      val at = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        // A time that already passed today (or is now) means tomorrow.
        if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_MONTH, 1)
      }
      val label = "a las %02d:%02d".format(hour, minute) + (condition?.let { " ${it.label}" } ?: "")
      return Parse.Wrapped(Deferred(m.groupValues[4].trim(), at.timeInMillis, condition, label))
    }
    inDelay.matchEntire(text)?.let { m ->
      val amount = m.groupValues[1].toLong()
      val millis = amount * if (m.groupValues[2].lowercase().startsWith("hora")) 3_600_000L else 60_000L
      if (millis !in MIN_DELAY_MILLIS..MAX_DELAY_MILLIS) return Parse.Rejected("la demora tiene que ser de 1 minuto a 24 horas.")
      val fireAt = now.timeInMillis + millis
      return Parse.Wrapped(Deferred(m.groupValues[3].trim(), fireAt, null, "a las ${clock(fireAt, now)}"))
    }
    // «en N minutos si llueve: …»: conditions need a clock time; never an immediate order either.
    if (Regex("^(a las \\S+|en \\d+ \\S+) si .+:", RegexOption.IGNORE_CASE).containsMatchIn(text))
      return Parse.Rejected("las condiciones van solo con una hora: «a las 06:30 si llueve: …».")
    return Parse.None
  }

  private fun stateTrigger(m: MatchResult, now: Calendar): Parse {
    val phrase = PhoneCommandParser.normalize(m.groupValues[1])
    val trigger = when {
      phrase.startsWith("terminar") -> Trigger.EventEnd()
      phrase.startsWith("empezar") -> Trigger.EventStart()
      phrase.startsWith("conectar") -> Trigger.HeadphonesOn
      phrase.startsWith("desconectar") -> Trigger.HeadphonesOff
      else -> Trigger.Unlock
    }
    val expiresAt = if (m.groups[2] != null) {
      InstinctTriggers.untilTime(m.groupValues[2].toInt(), m.groupValues[3].toInt(), now)
        ?: return Parse.Rejected("el tope «hasta las HH:MM» tiene que ser una hora de 00:00 a 23:59.")
    } else now.timeInMillis + InstinctTriggers.DEFAULT_EXPIRY_MILLIS
    val label = InstinctTriggers.label(trigger, expiresAt, expiresAt, now.timeInMillis, now.timeZone)
    return Parse.Wrapped(Deferred(m.groupValues[4].trim(), expiresAt, null, label, trigger, expiresAt))
  }

  private fun recurring(m: MatchResult, now: Calendar): Parse {
    val days = RecurrenceDays.entries.first { PhoneCommandParser.normalize(it.phrase) == PhoneCommandParser.normalize(m.groupValues[1]) }
    val hour = m.groupValues[2].toInt(); val minute = m.groupValues[3].toInt()
    if (hour !in 0..23 || minute !in 0..59) return Parse.Rejected("la hora tiene que ser de 00:00 a 23:59.")
    val condition = m.groups[4]?.value?.let { raw -> condition(raw) ?: return Parse.Rejected("solo se acepta una condición del clima: si llueve, si no llueve, si hace menos o más de N grados.") }
    val until = InstinctTriggers.untilDate(m.groupValues[5].toInt(), m.groupValues[6].toInt(), now)
      ?: return Parse.Rejected("«hasta el DD/MM» tiene que ser una fecha válida dentro de los próximos ${InstinctTriggers.MAX_RECURRING_DAYS} días.")
    val trigger = Trigger.Recurring(days, hour * 60 + minute, until)
    val first = InstinctTriggers.next(days, trigger.minuteOfDay, now.timeInMillis, until, now.timeZone)
      ?: return Parse.Rejected("no hay ninguna ocurrencia antes de esa fecha.")
    val label = InstinctTriggers.label(trigger, null, first, now.timeInMillis, now.timeZone, condition)
    return Parse.Wrapped(Deferred(m.groupValues[7].trim(), first, condition, label, trigger))
  }

  /** One condition, exactly as listed: no «y», «o» or nesting. */
  internal fun condition(raw: String): WeatherCondition? {
    val folded = PhoneCommandParser.normalize(raw).trimEnd('.')
    rain.matchEntire(folded)?.let { return WeatherCondition.Rain(raining = it.groups[1] == null) }
    temperature.matchEntire(folded)?.let {
      val n = it.groupValues[2].toInt()
      return if (it.groupValues[1] == "menos") WeatherCondition.Below(n) else WeatherCondition.Above(n)
    }
    return null
  }

  fun clock(millis: Long, zoneOf: Calendar): String = (zoneOf.clone() as Calendar).apply { timeInMillis = millis }
    .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
}

/**
 * A published probability as a range: SMN gives `[min, max]` per block, Open-Meteo exact hourly values. Over the
 * window the reading is the highest block: (max of the minimums, max of the maximums).
 */
data class PercentRange(val min: Int, val max: Int) { init { require(min in 0..100 && max in min..100) } }

/** Structured weather for conditions: a number, never the reply text. Null means not available now (the default). */
interface WeatherReadings {
  /** Current temperature (observation, not the feels-like), fresh; null when unavailable. */
  suspend fun currentTemperature(): BigDecimal? = null
  /** Precipitation probability over the next [WeatherConditions.RAIN_WINDOW_MILLIS]; null when unavailable or missing. */
  suspend fun rainChance(): PercentRange? = null
}

object WeatherConditions {
  /** «llueve» means a probability of at least this, in the forecast for the next six hours. */
  const val RAIN_THRESHOLD_PERCENT = 50
  const val RAIN_WINDOW_MILLIS = 6 * 60 * 60_000L

  enum class Verdict { MET, NOT_MET, UNKNOWN }

  /**
   * Fail-safe: without data, or with a range that straddles the threshold, the verdict is [Verdict.UNKNOWN] and the
   * order does not run. Nothing is ever assumed.
   */
  fun evaluate(condition: WeatherCondition, temperature: BigDecimal?, rain: PercentRange?): Verdict = when (condition) {
    is WeatherCondition.Rain -> {
      val raining = when {
        rain == null -> null
        rain.min >= RAIN_THRESHOLD_PERCENT -> true
        rain.max < RAIN_THRESHOLD_PERCENT -> false
        else -> null
      }
      when (raining) { null -> Verdict.UNKNOWN; condition.raining -> Verdict.MET; else -> Verdict.NOT_MET }
    }
    is WeatherCondition.Below -> temperature?.let { if (it < BigDecimal(condition.celsius)) Verdict.MET else Verdict.NOT_MET } ?: Verdict.UNKNOWN
    is WeatherCondition.Above -> temperature?.let { if (it > BigDecimal(condition.celsius)) Verdict.MET else Verdict.NOT_MET } ?: Verdict.UNKNOWN
  }

  /** Reads only what [condition] needs. Any failure is unavailable data. */
  suspend fun check(condition: WeatherCondition, readings: WeatherReadings): Verdict = try {
    when (condition) {
      is WeatherCondition.Rain -> evaluate(condition, null, readings.rainChance())
      else -> evaluate(condition, readings.currentTemperature(), null)
    }
  } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { Verdict.UNKNOWN }

  /** [check] with a deadline: a timeout is unavailable data, so the order does not run. */
  suspend fun checkWithin(condition: WeatherCondition, readings: WeatherReadings, timeoutMillis: Long): Verdict =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { check(condition, readings) } ?: Verdict.UNKNOWN
}

/**
 * One order waiting for its time. [state] is written to disk before anything runs: an order is CONSUMED first, and
 * an order still CONSUMED after a process death may have run and is never repeated.
 */
data class ScheduledOrder(
  val id: String,
  /** The full line as Instinct wrote it, after «#insto ». */
  val order: String,
  /** The inner order, checked again from scratch when it fires. */
  val inner: String,
  val fireAt: Long,
  val fingerprint: String,
  val condition: WeatherCondition?,
  val label: String,
  val createdAt: Long,
  /** Random, carried only by Insto's own alarm PendingIntent. */
  val token: String = java.util.UUID.randomUUID().toString(),
  val state: State = State.PENDING,
  /** The short id of Instinct's message (never its text), so a follow-up in the log names it even after a restart. */
  val message: String = "",
  /** WhatsApp or the signed mailbox (1.16.0): the same cap and the same log rules apply when it fires. */
  val source: ProposalSource = ProposalSource.WHATSAPP,
  /** What fires it (1.17.0); orders stored by 1.16.0 are [Trigger.At]. */
  val trigger: Trigger = Trigger.At,
  /** A state or event trigger lapses as «vencida» after this; null for a time or a recurrence. */
  val expiresAt: Long? = null,
  /** The same for every occurrence of a recurrence, so it is cancelled whole and keeps its short id. */
  val series: String = id,
  /** Preserve the selected provider target across firings, even if names or aliases are edited. */
  val remoteCommand: PhoneCommand? = null,
) {
  enum class State { PENDING, CONSUMED }
  val summary: String get() = "$label: $inner"
  val recurring: Boolean get() = trigger is Trigger.Recurring
  /** The 6 characters of «ver programadas» and «cancelar programada <id>». */
  val shortId: String get() = InstinctTriggers.shortId(series)
}

/**
 * Scheduled orders in one file with an atomic write, like [ConfiguredRoutineStore]. At most
 * [InstinctDeferral.MAX_SCHEDULED] pending and never more than 24 hours ahead. The caller keeps the file in
 * noBackupFilesDir, so a restore never brings them back.
 */
class InstinctScheduleStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
  sealed interface Add {
    data class Added(val order: ScheduledOrder) : Add
    data object Full : Add
    /** Already [InstinctTriggers.MAX_RECURRING] recurrences (1.17.0), apart from the 10 scheduled. */
    data object FullRecurring : Add
    data object OutOfRange : Add
  }

  /** A consumed order and, for a recurrence, its next occurrence, written in the same step. */
  data class Consumed(val order: ScheduledOrder, val successor: ScheduledOrder?)

  @Synchronized fun read(): List<ScheduledOrder> = if (!file.exists()) emptyList() else runCatching { decode(file.readText()) }.getOrDefault(emptyList())

  @Synchronized fun pending(): List<ScheduledOrder> = read().filter { it.state == ScheduledOrder.State.PENDING }.sortedBy { it.fireAt }

  @Synchronized fun add(order: ScheduledOrder): Add {
    val now = clock()
    val all = read()
    val pending = all.filter { it.state == ScheduledOrder.State.PENDING }
    val trigger = order.trigger
    if (trigger is Trigger.Recurring) {
      if (order.fireAt <= now || order.fireAt >= trigger.until || trigger.until - now > (InstinctTriggers.MAX_RECURRING_DAYS + 2) * 86_400_000L)
        return Add.OutOfRange
      if (pending.filter { it.recurring }.distinctBy { it.series }.size >= InstinctTriggers.MAX_RECURRING) return Add.FullRecurring
    } else {
      if (order.fireAt - now !in 1..InstinctDeferral.MAX_DELAY_MILLIS + 60_000L) return Add.OutOfRange
      if (pending.count { !it.recurring } >= InstinctDeferral.MAX_SCHEDULED) return Add.Full
    }
    write(all + order)
    return Add.Added(order)
  }

  /** The pending order [id] with [token], without touching it: to decide what its alarm means. */
  @Synchronized fun peek(id: String, token: String?): ScheduledOrder? =
    read().firstOrNull { it.id == id && it.state == ScheduledOrder.State.PENDING && (token == null || it.token == token) }

  /**
   * Marks [id] consumed on disk; only the one caller that found it pending with [token] gets it. A recurrence's
   * next occurrence after [now] (local time in [zone]) is written in the same step, as a new pending order with
   * its own id and token: each occurrence runs at most once and an uncertain one never stops the next.
   */
  @Synchronized fun consumeOccurrence(id: String, token: String?, now: Long = clock(),
    zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Consumed? {
    val all = read()
    val order = all.firstOrNull { it.id == id && it.state == ScheduledOrder.State.PENDING && (token == null || it.token == token) } ?: return null
    val consumed = order.copy(state = ScheduledOrder.State.CONSUMED)
    val successor = (order.trigger as? Trigger.Recurring)?.let { r ->
      InstinctTriggers.next(r.days, r.minuteOfDay, maxOf(order.fireAt, now), r.until, zone)?.let { next ->
        order.copy(id = java.util.UUID.randomUUID().toString(), token = java.util.UUID.randomUUID().toString(), fireAt = next,
          label = InstinctTriggers.label(r, null, next, now, zone, order.condition), createdAt = now)
      }
    }
    write(all.map { if (it.id == id) consumed else it } + listOfNotNull(successor))
    return Consumed(consumed, successor)
  }

  /** [consumeOccurrence] without the successor, as before 1.17.0. */
  @Synchronized fun consume(id: String, token: String?): ScheduledOrder? = consumeOccurrence(id, token)?.order

  /** A pending order moves (an event that changed its time, a recurrence after a time zone change). */
  @Synchronized fun reschedule(id: String, trigger: Trigger, fireAt: Long, label: String): ScheduledOrder? {
    val all = read()
    val order = all.firstOrNull { it.id == id && it.state == ScheduledOrder.State.PENDING } ?: return null
    val moved = order.copy(trigger = trigger, fireAt = fireAt, label = label)
    write(all.map { if (it.id == id) moved else it })
    return moved
  }

  /** Cancels every pending order of the recurrence or order whose short id is [shortId]; returns them. */
  @Synchronized fun cancelShort(shortId: String): List<ScheduledOrder> {
    val all = read()
    val matched = all.filter { it.state == ScheduledOrder.State.PENDING && it.shortId == shortId.lowercase() }
    if (matched.isNotEmpty()) write(all - matched.toSet())
    return matched
  }

  @Synchronized fun remove(id: String): Boolean {
    val all = read()
    if (all.none { it.id == id }) return false
    write(all.filterNot { it.id == id })
    return true
  }

  /** Cancels every pending order; returns them. Consumed ones are left for [recoverInterrupted]. */
  @Synchronized fun cancelAll(): List<ScheduledOrder> {
    val all = read()
    val pending = all.filter { it.state == ScheduledOrder.State.PENDING }
    if (pending.isNotEmpty()) write(all - pending.toSet())
    return pending
  }

  /** Orders left CONSUMED by a process death: they may have run. Removed and returned, to be reported, never run. */
  @Synchronized fun recoverInterrupted(running: Set<String> = emptySet()): List<ScheduledOrder> {
    val all = read()
    val interrupted = all.filter { it.state == ScheduledOrder.State.CONSUMED && it.id !in running }
    if (interrupted.isNotEmpty()) write(all - interrupted.toSet())
    return interrupted
  }

  private fun write(values: List<ScheduledOrder>) {
    if (values.isEmpty()) { file.delete(); return }
    val data = encode(values)
    file.parentFile?.mkdirs()
    val temporary = File.createTempFile("insto-scheduled-", ".tmp", file.absoluteFile.parentFile)
    try {
      FileOutputStream(temporary).use { it.write(data.toByteArray(Charsets.UTF_8)); it.fd.sync() }
      check(temporary.renameTo(file)) { "No se pudieron guardar las órdenes programadas" }
    } finally { temporary.delete() }
  }

  companion object {
    /** 2 since 1.17.0: `{version, orders}`, with a trigger per order. 1.16.0 wrote a bare array. */
    const val VERSION = 2

    fun encode(values: List<ScheduledOrder>): String = JSONObject().put("version", VERSION).put("orders", JSONArray(values.map { o ->
      JSONObject().put("id", o.id).put("order", o.order).put("inner", o.inner).put("fireAt", o.fireAt).put("fingerprint", o.fingerprint)
        .put("label", o.label).put("createdAt", o.createdAt).put("token", o.token).put("state", o.state.name)
        .apply { o.condition?.let { put("condition", conditionCode(it)) } }
        .apply { if (o.message.isNotEmpty()) put("message", o.message) }
        .apply { if (o.source != ProposalSource.WHATSAPP) put("source", o.source.name) }
        .put("trigger", InstinctTriggers.encode(o.trigger))
        .apply { o.expiresAt?.let { put("expiresAt", it) } }
        .apply { if (o.series != o.id) put("series", o.series) }
        .apply { o.remoteCommand?.let { put("remoteCommand", PhoneCommandCodec.encode(it)) } }
    })).toString()

    /**
     * Reads both formats: 1.16.0's bare array (every order a [Trigger.At], nothing lost) and the versioned object.
     * A newer version is read the same way; an order with a trigger Insto does not understand fails the file.
     */
    fun decode(text: String): List<ScheduledOrder> {
      require(text.length <= 131_072)
      val trimmed = text.trimStart()
      val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).also { require(it.getInt("version") >= 2) }.getJSONArray("orders")
      return (0 until minOf(array.length(), 48)).map { i ->
        val o = array.getJSONObject(i)
        val id = o.getString("id")
        ScheduledOrder(id, o.getString("order"), o.getString("inner"), o.getLong("fireAt"), o.getString("fingerprint"),
          o.optString("condition").takeIf { it.isNotEmpty() }?.let(::condition), o.getString("label"), o.getLong("createdAt"),
          o.getString("token"), ScheduledOrder.State.valueOf(o.getString("state")),
          InstinctLogEvents.messageId(o.optString("message")),
          if (o.optString("source") == ProposalSource.BUZON.name) ProposalSource.BUZON else ProposalSource.WHATSAPP,
          InstinctTriggers.decode(o.optJSONObject("trigger")),
          if (o.has("expiresAt")) o.getLong("expiresAt") else null,
          o.optString("series").takeIf { it.isNotEmpty() } ?: id,
          o.optJSONObject("remoteCommand")?.let { PhoneCommandCodec.decode(it).also { command ->
            require(OmniremoteCommands.isRemote(command))
          } })
      }
    }

    private fun conditionCode(c: WeatherCondition): String = when (c) {
      is WeatherCondition.Rain -> if (c.raining) "rain" else "dry"
      is WeatherCondition.Below -> "below:${c.celsius}"
      is WeatherCondition.Above -> "above:${c.celsius}"
    }

    private fun condition(code: String): WeatherCondition = when {
      code == "rain" -> WeatherCondition.Rain(true)
      code == "dry" -> WeatherCondition.Rain(false)
      code.startsWith("below:") -> WeatherCondition.Below(code.removePrefix("below:").toInt())
      code.startsWith("above:") -> WeatherCondition.Above(code.removePrefix("above:").toInt())
      else -> throw IllegalArgumentException("condición desconocida")
    }
  }
}

/** What to do when a scheduled order fires, before any weather or execution. */
object ScheduleFirePolicy {
  sealed interface Decision {
    data class Run(val command: PhoneCommand) : Decision
    /** Late: it does not run alone, it waits for Confirmar. */
    data class Late(val command: PhoneCommand) : Decision
    data class Drop(val why: String) : Decision
  }

  /**
   * Revalidates from scratch: the switch, the fingerprint now, and the inner order with today's parser. An order
   * that is no longer valid, or comes from another configuration, is dropped.
   */
  fun decide(order: ScheduledOrder, enabled: Boolean, fingerprint: String?, now: Long, check: (String) -> ProposalCheck): Decision {
    if (!enabled) return Decision.Drop("«Recibir órdenes de Instinct» está apagado.")
    if (fingerprint == null || fingerprint != order.fingerprint) return Decision.Drop("cambió la configuración de Insto desde que se programó.")
    val command = order.remoteCommand ?: run {
      val valid = check(order.inner) as? ProposalCheck.Valid ?: return Decision.Drop("la orden ya no es válida.")
      if (valid.deferred != null) return Decision.Drop("la orden ya no es válida.")
      valid.command
    }
    return if (now - order.fireAt > InstinctDeferral.LATE_MILLIS) Decision.Late(command) else Decision.Run(command)
  }
}
