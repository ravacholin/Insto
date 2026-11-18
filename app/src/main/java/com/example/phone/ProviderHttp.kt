package com.example.phone

import android.util.Log
import com.example.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

internal object ProviderHttp {
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
      val provider = if (call.request().url.host == "api.groq.com") GeminiTransport.Provider.GROQ else GeminiTransport.Provider.GEMINI
      val millis = total / 1_000_000
      GeminiDiagnostics.connection(provider, millis)
      if (BuildConfig.DEBUG) runCatching { Log.d("InstoVoiceLatency", "${provider.label}_connection_ms=$millis") }
    }
  }

  private data class Timeouts(val connectMillis: Int, val readMillis: Int)

  private val client = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .writeTimeout(0, TimeUnit.MILLISECONDS)
    .eventListenerFactory { Timings() }
    .addNetworkInterceptor { chain ->
      val response = chain.proceed(chain.request())
      // Un HEAD no tiene body para marcar como de un solo envío.
      if (chain.request().method == "HEAD" && response.code == 503)
        response.newBuilder().removeHeader("Retry-After").build()
      else response
    }
    .addInterceptor { chain ->
      val request = chain.request()
      val timeouts = request.tag(Timeouts::class.java)
      val configured = if (timeouts == null) chain else chain
        .withConnectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .withReadTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
      configured.proceed(request)
    }
    .build()

  suspend fun warm(geminiEnabled: Boolean) = coroutineScope {
    for (host in listOfNotNull("api.groq.com", "generativelanguage.googleapis.com".takeIf { geminiEnabled })) {
      launch {
        withTimeoutOrNull(5_000) {
          try {
            val call = client.newCall(Request.Builder().url("https://$host/").head().build())
            cancellable(call::cancel) { call.execute().close() }
          } catch (_: java.io.IOException) { }
        }
      }
    }
  }

  suspend fun <T> post(
    url: String,
    headers: Map<String, String>,
    contentType: String,
    length: Long,
    connectMillis: Int,
    readMillis: Int,
    legacy: (() -> HttpURLConnection)?,
    write: (java.io.OutputStream) -> Unit,
    read: (Int, () -> InputStream) -> T,
  ): T {
    if (legacy != null) {
      val connection = withContext(Dispatchers.IO) { legacy() }
      return cancellable(connection::disconnect) {
        try {
          connection.requestMethod = "POST"
          connection.connectTimeout = connectMillis
          connection.readTimeout = readMillis
          connection.instanceFollowRedirects = false
          connection.doOutput = true
          headers.forEach(connection::setRequestProperty)
          connection.setRequestProperty("Content-Type", contentType)
          if (length < 0) connection.setChunkedStreamingMode(8192) else connection.setFixedLengthStreamingMode(length.toInt())
          connection.outputStream.use(write)
          read(connection.responseCode) { connection.inputStream }
        } finally {
          connection.disconnect()
        }
      }
    }
    val body = object : RequestBody() {
      override fun contentType(): MediaType = contentType.toMediaType()
      override fun contentLength(): Long = length
      override fun isOneShot(): Boolean = true
      override fun writeTo(sink: BufferedSink) = write(sink.outputStream())
    }
    val request = Request.Builder().url(url).post(body)
      .tag(Timeouts::class.java, Timeouts(connectMillis, readMillis))
      .apply { headers.forEach { (name, value) -> header(name, value) } }
      .build()
    val call = client.newCall(request)
    return cancellable(call::cancel) {
      call.execute().use { response ->
        read(response.code) { response.body?.byteStream() ?: java.io.ByteArrayInputStream(byteArrayOf()) }
      }
    }
  }

  private suspend fun <T> cancellable(cancel: () -> Unit, block: () -> T): T = coroutineScope {
    suspendCancellableCoroutine { continuation ->
      val worker = launch(Dispatchers.IO) {
        try {
          continuation.resume(block())
        } catch (error: Exception) {
          continuation.resumeWithException(error)
        }
      }
      continuation.invokeOnCancellation {
        runCatching(cancel)
        worker.cancel()
      }
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
