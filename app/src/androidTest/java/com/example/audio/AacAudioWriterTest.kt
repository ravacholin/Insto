package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Real Android codec/container check; run on a device with connectedDebugAndroidTest. */
@RunWith(AndroidJUnit4::class)
class AacAudioWriterTest {
  @Test fun recordedPcmProducesPlayableAacInsideM4a() {
    val file = File.createTempFile("codec_test", ".m4a",
      InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    try {
      AacAudioWriter(file).use { writer ->
        repeat(50) { frame ->
          writer.write(ShortArray(320) { i ->
            (sin(2 * Math.PI * 440 * (frame * 320 + i) / 16000) * 12000).toInt().toShort()
          })
        }
      }
      assertTrue(file.length() > 0)
      file.inputStream().use { input ->
        val header = ByteArray(8)
        assertEquals(8, input.read(header))
        assertEquals("ftyp", String(header, 4, 4, Charsets.US_ASCII))
      }
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        assertEquals(1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(16000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        extractor.selectTrack(0)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
          decoder.configure(format, null, null, 0)
          decoder.start()
          var inputEnded = false
          var outputEnded = false
          var decodedBytes = 0L
          val info = MediaCodec.BufferInfo()
          val deadline = android.os.SystemClock.elapsedRealtime() + 5000
          while (!outputEnded && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!inputEnded) {
              val index = decoder.dequeueInputBuffer(10_000)
              if (index >= 0) {
                val buffer = checkNotNull(decoder.getInputBuffer(index))
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) {
                  decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  inputEnded = true
                } else {
                  decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                  extractor.advance()
                }
              }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
              decodedBytes += info.size
              outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
              decoder.releaseOutputBuffer(index, false)
            }
          }
          assertTrue("Decoder must reach end of stream", outputEnded)
          assertTrue("One second of mono PCM must be recoverable", decodedBytes >= 30000)
        } finally { runCatching { decoder.stop() }; decoder.release() }
      } finally { extractor.release() }
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        val duration = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        assertTrue("Expected a one second recording, got $duration ms", duration in 900..1300)
      } finally { retriever.release() }
    } finally { file.delete() }
  }
}
