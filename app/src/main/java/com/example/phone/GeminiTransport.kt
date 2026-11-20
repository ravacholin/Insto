package com.example.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor

internal object GeminiTransport {
  enum class Provider(val label: String) { GEMINI("Gemini"), GROQ("Groq") }

  class ModelUnavailable : Exception()
  private class OversizedErrorBody : Exception()

  private const val MAX_RESPONSE_BYTES = 65536

  /**
   * One JSON POST to an interpretation provider, sent at most once. [transport] replaces the network in tests.
   * A coroutine cancellation is rethrown as is: it is never a provider failure and never triggers a fallback.
   */
  suspend fun post(
    key: () -> String?,
    body: String,
    url: String,
    transport: Interceptor?,
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
          ProviderHttp.Limits.json(timeoutMillis), transport, { it.write(bytes) }) { status, input ->
          if (modelRejected != null) {
            if (status == 404) throw ModelUnavailable()
            if (status == 400) {
              val errorBody = try {
                input().use { it.readBounded(MAX_RESPONSE_BYTES) { OversizedErrorBody() }.toString(Charsets.UTF_8) }
              } catch (_: OversizedErrorBody) {
                ""
              }
              if (modelRejected(errorBody)) throw ModelUnavailable()
            }
          }
          ProviderHttp.classifyStatus(status)?.let { throw failure(service, it) }
          input().use {
            it.readBounded(MAX_RESPONSE_BYTES) { failure(service, ProviderHttp.FailureKind.TOO_LARGE) }.toString(Charsets.UTF_8)
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: ModelUnavailable) {
        throw error
      } catch (error: DecisionException) {
        throw error
      } catch (error: Exception) {
        // Only transport errors reach here; anything else is a local failure to connect.
        val kind = ProviderHttp.classify(error)
        throw failure(service, if (kind == ProviderHttp.FailureKind.TIMEOUT) kind else ProviderHttp.FailureKind.NETWORK)
      }
    }
  }

  /** One message per failure kind. Never includes the key, the request or the provider's body. */
  internal fun failure(service: String, kind: ProviderHttp.FailureKind): DecisionException = when (kind) {
    ProviderHttp.FailureKind.AUTH -> DecisionException("$service rechazó la clave o el acceso. Revisá la configuración; el pedido se conserva.", GeminiFailureKind.AUTH)
    ProviderHttp.FailureKind.TIMEOUT -> DecisionException("$service tardó demasiado. El pedido se conserva.", GeminiFailureKind.TIMEOUT)
    ProviderHttp.FailureKind.QUOTA -> DecisionException("$service alcanzó su cuota. Esperá antes de reintentar; el pedido se conserva.", GeminiFailureKind.QUOTA)
    ProviderHttp.FailureKind.UNAVAILABLE -> DecisionException("El servicio de $service no está disponible para esta cuenta. El pedido se conserva.", GeminiFailureKind.UNAVAILABLE)
    ProviderHttp.FailureKind.SERVER -> DecisionException("$service tiene un problema temporal. El pedido se conserva para reintentar.", GeminiFailureKind.SERVER)
    ProviderHttp.FailureKind.INVALID_REQUEST -> DecisionException("$service rechazó la solicitud. El pedido se conserva.", GeminiFailureKind.INVALID_REQUEST)
    ProviderHttp.FailureKind.TOO_LARGE -> DecisionException("La respuesta de $service es demasiado grande. El pedido se conserva.", GeminiFailureKind.INVALID_RESPONSE)
    ProviderHttp.FailureKind.NETWORK -> DecisionException("No se pudo conectar con $service. El pedido se conserva.", GeminiFailureKind.NETWORK)
    ProviderHttp.FailureKind.INVALID_RESPONSE, ProviderHttp.FailureKind.CANCELLED ->
      DecisionException("Respuesta inválida de $service. El pedido se conserva.", GeminiFailureKind.INVALID_RESPONSE)
  }
}
