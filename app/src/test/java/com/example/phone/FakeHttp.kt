package com.example.phone

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Replaces the network of [ProviderHttp] in tests. It runs after the production interceptors, so it observes the
 * effective limits, headers and the exact body that would be sent; redirects, retries, size limits and
 * cancellation keep their production behaviour.
 */
internal class FakeHttp(private val respond: (Exchange) -> Reply) : Interceptor {
  class Exchange(val request: Request, val body: ByteArray, val connectMillis: Int, val writeMillis: Int, val readMillis: Int,
    private val call: okhttp3.Call) {
    val url: String get() = request.url.toString()
    val path: String get() = request.url.encodedPath
    fun header(name: String): String? = request.header(name)
    fun bodyText(): String = body.toString(Charsets.UTF_8)
    val isCanceled: Boolean get() = call.isCanceled()
    /** Blocks like a stalled server until the call is cancelled, then fails as OkHttp does. */
    fun awaitCancel(): Nothing {
      while (!call.isCanceled()) Thread.sleep(5)
      throw IOException("Canceled")
    }
  }

  class Reply(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

  val exchanges = CopyOnWriteArrayList<Exchange>()
  /** True once every response body handed out was closed by the client. */
  @Volatile var closed = false
    private set
  private var opened = 0
  private var released = 0

  val last: Exchange get() = exchanges.last()

  override fun intercept(chain: Interceptor.Chain): Response {
    val buffer = Buffer()
    chain.request().body?.writeTo(buffer)
    val exchange = Exchange(chain.request(), buffer.readByteArray(), chain.connectTimeoutMillis(),
      chain.writeTimeoutMillis(), chain.readTimeoutMillis(), chain.call())
    exchanges.add(exchange)
    val reply = respond(exchange)
    val content = Buffer().writeUtf8(reply.body)
    val size = content.size
    synchronized(this) { opened++; closed = false }
    val tracked = object : ForwardingSource(content) {
      override fun close() {
        super.close()
        synchronized(this@FakeHttp) { released++; closed = released == opened }
      }
    }.buffer()
    return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.code).message("fake")
      .apply { reply.headers.forEach { (name, value) -> header(name, value) } }
      .body(object : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = size
        override fun source(): BufferedSource = tracked
      }).build()
  }

  companion object {
    fun json(code: Int, body: String) = FakeHttp { Reply(code, body) }
  }
}
