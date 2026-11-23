package com.example.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Deferred orders from Instinct on Android: the store in noBackupFilesDir, one AlarmManager alarm per order to a
 * receiver that is not exported, and the firing. When an order fires it is consumed on disk before anything else,
 * checked again from scratch, its weather condition evaluated (fail-safe) and then handed over exactly like an
 * order that just arrived: [InstinctProposalCenter.autoRunBlocker] and [InstinctProposalCenter.runInBackground].
 */
object InstinctScheduler {
  const val ACTION_FIRE = "com.example.phone.ACTION_FIRE_INSTINCT_SCHEDULED"
  const val EXTRA_ID = "insto.instinct.scheduled"
  const val EXTRA_TOKEN = "insto.instinct.scheduled.token"
  private const val FILE = "instinct-scheduled.json"
  /** Weather requests at firing time have this long; after it there is no data and nothing runs. */
  internal const val WEATHER_TIMEOUT_MILLIS = 20_000L

  internal var wallClock: () -> Long = System::currentTimeMillis
  /** The structured weather; tests replace it. The SMN with its existing fallback in production. */
  internal var weather: WeatherReadings? = null
  private val readings: WeatherReadings get() = weather ?: com.example.phone.weather.SmnWeatherService.shared
  /** A seam for tests; arms nothing when replaced. */
  internal var alarms: (Context, ScheduledOrder, Boolean) -> Unit = ::setAlarm
  /**
   * The calendar instances around [now] (1.17.0), without all-day ones' titles or any title at all; null without
   * READ_CALENDAR. Tests replace it.
   */
  internal var calendar: (Context, Long) -> List<CalendarInstance>? = { context, now -> InstinctCalendarReader(context).instances(now) }
  /** Runs a firing started by the phone's state (headphones, unlock) off the caller; tests run it inline. */
  internal var launch: (suspend () -> Unit) -> Unit = { block ->
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { runCatching { block() } }
  }

  /** What makes [fire] run: its alarm, or the phone's state for a state trigger. */
  enum class Cause { ALARM, STATE }

  /** Orders this process is firing right now (waiting for the weather): consumed, but not interrupted. */
  private val firing = java.util.Collections.synchronizedSet(HashSet<String>())

  /** In memory only: which reply line each scheduled order belongs to, while the reply handle is valid. */
  private val ackKeys = HashMap<String, Long>()
  private var nextAckKey = -1L

  fun store(context: Context) = InstinctScheduleStore(File(context.applicationContext.noBackupFilesDir, FILE)) { wallClock() }

  fun pending(context: Context): List<ScheduledOrder> = store(context).pending()

  sealed interface Outcome {
    data class Scheduled(val order: ScheduledOrder) : Outcome
    data class Refused(val why: String) : Outcome
  }

  /** Stores a validated deferred order (its inner order already passed the validator) and arms its alarm. */
  fun schedule(context: Context, check: ProposalCheck.Valid, fingerprint: String, message: String = "",
    source: ProposalSource = ProposalSource.WHATSAPP): Outcome {
    var deferred = check.deferred ?: return Outcome.Refused("no es una orden diferida.")
    recover(context)
    val now = wallClock()
    // An event trigger reads the calendar now: the instance and its time are stored, and read again when it fires.
    if (deferred.trigger.unresolved) {
      val events = runCatching { calendar(context.applicationContext, now) }.getOrNull()
        ?: return Outcome.Refused("Insto no tiene permiso para leer el calendario; la persona lo da en Ajustes → Propuestas de Instinct.")
      when (val pick = InstinctTriggers.pickEvent(deferred.trigger, events, now, deferred.expiresAt ?: now)) {
        is InstinctTriggers.Pick.None -> return Outcome.Refused(pick.why)
        is InstinctTriggers.Pick.Found -> deferred = deferred.copy(trigger = pick.trigger, fireAt = pick.fireAt,
          label = InstinctTriggers.label(pick.trigger, deferred.expiresAt, pick.fireAt, now))
      }
    }
    val order = ScheduledOrder(UUID.randomUUID().toString(), check.order, deferred.inner, deferred.fireAt, fingerprint,
      deferred.condition, deferred.label, now, message = InstinctLogEvents.messageId(message), source = source,
      trigger = deferred.trigger, expiresAt = deferred.expiresAt)
    return when (val added = runCatching { store(context).add(order) }.getOrNull()) {
      is InstinctScheduleStore.Add.Added -> { alarms(context.applicationContext, order, true); Outcome.Scheduled(order) }
      InstinctScheduleStore.Add.Full -> Outcome.Refused("ya hay ${InstinctDeferral.MAX_SCHEDULED} órdenes programadas; cancelá alguna antes.")
      InstinctScheduleStore.Add.FullRecurring -> Outcome.Refused("ya hay ${InstinctTriggers.MAX_RECURRING} órdenes recurrentes; cancelá alguna antes.")
      InstinctScheduleStore.Add.OutOfRange -> Outcome.Refused("la hora queda fuera de las próximas 24 horas.")
      null -> Outcome.Refused("no se pudo guardar la orden programada.")
    }
  }

  @Synchronized internal fun ackKey(orderId: String): Long = ackKeys.getOrPut(orderId) { nextAckKey-- }
  @Synchronized private fun takeAckKey(orderId: String): Long? = ackKeys.remove(orderId)
  @Synchronized private fun newAckKey(): Long = nextAckKey--

  fun cancel(context: Context, id: String): Boolean {
    val order = store(context).pending().firstOrNull { it.id == id } ?: return false
    alarms(context.applicationContext, order, false)
    return store(context).remove(id)
  }

  /** «#insto cancelar programada <id>» and the button in Settings: the order, or the whole recurrence. */
  fun cancelShort(context: Context, shortId: String): List<ScheduledOrder> {
    val cancelled = runCatching { store(context).cancelShort(shortId) }.getOrDefault(emptyList())
    cancelled.forEach { alarms(context.applicationContext, it, false) }
    return cancelled
  }

  /** The answer to «#insto cancelar programada <id>». */
  fun cancelShortAnswer(context: Context, shortId: String): String {
    val cancelled = cancelShort(context, shortId)
    val first = cancelled.firstOrNull() ?: return "No hay ninguna orden programada con el id $shortId."
    return "Se canceló $shortId: ${first.label}: ${first.inner}."
  }

  /** One line per order, as «ver programadas» and Settings show it: «abc123 · <disparador>: <orden>». */
  fun line(order: ScheduledOrder, now: Long = wallClock()): String {
    val label = InstinctTriggers.label(order.trigger, order.expiresAt, order.fireAt, now, condition = order.condition)
    return "${order.shortId} · $label: ${order.inner}"
  }

  /**
   * The phone did something (from the callbacks of «Insto · notificaciones»): every pending state trigger it
   * matches fires once. One past its expiry lapses instead.
   */
  fun onPhoneEvent(context: Context, event: PhoneEvent) {
    val app = context.applicationContext
    if (!InstinctProposalSettings(app).isEnabled()) return
    if (event == PhoneEvent.UNLOCK) InstinctContextScheduler.signal(app, unlock = true)
    val matching = runCatching { store(app).pending() }.getOrDefault(emptyList()).filter { InstinctTriggers.fires(it.trigger, event) }
    matching.forEach { order -> launch { fire(app, order.id, order.token, Cause.STATE) } }
  }

  /**
   * «Nunca» for a category (1.17.0): every recurrence whose order it refuses is cancelled whole and logged. Other
   * scheduled orders are refused when they fire, as before.
   */
  fun cancelRefused(context: Context) {
    val app = context.applicationContext
    val parser = PhoneCommandParser()
    val routines = InstinctRoutineGrants(app).access()
    val pending = runCatching { store(app).pending() }.getOrDefault(emptyList()).filter { it.recurring }
    pending.distinctBy { it.series }.forEach { order ->
      val command = (InstinctProposalValidator.check(order.inner, parser, Calendar.getInstance(), routines) as? ProposalCheck.Valid)?.command
        ?: return@forEach
      if (!InstinctProposalCenter.refusedByPolicy(app, command)) return@forEach
      cancelShort(app, order.shortId)
      InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}",
        "Se canceló la recurrente entera: elegiste que Instinct nunca haga esto.")
      tellLog(app, order, AckEntry(AckKind.ERROR, order.order, "${InstinctAutonomyPolicy.REFUSED}; se canceló la recurrente entera."))
    }
  }

  /** The acknowledgement line of [order]: the chat's while its handle lives; for the log, also after a restart. */
  private fun ackKey(app: Context, order: ScheduledOrder): Long? =
    takeAckKey(order.id)?.takeIf(InstinctProposalCenter.acks::tracks)
      ?: newAckKey().takeIf { InstinctProposalCenter.adopt(app, it, order.message, AckEntry(AckKind.SCHEDULED, order.order, order.label),
        logOnly = order.source == ProposalSource.BUZON) }

  /**
   * What only triggers and recurrences produce (1.17.0: lapsed, the event deleted, cancelled by «Nunca») goes to the
   * log only, whatever the channel: nothing new is written in the WhatsApp chat.
   */
  private fun tellLog(app: Context, order: ScheduledOrder, entry: AckEntry) {
    takeAckKey(order.id)
    val key = newAckKey()
    if (InstinctProposalCenter.adopt(app, key, order.message, AckEntry(AckKind.SCHEDULED, order.order, order.label), logOnly = true))
      InstinctProposalCenter.report(app, key, entry)
  }

  /** «#insto cancelar programadas», the switch turned off, or every cancel button at once. */
  fun cancelAll(context: Context): Int {
    val cancelled = runCatching { store(context).cancelAll() }.getOrDefault(emptyList())
    cancelled.forEach { alarms(context.applicationContext, it, false) }
    return cancelled.size
  }

  /** The answer to «#insto ver programadas». */
  fun describe(context: Context): String {
    val pending = pending(context)
    if (pending.isEmpty()) return "No hay órdenes programadas."
    val now = wallClock()
    return pending.joinToString("; ", postfix = ".") { line(it, now) }
  }

  /**
   * After a boot, a time or time zone change, or a process start: orders left consumed by a process death may have
   * run and are reported as uncertain, never run again; the rest get their alarms again.
   */
  fun rearm(context: Context, zoneChanged: Boolean = false) {
    val app = context.applicationContext
    recover(app)
    // A recurrence is a local time: after a time zone change its next occurrence is the same hour in the new zone.
    if (zoneChanged) runCatching { relocate(app) }
    store(app).pending().forEach { alarms(app, it, true) }
  }

  private fun relocate(app: Context) {
    val now = wallClock()
    val zone = java.util.TimeZone.getDefault()
    store(app).pending().filter { it.recurring }.forEach { order ->
      val r = order.trigger as Trigger.Recurring
      val next = InstinctTriggers.next(r.days, r.minuteOfDay, now, r.until, zone)
      if (next == null) { alarms(app, order, false); store(app).remove(order.id) }
      else if (next != order.fireAt) store(app).reschedule(order.id, r, next, InstinctTriggers.label(r, null, next, now, zone, order.condition))
    }
  }

  /** Orders a process death left consumed: reported once as uncertain, never run again. */
  private fun recover(context: Context) {
    val app = context.applicationContext
    runCatching { store(app).recoverInterrupted(HashSet(firing)) }.getOrDefault(emptyList()).forEach { order ->
      InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}",
        "Pudo haberse ejecutado antes de que el teléfono se reiniciara o se cerrara Insto. Revisalo; no se repite.")
    }
  }

  /**
   * The alarm fired. Consumed on disk first, so it runs at most once. Then: revalidation, the weather condition
   * (fail-safe), lateness and the same path as an order that just arrived.
   */
  suspend fun fire(context: Context, id: String, token: String?, cause: Cause = Cause.ALARM) {
    val app = context.applicationContext
    val store = store(app)
    // First, what this firing means (1.17.0): a lapsed trigger expires, an event that moved is rescheduled.
    val peeked = runCatching { store.peek(id, token) }.getOrNull() ?: return
    if (cause == Cause.ALARM && InstinctTriggers.onAlarm(peeked, wallClock()) == InstinctTriggers.AlarmAction.EXPIRE) {
      expire(app, peeked); return
    }
    if (cause == Cause.STATE && peeked.expiresAt != null && wallClock() > peeked.expiresAt) { expire(app, peeked); return }
    if (peeked.trigger.byEvent && !checkEvent(app, peeked)) return
    firing += id
    val consumed = runCatching { store.consumeOccurrence(id, token) }.getOrNull() ?: run { firing -= id; return }
    val order = consumed.order
    // The next occurrence of a recurrence is on disk already: it never depends on how this one ends.
    consumed.successor?.let { alarms(app, it, true) }
    if (order.trigger.byState) alarms(app, order, false)
    try {
      // 1.20.0: each firing is an execution of its own in the journal, under the row that scheduled it. Its id is the
      // occurrence's, so a second firing of the same occurrence finds it and never runs twice.
      val execution = try { occurrence(app, order) } catch (_: Throwable) {
        InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}", "No se ejecutó: ${InstinctProposalCenter.NOT_RECORDED}")
        ackKey(app, order)?.let { InstinctProposalCenter.report(app, it, AckEntry(AckKind.ERROR, order.order, InstinctProposalCenter.NOT_RECORDED)) }
        return
      } ?: return
      val now = wallClock()
      val routines = InstinctRoutineGrants(app).access()
      val parser = PhoneCommandParser()
      val decision = ScheduleFirePolicy.decide(order, InstinctProposalSettings(app).isEnabled(), InstinctProposalCenter.fingerprint(app), now) {
        InstinctProposalValidator.check(it, parser, Calendar.getInstance().apply { timeInMillis = now }, routines)
      }
      // The chat's line while its handle lives; for the log, also after a restart, from the stored message id.
      val ackKey = ackKey(app, order)
      ackKey?.let { InstinctExecutions.link(it, execution) }
      fun tell(entry: AckEntry) {
        if (ackKey != null) InstinctProposalCenter.report(app, ackKey, entry)
        else InstinctExecutions.move(app, execution, InstinctExecutions.stateOf(entry) ?: return, reason = entry.message)
      }
      if (decision is ScheduleFirePolicy.Decision.Drop) {
        // A recurrence that is no longer valid would be dropped every day: it is cancelled whole.
        if (order.recurring) cancelShort(app, order.shortId)
        InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}", "No se ejecutó: ${decision.why}")
        tell(AckEntry(AckKind.ERROR, order.order, decision.why))
        return
      }
      order.condition?.let { condition ->
        val verdict = WeatherConditions.checkWithin(condition, readings, WEATHER_TIMEOUT_MILLIS)
        when (verdict) {
          WeatherConditions.Verdict.UNKNOWN -> {
            InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}",
              "No se ejecutó: no hay datos del clima para comprobar «${condition.label}».")
            tell(AckEntry(AckKind.ERROR, order.order, "sin datos del clima; no se ejecutó."))
            return
          }
          WeatherConditions.Verdict.NOT_MET -> {
            InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}",
              "No se ejecutó: no se cumplió «${condition.label}».")
            tell(AckEntry(AckKind.DISCARDED, order.order, "no se cumplió «${condition.label}»; no se ejecutó."))
            return
          }
          WeatherConditions.Verdict.MET -> Unit
        }
      }
      val command = when (decision) {
        is ScheduleFirePolicy.Decision.Run -> decision.command
        is ScheduleFirePolicy.Decision.Late -> decision.command
        else -> return
      }
      // «Nunca» since it was programmed: a recurrence is cancelled whole.
      if (order.recurring && InstinctProposalCenter.refusedByPolicy(app, command)) {
        cancelShort(app, order.shortId)
        InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}",
          "No se ejecutó y se canceló la recurrente entera: elegiste que Instinct nunca haga esto.")
        tell(AckEntry(AckKind.ERROR, order.order, "${InstinctAutonomyPolicy.REFUSED}; se canceló la recurrente entera."))
        return
      }
      val proposal = InstinctProposalCenter.book.offer(order.order, command, order.fingerprint, order.source, execution) ?: run {
        tell(AckEntry(AckKind.ERROR, order.order, "ya había una orden igual esperando confirmación; no se duplica."))
        return
      }
      InstinctExecutions.link(proposal.id, execution)
      InstinctExecutions.move(app, execution, ExecutionState.READY, proposalId = proposal.id)
      if (ackKey != null) { InstinctProposalCenter.acks.relink(ackKey, proposal.id); InstinctExecutions.relink(ackKey, proposal.id) }
      if (decision is ScheduleFirePolicy.Decision.Late) InstinctProposalCenter.askLate(app, proposal,
        "Llegó tarde: estaba programada ${order.label}.")
      else InstinctProposalCenter.dispatch(app, proposal)
    } finally {
      // It ran, waits in the book for Confirmar, or was dropped: it never fires again.
      runCatching { store.remove(order.id) }
      firing -= id
    }
  }

  /**
   * The journal row of this firing: admitted now (without a claim: the store already consumed the occurrence) under
   * the row that scheduled its series. Null when this occurrence already has a row: it fired before and never runs
   * again. Throws when the journal cannot be written.
   */
  private fun occurrence(app: Context, order: ScheduledOrder): String? {
    val journal = InstinctExecutions.journal(app)
    val parent = journal.byOccurrence(order.series).firstOrNull { it.transport !in FIRINGS }
    val transport = when {
      order.recurring -> ExecutionTransport.RECURRENCE
      order.trigger.byState || order.trigger.byEvent -> ExecutionTransport.TRIGGER
      else -> ExecutionTransport.SCHEDULE
    }
    val admitted = journal.admit(ExecutionIds.occurrence(order.id), transport, listOf(order.order), order.message,
      parent = parent?.executionId, occurrence = order.series, claim = false) as? InstinctExecutionJournal.Admission.Admitted ?: return null
    return admitted.records.single().executionId.also { InstinctExecutions.move(app, it, ExecutionState.VALIDATED) }
  }
  private val FIRINGS = setOf(ExecutionTransport.RECURRENCE, ExecutionTransport.TRIGGER, ExecutionTransport.SCHEDULE)

  /** A trigger that lapsed without firing: removed, told and logged as «vencida». */
  private fun expire(app: Context, order: ScheduledOrder) {
    if (runCatching { store(app).remove(order.id) }.getOrDefault(false).not()) return
    alarms(app, order, false)
    InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}", "Venció sin dispararse. No se ejecutó.")
    tellLog(app, order, AckEntry(AckKind.EXPIRED, order.order, "venció sin dispararse; no se ejecutó."))
  }

  /**
   * An event trigger's alarm: the instance is read again. True when it is still there at that time (it fires now).
   * Moved: rescheduled (or lapsed past its expiry). Deleted, or no permission to check: dropped and told.
   */
  private fun checkEvent(app: Context, order: ScheduledOrder): Boolean {
    val now = wallClock()
    val candidates = runCatching { calendar(app, now) }.getOrNull()
    val check = if (candidates == null) null else InstinctTriggers.matchEvent(order.trigger, candidates)
    fun drop(text: String, entry: AckEntry) {
      if (runCatching { store(app).remove(order.id) }.getOrDefault(false).not()) return
      InstinctProposalCenter.notifyScheduled(app, order, "Instinct había programado: ${order.summary}", text)
      tellLog(app, order, entry)
    }
    return when (check) {
      null -> { drop("No se ejecutó: Insto ya no puede leer el calendario.", AckEntry(AckKind.ERROR, order.order, "sin permiso para leer el calendario; no se ejecutó.")); false }
      InstinctTriggers.EventCheck.Deleted -> {
        drop("No se ejecutó: el evento se borró.", AckEntry(AckKind.DISCARDED, order.order, "el evento se borró; no se ejecutó.")); false
      }
      InstinctTriggers.EventCheck.Same -> true
      is InstinctTriggers.EventCheck.Moved -> when {
        // Earlier than planned, already past: it fires now.
        check.at <= now -> true
        order.expiresAt != null && check.at > order.expiresAt -> { expire(app, order); false }
        else -> {
          val trigger = when (val t = order.trigger) {
            is Trigger.EventEnd -> t.copy(at = check.at)
            is Trigger.EventStart -> t.copy(at = check.at)
            else -> t
          }
          val label = InstinctTriggers.label(trigger, order.expiresAt, check.at, now)
          runCatching { store(app).reschedule(order.id, trigger, check.at, label) }.getOrNull()?.let { moved ->
            alarms(app, moved, true)
            InstinctProposalCenter.notifyScheduled(app, moved, "Instinct había programado: ${moved.summary}",
              "El evento cambió de horario: se reprogramó.")
          }
          false
        }
      }
    }
  }

  private fun pendingIntent(context: Context, order: ScheduledOrder): PendingIntent = PendingIntent.getBroadcast(context, 0,
    Intent(context, InstinctScheduleReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("insto-scheduled:${order.id}"))
      .putExtra(EXTRA_ID, order.id).putExtra(EXTRA_TOKEN, order.token),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  /** Exact only when Android allows exact alarms; otherwise inexact, and a late firing waits for Confirmar. */
  private fun setAlarm(context: Context, order: ScheduledOrder, arm: Boolean) {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    val intent = pendingIntent(context, order)
    if (!arm) { manager.cancel(intent); return }
    val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    runCatching {
      if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, order.fireAt, intent)
      else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, order.fireAt, intent)
    }
  }
}

/**
 * The alarm of a scheduled order, and boot or clock changes to arm them again. Not exported: only Insto's own
 * PendingIntent, carrying the order's random token, and the system broadcasts reach it.
 */
class InstinctScheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      InstinctScheduler.ACTION_FIRE -> {
        val id = intent.getStringExtra(InstinctScheduler.EXTRA_ID) ?: return
        val token = intent.getStringExtra(InstinctScheduler.EXTRA_TOKEN) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
          try { runCatching { InstinctScheduler.fire(context, id, token) } } finally { pending.finish() }
        }
      }
      InstinctContextScheduler.ACTION_TICK -> contextRules(context)
      Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
        runCatching { InstinctScheduler.rearm(context, zoneChanged = intent.action == Intent.ACTION_TIMEZONE_CHANGED) }
        if (InstinctExecutions.existingJournal(context) != null) contextRules(context)
      }
    }
  }

  private fun contextRules(context: Context) {
    val pending = goAsync()
    InstinctStatePublisher.background(Runnable {
      try { runCatching { InstinctContextScheduler.evaluate(context.applicationContext) } }
      finally { pending?.finish() }
    })
  }
}
