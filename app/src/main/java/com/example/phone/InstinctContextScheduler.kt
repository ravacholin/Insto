package com.example.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import java.util.Calendar

/** Adapts Android state to the scheduler and journal. No periodic work or network connection. */
internal object InstinctContextScheduler {
  const val ACTION_TICK = "com.example.phone.ACTION_INSTINCT_CONTEXT_TICK"
  const val FINGERPRINT = "insto-local-context"
  const val ONLY_SIGNED = "Enviá las órdenes de reglas por el buzón firmado."
  private const val DAY = 24 * 60 * 60_000L
  private val lock = Any()

  fun rules(app: Context): List<ContextRule> =
    runCatching { InstinctExecutions.existingJournal(app)?.contextRules().orEmpty() }.getOrDefault(emptyList())

  fun create(app: Context, spec: ContextRuleSpec, expiresAt: Long? = null, enabled: Boolean = true): String? {
    if (enabled) blocker(app, spec)?.let { return it }
    val now = InstinctExecutionJournal.clock()
    val rule = ContextRule(spec, expiresAt ?: now + spec.days * DAY, enabled = enabled)
    if (rule.expiresAt <= now) return "La regla ya venció."
    if (rule.expiresAt - now > ContextRuleSpec.MAX_DAYS * DAY) return "La regla excede el plazo máximo de 30 días."
    if (!runCatching { InstinctExecutions.journal(app).saveContextRule(rule) }.getOrDefault(false))
      return "No se pudo guardar la regla. El máximo es ${ContextRuleSpec.MAX_RULES} reglas vigentes."
    signal(app)
    return null
  }

  fun blocker(app: Context, spec: ContextRuleSpec): String? {
    val policy = InstinctAutonomySettings(app).policy()
    val notice = InstinctNotice.text(spec.action)
    if (notice != null) {
      if (!InstinctNotice.valid(notice)) return ContextRuleSpec.INVALID
      if (policy.level(InstinctCategory.AVISOS) == InstinctAutonomy.NUNCA) return InstinctAutonomyPolicy.REFUSED
    } else {
      val command = InstinctProposalValidator.backgroundAction(spec.action, PhoneCommandParser(), Calendar.getInstance())
        ?: return ContextRuleSpec.INVALID
      if (InstinctProposalCenter.refusedByPolicy(app, command)) return InstinctAutonomyPolicy.REFUSED
    }
    return null
  }

  data class Change(val ok: Boolean, val detail: String)

  fun change(app: Context, id: String, enabled: Boolean?): Change {
    if (enabled == true) {
      val spec = rules(app).singleOrNull { it.shortId == id }?.spec ?: return Change(false, "No hay ninguna regla con id $id.")
      blocker(app, spec)?.let { return Change(false, it) }
    }
    val changed = runCatching { InstinctExecutions.existingJournal(app)?.changeContextRule(id, enabled) == true }.getOrDefault(false)
    if (!changed) return Change(false, "No se pudo cambiar la regla $id. Revisá su id y su vencimiento.")
    if (enabled != true) InstinctProposalCenter.removeCancelledContextNotifications(app)
    InstinctLog.recoverQuestionReplies(app)
    signal(app)
    return Change(true, "Regla $id ${when (enabled) { true -> "activada"; false -> "desactivada"; null -> "eliminada" }}.")
  }

  fun status(app: Context, rule: ContextRule, now: Long = InstinctExecutionJournal.clock()): String {
    val status = when {
      now >= rule.expiresAt -> "vencida"
      !rule.enabled -> "desactivada"
      !InstinctProposalSettings(app).isEnabled() -> "en pausa (Recibir órdenes está apagado)"
      else -> blocker(app, rule.spec) ?: rule.status
    }
    return "${rule.shortId} · $status · ${rule.spec.line}"
  }

  fun describe(app: Context, shortId: String? = null): String {
    val rules = rules(app)
    if (shortId == null) return rules.joinToString(" | ") { rule ->
      val state = when {
        InstinctExecutionJournal.clock() >= rule.expiresAt -> "vencida"
        !rule.enabled -> "desactivada"
        else -> "activa"
      }
      "${rule.shortId} $state"
    }.ifEmpty { "No hay reglas contextuales." }
    val rule = rules.singleOrNull { it.shortId == shortId } ?: return "No hay ninguna regla con id $shortId."
    val latest = runCatching { InstinctExecutions.journal(app).byOccurrence(rule.id).lastOrNull() }.getOrNull()
    val cap = if (NotificationAccessService.connected == null) " · falta Insto · notificaciones para los eventos en vivo" else ""
    val calendar = if (rule.spec.conditions.any { it.signal == ContextSignal.CALENDAR } &&
      app.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      " · falta permiso para leer el calendario" else ""
    return status(app, rule) + cap + calendar + " · vence ${InstinctLogEvents.iso(rule.expiresAt)}" +
      (latest?.let { " · ejecución ${it.shortId}: ${it.state.code}, ${it.reason ?: it.result.orEmpty()}" } ?: "")
  }

  fun signal(context: Context, unlock: Boolean = false) {
    val app = context.applicationContext
    if (!InstinctProposalSettings(app).isEnabled() || InstinctExecutions.existingJournal(app) == null) return
    InstinctStatePublisher.background(Runnable { runCatching { evaluate(app, unlock) } })
  }

  fun stop(app: Context) {
    synchronized(lock) {
      arm(app, null)
      runCatching { InstinctExecutions.existingJournal(app)?.pauseContextRules() }
    }
    runCatching { InstinctLog.recoverQuestionReplies(app) }
  }

  fun evaluate(app: Context, unlock: Boolean = false) {
    val due = synchronized(lock) {
      if (!InstinctProposalSettings(app).isEnabled()) return
      val journal = InstinctExecutions.existingJournal(app) ?: return
      val now = InstinctExecutionJournal.clock()
      val active = journal.contextRules().filter { it.enabled && now < it.expiresAt }
      if (active.isEmpty()) { arm(app, null); return }
      val signals = active.flatMap { it.spec.conditions }.mapTo(HashSet()) { it.signal }
      val calendar = if (ContextSignal.CALENDAR in signals) runCatching {
        InstinctCalendarReader(app).instances(now, active.maxOf { it.expiresAt })
      }.getOrNull() else null
      val events = calendar?.filterNot { it.allDay }
      val snapshot = snapshot(app, now, signals, events, unlock)
      val due = journal.evaluateContextRules(snapshot, now)
      val remaining = journal.contextRules().filter { it.enabled && now < it.expiresAt }
      val clocks = remaining.flatMap { it.spec.conditions }.mapNotNull { it.hours }.distinct()
        .flatMap { listOf(ContextRuleSpec.nextTime(now, it.start), ContextRuleSpec.nextTime(now, it.end)) }
      val boundaries = events.orEmpty().flatMap { listOf(it.begin, it.end) }
      val next = (remaining.map { it.expiresAt } + remaining.mapNotNull { it.candidateAt?.plus(ContextRuleSpec.DEBOUNCE) } +
        clocks + boundaries).filter { it > now }.minOrNull()
      runCatching { arm(app, next) }
      due
    }
    if (due.isNotEmpty()) InstinctExecutionRecoveryManager.later(app)
    due.forEach { row -> runCatching { InstinctProposalCenter.dispatchContext(app, row) } }
  }

  fun hasPermissions(app: Context, spec: ContextRuleSpec): Boolean =
    spec.conditions.none { it.signal == ContextSignal.CALENDAR } ||
      app.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED

  /** The unlock pulse was durably admitted; recheck its state predicates against today's Android data. */
  fun stillMatches(app: Context, rule: ContextRule): Boolean {
    val now = InstinctExecutionJournal.clock()
    val signals = rule.spec.conditions.mapTo(HashSet()) { it.signal }
    val events = if (ContextSignal.CALENDAR in signals) runCatching {
      InstinctCalendarReader(app).instances(now)
    }.getOrNull()?.filterNot { it.allDay } else null
    return rule.spec.evaluate(snapshot(app, now, signals, events, unlock = true)) == true
  }

  private fun snapshot(app: Context, now: Long, signals: Set<ContextSignal>, events: List<CalendarInstance>?, unlock: Boolean): ContextSnapshot {
    val battery = if (ContextSignal.BATTERY in signals || ContextSignal.CHARGING in signals)
      runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() else null
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val network = if (ContextSignal.WIFI in signals || ContextSignal.ONLINE in signals) runCatching {
      val manager = app.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
      val active = manager.activeNetwork
      if (active == null) false to false else manager.getNetworkCapabilities(active)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) to it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      }
    }.getOrNull() else null
    val audio = app.getSystemService(AudioManager::class.java)
    return ContextSnapshot(
      battery = if (level >= 0 && scale > 0) level * 100 / scale else null,
      charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 },
      wifi = network?.first, online = network?.second,
      media = if (ContextSignal.MEDIA in signals) runCatching { audio?.isMusicActive }.getOrNull() else null,
      calendar = events?.any { it.begin <= now && now < it.end },
      headphones = if (ContextSignal.HEADPHONES in signals) runCatching {
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { InstinctAudioDevices.headphones(it.type) }
      }.getOrNull() else null,
      minute = InstinctAutonomySettings.minuteOfDay(now), unlock = unlock,
    )
  }

  private fun arm(app: Context, at: Long?) {
    val manager = app.getSystemService(AlarmManager::class.java) ?: return
    val pending = PendingIntent.getBroadcast(app, 0, Intent(app, InstinctScheduleReceiver::class.java).setAction(ACTION_TICK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    if (at == null) { manager.cancel(pending); return }
    if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
      try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); return }
      catch (_: SecurityException) { /* Permission can change after the check. */ }
    }
    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
  }
}
