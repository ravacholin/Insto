package com.example.audio

/**
 * End-pointing over per-frame WebRTC VAD decisions, with monotonic elapsed times. Independent of Android and of
 * the audio device, so every rule is tested with simulated time.
 *
 * The capture feeds raw VAD decisions (no smoothing inside the VAD library): the hysteresis is here.
 *
 * - Onset: speech frames open a candidate. It becomes a recording only after [ONSET_VOICED_MS] of voiced frames
 *   with gaps under [ONSET_GAP_MS]; otherwise it is discarded. Clicks and single knocks do not start a recording.
 *   The capture keeps the candidate's frames plus [PREROLL_MS] before it, so first syllables are preserved.
 * - End: the trailing silence grows with the length of the utterance ([SHORT_TRAILING_MS], [MEDIUM_TRAILING_MS],
 *   [LONG_TRAILING_MS]) and with its continuity: once the speaker paused [HESITATION_MS] or more and resumed, the
 *   next pause needs that length plus [PAUSE_MARGIN_MS], up to [MAX_ADAPTIVE_MS].
 *
 * Constants were calibrated on real WebRTC VAD decisions over real Spanish speech, in quiet, low-voice and noisy
 * conditions: docs/VOICE-CAPTURE.md and VoiceCaptureTraceTest.
 */
class VoiceSessionPolicy {
  enum class State { LISTENING, RECORDING, SEND, IDLE }
  private var state = State.LISTENING

  /** Start of the unconfirmed speech candidate, or null. The capture keeps audio from [PREROLL_MS] before it. */
  var candidateStartMs: Long? = null
    private set
  private var candidateVoicedMs = 0L
  private var candidateLastMs = 0L
  private var firstSpeechMs: Long? = null
  var lastSpeechMs = 0L
    private set
  /** Longest pause inside the utterance so far: a pause the speaker resumed after. */
  var longestPauseMs = 0L
    private set

  val trailingSilenceMs: Long get() {
    val span = lastSpeechMs - (firstSpeechMs ?: lastSpeechMs)
    val base = when {
      span < MEDIUM_SPAN_MS -> SHORT_TRAILING_MS
      span < LONG_SPAN_MS -> MEDIUM_TRAILING_MS
      else -> LONG_TRAILING_MS
    }
    if (longestPauseMs < HESITATION_MS) return base
    return maxOf(base, minOf(longestPauseMs + PAUSE_MARGIN_MS, MAX_ADAPTIVE_MS))
  }

  /** Whether speech was confirmed, or a candidate is pending: manual send may keep that audio. */
  val hasSpeech: Boolean get() = firstSpeechMs != null || candidateStartMs != null

  fun update(elapsedMs: Long, speech: Boolean): State {
    when (state) {
      State.IDLE, State.SEND -> state = State.IDLE
      State.LISTENING -> listen(elapsedMs, speech)
      State.RECORDING -> {
        if (speech) {
          longestPauseMs = maxOf(longestPauseMs, elapsedMs - lastSpeechMs - FRAME_MS)
          lastSpeechMs = elapsedMs
        } else if (elapsedMs - lastSpeechMs >= trailingSilenceMs) state = State.SEND
      }
    }
    return state
  }

  private fun listen(elapsedMs: Long, speech: Boolean) {
    if (speech) {
      if (candidateStartMs == null) { candidateStartMs = elapsedMs; candidateVoicedMs = 0 }
      candidateVoicedMs += FRAME_MS
      candidateLastMs = elapsedMs
      if (candidateVoicedMs >= ONSET_VOICED_MS) {
        state = State.RECORDING
        firstSpeechMs = candidateStartMs
        lastSpeechMs = elapsedMs
      }
    } else if (candidateStartMs != null && elapsedMs - candidateLastMs >= ONSET_GAP_MS) {
      candidateStartMs = null
    }
    // A candidate in progress may finish confirming after the initial window.
    if (state == State.LISTENING && candidateStartMs == null && elapsedMs >= INITIAL_LISTEN_MS) state = State.IDLE
  }

  /** Manual send: needs detected speech, confirmed or still a candidate. */
  fun sendNow(): State {
    state = when (state) {
      State.RECORDING -> State.SEND
      State.LISTENING -> if (candidateStartMs != null) State.SEND else State.LISTENING
      State.SEND -> State.IDLE
      State.IDLE -> State.IDLE
    }
    return state
  }

  fun cancel() { state = State.IDLE }

  companion object {
    /** WebRTC VAD frame: 320 samples at 16 kHz. */
    const val FRAME_MS = 20L
    const val INITIAL_LISTEN_MS = 4_000L
    /** Voiced time that confirms an utterance: above clicks and knocks (≤ 120 ms runs), below a short word. */
    const val ONSET_VOICED_MS = 160L
    /** A candidate whose speech stops this long before confirming was noise. */
    const val ONSET_GAP_MS = 300L
    /** Audio kept before the first voiced frame: VAD onsets lag real speech by up to 460 ms when the voice is low. */
    const val PREROLL_MS = 500L
    const val MEDIUM_SPAN_MS = 4_000L
    const val LONG_SPAN_MS = 12_000L
    /** Covers natural gaps inside commands and 600 ms hesitations, measured up to 1040 ms after VAD. */
    const val SHORT_TRAILING_MS = 1_100L
    const val MEDIUM_TRAILING_MS = 1_600L
    const val LONG_TRAILING_MS = 2_400L
    const val HESITATION_MS = 600L
    const val PAUSE_MARGIN_MS = 400L
    const val MAX_ADAPTIVE_MS = 2_000L
    /** Hard limit of one recording. */
    const val MAX_RECORDING_MS = 300_000L
  }
}
