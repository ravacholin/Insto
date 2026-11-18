package com.example.phone

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        "http://127.0.0.1:${server.address.port}$path", emptyMap(), "application/json", 2, 1_000, 1_000,
        legacy = null, write = { it.write("{}".toByteArray()) },
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
}
