package com.example.audio

/** Monotonic elapsed times, independent of Android and the audio device. */
class VoiceSessionPolicy {
  enum class State { LISTENING, RECORDING, SEND, IDLE }
  private var state = State.LISTENING
  private var firstSpeechMs: Long? = null
  var lastSpeechMs = 0L
    private set
  val trailingSilenceMs: Long get() = when (lastSpeechMs - (firstSpeechMs ?: lastSpeechMs)) {
    in 0 until 4_000L -> 900L
    in 4_000L until 12_000L -> 1_600L
    else -> 2_400L
  }

  fun update(elapsedMs: Long, speech: Boolean): State {
    if (state == State.IDLE || state == State.SEND) {
      state = State.IDLE
      return state
    }
    if (speech) {
      if (firstSpeechMs == null) firstSpeechMs = elapsedMs
      lastSpeechMs = elapsedMs
      state = State.RECORDING
    }
    if (state == State.LISTENING && elapsedMs >= INITIAL_LISTEN_MS) state = State.IDLE
    if (state == State.RECORDING && elapsedMs - lastSpeechMs >= trailingSilenceMs) {
      state = State.SEND
    }
    return state
  }

  fun sendNow(): State {
    state = when (state) {
      State.RECORDING -> State.SEND
      State.SEND -> State.IDLE
      else -> state
    }
    return state
  }

  fun cancel() { state = State.IDLE }

  companion object {
    const val INITIAL_LISTEN_MS = 4_000L
  }
}
