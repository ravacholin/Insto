package com.example.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleTest {
  @Test fun aRequestFollowsTheExpectedPath() {
    val sessions = SessionLifecycle()
    val id = sessions.begin(SessionState.CAPTURING)
    for (state in listOf(SessionState.TRANSCRIBING, SessionState.INTERPRETING, SessionState.AWAITING_CONFIRMATION,
      SessionState.EXECUTING, SessionState.COMPLETED)) assertTrue(state.name, sessions.transition(id, state))
    assertEquals(SessionState.COMPLETED, sessions.state)
    assertEquals(0, sessions.refused)
  }

  @Test fun invalidTransitionsAreRefusedAndNotApplied() {
    val sessions = SessionLifecycle()
    val id = sessions.begin(SessionState.TRANSCRIBING)
    assertFalse(sessions.transition(id, SessionState.EXECUTING)) // An action never runs on a raw transcript.
    assertEquals(SessionState.TRANSCRIBING, sessions.state)
    assertTrue(sessions.transition(id, SessionState.INTERPRETING))
    assertTrue(sessions.transition(id, SessionState.FAILED))
    for (state in SessionState.entries.filter { it != SessionState.FAILED })
      assertFalse(state.name, sessions.transition(id, state)) // Finished: only a new request changes anything.
    assertEquals(SessionState.FAILED, sessions.state)
    assertTrue(sessions.refused > 0)
  }

  @Test fun everyTerminalStateIsFinalAndEveryStateIsReachable() {
    for (state in SessionState.entries.filter { it.terminal }) assertTrue(SessionLifecycle.allowed.getValue(state).isEmpty())
    val reachable = SessionLifecycle.allowed.values.flatten().toSet()
    assertTrue(SessionState.entries.filter { it != SessionState.IDLE && it != SessionState.CAPTURING }.all { it in reachable })
  }

  @Test fun aNewRequestMakesEveryEarlierAnswerStale() {
    val sessions = SessionLifecycle()
    val first = sessions.begin(SessionState.INTERPRETING)
    val second = sessions.begin(SessionState.INTERPRETING)
    assertFalse(sessions.isCurrent(first))
    assertFalse(sessions.transition(first, SessionState.AWAITING_CONFIRMATION))
    assertFalse(sessions.canExecute(first))
    assertTrue(sessions.canExecute(second))
    assertEquals(SessionState.INTERPRETING, sessions.state)
  }

  @Test fun cancellingStopsExecutionForTheCancelledRequest() {
    val sessions = SessionLifecycle()
    val id = sessions.begin(SessionState.INTERPRETING)
    assertTrue(sessions.transition(id, SessionState.AWAITING_CONFIRMATION))
    val idle = sessions.cancel()
    assertFalse(sessions.canExecute(id))
    assertFalse(sessions.transition(id, SessionState.EXECUTING))
    assertEquals(SessionState.IDLE, sessions.state)
    assertTrue(sessions.isCurrent(idle))
    assertTrue(idle > id)
  }

  @Test fun closingAndReopeningNeverRevivesAnOldRequest() {
    val sessions = SessionLifecycle()
    val beforeClose = sessions.begin(SessionState.INTERPRETING)
    sessions.cancel() // Panel closed.
    val reopened = sessions.begin(SessionState.CAPTURING) // Panel reopened, new request.
    assertFalse(sessions.transition(beforeClose, SessionState.COMPLETED))
    assertTrue(sessions.isCurrent(reopened))
    assertEquals(SessionState.CAPTURING, sessions.state)
  }

  @Test fun aRequestCannotStartFinished() {
    val sessions = SessionLifecycle()
    for (state in SessionState.entries.filter { it.terminal })
      assertTrue(runCatching { sessions.begin(state) }.isFailure)
  }
}
