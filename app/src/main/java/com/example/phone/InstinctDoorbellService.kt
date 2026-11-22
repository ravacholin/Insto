package com.example.phone

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The doorbell's settings and state, all in [Context.getNoBackupFilesDir]: never in backups nor restored. Off by
 * default. The endpoint lives in its own file (`instinct-doorbell-endpoint`) and is never returned whole to the UI
 * ([InstinctDoorbellEndpoint.mask]), never logged and never in the diagnosis nor the catalog.
 */
class InstinctDoorbellSettings(context: Context) {
  private val app = context.applicationContext
  private val enabledFile = File(app.noBackupFilesDir, "instinct-doorbell-enabled")
  private val chosenFile = File(app.noBackupFilesDir, "instinct-doorbell-distributor")
  private val withOrderFile = File(app.noBackupFilesDir, "instinct-doorbell-with-order")
  private val endpointFile = File(app.noBackupFilesDir, ENDPOINT_FILE)
  private val stateFile = File(app.noBackupFilesDir, STATE_FILE)

  fun enabled(): Boolean = runCatching { enabledFile.readText() == "true" }.getOrDefault(false)
  internal fun writeEnabled(value: Boolean) { if (value) enabledFile.writeText("true") else enabledFile.delete() }

  /** «Incluir la orden en el timbre» (1.18.0): off by default. */
  fun withOrder(): Boolean = runCatching { withOrderFile.readText() == "true" }.getOrDefault(false)
  internal fun writeWithOrder(value: Boolean) { if (value) withOrderFile.writeText("true") else withOrderFile.delete() }

  /** The distributor chosen in Settings when there are several; null lets a single one be used. */
  fun chosen(): String? = runCatching { chosenFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
  internal fun writeChosen(value: String?) { if (value == null) chosenFile.delete() else chosenFile.writeText(value) }

  internal fun endpoint(): String? = runCatching { endpointFile.readText() }.getOrNull()?.takeIf(InstinctDoorbellEndpoint::valid)
  fun maskedEndpoint(): String = InstinctDoorbellEndpoint.mask(endpoint())
  internal fun writeEndpoint(value: String?) {
    if (value == null) endpointFile.delete() else writeAtomically(endpointFile, value, "insto-doorbell-endpoint-")
  }

  fun state(): DoorbellState = synchronized(LOCK) { runCatching { DoorbellState.decode(stateFile.readText()) }.getOrDefault(DoorbellState()) }
  internal fun update(change: (DoorbellState) -> DoorbellState): DoorbellState = synchronized(LOCK) {
    change(state()).also { runCatching { writeAtomically(stateFile, it.encode(), "insto-doorbell-state-") } }
  }

  companion object {
    const val ENDPOINT_FILE = "instinct-doorbell-endpoint"
    const val STATE_FILE = "instinct-doorbell-state.json"
    private val LOCK = Any()
  }
}

/**
 * The doorbell on Android: the UnifiedPush connector (spec AND_3, by hand), the endpoint to the Apps Script over the
 * log's transport with JobScheduler retries, and what a ring does: poll the mailbox, at once or as soon as it can.
 */
object InstinctDoorbell {
  private const val INFORM_JOB_ID = 7_319
  private const val RING_JOB_ID = 7_318
  private const val FOLLOW_UP_JOB_ID = 7_320
  private const val BACKOFF_MILLIS = 30_000L
  internal const val EXTRA_KIND = "kind"
  internal const val KIND_INFORM = "inform"
  internal const val KIND_RING = "ring"
  /** The poll ~60 seconds after an order that came inside the doorbell (1.18.0). */
  internal const val KIND_FOLLOW_UP = "follow-up"
  /** 1.19.0: drains the push inbox if the process died before dispatching it. */
  internal const val KIND_DRAIN = "drain"
  private const val DRAIN_JOB_ID = 7_321

  internal var clock: () -> Long = { InstinctScheduler.wallClock() }
  /** The installed distributors; tests replace it. */
  internal var distributors: (Context) -> List<String> = ::queryDistributors
  /** Sends a broadcast to a distributor; tests record it. */
  internal var send: (Context, Intent) -> Unit = ::sendToDistributor
  internal var online: (Context) -> Boolean = ::isOnline
  /** Arms a job: [kind], a delay (null: expedited when it can) and whether it needs the network. Tests record it. */
  internal var schedule: (Context, String, Long?) -> Unit = ::scheduleJob
  /** Cancels a job of [kind] (the rescue job once the inbox is empty). Tests record it. */
  internal var cancel: (Context, String) -> Unit = ::cancelJob
  internal var client: () -> InstinctLogClient = { InstinctLog.client() }
  /** The doorbell's jobs run here, one at a time: no thread per job. */
  internal val jobs by lazy { boundedExecutor("insto-timbre-job", 8) }

  fun settings(context: Context) = InstinctDoorbellSettings(context)

  /** Usable: the switch on, the mailbox polling and a distributor to register with. */
  fun active(context: Context): Boolean {
    val s = settings(context)
    return s.enabled() && InstinctMailboxSettings(context).blocker() == null && s.endpoint() != null
  }

  /** The distributor in use: the only one installed, or the chosen one. */
  fun distributor(context: Context): String? = InstinctDoorbellProtocol.pick(distributors(context), settings(context).chosen())

  /** Why the switch cannot be turned on, in Spanish; null when it can. */
  fun blocker(context: Context): String? = when {
    InstinctMailboxSettings(context).blocker() != null -> "Encendé primero el buzón."
    distributors(context).isEmpty() -> InstinctDoorbellText.NO_DISTRIBUTOR + "."
    distributor(context) == null -> "Elegí el distribuidor."
    else -> null
  }

  /** «Timbre instantáneo (ntfy)». On: registers with the distributor. Off: unregisters and deletes it from the script. */
  fun setEnabled(context: Context, value: Boolean) {
    val app = context.applicationContext
    val s = settings(app)
    if (value) { s.writeEnabled(true); register(app) }
    else { unregister(app); s.writeEnabled(false); InstinctMailbox.rearm(app) }
    InstinctCatalogPublisher.changed(app)
  }

  /**
   * «Incluir la orden en el timbre» (1.18.0). The script hears it the same way as the endpoint, with the same retries;
   * until it confirms, the doorbell keeps ringing without the order. Off is the default.
   */
  fun setWithOrder(context: Context, value: Boolean) {
    val app = context.applicationContext
    val s = settings(app)
    if (s.withOrder() == value) return
    s.writeWithOrder(value)
    s.update { it.copy(withOrderConfirmed = false) }
    if (s.enabled() && s.endpoint() != null) inform(app)
    InstinctCatalogPublisher.changed(app)
  }

  /** Several distributors installed: the one chosen in Settings. A change re-registers with it. */
  fun choose(context: Context, distributor: String) {
    val app = context.applicationContext
    if (distributor !in distributors(app)) return
    val before = distributor(app)
    settings(app).writeChosen(distributor)
    if (settings(app).enabled() && before != distributor) { unregister(app); register(app) }
  }

  /**
   * REGISTER with the current token (re-registration keeps it), or a new one. On Android 14 and later the
   * broadcast shares Insto's identity; before, the `pi` PendingIntent carries it. `application` is for AND_2
   * distributors.
   */
  fun register(context: Context) {
    val app = context.applicationContext
    val s = settings(app)
    val distributor = distributor(app) ?: run {
      s.update { it.copy(failure = if (distributors(app).isEmpty()) "sin distribuidor" else "elegí el distribuidor") }
      return
    }
    val state = s.update { old ->
      val token = old.token?.takeIf { old.distributor == distributor } ?: UUID.randomUUID().toString()
      old.copy(token = token, distributor = distributor, confirmed = old.confirmed && old.token == token,
        failure = old.failure?.takeIf { old.token == token })
    }
    val intent = Intent(UnifiedPush.ACTION_REGISTER).setPackage(distributor)
      .putExtra(UnifiedPush.EXTRA_TOKEN, state.token)
      .putExtra(UnifiedPush.EXTRA_MESSAGE, UnifiedPush.DESCRIPTION)
      .putExtra(UnifiedPush.EXTRA_APPLICATION, app.packageName)
    if (Build.VERSION.SDK_INT < 34) intent.putExtra(UnifiedPush.EXTRA_PI, identity(app))
    runCatching { send(app, intent) }
  }

  /**
   * UNREGISTER, then the token is forgotten at once (the spec: without waiting for UNREGISTERED), the endpoint
   * deleted and the script told to delete its copy.
   */
  private fun unregister(context: Context) {
    val app = context.applicationContext
    val s = settings(app)
    val state = s.state()
    if (state.token != null && state.distributor != null) {
      val intent = Intent(UnifiedPush.ACTION_UNREGISTER).setPackage(state.distributor).putExtra(UnifiedPush.EXTRA_TOKEN, state.token)
        .putExtra(UnifiedPush.EXTRA_APPLICATION, app.packageName)
      if (Build.VERSION.SDK_INT < 34) intent.putExtra(UnifiedPush.EXTRA_PI, identity(app))
      runCatching { send(app, intent) }
    }
    forget(app, failure = null)
  }

  /** The endpoint is gone (turned off, UNREGISTERED): delete it here and in the script. */
  private fun forget(context: Context, failure: String?) {
    val s = settings(context)
    val hadEndpoint = s.endpoint() != null || s.state().informedAt != null
    s.writeEndpoint(null)
    s.update { it.copy(token = null, confirmed = false, informedAt = null, informPending = hadEndpoint, failure = failure) }
    if (hadEndpoint) inform(context)
    InstinctCatalogPublisher.changed(context)
  }

  /** An IMMUTABLE broadcast to a package that does not exist: only its creator, Insto, matters to the distributor. */
  private fun identity(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
    Intent(UnifiedPush.DUMMY_APP).setPackage(UnifiedPush.DUMMY_APP), PendingIntent.FLAG_IMMUTABLE)

  /**
   * A broadcast from the distributor, on the main thread. Since 1.19.0 it only hands the broadcast over to
   * [InstinctPushIntake] and returns: nothing here waits for the disk, the network or an execution.
   */
  fun onReceive(context: Context, intent: Intent, async: () -> PushFinisher?) {
    val app = context.applicationContext
    InstinctPushIntake.receive(app, async) { handle(app, intent) }
  }

  /**
   * The light part of a broadcast, on the receiving thread: the token, then a MESSAGE is classified and stored in the
   * inbox **before** it is acknowledged, so what the distributor stops resending is already on disk. The payload is never
   * logged; a signed order in it is stored only until dispatch.
   */
  internal fun handle(app: Context, intent: Intent) {
    val s = settings(app)
    val state = s.state()
    val payload = if (intent.action == UnifiedPush.ACTION_MESSAGE) payload(intent) else null
    val action = InstinctDoorbellProtocol.handle(intent.action, intent.getStringExtra(UnifiedPush.EXTRA_TOKEN), state,
      intent.getStringExtra(UnifiedPush.EXTRA_ENDPOINT), intent.getStringExtra(UnifiedPush.EXTRA_ID), intent.getStringExtra(UnifiedPush.EXTRA_REASON),
      payload)
    when (action) {
      InstinctDoorbellProtocol.Action.Ignore -> Unit
      is InstinctDoorbellProtocol.Action.Ring -> {
        accept(app, if (action.inline != null) InstinctPushMessage.Kind.Order(action.inline) else InstinctPushMessage.Kind.Ring,
          encrypted = false, malformed = action.malformed)
        ack(app, state, action.ackId)
      }
      is InstinctDoorbellProtocol.Action.Endpoint -> {
        ack(app, state, action.ackId)
        val changed = s.endpoint() != action.endpoint
        s.writeEndpoint(action.endpoint)
        s.update { it.copy(confirmed = true, failure = null, informPending = true, informedAt = it.informedAt.takeUnless { changed }) }
        inform(app)
        InstinctMailbox.rearm(app)
        // The doorbell became active: the catalog says so.
        InstinctCatalogPublisher.changed(app)
      }
      is InstinctDoorbellProtocol.Action.BadEndpoint -> {
        ack(app, state, action.ackId)
        s.update { it.copy(confirmed = true, failure = "el endpoint no es https o es demasiado largo") }
      }
      is InstinctDoorbellProtocol.Action.Failed -> {
        // The next registration needs a new token.
        s.update { it.copy(token = null, confirmed = false, failure = action.reason) }
      }
      InstinctDoorbellProtocol.Action.Unregistered -> forget(app, failure = "el distribuidor dio de baja el registro")
    }
  }

  /** AND_3's `bytesMessage`, or the older `message` text. Too big is no payload at all. */
  private fun payload(intent: Intent): ByteArray? = runCatching {
    (intent.getByteArrayExtra(UnifiedPush.EXTRA_BYTES_MESSAGE) ?: intent.getStringExtra(UnifiedPush.EXTRA_MESSAGE)?.toByteArray(Charsets.UTF_8))
      ?.takeIf { it.size <= InstinctDoorbellPayload.MAX_BYTES }
  }.getOrNull()

  private fun ack(context: Context, state: DoorbellState, id: String?) {
    val distributor = state.distributor ?: return
    if (id == null || state.token == null) return
    runCatching {
      send(context, Intent(UnifiedPush.ACTION_MESSAGE_ACK).setPackage(distributor).putExtra(UnifiedPush.EXTRA_TOKEN, state.token)
        .putExtra(UnifiedPush.EXTRA_ID, id))
    }
  }

  /**
   * A push that arrived, already classified: counted, then stored in the inbox with a rescue job, for
   * [InstinctPushDispatcher]. Receiving is not running: the order still goes through the signature, the window, the
   * record of seen ids and the same intake as a poll. With the mailbox off nothing is stored.
   */
  internal fun accept(context: Context, kind: InstinctPushMessage.Kind, encrypted: Boolean, malformed: Boolean = false) {
    val app = context.applicationContext
    settings(app).update {
      it.copy(rings = it.rings + 1, lastRingAt = clock(), badPayloads = it.badPayloads + if (malformed) 1 else 0)
    }
    if (InstinctMailboxSettings(app).blocker() != null) return
    // The backup poll slows down to 15 minutes while rings arrive.
    InstinctMailbox.rearm(app)
    val inbox = InstinctPushDispatcher.inbox(app)
    val added = when (kind) {
      InstinctPushMessage.Kind.Ring -> inbox.add(PushEntry.Kind.RING, encrypted = encrypted)
      is InstinctPushMessage.Kind.Order -> inbox.add(PushEntry.Kind.ORDER, kind.order, encrypted)
    }
    when (added) {
      is InstinctPushInbox.Added.Queued -> schedule(app, KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS)
      InstinctPushInbox.Added.Coalesced, InstinctPushInbox.Added.Full -> schedule(app, KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS)
      InstinctPushInbox.Added.Duplicate -> settings(app).update { it.copy(duplicates = it.duplicates + 1) }
      // Not even the inbox could be written: a ring job polls when it can; the order waits in the sheet.
      InstinctPushInbox.Added.Failed -> {
        settings(app).update { it.copy(storageFailures = it.storageFailures + 1, lastError = "no se pudo guardar el timbre recibido") }
        schedule(app, KIND_RING, null)
      }
    }
  }

  /**
   * What the gate says for a ring: [now] polls at once; otherwise once more after the running poll, later or online.
   * [removed] runs when the ring was handed to a job or to the running poll, so the inbox can forget it.
   */
  internal fun pollAsTheGateSays(app: Context, removed: () -> Unit = {}, now: () -> Unit) {
    when (val decision = InstinctMailbox.gate.ring(online(app))) {
      InstinctMailboxGate.Ring.AfterCurrent -> { countPoll(app); removed() }
      is InstinctMailboxGate.Ring.Later -> { schedule(app, KIND_RING, decision.delayMillis); removed() }
      InstinctMailboxGate.Ring.WhenOnline -> { schedule(app, KIND_RING, null); removed() }
      InstinctMailboxGate.Ring.PollNow -> now()
    }
  }

  /** An order in the doorbell that was refused (bad signature, out of time) or could not be claimed: poll instead. */
  internal fun ringFallback(context: Context) {
    InstinctPushDispatcher.inbox(context).add(PushEntry.Kind.RING)
  }

  /** After an order of the doorbell was handed over: a poll ~60 seconds later marks it taken in the sheet. */
  internal fun afterDirect(context: Context, order: MailboxOrder) {
    schedule(context.applicationContext, KIND_FOLLOW_UP, InstinctDoorbellPolicy.FOLLOW_UP_MILLIS)
  }

  /** The follow-up poll's job: it respects the gate like a ring (a running poll, the 5-second window, no network). */
  internal fun followUp(context: Context) {
    val app = context.applicationContext
    if (InstinctMailboxSettings(app).blocker() != null) return
    pollAsTheGateSays(app) { pollForRing(app) }
  }

  /** One ring-triggered poll; without network it leaves a job that polls when there is. */
  internal fun pollForRing(context: Context): InstinctMailbox.Result {
    val app = context.applicationContext
    countPoll(app)
    val result = runCatching { InstinctMailbox.poll(app) }.getOrElse { InstinctMailbox.Result.Failed(InstinctLogClient.Failure.NETWORK.label) }
    if (result is InstinctMailbox.Result.Failed && result.reason == InstinctLogClient.Failure.NETWORK.label) schedule(app, KIND_RING, null)
    return result
  }

  private fun countPoll(context: Context) { settings(context).update { it.copy(ringPolls = it.ringPolls + 1) } }

  /** The backup interval for the mailbox's job: 15 minutes while the doorbell rang in the last 24 hours. */
  fun backupMinutes(context: Context, chosen: Int): Int {
    val s = settings(context)
    return InstinctDoorbellPolicy.backupMinutes(chosen, s.enabled() && s.endpoint() != null, s.state().lastRingAt, clock())
  }

  /** The endpoint (or its deletion) to the script, as a job with the network and backoff, like the outbox. */
  fun inform(context: Context) {
    val app = context.applicationContext
    settings(app).update { it.copy(informPending = true) }
    schedule(app, KIND_INFORM, 0L)
  }

  /**
   * Sends what is pending to the script now. True when the job should retry (no network, an HTTP error); a
   * rejected token, an old script or a missing log configuration wait for the person (saving the log informs again).
   */
  fun informNow(context: Context): Boolean {
    val app = context.applicationContext
    val s = settings(app)
    if (!s.state().informPending) return false
    val log = InstinctLogSettings(app)
    val url = log.url(); val token = log.token()
    if (url == null || token == null) { s.update { it.copy(failure = "falta configurar la bitácora") }; return false }
    val endpoint = s.endpoint()?.takeIf { s.enabled() }.orEmpty()
    val withOrder = s.withOrder() && endpoint.isNotEmpty()
    val raw = runCatching { client().post(url, InstinctDoorbellScript.informBody(token, endpoint, withOrder)) }
      .getOrElse { InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK) }
    return when (val answer = InstinctDoorbellScript.parseInform(raw)) {
      is InstinctDoorbellScript.Answer.Confirmed -> {
        // Only if nothing changed meanwhile: a newer endpoint (or option) stays pending for the next run.
        if (s.endpoint()?.takeIf { s.enabled() }.orEmpty() == endpoint && (s.withOrder() && endpoint.isNotEmpty()) == withOrder)
          // A deletion keeps the reason it happened (turned off, unregistered by the distributor). The option counts as
          // confirmed only when the script repeated it: a script older than 1.18.0 does not.
          s.update { it.copy(informPending = false, informedAt = if (answer.saved) clock() else null, failure = if (answer.saved) null else it.failure,
            withOrderConfirmed = withOrder && answer.withOrder == true) }
        false
      }
      is InstinctDoorbellScript.Answer.Failed -> { s.update { it.copy(failure = answer.reason) }; answer.retry }
    }
  }

  /** «Guardar bitácora»: the script may be a new one, so it hears the endpoint again. */
  fun onLogSaved(context: Context) {
    val s = settings(context)
    if (s.state().informPending || (s.enabled() && s.endpoint() != null)) inform(context)
  }

  /**
   * After a boot, an update of the log or the mailbox: register again (same token) while on, and whatever the script
   * still has to hear. The mailbox's backup interval follows.
   */
  fun rearm(context: Context) {
    val app = context.applicationContext
    val s = settings(app)
    if (s.enabled()) register(app)
    if (s.state().informPending || (s.enabled() && s.endpoint() != null)) inform(app)
    // A boot or an update with pushes still on disk: dispatch them (each order is verified again).
    if (InstinctPushDispatcher.inbox(app).size() > 0) InstinctPushDispatcher.kick(app)
  }

  /**
   * «Probar timbre»: asks the script to ring and waits up to 30 seconds for the ring to arrive. Blocking: call it
   * off the main thread.
   */
  fun test(context: Context): String {
    val app = context.applicationContext
    val log = InstinctLogSettings(app)
    val url = log.url(); val token = log.token()
    if (url == null || token == null) return "Falta configurar la bitácora (URL y token)."
    if (settings(app).endpoint() == null) return "Todavía no hay endpoint: encendé el timbre y esperá a que ntfy responda."
    val before = settings(app).state().rings
    val raw = runCatching { client().post(url, InstinctDoorbellScript.testBody(token)) }
      .getOrElse { InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK) }
    InstinctDoorbellScript.testFailure(raw)?.let { return "No sonó: $it." }
    val started = System.nanoTime()
    while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < InstinctDoorbellPolicy.TEST_WAIT_MILLIS) {
      if (settings(app).state().rings > before) {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        return "El timbre llegó en ${if (seconds < 1) "menos de un segundo" else "$seconds s"}."
      }
      Thread.sleep(250)
    }
    return "El script tocó el timbre, pero no llegó en 30 segundos. Revisá que ntfy esté abierto y sin restricción de batería."
  }

  /** The masked endpoint and whether the script has it, for Settings. */
  fun status(context: Context): String {
    val s = settings(context)
    return InstinctDoorbellText.status(s.endpoint(), s.state()) { InstinctDeferral.clock(it, Calendar.getInstance()) }
  }

  /** Under «Incluir la orden en el timbre»: whether the script already confirmed it. */
  fun withOrderStatus(context: Context): String = InstinctDoorbellText.withOrderStatus(settings(context).state())

  fun diagnosis(context: Context): String =
    InstinctDoorbellText.diagnosis(settings(context).state(), distributors(context).size) { InstinctDeferral.clock(it, Calendar.getInstance()) }

  /** The distributors installed: apps with a receiver for REGISTER (needs the `<queries>` entry). */
  private fun queryDistributors(context: Context): List<String> = runCatching {
    val pm = context.packageManager
    val intent = Intent(UnifiedPush.ACTION_REGISTER)
    @Suppress("DEPRECATION")
    val found = if (Build.VERSION.SDK_INT >= 33) pm.queryBroadcastReceivers(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
      else pm.queryBroadcastReceivers(intent, 0)
    found.mapNotNull { it.activityInfo?.packageName }.filter { it != context.packageName }.distinct().sorted()
  }.getOrDefault(emptyList())

  private fun sendToDistributor(context: Context, intent: Intent) {
    if (Build.VERSION.SDK_INT >= 34) {
      context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    } else context.sendBroadcast(intent)
  }

  private fun isOnline(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = manager.activeNetwork ?: return false
    manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
  }.getOrDefault(true)

  private fun jobId(kind: String) = when (kind) {
    KIND_RING -> RING_JOB_ID; KIND_FOLLOW_UP -> FOLLOW_UP_JOB_ID; KIND_DRAIN -> DRAIN_JOB_ID; else -> INFORM_JOB_ID
  }

  private fun cancelJob(context: Context, kind: String) {
    runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(jobId(kind)) }
  }

  /** [delayMillis] null: expedited from Android 12 (common before), with the network. A ring job never replaces the backup one. */
  private fun scheduleJob(context: Context, kind: String, delayMillis: Long?) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    val id = jobId(kind)
    fun builder() = JobInfo.Builder(id, ComponentName(context, InstinctDoorbellJobService::class.java))
      // The rescue job dispatches what is on disk: an order needs no network; a ring's poll waits for it on its own.
      .setRequiredNetworkType(if (kind == KIND_DRAIN) JobInfo.NETWORK_TYPE_NONE else JobInfo.NETWORK_TYPE_ANY)
      .setExtras(PersistableBundle().apply { putString(EXTRA_KIND, kind) })
    val job = when {
      kind == KIND_INFORM -> builder().setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
      delayMillis != null -> builder().setMinimumLatency(delayMillis).build()
      Build.VERSION.SDK_INT >= 31 -> builder().setExpedited(true).build()
      else -> builder().build()
    }
    // An expedited job over its quota is refused: a common one then.
    if (runCatching { scheduler.schedule(job) }.getOrDefault(JobScheduler.RESULT_FAILURE) != JobScheduler.RESULT_SUCCESS && delayMillis == null)
      runCatching { scheduler.schedule(builder().build()) }
  }
}

/**
 * UnifiedPush's broadcasts from the distributor: NEW_ENDPOINT, MESSAGE, REGISTRATION_FAILED, UNREGISTERED and
 * TEMP_UNAVAILABLE. Exported, because the distributor is another app: every request with a token Insto did not
 * register is ignored. `onReceive()` only takes `goAsync()` and queues the work (1.19.0).
 */
class InstinctDoorbellReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    runCatching { InstinctDoorbell.onReceive(context, intent) { goAsync()?.let { pending -> PushFinisher { pending.finish() } } } }
  }
}

/** AND_3: a service the distributor may bind for a few seconds to raise Insto while it delivers. It does nothing else. */
class InstinctDoorbellRaiseService : Service() {
  private val binder = Binder()
  override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * The endpoint to the script, with retries, the polls a ring left for later, the follow-up poll and the push inbox's
 * rescue (1.19.0). One shared thread for all of them. Not exported.
 */
class InstinctDoorbellJobService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    val kind = params.extras.getString(InstinctDoorbell.EXTRA_KIND)
    val app = applicationContext
    val work = Runnable {
      val retry = when (kind) {
        InstinctDoorbell.KIND_RING -> { runCatching { InstinctDoorbell.pollForRing(app) }; false }
        InstinctDoorbell.KIND_FOLLOW_UP -> { runCatching { InstinctDoorbell.followUp(app) }; false }
        InstinctDoorbell.KIND_DRAIN -> {
          runCatching { InstinctPushDispatcher.drain(app); InstinctPushDispatcher.awaitIdle(InstinctDoorbellPolicy.JOB_WAIT_MILLIS) }; false
        }
        else -> runCatching { InstinctDoorbell.informNow(app) }.getOrDefault(true)
      }
      jobFinished(params, retry)
    }
    return runCatching { InstinctDoorbell.jobs.execute(work) }.isSuccess
  }

  override fun onStopJob(params: JobParameters): Boolean = params.extras.getString(InstinctDoorbell.EXTRA_KIND).let {
    it != InstinctDoorbell.KIND_RING && it != InstinctDoorbell.KIND_FOLLOW_UP
  }
}
