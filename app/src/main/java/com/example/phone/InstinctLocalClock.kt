package com.example.phone

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject

internal enum class InstinctClockState {
  REGISTERING, SCHEDULED, FIRING, FIRED, CANCELLED, MISSED, UNCERTAIN;
  companion object { val ACTIVE = listOf(REGISTERING, SCHEDULED) }
}

internal data class InstinctClockEntry(
  val id: String, val at: Long, val elapsedAt: Long?, val boot: Int, val label: String, val state: InstinctClockState,
)

/** Exact alarms owned by Insto. The existing Reloj handoff remains a separate capability. */
internal object InstinctLocalClock {
  private const val CHANNEL = "instinct_clock"
  private const val FIRE = "com.example.phone.INSTINCT_CLOCK_FIRE"
  private const val FIRED_LATE_LIMIT = 15L * 60_000
  private val lock = Any()

  private fun journal(context: Context) = InstinctExecutions.journal(context)
  private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

  fun notificationsEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 26) return true
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
  }

  fun execute(context: Context, id: String, capability: String, params: JSONObject): PhoneActionResult = synchronized(lock) {
    if (capability == "insto_clock_cancel") return cancel(context, params.getString("clockId"))
    val now = System.currentTimeMillis()
    val duration = if (capability == "insto_timer") params.getLong("seconds") * 1000 else null
    val at = duration?.let { now + it } ?: params.getLong("at")
    if (at <= now || at - now > InstinctCapabilities.MAX_DURATION * 1000L) return rejected("Elegí un instante dentro de las próximas 24 horas.")
    val entry = InstinctClockEntry(id, at, duration?.let { SystemClock.elapsedRealtime() + it }, boot(context),
      params.optString("label", if (duration == null) "Alarma de Insto" else "Temporizador de Insto"), InstinctClockState.REGISTERING)
    val journal = journal(context)
    if (!journal.insertClock(entry)) return rejected("No se pudo registrar el reloj de Insto o se alcanzó el límite de 12 avisos.")
    try {
      makeChannel(context)
      arm(context, entry)
      if (!journal.moveClock(id, InstinctClockState.REGISTERING, InstinctClockState.SCHEDULED)) return uncertain()
      PhoneActionResult("Programado en Insto · $id.", verifiedLocally = true,
        executionData = JSONObject().put("clockId", id).toString())
    } catch (_: Exception) {
      journal.moveClock(id, InstinctClockState.REGISTERING, InstinctClockState.UNCERTAIN)
      uncertain()
    }
  }

  fun pending(context: Context): List<InstinctClockEntry> =
    journal(context).clockEntries().filter { it.state == InstinctClockState.SCHEDULED }

  fun cancel(context: Context, id: String): PhoneActionResult = synchronized(lock) {
    if (!journal(context).moveClock(id, InstinctClockState.SCHEDULED, InstinctClockState.CANCELLED))
      return rejected("Ese aviso ya no está pendiente.")
    // The receiver also checks the persisted state, including if it is already queued.
    pendingIntent(context, id, PendingIntent.FLAG_NO_CREATE)?.let {
      context.getSystemService(AlarmManager::class.java)?.cancel(it)
      it.cancel()
    }
    PhoneActionResult("Aviso de Insto cancelado.", verifiedLocally = true)
  }

  fun recover(context: Context, id: String): PhoneActionResult? {
    val entry = journal(context).clockEntries(id).singleOrNull() ?: return null
    if (entry.state !in setOf(InstinctClockState.SCHEDULED, InstinctClockState.FIRED)) return null
    return PhoneActionResult("Programación de Insto registrada · $id.", verifiedLocally = true,
      executionData = JSONObject().put("clockId", id).toString())
  }

  fun observeCancellation(context: Context, id: String): PhoneActionResult? =
    journal(context).clockEntries(id).singleOrNull()?.takeIf { it.state == InstinctClockState.CANCELLED }
      ?.let { PhoneActionResult("El aviso de Insto figura cancelado.", verifiedLocally = true) }

  fun rearm(context: Context) = synchronized(lock) {
    val now = System.currentTimeMillis()
    val canSchedule = InstinctDeviceCapabilities.granted(context, android.Manifest.permission.SCHEDULE_EXACT_ALARM)
    val journal = journal(context)
    for (entry in journal.clockEntries(includeFiring = true)) {
      if (entry.state == InstinctClockState.REGISTERING || entry.state == InstinctClockState.FIRING) {
        if (journal.moveClock(entry.id, entry.state, InstinctClockState.UNCERTAIN))
          report(context, entry, "La programación o entrega del aviso quedó incierta.")
        continue
      }
      if (!canSchedule) continue
      val late = if (useElapsed(context, entry)) SystemClock.elapsedRealtime() - checkNotNull(entry.elapsedAt) else now - entry.at
      if (late > FIRED_LATE_LIMIT) {
        if (journal.moveClock(entry.id, InstinctClockState.SCHEDULED, InstinctClockState.MISSED))
          report(context, entry, "Venció mientras el reloj de Insto no estaba disponible.")
      } else runCatching { arm(context, entry) }
    }
  }

  private fun useElapsed(context: Context, entry: InstinctClockEntry): Boolean =
    entry.elapsedAt != null && entry.boot >= 0 && entry.boot == boot(context)

  private fun arm(context: Context, entry: InstinctClockEntry) {
    val manager = checkNotNull(context.getSystemService(AlarmManager::class.java))
    val intent = checkNotNull(pendingIntent(context, entry.id, PendingIntent.FLAG_UPDATE_CURRENT))
    if (entry.elapsedAt == null) {
      val show = PendingIntent.getActivity(context, 0, Intent(context, com.example.MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      manager.setAlarmClock(AlarmManager.AlarmClockInfo(entry.at, show), intent)
    } else if (useElapsed(context, entry)) {
      manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, checkNotNull(entry.elapsedAt), intent)
    } else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.at, intent)
  }

  private fun pendingIntent(context: Context, id: String, flags: Int): PendingIntent? =
    PendingIntent.getBroadcast(context, 0, Intent(context, InstinctClockReceiver::class.java).setAction(FIRE)
      .setData(Uri.fromParts("insto-clock", id, null)), flags or PendingIntent.FLAG_IMMUTABLE)

  fun receive(context: Context, intent: Intent) = synchronized(lock) {
    if (intent.action != FIRE) return rearm(context)
    val id = intent.data?.schemeSpecificPart ?: return
    val journal = journal(context)
    val entry = journal.clockEntries(id).singleOrNull() ?: return
    if (entry.state != InstinctClockState.SCHEDULED) return
    val due = if (useElapsed(context, entry)) checkNotNull(entry.elapsedAt) <= SystemClock.elapsedRealtime()
      else entry.at <= System.currentTimeMillis()
    if (!due || !journal.moveClock(id, InstinctClockState.SCHEDULED, InstinctClockState.FIRING)) return
    val displayed = runCatching {
      check(InstinctDeviceCapabilities.granted(context, android.Manifest.permission.POST_NOTIFICATIONS) && notificationsEnabled(context))
      makeChannel(context)
      context.getSystemService(NotificationManager::class.java)?.notify(id, 1,
        NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
          .setContentTitle(entry.label).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
          .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
          .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).setAutoCancel(true).build()) ?: error("Sin notificaciones")
    }.isSuccess
    val state = if (displayed) InstinctClockState.FIRED else InstinctClockState.MISSED
    if (journal.moveClock(id, InstinctClockState.FIRING, state))
      report(context, entry, if (displayed) "El reloj de Insto publicó el aviso." else "Android no permitió publicar el aviso.")
  }

  private fun makeChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Insto · alarmas y temporizadores", NotificationManager.IMPORTANCE_HIGH)
      .apply { setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()) })
  }

  private fun report(context: Context, entry: InstinctClockEntry, detail: String) {
    InstinctLog.enqueueEvents(context, listOf(InstinctLogEvent(
      sha256("insto-clock:${entry.id}").take(32), InstinctLogEvents.iso(System.currentTimeMillis()),
      InstinctPlanEngine.LOG_KIND, "reloj ${entry.id.take(8)}", detail, "", InstinctProtocolCatalog.TITLE)))
  }

  private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
  private fun uncertain() = PhoneActionResult("No pude comprobar la programación en Insto.", outcome = PhoneActionOutcome.UNCERTAIN)
}

class InstinctClockReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    Thread({
      try { runCatching { InstinctLocalClock.receive(context.applicationContext, intent) } }
      finally { pending.finish() }
    }, "insto-reloj").start()
  }
}
