package com.example.phone.weather

import com.example.phone.GeminiDiagnostics
import com.example.phone.ProviderHttp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Local HTTP server only; no internet. */
class SmnTransportTest {
  private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange -> exchange.use { handler(it) } }
      start()
    }

  @Test fun getAcotadoSinSeguirRedireccionesNiTocarMetricasDeGemini() = runBlocking {
    val seen = mutableListOf<String>()
    val server = server { exchange ->
      seen += exchange.requestURI.path
      when (exchange.requestURI.path) {
        "/redirigir" -> { exchange.responseHeaders.set("Location", "/destino"); exchange.sendResponseHeaders(302, -1) }
        "/grande" -> { val body = ByteArray(2048); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body) }
        else -> {
          val body = "PK".toByteArray()
          exchange.responseHeaders.set("Content-Type", "application/octet-stream")
          exchange.responseHeaders.set("CF-Mitigated", "challenge")
          exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
      }
    }
    val before = GeminiDiagnostics.snapshot()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val response = ProviderHttp.get("$base/datos", emptyMap(), 1_000, 1_000, 1024)
      assertEquals(200, response.code)
      assertEquals("PK", String(response.body))
      assertEquals("challenge", response.headers["cf-mitigated"])
      assertEquals(302, ProviderHttp.get("$base/redirigir", emptyMap(), 1_000, 1_000, 1024).code)
      assertTrue(runCatching { ProviderHttp.get("$base/grande", emptyMap(), 1_000, 1_000, 1024) }.exceptionOrNull() is ProviderHttp.ResponseTooLarge)
      assertEquals(listOf("/datos", "/redirigir", "/grande"), seen)
      assertEquals(before.geminiConnectionMillis, GeminiDiagnostics.snapshot().geminiConnectionMillis)
      assertEquals(before.groqConnectionMillis, GeminiDiagnostics.snapshot().groqConnectionMillis)
      assertNull(ProviderHttp.providerFor("ssl.smn.gob.ar"))
      assertNull(ProviderHttp.providerFor("127.0.0.1"))
    } finally { server.stop(0) }
  }

  @Test fun unaRespuestaLentaVenceElPlazoDeLectura() = runBlocking {
    val server = server { exchange -> Thread.sleep(1_500); exchange.sendResponseHeaders(200, -1) }
    try {
      val error = runCatching {
        ProviderHttp.get("http://127.0.0.1:${server.address.port}/lento", emptyMap(), 500, 300, 1024)
      }.exceptionOrNull()
      assertTrue(error.toString(), error is java.io.InterruptedIOException)
    } finally { server.stop(0) }
  }

  @Test fun elTransporteDeProduccionTipificaErroresDeRedYTamano() = runBlocking {
    val server = server { exchange -> val body = ByteArray(4096); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body) }
    val port = server.address.port
    try {
      val tooLarge = runCatching { OkHttpSmnTransport.get("http://127.0.0.1:$port/", emptyMap(), 1024) }.exceptionOrNull() as SmnException
      assertEquals(SmnErrorKind.TOO_LARGE, tooLarge.kind)
    } finally { server.stop(0) }
    val refused = runCatching { OkHttpSmnTransport.get("http://127.0.0.1:$port/", emptyMap(), 1024) }.exceptionOrNull() as SmnException
    assertEquals(SmnErrorKind.NETWORK, refused.kind)
  }
}
