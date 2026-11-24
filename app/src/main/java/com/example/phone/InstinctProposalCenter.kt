package com.example.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.example.data.AssistantPreferencesCache
import com.example.whatsapp.PhoneNumberUtils
import com.example.whatsapp.WhatsAppDispatcher
import java.io.File
import org.json.JSONObject

/**
 * The opt-in switch «Recibir órdenes de Instinct» and its reply settings. Off by default, stored in
 * [Context.getNoBackupFilesDir] like Gemini's switch: never in preferences backups, configuration exports or the
 * cloud, so a restore never turns passive reading or automatic execution on. What runs alone is decided per category
 * by [InstinctAutonomySettings] (1.16.0), which replaced «Pedirme confirmación antes de ejecutar».
 */
class InstinctProposalSettings(context: Context) {
  private val app = context.applicationContext
  private val enabled = File(app.noBackupFilesDir, "instinct-proposals-enabled")
  private val replyMode = File(app.noBackupFilesDir, "instinct-proposals-reply-mode")
  private val ackChannel = File(app.noBackupFilesDir, "instinct-proposals-ack-channel")

  /** Read on every posted notification: one small file, before anything of the notification is touched. */
  fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK && runCatching { enabled.readText() == "true" }.getOrDefault(false)

  fun setEnabled(value: Boolean) {
    if (value) { enabled.writeText("true"); InstinctContextScheduler.signal(app); InstinctStatePublisher.rearm(app); InstinctMailbox.rearm(app); InstinctCatalogPublisher.changed(app) }
    else {
      if (enabled.exists()) enabled.delete()
      // The state's heartbeat and the mailbox's polling stop with the switch.
      InstinctStatePublisher.rearm(app)
      InstinctMailbox.rearm(app)
      // Replies are cut first: discarding below must not answer Instinct either.
      InstinctProposalCenter.acks.clear()
      // Turning it off discards every pending proposal and scheduled order: nothing can run after this.
      InstinctProposalCenter.discardAll(app)
      InstinctScheduler.cancelAll(app)
      InstinctContextScheduler.stop(app)
      InstinctPlanEngine.stopAll(app)
      InstinctProposalCenter.undos.clear()
      InstinctQuestionNotifications.recoverSoon(app)
    }
  }

  /** «Responderle a Instinct». Never by default; a restore never brings it, like the other switches. */
  fun replyMode(): InstinctReplyMode = runCatching { InstinctReplyMode.valueOf(replyMode.readText()) }.getOrDefault(InstinctReplyMode.NEVER)
  fun setReplyMode(value: InstinctReplyMode) {
    if (value == InstinctReplyMode.NEVER) {
      if (replyMode.exists()) replyMode.delete()
      InstinctProposalCenter.acks.clear()
    } else replyMode.writeText(value.name)
    InstinctCatalogPublisher.changed(app)
  }

  /** «Canal de estados» (1.15.0): WhatsApp by default; a restore never brings it, like the other switches. */
  fun channel(): InstinctAckChannel = runCatching { InstinctAckChannel.valueOf(ackChannel.readText()) }.getOrDefault(InstinctAckChannel.WHATSAPP)
  fun setChannel(value: InstinctAckChannel) {
    if (value == InstinctAckChannel.WHATSAPP) { if (ackChannel.exists()) ackChannel.delete() } else ackChannel.writeText(value.name)
    InstinctCatalogPublisher.changed(app)
  }

  companion object {
    /** [Notification.EXTRA_IS_GROUP_CONVERSATION] exists from Android 9; without it a group cannot be excluded. */
    const val MIN_SDK = Build.VERSION_CODES.P
    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
  }
}

/** Reads a WhatsApp notification's extras lazily, in the order the filters ask. Nothing is copied or kept. */
internal class ExtrasProposalNotification(
  override val packageName: String,
  override val key: String,
  private val extras: () -> Bundle,
) : ProposalNotification {
  private fun latestBundle(): Bundle? = runCatching {
    @Suppress("DEPRECATION") extras().getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle
  }.getOrNull()

  override fun isGroup(): Boolean = Build.VERSION.SDK_INT < InstinctProposalSettings.MIN_SDK ||
    extras().getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

  override fun title(): String? = extras().getCharSequence(Notification.EXTRA_TITLE)?.toString()

  override fun latestMessage(): ProposalNotification.LatestMessage? {
    val message = latestBundle() ?: return null
    @Suppress("DEPRECATION")
    val person = if (Build.VERSION.SDK_INT >= 28) message.getParcelable<android.app.Person>(KEY_SENDER_PERSON)?.name else null
    return ProposalNotification.LatestMessage((person ?: message.getCharSequence(KEY_SENDER))?.toString(),
      message.getLong(KEY_TIME).takeIf { it > 0L })
  }

  override fun text(): String? = (latestBundle()?.getCharSequence(KEY_TEXT)
    ?: extras().getCharSequence(Notification.EXTRA_TEXT))?.toString()

  private companion object {
    // Keys of Notification.MessagingStyle.Message bundles (framework constants).
    const val KEY_TEXT = "text"
    const val KEY_TIME = "time"
    const val KEY_SENDER = "sender"
    const val KEY_SENDER_PERSON = "sender_person"
  }
}

/**
 * WhatsApp's «Responder» action of the notification that brought the orders: the action and the key of its free-text
 * [android.app.RemoteInput]. In memory only, inside [InstinctAckLedger], for at most ten minutes; never persisted.
 * Sending fills the remote input and fires WhatsApp's own PendingIntent: WhatsApp is not opened, the screen is not
 * touched and it works the same with the phone locked.
 */
class InstinctReplyHandle internal constructor(private val action: Notification.Action, private val resultKey: String) {
  /** False when WhatsApp cancelled the action: the reply is dropped, never retried another way. */
  fun send(context: Context, text: String): Boolean {
    val inputs = action.remoteInputs ?: return false
    val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    android.app.RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { putCharSequence(resultKey, text) })
    if (Build.VERSION.SDK_INT >= 28) android.app.RemoteInput.setResultsSource(intent, android.app.RemoteInput.SOURCE_FREE_FORM_INPUT)
    return try { action.actionIntent.send(context, 0, intent); true } catch (_: PendingIntent.CanceledException) { false }
  }

  companion object {
    /** The action whose remote input takes free text: WhatsApp's and WhatsApp Business's «Responder». */
    fun from(notification: Notification): InstinctReplyHandle? {
      val actions = (notification.actions.orEmpty().asList() +
        runCatching { Notification.WearableExtender(notification).actions }.getOrDefault(emptyList()))
      for (action in actions) {
        if (action?.actionIntent == null) continue
        val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput && it.resultKey.isNotEmpty() } ?: continue
        return InstinctReplyHandle(action, input.resultKey)
      }
      return null
    }
  }
}

/**
 * Entry point from [NotificationAccessService]. With the switch off it returns before reading the notification. With
 * it on, the filters decide in order and every line that passes them is validated before it is offered. Logcat gets
 * stage counters only, never a title, a name or a text.
 */
object InstinctProposalIntake {
  internal val filter = InstinctProposalFilter()
  internal var log: (String) -> Unit = { if (com.example.BuildConfig.DEBUG) android.util.Log.d(TAG, it) }
  private const val TAG = "InstoProposals"

  /** Why the last WhatsApp notification was not offered, without any content. In memory only, for Settings. */
  enum class Diagnosis(val label: String) {
    PREFS("Insto todavía no había cargado los ajustes (el proceso recién arrancaba)."),
    NO_CONTACT("No hay contacto de Instinct configurado."),
    PACKAGE("Llegó de una app de WhatsApp distinta de la que Insto usa para enviar."),
    GROUP("Era una conversación grupal."),
    CONTACT("El remitente no coincide exactamente con el nombre del contacto configurado en Insto."),
    DUPLICATE("Ese mensaje ya se había procesado."),
    NO_PROPOSAL("El mensaje no tenía líneas que empiecen con «#insto »."),
    NOT_UNDERSTOOD("Había líneas #insto, pero ninguna se entendió o estaba permitida."),
    OFFERED("Llegó al panel abierto."),
    EXECUTED("Se ejecutó."),
    CONFIRM("Quedó para confirmar."),
    REFUSED("Se rechazó: elegiste que Instinct nunca haga esa acción."),
    ONLY_MAILBOX("Tenía líneas #insto, pero elegiste aceptar órdenes solo por el buzón: se ignoraron."),
    FAILED("Se intentó, pero Android no lo permitió."),
    REPLIED("Se respondió a Instinct."),
    REPLY_FAILED("No se pudo responder."),
  }

  @Volatile var lastDiagnosis: Diagnosis? = null
    private set
  @Volatile var lastDiagnosisAt: Long = 0L
    private set

  /** Extra detail for [Diagnosis.CONFIRM] and [Diagnosis.FAILED]: Insto's own result message, never Instinct's text. */
  @Volatile var lastDetail: String? = null
    private set

  internal fun diagnose(value: Diagnosis, detail: String? = null) {
    if (value == Diagnosis.REPLIED || value == Diagnosis.REPLY_FAILED) {
      // Replies have their own line: they never hide what happened to the orders.
      lastReply = value; lastReplyDetail = detail; lastReplyAt = SystemClock.elapsedRealtime(); return
    }
    lastDiagnosis = value; lastDetail = detail; lastDiagnosisAt = SystemClock.elapsedRealtime()
  }

  /** The last reply to Instinct: [Diagnosis.REPLIED] or [Diagnosis.REPLY_FAILED], without any content. */
  @Volatile var lastReply: Diagnosis? = null
    private set
  @Volatile var lastReplyDetail: String? = null
    private set
  @Volatile var lastReplyAt: Long = 0L
    private set

  private val whatsAppPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

  /** [replyHandle] is read only for an accepted message, and only when replies are on and go to the chat. */
  fun onPosted(context: Context, notification: () -> ProposalNotification?, replyHandle: () -> InstinctReplyHandle? = { null }) {
    if (!InstinctProposalSettings(context).isEnabled()) return
    val app = context.applicationContext
    val view = notification() ?: return
    // Only WhatsApp's own notifications update the diagnosis: the package name is not content.
    val fromWhatsApp = view.packageName in whatsAppPackages
    val prefs = AssistantPreferencesCache.of(app).current ?: run { if (fromWhatsApp) diagnose(Diagnosis.PREFS); return }
    val contact = prefs.pinnedContact ?: run { if (fromWhatsApp) diagnose(Diagnosis.NO_CONTACT); return }
    val packageName = WhatsAppDispatcher.resolveTargetPackage(app, prefs.preferredFlavor)
    val config = ProposalFilterConfig(packageName, contact.displayName, SystemClock.elapsedRealtime())
    val outcome = filter.evaluate(view, config)
    if (fromWhatsApp) when (outcome.stage) {
      InstinctProposalFilter.Stage.PACKAGE -> diagnose(Diagnosis.PACKAGE)
      InstinctProposalFilter.Stage.GROUP -> diagnose(Diagnosis.GROUP)
      InstinctProposalFilter.Stage.CONTACT -> diagnose(Diagnosis.CONTACT)
      InstinctProposalFilter.Stage.DUPLICATE -> diagnose(Diagnosis.DUPLICATE)
      InstinctProposalFilter.Stage.NO_PROPOSAL -> diagnose(Diagnosis.NO_PROPOSAL)
      InstinctProposalFilter.Stage.ACCEPTED -> Unit
    }
    // Any WhatsApp notification of Instinct's contact, with or without «#insto»: a good moment to read the mailbox.
    if (outcome.stage == InstinctProposalFilter.Stage.ACCEPTED || outcome.stage == InstinctProposalFilter.Stage.NO_PROPOSAL ||
      outcome.stage == InstinctProposalFilter.Stage.DUPLICATE) runCatching { InstinctMailbox.pollSoon(app) }
    if (outcome !is InstinctProposalFilter.Outcome.Accepted) {
      if (outcome.stage != InstinctProposalFilter.Stage.PACKAGE) log(counters())
      return
    }
    // «Solo buzón»: unsigned orders are ignored, so a contact with the same name cannot order anything.
    if (InstinctMailboxSettings(app).accept() == InstinctMailboxAccept.SOLO_BUZON) {
      diagnose(Diagnosis.ONLY_MAILBOX)
      log(counters() + " only_mailbox=1")
      return
    }
    val parser = PhoneCommandParser()
    val routines = InstinctRoutineGrants(app).access()
    // One wall clock for the grammar and the deferred orders it may produce.
    val now = java.util.Calendar.getInstance().apply { timeInMillis = InstinctScheduler.wallClock() }
    val checks = outcome.orders.map { InstinctProposalValidator.check(it, parser, now, routines) }
    if (checks.none { it !is ProposalCheck.Invalid }) diagnose(Diagnosis.NOT_UNDERSTOOD)
    val fingerprint = InstinctProposalCenter.fingerprint(app) ?: return
    // 1.20.0: every accepted line gets its own row before anything runs; without the journal nothing runs.
    val messageId = if (checks.any { InstinctQuestion.isRelated(it.order) }) {
      // The same WhatsApp notification can be posted after process death. Reuse its message identity in the journal.
      ExecutionIds.whatsappQuestion(outcome.message, view.latestMessage()?.sentAt)
    } else ExecutionIds.whatsapp(outcome.message, InstinctScheduler.wallClock(), java.util.UUID.randomUUID().toString())
    val records = try {
      (InstinctExecutions.journal(app).admit(messageId, ExecutionTransport.WHATSAPP, outcome.orders, outcome.message)
        as? InstinctExecutionJournal.Admission.Admitted)?.records.orEmpty()
    } catch (_: Throwable) {
      diagnose(Diagnosis.FAILED, JOURNAL_FAILED); return
    }
    if (records.isEmpty()) return
    val ticket = InstinctProposalCenter.openReply(app, checks.count { !InstinctQuestion.isRelated(it.order) }, replyHandle, outcome.message)
    records.forEach { InstinctExecutions.move(app, it.executionId, ExecutionState.VALIDATED) }
    InstinctProposalCenter.deliver(app, checks, fingerprint, ticket, outcome.message, records = records)
    log(counters() + " valid=${checks.count { it is ProposalCheck.Valid }} invalid=${checks.count { it is ProposalCheck.Invalid }}")
  }

  /**
   * A verified order from the signed mailbox: the same entry point as WhatsApp's, with [ProposalSource.BUZON]. The
   * same validator, allowlist, brake, autonomy, execution, «Deshacer» and deferred orders. Its acknowledgements go to
   * the log, whatever the channel: there is no WhatsApp notification to answer. Since 1.20.0 its orders are the rows
   * the claim recorded in the journal; only those still [ExecutionState.RECEIVED] go on, so a second call for the same
   * message (a recovery, a repeated drain) never offers an order twice.
   */
  fun onMailbox(context: Context, order: MailboxOrder, transport: ExecutionTransport = ExecutionTransport.POLL) {
    val app = context.applicationContext
    val journal = InstinctExecutions.journal(app)
    val message = InstinctMailboxSignature.messageId(order.id)
    val records = try {
      journal.forMessage(order.id).ifEmpty {
        // A caller that did not claim through the journal: the rows are made now, without a claim.
        val lines = InstinctProposalProtocol.orders(order.lines.joinToString("\n"))
        (journal.admit(order.id, transport, lines, message, claim = false) as? InstinctExecutionJournal.Admission.Admitted)?.records.orEmpty()
      }
    } catch (_: Throwable) { diagnose(Diagnosis.FAILED, JOURNAL_FAILED); return }
    resume(app, records, message, ProposalSource.BUZON)
  }

  /**
   * Orders already in the journal as [ExecutionState.RECEIVED] (just claimed, or left by a process that died before
   * validating them): validated now, with the configuration in force now, and delivered. Without the preferences yet
   * (a cold start) they stay received and recovery tries again; with the switch off they are cancelled.
   */
  internal fun resume(app: Context, rows: List<ExecutionRecord>, message: String, source: ProposalSource, adopted: Boolean = false) {
    // Each row once at a time in this process: the arrival and a recovery never validate the same order together.
    // Read again under that guard: only a row still received (or, adopted from a dead process, not offered here) goes on.
    val journal = InstinctExecutions.journal(app)
    val taken = rows.filter { resuming.add(it.executionId) }
    try {
      val records = taken.mapNotNull { row -> runCatching { journal.get(row.executionId) }.getOrNull() }.filter {
        if (adopted) it.state in RESUMABLE else it.state == ExecutionState.RECEIVED
      }
      if (records.isNotEmpty()) resumeNow(app, records, message, source)
    } finally { taken.forEach { resuming.remove(it.executionId) } }
  }
  private val resuming: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
  private val RESUMABLE = setOf(ExecutionState.RECEIVED, ExecutionState.VALIDATED, ExecutionState.READY)

  private fun resumeNow(app: Context, admitted: List<ExecutionRecord>, message: String, source: ProposalSource) {
    if (!InstinctProposalSettings(app).isEnabled() || (source == ProposalSource.BUZON && !InstinctMailboxSettings(app).enabled())) {
      admitted.forEach { InstinctExecutions.move(app, it.executionId, ExecutionState.CANCELLED, reason = SWITCHED_OFF) }
      return
    }
    val records = if (source == ProposalSource.BUZON) {
      val (local, others) = admitted.partition { InstinctQuestion.isRelated(it.line) || InstinctProposalValidator.isContextOrder(it.line) }
      if (local.isNotEmpty()) {
        val parser = PhoneCommandParser()
        val checks = local.map {
          val received = java.util.Calendar.getInstance().apply { timeInMillis = it.receivedAt }
          InstinctProposalValidator.check(it.line, parser, received)
        }
        local.forEach { InstinctExecutions.move(app, it.executionId, ExecutionState.VALIDATED) }
        val ticket = InstinctProposalCenter.openReply(app, checks.count { !InstinctQuestion.isRelated(it.order) }, { null }, message, logOnly = true)
        InstinctProposalCenter.deliver(app, checks, "", ticket, message, source, records = local)
      }
      others
    } else admitted
    if (records.isEmpty()) return
    val fingerprint = InstinctProposalCenter.awaitFingerprint(app) ?: run {
      if (AssistantPreferencesCache.of(app).current == null) {
        // A cold start: DataStore has not answered. Nothing is lost: recovery resumes them.
        diagnose(Diagnosis.PREFS)
        InstinctExecutionRecoveryManager.later(app)
      } else records.forEach { InstinctExecutions.move(app, it.executionId, ExecutionState.FAILED, reason = NO_CONTACT) }
      return
    }
    val parser = PhoneCommandParser()
    val routines = InstinctRoutineGrants(app).access()
    val now = java.util.Calendar.getInstance().apply { timeInMillis = InstinctScheduler.wallClock() }
    val checks = records.map { InstinctProposalValidator.check(it.line, parser, now, routines) }
    records.forEach { InstinctExecutions.move(app, it.executionId, ExecutionState.VALIDATED) }
    val logOnly = source == ProposalSource.BUZON || records.any { it.transport != ExecutionTransport.WHATSAPP }
    val ticket = InstinctProposalCenter.openReply(app, checks.count { !InstinctQuestion.isRelated(it.order) }, { null }, message, logOnly = logOnly)
    InstinctProposalCenter.deliver(app, checks, fingerprint, ticket, message, source, records)
    log("mailbox valid=${checks.count { it is ProposalCheck.Valid }} invalid=${checks.count { it is ProposalCheck.Invalid }}")
  }

  const val JOURNAL_FAILED = "No se pudo guardar el diario de ejecución; no se ejecutó nada."
  const val SWITCHED_OFF = "«Recibir órdenes» o el buzón se apagaron antes de ejecutarla."
  const val NO_CONTACT = "no hay contacto de Instinct configurado; no se ejecutó."

  private fun counters(): String = filter.counters().entries.sortedBy { it.key.ordinal }
    .joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" }
}

/**
 * Proposals between the notification and the user's touch. Process-wide and in memory only: a process restart loses
 * them, and their notifications can then only report that they are no longer available.
 */
object InstinctProposalCenter {
  /** The open floating assistant. Main thread only. */
  interface Listener {
    fun onProposal(proposal: ProposalBook.Proposal)
    fun onNotUnderstood()
  }

  internal var clock: () -> Long = SystemClock::elapsedRealtime
  val book = ProposalBook({ clock() })
  private val main by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var listener: Listener? = null

  const val CHANNEL_ID = "instinct-proposals"
  /** Low importance: the discreet «not understood» notice never pops up or makes a sound. */
  const val NOTICE_CHANNEL_ID = "instinct-proposal-notices"
  /** «Instinct hizo: …» after an automatic execution. */
  const val ACTIONS_CHANNEL_ID = "instinct-actions"
  const val ACTION_CONFIRM = "com.example.phone.ACTION_CONFIRM_INSTINCT_PROPOSAL"
  const val ACTION_DISCARD = "com.example.phone.ACTION_DISCARD_INSTINCT_PROPOSAL"
  const val EXTRA_PROPOSAL_ID = "insto.instinct.proposal"
  const val EXTRA_PROPOSAL_TOKEN = "insto.instinct.proposal.token"
  private const val NOTIFICATION_BASE = 7_100
  private const val NOT_UNDERSTOOD_ID = 7_099

  /** The configuration proposals are validated and confirmed under; null with the switch off or no contact. */
  fun fingerprint(context: Context): String? {
    val app = context.applicationContext
    if (!InstinctProposalSettings(app).isEnabled()) return null
    val prefs = AssistantPreferencesCache.of(app).current ?: return null
    val contact = prefs.pinnedContact ?: return null
    val packageName = WhatsAppDispatcher.resolveTargetPackage(app, prefs.preferredFlavor) ?: return null
    return ProposalBook.fingerprint(contact.displayName, contact.phoneNumber, packageName)
  }

  /**
   * [fingerprint], waiting (never on the main thread) up to [millis] for DataStore on a cold start: the process a push
   * or a job just started may not have its preferences yet (1.20.0). Null if they did not load or there is no contact.
   */
  internal fun awaitFingerprint(context: Context, millis: Long = PREFS_WAIT_MILLIS): String? {
    fingerprint(context)?.let { return it }
    if (Looper.myLooper() == Looper.getMainLooper() || !InstinctProposalSettings(context).isEnabled()) return null
    val cache = AssistantPreferencesCache.of(context)
    // A bounded wait on this worker thread (never the main one): DataStore usually answers in tens of milliseconds.
    val deadline = System.nanoTime() + millis * 1_000_000
    while (cache.current == null && System.nanoTime() < deadline) runCatching { Thread.sleep(25) }
    return fingerprint(context)
  }
  internal var PREFS_WAIT_MILLIS = 3_000L

  fun attach(value: Listener) { listener = value }
  fun detach(value: Listener) { if (listener === value) listener = null }

  internal fun deliver(context: Context, checks: List<ProposalCheck>, fingerprint: String, ticket: InstinctAckLedger.Ticket? = null,
    message: String = "", source: ProposalSource = ProposalSource.WHATSAPP, records: List<ExecutionRecord> = emptyList()) {
    val app = context.applicationContext
    val offered = ArrayList<ProposalBook.Proposal>()
    var replyIndex = 0
    checks.forEachIndexed { recordIndex, check ->
      val record = records.getOrNull(recordIndex)
      if (InstinctQuestion.isRelated(check.order)) {
        if (record != null) when (check) {
          is ProposalCheck.Question -> {
            val received = java.util.Calendar.getInstance().apply { timeInMillis = record.receivedAt }
            val question = InstinctQuestion.parse(check.order, received)
            if (question == null) InstinctQuestionNotifications.reject(app, record, InstinctQuestion.INVALID)
            else InstinctQuestionNotifications.receive(app, record, question, source)
          }
          is ProposalCheck.Invalid -> InstinctQuestionNotifications.reject(app, record, InstinctAck.reason(check.reason))
          is ProposalCheck.Query -> {
            val entry = probe(app, check.order, check.argument.orEmpty(), source)
            InstinctExecutions.move(app, record.executionId, if (entry.kind == AckKind.OK) ExecutionState.VERIFIED else ExecutionState.FAILED,
              result = entry.message, reported = entry.kind)
            if (InstinctLogSettings(app).configured()) InstinctLog.enqueue(app, listOf(entry), message)
          }
          else -> Unit
        }
        return@forEachIndexed
      }
      val index = replyIndex++
      val execution = record?.executionId
      // An answer given now, without a proposal: the row ends with it (1.20.0).
      fun answered(entry: AckEntry) {
        InstinctExecutions.move(app, execution, if (entry.kind == AckKind.OK) ExecutionState.VERIFIED else ExecutionState.FAILED,
          result = entry.message.takeIf { entry.kind == AckKind.OK }, reason = entry.message.takeIf { entry.kind != AckKind.OK },
          reported = entry.kind)
        reply(app, acks.resolve(ticket, index, entry))
      }
      when (check) {
        is ProposalCheck.Question -> Unit // Handled above, outside the acknowledgement ledger.
        is ProposalCheck.Invalid -> answered(InstinctAck.invalid(check))
        // 1.23.0: the order's row ends as «accepted»; the plan runs in its own row, its progress goes to the log.
        is PlanCheck -> {
          val admission = InstinctPlanEngine.admit(app, check, execution, source, message, fingerprint)
          answered(admission.entry)
          admission.plan?.let { InstinctPlanEngine.start(app, it) }
        }
        is ContextualCheck -> {
          val failure = when {
            source != ProposalSource.BUZON -> InstinctContextScheduler.ONLY_SIGNED
            !InstinctExecutions.begin(app, execution, null) -> NOT_RECORDED
            else -> InstinctContextScheduler.create(app, check.spec)
          }
          answered(if (failure == null) AckEntry(AckKind.OK, check.order,
            "Regla ${check.spec.id.take(InstinctTriggers.SHORT_ID_LENGTH)} guardada.", informative = true)
          else AckEntry(AckKind.ERROR, check.order, failure))
        }
        is ProposalCheck.Query -> answered(when {
          check.kind in InstinctProposalValidator.ruleQueries && source != ProposalSource.BUZON ->
            AckEntry(AckKind.ERROR, check.order, InstinctContextScheduler.ONLY_SIGNED)
          !check.kind.readOnly && !InstinctExecutions.begin(app, execution, null) ->
            AckEntry(AckKind.ERROR, check.order, NOT_RECORDED)
          check.kind == ProposalCheck.QueryKind.STATE -> stateAnswer(app, check.order)
          check.kind == ProposalCheck.QueryKind.PROBE -> probe(app, check.order, check.argument.orEmpty(), source)
          check.kind in InstinctProposalValidator.ruleQueries && !check.kind.readOnly -> {
            val changed = InstinctContextScheduler.change(app, check.argument.orEmpty(), ruleEnabled(check.kind))
            AckEntry(if (changed.ok) AckKind.OK else AckKind.ERROR, check.order, changed.detail, informative = true)
          }
          else -> AckEntry(AckKind.OK, check.order, answer(app, check.kind, check.argument,
            showQuestions = source != ProposalSource.WHATSAPP), informative = true)
        })
        is ProposalCheck.Notice -> answered(notice(app, check, source))
        // «Nunca» refuses at once, whatever the hour; Confirmar and the quiet hours are decided when it runs.
        is ProposalCheck.Valid -> if (refusedByPolicy(app, check.command)) {
          InstinctExecutions.move(app, execution, ExecutionState.CANCELLED, reason = InstinctAutonomyPolicy.REFUSED, reported = AckKind.ERROR)
          reply(app, acks.resolve(ticket, index, AckEntry(AckKind.ERROR, check.order, InstinctAutonomyPolicy.REFUSED)))
        } else if (check.deferred != null) schedule(app, check, fingerprint, ticket, index, message, source, execution) else {
          val proposal = book.offer(check.order, check.command, fingerprint, source, execution)
          if (proposal == null) {
            InstinctExecutions.move(app, execution, ExecutionState.FAILED, reason = "ya estaba esperando confirmación; no se duplica.")
            reply(app, acks.resolve(ticket, index,
              AckEntry(AckKind.ERROR, check.order, "ya había una orden igual esperando confirmación; no se duplica.")))
          } else {
            InstinctExecutions.link(proposal.id, execution)
            InstinctExecutions.move(app, execution, ExecutionState.READY, proposalId = proposal.id)
            acks.link(ticket, index, proposal.id); offered += proposal
          }
        }
      }
    }
    val notUnderstood = checks.any { it is ProposalCheck.Invalid && !InstinctQuestion.isRelated(it.order) }
    if (offered.isEmpty() && !notUnderstood) return
    val run = Runnable {
      val target = listener
      if (target != null) {
        offered.forEach(target::onProposal)
        if (notUnderstood) target.onNotUnderstood()
      } else {
        offered.forEach { runInBackground(app, it) }
        if (notUnderstood) notifyNotUnderstood(app)
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
  }

  /**
   * Replies to Instinct: one per accepted message, through WhatsApp's «Responder» action and/or as events in the
   * log, as «Canal de estados» says. In memory only; cleared when «Recibir órdenes» is turned off.
   */
  val acks = InstinctAckLedger<InstinctReplyHandle>({ clock() }, { replyMode() }, channel = { ackChannel() })
  @Volatile private var replyModeSource: () -> InstinctReplyMode = { InstinctReplyMode.NEVER }
  @Volatile private var channelSource: () -> InstinctAckChannel = { InstinctAckChannel.WHATSAPP }
  private fun replyMode(): InstinctReplyMode = replyModeSource()
  private fun ackChannel(): InstinctAckChannel = channelSource()

  /** The ledger reads the settings through the application context; any entry point binds it. */
  internal fun bind(context: Context) {
    val settings = InstinctProposalSettings(context.applicationContext)
    replyModeSource = { settings.replyMode() }
    channelSource = { settings.channel() }
  }
  /** Sends one reply; a seam for tests. */
  internal var replySender: (Context, InstinctReplyHandle, String) -> Boolean = { context, handle, text -> handle.send(context, text) }
  /** Before its handle lapses, a waiting order is expired here so its «vencida» line can still go out. */
  private const val EXPIRY_MARGIN_MILLIS = 2_000L

  /**
   * Opens the reply of an accepted message ([message] is its short hash, for the log). The handle is read only when
   * replies are on and the channel includes the chat: with «Bitácora» WhatsApp's «Responder» is never touched.
   */
  internal fun openReply(context: Context, orders: Int, handle: () -> InstinctReplyHandle?, message: String = "",
    logOnly: Boolean = false): InstinctAckLedger.Ticket? {
    val app = context.applicationContext
    val settings = InstinctProposalSettings(app)
    bind(app)
    if (orders == 0 || settings.replyMode() == InstinctReplyMode.NEVER) return null
    // From the mailbox: to the log only, whatever the channel; WhatsApp's «Responder» is never touched.
    if (logOnly) return acks.open(null, orders, message, logOnly = true)
    val chat = settings.channel().usesChat
    val ticket = acks.open(if (chat) runCatching(handle).getOrNull() else null, orders, message) ?: return null
    if (chat) main.postDelayed({ sweepReplies(app) }, ProposalBook.TTL_MILLIS - EXPIRY_MARGIN_MILLIS)
    return ticket
  }

  /** Tracks a deferred order for the log when this process no longer remembers its message (after a restart). */
  internal fun adopt(context: Context, key: Long, message: String, entry: AckEntry, logOnly: Boolean = false): Boolean {
    bind(context)
    return acks.adopt(key, message, entry, logOnly)
  }

  /**
   * Sends [outgoing], if any: its events to the log's outbox (never blocking, never instead of anything), its text
   * through WhatsApp. Without a «Responder» action or when WhatsApp cancels it, the text is dropped and diagnosed.
   */
  internal fun reply(context: Context, outgoing: InstinctAckLedger.Outgoing<InstinctReplyHandle>?) {
    outgoing ?: return
    if (outgoing.events.isNotEmpty()) InstinctLog.enqueue(context, outgoing.events, outgoing.message)
    if (outgoing.text == null) return
    val handle = outgoing.handle ?: run {
      InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REPLY_FAILED, "WhatsApp no ofreció Responder."); return
    }
    val sent = runCatching { replySender(context.applicationContext, handle, outgoing.text) }.getOrDefault(false)
    if (sent) InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REPLIED)
    else InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REPLY_FAILED, "WhatsApp no aceptó la respuesta.")
  }

  /** The outcome of [proposal] for Instinct. Only the first outcome of each line and one follow-up are ever sent. */
  fun report(context: Context, proposalId: Long, entry: AckEntry) {
    bind(context)
    // The journal follows every acknowledgement, unless it already holds something final (1.20.0).
    InstinctExecutions.settle(context.applicationContext, proposalId, entry)
    // An order of Instinct ran (or may have): a fresh state follows in the log, so Instinct sees the effect.
    if (entry.kind == AckKind.OK || entry.kind == AckKind.UNCERTAIN) InstinctStatePublisher.afterOrder(context)
    reply(context, acks.report(proposalId, entry))
    // In the log a waiting order is closed when it lapses, whatever the chat's handle: check it then.
    if (entry.kind == AckKind.WAITING && acks.logs(proposalId) && replyMode() != InstinctReplyMode.NEVER) {
      val app = context.applicationContext
      main.postDelayed({ sweepLog(app) }, ProposalBook.TTL_MILLIS - EXPIRY_MARGIN_MILLIS)
    }
  }

  /**
   * The result of [proposal] run by the panel or the notification: checked against the phone when it can be (without
   * a reading from before: «the requested state holds», never «this order produced it»), then reported (1.20.0).
   */
  fun report(context: Context, proposal: ProposalBook.Proposal, result: PhoneActionResult) {
    val execution = proposal.executionId
    if (execution == null || InstinctAck.needsUser(result) ||
      (result.outcome != PhoneActionOutcome.COMPLETED && result.outcome != PhoneActionOutcome.HANDOFF)) {
      report(context, proposal.id, InstinctAck.entry(proposal.order, result)); return
    }
    val observation = runCatching { outcomeVerifier(context).verify(proposal.command, null, result) }
      .getOrDefault(Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = ActionOutcomeRules.HANDED_OFF))
    val entry = ActionOutcomeRules.ack(proposal.order, result, observation)
    InstinctExecutions.move(context, execution, ActionOutcomeRules.state(observation), result = entry.message,
      reason = observation.detail.takeIf { observation.verdict != OutcomeVerdict.VERIFIED && observation.verdict != OutcomeVerdict.NOT_OBSERVABLE },
      attribution = observation.attribution.takeIf { observation.verdict == OutcomeVerdict.VERIFIED })
    report(context, proposal.id, entry)
  }

  /** Waiting orders whose reply handle is about to lapse expire now and tell Instinct. */
  internal fun sweepReplies(context: Context) {
    acks.waitingDue(EXPIRY_MARGIN_MILLIS).forEach { id ->
      val order = book.pending().firstOrNull { it.id == id }?.order ?: return@forEach
      if (book.expireNow(id)) {
        cancelNotification(context, id)
        book.find(id)?.let { InstinctTrust.record(context, it.command, TrustEvent.VENCIDA) }
        report(context, id, AckEntry(AckKind.EXPIRED, order, "venció sin que se confirmara; no se ejecutó."))
      }
    }
  }

  /** Waiting orders about to lapse, by their own age: they expire now and the log records «vencida». */
  internal fun sweepLog(context: Context) {
    val now = clock()
    acks.waiting().forEach { id ->
      val proposal = book.pending().firstOrNull { it.id == id } ?: return@forEach
      if (now < proposal.createdAt + ProposalBook.TTL_MILLIS - EXPIRY_MARGIN_MILLIS) return@forEach
      if (book.expireNow(id)) {
        cancelNotification(context, id)
        InstinctTrust.record(context, proposal.command, TrustEvent.VENCIDA)
        report(context, id, AckEntry(AckKind.EXPIRED, proposal.order, "venció sin que se confirmara; no se ejecutó."))
      }
    }
  }

  /**
   * «#insto ver estado»: publishes the state in the log now, even unchanged, and never consumes the brake. The state
   * only goes to the log: with the channel on WhatsApp the reply is an error and carries no field of the state.
   */
  internal fun stateAnswer(context: Context, order: String): AckEntry {
    val app = context.applicationContext
    val configured = InstinctLogSettings(app).configured()
    // Off the main thread (the calendar is a query); it publishes even when nothing changed.
    if (configured) InstinctStatePublisher.publishAsync(app, InstinctState.Reason.QUERY)
    return when {
      !InstinctProposalSettings(app).channel().usesLog -> AckEntry(AckKind.ERROR, order, STATE_ONLY_LOG, informative = true)
      !configured ->
        AckEntry(AckKind.ERROR, order, "la bitácora no está configurada; no se publicó el estado.", informative = true)
      else -> AckEntry(AckKind.OK, order, "estado publicado en la bitácora; leelo con action=estado.", informative = true)
    }
  }
  const val STATE_ONLY_LOG = "el estado solo se publica en la bitácora"
  const val JOURNAL_UNREADABLE = "no se pudo leer el diario de ejecución."

  private fun ruleEnabled(kind: ProposalCheck.QueryKind): Boolean? = when (kind) {
    ProposalCheck.QueryKind.RULE_ENABLE -> true
    ProposalCheck.QueryKind.RULE_DISABLE -> false
    else -> null
  }

  /** The answer to a query. Names of enabled routines only, never their steps. */
  internal fun answer(context: Context, kind: ProposalCheck.QueryKind, argument: String? = null,
    showQuestions: Boolean = true): String = when (kind) {
    ProposalCheck.QueryKind.CATALOG -> InstinctProtocolCatalog.summary(InstinctRoutineGrants(context).access().enabledNames(),
      InstinctCatalogPublisher.last(context).hash)
    ProposalCheck.QueryKind.SCHEDULED -> InstinctScheduler.describe(context)
    ProposalCheck.QueryKind.CANCEL_SCHEDULED -> when (val n = InstinctScheduler.cancelAll(context)) {
      0 -> "No había órdenes programadas."
      1 -> "Se canceló 1 orden programada."
      else -> "Se cancelaron $n órdenes programadas."
    }
    ProposalCheck.QueryKind.STATE -> stateAnswer(context, kind.phrase).message
    ProposalCheck.QueryKind.CANCEL_ONE -> InstinctScheduler.cancelShortAnswer(context, argument.orEmpty())
    ProposalCheck.QueryKind.PROBE -> probe(context, "${ProposalCheck.QueryKind.PROBE.phrase} ${argument.orEmpty()}", argument.orEmpty(),
      ProposalSource.BUZON).message
    ProposalCheck.QueryKind.RULES -> InstinctContextScheduler.describe(context)
    ProposalCheck.QueryKind.RULE_STATUS -> InstinctContextScheduler.describe(context, argument)
    ProposalCheck.QueryKind.RULE_ENABLE -> InstinctContextScheduler.change(context, argument.orEmpty(), true).detail
    ProposalCheck.QueryKind.RULE_DISABLE -> InstinctContextScheduler.change(context, argument.orEmpty(), false).detail
    ProposalCheck.QueryKind.RULE_DELETE -> InstinctContextScheduler.change(context, argument.orEmpty(), null).detail
    ProposalCheck.QueryKind.EXECUTIONS -> runCatching { InstinctExecutionQueries.list(context, showQuestions) }.getOrDefault(JOURNAL_UNREADABLE)
    ProposalCheck.QueryKind.PLANS -> runCatching { InstinctPlanEngine.describe(context) }.getOrDefault(JOURNAL_UNREADABLE)
    ProposalCheck.QueryKind.PLAN_STATUS -> runCatching { InstinctPlanEngine.status(context, argument.orEmpty()) }.getOrDefault(JOURNAL_UNREADABLE)
    ProposalCheck.QueryKind.PLAN_CANCEL -> runCatching { InstinctPlanEngine.cancel(context, argument.orEmpty()) }.getOrDefault(JOURNAL_UNREADABLE)
    ProposalCheck.QueryKind.PLAN_RESUME -> runCatching { InstinctPlanEngine.resume(context, argument.orEmpty()) }.getOrDefault(JOURNAL_UNREADABLE)
    ProposalCheck.QueryKind.EXECUTION_STATUS -> runCatching { InstinctExecutionQueries.status(context, argument.orEmpty(), showQuestions) }.getOrDefault(JOURNAL_UNREADABLE)
  }

  /** «probar»'s own brake (1.18.0): 20 every 10 minutes. Never the anti-loop one, never the trust ledger. */
  val probes = AutoRunBudget({ clock() }, max = InstinctProbe.PER_PERIOD, periodMillis = InstinctProbe.PERIOD_MILLIS)

  /**
   * «#insto probar <orden>» (1.18.0): read-only. The same validator, then the same decision as the real path
   * ([InstinctGatePreview], on a photo of the context), and what would happen NOW. Nothing runs, is saved, scheduled,
   * notified or counted: neither the anti-loop brake nor the autonomy's ledger. [line] is the whole «probar …» order.
   */
  internal fun probe(context: Context, line: String, inner: String, source: ProposalSource): AckEntry {
    val app = context.applicationContext
    if (!probes.tryAcquire()) return AckEntry(AckKind.ERROR, line, InstinctProbe.TOO_MANY, informative = true)
    val now = java.util.Calendar.getInstance().apply { timeInMillis = InstinctScheduler.wallClock() }
    val check = InstinctProposalValidator.check(inner, PhoneCommandParser(), now, InstinctRoutineGrants(app).access())
    val minute = InstinctAutonomySettings.minuteOfDay(InstinctScheduler.wallClock())
    val verdict = when (check) {
      is ProposalCheck.Question -> InstinctQuestionNotifications.probe(app, source, check.question)
      is PlanCheck -> InstinctPlanEngine.probe(app, check, source)
      is ContextualCheck -> when {
        source != ProposalSource.BUZON -> InstinctProbe.Verdict.Invalid(InstinctContextScheduler.ONLY_SIGNED)
        else -> InstinctContextScheduler.blocker(app, check.spec)?.let { InstinctProbe.Verdict.Invalid(it) }
          ?: InstinctProbe.Verdict.Scheduled("regla contextual por ${check.spec.days} días")
      }
      is ProposalCheck.Invalid -> InstinctProbe.Verdict.Invalid(InstinctAck.reason(check.reason))
      is ProposalCheck.Query -> if (check.kind == ProposalCheck.QueryKind.PROBE) InstinctProbe.Verdict.Invalid("no se puede probar una prueba")
        else if (check.kind in InstinctProposalValidator.ruleQueries && source != ProposalSource.BUZON) InstinctProbe.Verdict.Invalid(InstinctContextScheduler.ONLY_SIGNED)
        else InstinctProbe.Verdict.Alone(if (check.kind.readOnly) "consulta" else "cambio de programación")
      is ProposalCheck.Notice -> when {
        source != ProposalSource.BUZON -> InstinctProbe.Verdict.Invalid(InstinctNotice.ONLY_MAILBOX)
        InstinctAutonomySettings(app).policy().level(InstinctCategory.AVISOS) == InstinctAutonomy.NUNCA -> InstinctProbe.Verdict.Refused
        !InstinctNotices.budget.available() -> InstinctProbe.Verdict.Invalid("ya hubo ${InstinctNotice.PER_HOUR} avisos en la última hora; no se mostraría")
        else -> InstinctProbe.Verdict.Alone(InstinctCategory.AVISOS.label)
      }
      is ProposalCheck.Valid -> when {
        refusedByPolicy(app, check.command) -> InstinctProbe.Verdict.Refused
        check.deferred != null -> InstinctProbe.Verdict.Scheduled(check.deferred.label)
        else -> {
          val gate = InstinctGatePreview.preview(steps(app, check.command), isRoutine(check.command), snapshot(app, foreground = listener != null, source))
          InstinctProbe.verdict(check.command, gate, InstinctGatePreview.needs(check.command, minute))
        }
      }
    }
    return AckEntry(AckKind.OK, line, verdict.detail, informative = true)
  }

  /** A deferred order: stored for later, never run now. Instinct hears «#insto-programada» or why not. */
  private fun schedule(context: Context, check: ProposalCheck.Valid, fingerprint: String, ticket: InstinctAckLedger.Ticket?, index: Int,
    message: String, source: ProposalSource, execution: String? = null) {
    when (val outcome = InstinctScheduler.schedule(context, check, fingerprint, message, source)) {
      is InstinctScheduler.Outcome.Refused -> {
        InstinctExecutions.move(context, execution, ExecutionState.FAILED, reason = outcome.why, reported = AckKind.ERROR)
        reply(context, acks.resolve(ticket, index, AckEntry(AckKind.ERROR, check.order, outcome.why)))
      }
      is InstinctScheduler.Outcome.Scheduled -> {
        // The row waits as «programada» under its series; each firing is an execution of its own.
        InstinctExecutions.move(context, execution, ExecutionState.SCHEDULED, result = outcome.order.label, occurrence = outcome.order.series)
        notifyScheduled(context, outcome.order, "Instinct programó: ${PhoneCommandCodec.summary(check.command)}",
          "Se ejecuta ${outcome.order.label}. Podés cancelarla en Ajustes → Propuestas de Instinct.")
        if (ticket == null) return
        val key = InstinctScheduler.ackKey(outcome.order.id)
        InstinctExecutions.link(key, execution)
        acks.link(ticket, index, key)
        report(context, key, AckEntry(AckKind.SCHEDULED, check.order, outcome.order.label))
      }
    }
  }

  /**
   * «avisarme»: only from the mailbox, with its own brake (3 an hour), refused under «Nunca» for avisos and without
   * sound during the quiet hours (or under «Confirmar»: there is nothing to confirm in a notice). No «Deshacer».
   */
  internal fun notice(context: Context, check: ProposalCheck.Notice, source: ProposalSource): AckEntry {
    if (source != ProposalSource.BUZON) return AckEntry(AckKind.ERROR, check.order, InstinctNotice.ONLY_MAILBOX)
    val policy = InstinctAutonomySettings(context).policy()
    val level = policy.level(InstinctCategory.AVISOS)
    if (level == InstinctAutonomy.NUNCA) return AckEntry(AckKind.ERROR, check.order, InstinctAutonomyPolicy.REFUSED)
    val quiet = level == InstinctAutonomy.CONFIRMAR ||
      policy.quiet?.contains(InstinctAutonomySettings.minuteOfDay(InstinctScheduler.wallClock())) == true
    val failure = InstinctNotices.show(context, check.text, quiet)
    return if (failure == null) AckEntry(AckKind.OK, check.order, if (quiet) "aviso mostrado sin sonido." else "aviso mostrado.")
      else AckEntry(AckKind.ERROR, check.order, failure)
  }

  /** A scheduled order fired and may run now: to the open panel, or in the background, as any order that arrives. */
  internal fun dispatch(context: Context, proposal: ProposalBook.Proposal) {
    val target = listener
    if (target != null) target.onProposal(proposal) else runInBackground(context.applicationContext, proposal)
  }

  /** A scheduled order that fired late waits for Confirmar. */
  internal fun askLate(context: Context, proposal: ProposalBook.Proposal, reason: String) {
    if (refusedByPolicy(context, proposal.command)) { refuse(context, proposal, InstinctAutonomyPolicy.REFUSED); return }
    askConfirmation(context, proposal, reason)
    report(context, proposal.id, AckEntry(AckKind.WAITING, proposal.order, reason))
  }

  /**
   * The catalog to give Instinct, with the routines enabled now. With the log on, the copy to paste carries its
   * address and token, because Instinct needs them; the chat copy ([forChat]) never does.
   */
  fun catalog(context: Context, forChat: Boolean = false): String {
    val log = if (!InstinctProposalSettings(context).channel().usesLog) null
      else if (forChat) InstinctCatalogLog.HIDDEN
      else InstinctLogSettings(context).let { InstinctCatalogLog(it.url(), it.token()) }
    val mailbox = if (!InstinctMailboxSettings(context).enabled()) null
      else if (forChat) InstinctCatalogLog.HIDDEN
      else InstinctLogSettings(context).let { InstinctCatalogLog(it.url(), it.token()) }
    val doorbell = mailbox != null && InstinctDoorbell.active(context)
    return InstinctProtocolCatalog.text(InstinctRoutineGrants(context).access().enabledNames(), forChat, log,
      InstinctAutonomySettings(context).policy(), mailbox, doorbell = doorbell,
      doorbellDirect = doorbell && InstinctDoorbell.settings(context).withOrder())
  }

  /**
   * Sends the catalog to Instinct through the newest valid «Responder» action. False when replies are off, there is
   * no valid action or WhatsApp refused it. The chat copy marks every example, so it never reads as orders.
   */
  fun sendCatalog(context: Context): Boolean {
    val handle = acks.latestHandle() ?: return false
    val secrets = InstinctLogSettings(context).let { listOfNotNull(it.url(), it.token()) } +
      listOfNotNull(InstinctMailboxSettings(context).key())
    // Through WhatsApp: never the log's address, its token nor the mailbox key.
    val text = catalog(context, forChat = true).takeIf { InstinctAck.safe(it) && secrets.none(it::contains) } ?: return false
    val sent = runCatching { replySender(context.applicationContext, handle, text) }.getOrDefault(false)
    InstinctProposalIntake.diagnose(if (sent) InstinctProposalIntake.Diagnosis.REPLIED else InstinctProposalIntake.Diagnosis.REPLY_FAILED,
      if (sent) null else "WhatsApp no aceptó el mensaje.")
    return sent
  }

  /** The steps a command stands for: a routine Instinct may run expands to its steps; anything else is itself. */
  internal fun steps(context: Context, command: PhoneCommand): List<PhoneCommand>? =
    if (command is PhoneCommand.Platform && command.action == PlatformAction.ROUTINE_RUN)
      command.argument?.let { InstinctRoutineGrants(context).access().resolve(it)?.commands }
    else listOf(command)

  /** Anti-loop brake shared by both paths: 6 automatic executions per 10 minutes. */
  val budget = AutoRunBudget({ clock() })
  internal var accessibilityConnected: () -> Boolean = { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
  internal var keyguardLocked: (Context) -> Boolean = {
    it.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
  }

  /** Commands that open another app's screen. From the background Android only allows that while Insto is exempt. */
  internal fun opensActivity(command: PhoneCommand): Boolean = InstinctGatePreview.opensActivity(command)

  /**
   * Whether [command] runs now, waits for Confirmar or is refused. The blockers that already existed are decided
   * first and the autonomy policy after ([InstinctAutonomyGate.combine]): the policy can only make an order stricter.
   * [foreground] is true inside the open panel, where opening another app is allowed. The anti-loop budget is
   * acquired only for an order that runs; a routine counts as one execution. The decision itself is
   * [InstinctGatePreview.decide] on a photo of the context (1.18.0), the same one «probar» uses.
   */
  fun gate(context: Context, command: PhoneCommand, foreground: Boolean,
    source: ProposalSource = ProposalSource.WHATSAPP): InstinctAutonomyGate.Gate =
    InstinctGatePreview.decide(steps(context, command), isRoutine(command), snapshot(context, foreground, source)) { budget.tryAcquire() }

  /** The photo the gate decides on: read once, no effects. */
  internal fun snapshot(context: Context, foreground: Boolean, source: ProposalSource = ProposalSource.WHATSAPP): GateSnapshot =
    GateSnapshot(locked = !foreground && (keyguardLocked(context) ||
      context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false), accessibility = accessibilityConnected(), foreground = foreground,
      budgetAvailable = budget.available(), policy = InstinctAutonomySettings(context).policy(),
      minuteOfDay = InstinctAutonomySettings.minuteOfDay(InstinctScheduler.wallClock()), source = source,
      whatsappCap = InstinctMailboxSettings(context).whatsappCap())

  /** The autonomy decision for [command] now, at the hour it runs (a deferred order: when it fires). */
  internal fun policy(context: Context, command: PhoneCommand, steps: List<PhoneCommand>? = steps(context, command)): AutonomyDecision =
    InstinctAutonomySettings(context).policy().decide(steps ?: emptyList(), isRoutine(command),
      InstinctAutonomySettings.minuteOfDay(InstinctScheduler.wallClock()))

  /** «Nunca» for some category of [command]: refused whatever the hour. */
  internal fun refusedByPolicy(context: Context, command: PhoneCommand): Boolean =
    InstinctAutonomySettings(context).policy().refuses(steps(context, command) ?: emptyList(), isRoutine(command))

  /** The checks that existed before the autonomy policy, for one command, without the budget. */
  internal fun blocker(context: Context, command: PhoneCommand, foreground: Boolean): String? =
    InstinctGatePreview.blocker(command, snapshot(context, foreground))

  /** The panel is closed: runs the proposal now, or leaves it for Confirmar when it cannot or should not. */
  internal fun runInBackground(context: Context, proposal: ProposalBook.Proposal) {
    val execution = proposal.executionId
    when (val gate = gate(context, proposal.command, foreground = false, proposal.source)) {
      is InstinctAutonomyGate.Gate.Refuse -> {
        InstinctExecutions.move(context, execution, ExecutionState.CANCELLED, reason = gate.reason)
        refuse(context, proposal, gate.reason); return
      }
      is InstinctAutonomyGate.Gate.Wait -> {
        askConfirmation(context, proposal, gate.reason)
        report(context, proposal.id, AckEntry(AckKind.WAITING, proposal.order, gate.reason))
        return
      }
      InstinctAutonomyGate.Gate.Run -> Unit
    }
    val claim = book.claim(proposal.id, proposalFingerprint(context, proposal))
    if (claim !is ProposalBook.Claim.Granted) { reportUnavailable(context, proposal, claim); return }
    executeBackground(context, proposal, canReoffer = proposal.fingerprint != InstinctContextScheduler.FINGERPRINT)
  }

  private fun proposalFingerprint(context: Context, proposal: ProposalBook.Proposal?): String? =
    if (proposal?.fingerprint == InstinctContextScheduler.FINGERPRINT)
      InstinctContextScheduler.FINGERPRINT.takeIf { InstinctProposalSettings(context).isEnabled() }
    else fingerprint(context)

  internal fun dispatchContext(context: Context, row: ExecutionRecord, resumeReady: Boolean = false) {
    val journal = InstinctExecutions.journal(context)
    if (!InstinctProposalSettings(context).isEnabled() || !journal.contextEnabled(row)) {
      InstinctExecutionRecoveryManager.settle(context, row, ExecutionState.CANCELLED, "regla desactivada o vencida")
      return
    }
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = InstinctExecutionJournal.clock() }
    val rule = InstinctContextScheduler.rules(context).firstOrNull { it.id == row.occurrence }
    if (rule == null || !InstinctContextScheduler.hasPermissions(context, rule.spec) ||
      (row.owner != InstinctExecutionJournal.owner || resumeReady) && !InstinctContextScheduler.stillMatches(context, rule)) {
      InstinctExecutionRecoveryManager.settle(context, row, ExecutionState.CANCELLED, "las condiciones ya no se cumplen o falta un permiso")
      return
    }
    val check = InstinctProposalValidator.check(row.line, PhoneCommandParser(), calendar)
    if (check is ProposalCheck.Notice) {
      if (!InstinctExecutions.begin(context, row.executionId, null)) return
      val entry = notice(context, check, ProposalSource.BUZON)
      InstinctExecutions.move(context, row.executionId, if (entry.kind == AckKind.OK) ExecutionState.VERIFIED else ExecutionState.FAILED,
        result = entry.message)
      return
    }
    val command = (check as? ProposalCheck.Valid)?.takeIf { it.deferred == null }?.command?.takeIf(LockScreenPolicy::runsLocked)
    if (command == null) {
      InstinctExecutionRecoveryManager.settle(context, row, ExecutionState.FAILED, ContextRuleSpec.INVALID)
      return
    }
    if (!resumeReady && !InstinctExecutions.move(context, row.executionId, ExecutionState.READY, from = setOf(ExecutionState.RECEIVED))) return
    val proposal = book.offer(row.line, command, InstinctContextScheduler.FINGERPRINT, ProposalSource.BUZON, row.executionId) ?: return
    InstinctExecutions.link(proposal.id, row.executionId)
    runInBackground(context, proposal)
  }

  /** A notification option is the person's confirmation; the journal still owns dispatch and verification. */
  internal fun dispatchDecision(context: Context, row: ExecutionRecord, resumeReady: Boolean = false) {
    val now = InstinctExecutionJournal.clock()
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val command = InstinctProposalValidator.backgroundAction(row.line, PhoneCommandParser(), calendar)
    val reason = when {
      now >= (row.deadline ?: 0) -> ExecutionRecoveryPolicy.LAPSED
      !InstinctProposalSettings(context).isEnabled() ||
        InstinctAutonomySettings(context).policy().level(InstinctCategory.PREGUNTAS) == InstinctAutonomy.NUNCA ->
        InstinctAutonomyPolicy.REFUSED
      command == null -> InstinctQuestion.INVALID
      refusedByPolicy(context, command) -> InstinctAutonomyPolicy.REFUSED
      else -> blocker(context, command, foreground = false)
    }
    if (reason != null || command == null) {
      InstinctExecutions.move(context, row.executionId,
        if (reason == ExecutionRecoveryPolicy.LAPSED) ExecutionState.EXPIRED else ExecutionState.FAILED,
        reason = reason, from = setOf(ExecutionState.RECEIVED, ExecutionState.READY))
      return
    }
    if (!resumeReady && !InstinctExecutions.move(context, row.executionId, ExecutionState.READY, from = setOf(ExecutionState.RECEIVED))) return
    val fingerprint = row.parent.orEmpty()
    val proposal = book.offer(row.line, command, fingerprint, ProposalSource.BUZON, row.executionId) ?: return
    InstinctExecutions.link(proposal.id, row.executionId)
    if (book.claim(proposal.id, fingerprint) !is ProposalBook.Claim.Granted) return
    executeBackground(context, proposal, canReoffer = false, successTrust = TrustEvent.CONFIRMADA)
  }

  private fun executeBackground(context: Context, proposal: ProposalBook.Proposal, canReoffer: Boolean,
    successTrust: TrustEvent = TrustEvent.EJECUTADA) {
    val execution = proposal.executionId
    // 1.20.0: «despachando» is on disk before anything touches the phone; without it nothing runs.
    if (!InstinctExecutions.begin(context, execution, proposal.command)) {
      report(context, proposal.id, AckEntry(AckKind.ERROR, proposal.order, NOT_RECORDED)); return
    }
    if (isRoutine(proposal.command)) { runRoutineInBackground(context, proposal); return }
    val capture = InstinctUndoActions.capture(context, proposal.command)
    // Read before running, so the check after it can tell «changed by this order» from «it already was so» (1.20.0).
    val verifier = outcomeVerifier(context)
    val before = runCatching { verifier.read(proposal.command) }.getOrNull()
    // 1.20.0: an order the gate let run is authorized; the action does not ask its own «¿Confirmás?» again.
    val result = PhoneActionExecutor(context).execute(proposal.command, confirmed = InstinctConfirmations.preAuthorized(proposal.command))
    when {
      // Maybe it happened: never offered again, so it is never repeated.
      result.outcome == PhoneActionOutcome.UNCERTAIN -> {
        InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.FAILED, result.message)
        notifyResult(context, proposal, "Instinct pidió: ${proposal.summary}", "${result.message} Revisalo; no se repite.")
        report(context, proposal, result)
      }
      result.outcome == PhoneActionOutcome.REJECTED -> {
        InstinctReadiness.noteMissing(context, result)
        InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.FAILED, result.message)
        notifyResult(context, proposal, "Instinct pidió: ${proposal.summary}", "No se pudo: ${result.message}")
        report(context, proposal, result)
      }
      !canReoffer && InstinctAck.needsUser(result) -> {
        InstinctReadiness.noteMissing(context, result)
        notifyResult(context, proposal, "Insto no pudo ejecutar la acción", result.message)
        report(context, proposal.id, AckEntry(AckKind.ERROR, proposal.order, result.message))
      }
      InstinctAck.needsUser(result) -> {
        // It did not happen: it goes back for a touch, which can open the panel, ask a permission or choose. A missing
        // permission is noted for Settings (1.20.0); nothing is asked in the background.
        InstinctReadiness.noteMissing(context, result)
        val again = book.offer(proposal.order, proposal.command, proposal.fingerprint, proposal.source, execution)
        if (again != null) {
          acks.relink(proposal.id, again.id)
          InstinctExecutions.relink(proposal.id, again.id)
          askConfirmation(context, again, result.message)
          report(context, again.id, AckEntry(AckKind.WAITING, proposal.order, result.message))
        } else report(context, proposal.id, AckEntry(AckKind.ERROR, proposal.order, "ya había una orden igual esperando confirmación; no se duplica."))
      }
      else -> {
        InstinctExecutions.move(context, execution, ExecutionState.VERIFYING)
        val observation = runCatching { verifier.verify(proposal.command, before, result) }
          .getOrDefault(Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = ActionOutcomeRules.HANDED_OFF))
        val entry = ActionOutcomeRules.ack(proposal.order, result, observation)
        InstinctExecutions.move(context, execution, ActionOutcomeRules.state(observation), result = entry.message,
          reason = observation.detail.takeIf { observation.verdict == OutcomeVerdict.NOT_ACHIEVED || observation.verdict == OutcomeVerdict.INDETERMINATE },
          attribution = observation.attribution.takeIf { observation.verdict == OutcomeVerdict.VERIFIED })
        if (observation.verdict == OutcomeVerdict.NOT_ACHIEVED) {
          // Android said yes, the phone says no: reported as not done, never as done.
          InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.FAILED, entry.message)
          notifyResult(context, proposal, "Instinct pidió: ${proposal.summary}", entry.message)
          report(context, proposal.id, entry); return
        }
        InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.EXECUTED)
        InstinctTrust.record(context, proposal.command, successTrust)
        val undo = capture?.let { InstinctUndoActions.complete(context, it) }
          ?.let { undos.offer(proposal.id, proposal.order, listOf(it), category = InstinctTrust.category(proposal.command)) }
        notifyResult(context, proposal, "Instinct hizo: ${proposal.summary}", result.message, undo)
        report(context, proposal.id, entry)
      }
    }
  }

  /** Reads back what an action did (1.20.0); a seam for tests. */
  internal var outcomeVerifier: (Context) -> ActionOutcomeVerifier = { AndroidActionOutcomeVerifier(it) }

  const val NOT_RECORDED = "no se pudo registrar el comienzo de la ejecución; no se ejecutó."

  /**
   * A confirmation a dead process left pending (1.20.0): offered again under the same execution, with its notification,
   * until its original expiry ([until], wall clock). It still waits for the person, whatever the gate would say now:
   * recovering a pending confirmation never turns it into an automatic execution. False when it can no longer be
   * offered (invalid now, refused by «Nunca», the configuration changed).
   */
  internal fun restoreWaiting(context: Context, row: ExecutionRecord, until: Long): Boolean {
    val app = context.applicationContext
    val fingerprint = if (row.transport == ExecutionTransport.CONTEXT) {
      if (!InstinctProposalSettings(app).isEnabled() || !InstinctExecutions.journal(app).contextEnabled(row)) return false
      InstinctContextScheduler.FINGERPRINT
    } else awaitFingerprint(app) ?: return false
    val now = java.util.Calendar.getInstance().apply { timeInMillis = InstinctScheduler.wallClock() }
    val check = InstinctProposalValidator.check(row.line, PhoneCommandParser(), now, InstinctRoutineGrants(app).access())
    if (check !is ProposalCheck.Valid || check.deferred != null || refusedByPolicy(app, check.command)) return false
    val source = if (row.transport == ExecutionTransport.WHATSAPP) ProposalSource.WHATSAPP else ProposalSource.BUZON
    // Its notification keeps the time it had left: the book counts from the original wait, in this process's clock.
    val left = (until - InstinctScheduler.wallClock()).coerceIn(1_000L, ProposalBook.TTL_MILLIS)
    val proposal = book.offer(row.line, check.command, fingerprint, source, row.executionId, createdAt = clock() - (ProposalBook.TTL_MILLIS - left))
      ?: return false
    InstinctExecutions.link(proposal.id, row.executionId)
    InstinctExecutions.move(app, row.executionId, ExecutionState.WAITING, proposalId = proposal.id)
    bind(app)
    if (row.logMessage.isNotEmpty()) acks.adopt(proposal.id, row.logMessage, AckEntry(AckKind.WAITING, row.line, row.reason.orEmpty()), logOnly = true)
    askConfirmation(app, proposal, row.reason ?: ExecutionRecoveryPolicy.RECOVERED)
    return true
  }

  internal fun isRoutine(command: PhoneCommand) = command is PhoneCommand.Platform && command.action == PlatformAction.ROUTINE_RUN

  /**
   * A claimed routine, step by step on its own [WorkflowRunner] file (never the panel's). Re-resolved now: a routine
   * disabled or changed since the order arrived does not run. Steps never retry by themselves.
   */
  private fun runRoutineInBackground(context: Context, proposal: ProposalBook.Proposal) {
    val name = (proposal.command as PhoneCommand.Platform).argument.orEmpty()
    val routine = InstinctRoutineGrants(context).access().resolve(name)
    if (routine == null) {
      notifyResult(context, proposal, "Instinct pidió la rutina $name", "Ya no está habilitada para Instinct o cambió. No se ejecutó nada.")
      report(context, proposal.id, AckEntry(AckKind.ERROR, proposal.order, "la rutina ya no está habilitada o cambió; no se ejecutó nada."))
      return
    }
    val store = WorkflowStore(context, ROUTINE_WORKFLOW_FILE)
    // A routine interrupted by a process death is never resumed: its uncertain step is not repeated.
    val runner = WorkflowRunner(runCatching { store.read() }.getOrNull(), store::write)
    val executor = PhoneActionExecutor(context)
    val reversals = ArrayList<Reversal>()
    val irreversible = ArrayList<PhoneCommand>()
    val verifier = outcomeVerifier(context)
    var verified = 0
    val run = InstinctRoutineRun(runner, { blocker(context, it, foreground = false) }) { step ->
      val capture = InstinctUndoActions.capture(context, step)
      val before = runCatching { verifier.read(step) }.getOrNull()
      executor.execute(step, confirmed = InstinctConfirmations.preAuthorized(step)).let { result ->
        if (InstinctAck.kindOf(result) != AckKind.OK) return@let result
        // Each step is checked like a single order: not reached stops the routine, unconfirmed stops it as uncertain.
        val observation = runCatching { verifier.verify(step, before, result) }.getOrNull()
        when (observation?.verdict) {
          OutcomeVerdict.NOT_ACHIEVED -> return@let result.copy(message = "no se logró: ${observation.detail}", outcome = PhoneActionOutcome.REJECTED)
          OutcomeVerdict.INDETERMINATE -> return@let result.copy(message = "no se pudo confirmar: ${observation.detail}", outcome = PhoneActionOutcome.UNCERTAIN)
          OutcomeVerdict.VERIFIED -> verified++
          else -> Unit
        }
        val reversal = capture?.let { InstinctUndoActions.complete(context, it) }
        if (reversal != null) reversals += reversal else irreversible += step
        result
      }
    }.run(routine)
    InstinctExecutions.move(context, proposal.executionId, when {
      run.finished && verified == routine.commands.size -> ExecutionState.VERIFIED
      run.finished -> ExecutionState.HANDED_OFF
      run.uncertain -> ExecutionState.UNCERTAIN
      else -> ExecutionState.FAILED
    }, result = run.summary, reason = run.reason, attribution = ExecutionAttribution.CAUSED.takeIf { run.finished && verified == routine.commands.size })
    val undo = undos.offer(proposal.id, proposal.order, reversals, irreversible, category = InstinctCategory.RUTINAS)
    if (run.finished) InstinctTrust.record(context, InstinctCategory.RUTINAS, TrustEvent.EJECUTADA)
    InstinctProposalIntake.diagnose(if (run.finished) InstinctProposalIntake.Diagnosis.EXECUTED else InstinctProposalIntake.Diagnosis.FAILED,
      run.summary.takeUnless { run.finished })
    notifyResult(context, proposal, if (run.finished) "Instinct hizo: rutina ${routine.name}" else "Instinct pidió: rutina ${routine.name}", run.summary, undo)
    report(context, proposal.id, AckEntry(run.kind, proposal.order, run.summary))
  }

  const val ROUTINE_WORKFLOW_FILE = "instinct-routine-workflow.json"

  /** A proposal that can no longer run, said once to Instinct. */
  internal fun reportUnavailable(context: Context, proposal: ProposalBook.Proposal, claim: ProposalBook.Claim) {
    val entry = when (claim) {
      ProposalBook.Claim.Changed -> AckEntry(AckKind.ERROR, proposal.order, "cambió la configuración de Insto; no se ejecutó.")
      ProposalBook.Claim.Expired -> AckEntry(AckKind.EXPIRED, proposal.order, "venció sin que se confirmara; no se ejecutó.")
      else -> return
    }
    if (claim == ProposalBook.Claim.Expired) InstinctTrust.record(context, proposal.command, TrustEvent.VENCIDA)
    report(context, proposal.id, entry)
  }

  /** «Nunca»: the proposal is dropped without running, the person sees why and Instinct hears «#insto-error». */
  internal fun refuse(context: Context, proposal: ProposalBook.Proposal, reason: String) {
    book.discard(proposal.id)
    cancelNotification(context, proposal.id)
    InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REFUSED)
    notifyResult(context, proposal, "Instinct pidió: ${proposal.summary}",
      "No se ejecutó: elegiste que Instinct nunca haga esto. Podés cambiarlo en Ajustes → Propuestas de Instinct → Autonomía.")
    report(context, proposal.id, AckEntry(AckKind.ERROR, proposal.order, reason))
  }

  private fun askConfirmation(context: Context, proposal: ProposalBook.Proposal, reason: String) {
    InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.CONFIRM, sentence(reason))
    notify(context, proposal, sentence(reason))
  }

  /** A reason as the person reads it: capitalized and ending with a period («horas de silencio» → «Horas de silencio.»). */
  internal fun sentence(reason: String): String =
    reason.trim().replaceFirstChar { it.uppercaseChar() }.let { if (it.endsWith('.')) it else "$it." }

  /**
   * Confirmation from the floating assistant or, with its [token], from the notification: the only way a proposal is
   * executed. The fingerprint is recomputed now, so a proposal confirmed after the switch, the contact or the
   * WhatsApp flavor changed is refused and can no longer run.
   */
  fun claim(context: Context, id: Long, token: String? = null, fromNotification: Boolean = false, auto: Boolean = false): ProposalBook.Claim {
    val proposal = book.find(id)
    var claim = book.claim(id, proposalFingerprint(context, proposal), token, requireToken = fromNotification)
    if (claim is ProposalBook.Claim.Granted && claim.proposal.fingerprint == InstinctContextScheduler.FINGERPRINT &&
      refusedByPolicy(context, claim.proposal.command)) {
      report(context, claim.proposal.id, AckEntry(AckKind.ERROR, claim.proposal.order, InstinctAutonomyPolicy.REFUSED))
      claim = ProposalBook.Claim.Used
    }
    // The panel and the notification run it right after this: «despachando» first, or it does not run (1.20.0).
    if (claim is ProposalBook.Claim.Granted && !InstinctExecutions.begin(context, claim.proposal.executionId, claim.proposal.command)) {
      report(context, claim.proposal.id, AckEntry(AckKind.ERROR, claim.proposal.order, NOT_RECORDED))
      claim = ProposalBook.Claim.Used
    }
    // For the trust ledger: the open panel running it alone, or the person confirming it.
    if (claim is ProposalBook.Claim.Granted) InstinctTrust.record(context, claim.proposal.command,
      if (auto) TrustEvent.EJECUTADA else TrustEvent.CONFIRMADA)
    if (claim !is ProposalBook.Claim.Unknown || !fromNotification) cancelNotification(context, id)
    if (proposal != null && claim !is ProposalBook.Claim.Granted && claim !is ProposalBook.Claim.Unknown) reportUnavailable(context, proposal, claim)
    return claim
  }

  fun discard(context: Context, id: Long) {
    val proposal = book.pending().firstOrNull { it.id == id }
    if (book.discard(id) && proposal != null) {
      InstinctTrust.record(context, proposal.command, TrustEvent.DESCARTADA)
      report(context, id, AckEntry(AckKind.DISCARDED, proposal.order, "la persona la descartó; no se ejecutó."))
    }
    cancelNotification(context, id)
  }

  internal fun removeCancelledContextNotifications(context: Context) {
    val journal = InstinctExecutions.existingJournal(context) ?: return
    book.pending().filter { it.fingerprint == InstinctContextScheduler.FINGERPRINT }.forEach { proposal ->
      val id = proposal.executionId ?: return@forEach
      if (journal.get(id)?.state != ExecutionState.CANCELLED) return@forEach
      book.discard(proposal.id)
      InstinctExecutions.unlink(proposal.id)
      cancelNotification(context, proposal.id)
    }
  }

  fun discardAll(context: Context) {
    book.discardAll().forEach { cancelNotification(context, it) }
  }

  /** A proposal the floating assistant could not show before closing goes back to a notification while it is valid. */
  fun handBack(context: Context, proposal: ProposalBook.Proposal) {
    if (book.isPending(proposal.id)) notify(context.applicationContext, proposal)
  }

  internal fun canNotify(context: Context, channel: String = CHANNEL_ID): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!manager.areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
      if (channel == InstinctQuestionNotifications.CHANNEL) NotificationChannel(channel, "Insto · preguntas", NotificationManager.IMPORTANCE_HIGH)
      else if (channel == ACTIONS_CHANNEL_ID) NotificationChannel(ACTIONS_CHANNEL_ID, "Insto · acciones de Instinct", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Avisos de lo que Insto hizo cuando Instinct se lo pidió."
      } else if (channel == CHANNEL_ID) NotificationChannel(CHANNEL_ID, "Insto · propuestas de Instinct", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Acciones que Instinct te propone. Ninguna se ejecuta sin que la confirmes."
      } else NotificationChannel(NOTICE_CHANNEL_ID, "Insto · avisos de propuestas", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Avisos discretos cuando Instinct propone algo que Insto no entiende."
      })
    return true
  }

  internal fun builder(context: Context, channel: String = CHANNEL_ID): Notification.Builder =
    if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else @Suppress("DEPRECATION") Notification.Builder(context)

  private fun notify(context: Context, proposal: ProposalBook.Proposal, reason: String? = null) {
    if (!canNotify(context)) return
    val requestCode = proposal.id.toInt()
    val confirm = PendingIntent.getActivity(context, requestCode,
      Intent(context, com.example.assist.AssistActivity::class.java).setAction(ACTION_CONFIRM)
        .putExtra(EXTRA_PROPOSAL_ID, proposal.id).putExtra(EXTRA_PROPOSAL_TOKEN, proposal.token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val discard = PendingIntent.getBroadcast(context, requestCode,
      Intent(context, InstinctProposalReceiver::class.java).setAction(ACTION_DISCARD).putExtra(EXTRA_PROPOSAL_ID, proposal.id)
        .putExtra(EXTRA_PROPOSAL_TOKEN, proposal.token),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val remaining = (ProposalBook.TTL_MILLIS - (clock() - proposal.createdAt)).coerceAtLeast(1_000L)
    val title = "Instinct propone: ${proposal.summary}"
    val public = builder(context).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct propone una acción").setContentText("Desbloqueá el teléfono para revisarla.").build()
    val notification = builder(context).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(listOfNotNull(reason, "Confirmá para hacerlo. Vence en 10 minutos.").joinToString(" "))
      .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(reason, "Confirmá para hacerlo. Vence en 10 minutos.").joinToString(" ")))
      .setCategory(Notification.CATEGORY_RECOMMENDATION)
      .setPriority(@Suppress("DEPRECATION") Notification.PRIORITY_HIGH)
      .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
      .setOnlyAlertOnce(true).setAutoCancel(true)
      .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(remaining) }
      .addAction(Notification.Action.Builder(null, "Confirmar", confirm).build())
      .addAction(Notification.Action.Builder(null, "Descartar", discard).build())
      .build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + requestCode, notification) }
  }

  /**
   * «Instinct hizo: …» after an automatic execution, or why it could not. Its only action is «Deshacer», for a
   * reversible state ([undo]); it carries a random token and lapses with the undo after ten minutes.
   */
  private fun notifyResult(context: Context, proposal: ProposalBook.Proposal, title: String, text: String, undo: UndoBook.Entry? = null) {
    if (!canNotify(context, ACTIONS_CHANNEL_ID)) return
    val public = builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct usó Insto").build()
    val notification = builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
      .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
      .setAutoCancel(true)
      .apply {
        if (undo != null) {
          val intent = PendingIntent.getBroadcast(context, UNDO_BASE + undo.id.toInt(),
            Intent(context, InstinctProposalReceiver::class.java).setAction(ACTION_UNDO)
              .putExtra(EXTRA_UNDO_ID, undo.id).putExtra(EXTRA_UNDO_TOKEN, undo.token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
          addAction(Notification.Action.Builder(null, "Deshacer", intent).build())
          if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(ProposalBook.TTL_MILLIS)
        }
      }.build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + proposal.id.toInt(), notification) }
  }

  /** Pending «Deshacer», in memory only: ten minutes, one use, a random token. */
  val undos = UndoBook({ clock() })
  const val ACTION_UNDO = "com.example.phone.ACTION_UNDO_INSTINCT_ACTION"
  const val EXTRA_UNDO_ID = "insto.instinct.undo"
  const val EXTRA_UNDO_TOKEN = "insto.instinct.undo.token"
  private const val UNDO_BASE = 50_000

  /**
   * «Deshacer»: reverts the reversible steps in reverse order, never over a change the user made afterwards, and
   * says what could not be undone. Instinct hears «#insto-deshecho» if replies are on and the handle is valid.
   */
  fun undo(context: Context, id: Long, token: String?): String {
    val app = context.applicationContext
    val entry = when (val claim = undos.claim(id, token)) {
      is UndoBook.Claim.Granted -> claim.entry
      UndoBook.Claim.Expired -> return "Ya pasaron más de 10 minutos: no se puede deshacer."
      UndoBook.Claim.Used -> return "Ya se deshizo."
      UndoBook.Claim.Unknown -> return "No hay nada para deshacer."
    }
    val reverted = ArrayList<PhoneCommand>()
    val kept = ArrayList<PhoneCommand>()
    entry.reversals.asReversed().forEach { if (InstinctUndoActions.revert(app, it)) reverted += it.command else kept += it.command }
    val message = UndoPolicy.summary(reverted.asReversed(), kept.asReversed(), entry.irreversible)
    if (canNotify(app, ACTIONS_CHANNEL_ID)) {
      val notification = builder(app, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(if (reverted.isEmpty()) "No se deshizo nada" else "Deshecho").setContentText(message)
        .setStyle(Notification.BigTextStyle().bigText(message)).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
      runCatching { app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + entry.proposalId.toInt(), notification) }
    }
    if (reverted.isNotEmpty()) {
      entry.category?.let { InstinctTrust.record(app, it, TrustEvent.DESHECHA) }
      reply(app, acks.note(entry.proposalId, AckEntry(AckKind.UNDONE, entry.order, message)))
    }
    return message
  }

  /** About a plan (1.23.0): one notification per plan, replaced as it goes, in the actions channel. Never WhatsApp. */
  internal fun notifyPlan(context: Context, planId: String, title: String, text: String) {
    if (!canNotify(context, ACTIONS_CHANNEL_ID)) return
    val notification = builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setPublicVersion(builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Instinct usó Insto").build())
      .setContentIntent(PendingIntent.getActivity(context, (planId.hashCode() and 0xFFFF),
        Intent(context, com.example.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
      .setAutoCancel(true).build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(PLAN_BASE + (planId.hashCode() and 0xFFFF), notification) }
  }
  private const val PLAN_BASE = 80_000

  /** About a scheduled order: programmed, dropped, uncertain. Informative, in the actions channel. */
  internal fun notifyScheduled(context: Context, order: ScheduledOrder, title: String, text: String) {
    if (!canNotify(context, ACTIONS_CHANNEL_ID)) return
    val notification = builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setPublicVersion(builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Instinct usó Insto").build())
      .setAutoCancel(true).build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(SCHEDULED_BASE + (order.id.hashCode() and 0xFFFF), notification) }
  }
  private const val SCHEDULED_BASE = 60_000

  private fun notifyNotUnderstood(context: Context) {
    if (!canNotify(context, NOTICE_CHANNEL_ID)) return
    val notification = builder(context, NOTICE_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(NOT_UNDERSTOOD)
      .setContentText("No se ejecutó nada.")
      .setPriority(@Suppress("DEPRECATION") Notification.PRIORITY_LOW)
      .setOnlyAlertOnce(true).setAutoCancel(true)
      .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(ProposalBook.TTL_MILLIS) }
      .build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOT_UNDERSTOOD_ID, notification) }
  }

  const val NOT_UNDERSTOOD = "Instinct propuso algo que no entiendo"

  fun cancelNotification(context: Context, id: Long) {
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_BASE + id.toInt()) }
  }

  fun notificationId(id: Long): Int = NOTIFICATION_BASE + id.toInt()
}

/**
 * «Descartar», «Deshacer» and the trust actions («Volver a automático», «Sí», «No») from the notifications. Not
 * exported; only Insto's own PendingIntents reach it.
 */
class InstinctProposalReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == InstinctQuestionNotifications.ACTION || intent.action == InstinctQuestionNotifications.TICK ||
      intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
      intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
      val pending = goAsync()
      Thread({
        try { runCatching { InstinctQuestionNotifications.handle(context.applicationContext, intent) } }
        finally { pending.finish() }
      }, "insto-preguntas").start()
      return
    }
    if (intent.action == InstinctTrust.ACTION_RESTORE || intent.action == InstinctTrust.ACTION_RAISE_YES ||
      intent.action == InstinctTrust.ACTION_RAISE_NO) {
      InstinctTrust.answer(context, intent.action, intent.getStringExtra(InstinctTrust.EXTRA_TOKEN))
      return
    }
    if (intent.action == InstinctProposalCenter.ACTION_UNDO) {
      val id = intent.getLongExtra(InstinctProposalCenter.EXTRA_UNDO_ID, -1L)
      if (id > 0) InstinctProposalCenter.undo(context, id, intent.getStringExtra(InstinctProposalCenter.EXTRA_UNDO_TOKEN))
      return
    }
    if (intent.action != InstinctProposalCenter.ACTION_DISCARD) return
    val id = intent.getLongExtra(InstinctProposalCenter.EXTRA_PROPOSAL_ID, -1L)
    if (id > 0) InstinctProposalCenter.discard(context, id)
  }
}
