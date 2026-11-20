package com.example.phone

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderHttpTest {
  @Test fun reutilizaLaConexionYSoloEnviaUnaVezAnteRedireccionOErrorTemporal() = runBlocking {
    val ports = CopyOnWriteArrayList<Int>()
    val paths = CopyOnWriteArrayList<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.use {
        exchange.requestBody.readBytes()
        ports.add(exchange.remoteAddress.port)
        val path = exchange.requestURI.path
        paths.add(path)
        val status = when (path) {
          "/redirigir" -> {
            exchange.responseHeaders.set("Location", "/destino")
            307
          }
          "/temporal" -> {
            exchange.responseHeaders.set("Retry-After", "0")
            503
          }
          else -> 200
        }
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
      }
    }
    server.start()
    try {
      suspend fun post(path: String) = ProviderHttp.post(
        "http://127.0.0.1:${server.address.port}$path", emptyMap(), "application/json", 2,
        ProviderHttp.Limits(1_000, 1_000, 1_000, 3_000), write = { it.write("{}".toByteArray()) },
      ) { status, input -> input().use { it.readBytes() }; status }
      assertEquals(200, post("/uno"))
      assertEquals(200, post("/dos"))
      assertEquals(ports[0], ports[1])
      assertEquals(307, post("/redirigir"))
      assertEquals(503, post("/temporal"))
      assertEquals(listOf("/uno", "/dos", "/redirigir", "/temporal"), paths)
    } finally {
      server.stop(0)
    }
  }

  private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange -> handler(exchange) }
      executor = java.util.concurrent.Executors.newCachedThreadPool()
      start()
    }

  private fun HttpServer.url(path: String = "/") = "http://127.0.0.1:${address.port}$path"

  private suspend fun postBytes(url: String, size: Int, limits: ProviderHttp.Limits): Int {
    val chunk = ByteArray(64 * 1024)
    return ProviderHttp.post(url, emptyMap(), "application/octet-stream", size.toLong(), limits, write = { out ->
      var left = size
      while (left > 0) { val n = minOf(left, chunk.size); out.write(chunk, 0, n); left -= n }
    }) { status, input -> input().use { it.readBytes() }; status }
  }

  @Test fun everyBudgetIsFiniteAndFitsItsCaller() {
    for (budget in listOf(1_500L, 4_000L, 6_000L, 8_000L, 12_000L)) {
      val limits = ProviderHttp.Limits.json(budget)
      assertTrue(limits.connectMillis in 1..budget.toInt())
      assertTrue(limits.writeMillis in 1..budget.toInt())
      assertTrue(limits.readMillis in 1..budget.toInt())
      assertEquals(budget + ProviderHttp.Limits.GRACE_MILLIS, limits.callMillis)
    }
    for (limits in listOf(ProviderHttp.Limits.UPLOAD, ProviderHttp.Limits.DEFAULT)) {
      assertTrue(limits.connectMillis > 0 && limits.writeMillis > 0 && limits.readMillis > 0 && limits.callMillis > 0)
    }
    assertTrue(runCatching { ProviderHttp.Limits(1, 0, 1, 1) }.isFailure)
  }

  @Test fun statusAndErrorClassificationIsCentral() {
    val expected = mapOf(200 to null, 204 to null, 401 to ProviderHttp.FailureKind.AUTH, 403 to ProviderHttp.FailureKind.AUTH,
      429 to ProviderHttp.FailureKind.QUOTA, 408 to ProviderHttp.FailureKind.TIMEOUT, 500 to ProviderHttp.FailureKind.SERVER,
      502 to ProviderHttp.FailureKind.SERVER, 503 to ProviderHttp.FailureKind.SERVER, 504 to ProviderHttp.FailureKind.SERVER,
      404 to ProviderHttp.FailureKind.UNAVAILABLE, 501 to ProviderHttp.FailureKind.UNAVAILABLE,
      400 to ProviderHttp.FailureKind.INVALID_REQUEST, 302 to ProviderHttp.FailureKind.INVALID_RESPONSE)
    expected.forEach { (code, kind) -> assertEquals("$code", kind, ProviderHttp.classifyStatus(code)) }
    assertEquals(ProviderHttp.FailureKind.TIMEOUT, ProviderHttp.classify(SocketTimeoutException()))
    assertEquals(ProviderHttp.FailureKind.TIMEOUT, ProviderHttp.classify(InterruptedIOException("timeout")))
    assertEquals(ProviderHttp.FailureKind.NETWORK, ProviderHttp.classify(IOException("reset")))
    assertEquals(ProviderHttp.FailureKind.TOO_LARGE, ProviderHttp.classify(ProviderHttp.ResponseTooLarge()))
    assertEquals(ProviderHttp.FailureKind.CANCELLED, ProviderHttp.classify(kotlinx.coroutines.CancellationException()))
    assertEquals(ProviderHttp.FailureKind.INVALID_RESPONSE, ProviderHttp.classify(org.json.JSONException("x")))
  }

  @Test fun readTimeoutEndsAStalledResponseWithoutResending() = runBlocking {
    val requests = AtomicInteger()
    val release = CountDownLatch(1)
    val server = server { exchange -> exchange.use { requests.incrementAndGet(); it.requestBody.readBytes(); release.await(5, TimeUnit.SECONDS) } }
    try {
      val started = System.nanoTime()
      val error = runCatching { postBytes(server.url(), 2, ProviderHttp.Limits(1_000, 1_000, 300, 5_000)) }.exceptionOrNull()
      assertEquals(ProviderHttp.FailureKind.TIMEOUT, ProviderHttp.classify(error!!))
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
      assertEquals(1, requests.get())
    } finally { release.countDown(); server.stop(0) }
  }

  @Test fun writeTimeoutEndsAnUploadTheServerStoppedReading() = runBlocking {
    val requests = AtomicInteger()
    val release = CountDownLatch(1)
    // The handler never reads the body: once the socket buffers fill, every write stalls.
    val server = server { exchange -> requests.incrementAndGet(); release.await(10, TimeUnit.SECONDS); exchange.close() }
    try {
      val started = System.nanoTime()
      val error = runCatching { postBytes(server.url(), 64 * 1024 * 1024, ProviderHttp.Limits(1_000, 300, 1_000, 8_000)) }.exceptionOrNull()
      assertTrue("$error", error is IOException)
      assertTrue(ProviderHttp.classify(error!!) in setOf(ProviderHttp.FailureKind.TIMEOUT, ProviderHttp.FailureKind.NETWORK))
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 6_000)
      assertTrue(requests.get() <= 1)
    } finally { release.countDown(); server.stop(0) }
  }

  @Test fun wholeCallDeadlineBoundsASlowDripResponse() = runBlocking {
    val stop = CountDownLatch(1)
    val server = server { exchange ->
      exchange.use {
        it.requestBody.readBytes()
        it.sendResponseHeaders(200, 0)
        // Each byte arrives inside the read timeout; the call never ends on its own.
        while (!stop.await(100, TimeUnit.MILLISECONDS)) runCatching { it.responseBody.write(1); it.responseBody.flush() }.onFailure { return@use }
      }
    }
    try {
      val started = System.nanoTime()
      val error = runCatching { postBytes(server.url(), 2, ProviderHttp.Limits(1_000, 1_000, 500, 800)) }.exceptionOrNull()
      assertEquals(ProviderHttp.FailureKind.TIMEOUT, ProviderHttp.classify(error!!))
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    } finally { stop.countDown(); server.stop(0) }
  }

  @Test fun aDroppedConnectionAfterThePostIsNeverResent() = runBlocking {
    val requests = AtomicInteger()
    val server = server { exchange -> requests.incrementAndGet(); exchange.requestBody.readBytes(); exchange.close() }
    try {
      val error = runCatching { postBytes(server.url(), 2, ProviderHttp.Limits(1_000, 1_000, 1_000, 3_000)) }.exceptionOrNull()
      assertTrue("$error", error is IOException)
      assertEquals(ProviderHttp.FailureKind.NETWORK, ProviderHttp.classify(error!!))
      assertEquals("The outcome is uncertain: the POST must not be repeated", 1, requests.get())
    } finally { server.stop(0) }
  }

  @Test fun cancellingDuringAnUploadStopsAtOnceAndSendsNothingElse() = runBlocking {
    val requests = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = server { exchange -> requests.incrementAndGet(); entered.countDown(); release.await(10, TimeUnit.SECONDS); exchange.close() }
    try {
      val job = async(Dispatchers.Default) { postBytes(server.url(), 64 * 1024 * 1024, ProviderHttp.Limits.UPLOAD) }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val started = System.nanoTime()
      job.cancelAndJoin()
      assertTrue(job.isCancelled)
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
      assertEquals(1, requests.get())
    } finally { release.countDown(); server.stop(0) }
  }

  @Test fun aCallerRetryIsExplicitAndSendsTheSameRequestOnce() = runBlocking {
    var status = 503
    val http = FakeHttp { FakeHttp.Reply(status, if (status == 200) """{"ok":true}""" else "") }
    val first = runCatching { GeminiTransport.post({ "key" }, """{"pedido":"igual"}""", "https://generativelanguage.googleapis.com/x", http, 4_000) }
    assertEquals(GeminiFailureKind.SERVER, (first.exceptionOrNull() as DecisionException).kind)
    assertEquals(1, http.exchanges.size)
    status = 200
    assertEquals("""{"ok":true}""", GeminiTransport.post({ "key" }, """{"pedido":"igual"}""", "https://generativelanguage.googleapis.com/x", http, 4_000))
    assertEquals(2, http.exchanges.size)
    assertEquals(http.exchanges[0].bodyText(), http.exchanges[1].bodyText())
  }

  @Test fun declaredOversizedResponsesAreRejectedBeforeReading() = runBlocking {
    val http = FakeHttp { FakeHttp.Reply(200, "x".repeat(2048)) }
    val error = runCatching { ProviderHttp.get("https://ssl.smn.gob.ar/x", emptyMap(), 1_000, 1_000, 1024, http) }.exceptionOrNull()
    assertTrue(error is ProviderHttp.ResponseTooLarge)
    assertTrue(http.closed)
    val small = ProviderHttp.get("https://ssl.smn.gob.ar/x", emptyMap(), 1_000, 1_000, 4096, http)
    assertEquals(2048, small.body.size)
    assertNull(ProviderHttp.classifyStatus(small.code))
  }
}
