package com.example.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * The mailbox's settings, all in [Context.getNoBackupFilesDir]: never in backups nor restored. Off by default. The
 * key is never returned whole ([maskedKey]), never logged and never shown in the diagnosis.
 */
class InstinctMailboxSettings(context: Context) {
  private val app = context.applicationContext
  private val enabledFile = File(app.noBackupFilesDir, "instinct-mailbox-enabled")
  private val keyFile = File(app.noBackupFilesDir, KEY_FILE)
  private val acceptFile = File(app.noBackupFilesDir, "instinct-mailbox-accept")
  private val capFile = File(app.noBackupFilesDir, "instinct-whatsapp-cap")
  private val intervalFile = File(app.noBackupFilesDir, "instinct-mailbox-interval")

  fun enabled(): Boolean = runCatching { enabledFile.readText() == "true" }.getOrDefault(false)
  fun setEnabled(value: Boolean) {
    if (value) enabledFile.writeText("true") else enabledFile.delete()
    InstinctMailbox.rearm(app)
    InstinctCatalogPublisher.changed(app)
  }

  internal fun key(): String? = runCatching { keyFile.readText() }.getOrNull()?.takeIf(InstinctMailboxKey::valid)
  fun maskedKey(): String = InstinctMailboxKey.mask(key())
  fun hasKey(): Boolean = key() != null

  /** False when [value] is not a valid key: nothing is saved then. */
  fun saveKey(value: String): Boolean {
    val clean = value.trim()
    if (!InstinctMailboxKey.valid(clean)) return false
    writeAtomically(keyFile, clean, "insto-mailbox-key-")
    InstinctMailbox.rearm(app)
    return true
  }

  /** «Generar clave»: a new random key, saved. Returned once, for «Copiar clave». */
  fun generateKey(): String = InstinctMailboxKey.generate().also { saveKey(it) }

  fun accept(): InstinctMailboxAccept =
    runCatching { InstinctMailboxAccept.valueOf(acceptFile.readText()) }.getOrDefault(InstinctMailboxAccept.WHATSAPP_Y_BUZON)
  fun setAccept(value: InstinctMailboxAccept) {
    if (value == InstinctMailboxAccept.WHATSAPP_Y_BUZON) acceptFile.delete() else acceptFile.writeText(value.name)
    InstinctCatalogPublisher.changed(app)
  }

  /** «Las órdenes por WhatsApp (sin firma) como máximo en»: Solo (default) or Confirmar. */
  fun whatsappCap(): InstinctAutonomy =
    if (runCatching { capFile.readText() }.getOrNull() == InstinctAutonomy.CONFIRMAR.name) InstinctAutonomy.CONFIRMAR else InstinctAutonomy.SOLO
  fun setWhatsappCap(value: InstinctAutonomy) {
    if (value == InstinctAutonomy.CONFIRMAR) capFile.writeText(value.name) else capFile.delete()
  }

  /** 2, 5 (default) or 15 minutes. */
  fun intervalMinutes(): Int = runCatching { intervalFile.readText().trim().toInt() }.getOrNull()?.takeIf { it in INTERVALS } ?: 5
  fun setIntervalMinutes(value: Int) {
    if (value !in INTERVALS) return
    if (value == 5) intervalFile.delete() else intervalFile.writeText(value.toString())
    InstinctMailbox.rearm(app)
  }

  /** Why the mailbox cannot poll now, in Spanish; null when it can. */
  fun blocker(): String? = when {
    !InstinctProposalSettings(app).isEnabled() -> "«Recibir órdenes de Instinct» está apagado."
    !enabled() -> "«Recibir órdenes por el buzón» está apagado."
    !InstinctLogSettings(app).configured() -> "falta configurar la bitácora"
    key() == null -> "falta la clave"
    else -> null
  }

  companion object {
    const val KEY_FILE = "instinct-mailbox-key"
    val INTERVALS = listOf(2, 5, 15)
  }
}

/**
 * Polls the mailbox: one-shot JobScheduler jobs with a network constraint, each rescheduling the next; at once when
 * Insto or the floating panel opens and when any WhatsApp notification of Instinct's contact arrives. The cycle is:
 * GET ordenes → verify each → mark the ids seen on disk → POST tomadas (every id read) → dispatch the valid ones.
 */
object InstinctMailbox {
  private const val JOB_ID = 7_317
  private const val STATUS_FILE = "instinct-mailbox-status.json"
  /** Immediate polls closer than this to the last one are skipped: the Apps Script quota is not spent on bursts. */
  private const val MIN_SPACING_MILLIS = 20_000L

  internal var clock: () -> Long = { InstinctScheduler.wallClock() }
  internal var client: () -> InstinctMailboxClient = { InstinctMailboxClient(InstinctLog.client()) }
  internal var schedule: (Context, Long?) -> Unit = ::scheduleJob
  internal var background: (Runnable) -> Unit = { executor.execute(it) }
  private val executor by lazy { Executors.newSingleThreadExecutor { Thread(it, "insto-buzon") } }
  private val lock = Any()
  /** Elapsed-realtime millis of the last immediate poll; tests reset it. */
  @Volatile internal var lastPollAt = 0L
  /**
   * Every poll begins and ends here (1.17.0), so a doorbell ring knows whether one is running or just started:
   * a ring during a poll polls once more after it, one within 5 seconds of the last start waits for the window.
   */
  val gate = InstinctMailboxGate { SystemClock.elapsedRealtime() }

  /**
   * The one record of seen ids, shared by the poll and the orders that come inside the doorbell (1.18.0): its
   * [InstinctMailboxSeen.claim] is what keeps an order from running twice.
   */
  fun seen(context: Context): InstinctMailboxSeen {
    val file = File(context.applicationContext.noBackupFilesDir, InstinctMailboxSeen.FILE)
    return synchronized(sharedLock) { shared?.takeIf { it.first == file.absolutePath }?.second
      ?: InstinctMailboxSeen(file) { clock() }.also { shared = file.absolutePath to it } }
  }
  private var shared: Pair<String, InstinctMailboxSeen>? = null
  /** Not the poll's lock: an order inside the doorbell must never wait for a poll's network. */
  private val sharedLock = Any()

  /** The job, armed while the mailbox can poll; cancelled otherwise. */
  fun rearm(context: Context) {
    val app = context.applicationContext
    val settings = InstinctMailboxSettings(app)
    // While the doorbell rings, the backup poll is every 15 minutes, whatever was chosen (1.17.0).
    schedule(app, if (settings.blocker() == null) InstinctDoorbell.backupMinutes(app, settings.intervalMinutes()) * 60_000L else null)
  }

  /** At once, off the main thread, unless one ran in the last 20 seconds. */
  fun pollSoon(context: Context) {
    val app = context.applicationContext
    if (InstinctMailboxSettings(app).blocker() != null) return
    val now = SystemClock.elapsedRealtime()
    if (lastPollAt != 0L && now - lastPollAt < MIN_SPACING_MILLIS) return
    lastPollAt = now
    background(Runnable { runCatching { poll(app) } })
  }

  sealed interface Result {
    data class Done(val orders: Int, val badSignatures: Int, val expired: Int) : Result
    data class Failed(val reason: String) : Result
  }

  /**
   * One cycle, and one more while a doorbell ring arrived during it (that cycle may have read the sheet before the
   * order was written). Blocking: call it from a job's thread or [background].
   */
  fun poll(context: Context): Result = synchronized(lock) {
    var result: Result
    do {
      gate.begin()
      result = try { pollOnce(context) } catch (error: Throwable) { gate.end(); throw error }
    } while (gate.end() && result is Result.Done)
    result
  }

  private fun pollOnce(context: Context): Result {
    val app = context.applicationContext
    val settings = InstinctMailboxSettings(app)
    val log = InstinctLogSettings(app)
    val url = log.url(); val token = log.token(); val key = settings.key()
    if (!InstinctProposalSettings(app).isEnabled() || !settings.enabled()) return Result.Failed("apagado")
    if (url == null || token == null) return status(app, Result.Failed("falta la URL o el token"))
    if (key == null) return status(app, Result.Failed("falta la clave"))
    val fetched = runCatching { client().fetch(url, token, InstinctMailboxPoll.MAX_ORDERS) }
      .getOrElse { InstinctMailboxClient.Fetch.Failed(InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK).reason) }
    val orders = when (fetched) {
      is InstinctMailboxClient.Fetch.Failed -> return status(app, Result.Failed(fetched.reason))
      is InstinctMailboxClient.Fetch.Orders -> fetched.orders
    }
    // Verified and marked seen on disk before anything else: a valid order is dispatched at most once.
    val outcome = InstinctMailboxPoll.process(orders, key, clock(), seen(app))
    // Every id read, also the refused ones, so they never come back. If this fails nothing repeats: they are seen.
    if (outcome.taken.isNotEmpty()) runCatching { client().take(url, token, outcome.taken) }
    outcome.valid.forEach { order -> runCatching { InstinctProposalIntake.onMailbox(app, order) } }
    return status(app, Result.Done(outcome.valid.size, outcome.badSignatures, outcome.expired))
  }

  /** «Probar buzón»: how many orders are pending, without taking, verifying or running anything. */
  fun test(context: Context): String {
    val app = context.applicationContext
    val log = InstinctLogSettings(app)
    val url = log.url(); val token = log.token()
    if (url == null || token == null) return "Falta configurar la bitácora (URL y token)."
    if (InstinctMailboxSettings(app).key() == null) return "Falta guardar la clave del buzón."
    return when (val fetched = runCatching { client().fetch(url, token, InstinctMailboxPoll.MAX_ORDERS) }
      .getOrElse { InstinctMailboxClient.Fetch.Failed("sin red") }) {
      is InstinctMailboxClient.Fetch.Orders -> when (val n = fetched.orders.size) {
        0 -> "El buzón respondió bien: no hay órdenes pendientes."
        1 -> "El buzón respondió bien: hay 1 orden pendiente."
        else -> "El buzón respondió bien: hay $n órdenes pendientes."
      }
      is InstinctMailboxClient.Fetch.Failed -> "No se pudo leer el buzón (motivo: ${fetched.reason})."
    }
  }

  /** Cumulative counters and the last result, without any content. */
  data class Status(val at: Long, val failure: String?, val orders: Int, val badSignatures: Int, val expired: Int)

  fun status(context: Context): Status? = runCatching {
    val o = JSONObject(File(context.applicationContext.noBackupFilesDir, STATUS_FILE).readText())
    Status(o.getLong("at"), o.optString("failure").takeIf { it.isNotEmpty() }, o.optInt("orders"), o.optInt("bad"), o.optInt("expired"))
  }.getOrNull()

  private fun status(context: Context, result: Result): Result {
    val old = status(context)
    val now = clock()
    val o = JSONObject().put("at", now).put("orders", old?.orders ?: 0).put("bad", old?.badSignatures ?: 0).put("expired", old?.expired ?: 0)
    when (result) {
      is Result.Done -> o.put("orders", (old?.orders ?: 0) + result.orders).put("bad", (old?.badSignatures ?: 0) + result.badSignatures)
        .put("expired", (old?.expired ?: 0) + result.expired)
      is Result.Failed -> o.put("failure", result.reason)
    }
    runCatching { writeAtomically(File(context.applicationContext.noBackupFilesDir, STATUS_FILE), o.toString(), "insto-mailbox-status-") }
    return result
  }

  /** «Buzón: último sondeo HH:MM · órdenes N · firmas inválidas N · vencidas N», or why it failed. Never any content. */
  fun diagnosis(context: Context): String {
    val settings = InstinctMailboxSettings(context)
    settings.blocker()?.let { if (settings.enabled()) return "Buzón: no se sondea ($it)." }
    val s = status(context) ?: return "Buzón: todavía no se sondeó."
    val counts = "órdenes ${s.orders} · firmas inválidas ${s.badSignatures} · vencidas ${s.expired}"
    val at = InstinctDeferral.clock(s.at, Calendar.getInstance())
    return if (s.failure == null) "Buzón: último sondeo $at · $counts" else "Buzón: no se pudo sondear a las $at (motivo: ${s.failure}) · $counts"
  }

  private fun scheduleJob(context: Context, delayMillis: Long?) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    if (delayMillis == null) { runCatching { scheduler.cancel(JOB_ID) }; return }
    val job = JobInfo.Builder(JOB_ID, ComponentName(context, InstinctMailboxJobService::class.java))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setMinimumLatency(delayMillis)
      .build()
    runCatching { scheduler.schedule(job) }
  }
}

/** One poll, then the next one is scheduled. Not exported: only the system's JobScheduler binds it. */
class InstinctMailboxJobService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    Thread({
      runCatching { InstinctMailbox.poll(applicationContext) }
      jobFinished(params, false)
      InstinctMailbox.rearm(applicationContext)
    }, "insto-buzon-job").start()
    return true
  }

  /** Stopped by the system: the next one is scheduled; seen ids never repeat. */
  override fun onStopJob(params: JobParameters): Boolean { InstinctMailbox.rearm(applicationContext); return false }
}

/**
 * «#insto avisarme <texto>»: a notification of Insto for the person, in «Insto · mensajes de Instinct». Plain text,
 * no links to tap, at most 3 an hour, without sound during the quiet hours. Not a message to anyone else.
 */
object InstinctNotices {
  const val CHANNEL_ID = "instinct-messages"
  const val QUIET_CHANNEL_ID = "instinct-messages-quiet"
  private const val NOTIFICATION_BASE = 80_000
  val budget = AutoRunBudget({ InstinctProposalCenter.clock() }, max = InstinctNotice.PER_HOUR, periodMillis = 60 * 60_000L)
  private var next = 0

  /** Shows [text]; the reason it was not shown otherwise. [quiet]: without sound. */
  fun show(context: Context, text: String, quiet: Boolean): String? {
    if (!budget.tryAcquire()) return "ya hubo ${InstinctNotice.PER_HOUR} avisos en la última hora; no se mostró."
    val app = context.applicationContext
    val channel = if (quiet) QUIET_CHANNEL_ID else CHANNEL_ID
    if (!InstinctProposalCenter.canNotify(app, InstinctProposalCenter.ACTIONS_CHANNEL_ID)) return "Insto no puede mostrar notificaciones."
    val manager = app.getSystemService(NotificationManager::class.java) ?: return "Insto no puede mostrar notificaciones."
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
      if (quiet) NotificationChannel(QUIET_CHANNEL_ID, "Insto · mensajes de Instinct (horas de silencio)", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Lo que Instinct te avisa durante las horas de silencio, sin sonido."
      } else NotificationChannel(CHANNEL_ID, "Insto · mensajes de Instinct", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Lo que Instinct te avisa con «avisarme», desde el buzón firmado."
      })
    // A plain String: nothing is linkified, nothing can be tapped but the notification itself.
    val plain: String = text
    val notification = InstinctProposalCenter.builder(app, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct te avisa").setContentText(plain).setStyle(Notification.BigTextStyle().bigText(plain))
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setPublicVersion(InstinctProposalCenter.builder(app, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Instinct te avisa").build())
      .setAutoCancel(true).build()
    val id = synchronized(this) { NOTIFICATION_BASE + (next++ % 16) }
    return if (runCatching { manager.notify(id, notification) }.isSuccess) null else "Android no mostró el aviso."
  }
}
