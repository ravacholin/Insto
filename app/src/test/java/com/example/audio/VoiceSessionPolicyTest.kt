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
  @Test fun `voice waits three seconds since last voiced frame and sends once`() {
    val policy = VoiceSessionPolicy()
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(100, true))
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(3099, false))
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(3100, true))
    assertEquals(VoiceSessionPolicy.State.RECORDING, policy.update(6099, false))
    assertEquals(VoiceSessionPolicy.State.SEND, policy.update(6100, false))
    assertEquals(VoiceSessionPolicy.State.IDLE, policy.update(6200, true))
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
