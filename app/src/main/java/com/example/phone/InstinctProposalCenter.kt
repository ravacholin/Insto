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
 * The opt-in switch «Recibir propuestas de Instinct» and «Pedirme confirmación antes de ejecutar». Off by default,
 * stored in [Context.getNoBackupFilesDir] like Gemini's switch: never in preferences backups, configuration exports
 * or the cloud, so a restore never turns passive reading or automatic execution on.
 */
class InstinctProposalSettings(context: Context) {
  private val app = context.applicationContext
  private val enabled = File(app.noBackupFilesDir, "instinct-proposals-enabled")
  private val askFirst = File(app.noBackupFilesDir, "instinct-proposals-ask-first")
  private val replyMode = File(app.noBackupFilesDir, "instinct-proposals-reply-mode")

  /** Read on every posted notification: one small file, before anything of the notification is touched. */
  fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK && runCatching { enabled.readText() == "true" }.getOrDefault(false)

  fun setEnabled(value: Boolean) {
    if (value) enabled.writeText("true")
    else {
      if (enabled.exists()) enabled.delete()
      // Replies are cut first: discarding below must not answer Instinct either.
      InstinctProposalCenter.acks.clear()
      // Turning it off discards every pending proposal: nothing can run after this.
      InstinctProposalCenter.discardAll(app)
    }
  }

  /** With this on, nothing runs automatically: every proposal waits for Confirmar or «dale». */
  fun asksFirst(): Boolean = runCatching { askFirst.readText() == "true" }.getOrDefault(false)
  fun setAsksFirst(value: Boolean) { if (value) askFirst.writeText("true") else if (askFirst.exists()) askFirst.delete() }

  /** «Responderle a Instinct». Never by default; a restore never brings it, like the other switches. */
  fun replyMode(): InstinctReplyMode = runCatching { InstinctReplyMode.valueOf(replyMode.readText()) }.getOrDefault(InstinctReplyMode.NEVER)
  fun setReplyMode(value: InstinctReplyMode) {
    if (value == InstinctReplyMode.NEVER) {
      if (replyMode.exists()) replyMode.delete()
      InstinctProposalCenter.acks.clear()
    } else replyMode.writeText(value.name)
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
    return ProposalNotification.LatestMessage((person ?: message.getCharSequence(KEY_SENDER))?.toString())
  }

  override fun text(): String? = (latestBundle()?.getCharSequence(KEY_TEXT)
    ?: extras().getCharSequence(Notification.EXTRA_TEXT))?.toString()

  private companion object {
    // Keys of Notification.MessagingStyle.Message bundles (framework constants).
    const val KEY_TEXT = "text"
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

  /** [replyHandle] is read only for an accepted message, and only when replies are on. */
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
    if (outcome !is InstinctProposalFilter.Outcome.Accepted) {
      if (outcome.stage != InstinctProposalFilter.Stage.PACKAGE) log(counters())
      return
    }
    val parser = PhoneCommandParser()
    val routines = InstinctRoutineGrants(app).access()
    val checks = outcome.orders.map { InstinctProposalValidator.check(it, parser, routines = routines) }
    if (checks.none { it !is ProposalCheck.Invalid }) diagnose(Diagnosis.NOT_UNDERSTOOD)
    val fingerprint = InstinctProposalCenter.fingerprint(app) ?: return
    val ticket = InstinctProposalCenter.openReply(app, checks.size, replyHandle)
    InstinctProposalCenter.deliver(app, checks, fingerprint, ticket)
    log(counters() + " valid=${checks.count { it is ProposalCheck.Valid }} invalid=${checks.count { it is ProposalCheck.Invalid }}")
  }

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

  fun attach(value: Listener) { listener = value }
  fun detach(value: Listener) { if (listener === value) listener = null }

  internal fun deliver(context: Context, checks: List<ProposalCheck>, fingerprint: String, ticket: InstinctAckLedger.Ticket? = null) {
    val app = context.applicationContext
    val offered = ArrayList<ProposalBook.Proposal>()
    checks.forEachIndexed { index, check ->
      when (check) {
        is ProposalCheck.Invalid -> reply(app, acks.resolve(ticket, index, InstinctAck.invalid(check)))
        // Read-only: nothing runs on the phone; the answer only goes back through the reply, if replies are on.
        is ProposalCheck.Query -> reply(app, acks.resolve(ticket, index, AckEntry(AckKind.OK, check.order, answer(app, check.kind), informative = true)))
        is ProposalCheck.Valid -> {
          val proposal = book.offer(check.order, check.command, fingerprint)
          if (proposal == null) reply(app, acks.resolve(ticket, index,
            AckEntry(AckKind.ERROR, check.order, "ya había una orden igual esperando confirmación; no se duplica.")))
          else { acks.link(ticket, index, proposal.id); offered += proposal }
        }
      }
    }
    val notUnderstood = checks.any { it is ProposalCheck.Invalid }
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
   * Replies to Instinct: one per accepted message, through WhatsApp's «Responder» action. In memory only; cleared
   * when «Recibir órdenes» is turned off.
   */
  val acks = InstinctAckLedger<InstinctReplyHandle>({ clock() }, { replyMode() })
  @Volatile private var replyModeSource: () -> InstinctReplyMode = { InstinctReplyMode.NEVER }
  private fun replyMode(): InstinctReplyMode = replyModeSource()
  /** Sends one reply; a seam for tests. */
  internal var replySender: (Context, InstinctReplyHandle, String) -> Boolean = { context, handle, text -> handle.send(context, text) }
  /** Before its handle lapses, a waiting order is expired here so its «vencida» line can still go out. */
  private const val EXPIRY_MARGIN_MILLIS = 2_000L

  /** Opens the reply of an accepted message. The handle is read only when replies are on. */
  internal fun openReply(context: Context, orders: Int, handle: () -> InstinctReplyHandle?): InstinctAckLedger.Ticket? {
    val app = context.applicationContext
    val settings = InstinctProposalSettings(app)
    replyModeSource = { settings.replyMode() }
    if (settings.replyMode() == InstinctReplyMode.NEVER) return null
    val ticket = acks.open(runCatching(handle).getOrNull(), orders) ?: return null
    main.postDelayed({ sweepReplies(app) }, ProposalBook.TTL_MILLIS - EXPIRY_MARGIN_MILLIS)
    return ticket
  }

  /** Sends [outgoing], if any. Without a «Responder» action or when WhatsApp cancels it, it is dropped and diagnosed. */
  internal fun reply(context: Context, outgoing: InstinctAckLedger.Outgoing<InstinctReplyHandle>?) {
    outgoing ?: return
    val handle = outgoing.handle ?: run {
      InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REPLY_FAILED, "WhatsApp no ofreció Responder."); return
    }
    val sent = runCatching { replySender(context.applicationContext, handle, outgoing.text) }.getOrDefault(false)
    if (sent) InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REPLIED)
    else InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.REPLY_FAILED, "WhatsApp no aceptó la respuesta.")
  }

  /** The outcome of [proposal] for Instinct. Only the first outcome of each line and one follow-up are ever sent. */
  fun report(context: Context, proposalId: Long, entry: AckEntry) = reply(context, acks.report(proposalId, entry))

  fun report(context: Context, proposal: ProposalBook.Proposal, result: PhoneActionResult) =
    report(context, proposal.id, InstinctAck.entry(proposal.order, result))

  /** Waiting orders whose reply handle is about to lapse expire now and tell Instinct. */
  internal fun sweepReplies(context: Context) {
    acks.waitingDue(EXPIRY_MARGIN_MILLIS).forEach { id ->
      val order = book.pending().firstOrNull { it.id == id }?.order ?: return@forEach
      if (book.expireNow(id)) {
        cancelNotification(context, id)
        report(context, id, AckEntry(AckKind.EXPIRED, order, "venció sin que se confirmara; no se ejecutó."))
      }
    }
  }

  /** The answer to a read-only query. Names of enabled routines only, never their steps. */
  internal fun answer(context: Context, kind: ProposalCheck.QueryKind): String = when (kind) {
    ProposalCheck.QueryKind.CATALOG -> InstinctProtocolCatalog.summary(InstinctRoutineGrants(context).access().enabledNames())
  }

  /** The catalog to give Instinct, with the routines enabled now. */
  fun catalog(context: Context, forChat: Boolean = false): String =
    InstinctProtocolCatalog.text(InstinctRoutineGrants(context).access().enabledNames(), forChat)

  /**
   * Sends the catalog to Instinct through the newest valid «Responder» action. False when replies are off, there is
   * no valid action or WhatsApp refused it. The chat copy marks every example, so it never reads as orders.
   */
  fun sendCatalog(context: Context): Boolean {
    val handle = acks.latestHandle() ?: return false
    val text = catalog(context, forChat = true).takeIf(InstinctAck::safe) ?: return false
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
  internal fun opensActivity(command: PhoneCommand): Boolean = when (command) {
    is PhoneCommand.Volume, is PhoneCommand.Torch, is PhoneCommand.Media, is PhoneCommand.TemporaryMode,
    is PhoneCommand.Platform -> false
    else -> true
  }

  /**
   * Null: run now without asking. Otherwise the reason it waits for Confirmar. [foreground] is true inside the open
   * panel, where opening another app is allowed. Acquires the anti-loop budget only when it returns null.
   */
  fun autoRunBlocker(context: Context, command: PhoneCommand, foreground: Boolean): String? {
    // A routine is checked as a whole before its first step: one step that cannot run leaves all of it for Confirmar.
    val steps = steps(context, command) ?: return "La rutina ya no está habilitada para Instinct o cambió."
    steps.firstNotNullOfOrNull { blocker(context, it, foreground) }?.let { return it }
    // A routine counts as one execution.
    return if (!budget.tryAcquire()) "Instinct pidió muchas acciones seguidas; esta espera tu confirmación." else null
  }

  /** The checks of [autoRunBlocker] for one command, without the budget. */
  internal fun blocker(context: Context, command: PhoneCommand, foreground: Boolean): String? = when {
    InstinctProposalSettings(context).asksFirst() -> "Elegiste confirmar cada propuesta."
    !foreground && keyguardLocked(context) -> "El teléfono estaba bloqueado."
    !foreground && opensActivity(command) && !accessibilityConnected() ->
      "Para abrir otra app sin tocar nada, Android necesita el servicio de Accesibilidad de Insto activo."
    else -> null
  }

  /** The panel is closed: runs the proposal now, or leaves it for Confirmar when it cannot or should not. */
  internal fun runInBackground(context: Context, proposal: ProposalBook.Proposal) {
    val blocker = autoRunBlocker(context, proposal.command, foreground = false)
    if (blocker != null) {
      askConfirmation(context, proposal, blocker)
      report(context, proposal.id, AckEntry(AckKind.WAITING, proposal.order, blocker))
      return
    }
    val claim = book.claim(proposal.id, fingerprint(context))
    if (claim !is ProposalBook.Claim.Granted) { reportUnavailable(context, proposal, claim); return }
    if (isRoutine(proposal.command)) { runRoutineInBackground(context, proposal); return }
    val result = PhoneActionExecutor(context).execute(proposal.command)
    when {
      // Maybe it happened: never offered again, so it is never repeated.
      result.outcome == PhoneActionOutcome.UNCERTAIN -> {
        InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.FAILED, result.message)
        notifyResult(context, proposal, "Instinct pidió: ${proposal.summary}", "${result.message} Revisalo; no se repite.")
        report(context, proposal, result)
      }
      result.outcome == PhoneActionOutcome.REJECTED -> {
        InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.FAILED, result.message)
        notifyResult(context, proposal, "Instinct pidió: ${proposal.summary}", "No se pudo: ${result.message}")
        report(context, proposal, result)
      }
      InstinctAck.needsUser(result) -> {
        // It did not happen: it goes back for a touch, which can open the panel, ask a permission or choose.
        val again = book.offer(proposal.order, proposal.command, proposal.fingerprint)
        if (again != null) {
          acks.relink(proposal.id, again.id)
          askConfirmation(context, again, result.message)
          report(context, again.id, AckEntry(AckKind.WAITING, proposal.order, result.message))
        } else report(context, proposal.id, AckEntry(AckKind.ERROR, proposal.order, "ya había una orden igual esperando confirmación; no se duplica."))
      }
      else -> {
        InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.EXECUTED)
        notifyResult(context, proposal, "Instinct hizo: ${proposal.summary}", result.message)
        report(context, proposal, result)
      }
    }
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
    val run = InstinctRoutineRun(runner, { blocker(context, it, foreground = false) }, executor::execute).run(routine)
    InstinctProposalIntake.diagnose(if (run.finished) InstinctProposalIntake.Diagnosis.EXECUTED else InstinctProposalIntake.Diagnosis.FAILED,
      run.summary.takeUnless { run.finished })
    notifyResult(context, proposal, if (run.finished) "Instinct hizo: rutina ${routine.name}" else "Instinct pidió: rutina ${routine.name}", run.summary)
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
    report(context, proposal.id, entry)
  }

  private fun askConfirmation(context: Context, proposal: ProposalBook.Proposal, reason: String) {
    InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.CONFIRM, reason)
    notify(context, proposal, reason)
  }

  /**
   * Confirmation from the floating assistant or, with its [token], from the notification: the only way a proposal is
   * executed. The fingerprint is recomputed now, so a proposal confirmed after the switch, the contact or the
   * WhatsApp flavor changed is refused and can no longer run.
   */
  fun claim(context: Context, id: Long, token: String? = null, fromNotification: Boolean = false): ProposalBook.Claim {
    val proposal = book.find(id)
    val claim = book.claim(id, fingerprint(context), token, requireToken = fromNotification)
    if (claim !is ProposalBook.Claim.Unknown || !fromNotification) cancelNotification(context, id)
    if (proposal != null && claim !is ProposalBook.Claim.Granted && claim !is ProposalBook.Claim.Unknown) reportUnavailable(context, proposal, claim)
    return claim
  }

  fun discard(context: Context, id: Long) {
    val proposal = book.pending().firstOrNull { it.id == id }
    if (book.discard(id) && proposal != null)
      report(context, id, AckEntry(AckKind.DISCARDED, proposal.order, "la persona la descartó; no se ejecutó."))
    cancelNotification(context, id)
  }

  fun discardAll(context: Context) {
    book.discardAll().forEach { cancelNotification(context, it) }
  }

  /** A proposal the floating assistant could not show before closing goes back to a notification while it is valid. */
  fun handBack(context: Context, proposal: ProposalBook.Proposal) {
    if (book.isPending(proposal.id)) notify(context.applicationContext, proposal)
  }

  private fun canNotify(context: Context, channel: String = CHANNEL_ID): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!manager.areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
      if (channel == ACTIONS_CHANNEL_ID) NotificationChannel(ACTIONS_CHANNEL_ID, "Insto · acciones de Instinct", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Avisos de lo que Insto hizo cuando Instinct se lo pidió."
      } else if (channel == CHANNEL_ID) NotificationChannel(CHANNEL_ID, "Insto · propuestas de Instinct", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Acciones que Instinct te propone. Ninguna se ejecuta sin que la confirmes."
      } else NotificationChannel(NOTICE_CHANNEL_ID, "Insto · avisos de propuestas", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Avisos discretos cuando Instinct propone algo que Insto no entiende."
      })
    return true
  }

  private fun builder(context: Context, channel: String = CHANNEL_ID): Notification.Builder =
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

  /** «Instinct hizo: …» after an automatic execution, or why it could not. Informative: no action runs from it. */
  private fun notifyResult(context: Context, proposal: ProposalBook.Proposal, title: String, text: String) {
    if (!canNotify(context, ACTIONS_CHANNEL_ID)) return
    val public = builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct usó Insto").build()
    val notification = builder(context, ACTIONS_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
      .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
      .setAutoCancel(true).build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + proposal.id.toInt(), notification) }
  }

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

/** «Descartar» from the notification. Not exported; only Insto's own PendingIntent reaches it. */
class InstinctProposalReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != InstinctProposalCenter.ACTION_DISCARD) return
    val id = intent.getLongExtra(InstinctProposalCenter.EXTRA_PROPOSAL_ID, -1L)
    if (id > 0) InstinctProposalCenter.discard(context, id)
  }
}
