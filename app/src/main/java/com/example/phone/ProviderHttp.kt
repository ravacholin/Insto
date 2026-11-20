package com.example.phone

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * The single HTTP transport of the app: one OkHttp pool for Gemini, Groq, SMN and Open-Meteo.
 *
 * Policies shared by every request, whatever the caller:
 * - no redirects and no automatic retries: a POST is sent at most once, its outcome is never guessed;
 * - finite connect, write and read limits per I/O operation, plus a whole-call deadline;
 * - bounded response bodies;
 * - cancelling the calling coroutine cancels the call and drops the late answer.
 *
 * Tests replace the network with an [Interceptor] (`transport`), never the policies above.
 */
internal object ProviderHttp {
  @Volatile var connectionLogger: ((String) -> Unit)? = null

  /**
   * Explicit limits of one exchange. [connectMillis], [writeMillis] and [readMillis] bound each I/O operation, so
   * a large upload that keeps progressing is not cut; [callMillis] bounds the whole exchange, including DNS.
   */
  data class Limits(val connectMillis: Int, val writeMillis: Int, val readMillis: Int, val callMillis: Long) {
    init { require(connectMillis > 0 && writeMillis > 0 && readMillis > 0 && callMillis > 0) { "Límites HTTP finitos" } }

    companion object {
      /**
       * A JSON request inside a caller deadline of [budgetMillis]. The caller's coroutine deadline decides first;
       * the call deadline is a backstop with [GRACE_MILLIS] of slack, so OkHttp threads never outlive it unbounded.
       */
      fun json(budgetMillis: Long): Limits {
        val budget = budgetMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        return Limits(minOf(5_000, budget), minOf(5_000, budget), minOf(8_000, budget), budget + GRACE_MILLIS)
      }

      /** Audio upload: up to 24 MB on a mobile link. Each write may stall 30 s; the whole call ends at 60 s. */
      val UPLOAD = Limits(connectMillis = 15_000, writeMillis = 30_000, readMillis = 30_000, callMillis = 60_000 + GRACE_MILLIS)

      /** Default for any request that did not declare its own limits. */
      val DEFAULT = Limits(connectMillis = 10_000, writeMillis = 10_000, readMillis = 10_000, callMillis = 30_000)

      const val GRACE_MILLIS = 1_000L
    }
  }

  /** Transport-level classification. Callers map it to their own user-facing messages. */
  enum class FailureKind { AUTH, QUOTA, TIMEOUT, NETWORK, SERVER, UNAVAILABLE, INVALID_REQUEST, INVALID_RESPONSE, TOO_LARGE, CANCELLED }

  /** Null for 2xx. Every non-2xx status maps to exactly one kind; nothing is retried here. */
  fun classifyStatus(code: Int): FailureKind? = when (code) {
    in 200..299 -> null
    401, 403 -> FailureKind.AUTH
    408 -> FailureKind.TIMEOUT
    429 -> FailureKind.QUOTA
    404, 405, 501 -> FailureKind.UNAVAILABLE
    in 500..599 -> FailureKind.SERVER
    in 400..499 -> FailureKind.INVALID_REQUEST
    else -> FailureKind.INVALID_RESPONSE // 1xx/3xx: redirects are never followed.
  }

  /**
   * Classifies a thrown error. A coroutine cancellation is [FailureKind.CANCELLED] and must be rethrown by the
   * caller, never reported as a provider failure nor used to try another provider.
   */
  fun classify(error: Throwable): FailureKind = when (error) {
    is CancellationException -> FailureKind.CANCELLED
    is ResponseTooLarge -> FailureKind.TOO_LARGE
    // SocketTimeoutException and OkHttp's whole-call "timeout" are both InterruptedIOException.
    is InterruptedIOException -> FailureKind.TIMEOUT
    is IOException -> FailureKind.NETWORK
    else -> FailureKind.INVALID_RESPONSE
  }

  private class Timings : EventListener() {
    private var dns = 0L
    private var tls = 0L
    private var total = 0L
    override fun dnsStart(call: Call, domainName: String) { dns = System.nanoTime() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
      total += System.nanoTime() - dns
    }
    override fun secureConnectStart(call: Call) { tls = System.nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
      total += System.nanoTime() - tls
    }
    override fun connectionAcquired(call: Call, connection: Connection) {
      // Solo los proveedores de interpretación alimentan sus métricas; otro host no se etiqueta como Gemini.
      val provider = providerFor(call.request().url.host) ?: return
      val millis = total / 1_000_000
      GeminiDiagnostics.connection(provider, millis)
      connectionLogger?.invoke("${provider.label}_connection_ms=$millis")
    }
  }

  internal fun providerFor(host: String): GeminiTransport.Provider? = when (host) {
    "api.groq.com" -> GeminiTransport.Provider.GROQ
    "generativelanguage.googleapis.com" -> GeminiTransport.Provider.GEMINI
    else -> null
  }

  private val client = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(Limits.DEFAULT.connectMillis.toLong(), TimeUnit.MILLISECONDS)
    .writeTimeout(Limits.DEFAULT.writeMillis.toLong(), TimeUnit.MILLISECONDS)
    .readTimeout(Limits.DEFAULT.readMillis.toLong(), TimeUnit.MILLISECONDS)
    .callTimeout(Limits.DEFAULT.callMillis, TimeUnit.MILLISECONDS)
    .eventListenerFactory { Timings() }
    .addNetworkInterceptor { chain ->
      val response = chain.proceed(chain.request())
      // Un HEAD no tiene body para marcar como de un solo envío.
      if (chain.request().method == "HEAD" && response.code == 503)
        response.newBuilder().removeHeader("Retry-After").build()
      else response
    }
    .addInterceptor { chain ->
      val limits = chain.request().tag(Limits::class.java) ?: return@addInterceptor chain.proceed(chain.request())
      chain.withConnectTimeout(limits.connectMillis, TimeUnit.MILLISECONDS)
        .withWriteTimeout(limits.writeMillis, TimeUnit.MILLISECONDS)
        .withReadTimeout(limits.readMillis, TimeUnit.MILLISECONDS)
        .proceed(chain.request())
    }
    .build()

  /**
   * Same pool, dispatcher, redirect policy and timeouts as [client], without the provider timing listener:
   * data requests (SMN) never reach Gemini/Groq diagnostics.
   */
  private val dataClient: OkHttpClient by lazy { client.newBuilder().eventListener(EventListener.NONE).build() }

  /** A test transport runs after the limits interceptor, so it observes the effective limits of the chain. */
  private fun OkHttpClient.with(transport: Interceptor?): OkHttpClient =
    if (transport == null) this else newBuilder().addInterceptor(transport).build()

  /** Response of a bounded GET. Header names are lower case; only the first value of each is kept. */
  class BoundedResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray)

  class ResponseTooLarge : IOException("Respuesta demasiado grande")

  /**
   * Bounded GET without following redirects. The body is read up to [maxBytes]; a larger declared or actual body
   * throws [ResponseTooLarge]. Cancelling the caller cancels the call.
   */
  suspend fun get(
    url: String,
    headers: Map<String, String>,
    connectMillis: Int,
    readMillis: Int,
    maxBytes: Int,
    transport: Interceptor? = null,
  ): BoundedResponse {
    val limits = Limits(connectMillis, connectMillis, readMillis, (connectMillis + readMillis).toLong())
    val request = Request.Builder().url(url).get()
      .tag(Limits::class.java, limits)
      .apply { headers.forEach { (name, value) -> header(name, value) } }
      .build()
    return execute(dataClient.with(transport), request, limits) { response ->
      val declared = response.body?.contentLength() ?: -1L
      if (declared > maxBytes) throw ResponseTooLarge()
      val body = response.body?.byteStream()?.use { it.readBounded(maxBytes) { ResponseTooLarge() } } ?: byteArrayOf()
      val names = response.headers.names().associate { name -> name.lowercase(java.util.Locale.ROOT) to response.headers[name].orEmpty() }
      BoundedResponse(response.code, names, body)
    }
  }

  suspend fun warm(geminiEnabled: Boolean) = coroutineScope {
    for (host in listOfNotNull("api.groq.com", "generativelanguage.googleapis.com".takeIf { geminiEnabled })) {
      launch {
        withTimeoutOrNull(5_000) {
          try {
            val limits = Limits(5_000, 5_000, 5_000, 5_000)
            val request = Request.Builder().url("https://$host/").head().tag(Limits::class.java, limits).build()
            execute(client, request, limits) { }
          } catch (_: IOException) { }
        }
      }
    }
  }

  /**
   * POST of a one-shot body. [write] streams the body (never buffered here); [read] receives the status and a
   * lazily opened body stream. The request is sent at most once: no retry, no redirect.
   */
  suspend fun <T> post(
    url: String,
    headers: Map<String, String>,
    contentType: String,
    length: Long,
    limits: Limits,
    transport: Interceptor? = null,
    write: (java.io.OutputStream) -> Unit,
    read: (Int, () -> InputStream) -> T,
  ): T {
    val body = object : RequestBody() {
      override fun contentType(): MediaType = contentType.toMediaType()
      override fun contentLength(): Long = length
      override fun isOneShot(): Boolean = true
      override fun writeTo(sink: BufferedSink) = write(sink.outputStream())
    }
    return post(url, headers, body, limits, transport, read)
  }

  /** POST of a prepared OkHttp body (for example, a streaming [okhttp3.MultipartBody]). */
  suspend fun <T> post(
    url: String,
    headers: Map<String, String>,
    body: RequestBody,
    limits: Limits,
    transport: Interceptor? = null,
    read: (Int, () -> InputStream) -> T,
  ): T {
    val request = Request.Builder().url(url).post(body)
      .tag(Limits::class.java, limits)
      .apply { headers.forEach { (name, value) -> header(name, value) } }
      .build()
    return execute(client.with(transport), request, limits) { response ->
      read(response.code) { response.body?.byteStream() ?: java.io.ByteArrayInputStream(byteArrayOf()) }
    }
  }

  /**
   * Runs the call on OkHttp's dispatcher and suspends until it ends. Cancellation cancels the call at once and
   * the coroutine stops waiting, even while the system DNS is still blocked: the late answer is closed and dropped.
   */
  private suspend fun <T> execute(client: OkHttpClient, request: Request, limits: Limits, handle: (Response) -> T): T {
    val call = client.newCall(request)
    call.timeout().timeout(limits.callMillis, TimeUnit.MILLISECONDS)
    return suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
          val outcome = try {
            Result.success(response.use(handle))
          } catch (error: Throwable) {
            Result.failure(error)
          }
          if (!continuation.isActive) return
          outcome.fold(continuation::resume, continuation::resumeWithException)
        }
      })
    }
  }
}

internal fun InputStream.readBounded(limit: Int, tooLarge: () -> Exception): ByteArray {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(4096)
  while (true) {
    val count = read(buffer)
    if (count < 0) return output.toByteArray()
    if (output.size() + count > limit) throw tooLarge()
    output.write(buffer, 0, count)
  }
}
