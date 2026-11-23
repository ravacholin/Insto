package com.example.phone

/**
 * The execution journal's pure part (1.20.0): the lifecycle of one accepted order, its identity, where it came from
 * and what kind of effect it has. Transport ([InstinctPushInbox], the poll, WhatsApp) says «something arrived»; the
 * journal says what became of each order: accepted, waiting, scheduled, dispatched, verified, handed off, failed,
 * uncertain, cancelled or expired. Nothing here touches Android. See docs/architecture/instinct-execution-journal.md.
 */
enum class ExecutionState(val code: String) {
  /** Accepted and claimed: its line is on disk, nothing validated or run yet. */
  RECEIVED("recibida"),
  /** The validator accepted it; not offered to the gate yet. */
  VALIDATED("validada"),
  /** Waits for the person: Confirmar, the quiet hours, a choice or a permission. */
  WAITING("espera"),
  /** A question awaiting approval of its preview in Insto. */
  QUESTION_CONFIRM("por confirmar"),
  /** A question awaiting an option, possibly held by quiet hours. */
  ASKING("preguntando"),
  ANSWERED("respondida"),
  /** A deferred order or a recurrence, stored to fire later. */
  SCHEDULED("programada"),
  /** Offered to the gate in this process; nothing touched yet. */
  READY("lista"),
  /** Written before touching the phone. Found after a process death, it means «maybe it happened». */
  DISPATCHING("despachando"),
  /** The action returned; its effect is being read back. */
  VERIFYING("verificando"),
  /** The expected state was observed after it ran. */
  VERIFIED("verificada"),
  /** Handed to Android or another app (the Clock, a search, an app opened, a media session): its final effect is not observable. */
  HANDED_OFF("entregada"),
  /** It may or may not have happened. Never repeated by itself. */
  UNCERTAIN("incierta"),
  /** It did not happen: refused, invalid, or the expected state was not reached. */
  FAILED("fallida"),
  /** Discarded by the person, refused by «Nunca», cancelled or turned off before it ran. */
  CANCELLED("cancelada"),
  /** Its window lapsed before it could run. */
  EXPIRED("vencida"),
  ;

  val terminal: Boolean get() = this in TERMINAL

  companion object {
    val TERMINAL = setOf(VERIFIED, HANDED_OFF, UNCERTAIN, FAILED, CANCELLED, EXPIRED, ANSWERED)
    fun of(name: String?): ExecutionState? = entries.firstOrNull { it.name == name }
  }
}

/**
 * The ids every transport claims (push, poll, backup poll, follow-up, WhatsApp). One interface so the poll's
 * bookkeeping ([InstinctMailboxPoll.process]) works on the 1.19.0 file and on the journal alike.
 */
interface InstinctOrderClaims {
  fun contains(id: String): Boolean

  /** The ids (lowercase) this call took. Throws when the record cannot be written: then nothing runs. */
  fun claimAll(ids: Collection<String>): Set<String>

  /**
   * Takes the orders whose id nobody took and, where supported, records their lines in the same transaction. Returns
   * the ids (lowercase) this call took.
   */
  fun claimOrders(orders: List<MailboxOrder>, transport: ExecutionTransport): Set<String> = claimAll(orders.map { it.id })

  fun claim(id: String): Boolean = runCatching { id.lowercase() in claimAll(listOf(id)) }.getOrDefault(false)
}

/** Where an execution came from. The same allowlist, validator and policies apply to all of them. */
enum class ExecutionTransport(val code: String) {
  PUSH("timbre"), POLL("buzón"), WHATSAPP("whatsapp"), SCHEDULE("programada"), TRIGGER("disparador"),
  RECURRENCE("recurrente"), DECISION("decisión"), RESTORE("restauración"), LEGACY("1.19.0");

  companion object { fun of(name: String?): ExecutionTransport? = entries.firstOrNull { it.name == name } }
}

/**
 * What one action does to the phone, for recovery: only [READ_ONLY] could ever be repeated safely, and recovery never
 * repeats anything else. [IDEMPOTENT_SET] sets a state that can be read back; [NON_IDEMPOTENT] changes something each
 * time it runs (a relative volume step, a routine); [EXTERNAL_HANDOFF] hands over to another app.
 */
enum class ActionEffect {
  READ_ONLY, IDEMPOTENT_SET, NON_IDEMPOTENT, EXTERNAL_HANDOFF;

  companion object {
    fun of(command: PhoneCommand?): ActionEffect = when (command) {
      null -> READ_ONLY
      is PhoneCommand.Volume -> if (command.operation == VolumeOp.SET) IDEMPOTENT_SET else NON_IDEMPOTENT
      is PhoneCommand.Torch -> IDEMPOTENT_SET
      is PhoneCommand.TemporaryMode -> NON_IDEMPOTENT
      PhoneCommand.EndMode -> NON_IDEMPOTENT
      is PhoneCommand.Media -> EXTERNAL_HANDOFF
      is PhoneCommand.Platform -> when (command.action) {
        PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON, PlatformAction.DND_ON,
        PlatformAction.DND_OFF -> IDEMPOTENT_SET
        PlatformAction.BATTERY, PlatformAction.ROUTINE_LIST -> READ_ONLY
        PlatformAction.ROUTINE_RUN -> NON_IDEMPOTENT
        else -> EXTERNAL_HANDOFF
      }
      else -> EXTERNAL_HANDOFF
    }
  }
}

/** One row of the journal. [line] is the order as Instinct wrote it; [command] the validated command, encoded. */
data class ExecutionRecord(
  val executionId: String,
  /** The original message id: a mailbox order's UUID, or Insto's own key for a WhatsApp message or an occurrence. */
  val messageId: String,
  /** The order's index inside its message (0..2). */
  val index: Int,
  val transport: ExecutionTransport,
  val line: String,
  val receivedAt: Long,
  val updatedAt: Long,
  val state: ExecutionState,
  val command: String? = null,
  /** Insto's own result text, never data of the phone beyond it. */
  val result: String? = null,
  /** Why it is uncertain, failed, cancelled or expired. */
  val reason: String? = null,
  /** The scheduled order or recurrence this row belongs to. */
  val occurrence: String? = null,
  /** The execution that scheduled this occurrence. */
  val parent: String? = null,
  val attempts: Int = 0,
  /** The log's short message id, for acknowledgements that recovery sends. */
  val logMessage: String = "",
  /** The process that last touched it; another one's non-terminal row was left by a process that died. */
  val owner: String = "",
  /** After this (wall-clock millis) a row that has not run expires instead of running. */
  val deadline: Long? = null,
  /** The acknowledgement kind already sent for it, so recovery never sends a contradicting one. */
  val reported: String? = null,
  /** Whether a verified state was produced by this execution or was already there. */
  val attribution: ExecutionAttribution? = null,
  /** The in-memory proposal of the process that owns it. */
  val proposalId: Long? = null,
) {
  val shortId: String get() = ExecutionIds.short(executionId)
  override fun toString() = "ExecutionRecord($executionId, $state, $transport)"
}

/** Whether the observed state can be attributed to Insto. */
enum class ExecutionAttribution(val label: String) {
  /** Read before and after: this execution changed it. */
  CAUSED("cambiado por esta ejecución"),
  /** It already was in the requested state before running. */
  ALREADY("ya estaba así antes de ejecutarla"),
  /** The requested state holds now, but nothing proves this execution produced it (found after a restart). */
  SATISFIED("el estado pedido se cumple; no se puede probar que lo produjo esta ejecución"),
  ;

  companion object { fun of(name: String?): ExecutionAttribution? = entries.firstOrNull { it.name == name } }
}

object ExecutionIds {
  const val SHORT = 8
  private val SHORT_FORM = Regex("^e?([0-9a-f]{$SHORT})$")

  /**
   * Stable for one order of one message: the same message delivered twice gives the same id, two messages with the
   * same text give different ids (their message ids differ), and the three orders of one message are independent.
   */
  fun of(messageId: String, index: Int): String = "e" + sha256("insto-exec\u0000${messageId.lowercase()}\u0000$index").take(20)

  /** «e1a2b3c4d»: what «ver ejecuciones» shows and «estado de ejecución <id>» takes. */
  fun short(executionId: String): String = executionId.take(SHORT + 1)

  /** The 8 hex digits of a short id, with or without its «e»; null when it is not one. */
  fun parseShort(text: String): String? = SHORT_FORM.matchEntire(text.trim().lowercase())?.groupValues?.get(1)?.let { "e$it" }

  /** WhatsApp has no message id: Insto's own, unique per accepted notification. */
  fun whatsapp(messageHash: String, receivedAt: Long, nonce: String): String = "wa:$messageHash:$receivedAt:$nonce"

  /** A notification containing a question keeps its claim across process death and notification reposts. */
  fun whatsappQuestion(messageHash: String, sentAt: Long?): String = whatsapp(messageHash, sentAt ?: 0L, "")

  /** One occurrence of a scheduled order: its store id is unique per occurrence. */
  fun occurrence(scheduledId: String): String = "sched:$scheduledId"
}

/**
 * Which transitions the journal accepts. A terminal state is never left; [ExecutionState.DISPATCHING] is reached only
 * from a state that has not touched the phone, so an execution is dispatched at most once.
 */
object ExecutionTransitions {
  private val NOT_DISPATCHED = setOf(ExecutionState.RECEIVED, ExecutionState.VALIDATED, ExecutionState.WAITING,
    ExecutionState.SCHEDULED, ExecutionState.READY)

  fun allowed(from: ExecutionState, to: ExecutionState): Boolean = when {
    from.terminal -> false
    from == ExecutionState.QUESTION_CONFIRM -> to == ExecutionState.ASKING || to == ExecutionState.CANCELLED || to == ExecutionState.EXPIRED
    from == ExecutionState.ASKING -> to == ExecutionState.ANSWERED || to == ExecutionState.CANCELLED || to == ExecutionState.EXPIRED
    to == ExecutionState.ANSWERED -> false
    to in InstinctQuestion.PENDING -> from == ExecutionState.VALIDATED
    from == to -> to != ExecutionState.DISPATCHING
    to == ExecutionState.DISPATCHING -> from in NOT_DISPATCHED
    from == ExecutionState.DISPATCHING -> to == ExecutionState.VERIFYING || to.terminal || to == ExecutionState.WAITING
    from == ExecutionState.VERIFYING -> to.terminal
    to == ExecutionState.RECEIVED -> false
    else -> true
  }
}

/** The journal's limits. */
object ExecutionJournalPolicy {
  /** Terminal rows are kept this long for «ver ejecuciones» and «estado de ejecución», then pruned. */
  const val RETENTION_MILLIS = 7 * 24 * 60 * 60_000L
  /** Claimed ids are kept as long as 1.19.0 kept them in `instinct-mailbox-seen.json`. */
  const val CLAIM_RETENTION_MILLIS = InstinctMailboxSeen.RETENTION_MILLIS
  const val MAX_ROWS = 2_000
  /** An order accepted but not run yet may resume within this window after a process death. */
  const val RESUME_WINDOW_MILLIS = InstinctMailboxSignature.MAX_AGE_MILLIS
  /** A confirmation recovered after a restart keeps its original expiry. */
  const val WAITING_TTL_MILLIS = ProposalBook.TTL_MILLIS
  /** How many rows «ver ejecuciones» lists. */
  const val LIST = 6
}

/** «ver ejecuciones» and «estado de ejecución <id>»: one line per execution, without data of the phone. */
object ExecutionText {
  fun line(record: ExecutionRecord, now: Long, clock: (Long) -> String): String = line(record, now, true, clock)

  internal fun line(record: ExecutionRecord, now: Long, showQuestion: Boolean, clock: (Long) -> String): String {
    val age = if (now >= record.updatedAt) clock(record.updatedAt) else "?"
    val status = "${record.shortId} ${record.state.code} ($age, ${record.transport.code})"
    if (!showQuestion && (InstinctQuestion.isRelated(record.line) || record.transport == ExecutionTransport.DECISION)) return "$status · detalle en la bitácora"
    val what = InstinctAck.flat(record.line.removePrefix(InstinctProposalProtocol.PREFIX).trim()).take(60)
    val why = (record.reason ?: record.result)?.let { " · " + InstinctAck.flat(it).trimEnd('.').take(90) }.orEmpty()
    val attribution = record.attribution?.takeIf { record.state == ExecutionState.VERIFIED }?.let { " · ${it.label}" }.orEmpty()
    return "$status: $what$why$attribution"
  }

  fun list(records: List<ExecutionRecord>, now: Long, clock: (Long) -> String): String =
    if (records.isEmpty()) "No hay ejecuciones registradas en los últimos 7 días."
    else records.joinToString(" | ") { line(it, now, clock = clock) }

  fun status(record: ExecutionRecord?, shortId: String, now: Long, clock: (Long) -> String): String =
    record?.let { line(it, now, clock = clock) } ?: "No hay ninguna ejecución $shortId en los últimos 7 días."
}

/** The recovery's decisions, pure. */
object ExecutionRecoveryPolicy {
  sealed interface Action {
    /** Nothing touched the phone yet and it is still in time: validated again and continued. */
    data object Resume : Action
    /** Read the state back, never run anything: an interrupted set, or an action that returned but was not checked. */
    data object Verify : Action
    /** A confirmation still in its time: offered again, still waiting for the person. */
    data object RestoreWaiting : Action
    data object RefreshQuestion : Action
    /** A scheduled row: compared with what the scheduler still holds. */
    data object Reconcile : Action
    data class Settle(val state: ExecutionState, val reason: String) : Action
    data object Leave : Action
  }

  const val RECOVERED = "(recuperada tras reiniciarse Insto)"
  const val LAPSED = "Insto se cerró antes de ejecutarla y ya pasó su plazo; no se ejecutó."
  const val INTERRUPTED = "Insto se cerró mientras la ejecutaba; puede haberse hecho o no. No se repite: revisalo."
  const val WAIT_LAPSED = "venció sin que se confirmara; no se ejecutó."
  const val READ_INTERRUPTED = "se interrumpió una consulta; pedila de nuevo."
  const val SATISFIED = "el estado pedido se cumple; no se puede probar que lo produjo esta ejecución."
  const val NOT_SCHEDULED = "ya no está programada (se canceló o se apagó «Recibir órdenes»)."

  /** [effect] is the effect of the row's command, when it is known (it is stored when the dispatch begins). */
  fun decide(row: ExecutionRecord, now: Long, effect: ActionEffect? = null): Action = when (row.state) {
    ExecutionState.RECEIVED, ExecutionState.VALIDATED, ExecutionState.READY ->
      if (InstinctQuestion.isOrder(row.line) || inTime(row, now)) Action.Resume else Action.Settle(ExecutionState.EXPIRED, LAPSED)
    // Maybe it touched the phone: never again. A set can be read back; anything else stays uncertain.
    ExecutionState.DISPATCHING -> when (effect) {
      ActionEffect.IDEMPOTENT_SET -> Action.Verify
      ActionEffect.READ_ONLY -> Action.Settle(ExecutionState.FAILED, READ_INTERRUPTED)
      else -> Action.Settle(ExecutionState.UNCERTAIN, INTERRUPTED)
    }
    // It returned and only the check was missing: repeating a read is always safe.
    ExecutionState.VERIFYING -> Action.Verify
    ExecutionState.WAITING ->
      if (now - row.updatedAt in 0 until ExecutionJournalPolicy.WAITING_TTL_MILLIS) Action.RestoreWaiting
      else Action.Settle(ExecutionState.EXPIRED, WAIT_LAPSED)
    ExecutionState.QUESTION_CONFIRM, ExecutionState.ASKING -> Action.RefreshQuestion
    ExecutionState.SCHEDULED -> Action.Reconcile
    else -> Action.Leave
  }

  /** Within its own deadline (a signed order's window) and the resume window since it arrived. */
  fun inTime(row: ExecutionRecord, now: Long): Boolean =
    now - row.receivedAt in 0..ExecutionJournalPolicy.RESUME_WINDOW_MILLIS && (row.deadline == null || now <= row.deadline)

  /**
   * After a read-back: an interrupted dispatch whose state holds is «satisfied», never «caused»; one that does not hold
   * stays uncertain (it may have run and been changed back). An action that had returned and is not observable was
   * handed off.
   */
  fun afterVerify(row: ExecutionRecord, verdict: OutcomeVerdict): ExecutionState = when {
    verdict == OutcomeVerdict.VERIFIED -> ExecutionState.VERIFIED
    row.state == ExecutionState.VERIFYING && verdict == OutcomeVerdict.NOT_OBSERVABLE -> ExecutionState.HANDED_OFF
    else -> ExecutionState.UNCERTAIN
  }

  /** What the log hears for a row recovery ends. */
  fun ack(state: ExecutionState): AckKind? = when (state) {
    ExecutionState.EXPIRED -> AckKind.EXPIRED
    ExecutionState.UNCERTAIN -> AckKind.UNCERTAIN
    ExecutionState.FAILED -> AckKind.ERROR
    ExecutionState.CANCELLED -> AckKind.DISCARDED
    ExecutionState.VERIFIED, ExecutionState.HANDED_OFF -> AckKind.OK
    else -> null
  }
}
