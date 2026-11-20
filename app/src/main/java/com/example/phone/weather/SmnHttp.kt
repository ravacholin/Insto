package com.example.phone.weather

import com.example.phone.ProviderHttp
import java.io.InterruptedIOException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Typed SMN failures. Messages never contain tokens, bodies or user text. */
enum class SmnErrorKind { NETWORK, TIMEOUT, BLOCKED, RATE_LIMITED, UNAUTHORIZED, NOT_JSON, MALFORMED, TOO_LARGE }

class SmnException(val kind: SmnErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/** One HTTP response. Header names are lower case. */
class SmnHttpResponse(val code: Int, headers: Map<String, String>, val body: ByteArray) {
  val headers: Map<String, String> = headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
  fun header(name: String): String? = headers[name.lowercase(java.util.Locale.ROOT)]
  override fun toString(): String = "SmnHttpResponse(code=$code, bytes=${body.size})"
}

/**
 * Injectable GET. Implementations must not follow redirects, must read at most [maxBytes] and throw
 * [SmnException] with [SmnErrorKind.TOO_LARGE], [SmnErrorKind.TIMEOUT] or [SmnErrorKind.NETWORK].
 */
fun interface SmnTransport {
  suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): SmnHttpResponse
}

/** Honest identification: no browser User-Agent. */
internal const val SMN_USER_AGENT = "Insto/1.12 (Android)"
internal const val SMN_CONNECT_MILLIS = 4_000
internal const val SMN_READ_MILLIS = 8_000

/** Production transport over the shared OkHttp pool, without provider diagnostics. */
object OkHttpSmnTransport : SmnTransport {
  override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): SmnHttpResponse = try {
    val response = ProviderHttp.get(url, mapOf("User-Agent" to SMN_USER_AGENT) + headers,
      SMN_CONNECT_MILLIS, SMN_READ_MILLIS, maxBytes)
    SmnHttpResponse(response.code, response.headers, response.body)
  } catch (error: ProviderHttp.ResponseTooLarge) {
    throw SmnException(SmnErrorKind.TOO_LARGE, "La respuesta del SMN supera el tamaño admitido.")
  } catch (error: SocketTimeoutException) {
    throw SmnException(SmnErrorKind.TIMEOUT, "El SMN no respondió a tiempo.")
  } catch (error: InterruptedIOException) {
    // OkHttp reports the whole-call deadline as InterruptedIOException("timeout").
    throw SmnException(SmnErrorKind.TIMEOUT, "El SMN no respondió a tiempo.")
  } catch (error: IOException) {
    throw SmnException(SmnErrorKind.NETWORK, "No hubo conexión con el SMN.")
  }
}

/** Hosts this feature may contact. Everything else is refused before any request. */
internal object SmnHosts {
  const val OPEN_DATA = "ssl.smn.gob.ar"
  const val PORTAL = "www.smn.gob.ar"
  const val API = "ws1.smn.gob.ar"
  private val allowed = setOf(OPEN_DATA, PORTAL, API)

  fun host(url: String): String {
    val uri = try { URI(url) } catch (_: Exception) { null }
    require(uri != null && uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host in allowed) {
      "Destino no admitido para el SMN"
    }
    return uri.host
  }
}

/**
 * In-memory cooldown per SMN host after a block or rate limit. While active, nothing is requested from that host.
 * Never persisted: a restart is the only way to retry earlier, and that is a deliberate user action.
 */
class SmnCooldown(private val clock: () -> Long, private val durationMillis: Long = 30 * 60_000L) {
  private val until = ConcurrentHashMap<String, Pair<Long, SmnErrorKind>>()
  fun active(host: String): SmnErrorKind? {
    val (end, kind) = until[host] ?: return null
    if (clock() >= end) { until.remove(host); return null }
    return kind
  }
  fun start(host: String, kind: SmnErrorKind) { until[host] = clock() + durationMillis to kind }
  fun check(host: String) {
    active(host)?.let { throw SmnException(it, "El SMN limitó las consultas automáticas; se espera antes de reintentar.") }
  }
}

internal object SmnResponses {
  private const val SNIFF = 64 * 1024

  /** Cloudflare challenge or block page, regardless of status. */
  fun isChallenge(response: SmnHttpResponse): Boolean {
    if (response.header("cf-mitigated")?.trim()?.equals("challenge", ignoreCase = true) == true) return true
    val type = response.header("content-type").orEmpty().lowercase(java.util.Locale.ROOT)
    val start = String(response.body, 0, minOf(response.body.size, SNIFF), Charsets.ISO_8859_1)
    if (!type.contains("html") && !start.trimStart().startsWith("<")) return false
    return start.contains("Attention Required") || start.contains("cf-chl") || start.contains("challenge-platform")
  }

  /** Throws for blocks and limits, and starts the host cooldown. Other statuses are left to the caller. */
  fun rejectBlocked(host: String, response: SmnHttpResponse, cooldown: SmnCooldown) {
    val kind = when {
      isChallenge(response) || response.code == 403 -> SmnErrorKind.BLOCKED
      response.code == 429 || response.code == 503 -> SmnErrorKind.RATE_LIMITED
      else -> return
    }
    cooldown.start(host, kind)
    throw SmnException(kind, if (kind == SmnErrorKind.BLOCKED) "El SMN bloqueó la consulta automática."
      else "El SMN limitó las consultas automáticas.")
  }
}
