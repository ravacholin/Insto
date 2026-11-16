package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class WorkflowRunnerTest {
  @Test fun retryNeverRepeatsCompletedOrHandedOffTimer() {
    val runner = WorkflowRunner()
    runner.prepare(listOf(PhoneCommand.Volume(VolumeOp.DOWN), PhoneCommand.Timer(60), PhoneCommand.OpenApp("Spotify")))
    runner.confirm()
    assertTrue(runner.next() is PhoneCommand.Volume)
    runner.accept(PhoneActionResult("ok"))
    assertTrue(runner.next() is PhoneCommand.Timer)
    runner.accept(PhoneActionResult("entregado", outcome = PhoneActionOutcome.HANDOFF))
    assertNull(runner.next())
    runner.resumeHandoff()
    assertTrue(runner.next() is PhoneCommand.OpenApp)
    runner.accept(PhoneActionResult("no instalada", outcome = PhoneActionOutcome.REJECTED))
    runner.retry()
    assertTrue(runner.next() is PhoneCommand.OpenApp)
  }
  @Test fun permissionsDoNotAdvanceAndCancellationStopsAllFollowingSteps() {
    val r = WorkflowRunner()
    r.prepare(listOf(PhoneCommand.Torch(true), PhoneCommand.OpenApp("Maps"))); r.confirm()
    r.next(); r.accept(PhoneActionResult("permiso", needsCamera = true, outcome = PhoneActionOutcome.NEEDS_INPUT))
    assertEquals(0, r.snapshot!!.cursor)
    r.cancel()
    assertNull(r.next())
  }
  @Test fun interruptedDispatchCannotBeRetriedBlindly() {
    var saved: WorkflowSnapshot? = null
    val r = WorkflowRunner(save = { saved = it })
    r.prepare(listOf(PhoneCommand.Timer(60))); r.confirm(); r.next()
    val recovered = WorkflowRunner(saved)
    assertEquals(StepStatus.UNCERTAIN, recovered.snapshot!!.steps.single().status)
    recovered.retry()
    assertNull(recovered.next())
  }
}
