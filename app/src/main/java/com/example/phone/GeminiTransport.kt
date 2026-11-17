package com.example.phone

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Shared bounded, cancellable HTTP boundary. Never includes provider bodies in errors. */
internal object GeminiTransport {
  suspend fun post(key: () -> String?, body: String, connectionFactory: () -> HttpURLConnection, timeoutMillis: Long): String {
    val token = key()?.trim()?.takeIf { it.isNotBlank() }
      ?: throw DecisionException("Configurá tu clave de Gemini. El pedido se conserva.", GeminiFailureKind.AUTH)
    return withTimeout(timeoutMillis) {
      suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val worker = thread(start = false, isDaemon = true, name = "GeminiRequest") {
          val result = runCatching {
            val c = connectionFactory()
            active.set(c)
            try {
              check(continuation.isActive)
              c.requestMethod = "POST"
              c.connectTimeout = minOf(5000, timeoutMillis.toInt())
              c.readTimeout = minOf(8000, timeoutMillis.toInt())
              c.instanceFollowRedirects = false
              c.doOutput = true
              c.setRequestProperty("x-goog-api-key", token)
              c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
              val bytes = body.toByteArray(Charsets.UTF_8)
              c.setFixedLengthStreamingMode(bytes.size)
              check(continuation.isActive)
              c.outputStream.use { it.write(bytes) }
              val status = c.responseCode
              if (status !in 200..299) throw when (status) {
                401, 403 -> DecisionException("Gemini rechazó la clave o el acceso. Revisá la configuración; el pedido se conserva.", GeminiFailureKind.AUTH)
                429 -> DecisionException("Gemini alcanzó su cuota. Esperá antes de reintentar; el pedido se conserva.", GeminiFailureKind.QUOTA)
                404, 405, 501 -> DecisionException("El servicio de Gemini no está disponible para esta cuenta. El pedido se conserva.", GeminiFailureKind.UNAVAILABLE)
                in 500..599 -> DecisionException("Gemini tiene un problema temporal. El pedido se conserva para reintentar.", GeminiFailureKind.SERVER)
                else -> DecisionException("Gemini rechazó la solicitud. El pedido se conserva.", GeminiFailureKind.INVALID_RESPONSE)
              }
              c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                  check(continuation.isActive)
                  val n = input.read(buffer)
                  if (n < 0) break
                  if (out.size() + n > 65536) throw DecisionException("La respuesta de Gemini es demasiado grande. El pedido se conserva.", GeminiFailureKind.INVALID_RESPONSE)
                  out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
              }
            } finally { runCatching { c.disconnect() }; active.set(null) }
          }
          if (continuation.isActive) result.fold(
            { continuation.resume(it) },
            { continuation.resumeWithException(it as? DecisionException ?: DecisionException("No se pudo conectar con Gemini. El pedido se conserva.", GeminiFailureKind.NETWORK)) })
        }
        continuation.invokeOnCancellation { runCatching { active.get()?.disconnect() }; worker.interrupt() }
        worker.start()
      }
    }
  }
}
