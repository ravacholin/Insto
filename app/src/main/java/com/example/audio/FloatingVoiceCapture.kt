package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.File
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.abs

/** Each session owns its device/file; cancelling never waits for codec finalization on Main. */
class FloatingVoiceCapture(private val context: Context) : FloatingAudioCapture {
  data class Snapshot(
    val state: VoiceSessionPolicy.State = VoiceSessionPolicy.State.LISTENING,
    val elapsedMs: Long = 0,
    val amplitude: Float = 0f,
    val error: String? = null,
  )
  private class Session(val input: AudioRecord) {
    var output: File? = null
    var worker: Thread? = null
    @Volatile var running = false
    @Volatile var hasSpeech = false
    @Volatile var finalized = false
    @Volatile var snapshot = Snapshot()
  }
  @Volatile private var active: Session? = null
  @Volatile private var lastSnapshot = Snapshot()
  override val snapshot: Snapshot get() = active?.snapshot ?: lastSnapshot

  @SuppressLint("MissingPermission")
  override fun start(): Result<Unit> = runCatching {
    cancel()
    lastSnapshot = Snapshot()
    val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0) { "El micrófono no admite audio de 16 kHz" }
    val device = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
      AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
    val session = Session(device)
    active = session
    check(device.state == AudioRecord.STATE_INITIALIZED) { "Micrófono no disponible" }
    val vad = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320,
      Mode.VERY_AGGRESSIVE, speechDurationMs = 60, silenceDurationMs = 0)
    try {
      val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
      val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
      dir.listFiles()?.filter { it.name.startsWith("instinct_") && it.lastModified() < cutoff }
        ?.forEach { it.delete() }
      session.output = File.createTempFile("instinct_", ".m4a", dir)
      device.startRecording()
      check(device.recordingState == AudioRecord.RECORDSTATE_RECORDING)
      session.running = true
      session.worker = thread(name = "InstinctVoiceCapture") { capture(session, vad) }
    } catch (failure: Throwable) {
      vad.close()
      throw failure
    }
  }.onFailure { cancel() }

  private fun capture(session: Session, vad: VadWebRTC) {
    val policy = VoiceSessionPolicy()
    val preroll = ArrayDeque<ShortArray>()
    val frame = ShortArray(320)
    val started = SystemClock.elapsedRealtime()
    var writer: AacAudioWriter? = null
    try {
      var offset = 0
      try {
        while (session.running) {
          val read = session.input.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
          if (!session.running) break
          check(read > 0) { "Se interrumpió la captura del micrófono ($read)" }
          offset += read
          if (offset < frame.size) continue
          offset = 0
          if (android.os.Build.VERSION.SDK_INT >= 29) {
            check(session.input.activeRecordingConfiguration?.isClientSilenced != true) {
              "Android silenció el micrófono. Reactivá la escucha cuando esté disponible."
            }
          }
          val speech = vad.isSpeech(frame)
          val elapsed = SystemClock.elapsedRealtime() - started
          val state = policy.update(elapsed, speech)
          if (!session.hasSpeech) {
            preroll.addLast(frame.copyOf())
            if (preroll.size > 15) preroll.removeFirst() // 300 ms preserve first syllables.
            if (state == VoiceSessionPolicy.State.RECORDING) {
              writer = AacAudioWriter(checkNotNull(session.output))
              session.hasSpeech = true
              preroll.forEach { writer.write(it) }
              preroll.clear()
            }
          } else checkNotNull(writer).write(frame)
          session.snapshot = Snapshot(state, elapsed, frame.maxOf { abs(it.toInt()) } / 32768f)
          if (state == VoiceSessionPolicy.State.IDLE || state == VoiceSessionPolicy.State.SEND) break
          check(elapsed < 300_000) { "Límite de 5 minutos alcanzado. Podés enviar el audio guardado." }
        }
      } finally {
        writer?.close()
        session.finalized = writer != null
      }
    } catch (failure: Throwable) {
      session.snapshot = session.snapshot.copy(error = failure.localizedMessage ?: "Error de micrófono o codificación")
    } finally {
      session.running = false
      runCatching { session.input.stop() }
      vad.close()
    }
  }

  /** Blocking finalization: the ViewModel must call this on Dispatchers.IO. */
  override fun finish(): RecordedAudio? {
    val session = detach() ?: return null
    stopInput(session)
    awaitRelease(session)
    lastSnapshot = session.snapshot
    val file = session.output
    return if (session.hasSpeech && session.finalized && file != null && file.length() > 0) {
      RecordedAudio(file, "audio/mp4")
    } else { file?.delete(); null }
  }

  override fun cancel() {
    val session = detach() ?: return
    stopInput(session) // Stop the microphone immediately; discard the file off Main.
    thread(name = "InstinctVoiceDiscard") {
      awaitRelease(session)
      session.output?.delete()
    }
  }

  private fun detach(): Session? = synchronized(this) { active.also { active = null } }

  private fun stopInput(session: Session) {
    session.running = false
    runCatching { session.input.stop() }
  }

  private fun awaitRelease(session: Session) {
    session.worker?.join()
    runCatching { session.input.release() }
  }
}
