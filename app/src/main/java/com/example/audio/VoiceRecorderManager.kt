package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

data class RecordedAudio(
  val file: File,
  val mimeType: String,
)

class VoiceRecorderManager(private val context: Context) {

  private data class RecorderProfile(
    val source: Int,
    val outputFormat: Int,
    val audioEncoder: Int,
    val bitRate: Int?,
    val sampleRate: Int?,
    val extension: String,
    val mimeType: String,
  )

  private var recorder: MediaRecorder? = null
  private var activeOutputFile: File? = null
  private var activeMimeType: String = "audio/mp4"
  private var startedAtMillis: Long = 0L

  val isRecording: Boolean
    get() = recorder != null

  fun startRecording(): Result<File> {
    stopAndCleanupInternal(deleteFile = true)

    val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
    cleanupOldFiles(dir)

    val profiles =
      listOf(
        RecorderProfile(
          source = MediaRecorder.AudioSource.MIC,
          outputFormat = MediaRecorder.OutputFormat.MPEG_4,
          audioEncoder = MediaRecorder.AudioEncoder.AAC,
          bitRate = 96_000,
          sampleRate = 44_100,
          extension = "m4a",
          mimeType = "audio/mp4",
        ),
        RecorderProfile(
          source = MediaRecorder.AudioSource.MIC,
          outputFormat = MediaRecorder.OutputFormat.MPEG_4,
          audioEncoder = MediaRecorder.AudioEncoder.AAC,
          bitRate = 64_000,
          sampleRate = 16_000,
          extension = "m4a",
          mimeType = "audio/mp4",
        ),
        RecorderProfile(
          source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
          outputFormat = MediaRecorder.OutputFormat.MPEG_4,
          audioEncoder = MediaRecorder.AudioEncoder.AAC,
          bitRate = null,
          sampleRate = null,
          extension = "m4a",
          mimeType = "audio/mp4",
        ),
        RecorderProfile(
          source = MediaRecorder.AudioSource.MIC,
          outputFormat = MediaRecorder.OutputFormat.THREE_GPP,
          audioEncoder = MediaRecorder.AudioEncoder.AMR_NB,
          bitRate = null,
          sampleRate = null,
          extension = "3gp",
          mimeType = "audio/3gpp",
        ),
      )

    var lastError: Throwable? = null

    for (profile in profiles) {
      val file = File(dir, "ping_voice_${System.currentTimeMillis()}.${profile.extension}")
      val candidate = createMediaRecorder()
      val attempt = runCatching {
        candidate.apply {
          setAudioSource(profile.source)
          setOutputFormat(profile.outputFormat)
          setAudioEncoder(profile.audioEncoder)
          profile.bitRate?.let { setAudioEncodingBitRate(it) }
          profile.sampleRate?.let { setAudioSamplingRate(it) }
          setOutputFile(file.absolutePath)
          prepare()
          start()
        }
        recorder = candidate
        activeOutputFile = file
        activeMimeType = profile.mimeType
        startedAtMillis = System.currentTimeMillis()
        file
      }

      if (attempt.isSuccess) {
        return attempt
      }

      lastError = attempt.exceptionOrNull()
      runCatching { candidate.reset() }
      runCatching { candidate.release() }
      file.delete()
    }

    stopAndCleanupInternal(deleteFile = true)
    return Result.failure(lastError ?: IllegalStateException("Unable to initialize MediaRecorder"))
  }

  /** Returns raw MediaRecorder maxAmplitude in [0, 32767] for voice activity detection. */
  fun sampleRawMaxAmplitude(): Int {
    val active = recorder ?: return 0
    return runCatching { active.maxAmplitude }.getOrDefault(0)
  }

  /** Returns normalized amplitude in [0f, 1f] for UI waveform rendering. */
  fun sampleNormalizedAmplitude(): Float {
    val raw = sampleRawMaxAmplitude()
    if (recorder == null) return 0f
    return (raw / 22_000f).coerceIn(0.08f, 1f)
  }

  fun elapsedMillis(): Long {
    if (recorder == null) return 0L
    return (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
  }

  fun remainingMinDurationMillis(minDurationMs: Long = 650L): Long {
    if (recorder == null) return 0L
    return (minDurationMs - elapsedMillis()).coerceAtLeast(0L)
  }

  fun stopRecording(): RecordedAudio? {
    val current = recorder ?: return null
    val output = activeOutputFile
    val mimeType = activeMimeType
    recorder = null
    activeOutputFile = null

    return runCatching {
        current.stop()
        current.reset()
        current.release()
        output
          ?.takeIf { it.exists() && it.length() > 0L }
          ?.let { RecordedAudio(file = it, mimeType = mimeType) }
      }
      .getOrElse {
        runCatching { current.release() }
        output?.delete()
        null
      }
  }

  fun cancelRecording() {
    stopAndCleanupInternal(deleteFile = true)
  }

  fun release() {
    stopAndCleanupInternal(deleteFile = true)
  }

  private fun createMediaRecorder(): MediaRecorder {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(context)
    } else {
      @Suppress("DEPRECATION") MediaRecorder()
    }
  }

  private fun stopAndCleanupInternal(deleteFile: Boolean) {
    val current = recorder
    val file = activeOutputFile
    recorder = null
    activeOutputFile = null

    if (current != null) {
      runCatching { current.stop() }
      runCatching { current.reset() }
      runCatching { current.release() }
    }
    if (deleteFile) {
      file?.delete()
    }
  }

  private fun cleanupOldFiles(dir: File) {
    val files = dir.listFiles() ?: return
    val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L // keep last 1h only
    files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
  }
}
