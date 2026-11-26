package com.example.session

/** Lifecycle of one floating-assistant request. */
enum class SessionState {
  /** Nothing in flight: a retained request or an empty panel. */
  IDLE,
  /** Microphone or text capture. */
  CAPTURING,
  TRANSCRIBING,
  INTERPRETING,
  AWAITING_CLARIFICATION,
  AWAITING_CONFIRMATION,
  EXECUTING,
  COMPLETED,
  FAILED,
  CANCELLED;

  val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/**
 * Identity and state of the floating assistant's requests.
 *
 * Every request gets a new, strictly increasing id. A result carries the id of the request that produced it and
 * is applied only while that id is current: starting a request, cancelling, pausing, closing the panel or clearing
 * the ViewModel all move to a new id, so late answers (Gemini, Groq, accessibility callbacks) are dropped.
 *
 * Transitions are checked against [allowed]; an invalid or stale one is refused and counted, never applied.
 * Confined to the main thread, like the ViewModel state it guards.
 */
class SessionLifecycle {
  @Volatile var current: Long = 0L
    private set
  var state: SessionState = SessionState.IDLE
    private set
  /** Refused transitions (stale id or not allowed from the current state). Diagnostic only. */
  var refused: Int = 0
    private set

  fun isCurrent(id: Long): Boolean = id == current

  /** Starts a new request. The previous one, whatever its state, can no longer change anything. */
  fun begin(initial: SessionState = SessionState.CAPTURING): Long {
    require(initial == SessionState.IDLE || !initial.terminal) { "A request cannot start finished" }
    current++
    state = initial
    return current
  }

  /**
   * Cancels the current request and opens an idle one. Work that can still stop is stopped by the caller; an
   * action already dispatched is not undone and keeps its recorded outcome.
   */
  fun cancel(): Long {
    if (!state.terminal) state = SessionState.CANCELLED
    return begin(SessionState.IDLE)
  }

  /** Applies [to] to request [id]. False when [id] is stale or the transition is not allowed. */
  fun transition(id: Long, to: SessionState): Boolean {
    if (isCurrent(id) && to == state) return true
    if (!isCurrent(id) || to !in allowed.getValue(state)) {
      refused++
      return false
    }
    state = to
    return true
  }

  /** An action may start only for the current request, and never after it was cancelled or finished. */
  fun canExecute(id: Long): Boolean = isCurrent(id) && !state.terminal

  companion object {
    private val active = setOf(SessionState.AWAITING_CLARIFICATION, SessionState.AWAITING_CONFIRMATION,
      SessionState.EXECUTING, SessionState.COMPLETED, SessionState.FAILED, SessionState.CANCELLED)

    val allowed: Map<SessionState, Set<SessionState>> = mapOf(
      SessionState.IDLE to (setOf(SessionState.CAPTURING, SessionState.TRANSCRIBING, SessionState.INTERPRETING) + active),
      // A pending confirmation or choice survives focusing the composer or starting the microphone.
      SessionState.CAPTURING to (setOf(SessionState.IDLE, SessionState.TRANSCRIBING, SessionState.INTERPRETING) + active),
      SessionState.TRANSCRIBING to setOf(SessionState.IDLE, SessionState.INTERPRETING, SessionState.FAILED, SessionState.CANCELLED),
      SessionState.INTERPRETING to (setOf(SessionState.IDLE) + active),
      SessionState.AWAITING_CLARIFICATION to (setOf(SessionState.INTERPRETING) + active),
      SessionState.AWAITING_CONFIRMATION to (setOf(SessionState.INTERPRETING) + active),
      SessionState.EXECUTING to active,
      // Finished requests change only through a new request.
      SessionState.COMPLETED to emptySet(),
      SessionState.FAILED to emptySet(),
      SessionState.CANCELLED to emptySet(),
    )
  }
}
