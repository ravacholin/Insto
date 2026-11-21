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
  fun schedule(context: Context, check: ProposalCheck.Valid, fingerprint: String): Outcome {
    val deferred = check.deferred ?: return Outcome.Refused("no es una orden diferida.")
    recover(context)
    val order = ScheduledOrder(UUID.randomUUID().toString(), check.order, deferred.inner, deferred.fireAt, fingerprint,
      deferred.condition, deferred.label, wallClock())
    return when (val added = runCatching { store(context).add(order) }.getOrNull()) {
      is InstinctScheduleStore.Add.Added -> { alarms(context.applicationContext, order, true); Outcome.Scheduled(order) }
      InstinctScheduleStore.Add.Full -> Outcome.Refused("ya hay ${InstinctDeferral.MAX_SCHEDULED} órdenes programadas; cancelá alguna antes.")
      InstinctScheduleStore.Add.OutOfRange -> Outcome.Refused("la hora queda fuera de las próximas 24 horas.")
      null -> Outcome.Refused("no se pudo guardar la orden programada.")
    }
  }

  @Synchronized internal fun ackKey(orderId: String): Long = ackKeys.getOrPut(orderId) { nextAckKey-- }
  @Synchronized private fun takeAckKey(orderId: String): Long? = ackKeys.remove(orderId)

  fun cancel(context: Context, id: String): Boolean {
    val order = store(context).pending().firstOrNull { it.id == id } ?: return false
    alarms(context.applicationContext, order, false)
    return store(context).remove(id)
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
    val zone = Calendar.getInstance()
    return pending.joinToString("; ", postfix = ".") { "${InstinctDeferral.clock(it.fireAt, zone)} ${it.condition?.label?.let { c -> "$c " } ?: ""}${it.inner}" }
  }

  /**
   * After a boot, a time or time zone change, or a process start: orders left consumed by a process death may have
   * run and are reported as uncertain, never run again; the rest get their alarms again.
   */
  fun rearm(context: Context) {
    val app = context.applicationContext
    recover(app)
    store(app).pending().forEach { alarms(app, it, true) }
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
  suspend fun fire(context: Context, id: String, token: String?) {
    val app = context.applicationContext
    val store = store(app)
    firing += id
    val order = runCatching { store.consume(id, token) }.getOrNull() ?: run { firing -= id; return }
    try {
      val now = wallClock()
      val routines = InstinctRoutineGrants(app).access()
      val parser = PhoneCommandParser()
      val decision = ScheduleFirePolicy.decide(order, InstinctProposalSettings(app).isEnabled(), InstinctProposalCenter.fingerprint(app), now) {
        InstinctProposalValidator.check(it, parser, Calendar.getInstance().apply { timeInMillis = now }, routines)
      }
      val ackKey = takeAckKey(order.id)
      fun tell(entry: AckEntry) { if (ackKey != null) InstinctProposalCenter.report(app, ackKey, entry) }
      if (decision is ScheduleFirePolicy.Decision.Drop) {
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
      val proposal = InstinctProposalCenter.book.offer(order.order, command, order.fingerprint) ?: run {
        tell(AckEntry(AckKind.ERROR, order.order, "ya había una orden igual esperando confirmación; no se duplica."))
        return
      }
      if (ackKey != null) InstinctProposalCenter.acks.relink(ackKey, proposal.id)
      if (decision is ScheduleFirePolicy.Decision.Late) InstinctProposalCenter.askLate(app, proposal,
        "Llegó tarde: estaba programada ${order.label}.")
      else InstinctProposalCenter.dispatch(app, proposal)
    } finally {
      // It ran, waits in the book for Confirmar, or was dropped: it never fires again.
      runCatching { store.remove(order.id) }
      firing -= id
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
      Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
        runCatching { InstinctScheduler.rearm(context) }
    }
  }
}
