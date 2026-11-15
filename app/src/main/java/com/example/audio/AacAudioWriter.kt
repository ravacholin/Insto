package com.example.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/** Streams 16-bit mono PCM into a finalized AAC-LC/MPEG-4 file (not renamed WAV). */
class AacAudioWriter(file: File) : Closeable {
  private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
  private var muxer: MediaMuxer? = null
  private var muxerStarted = false
  private var track = -1
  private var samplesWritten = 0L
  private var outputEnded = false
  private var closed = false
  private val info = MediaCodec.BufferInfo()

  init {
    try {
      val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 6400)
      }
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (failure: Throwable) {
      runCatching { codec.stop() }
      codec.release()
      throw failure
    }
  }

  fun write(samples: ShortArray) {
    check(!closed) { "El codificador ya está cerrado" }
    var offset = 0
    while (offset < samples.size) {
      val index = awaitInputBuffer()
      val input = checkNotNull(codec.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
      val count = minOf(samples.size - offset, input.remaining() / 2)
      check(count > 0) { "El codificador no ofrece espacio de entrada" }
      repeat(count) { input.putShort(samples[offset + it]) }
      codec.queueInputBuffer(index, 0, count * 2, samplesWritten * 1_000_000L / SAMPLE_RATE, 0)
      samplesWritten += count
      offset += count
      drain(waitForEnd = false)
    }
  }

  private fun awaitInputBuffer(): Int {
    val deadline = SystemClock.elapsedRealtime() + 2_000
    do {
      val index = codec.dequeueInputBuffer(10_000)
      if (index >= 0) return index
      drain(waitForEnd = false)
    } while (SystemClock.elapsedRealtime() < deadline)
    error("El codificador de audio no respondió")
  }

  private fun drain(waitForEnd: Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 2_000
    do {
      val index = codec.dequeueOutputBuffer(info, if (waitForEnd) 10_000 else 0)
      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!waitForEnd) return
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          check(!muxerStarted) { "El formato AAC cambió durante la grabación" }
          val target = checkNotNull(muxer)
          track = target.addTrack(codec.outputFormat)
          target.start()
          muxerStarted = true
        }
        index >= 0 -> {
          try {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (info.size > 0) {
              check(muxerStarted) { "Falta la cabecera del contenedor de audio" }
              val data = checkNotNull(codec.getOutputBuffer(index))
              data.position(info.offset)
              data.limit(info.offset + info.size)
              checkNotNull(muxer).writeSampleData(track, data, info)
            }
            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
          } finally { codec.releaseOutputBuffer(index, false) }
        }
      }
      if (outputEnded) return
    } while (!waitForEnd || SystemClock.elapsedRealtime() < deadline)
    error("No se pudo finalizar el audio AAC")
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      val index = awaitInputBuffer()
      codec.queueInputBuffer(index, 0, 0, samplesWritten * 1_000_000L / SAMPLE_RATE,
        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      drain(waitForEnd = true)
      check(muxerStarted && outputEnded) { "El archivo de audio está incompleto" }
      checkNotNull(muxer).stop() // Writes the MPEG-4 metadata before the file can be shared.
      muxerStarted = false
    } finally {
      runCatching { muxer?.release() }
      muxer = null
      runCatching { codec.stop() }
      codec.release()
    }
  }

  companion object { private const val SAMPLE_RATE = 16_000 }
}
