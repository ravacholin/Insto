package com.example.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.alarmvoice.AlarmVoiceModel
import com.example.phone.CapabilityHealthCommand
import com.example.phone.CapabilityHealthRuntime
import com.example.phone.HealthCode
import com.example.phone.HealthStage
import com.example.phone.LocalIntentRegistry
import com.example.phone.PhoneCommandParser
import com.example.phone.PhoneRoute
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.coroutines.coroutineContext

/** Offline recognition is limited to the diagnostic vocabulary and uses the existing Vosk installation. */
internal object HealthSpeechRecognition {
  suspend fun recognize(context: Context, file: File): String? = withContext(Dispatchers.IO) {
    val model = AlarmVoiceModel(context)
    if (!model.hasRequiredFiles()) return@withContext null
    try {
      decode(model.directory, file)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun decode(directory: File, file: File): String? {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      val track = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
      } ?: return null
      extractor.selectTrack(track)
      val format = extractor.getTrackFormat(track)
      if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1 ||
        format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16_000) return null
      if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > 12_000_000L) return null
      val model = Model(directory.absolutePath)
      try {
        val grammar = CapabilityHealthCommand.entries.flatMap { it.phrases.toList() }.map {
          it.trim('¿', '?', '¡', '!').lowercase(Locale.ROOT)
        } + "[unk]"
        val recognizer = Recognizer(model, 16_000f, JSONArray(grammar).toString()).apply { setWords(true) }
        try {
          val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
          try {
            codec.configure(format, null, null, 0)
            codec.start()
            val results = decodePcm(codec, extractor, recognizer)
            val segments = results.mapNotNull { json ->
              val result = JSONObject(json)
              val text = result.optString("text").takeIf(String::isNotBlank) ?: return@mapNotNull null
              val words = result.optJSONArray("result") ?: return null
              if (words.length() == 0 || (0 until words.length()).any { words.getJSONObject(it).optDouble("conf", 0.0) < 0.7 }) return null
              text
            }
            val text = segments.joinToString(" ")
            val normalized = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(text))
            val match = LocalIntentRegistry.default.matchPhrase(normalized) as? PhoneRoute.Local
            // Offline audio never dispatches any command beyond this closed diagnostic grammar.
            return text.takeIf { CapabilityHealthCommand.of(match?.command) != null }
          } finally {
            codec.release()
          }
        } finally {
          recognizer.close()
        }
      } finally {
        model.close()
      }
    } finally {
      extractor.release()
    }
  }

  private suspend fun decodePcm(codec: MediaCodec, extractor: MediaExtractor, recognizer: Recognizer): List<String> {
    val deadline = SystemClock.elapsedRealtime() + 3_000L
    val info = MediaCodec.BufferInfo()
    val results = mutableListOf<String>()
    var inputEnded = false
    var totalBytes = 0
    while (SystemClock.elapsedRealtime() < deadline) {
      coroutineContext.ensureActive()
      if (!inputEnded) {
        val index = codec.dequeueInputBuffer(5_000)
        if (index >= 0) {
          val length = extractor.readSampleData(checkNotNull(codec.getInputBuffer(index)), 0)
          if (length < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEnded = true
          } else {
            codec.queueInputBuffer(index, 0, length, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }
      val index = codec.dequeueOutputBuffer(info, 5_000)
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        val output = codec.outputFormat
        if (output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1 || output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16_000 ||
          output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT)
          return emptyList()
      }
      if (index < 0) continue
      try {
        totalBytes += info.size
        if (totalBytes > 400_000) return emptyList()
        if (info.size > 0) {
          val buffer = checkNotNull(codec.getOutputBuffer(index))
          buffer.position(info.offset)
          buffer.limit(info.offset + info.size)
          val pcm = ByteArray(info.size).also { buffer.get(it) }
          if (recognizer.acceptWaveForm(pcm, pcm.size)) results += recognizer.result
        }
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return results + recognizer.finalResult
      } finally {
        codec.releaseOutputBuffer(index, false)
      }
    }
    return emptyList()
  }
}

/** Short diagnostic responses using a locally installed Spanish voice when available. */
internal class HealthSpeech(private val context: Context, private val scope: CoroutineScope) {
  private var speaking: Job? = null

  fun speak(message: String) {
    speaking?.cancel()
    speaking = scope.launch(Dispatchers.Main.immediate) {
      val initialized = Channel<Int>(1)
      val finished = Channel<Boolean>(1)
      val tts = TextToSpeech(context) { initialized.trySend(it) }
      var code = HealthCode.UNSUPPORTED
      try {
        val success = withTimeoutOrNull(12_000L) {
          if (initialized.receive() != TextToSpeech.SUCCESS) return@withTimeoutOrNull false
          val voice = tts.voices?.firstOrNull { it.locale.language == "es" && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features } ?: return@withTimeoutOrNull false
          if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return@withTimeoutOrNull false
          val id = UUID.randomUUID().toString()
          tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) finished.trySend(true) }
            @Deprecated("Legacy TTS callback")
            override fun onError(utteranceId: String?) { if (utteranceId == id) finished.trySend(false) }
          })
          code = HealthCode.NO_RESULT
          if (tts.speak(message.take(350), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
            return@withTimeoutOrNull false
          finished.receive()
        }
        val health = CapabilityHealthRuntime.current()
        health?.report("voice.tts", if (success == true) HealthCode.VERIFIED else if (success == null) HealthCode.TIMEOUT else code,
          stage = HealthStage.RESPONSE)
      } finally {
        tts.stop()
        tts.shutdown()
        initialized.close()
        finished.close()
      }
    }
  }
}
