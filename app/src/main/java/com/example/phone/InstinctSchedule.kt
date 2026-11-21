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

/** When a wrapped order runs: wall-clock [fireAt], an optional [condition], and its inner order as written. */
data class Deferred(val inner: String, val fireAt: Long, val condition: WeatherCondition?, val label: String)

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
) {
  enum class State { PENDING, CONSUMED }
  val summary: String get() = "$label: $inner"
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
    data object OutOfRange : Add
  }

  @Synchronized fun read(): List<ScheduledOrder> = if (!file.exists()) emptyList() else runCatching { decode(file.readText()) }.getOrDefault(emptyList())

  @Synchronized fun pending(): List<ScheduledOrder> = read().filter { it.state == ScheduledOrder.State.PENDING }.sortedBy { it.fireAt }

  @Synchronized fun add(order: ScheduledOrder): Add {
    val now = clock()
    if (order.fireAt - now !in 1..InstinctDeferral.MAX_DELAY_MILLIS + 60_000L) return Add.OutOfRange
    val all = read()
    if (all.count { it.state == ScheduledOrder.State.PENDING } >= InstinctDeferral.MAX_SCHEDULED) return Add.Full
    write(all + order)
    return Add.Added(order)
  }

  /** Marks [id] consumed on disk; true only for the one caller that found it pending with [token]. */
  @Synchronized fun consume(id: String, token: String?): ScheduledOrder? {
    val all = read()
    val order = all.firstOrNull { it.id == id && it.state == ScheduledOrder.State.PENDING && (token == null || it.token == token) } ?: return null
    val consumed = order.copy(state = ScheduledOrder.State.CONSUMED)
    write(all.map { if (it.id == id) consumed else it })
    return consumed
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
    fun encode(values: List<ScheduledOrder>): String = JSONArray(values.map { o ->
      JSONObject().put("id", o.id).put("order", o.order).put("inner", o.inner).put("fireAt", o.fireAt).put("fingerprint", o.fingerprint)
        .put("label", o.label).put("createdAt", o.createdAt).put("token", o.token).put("state", o.state.name)
        .apply { o.condition?.let { put("condition", conditionCode(it)) } }
        .apply { if (o.message.isNotEmpty()) put("message", o.message) }
    }).toString()

    fun decode(text: String): List<ScheduledOrder> {
      require(text.length <= 65_536)
      val array = JSONArray(text)
      return (0 until minOf(array.length(), 32)).map { i ->
        val o = array.getJSONObject(i)
        ScheduledOrder(o.getString("id"), o.getString("order"), o.getString("inner"), o.getLong("fireAt"), o.getString("fingerprint"),
          o.optString("condition").takeIf { it.isNotEmpty() }?.let(::condition), o.getString("label"), o.getLong("createdAt"),
          o.getString("token"), ScheduledOrder.State.valueOf(o.getString("state")),
          InstinctLogEvents.messageId(o.optString("message")))
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
    val valid = check(order.inner) as? ProposalCheck.Valid ?: return Decision.Drop("la orden ya no es válida.")
    if (valid.deferred != null) return Decision.Drop("la orden ya no es válida.")
    return if (now - order.fireAt > InstinctDeferral.LATE_MILLIS) Decision.Late(valid.command) else Decision.Run(valid.command)
  }
}
