package com.example.speech

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Upload a copy of the existing recording; never log keys, audio, or provider error bodies. */
class GroqTranscriber(
  private val key: () -> String?,
  private val connectionFactory: () -> HttpURLConnection = {
    URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
  },
) : AudioTranscriber {
  override suspend fun transcribe(file: File): String {
    val token = key()?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw TranscriptionException("Configurá tu clave de Groq para interpretar el audio.", needsKey = true)
    if (!file.isFile || file.length() == 0L || file.length() > 24L * 1024 * 1024) {
      throw TranscriptionException("El audio está vacío o supera el límite de 24 MB. La grabación se conserva.")
    }
    return withTimeout(60_000) {
      suspendCancellableCoroutine { continuation ->
        val connection = try { connectionFactory() } catch (_: Exception) {
          continuation.resumeWithException(TranscriptionException("No se pudo conectar con Groq. El audio se conserva."))
          return@suspendCancellableCoroutine
        }
        val worker = thread(start = false, name = "GroqTranscription") {
          try {
            val boundary = "Insto${UUID.randomUUID()}"
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false // Never forward a credential to a redirect.
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setChunkedStreamingMode(8192)
            connection.outputStream.use { output ->
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
                  if (!continuation.isActive) return@thread
                  output.write(bytes, 0, count)
                  count = input.read(bytes)
                }
              }
              output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code !in 200..299) throw TranscriptionException(when (code) {
              401, 403 -> "Groq rechazó la clave. Revisala en la configuración."
              429 -> "Groq alcanzó su cuota o límite de pedidos. Esperá antes de reintentar."
              else -> "Groq no pudo interpretar el audio. Podés reintentar sin volver a grabar."
            }, needsKey = code == 401 || code == 403)
            val bytes = connection.inputStream.use { it.readNBytesCompat(256 * 1024) }
            val text = JSONObject(bytes.toString(Charsets.UTF_8)).optString("text").trim()
            if (text.isBlank()) throw TranscriptionException("No se reconoció un pedido. El audio se conserva.")
            if (continuation.isActive) continuation.resume(text)
          } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(
              if (e is TranscriptionException) e else TranscriptionException("No se pudo transcribir. Revisá la conexión y reintentá; el audio se conserva."))
          } finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { connection.disconnect(); worker.interrupt() }
        worker.start()
      }
    }
  }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
  val output = java.io.ByteArrayOutputStream()
  val buffer = ByteArray(4096)
  while (true) {
    val n = read(buffer)
    if (n == -1) return output.toByteArray()
    if (output.size() + n > limit) throw IOException("Response too large")
    output.write(buffer, 0, n)
  }
}
