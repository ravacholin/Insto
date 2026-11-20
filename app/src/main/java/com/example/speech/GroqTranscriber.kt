package com.example.speech

import com.example.phone.ProviderHttp
import com.example.phone.readBounded
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject

/** Upload a copy of the existing recording; never log keys, audio, or provider error bodies. */
class GroqTranscriber(
  private val key: () -> String?,
  /** Test seam replacing the network; production uses the shared transport. */
  private val transport: Interceptor? = null,
  private val prompt: () -> String? = { null },
) : AudioTranscriber {
  override suspend fun transcribe(file: File): String {
    val token = key()?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw TranscriptionException("Configurá tu clave de Groq para interpretar el audio.", needsKey = true)
    if (!file.isFile || file.length() == 0L || file.length() > MAX_AUDIO_BYTES) {
      throw TranscriptionException("El audio está vacío o supera el límite de 24 MB. La grabación se conserva.")
    }
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("model", "whisper-large-v3-turbo")
      .addFormDataPart("language", "es")
      .addFormDataPart("response_format", "json")
      .addFormDataPart("temperature", "0")
      .apply { prompt()?.takeIf { it.isNotBlank() }?.let { addFormDataPart("prompt", it) } }
      .addFormDataPart("file", "recording.m4a", AudioFileBody(file))
      .build()
      .let(::OneShotBody)
    return withTimeout(ProviderHttp.Limits.UPLOAD.callMillis - ProviderHttp.Limits.GRACE_MILLIS) {
      try {
        ProviderHttp.post(TRANSCRIPTIONS_URL, mapOf("Authorization" to "Bearer $token"), body,
          ProviderHttp.Limits.UPLOAD, transport) { code, input ->
          ProviderHttp.classifyStatus(code)?.let { throw TranscriptionException(statusMessage(it), needsKey = it == ProviderHttp.FailureKind.AUTH) }
          val bytes = input().use { it.readBounded(256 * 1024) { ProviderHttp.ResponseTooLarge() } }
          val text = JSONObject(bytes.toString(Charsets.UTF_8)).optString("text").trim()
          if (text.isBlank()) throw TranscriptionException("No se reconoció un pedido. El audio se conserva.")
          text
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: TranscriptionException) {
        throw error
      } catch (_: Exception) {
        throw TranscriptionException("No se pudo transcribir. Revisá la conexión y reintentá; el audio se conserva.")
      }
    }
  }

  /**
   * Streams the recording from disk on each write; never loads it into memory. One-shot: the upload is
   * never replayed by the HTTP layer, a retry is always an explicit new request.
   */
  private class AudioFileBody(private val file: File) : RequestBody() {
    override fun contentType() = "audio/mp4".toMediaType()
    override fun contentLength(): Long = file.length()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) {
      file.source().use { sink.writeAll(it) }
    }
  }

  /** OkHttp 4 does not mark a multipart body as one-shot even when a part is: declare it for the whole upload. */
  private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
  }

  private companion object {
    const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val MAX_AUDIO_BYTES = 24L * 1024 * 1024

    fun statusMessage(kind: ProviderHttp.FailureKind): String = when (kind) {
      ProviderHttp.FailureKind.AUTH -> "Groq rechazó la clave. Revisala en la configuración."
      ProviderHttp.FailureKind.QUOTA -> "Groq alcanzó su cuota o límite de pedidos. Esperá antes de reintentar."
      else -> "Groq no pudo interpretar el audio. Podés reintentar sin volver a grabar."
    }
  }
}
