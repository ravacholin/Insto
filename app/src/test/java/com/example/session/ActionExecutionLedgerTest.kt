package com.example.session

import com.example.assist.AutoSendCoordinator
import com.example.assist.AutoSendMetrics
import com.example.assist.ShareSendAttempt
import com.example.assist.TextSendAttempt
import com.example.phone.PhoneActionOutcome
import com.example.phone.PhoneCommand
import com.example.phone.PlatformAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutionLedgerTest {
  private val message = PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, argument = "+5491100000000", content = "Llego en diez")

  @Test fun outcomesMapToWhatIsActuallyKnown() {
    assertEquals(DispatchState.CONFIRMED, DispatchState.of(PhoneActionOutcome.COMPLETED))
    // An opened app or a sent gesture is not proof that the message arrived.
    assertEquals(DispatchState.DISPATCHED_UNCERTAIN, DispatchState.of(PhoneActionOutcome.HANDOFF))
    assertEquals(DispatchState.DISPATCHED_UNCERTAIN, DispatchState.of(PhoneActionOutcome.UNCERTAIN))
    assertEquals(DispatchState.NOT_DISPATCHED, DispatchState.of(PhoneActionOutcome.NEEDS_INPUT))
    assertEquals(DispatchState.FAILED, DispatchState.of(PhoneActionOutcome.REJECTED))
  }

  @Test fun aCallbackDeliveredTwiceIsRecordedOnce() {
    val ledger = ActionExecutionLedger()
    val ticket = ledger.open(1, PhoneCommand.Stopwatch(com.example.phone.StopwatchOp.START))
    assertTrue(ledger.complete(ticket, PhoneActionOutcome.COMPLETED))
    assertFalse(ledger.complete(ticket, PhoneActionOutcome.COMPLETED))
    // A late, contradicting duplicate does not overwrite the first outcome either.
    assertFalse(ledger.complete(ticket, PhoneActionOutcome.REJECTED))
    assertEquals(DispatchState.CONFIRMED, ledger.lastState(ticket.command))
  }

  @Test fun anUncertainResultNeedsANewExplicitConfirmationBeforeRepeating() {
    val ledger = ActionExecutionLedger()
    assertFalse(ledger.needsReconfirmation(message))
    ledger.complete(ledger.open(1, message), PhoneActionOutcome.UNCERTAIN)
    assertTrue(ledger.needsReconfirmation(message))
    // The confirmed repeat is a new dispatch with its own outcome.
    ledger.complete(ledger.open(2, message), PhoneActionOutcome.COMPLETED)
    assertFalse(ledger.needsReconfirmation(message))
  }

  @Test fun aRefusedOrIncompleteActionCanBeRetried() {
    val ledger = ActionExecutionLedger()
    ledger.complete(ledger.open(1, message), PhoneActionOutcome.REJECTED)
    assertFalse(ledger.needsReconfirmation(message))
    ledger.complete(ledger.open(2, message), PhoneActionOutcome.NEEDS_INPUT)
    assertFalse(ledger.needsReconfirmation(message))
  }

  @Test fun cancellingAfterDispatchKeepsTheOutcomeAndNeverRunsAgain() {
    val sessions = SessionLifecycle()
    val ledger = ActionExecutionLedger()
    val id = sessions.begin(SessionState.INTERPRETING)
    assertTrue(sessions.transition(id, SessionState.EXECUTING))
    val ticket = ledger.open(id, message)
    ledger.complete(ticket, PhoneActionOutcome.HANDOFF)
    sessions.cancel()
    // The handoff is not undone nor assumed reverted, and the cancelled request can no longer execute.
    assertEquals(DispatchState.DISPATCHED_UNCERTAIN, ledger.lastState(message))
    assertFalse(sessions.canExecute(id))
    assertFalse(ledger.complete(ticket, PhoneActionOutcome.COMPLETED))
  }

  @Test fun whatsappWithoutConfirmationIsTouchedOnceAndReportedOnce() {
    // Text: the send button is touched at most once per request, whatever events follow.
    val text = TextSendAttempt(7)
    assertTrue(text.beginTouch(7, recipientMatches = true, textMatches = true, buttonReady = true))
    assertFalse(text.beginTouch(7, recipientMatches = true, textMatches = true, buttonReady = true))
    assertFalse(TextSendAttempt(8).beginTouch(7, recipientMatches = true, textMatches = true, buttonReady = true))
    // Share: send and its confirmation are each attempted once; no confirmation means no second send.
    val share = ShareSendAttempt(9)
    assertTrue(share.beginSend(9, recipientMatches = true, payloadMatches = true, buttonReady = true))
    assertFalse(share.beginSend(9, recipientMatches = true, payloadMatches = true, buttonReady = true))
    assertFalse(share.hasCompletedStep)
    // The outcome of the request is recorded once: a touched send without confirmation stays uncertain.
    val report = AutoSendCoordinator.Report(9, "com.whatsapp", AutoSendMetrics.Kind.TEXT)
    report.touched = true
    assertTrue(report.finish())
    assertFalse(report.finish())
    assertNull(report.failure)
  }

  @Test fun memoryIsBounded() {
    val ledger = ActionExecutionLedger()
    for (seconds in 1..100) ledger.complete(ledger.open(1, PhoneCommand.Timer(seconds)), PhoneActionOutcome.UNCERTAIN)
    assertNull(ledger.lastState(PhoneCommand.Timer(1)))
    assertTrue(ledger.needsReconfirmation(PhoneCommand.Timer(100)))
  }
}
