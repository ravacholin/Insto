package com.example.phone

import android.content.Context

/**
 * What to do with the journal rows a process left unfinished (1.20.0). It is not another recovery system: it runs
 * inside the jobs and receivers that already exist (the doorbell's drain job, the boot receiver, opening Insto) and
 * decides from each row's state with [ExecutionRecoveryPolicy]. It never repeats an action that may have touched the
 * phone; it only resumes what provably did not, re-reads what can be re-read, and says «incierta» for the rest.
 */
object InstinctExecutionRecoveryManager {
  private val lock = Any()

  /** Soon, in the doorbell's drain job (JobScheduler): survives this process. */
  fun later(context: Context) {
    runCatching { InstinctDoorbell.schedule(context.applicationContext, InstinctDoorbell.KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS) }
  }

  /** Every row another process left unfinished, once. Off the main thread. Returns how many it settled or resumed. */
  fun recover(context: Context): Int = synchronized(lock) {
    val app = context.applicationContext
    val journal = InstinctExecutions.journal(app)
    val rows = runCatching { journal.unfinished() }.getOrNull() ?: return@synchronized 0
    val now = InstinctExecutionJournal.clock()
    var touched = 0
    val resume = ArrayList<Pair<ExecutionRecord, Boolean>>()
    for (row in rows) {
      val mine = row.owner == InstinctExecutionJournal.owner
      // This process's rows are in memory, except a received one nobody validated yet (a cold start without preferences).
      if (mine && row.state != ExecutionState.RECEIVED) continue
      val action = ExecutionRecoveryPolicy.decide(row, now)
      if (action == ExecutionRecoveryPolicy.Action.Leave) continue
      if (!mine && !journal.adopt(row)) continue
      touched++
      when (action) {
        ExecutionRecoveryPolicy.Action.Resume -> resume += row to !mine
        is ExecutionRecoveryPolicy.Action.Settle -> settle(app, row, action.state, action.reason)
        ExecutionRecoveryPolicy.Action.Leave -> Unit
      }
    }
    // Resumed by message, with the configuration in force now, through the same intake as when they arrived.
    resume.groupBy { it.first.messageId to it.second }.forEach { (key, pairs) ->
      val records = pairs.map { it.first }
      val first = records.first()
      val source = if (first.transport == ExecutionTransport.WHATSAPP) ProposalSource.WHATSAPP else ProposalSource.BUZON
      runCatching { InstinctProposalIntake.resume(app, records, first.logMessage, source, adopted = key.second) }
    }
    runCatching { journal.prune() }
    touched
  }

  /** Whether a row still waits for this process to resume it (a received order without preferences yet). */
  fun pending(context: Context): Boolean = runCatching {
    InstinctExecutions.journal(context).unfinished(listOf(ExecutionState.RECEIVED)).any {
      ExecutionRecoveryPolicy.inTime(it, InstinctExecutionJournal.clock())
    }
  }.getOrDefault(false)

  /** Ends [row] as [state] and tells the log once, unless an acknowledgement already went out for it. */
  internal fun settle(app: Context, row: ExecutionRecord, state: ExecutionState, reason: String) {
    val kind = ExecutionRecoveryPolicy.ack(state)
    val moved = InstinctExecutions.move(app, row.executionId, state, reason = reason, reported = kind)
    if (!moved || kind == null || row.reported != null) return
    if (InstinctProposalSettings(app).replyMode() == InstinctReplyMode.NEVER || row.logMessage.isEmpty()) return
    InstinctLog.enqueue(app, listOf(AckEntry(kind, row.line, "$reason ${ExecutionRecoveryPolicy.RECOVERED}")), row.logMessage)
  }
}

