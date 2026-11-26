package com.example.phone

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the proposal center meets the journal (1.20.0). The in-memory keys of this process (proposal ids and the ack
 * keys of scheduled orders) map to durable execution ids; every acknowledgement that ends or parks an order moves its
 * row ([settle]). Journal failures never break an acknowledgement; only [begin] can stop an execution, and it must.
 */
object InstinctExecutions {
  private val byKey = ConcurrentHashMap<Long, String>()

  fun journal(context: Context): InstinctExecutionJournal = InstinctExecutionJournal.of(context)

  /** Background checks for question work must not create a journal or start the legacy import. */
  internal fun existingJournal(context: Context): InstinctExecutionJournal? =
    if (File(context.applicationContext.noBackupFilesDir, InstinctExecutionJournal.FILE).isFile) journal(context) else null

  internal fun unlink(key: Long) { byKey.remove(key) }

  internal fun link(key: Long, executionId: String?) { if (executionId != null) byKey[key] = executionId }
  internal fun relink(old: Long, new: Long) { byKey.remove(old)?.let { byKey[new] = it } }
  fun executionOf(key: Long): String? = byKey[key]

  /** Before touching the phone. False: the action must not run (already dispatched, terminal or the disk failed). */
  fun begin(context: Context, executionId: String?, command: PhoneCommand?): Boolean {
    if (executionId == null) return true
    return journal(context).beginDispatch(executionId, command?.let { runCatching { PhoneCommandCodec.encode(it).toString() }.getOrNull() })
  }

  /** Moves [executionId] to [state]; never throws. */
  fun move(context: Context, executionId: String?, state: ExecutionState, result: String? = null, reason: String? = null,
    attribution: ExecutionAttribution? = null, proposalId: Long? = null, occurrence: String? = null, reported: AckKind? = null,
    from: Set<ExecutionState>? = null, executionData: String? = null, command: PhoneCommand? = null): Boolean {
    if (executionId == null) return false
    val moved = runCatching {
      journal(context).transition(executionId, state, result, reason, command = command?.let { PhoneCommandCodec.encode(it).toString() },
        from = from, attribution = attribution,
        proposalId = proposalId, occurrence = occurrence, reported = reported, executionData = executionData)
    }.getOrDefault(false)
    if (moved && state.terminal) runCatching {
      closeParent(context, executionId, state)
      if (journal(context).get(executionId)?.transport in setOf(ExecutionTransport.DECISION, ExecutionTransport.CONTEXT))
        InstinctLog.questionCompleted(context)
    }
    return moved
  }

  /**
   * A one-time deferred order or trigger fired and ended: the row that scheduled it ends the same way, pointing at it.
   * A recurrence's row stays «programada»: it is the definition, each firing is its own execution.
   */
  private fun closeParent(context: Context, executionId: String, state: ExecutionState) {
    val journal = journal(context)
    val child = journal.get(executionId) ?: return
    val parent = child.parent ?: return
    if (child.transport == ExecutionTransport.RECURRENCE) return
    journal.transition(parent, state, result = "se disparó como ${child.shortId}", from = setOf(ExecutionState.SCHEDULED),
      reported = child.reported?.let { name -> AckKind.entries.firstOrNull { it.name == name } })
  }

  /**
   * The state an acknowledgement means for an order whose row is not terminal yet. A row already settled (verified,
   * handed off, uncertain) is left as it is: the acknowledgement is never allowed to contradict the journal.
   */
  fun stateOf(entry: AckEntry): ExecutionState? = when (entry.kind) {
    AckKind.OK -> if (entry.informative) ExecutionState.VERIFIED else ExecutionState.HANDED_OFF
    AckKind.WAITING -> ExecutionState.WAITING
    AckKind.ERROR -> ExecutionState.FAILED
    AckKind.UNCERTAIN -> ExecutionState.UNCERTAIN
    AckKind.DISCARDED -> ExecutionState.CANCELLED
    AckKind.EXPIRED -> ExecutionState.EXPIRED
    AckKind.SCHEDULED -> ExecutionState.SCHEDULED
    AckKind.UNDONE -> null
  }

  /** Every acknowledgement of [key] passes here: the row follows it, unless the row already said something final. */
  fun settle(context: Context, key: Long, entry: AckEntry) {
    val id = byKey[key] ?: return
    val state = stateOf(entry) ?: return
    val text = InstinctAck.flat(entry.message)
    val moved = move(context, id, state, result = text.takeIf { state != ExecutionState.FAILED && state != ExecutionState.CANCELLED },
      reason = text.takeIf { state == ExecutionState.FAILED || state == ExecutionState.CANCELLED || state == ExecutionState.EXPIRED ||
        state == ExecutionState.UNCERTAIN || state == ExecutionState.WAITING }, reported = entry.kind.takeIf { it.final })
    if (!moved && entry.kind.final) runCatching { journal(context).markReported(id, entry.kind) }
    if (state.terminal) byKey.remove(key)
  }

  /** Tests only. */
  internal fun reset() = byKey.clear()
}
