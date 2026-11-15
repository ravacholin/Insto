package com.example.audio

/** Hardware boundary permits session behavior tests without opening a microphone. */
interface FloatingAudioCapture {
  val snapshot: FloatingVoiceCapture.Snapshot
  fun start(): Result<Unit>
  fun finish(): RecordedAudio?
  fun cancel()
}
