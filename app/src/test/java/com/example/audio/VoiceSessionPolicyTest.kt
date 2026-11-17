package com.example.audio

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionPolicyTest {
  @Test fun `initial silence expires at four seconds without sending`() {
    val policy = VoiceSessionPolicy()
    assertEquals(VoiceSessionPolicy.State.LISTENING, policy.update(3999, false))
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.update(4000, false))
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.update(5000, true))
  }
  @Test fun `short command sends after nine hundred milliseconds once`() {
    val policy = VoiceSessionPolicy()
    policy.update(100, true)
    policy.update(1000, true)
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(1899, false))
    assertEquals(VoiceSessionPolicy.State.SEND, policy.update(1900, false))
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.update(2000, true))
  }
  @Test fun `medium speech tolerates a longer pause and resets on resumed voice`() {
    val policy = VoiceSessionPolicy()
    for (time in 100L..4100L step 20) policy.update(time, true)
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(5600, false))
    policy.update(5620, true)
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(7219, false))
    assertEquals(VoiceSessionPolicy.State.SEND, policy.update(7220, false))
  }
  @Test fun `long dictation tolerates pauses up to twenty four hundred milliseconds`() {
    val policy = VoiceSessionPolicy()
    for (time in 100L..12100L step 20) policy.update(time, true)
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(14499, false))
    assertEquals(VoiceSessionPolicy.State.SEND, policy.update(14500, false))
  }
  @Test fun `initial waiting time does not make a short command long`() {
    val policy = VoiceSessionPolicy()
    policy.update(3900, true)
    policy.update(4300, true)
    assertEquals(VoiceSessionPolicy.State.SEND, policy.update(5200, false))
  }
  @Test fun `typing or cancellation prevents all later audio sends`() {
    val policy = VoiceSessionPolicy()
    policy.update(100, true)
    policy.cancel()
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.update(10000, false))
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.sendNow())
  }
  @Test fun `manual send requires detected speech`() {
    val policy = VoiceSessionPolicy()
    assertEquals(VoiceSessionPolicy.State.LISTENING, policy.sendNow())
    policy.update(10, true)
    assertEquals(VoiceSessionPolicy.State.SEND, policy.sendNow())
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.sendNow())
  }
}
