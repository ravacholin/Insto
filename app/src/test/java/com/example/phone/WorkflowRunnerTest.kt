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
  @Test fun uncertainResultCannotBeRetriedAsARejectedStep() {
    val r=WorkflowRunner();r.prepare(listOf(PhoneCommand.Media(MediaOp.NEXT)));r.confirm();r.next()
    r.accept(PhoneActionResult("sin confirmación",outcome=PhoneActionOutcome.UNCERTAIN))
    r.retry();assertNull(r.next())
    assertEquals(StepStatus.UNCERTAIN,r.snapshot!!.steps.single().status)
  }

  @Test fun failingProgressWritePreventsDispatch() {
    var failWrite=false
    val r=WorkflowRunner(save={if(failWrite)throw java.io.IOException("disk")})
    r.prepare(listOf(PhoneCommand.Timer(60)));r.confirm();failWrite=true
    assertThrows(java.io.IOException::class.java){r.next()}
    assertEquals(StepStatus.PENDING,r.snapshot!!.steps.single().status)
  }

  @Test fun invalidatedPreviewCannotBeConfirmed() {
    val r=WorkflowRunner();r.prepare(listOf(PhoneCommand.Timer(60)))
    r.invalidatePreview();r.confirm()
    assertNull(r.next())
    assertNull(r.snapshot)
  }

}
