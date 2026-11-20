package com.example.phone.weather

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

/**
 * Anonymous JWT that the SMN home page stores with `localStorage.setItem('token', …)`.
 * Kept in memory only; [toString] never reveals it.
 */
class SmnToken internal constructor(internal val value: String, val expiresAtMillis: Long) {
  override fun toString(): String = "SmnToken(expiresAtMillis=$expiresAtMillis)"
}

/** Pure extraction and validation. Unverified against the live page: Cloudflare blocked the check (see docs). */
object SmnTokenExtractor {
  const val MAX_HTML_BYTES = 1024 * 1024
  internal const val DEFAULT_LIFETIME_MILLIS = 10 * 60_000L
  internal const val RENEW_MARGIN_MILLIS = 2 * 60_000L
  private val pattern = Regex(
    "localStorage\\.setItem\\(\\s*['\"]token['\"]\\s*,\\s*['\"]([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*)['\"]\\s*\\)")

  /** Null when the page has no token. Throws [SmnErrorKind.MALFORMED] for a present but invalid one. */
  fun extract(html: String, now: Long): SmnToken? {
    val raw = pattern.find(html)?.groupValues?.get(1) ?: return null
    return validate(raw, now)
  }

  fun validate(raw: String, now: Long): SmnToken {
    val parts = raw.split('.')
    if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || raw.length > 4096)
      throw SmnException(SmnErrorKind.MALFORMED, "La credencial anónima del SMN no tiene formato JWT.")
    val header = json(parts[0]) ?: throw SmnException(SmnErrorKind.MALFORMED, "La credencial anónima del SMN no tiene un encabezado válido.")
    if (header.optString("alg").isEmpty()) throw SmnException(SmnErrorKind.MALFORMED, "La credencial anónima del SMN no declara algoritmo.")
    val exp = json(parts[1])?.opt("exp")
    val expiresAt = when (exp) {
      null -> now + DEFAULT_LIFETIME_MILLIS
      is Number -> exp.toDouble().takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() - RENEW_MARGIN_MILLIS }
        ?: throw SmnException(SmnErrorKind.MALFORMED, "La credencial anónima del SMN tiene un vencimiento inválido.")
      else -> throw SmnException(SmnErrorKind.MALFORMED, "La credencial anónima del SMN tiene un vencimiento inválido.")
    }
    if (expiresAt <= now) throw SmnException(SmnErrorKind.UNAUTHORIZED, "La credencial anónima del SMN ya está vencida.")
    return SmnToken(raw, expiresAt)
  }

  private fun json(segment: String): JSONObject? {
    val bytes = Base64Url.decode(segment) ?: return null
    return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: JSONException) { null }
  }
}

/** RFC 4648 base64url without padding; java.util.Base64 needs API 26. */
internal object Base64Url {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
  fun decode(text: String): ByteArray? {
    val clean = text.trimEnd('=')
    if (clean.length % 4 == 1) return null
    val output = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (char in clean) {
      val value = ALPHABET.indexOf(char).takeIf { it >= 0 } ?: return null
      buffer = (buffer shl 6) or value
      bits += 6
      if (bits >= 8) {
        bits -= 8
        output.write((buffer shr bits) and 0xFF)
      }
    }
    return output.toByteArray()
  }
  fun encode(bytes: ByteArray): String {
    val output = StringBuilder()
    var buffer = 0
    var bits = 0
    for (byte in bytes) {
      buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
      bits += 8
      while (bits >= 6) { bits -= 6; output.append(ALPHABET[(buffer shr bits) and 0x3F]) }
    }
    if (bits > 0) output.append(ALPHABET[(buffer shl (6 - bits)) and 0x3F])
    return output.toString()
  }
}

/**
 * One home-page request at a time, reused until shortly before expiry. Blocks start the shared cooldown,
 * so no further home-page request is made while it lasts.
 */
class SmnTokenProvider(
  private val transport: SmnTransport,
  private val cooldown: SmnCooldown,
  private val clock: () -> Long,
) {
  private val mutex = Mutex()
  @Volatile private var current: SmnToken? = null

  suspend fun token(): SmnToken = mutex.withLock {
    current?.takeIf { it.expiresAtMillis > clock() }?.let { return it }
    current = null
    val host = SmnHosts.host(PORTAL_URL)
    cooldown.check(host)
    val response = transport.get(PORTAL_URL, mapOf("Accept" to "text/html"), SmnTokenExtractor.MAX_HTML_BYTES)
    SmnResponses.rejectBlocked(host, response, cooldown)
    if (response.code != 200) throw SmnException(SmnErrorKind.NETWORK, "La portada del SMN respondió con el estado ${response.code}.")
    val html = String(response.body, Charsets.UTF_8)
    val token = SmnTokenExtractor.extract(html, clock())
      ?: throw SmnException(SmnErrorKind.UNAUTHORIZED, "La portada del SMN no incluyó la credencial anónima.")
    current = token
    token
  }

  /** Forget [used] after a 401, unless another caller already replaced it. */
  suspend fun invalidate(used: SmnToken) = mutex.withLock { if (current === used) current = null }

  companion object {
    const val PORTAL_URL = "https://www.smn.gob.ar/"
  }
}
