package com.example.speech

import com.example.phone.ProviderHttp
import com.example.phone.readBounded
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Upload a copy of the existing recording; never log keys, audio, or provider error bodies. */
class GroqTranscriber(
  private val key: () -> String?,
  private val connectionFactory: (() -> HttpURLConnection)? = null,
) : AudioTranscriber {
  override suspend fun transcribe(file: File): String {
    val token = key()?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw TranscriptionException("Configurá tu clave de Groq para interpretar el audio.", needsKey = true)
    if (!file.isFile || file.length() == 0L || file.length() > 24L * 1024 * 1024) {
      throw TranscriptionException("El audio está vacío o supera el límite de 24 MB. La grabación se conserva.")
    }
    return withTimeout(60_000) {
      val boundary = "Insto${UUID.randomUUID()}"
      try {
        ProviderHttp.post(
          "https://api.groq.com/openai/v1/audio/transcriptions", mapOf("Authorization" to "Bearer $token"),
          "multipart/form-data; boundary=$boundary", -1, 15_000, 30_000, connectionFactory,
          write = { output ->
            fun field(name: String, value: String) {
              output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", "whisper-large-v3-turbo")
            field("language", "es")
            field("response_format", "json")
            field("temperature", "0")
            output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
            file.inputStream().use { input ->
              val bytes = ByteArray(8192)
              var count = input.read(bytes)
              while (count != -1) {
                output.write(bytes, 0, count)
                count = input.read(bytes)
              }
            }
            output.write("\r\n--$boundary--\r\n".toByteArray())
          },
        ) { code, input ->
          if (code !in 200..299) throw TranscriptionException(when (code) {
            401, 403 -> "Groq rechazó la clave. Revisala en la configuración."
            429 -> "Groq alcanzó su cuota o límite de pedidos. Esperá antes de reintentar."
            else -> "Groq no pudo interpretar el audio. Podés reintentar sin volver a grabar."
          }, needsKey = code == 401 || code == 403)
          val bytes = input().use { it.readBounded(256 * 1024) { IOException("Response too large") } }
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
}
