package com.example.session

import com.example.phone.PhoneActionOutcome
import com.example.phone.PhoneActionResult
import com.example.phone.PhoneCommand

/** What is known about one dispatch of an action with external effects. */
enum class DispatchState {
  /** Nothing reached the system or another app: safe to try again. */
  NOT_DISPATCHED,
  /** It may have happened (handed to another app, gesture sent, state not verified): never repeated on its own. */
  DISPATCHED_UNCERTAIN,
  CONFIRMED,
  /** Verifiably refused before any effect. */
  FAILED;

  companion object {
    /**
     * A handoff (WhatsApp opened, a call placed through another app, a gesture sent) is not a confirmation that the
     * effect happened: it is uncertain. Only [PhoneActionOutcome.COMPLETED] confirms.
     */
    fun of(outcome: PhoneActionOutcome): DispatchState = when (outcome) {
      PhoneActionOutcome.COMPLETED -> CONFIRMED
      PhoneActionOutcome.HANDOFF, PhoneActionOutcome.UNCERTAIN -> DISPATCHED_UNCERTAIN
      PhoneActionOutcome.NEEDS_INPUT -> NOT_DISPATCHED
      PhoneActionOutcome.REJECTED -> FAILED
    }

    /** As [of], except that a failure whose effect could not be verified is uncertain, never [FAILED]. */
    fun of(result: PhoneActionResult): DispatchState =
      if (result.effectUnverified && result.outcome == PhoneActionOutcome.REJECTED) DISPATCHED_UNCERTAIN else of(result.outcome)
  }
}

/**
 * In-process record of action dispatches. Each dispatch gets a [Ticket]; its outcome is recorded exactly once, so a
 * callback delivered twice (accessibility, automation) is applied once. The ledger also remembers the last outcome
 * per command, so a request that ended uncertain is never re-run without an explicit, new confirmation.
 *
 * Confined to the main thread, like the ViewModel that owns it. Nothing is persisted: after a process restart, the
 * interrupted step of a workflow is already marked uncertain by WorkflowRunner.
 */
class ActionExecutionLedger {
  class Ticket internal constructor(val session: Long, val command: PhoneCommand, internal val serial: Long)

  private var serial = 0L
  private val completed = mutableSetOf<Long>()
  private data class Outcome(val dispatch: DispatchState, val uncertain: Boolean)
  private val last = LinkedHashMap<PhoneCommand, Outcome>()

  fun open(session: Long, command: PhoneCommand): Ticket = Ticket(session, command, ++serial)

  /** Records the outcome of [ticket]. False if it was already recorded: the duplicate must be ignored. */
  fun complete(ticket: Ticket, outcome: PhoneActionOutcome): Boolean = complete(ticket, Outcome(DispatchState.of(outcome), outcome == PhoneActionOutcome.UNCERTAIN))

  /** Records a full result, so an unverified effect counts as [DispatchState.DISPATCHED_UNCERTAIN]. */
  fun complete(ticket: Ticket, result: PhoneActionResult): Boolean = complete(ticket, Outcome(DispatchState.of(result), result.outcome == PhoneActionOutcome.UNCERTAIN || result.effectUnverified))

  private fun complete(ticket: Ticket, state: Outcome): Boolean {
    if (!completed.add(ticket.serial)) return false
    last.remove(ticket.command)
    last[ticket.command] = state
    // Bounded: only recent commands matter for a retry.
    while (last.size > MAX_REMEMBERED) last.remove(last.keys.first())
    return true
  }

  fun lastState(command: PhoneCommand): DispatchState? = last[command]?.dispatch

  fun lastResultWasUncertain(command: PhoneCommand): Boolean = last[command]?.uncertain == true

  /** An automatic or implicit retry of [command] would risk repeating an effect that may already have happened. */
  fun needsReconfirmation(command: PhoneCommand): Boolean = last[command] == DispatchState.DISPATCHED_UNCERTAIN

  private companion object { const val MAX_REMEMBERED = 32 }
}
