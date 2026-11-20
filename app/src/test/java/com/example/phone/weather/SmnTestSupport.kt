package com.example.phone.weather

import java.util.concurrent.CopyOnWriteArrayList

/** Local SMN time (America/Argentina/Buenos_Aires) as epoch millis. */
internal fun smnTime(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
  SmnDate(year, month, day).atHour(hour)!! + minute * 60_000L

internal fun fixture(name: String): ByteArray =
  checkNotNull(SmnTestSupport::class.java.classLoader!!.getResourceAsStream("smn/$name")) { "Falta el fixture $name" }
    .use { it.readBytes() }

internal object SmnTestSupport {
  /** Signature-less test JWT. Never a real SMN token. */
  fun jwt(payload: String = """{"sub":"anonimo"}""", header: String = """{"alg":"HS256","typ":"JWT"}"""): String =
    Base64Url.encode(header.toByteArray()) + "." + Base64Url.encode(payload.toByteArray()) + ".firma"

  fun portal(token: String, quote: Char = '\''): String =
    "<html><head><script>localStorage.setItem($quote" + "token$quote, $quote$token$quote);</script></head><body>SMN</body></html>"
}

internal class Clock(var now: Long) { fun read() = now }

/** Records every request; answers by URL prefix in order. */
internal class FakeTransport(private val handler: suspend (url: String, headers: Map<String, String>) -> SmnHttpResponse) : SmnTransport {
  data class Call(val url: String, val headers: Map<String, String>, val maxBytes: Int)
  val calls = CopyOnWriteArrayList<Call>()
  override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): SmnHttpResponse {
    calls += Call(url, headers, maxBytes)
    return handler(url, headers)
  }
  fun count(prefix: String) = calls.count { it.url.startsWith(prefix) }
}

internal fun ok(body: ByteArray, type: String) = SmnHttpResponse(200, mapOf("Content-Type" to type), body)
internal fun json(body: String, code: Int = 200) =
  SmnHttpResponse(code, mapOf("Content-Type" to "application/json;charset=UTF-8"), body.toByteArray())
internal fun html(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
  SmnHttpResponse(code, mapOf("Content-Type" to "text/html; charset=UTF-8") + headers, body.toByteArray())

internal const val CLOUDFLARE_BLOCK =
  "<!DOCTYPE html><html><head><title>Attention Required! | Cloudflare</title></head><body><div class=\"cf-error-details\">Sorry, you have been blocked</div></body></html>"
internal const val API = "https://ws1.smn.gob.ar/"
internal const val PORTAL = "https://www.smn.gob.ar/"
internal const val OPEN_DATA = "https://ssl.smn.gob.ar/"
