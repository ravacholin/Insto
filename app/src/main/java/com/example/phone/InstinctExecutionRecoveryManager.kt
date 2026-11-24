package com.example.phone

import android.content.Context
import org.json.JSONObject

/**
 * What to do with the journal rows a process left unfinished (1.20.0). It is not another recovery system: it runs
 * inside the jobs and receivers that already exist (the doorbell's drain job, the boot and update receiver, opening
 * Insto) and decides from each row's state with [ExecutionRecoveryPolicy]. It never repeats an action that may have
 * touched the phone: it resumes what provably did not, re-reads what can be re-read, offers a pending confirmation
 * again, compares scheduled rows with the scheduler and says «incierta» for the rest. Each row it ends is told to the
 * log once, unless an acknowledgement already went out for it.
 *
 * A force-stopped Insto (by the person or by Android) receives nothing until it is opened again: no push, job or
 * alarm. Recovery then happens when Insto is opened, not before. A normal process death does not stop the next push,
 * job or alarm from starting a new process, which recovers.
 */
object InstinctExecutionRecoveryManager {
  private val lock = Any()

  /** Soon, in the doorbell's drain job (JobScheduler): survives this process. */
  fun later(context: Context) {
    runCatching { InstinctDoorbell.schedule(context.applicationContext, InstinctDoorbell.KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS) }
  }

  /** Whether a row still waits for this process to resume it (a received order without preferences yet). */
  fun pending(context: Context): Boolean = runCatching {
    InstinctExecutions.journal(context).unfinished(listOf(ExecutionState.RECEIVED)).any {
      ExecutionRecoveryPolicy.inTime(it, InstinctExecutionJournal.clock())
    }
  }.getOrDefault(false)

  /** Every row left unfinished, once. Off the main thread. Returns how many it ended, resumed or restored. */
  fun recover(context: Context, localOnly: Boolean = false): Int = synchronized(lock) {
    val app = context.applicationContext
    val journal = InstinctExecutions.journal(app)
    val rows = runCatching { journal.unfinished() }.getOrNull() ?: return@synchronized 0
    val now = InstinctExecutionJournal.clock()
    var touched = 0
    val resume = ArrayList<Pair<ExecutionRecord, Boolean>>()
    val scheduledSeries by lazy { runCatching { InstinctScheduler.pending(app).map { it.series }.toSet() }.getOrNull() }
    for (row in rows) {
      // A plan's action is the plan engine's: its own row decides (1.23.0). Its questions are refreshed below.
      if (row.transport == ExecutionTransport.PLAN && row.state !in InstinctQuestion.PENDING) continue
      if (localOnly && !InstinctQuestion.isRelated(row.line) &&
        row.transport != ExecutionTransport.DECISION && row.transport != ExecutionTransport.CONTEXT) continue
      val mine = row.owner == InstinctExecutionJournal.owner
      val action = ExecutionRecoveryPolicy.decide(row, now, effect(row))
      if (action == ExecutionRecoveryPolicy.Action.RefreshQuestion) continue
      // A scheduled row is compared whoever owns it: cancelling a series does not pass through the journal.
      if (action == ExecutionRecoveryPolicy.Action.Reconcile) { if (reconcile(app, row, scheduledSeries)) touched++; continue }
      // This process's rows are in memory, except a received one nobody validated yet (a cold start without preferences).
      if (mine && row.state != ExecutionState.RECEIVED) continue
      if (action == ExecutionRecoveryPolicy.Action.Leave) continue
      if (!mine && !journal.adopt(row)) continue
      touched++
      when (action) {
        ExecutionRecoveryPolicy.Action.Resume -> if (row.transport == ExecutionTransport.DECISION) {
          runCatching { InstinctProposalCenter.dispatchDecision(app, row, resumeReady = !mine && row.state == ExecutionState.READY) }
        } else if (row.transport == ExecutionTransport.CONTEXT) {
          runCatching { InstinctProposalCenter.dispatchContext(app, row, resumeReady = !mine && row.state == ExecutionState.READY) }
        } else resume += row to !mine
        ExecutionRecoveryPolicy.Action.Verify -> verify(app, row)
        ExecutionRecoveryPolicy.Action.RestoreWaiting -> {
          val until = row.updatedAt + ExecutionJournalPolicy.WAITING_TTL_MILLIS
          if (!runCatching { InstinctProposalCenter.restoreWaiting(app, row, until) }.getOrDefault(false))
            settle(app, row, ExecutionState.EXPIRED, "ya no se puede confirmar; no se ejecutó.")
        }
        is ExecutionRecoveryPolicy.Action.Settle -> settle(app, row, action.state, action.reason)
        else -> Unit
      }
    }
    // Resumed by message, with the configuration in force now, through the same intake as when they arrived.
    resume.groupBy { it.first.messageId to it.second }.forEach { (key, pairs) ->
      val records = pairs.map { it.first }
      val first = records.first()
      val source = if (first.transport == ExecutionTransport.WHATSAPP) ProposalSource.WHATSAPP else ProposalSource.BUZON
      runCatching { InstinctProposalIntake.resume(app, records, first.logMessage, source, adopted = key.second) }
    }
    InstinctQuestionNotifications.refresh(app)
    runCatching { InstinctLog.recoverQuestionReplies(app) }
    if (!localOnly) runCatching { InstinctPlanEngine.recover(app) }
    if (!localOnly) runCatching { journal.prune() }
    touched
  }

  private fun command(row: ExecutionRecord): PhoneCommand? = row.command?.let { runCatching { PhoneCommandCodec.decode(JSONObject(it)) }.getOrNull() }

  private fun effect(row: ExecutionRecord): ActionEffect? = command(row)?.let(ActionEffect::of)

  /** Reads the state back; runs nothing. */
  private fun verify(app: Context, row: ExecutionRecord) {
    val command = command(row) ?: return settle(app, row, ExecutionState.UNCERTAIN, ExecutionRecoveryPolicy.INTERRUPTED)
    val observation = runCatching {
      InstinctProposalCenter.outcomeVerifier(app).verify(command, null, PhoneActionResult("", outcome = PhoneActionOutcome.COMPLETED))
    }.getOrNull()
    val state = ExecutionRecoveryPolicy.afterVerify(row, observation?.verdict ?: OutcomeVerdict.INDETERMINATE)
    val reason = when (state) {
      ExecutionState.VERIFIED -> ExecutionRecoveryPolicy.SATISFIED
      ExecutionState.HANDED_OFF -> ActionOutcomeRules.HANDED_OFF
      else -> ExecutionRecoveryPolicy.INTERRUPTED
    }
    settle(app, row, state, reason, ExecutionAttribution.SATISFIED.takeIf { state == ExecutionState.VERIFIED })
  }

  /** A scheduled row whose series the scheduler no longer holds ends: as its one-time firing did, or cancelled. */
  private fun reconcile(app: Context, row: ExecutionRecord, series: Set<String>?): Boolean {
    val occurrence = row.occurrence ?: return false
    if (series == null || occurrence in series) return false
    val children = runCatching { InstinctExecutions.journal(app).byOccurrence(occurrence) }.getOrNull()?.filter { it.parent == row.executionId }
      ?: return false
    // A firing still running is this process's or a dead one's: its own row decides; the definition waits.
    if (children.any { !it.state.terminal }) return false
    val fired = children.lastOrNull()?.takeIf { it.transport != ExecutionTransport.RECURRENCE }
    // The scheduler already told Instinct when it cancelled or fired: nothing more is sent from here.
    return if (fired != null) InstinctExecutions.move(app, row.executionId, fired.state, result = "se disparó como ${fired.shortId}")
      else InstinctExecutions.move(app, row.executionId, ExecutionState.CANCELLED, reason = ExecutionRecoveryPolicy.NOT_SCHEDULED)
  }

  /** Ends [row] as [state] and tells the log once, unless an acknowledgement already went out for it. */
  internal fun settle(app: Context, row: ExecutionRecord, state: ExecutionState, reason: String, attribution: ExecutionAttribution? = null) {
    if (InstinctQuestion.isRelated(row.line)) {
      InstinctQuestionNotifications.reject(app, row, reason, state)
      return
    }
    val kind = ExecutionRecoveryPolicy.ack(state)
    val ok = state == ExecutionState.VERIFIED || state == ExecutionState.HANDED_OFF
    val moved = InstinctExecutions.move(app, row.executionId, state, result = reason.takeIf { ok }, reason = reason.takeUnless { ok },
      reported = kind, attribution = attribution)
    if (row.transport == ExecutionTransport.DECISION || row.transport == ExecutionTransport.CONTEXT) return
    if (!moved || kind == null || row.reported != null) return
    if (InstinctProposalSettings(app).replyMode() == InstinctReplyMode.NEVER || row.logMessage.isEmpty()) return
    InstinctLog.enqueue(app, listOf(AckEntry(kind, row.line, "$reason ${ExecutionRecoveryPolicy.RECOVERED}")), row.logMessage)
  }
}
