package com.example.phone

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PersistableBundle
import java.io.File
import java.util.Calendar
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
  private val sealedFile = File(app.noBackupFilesDir, SEALED_FILE)

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

  /** 1.19.0: the plain doorbell encrypted for Insto's own keys (RFC 8291), base64url, for the script. */
  internal fun sealed(): String? = runCatching { sealedFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
  internal fun writeSealed(value: String?) {
    if (value == null) sealedFile.delete() else writeAtomically(sealedFile, value, "insto-doorbell-sealed-")
  }

  fun state(): DoorbellState = synchronized(LOCK) { runCatching { DoorbellState.decode(stateFile.readText()) }.getOrDefault(DoorbellState()) }
  internal fun update(change: (DoorbellState) -> DoorbellState): DoorbellState = synchronized(LOCK) {
    change(state()).also { runCatching { writeAtomically(stateFile, it.encode(), "insto-doorbell-state-") } }
  }

  companion object {
    const val ENDPOINT_FILE = "instinct-doorbell-endpoint"
    const val STATE_FILE = "instinct-doorbell-state.json"
    const val SEALED_FILE = "instinct-doorbell-sealed"
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
  /** 1.19.0: tells the sheet which orders of the doorbell were taken. */
  internal const val KIND_TAKE = "take"
  private const val TAKE_JOB_ID = 7_322
  /** 1.19.0: drains the push inbox if the process died before dispatching it. */
  internal const val KIND_DRAIN = "drain"
  private const val DRAIN_JOB_ID = 7_321

  internal var clock: () -> Long = { InstinctScheduler.wallClock() }
  /** The UnifiedPush connector: the official library (1.19.0). */
  internal var connector: PushConnector = LibraryPushConnector
  /** The library's Web Push keys; tests use one without the Android Keystore. */
  internal var keyManager: (Context) -> org.unifiedpush.android.connector.keys.KeyManager = InstinctPushReceiver::defaultKeyManager
  /** The installed distributors; tests replace it. */
  internal var distributors: (Context) -> List<String> = { connector.distributors(it) }
  /** Sends a broadcast to a distributor (only the UNREGISTER of a 1.18.0 registration now); tests record it. */
  internal var send: (Context, Intent) -> Unit = ::sendToDistributor
  /** Registering touches the library's database and may generate keys: never on the main thread. Tests run it inline. */
  internal var io: (Runnable) -> Unit = { jobs.execute(it) }
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
   * REGISTER through the library (1.19.0), off the main thread: it keeps its token (re-registering keeps it), shares
   * Insto's identity on Android 14 and later or sends the `pi` before, and generates the Web Push keys the first time.
   * A registration of the hand-written connector of 1.18.0 is unregistered first.
   */
  fun register(context: Context) {
    val app = context.applicationContext
    val s = settings(app)
    val distributor = distributor(app) ?: run {
      s.update { it.copy(failure = if (distributors(app).isEmpty()) "sin distribuidor" else "elegí el distribuidor") }
      return
    }
    io(Runnable {
      migrateLegacy(app)
      val previous = s.state().distributor
      s.update { it.copy(distributor = distributor, confirmed = it.confirmed && previous == distributor,
        failure = it.failure?.takeIf { previous == distributor }) }
      runCatching { connector.register(app, distributor) }.onFailure { error ->
        s.update { it.copy(failure = "no se pudo registrar con el distribuidor", lastError = error.javaClass.simpleName) }
      }
    })
  }

  /**
   * 1.18.0 registered by hand, with its own token in the state. That token is unregistered with the distributor and
   * forgotten; the library registers anew (a new endpoint, which the script hears). Nothing else of 1.18.0 changes.
   */
  internal fun migrateLegacy(context: Context) {
    val app = context.applicationContext
    val s = settings(app)
    val state = s.state()
    val token = state.token ?: return
    state.distributor?.let { distributor ->
      val intent = Intent(UnifiedPush.ACTION_UNREGISTER).setPackage(distributor).putExtra(UnifiedPush.EXTRA_TOKEN, token)
        .putExtra(UnifiedPush.EXTRA_APPLICATION, app.packageName)
      if (Build.VERSION.SDK_INT < 34) intent.putExtra(UnifiedPush.EXTRA_PI, identity(app))
      runCatching { send(app, intent) }
    }
    s.update { it.copy(token = null, confirmed = false) }
  }

  /**
   * UNREGISTER through the library, which forgets the token at once (the spec: without waiting for UNREGISTERED);
   * the endpoint is deleted and the script told to delete its copy.
   */
  private fun unregister(context: Context) {
    val app = context.applicationContext
    io(Runnable {
      migrateLegacy(app)
      runCatching { connector.unregister(app) }
    })
    forget(app, failure = null)
  }

  /** The endpoint is gone (turned off, UNREGISTERED): delete it here and in the script. */
  private fun forget(context: Context, failure: String?) {
    val s = settings(context)
    val hadEndpoint = s.endpoint() != null || s.state().informedAt != null
    s.writeEndpoint(null)
    s.writeSealed(null)
    s.update { it.copy(token = null, confirmed = false, informedAt = null, informPending = hadEndpoint, failure = failure,
      encryptionConfirmed = false, relayConfirmed = false) }
    if (hadEndpoint) inform(context)
    InstinctCatalogPublisher.changed(context)
  }

  /** An IMMUTABLE broadcast to a package that does not exist: only its creator, Insto, matters to the distributor. */
  private fun identity(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
    Intent(UnifiedPush.DUMMY_APP).setPackage(UnifiedPush.DUMMY_APP), PendingIntent.FLAG_IMMUTABLE)

  /**
   * NEW_ENDPOINT, on the receiving thread, after the library checked the token. [keys] are the registration's Web Push
   * keys: Insto encrypts its plain doorbell with them (RFC 8291) for the script. The endpoint and the keys never go to
   * Logcat, the diagnosis nor the catalog.
   */
  internal fun onNewEndpoint(context: Context, endpoint: String, keys: PushKeys?, temporary: Boolean) {
    val app = context.applicationContext
    val s = settings(app)
    if (!InstinctDoorbellEndpoint.valid(endpoint)) {
      s.update { it.copy(confirmed = true, failure = "el endpoint no es https o es demasiado largo") }
      return
    }
    val changed = s.endpoint() != endpoint
    val sealed = keys?.let { k -> runCatching { InstinctPushCrypto.encode(InstinctPushCrypto.seal(k, InstinctDoorbellPayload.PLAIN.toByteArray())) }.getOrNull() }
    val sealedChanged = keys?.let { s.state().keysFingerprint != fingerprint(it) } ?: true
    s.writeEndpoint(endpoint)
    // A new endpoint or new keys: a new sealed doorbell; otherwise the one the script has stays valid.
    if (changed || sealedChanged || s.sealed() == null) s.writeSealed(sealed)
    s.update {
      it.copy(confirmed = true, failure = null, informPending = true, informedAt = it.informedAt.takeUnless { changed || sealedChanged },
        temporaryEndpoint = temporary, keysFingerprint = keys?.let(::fingerprint),
        encryptionConfirmed = it.encryptionConfirmed && !changed && !sealedChanged,
        lastError = if (keys == null) "el distribuidor no entregó claves de cifrado" else it.lastError)
    }
    inform(app)
    InstinctMailbox.rearm(app)
    // The doorbell became active: the catalog says so.
    InstinctCatalogPublisher.changed(app)
  }

  /** A short hash of the public keys: only to know whether they changed. */
  private fun fingerprint(keys: PushKeys): String = InstinctMailboxSignature.messageId(keys.p256dh + "." + keys.auth)

  /**
   * MESSAGE, on the receiving thread, after the library checked the token and tried to decrypt it. Classified, then
   * stored in the inbox **before** returning: the library acknowledges it only after this. The content is never logged.
   */
  internal fun onMessage(context: Context, content: ByteArray, decrypted: Boolean, receivedAt: Long) {
    val app = context.applicationContext
    val s = settings(app)
    val classified = InstinctPushMessage.classify(content.takeIf { it.size <= MAX_MESSAGE_BYTES }, decrypted, s.state().encryptionConfirmed)
    s.update {
      it.copy(encryptedPushes = it.encryptedPushes + if (classified.encrypted) 1 else 0,
        plainPushes = it.plainPushes + if (classified.plainLegacy || classified.downgraded) 1 else 0,
        decryptFailures = it.decryptFailures + if (classified.undecryptable) 1 else 0,
        lastError = when {
          classified.undecryptable -> "un timbre cifrado no se pudo descifrar (claves viejas o mensaje dañado)"
          classified.downgraded -> "llegó un timbre sin cifrar: solo se usó para revisar el buzón"
          else -> it.lastError
        })
    }
    accept(app, classified.kind, classified.encrypted, classified.malformed, receivedAt)
    CapabilityHealthRuntime.current()?.let { health ->
      val code = if (classified.malformed || classified.undecryptable) HealthCode.RESPONSE else HealthCode.VERIFIED
      health.report("instinct.push", code, (clock() - receivedAt).takeIf { it >= 0 }, evidence = HealthEvidence.TRANSPORT)
    }
  }

  /** REGISTRATION_FAILED: the library keeps its token; the reason, for the diagnosis. */
  internal fun onRegistrationFailed(context: Context, reason: String?) {
    settings(context).update { it.copy(confirmed = false, failure = InstinctDoorbellProtocol.failureLabel(reason)) }
  }

  /** UNREGISTERED: the library already forgot the registration. */
  internal fun onUnregistered(context: Context) = forget(context.applicationContext, failure = "el distribuidor dio de baja el registro")

  /** TEMP_UNAVAILABLE: the distributor's server is down; the backup poll covers it. */
  internal fun onTempUnavailable(context: Context) {
    CapabilityHealthRuntime.current()?.report("instinct.push", HealthCode.PROVIDER_DOWN, evidence = HealthEvidence.TRANSPORT)
    settings(context).update { it.copy(lastError = "el servidor del distribuidor no está disponible por ahora; queda la revisión periódica") }
  }

  /** AND_3: a push message is at most 4096 bytes; more is no payload at all. */
  private const val MAX_MESSAGE_BYTES = 4_096

  /**
   * A push that arrived, already classified: counted, then stored in the inbox with a rescue job, for
   * [InstinctPushDispatcher]. Receiving is not running: the order still goes through the signature, the window, the
   * record of seen ids and the same intake as a poll. With the mailbox off nothing is stored.
   */
  internal fun accept(context: Context, kind: InstinctPushMessage.Kind, encrypted: Boolean, malformed: Boolean = false,
    receivedAt: Long = InstinctMailbox.clock()) {
    val app = context.applicationContext
    settings(app).update {
      it.copy(rings = it.rings + 1, lastRingAt = clock(), badPayloads = it.badPayloads + if (malformed) 1 else 0)
    }
    if (InstinctMailboxSettings(app).blocker() != null) return
    // The backup poll slows down to 15 minutes while rings arrive.
    InstinctMailbox.rearm(app)
    val inbox = InstinctPushDispatcher.inbox(app)
    val added = when (kind) {
      InstinctPushMessage.Kind.Ring -> inbox.add(PushEntry.Kind.RING, encrypted = encrypted, receivedAt = receivedAt)
      is InstinctPushMessage.Kind.Order -> inbox.add(PushEntry.Kind.ORDER, kind.order, encrypted, receivedAt = receivedAt)
      is InstinctPushMessage.Kind.Reference -> inbox.add(PushEntry.Kind.REFERENCE, encrypted = encrypted, reference = kind.reference, receivedAt = receivedAt)
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

  /**
   * After an order of the doorbell was handed over, the sheet must hear it was taken. A 1.19.0 script rings for every
   * new order, so a `POST tomadas` with its id is enough (a job with the network and backoff), without reading the sheet.
   * An older script skips rings within 5 seconds: then a poll ~60 seconds later also picks up any order it skipped.
   */
  internal fun afterDirect(context: Context, order: MailboxOrder) {
    val app = context.applicationContext
    val s = settings(app)
    if (!s.state().perOrderRing) { schedule(app, KIND_FOLLOW_UP, InstinctDoorbellPolicy.FOLLOW_UP_MILLIS); return }
    s.update { it.copy(pendingTaken = (it.pendingTaken + order.id.lowercase()).distinct().takeLast(DoorbellState.MAX_PENDING_TAKEN)) }
    schedule(app, KIND_TAKE, 0L)
  }

  /** The `tomadas` job: true when it should retry (no network, the script failed). Never runs or reads an order. */
  fun takeNow(context: Context): Boolean {
    val app = context.applicationContext
    val s = settings(app)
    val ids = s.state().pendingTaken
    if (ids.isEmpty()) return false
    val log = InstinctLogSettings(app)
    val url = log.url() ?: return false
    val token = log.token() ?: return false
    val ok = runCatching { InstinctMailbox.client().take(url, token, ids) }.getOrDefault(false)
    if (ok) s.update { state -> state.copy(pendingTaken = state.pendingTaken - ids.toSet()) }
    return !ok
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
    if (result is InstinctMailbox.Result.Failed && result.reason != "apagado")
      settings(app).update { it.copy(lastError = "el sondeo por timbre falló (${result.reason.take(120)})") }
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
    // 1.19.0: the sealed plain doorbell and, only with the order in the doorbell, the public keys for the relay.
    val sealed = s.sealed()?.takeIf { endpoint.isNotEmpty() }
    val keys = if (withOrder && sealed != null) runCatching { connector.publicKeys(app) }.getOrNull() else null
    val cifrado = sealed?.let { InstinctDoorbellScript.Sealed(it, keys?.p256dh, keys?.auth) }
    val raw = runCatching { client().post(url, InstinctDoorbellScript.informBody(token, endpoint, withOrder, cifrado)) }
      .getOrElse { InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK) }
    return when (val answer = InstinctDoorbellScript.parseInform(raw)) {
      is InstinctDoorbellScript.Answer.Confirmed -> {
        // Only if nothing changed meanwhile: a newer endpoint (or option) stays pending for the next run.
        if (s.endpoint()?.takeIf { s.enabled() }.orEmpty() == endpoint && (s.withOrder() && endpoint.isNotEmpty()) == withOrder)
          // A deletion keeps the reason it happened (turned off, unregistered by the distributor). The option counts as
          // confirmed only when the script repeated it: a script older than 1.18.0 does not.
          s.update { it.copy(informPending = false, informedAt = if (answer.saved) clock() else null, failure = if (answer.saved) null else it.failure,
            withOrderConfirmed = withOrder && answer.withOrder == true,
            // Only a script that saved the sealed doorbell makes clear pushes suspicious (1.19.0).
            encryptionConfirmed = answer.saved && cifrado != null && answer.sealed == true,
            relayConfirmed = answer.saved && answer.relay == true, perOrderRing = answer.saved && answer.perOrder == true) }
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
    if (s.state().pendingTaken.isNotEmpty()) schedule(app, KIND_TAKE, 0L)
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

  /** One step of «Probar de punta a punta»: [ok] null when it could not be checked. */
  data class Step(val name: String, val ok: Boolean?, val detail: String) {
    override fun toString(): String = (when (ok) { true -> "✓ "; false -> "✗ "; null -> "– " }) + "$name: $detail"
  }

  /** How long «Probar de punta a punta» waits for the push; tests shorten it. */
  internal var endToEndWaitMillis = InstinctDoorbellPolicy.TEST_WAIT_MILLIS

  /** The order «Probar de punta a punta» publishes: read-only, it says what would happen and runs nothing. */
  const val END_TO_END_LINE = "#insto probar ver estado"

  /**
   * «Probar de punta a punta» (1.19.0): publishes in the mailbox, signed with the mailbox's key, one order that only
   * answers (`#insto probar ver estado`: it runs nothing, changes no volume and sets no alarm), lets the script ring,
   * and tells apart registration, delivery, decryption, verification, dispatch and result. Waits up to 30 seconds.
   * Blocking: call it off the main thread.
   */
  fun endToEnd(context: Context): List<Step> {
    val app = context.applicationContext
    val s = settings(app)
    val log = InstinctLogSettings(app)
    val url = log.url(); val token = log.token(); val key = InstinctMailboxSettings(app).key()
    if (url == null || token == null) return listOf(Step("Bitácora", false, "falta configurar la URL y el token."))
    if (key == null || InstinctMailboxSettings(app).blocker() != null) return listOf(Step("Buzón", false, "encendé el buzón y guardá la clave."))
    val steps = ArrayList<Step>()
    val registered = s.endpoint() != null && s.state().informedAt != null && !s.state().informPending
    steps += Step("Registro", registered, if (registered) "endpoint ${s.maskedEndpoint()}, el script lo tiene." else
      "el script todavía no tiene el endpoint (${s.state().failure ?: "esperando al distribuidor"}).")
    if (!registered) return steps
    val before = s.state()
    val id = java.util.UUID.randomUUID().toString()
    val at = InstinctLogEvents.iso(InstinctMailbox.clock())
    val lines = listOf(END_TO_END_LINE)
    val order = MailboxOrder(id, at, lines, InstinctMailboxSignature.sign(key, InstinctMailboxSignature.canonical(id, at, lines)))
    val published = runCatching { client().post(url, InstinctMailboxPoll.orderBody(token, order)) }
      .getOrElse { InstinctLogClient.Raw.Failed(InstinctLogClient.Failure.NETWORK) }
    InstinctDoorbellScript.publishFailure(published)?.let {
      steps += Step("Entrega del push", false, "no se pudo publicar la orden de prueba en el buzón: $it.")
      return steps
    }
    val seen = InstinctMailbox.seen(app)
    val started = System.nanoTime()
    fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    while (elapsed() < endToEndWaitMillis && !(s.state().rings > before.rings && seen.contains(id))) Thread.sleep(250)
    val after = s.state()
    val delivered = after.rings > before.rings
    steps += Step("Entrega del push", delivered, if (delivered) "llegó en ${elapsed() / 1000} s." else
      "no llegó en 30 s: ahora Insto funciona solo con la revisión periódica. Revisá ntfy (abierto y sin restricción de batería).")
    steps += when {
      after.encryptedPushes > before.encryptedPushes -> Step("Descifrado", true, "cifrado RFC 8291, descifrado con las claves de Insto.")
      after.decryptFailures > before.decryptFailures -> Step("Descifrado", false, "llegó cifrado pero no se pudo descifrar.")
      after.plainPushes > before.plainPushes -> Step("Descifrado", false, "llegó sin cifrar (modo heredado, no conforme con UnifiedPush).")
      else -> Step("Descifrado", null, "sin push que descifrar.")
    }
    if (!seen.contains(id)) runCatching { InstinctMailbox.poll(app) }
    val verified = seen.contains(id)
    steps += Step("Verificación de la orden", verified, if (verified) "firma, vigencia e id válidos." else "la orden de prueba no se verificó.")
    val direct = s.state().directOrders > before.directOrders
    steps += Step("Despacho", if (verified) true else null, when {
      direct -> "por el timbre, sin leer la planilla."
      verified -> "por el sondeo del buzón (la orden no vino dentro del timbre)."
      else -> "no se despachó."
    })
    val answered = InstinctLog.lastMessage == InstinctMailboxSignature.messageId(id)
    steps += Step("Resultado", if (InstinctProposalSettings(app).replyMode() == InstinctReplyMode.NEVER) null else answered, when {
      InstinctProposalSettings(app).replyMode() == InstinctReplyMode.NEVER -> "«Responderle a Instinct» está en Nunca: no hay respuesta que ver."
      answered -> "la respuesta de la prueba quedó en la bitácora. No se ejecutó nada."
      else -> "no se registró la respuesta de la prueba."
    })
    return steps
  }

  /** The doorbell in detail, for Settings. Never a key nor the whole endpoint. */
  fun details(context: Context): List<String> {
    val app = context.applicationContext
    val s = settings(app)
    val box = InstinctMailboxSettings(app)
    return InstinctDoorbellText.details(s.state(), distributor(app), s.endpoint(), s.withOrder(), InstinctMailbox.status(app)?.at,
      InstinctPushDispatcher.inbox(app).size(), backupMinutes(app, box.intervalMinutes())) { InstinctDeferral.clock(it, Calendar.getInstance()) }
  }

  /** The masked endpoint and whether the script has it, for Settings. */
  fun status(context: Context): String {
    val s = settings(context)
    return InstinctDoorbellText.status(s.endpoint(), s.state()) { InstinctDeferral.clock(it, Calendar.getInstance()) }
  }

  /** Whether the doorbell travels encrypted, for Settings. Never a key. */
  fun encryptionStatus(context: Context): String {
    val s = settings(context)
    return InstinctDoorbellText.encryption(s.state(), s.endpoint() != null, s.withOrder())
  }

  /** Under «Incluir la orden en el timbre»: whether the script already confirmed it. */
  fun withOrderStatus(context: Context): String = InstinctDoorbellText.withOrderStatus(settings(context).state())

  fun diagnosis(context: Context): String =
    InstinctDoorbellText.diagnosis(settings(context).state(), distributors(context).size) { InstinctDeferral.clock(it, Calendar.getInstance()) }

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
    KIND_RING -> RING_JOB_ID; KIND_FOLLOW_UP -> FOLLOW_UP_JOB_ID; KIND_DRAIN -> DRAIN_JOB_ID; KIND_TAKE -> TAKE_JOB_ID; else -> INFORM_JOB_ID
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
      kind == KIND_INFORM || kind == KIND_TAKE -> builder().setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
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
        InstinctDoorbell.KIND_TAKE -> runCatching { InstinctDoorbell.takeNow(app) }.getOrDefault(true)
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
