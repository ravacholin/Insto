package com.example.phone

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

internal object GeminiTransport {
  enum class Provider(val label: String) { GEMINI("Gemini"), GROQ("Groq") }

  class ModelUnavailable : Exception()
  private class OversizedErrorBody : Exception()

  suspend fun post(
    key: () -> String?,
    body: String,
    url: String,
    connectionFactory: (() -> HttpURLConnection)?,
    timeoutMillis: Long,
    provider: Provider = Provider.GEMINI,
    modelRejected: ((String) -> Boolean)? = null,
  ): String {
    val service = provider.label
    val token = key()?.trim()?.takeIf { it.isNotBlank() }
      ?: throw DecisionException("Configurá tu clave de $service. El pedido se conserva.", GeminiFailureKind.AUTH)
    val headers = when (provider) {
      Provider.GEMINI -> mapOf("x-goog-api-key" to token)
      Provider.GROQ -> mapOf("Authorization" to "Bearer $token")
    }
    val bytes = body.toByteArray(Charsets.UTF_8)
    return withTimeout(timeoutMillis) {
      try {
        ProviderHttp.post(url, headers, "application/json; charset=utf-8", bytes.size.toLong(),
          minOf(5000, timeoutMillis.toInt()), minOf(8000, timeoutMillis.toInt()),
          connectionFactory, { it.write(bytes) }) { status, input ->
          if (modelRejected != null) {
            if (status == 404) throw ModelUnavailable()
            if (status == 400) {
              val errorBody = try {
                input().use { it.readBounded(65536) { OversizedErrorBody() }.toString(Charsets.UTF_8) }
              } catch (_: OversizedErrorBody) {
                ""
              }
              if (modelRejected(errorBody)) throw ModelUnavailable()
            }
          }
          if (status !in 200..299) throw when (status) {
            401, 403 -> DecisionException("$service rechazó la clave o el acceso. Revisá la configuración; el pedido se conserva.", GeminiFailureKind.AUTH)
            408 -> DecisionException("$service tardó demasiado. El pedido se conserva.", GeminiFailureKind.TIMEOUT)
            429 -> DecisionException("$service alcanzó su cuota. Esperá antes de reintentar; el pedido se conserva.", GeminiFailureKind.QUOTA)
            404, 405, 501 -> DecisionException("El servicio de $service no está disponible para esta cuenta. El pedido se conserva.", GeminiFailureKind.UNAVAILABLE)
            in 500..599 -> DecisionException("$service tiene un problema temporal. El pedido se conserva para reintentar.", GeminiFailureKind.SERVER)
            in 400..499 -> DecisionException("$service rechazó la solicitud. El pedido se conserva.", GeminiFailureKind.INVALID_REQUEST)
            else -> DecisionException("Respuesta inválida de $service. El pedido se conserva.", GeminiFailureKind.INVALID_RESPONSE)
          }
          input().use {
            it.readBounded(65536) {
              DecisionException("La respuesta de $service es demasiado grande. El pedido se conserva.", GeminiFailureKind.INVALID_RESPONSE)
            }.toString(Charsets.UTF_8)
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: ModelUnavailable) {
        throw error
      } catch (error: DecisionException) {
        throw error
      } catch (error: Exception) {
        throw DecisionException("No se pudo conectar con $service. El pedido se conserva.",
          if (error is SocketTimeoutException) GeminiFailureKind.TIMEOUT else GeminiFailureKind.NETWORK)
      }
    }
  }
}
