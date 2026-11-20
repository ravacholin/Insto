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

/** 16 kHz mono PCM source. [read] blocks like `AudioRecord.read`: samples read, or a negative error code. */
internal interface CaptureInput {
  fun read(buffer: ShortArray, offset: Int, size: Int): Int
  /** Android silenced this client: another app took the microphone. */
  val silenced: Boolean
  fun stop()
  fun release()
}

/** Raw per-frame speech decision. */
internal interface SpeechDetector {
  fun isSpeech(frame: ShortArray): Boolean
  fun close()
}

internal interface FrameWriter {
  fun write(frame: ShortArray)
  fun close()
}

/**
 * Each session owns its device and its file; cancelling never waits for codec finalization on Main.
 *
 * The device, the VAD and the encoder are replaceable only to test the session lifecycle on the JVM; the app always
 * uses AudioRecord, WebRTC VAD and AAC.
 */
class FloatingVoiceCapture internal constructor(
  private val directory: () -> File,
  private val openInput: () -> CaptureInput,
  private val newDetector: () -> SpeechDetector,
  private val newWriter: (File) -> FrameWriter,
  private val clock: () -> Long,
) : FloatingAudioCapture {
  constructor(context: Context) : this(
    directory = { File(context.cacheDir, "voice_notes") },
    openInput = ::openMicrophone,
    newDetector = ::webRtcDetector,
    newWriter = { file -> AacAudioWriter(file).let { writer -> object : FrameWriter {
      override fun write(frame: ShortArray) = writer.write(frame)
      override fun close() = writer.close()
    } } },
    clock = SystemClock::elapsedRealtime,
  )

  data class Snapshot(
    val state: VoiceSessionPolicy.State = VoiceSessionPolicy.State.LISTENING,
    val elapsedMs: Long = 0,
    val amplitude: Float = 0f,
    val error: String? = null,
    val speechEndAtMs: Long? = null,
  )
  private class Session(val input: CaptureInput) {
    var output: File? = null
    var worker: Thread? = null
    @Volatile var running = false
    @Volatile var hasSpeech = false
    @Volatile var finalized = false
    /** Set by [finish]: speech still being confirmed is written instead of discarded. */
    @Volatile var keepPending = false
    @Volatile var snapshot = Snapshot()
  }
  @Volatile private var active: Session? = null
  @Volatile private var lastSnapshot = Snapshot()
  override val snapshot: Snapshot get() = active?.snapshot ?: lastSnapshot

  override fun start(): Result<Unit> = runCatching {
    cancel()
    lastSnapshot = Snapshot()
    val session = Session(openInput())
    active = session
    val detector = newDetector()
    try {
      val dir = directory().apply { mkdirs() }
      val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
      dir.listFiles()?.filter { it.name.startsWith("instinct_") && it.lastModified() < cutoff }
        ?.forEach { it.delete() }
      session.output = File.createTempFile("instinct_", ".m4a", dir)
      session.running = true
      session.worker = thread(name = "InstinctVoiceCapture") { capture(session, detector) }
    } catch (failure: Throwable) {
      session.running = false
      detector.close()
      throw failure
    }
  }.onFailure { cancel() }

  private fun capture(session: Session, detector: SpeechDetector) {
    val policy = VoiceSessionPolicy()
    // Before confirmation: the pre-roll plus the unconfirmed candidate, so first syllables are kept.
    val pending = ArrayDeque<ShortArray>()
    val frame = ShortArray(FRAME_SAMPLES)
    val started = clock()
    var writer: FrameWriter? = null
    var failure: Throwable? = null
    fun open() = newWriter(checkNotNull(session.output)).also { opened ->
      writer = opened
      session.hasSpeech = true
      while (pending.isNotEmpty()) opened.write(pending.removeFirst())
    }
    try {
      var offset = 0
      while (session.running) {
        val read = session.input.read(frame, offset, frame.size - offset)
        if (!session.running) break
        check(read > 0) { "Se interrumpió la captura del micrófono ($read)" }
        offset += read
        if (offset < frame.size) continue
        offset = 0
        check(!session.input.silenced) { "Android silenció el micrófono. Reactivá la escucha cuando esté disponible." }
        val speech = detector.isSpeech(frame)
        val elapsed = clock() - started
        val state = policy.update(elapsed, speech)
        val current = writer
        if (current != null) current.write(frame)
        else {
          pending.addLast(frame.copyOf())
          val candidateFrames = policy.candidateStartMs?.let { ((elapsed - it) / VoiceSessionPolicy.FRAME_MS).toInt() + 1 } ?: 0
          val keep = minOf(PREROLL_FRAMES + candidateFrames, MAX_PENDING_FRAMES)
          while (pending.size > keep) pending.removeFirst()
          if (state == VoiceSessionPolicy.State.RECORDING || state == VoiceSessionPolicy.State.SEND) open()
        }
        session.snapshot = Snapshot(state, elapsed, frame.maxOf { abs(it.toInt()) } / 32768f,
          speechEndAtMs = if (session.hasSpeech) started + policy.lastSpeechMs else null)
        if (state == VoiceSessionPolicy.State.IDLE || state == VoiceSessionPolicy.State.SEND) break
        check(elapsed < VoiceSessionPolicy.MAX_RECORDING_MS) { "Límite de 5 minutos alcanzado. Podés enviar el audio guardado." }
      }
    } catch (error: Throwable) {
      failure = error
    }
    try {
      // A manual send or a microphone failure keeps speech that was still being confirmed.
      if (writer == null && policy.candidateStartMs != null && pending.isNotEmpty() && (session.keepPending || failure != null)) open()
    } catch (error: Throwable) {
      if (failure == null) failure = error
    }
    try {
      writer?.close()
      session.finalized = writer != null
    } catch (error: Throwable) {
      if (failure == null) failure = error
    }
    failure?.let { session.snapshot = session.snapshot.copy(error = it.localizedMessage ?: "Error de micrófono o codificación") }
    session.running = false
    runCatching { session.input.stop() }
    detector.close()
  }

  /** Blocking finalization: the ViewModel must call this on Dispatchers.IO. */
  override fun finish(): RecordedAudio? {
    val session = detach() ?: return null
    session.keepPending = true
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

  private companion object {
    const val FRAME_SAMPLES = 320
    val PREROLL_FRAMES = (VoiceSessionPolicy.PREROLL_MS / VoiceSessionPolicy.FRAME_MS).toInt()
    /** An unconfirmed candidate lasts about two seconds at most; this only bounds memory. */
    const val MAX_PENDING_FRAMES = 250

    @SuppressLint("MissingPermission")
    fun openMicrophone(): CaptureInput {
      val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      check(minimum > 0) { "El micrófono no admite audio de 16 kHz" }
      val device = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
      try {
        check(device.state == AudioRecord.STATE_INITIALIZED) { "Micrófono no disponible" }
        device.startRecording()
        check(device.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Micrófono no disponible" }
      } catch (failure: Throwable) {
        runCatching { device.stop() }
        device.release()
        throw failure
      }
      return object : CaptureInput {
        override fun read(buffer: ShortArray, offset: Int, size: Int) =
          device.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
        override val silenced: Boolean get() =
          android.os.Build.VERSION.SDK_INT >= 29 && device.activeRecordingConfiguration?.isClientSilenced == true
        override fun stop() = device.stop()
        override fun release() = device.release()
      }
    }

    /**
     * Raw per-frame decisions: VoiceSessionPolicy owns the hysteresis. With the library's smoothing (60 ms / 0 ms)
     * a single non-speech frame reset the onset count, and VERY_AGGRESSIVE dropped most of a low voice in noise.
     * AGGRESSIVE keeps noise rejection without that loss (docs/VOICE-CAPTURE.md).
     */
    fun webRtcDetector(): SpeechDetector {
      val vad = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320,
        Mode.AGGRESSIVE, speechDurationMs = 0, silenceDurationMs = 0)
      return object : SpeechDetector {
        override fun isSpeech(frame: ShortArray) = vad.isSpeech(frame)
        override fun close() = vad.close()
      }
    }
  }
}
