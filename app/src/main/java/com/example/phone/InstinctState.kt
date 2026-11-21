package com.example.phone

import org.json.JSONObject

/**
 * «Estado del teléfono para Instinct» (1.16.0): a read-only snapshot Insto publishes in the log so Instinct decides
 * with context. The pure part: the fixed fields, their serialization, the comparison and when to publish. Nothing
 * here reads Android. See docs/INSTINCT-PROPUESTAS.md, «Estado del teléfono».
 *
 * Fixed fields, never more: the ringer mode and Do not disturb, the time of the next alarm (never its label), the
 * current and next calendar events in the next 12 hours, whether headphones or Bluetooth audio are connected (the
 * type, never a device name), whether the screen is locked, the temporary mode and the scheduled Instinct orders.
 * Never volume, battery, network, location, contacts, notifications, app usage nor any identifier.
 */
data class InstinctStateSnapshot(
  val sonido: String,
  val dnd: String,
  val alarma: String,
  val evento: String,
  val audio: String,
  val bloqueado: String,
  val modoTemporal: String,
  val programadas: String,
) {
  /** The fields in a fixed order, as `clave=valor`. */
  val entries: List<Pair<String, String>> get() = listOf("sonido" to sonido, "dnd" to dnd, "alarma" to alarma, "evento" to evento,
    "audio" to audio, "bloqueado" to bloqueado, "modo_temporal" to modoTemporal, "programadas" to programadas)

  /** «sonido=vibración; dnd=no; alarma=07:00; …», on one line, at most [InstinctState.MAX_DETAIL] characters. */
  fun detail(): String = InstinctAck.cut(entries.joinToString("; ") { (k, v) -> "$k=${InstinctState.value(v)}" }, InstinctState.MAX_DETAIL)

  fun json(): JSONObject = JSONObject().apply { entries.forEach { (k, v) -> put(k, v) } }

  companion object {
    fun from(o: JSONObject): InstinctStateSnapshot = InstinctStateSnapshot(o.getString("sonido"), o.getString("dnd"), o.getString("alarma"),
      o.getString("evento"), o.getString("audio"), o.getString("bloqueado"), o.getString("modo_temporal"), o.getString("programadas"))
  }
}

/** A calendar event as the snapshot shows it: title and local times. */
data class InstinctStateEvent(val title: String, val begin: Long, val end: Long)

object InstinctState {
  const val KIND = "estado"
  const val MAX_DETAIL = 600
  const val MAX_TITLE = 60
  const val NO_DATA = "sin datos"
  const val NO_PERMISSION = "sin permiso"
  const val FREE = "libre"
  const val DEBOUNCE_MILLIS = 30_000L
  const val HEARTBEAT_MILLIS = 30 * 60_000L
  /** A heartbeat that finds a snapshot younger than this skips: something else published it recently. */
  const val HEARTBEAT_SLACK_MILLIS = 5 * 60_000L
  /** Events that start within this window count as «próximo». */
  const val LOOKAHEAD_MILLIS = 12 * 60 * 60_000L

  /** Why a snapshot is being considered. */
  enum class Reason { CHANGE, HEARTBEAT, AFTER_ORDER, QUERY, MANUAL }

  /**
   * A value as it can travel: on one line, without the separators of the detail line and never able to start an
   * order. «#» is dropped altogether, so not even a calendar title can write «#insto …».
   */
  fun value(raw: String): String = InstinctAck.flat(raw.replace("#", "").replace(";", ",").replace("=", "-")).ifEmpty { NO_DATA }

  fun ringer(mode: Int?): String = when (mode) {
    0 -> "silencio"
    1 -> "vibración"
    2 -> "normal"
    else -> NO_DATA
  }

  fun yesNo(value: Boolean?): String = when (value) { true -> "sí"; false -> "no"; null -> NO_DATA }

  /** The current event and the next one, or «libre». Titles cut to [MAX_TITLE] and cleaned with [value]. */
  fun events(current: InstinctStateEvent?, next: InstinctStateEvent?, clock: (Long) -> String): String {
    fun show(e: InstinctStateEvent) = "${InstinctAck.cut(value(e.title), MAX_TITLE)} ${clock(e.begin)}–${clock(e.end)}"
    val parts = listOfNotNull(current?.let { "ahora ${show(it)}" }, next?.let { "próximo ${show(it)}" })
    return if (parts.isEmpty()) FREE else parts.joinToString(" · ")
  }

  /** From the events in the window: the one in progress (the latest started) and the next one to start. */
  fun pick(events: List<InstinctStateEvent>, now: Long): Pair<InstinctStateEvent?, InstinctStateEvent?> {
    val current = events.filter { it.begin <= now && it.end > now }.maxByOrNull { it.begin }
    val next = events.filter { it.begin > now && it.begin - now <= LOOKAHEAD_MILLIS }.minByOrNull { it.begin }
    return current to next
  }

  /** «auriculares», «Bluetooth», «auriculares y Bluetooth» or «no». Types only, never names. */
  fun audio(wired: Boolean?, bluetooth: Boolean?): String = when {
    wired == null && bluetooth == null -> NO_DATA
    wired == true && bluetooth == true -> "sí (auriculares y Bluetooth)"
    wired == true -> "sí (auriculares)"
    bluetooth == true -> "sí (Bluetooth)"
    else -> "no"
  }

  fun scheduled(count: Int, next: String?): String = if (count == 0) "ninguna" else "$count, próxima $next"

  /**
   * Whether to publish [current] now. A change only when something differs from the last published snapshot. A
   * heartbeat also when unchanged, unless the last one is younger than [HEARTBEAT_MILLIS] minus
   * [HEARTBEAT_SLACK_MILLIS] (something else published it recently). After an order, a query or by hand, always.
   */
  fun shouldPublish(reason: Reason, last: InstinctStateSnapshot?, lastAt: Long?, current: InstinctStateSnapshot, now: Long): Boolean = when (reason) {
    Reason.CHANGE -> current != last
    Reason.HEARTBEAT -> current != last || lastAt == null || now - lastAt !in 0 until HEARTBEAT_MILLIS - HEARTBEAT_SLACK_MILLIS
    Reason.AFTER_ORDER, Reason.QUERY, Reason.MANUAL -> true
  }

  /** The last published snapshot and when, as `instinct-state-last.json` keeps it. */
  fun encodeLast(snapshot: InstinctStateSnapshot, at: Long): String = JSONObject().put("at", at).put("state", snapshot.json()).toString()

  fun decodeLast(text: String): Pair<InstinctStateSnapshot, Long>? = runCatching {
    require(text.length <= 8_192)
    val o = JSONObject(text)
    InstinctStateSnapshot.from(o.getJSONObject("state")) to o.getLong("at")
  }.getOrNull()
}

/**
 * Groups state changes: the first change of a burst schedules one publication [delayMillis] later and the following
 * ones join it, so a flickering setting never sends an event per flicker. [clock] is injectable for tests.
 */
class InstinctStateDebouncer(private val clock: () -> Long, private val delayMillis: Long = InstinctState.DEBOUNCE_MILLIS) {
  private var dueAt: Long? = null

  /** A change happened. Returns the delay until the pending publication, or null when one was already pending. */
  @Synchronized fun signal(): Long? {
    if (dueAt != null) return null
    dueAt = clock() + delayMillis
    return delayMillis
  }

  /** True once, when the pending publication is due. */
  @Synchronized fun due(): Boolean {
    val at = dueAt ?: return false
    if (clock() < at) return false
    dueAt = null
    return true
  }

  @Synchronized fun cancel() { dueAt = null }
}
