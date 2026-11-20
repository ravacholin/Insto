package com.example.audio

import com.example.audio.VoiceSessionPolicy.Companion.FRAME_MS
import com.example.audio.VoiceSessionPolicy.State
import org.junit.Assert.*
import org.junit.Test

/** Simulated time, one decision per 20 ms VAD frame, as the capture feeds it. */
class VoiceSessionPolicyTest {
  /** Feeds frames in [from, to] and returns the last state. */
  private fun VoiceSessionPolicy.feed(from: Long, to: Long, speech: Boolean): State {
    var state = State.LISTENING
    var time = from
    while (time <= to) { state = update(time, speech); time += FRAME_MS }
    return state
  }

  /** Returns the time of the first SEND, or null. */
  private fun VoiceSessionPolicy.sendTime(from: Long, to: Long, speech: (Long) -> Boolean): Long? {
    var time = from
    while (time <= to) {
      if (update(time, speech(time)) == State.SEND) return time
      time += FRAME_MS
    }
    return null
  }

  @Test fun `initial silence expires at four seconds without sending`() {
    val policy = VoiceSessionPolicy()
    assertEquals(State.LISTENING, policy.feed(0, 3980, false))
    assertEquals(State.IDLE, policy.update(4000, false))
    assertEquals(State.IDLE, policy.update(5000, true))
    assertFalse(policy.hasSpeech)
  }

  @Test fun `short command sends after the short trailing silence once`() {
    val policy = VoiceSessionPolicy()
    assertEquals(State.RECORDING, policy.feed(100, 1000, true))
    assertEquals(VoiceSessionPolicy.SHORT_TRAILING_MS, policy.trailingSilenceMs)
    assertEquals(State.RECORDING, policy.feed(1020, 1000 + VoiceSessionPolicy.SHORT_TRAILING_MS - FRAME_MS, false))
    assertEquals(State.SEND, policy.update(1000 + VoiceSessionPolicy.SHORT_TRAILING_MS, false))
    assertEquals(State.IDLE, policy.update(2200, true))
  }

  @Test fun `medium speech tolerates a longer pause and adapts to the pause the speaker made`() {
    val policy = VoiceSessionPolicy()
    policy.feed(100, 4100, true)
    assertEquals(VoiceSessionPolicy.MEDIUM_TRAILING_MS, policy.trailingSilenceMs)
    assertEquals(State.RECORDING, policy.feed(4120, 5600, false))
    policy.update(5620, true)
    // The speaker resumed after a 1500 ms pause: the next one gets that much plus a margin.
    assertEquals(1500L, policy.longestPauseMs)
    assertEquals(1900L, policy.trailingSilenceMs)
    assertEquals(State.RECORDING, policy.feed(5640, 5620 + 1900 - FRAME_MS, false))
    assertEquals(State.SEND, policy.update(5620 + 1900, false))
  }

  @Test fun `long dictation tolerates pauses up to twenty four hundred milliseconds`() {
    val policy = VoiceSessionPolicy()
    policy.feed(100, 12100, true)
    assertEquals(State.RECORDING, policy.update(14499, false))
    assertEquals(State.SEND, policy.update(14500, false))
  }

  @Test fun `initial waiting time does not make a short command long`() {
    val policy = VoiceSessionPolicy()
    policy.feed(0, 3880, false)
    assertEquals(State.RECORDING, policy.feed(3900, 4300, true))
    assertEquals(4300 + VoiceSessionPolicy.SHORT_TRAILING_MS,
      policy.sendTime(4320, 9000) { false })
  }

  @Test fun `speech that starts just before the initial window ends is confirmed after it`() {
    val policy = VoiceSessionPolicy()
    policy.feed(0, 3940, false)
    // Candidate opened at 3960; confirmation needs 160 ms of voice and happens after 4000.
    assertEquals(State.LISTENING, policy.feed(3960, 4080, true))
    assertEquals(State.RECORDING, policy.update(4100, true))
  }

  @Test fun `a mid sentence pause of six hundred milliseconds does not send`() {
    val policy = VoiceSessionPolicy()
    // «Poné una alarma…» 1.2 s, VAD-measured pause up to 1040 ms, «…a las ocho» 0.8 s.
    val send = policy.sendTime(200, 8000) { t -> t in 200..1400 || t in 2460..3260 }
    // After that hesitation, the end waits the speaker's own pause plus a margin.
    assertEquals(1040L, policy.longestPauseMs)
    assertEquals(3260 + 1040 + VoiceSessionPolicy.PAUSE_MARGIN_MS, send)
  }

  @Test fun `low voice with flickering decisions is not cut`() {
    val policy = VoiceSessionPolicy()
    // Measured for low voice in noise: internal gaps up to 840 ms inside one command.
    val voiced = setOf(0L, 1L, 2L, 5L, 6L, 9L)
    val send = policy.sendTime(0, 8000) { t -> t <= 1000 && (t / FRAME_MS % 10) in voiced || t in 1860..2600 }
    assertEquals(840L, policy.longestPauseMs)
    assertEquals(2600 + 840 + VoiceSessionPolicy.PAUSE_MARGIN_MS, send)
  }

  @Test fun `brief noises never start a recording`() {
    for (burst in listOf(20L, 80L, 140L)) {
      val policy = VoiceSessionPolicy()
      policy.feed(0, 980, false)
      policy.feed(1000, 1000 + burst - FRAME_MS, true)
      assertEquals("burst $burst ms", State.LISTENING, policy.feed(1000 + burst, 3980, false))
      assertEquals(State.IDLE, policy.update(4000, false))
      assertFalse(policy.hasSpeech)
    }
    // Two knocks 200 ms apart: 160 ms of voice in total confirm. Measured: such events reach Whisper as before.
    val knocks = VoiceSessionPolicy()
    var state = State.LISTENING
    for (t in 0L..400L step FRAME_MS) state = knocks.update(t, t in 0..60 || t in 200..260)
    assertEquals(State.RECORDING, state)
  }

  @Test fun `typing or cancellation prevents all later audio sends`() {
    val policy = VoiceSessionPolicy()
    policy.feed(100, 400, true)
    policy.cancel()
    assertEquals(State.IDLE, policy.update(10000, false))
    assertEquals(State.IDLE, policy.sendNow())
  }

  @Test fun `manual send requires detected speech`() {
    val policy = VoiceSessionPolicy()
    assertEquals(State.LISTENING, policy.sendNow())
    policy.update(10, true)
    // A candidate still being confirmed counts: the capture keeps its frames.
    assertTrue(policy.hasSpeech)
    assertEquals(State.SEND, policy.sendNow())
    assertEquals(State.IDLE, policy.sendNow())
  }

  @Test fun `candidate frames and the pre-roll cover the measured onset lag`() {
    // VAD onsets lagged real speech by up to 460 ms with low voice; the pre-roll keeps 500 ms before the candidate.
    assertTrue(VoiceSessionPolicy.PREROLL_MS >= 460)
    val policy = VoiceSessionPolicy()
    policy.feed(0, 980, false)
    policy.update(1000, true)
    assertEquals(1000L, policy.candidateStartMs)
    policy.feed(1020, 1300, false)
    assertNull("discarded after ${VoiceSessionPolicy.ONSET_GAP_MS} ms without voice", policy.candidateStartMs)
  }
}
