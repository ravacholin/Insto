package com.example.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.TimeZone

/**
 * The log's address and token. Both in [Context.getNoBackupFilesDir]: never in preferences backups, configuration
 * exports or the cloud. The token is never returned to the UI whole ([maskedToken]) and never logged.
 */
class InstinctLogSettings(context: Context) {
  private val app = context.applicationContext
  private val urlFile = File(app.noBackupFilesDir, "instinct-log-url")
  private val tokenFile = File(app.noBackupFilesDir, "instinct-log-token")

  fun url(): String? = runCatching { urlFile.readText().trim() }.getOrNull()?.takeIf(InstinctLogConfig::validUrl)
  internal fun token(): String? = runCatching { tokenFile.readText() }.getOrNull()?.takeIf(InstinctLogConfig::validToken)
  fun maskedToken(): String = InstinctLogConfig.mask(token())
  fun configured(): Boolean = url() != null && token() != null

  /** False when either value is invalid: nothing is saved then. A blank token keeps the saved one. */
  fun save(url: String, token: String): Boolean {
    val cleanUrl = url.trim()
    if (!InstinctLogConfig.validUrl(cleanUrl)) return false
    val newToken = token.trim().takeIf { it.isNotEmpty() }
    if (newToken == null && token() == null) return false
    if (newToken != null && !InstinctLogConfig.validToken(newToken)) return false
    urlFile.writeText(cleanUrl)
    newToken?.let(tokenFile::writeText)
    // Events that waited for a configuration go out now.
    InstinctLog.rearm(app)
    return true
  }

  fun clear() { urlFile.delete(); tokenFile.delete() }
}

/**
 * The log on Android: events into the outbox ([InstinctLogOutbox], noBackupFilesDir), one system [JobScheduler] job
 * that drains it with a network constraint and exponential backoff, and the last result for the diagnosis, without
 * any content. A failure never blocks an order nor makes Insto use WhatsApp instead.
 */
object InstinctLog {
  private const val JOB_ID = 7_315
  private const val BACKOFF_MILLIS = 30_000L
  private const val STATUS_FILE = "instinct-log-status"

  internal var clock: () -> Long = System::currentTimeMillis
  /** Arms the job; a seam for tests. */
  internal var schedule: (Context) -> Unit = ::scheduleJob
  /** The HTTP client; tests replace its transport. */
  internal var client: () -> InstinctLogClient = { shared }
  private val shared by lazy { InstinctLogClient() }
  private val drainLock = Any()

  fun outbox(context: Context) = InstinctLogOutbox(File(context.applicationContext.noBackupFilesDir, InstinctLogOutbox.FILE))

  /** Writes [entries] about message [message] to the outbox and arms the job. Never throws. */
  fun enqueue(context: Context, entries: List<AckEntry>, message: String) {
    if (entries.isEmpty()) return
    val app = context.applicationContext
    runCatching {
      val now = clock()
      val events = entries.mapNotNull { InstinctLogEvents.of(it, message, now, TimeZone.getDefault()) }
      if (events.isEmpty()) return
      outbox(app).add(events)
      schedule(app)
    }
  }

  /**
   * Sends the outbox, a batch of at most 50 at a time, until it is empty or a batch fails. True when the job should
   * be retried with backoff (no network, an invalid answer, an HTTP error); a rejected token or a missing
   * configuration waits for the user instead.
   */
  fun drain(context: Context): Boolean = synchronized(drainLock) { drainLocked(context.applicationContext) }

  private fun drainLocked(app: Context): Boolean {
    val box = outbox(app)
    val settings = InstinctLogSettings(app)
    while (true) {
      val batch = box.batch()
      if (batch.isEmpty()) return false
      val url = settings.url()
      val token = settings.token()
      val result = if (url == null || token == null) InstinctLogClient.Result.Failed(InstinctLogClient.Failure.NOT_CONFIGURED)
        else runCatching { client().send(url, token, batch) }.getOrElse { InstinctLogClient.Result.Failed(InstinctLogClient.Failure.NETWORK) }
      when (result) {
        is InstinctLogClient.Result.Accepted -> {
          // An answer that confirms nothing would loop forever: it is invalid.
          if (result.ids.isEmpty()) { status(app, InstinctLogClient.Result.Failed(InstinctLogClient.Failure.INVALID)); return true }
          box.remove(result.ids)
          status(app, result)
        }
        is InstinctLogClient.Result.Failed -> {
          status(app, result)
          return result.failure != InstinctLogClient.Failure.TOKEN && result.failure != InstinctLogClient.Failure.NOT_CONFIGURED
        }
      }
    }
  }

  /** «Probar bitácora»: one event of kind «prueba», sent now, outside the outbox. Blocking: call it off the main thread. */
  fun test(context: Context): String {
    val app = context.applicationContext
    val settings = InstinctLogSettings(app)
    val url = settings.url()
    val token = settings.token()
    if (url == null || token == null) return "Falta guardar la URL y el token."
    val event = InstinctLogEvents.test(clock())
    val result = runCatching { client().send(url, token, listOf(event)) }
      .getOrElse { InstinctLogClient.Result.Failed(InstinctLogClient.Failure.NETWORK) }
    status(app, result)
    return when (result) {
      is InstinctLogClient.Result.Accepted -> if (event.id in result.ids) "La bitácora respondió bien: se anotó una prueba."
        else "La bitácora respondió, pero no confirmó la prueba (respuesta inválida)."
      is InstinctLogClient.Result.Failed -> "No se pudo escribir en la bitácora (motivo: ${result.reason})."
    }
  }

  /** The last write, without any content: «ok:<millis>» or «fail:<reason>». */
  data class Status(val ok: Boolean, val at: Long, val reason: String?)

  fun status(context: Context): Status? = runCatching {
    val text = File(context.applicationContext.noBackupFilesDir, STATUS_FILE).readText()
    val parts = text.split(':', limit = 3)
    Status(parts[0] == "ok", parts[1].toLong(), parts.getOrNull(2))
  }.getOrNull()

  private fun status(context: Context, result: InstinctLogClient.Result) = runCatching {
    val text = when (result) {
      is InstinctLogClient.Result.Accepted -> "ok:${clock()}"
      is InstinctLogClient.Result.Failed -> "fail:${clock()}:${result.reason}"
    }
    File(context.applicationContext.noBackupFilesDir, STATUS_FILE).writeText(text)
  }

  /** «Bitácora: última escritura HH:MM · pendientes N», or why it could not write. Never any content. */
  fun diagnosis(context: Context): String {
    val box = outbox(context).read()
    val pending = "pendientes ${box.events.size}"
    val dropped = if (box.dropped > 0) " · se descartaron ${box.dropped} por bandeja llena" else ""
    val status = status(context)
    return when {
      status == null -> "Bitácora: todavía no escribió · $pending$dropped"
      status.ok -> "Bitácora: última escritura ${InstinctDeferral.clock(status.at, java.util.Calendar.getInstance())} · $pending$dropped"
      else -> "Bitácora: no se pudo escribir (motivo: ${status.reason}) · $pending$dropped"
    }
  }

  /** After a boot: the job again, only if something is pending. */
  fun rearm(context: Context) { if (outbox(context).size() > 0) schedule(context.applicationContext) }

  private fun scheduleJob(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    val job = JobInfo.Builder(JOB_ID, ComponentName(context, InstinctLogJobService::class.java))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
      .build()
    runCatching { scheduler.schedule(job) }
  }
}

/** Drains the outbox on its own thread. Not exported: only the system's JobScheduler binds it. */
class InstinctLogJobService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    Thread({
      val retry = runCatching { InstinctLog.drain(applicationContext) }.getOrDefault(true)
      jobFinished(params, retry)
    }, "insto-bitacora").start()
    return true
  }

  /** Stopped by the system (the network went away): the outbox is untouched, so retry. */
  override fun onStopJob(params: JobParameters): Boolean = true
}

/** After a boot the job is armed again if events are pending. Not exported. */
class InstinctLogBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { InstinctLog.rearm(context) }
  }
}
