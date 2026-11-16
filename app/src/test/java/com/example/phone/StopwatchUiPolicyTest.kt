package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class StopwatchUiPolicyTest {
  private fun control(label: String, id: String = "", selected: Boolean = false) =
    StopwatchControl(label, id, selected)
  private val tab = control("Cronómetro", selected = true)

  @Test fun startsTheStopwatchAfterSelectingItsTab() {
    val policy = StopwatchUiPolicy(StopwatchOp.START)
    assertEquals(StopwatchUiStep.Select(0), policy.next(listOf(control("Cronómetro"), control("Iniciar"))))
    assertEquals(StopwatchUiStep.Click(1), policy.next(listOf(tab, control("Iniciar"))))
    assertEquals(StopwatchUiStep.Wait, policy.next(listOf(tab, control("Iniciar"))))
    assertEquals(StopwatchUiStep.Done, policy.next(listOf(tab, control("Pausar"))))
  }

  @Test fun startAndResumeDoNotPauseAnAlreadyRunningStopwatch() {
    for (operation in listOf(StopwatchOp.START, StopwatchOp.RESUME)) {
      assertEquals(StopwatchUiStep.Done, StopwatchUiPolicy(operation).next(listOf(tab, control("Pausar"))))
    }
  }

  @Test fun neverClicksAStartButtonFromAnAlarmOrTimer() {
    assertEquals(StopwatchUiStep.Wait, StopwatchUiPolicy(StopwatchOp.START).next(listOf(control("Temporizador"), control("Iniciar"))))
  }

  @Test fun pauseMustBeObservedAfterOneClick() {
    val policy = StopwatchUiPolicy(StopwatchOp.PAUSE)
    assertEquals(StopwatchUiStep.Click(1), policy.next(listOf(tab, control("Stop"))))
    assertEquals(StopwatchUiStep.Wait, policy.next(listOf(tab, control("Stop"))))
    assertEquals(StopwatchUiStep.Done, policy.next(listOf(tab, control("Resume"))))
  }

  @Test fun resetPausesBeforeResettingAndWaitsForResetToDisappear() {
    val policy = StopwatchUiPolicy(StopwatchOp.RESET)
    assertEquals(StopwatchUiStep.Click(1), policy.next(listOf(tab, control("Pausar"), control("Vuelta"))))
    assertEquals(StopwatchUiStep.Click(2), policy.next(listOf(tab, control("Continuar"), control("Restablecer"))))
    assertEquals(StopwatchUiStep.Wait, policy.next(listOf(tab, control("Continuar"), control("Restablecer"))))
    assertEquals(StopwatchUiStep.Done, policy.next(listOf(tab, control("Iniciar"))))
  }

  @Test fun acceptsClockResourceIdsAndEnglishDescriptions() {
    val policy = StopwatchUiPolicy(StopwatchOp.START)
    assertEquals(StopwatchUiStep.Click(1), policy.next(listOf(control("", "stopwatch_time_text"), control("Start stopwatch"))))
    assertEquals(StopwatchUiStep.Done, policy.next(listOf(control("", "stopwatch_time_text"), control("Pause stopwatch"))))
  }

  @Test fun ambiguousButtonsAndUnknownTranslationsDoNotAuthorizeClicks() {
    assertEquals(StopwatchUiStep.Wait, StopwatchUiPolicy(StopwatchOp.START).next(listOf(tab, control("Iniciar"), control("Iniciar"))))
    assertEquals(StopwatchUiStep.Wait, StopwatchUiPolicy(StopwatchOp.START).next(listOf(tab, control("Comenzar otra cosa"))))
  }

  @Test fun statusOnlySelectsTabAndDoesNotTouchItsControls() {
    assertEquals(StopwatchUiStep.Done, StopwatchUiPolicy(StopwatchOp.STATUS).next(listOf(tab, control("Iniciar"))))
  }
}
