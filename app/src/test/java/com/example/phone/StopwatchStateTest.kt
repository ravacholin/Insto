package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class StopwatchStateTest {
  @Test fun elapsedTimeSurvivesClosingAndReopening() {
    val started = StopwatchState().apply(StopwatchOp.START, 100, "boot1")
    assertEquals(5000L, started.elapsed(5100, "boot1"))
    assertEquals(5000L, started.apply(StopwatchOp.START, 5100, "boot1").elapsed(5100, "boot1"))
  }
  @Test fun pauseAndResumePreserveAccumulatedTime() {
    val paused = StopwatchState().apply(StopwatchOp.START, 100, "boot1").apply(StopwatchOp.PAUSE, 1100, "boot1")
    assertEquals(1000L, paused.elapsed(9999, "boot1"))
    assertEquals(2000L, paused.apply(StopwatchOp.RESUME, 10000, "boot1").elapsed(11000, "boot1"))
  }
  @Test fun rebootNeverUsesPreviousMonotonicReference() {
    val state = StopwatchState(1000, 2000, "boot1")
    assertEquals(1000L, state.elapsed(9000, "boot2"))
    assertNull(state.reconcile(9000, "boot2").startedAt)
  }
  @Test fun resetLeavesZeroAndStopped() {
    val reset = StopwatchState(1000, 100, "boot1").apply(StopwatchOp.RESET, 500, "boot1")
    assertEquals(0L, reset.elapsed(999, "boot1"))
    assertNull(reset.startedAt)
  }
}
