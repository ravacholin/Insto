package com.example.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import java.util.Calendar
import org.json.JSONObject

/**
 * «Catálogo vivo» (1.18.0): Insto publishes the catalog as JSON in the sheet ([InstinctProtocolCatalog.json]), so
 * Instinct reads the rules in force instead of depending on a pasted copy. Published when its hash differs from the
 * last one published (`instinct-catalog-last.json` in noBackupFilesDir): after a setting that goes into it changes,
 * on the first start after an update and on `BOOT_COMPLETED`. Over the log's transport, with JobScheduler retries
 * like the outbox and a job of its own. A failure never blocks anything.
 */
object InstinctCatalogPublisher {
  const val LAST_FILE = "instinct-catalog-last.json"
  const val ACTION = "catalogo"
  private const val JOB_ID = 7_321
  private const val BACKOFF_MILLIS = 30_000L

  internal var clock: () -> Long = { InstinctScheduler.wallClock() }
  /** Arms the job; tests run it inline or record it. */
  internal var schedule: (Context) -> Unit = ::scheduleJob
  internal var client: () -> InstinctLogClient = { InstinctLog.client() }
  internal var instoVersion: () -> String = { com.example.BuildConfig.VERSION_NAME }
  private val lock = Any()

  /** The last result: the hash and time of the last catalog the script took, and the last failure, if any. */
  data class Last(val hash: String?, val at: Long?, val failure: String?)

  /** Published only with the log configured and «Recibir órdenes de Instinct» on. */
  fun active(context: Context): Boolean = InstinctProposalSettings(context).isEnabled() && InstinctLogSettings(context).configured()

  /** The channels as the catalog says them: which are on, never their addresses. */
  fun channels(context: Context): InstinctCatalogChannels {
    val app = context.applicationContext
    val proposals = InstinctProposalSettings(app)
    val mailbox = InstinctMailboxSettings(app)
    val doorbell = mailbox.enabled() && InstinctDoorbell.active(app)
    return InstinctCatalogChannels(
      whatsapp = mailbox.accept() == InstinctMailboxAccept.WHATSAPP_Y_BUZON,
      log = InstinctLogSettings(app).configured(),
      mailbox = mailbox.enabled(),
      doorbell = doorbell,
      doorbellDirect = doorbell && InstinctDoorbell.settings(app).withOrder(),
      replyMode = proposals.replyMode(),
      ackChannel = proposals.channel(),
      showQuestionsOnLockScreen = InstinctAutonomySettings(app).showQuestionsOnLockScreen(),
    )
  }

  /** The catalog now, from the settings in force. */
  fun current(context: Context): InstinctCatalogJson {
    val app = context.applicationContext
    val channels = channels(app)
    val calendars = if (InstinctDeviceCapabilities.granted(app, android.Manifest.permission.READ_CALENDAR))
      runCatching { InstinctCalendarActions(app.contentResolver).calendars() }.getOrDefault(emptyList()) else emptyList()
    val calendarAccess = InstinctCalendarAccess(app)
    val enabledCalendars = calendars.map { it.id }.intersect(calendarAccess.enabled()).sorted()
    val writableCalendars = calendars.filter { it.writable }.map { it.id }.intersect(calendarAccess.enabled(write = true)).sorted()
    val discovery = if (channels.mailbox) InstinctCapabilities.specs.mapValues { (_, spec) ->
      runCatching { InstinctDeviceCapabilities.discover(app, spec) }.getOrElse {
        InstinctDeviceCapabilities.Discovery(InstinctDeviceCapabilities.Block("No se pudo comprobar la disponibilidad.", false))
      }
    } else emptyMap()
    val availability = InstinctCapabilities.specs.mapValues { (id, spec) ->
      val blocker = discovery[id]?.blocker
      when {
        spec.adapter == InstinctCapabilities.Adapter.OMNIREMOTE && blocker == null && !channels.mailbox ->
          InstinctCapabilities.OMNIREMOTE_UNAVAILABLE
        !channels.mailbox -> "requiere el buzón firmado"
        blocker?.pending == true -> "pendiente: ${blocker.reason}"
        blocker != null -> "no disponible: ${blocker.reason}"
        spec.id.startsWith("calendar_") && (if (spec.id == "calendar_query") enabledCalendars else writableCalendars).isEmpty() -> "no hay calendarios habilitados disponibles"
        !spec.runsLocked -> "requiere un toque"
        else -> "disponible"
      }
    }
    val routines = InstinctRoutineGrants(app).access()
    val names = routines.enabledNames()
    return InstinctProtocolCatalog.json(names, InstinctAutonomySettings(app).policy(), channels, instoVersion(),
      availability = availability, calendarIds = enabledCalendars, calendarWriteIds = writableCalendars,
      calendarLabels = calendars.filter { it.id in enabledCalendars }.associate {
        it.id to InstinctAck.cut(InstinctAck.flat(it.label), 80).takeIf(InstinctAck::safe).orEmpty()
      },
      capabilityTargets = discovery.mapValues { it.value.targets }.filterValues { it.isNotEmpty() },
      routineRevisions = names.mapNotNull { name -> routines.resolve(name)?.let { name to InstinctRoutines.hash(it) } }.toMap())
  }

  fun last(context: Context): Last = runCatching {
    val o = JSONObject(File(context.applicationContext.noBackupFilesDir, LAST_FILE).readText())
    Last(o.optString("hash").takeIf { it.length == 64 }, o.optLong("at", 0L).takeIf { it > 0 }, o.optString("failure").takeIf { it.isNotEmpty() })
  }.getOrDefault(Last(null, null, null))

  private fun writeLast(context: Context, value: Last) = runCatching {
    val o = JSONObject().apply { value.hash?.let { put("hash", it) }; value.at?.let { put("at", it) }; value.failure?.let { put("failure", it) } }
    writeAtomically(File(context.applicationContext.noBackupFilesDir, LAST_FILE), o.toString(), "insto-catalog-")
  }

  /** Something that goes into the catalog changed (or the process started, or the phone booted): the job decides. */
  fun changed(context: Context) {
    val app = context.applicationContext
    if (!runCatching { active(app) }.getOrDefault(false)) return
    runCatching { schedule(app) }
  }

  sealed interface Outcome {
    data class Published(val hash: String) : Outcome
    data object Unchanged : Outcome
    data object Inactive : Outcome
    /** [retry]: no network or an HTTP error; otherwise it waits for the person (token, an old script). */
    data class Failed(val reason: String, val retry: Boolean) : Outcome
  }

  /**
   * Publishes when the hash differs from the last published one, or always with [force] («Publicar catálogo ahora»).
   * Blocking: call it from a job's thread or off the main thread.
   */
  fun publish(context: Context, force: Boolean = false): Outcome = synchronized(lock) {
    val app = context.applicationContext
    if (!active(app)) return Outcome.Inactive
    val log = InstinctLogSettings(app)
    val url = log.url() ?: return Outcome.Inactive
    val token = log.token() ?: return Outcome.Inactive
    val catalog = current(app)
    val last = last(app)
    if (!force && last.hash == catalog.hash) return Outcome.Unchanged
    val body = JSONObject().put("token", token).put("action", ACTION).put("hash", catalog.hash)
      .put("json", catalog.published(InstinctLogEvents.iso(clock()))).toString()
    val raw = runCatching { client().post(url, body) }.getOrElse { InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK) }
    val outcome = parse(raw, catalog.hash)
    when (outcome) {
      is Outcome.Published -> writeLast(app, Last(catalog.hash, clock(), null))
      is Outcome.Failed -> writeLast(app, last.copy(failure = outcome.reason))
      else -> Unit
    }
    outcome
  }

  const val OLD_SCRIPT = "el Apps Script no conoce «catalogo»: pegá el instinct-bitacora.gs de 1.18.0 e implementá una versión nueva"

  /** `{ok:true, catalogo:"guardado"|"igual"}`; anything else, why, in Spanish. */
  internal fun parse(raw: InstinctLogClient.Raw, hash: String): Outcome = when (raw) {
    is InstinctLogClient.Raw.Failed -> Outcome.Failed(raw.reason,
      retry = raw.failure != InstinctLogClient.Failure.TOKEN && raw.failure != InstinctLogClient.Failure.NOT_CONFIGURED &&
        raw.failure != InstinctLogClient.Failure.LOGIN)
    is InstinctLogClient.Raw.Body -> {
      val o = raw.text?.let { runCatching { JSONObject(it) }.getOrNull() }
      when {
        o == null -> Outcome.Failed(InstinctLogClient.Failure.INVALID.label, retry = true)
        !o.optBoolean("ok", false) && o.optString("error") == "token" -> Outcome.Failed(InstinctLogClient.Failure.TOKEN.label, retry = false)
        !o.optBoolean("ok", false) && o.optString("error") == ACTION -> Outcome.Failed("el script rechazó el catálogo", retry = false)
        o.optBoolean("ok", false) && o.optString(ACTION) in setOf("guardado", "igual") -> Outcome.Published(hash)
        // A script older than 1.18.0 answers the events branch: {ok:true, accepted:[]}.
        else -> Outcome.Failed(OLD_SCRIPT, retry = false)
      }
    }
  }

  /** «Catálogo para Instinct: publicado HH:MM · ••••<6 del hash>», or why not. Null when it is not published at all. */
  fun line(context: Context): String? {
    if (!active(context)) return null
    return InstinctCatalogText.line(last(context), current(context).hash) { InstinctDeferral.clock(it, Calendar.getInstance()) }
  }

  private fun scheduleJob(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    val job = JobInfo.Builder(JOB_ID, ComponentName(context, InstinctCatalogJobService::class.java))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
      .build()
    runCatching { scheduler.schedule(job) }
  }
}

/** The catalog's line in Settings. Pure. */
object InstinctCatalogText {
  /** «••••» and the last 6 characters of the hash. */
  fun mask(hash: String): String = "••••" + hash.takeLast(6)

  fun line(last: InstinctCatalogPublisher.Last, currentHash: String, clock: (Long) -> String): String {
    val published = if (last.hash != null && last.at != null) "publicado ${clock(last.at)} · ${mask(last.hash)}" else null
    return "Catálogo para Instinct: " + when {
      last.failure != null -> "no se pudo publicar (motivo: ${last.failure})" + (published?.let { "; el último, $it" } ?: "")
      published == null -> "todavía no se publicó"
      last.hash != currentHash -> "$published · cambió, se publica en cuanto haya red"
      else -> published
    }
  }
}

/** Publishes the catalog on its own thread, with backoff. Not exported: only the system's JobScheduler binds it. */
class InstinctCatalogJobService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    Thread({
      val retry = when (val outcome = runCatching { InstinctCatalogPublisher.publish(applicationContext) }.getOrNull()) {
        is InstinctCatalogPublisher.Outcome.Failed -> outcome.retry
        null -> true
        else -> false
      }
      jobFinished(params, retry)
    }, "insto-catalogo").start()
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean = true
}
