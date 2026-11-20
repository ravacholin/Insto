package com.example.phone.weather

import com.example.phone.weather.SmnTestSupport.jwt
import com.example.phone.weather.SmnTestSupport.portal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmnForecastClientTest {
  private val now = smnTime(2026, 11, 20, 13)
  private val forecastJson = String(fixture("forecast-4864.unverified.json"), Charsets.UTF_8)
  private val unauthorized = """{"timestamp":"2026-11-20T16:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/v1/forecast/location/4864"}"""

  private fun client(transport: FakeTransport, clock: Clock = Clock(now)): Pair<SmnForecastClient, SmnCooldown> {
    val cooldown = SmnCooldown(clock::read)
    return SmnForecastClient(transport, SmnTokenProvider(transport, cooldown, clock::read), cooldown) to cooldown
  }

  private suspend fun failure(client: SmnForecastClient) =
    (runCatching { client.forecast(4864) }.exceptionOrNull() as SmnException).kind

  @Test fun enviaElTokenSoloAWs1ConAcceptJson() = runBlocking {
    val token = jwt()
    val transport = FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(token)) else json(forecastJson) }
    val forecast = client(transport).first.forecast(4864)
    assertEquals("Capital Federal", forecast.locationName)
    val api = transport.calls.single { it.url.startsWith(API) }
    assertEquals("https://ws1.smn.gob.ar/v1/forecast/location/4864", api.url)
    assertEquals("JWT $token", api.headers["Authorization"])
    assertEquals("application/json", api.headers["Accept"])
    assertEquals(SmnForecastParser.MAX_BYTES, api.maxBytes)
    assertTrue(transport.calls.filterNot { it.url.startsWith(API) }.none { it.headers.containsKey("Authorization") })
  }

  @Test fun un401SeResuelveConUnaSolaRenovacion() = runBlocking {
    var api = 0
    val transport = FakeTransport { url, _ ->
      if (url.startsWith(PORTAL)) html(portal(jwt())) else if (++api == 1) json(unauthorized, 401) else json(forecastJson)
    }
    assertEquals(4864, client(transport).first.forecast(4864).locationId)
    assertEquals(2, transport.count(PORTAL))
    assertEquals(2, transport.count(API))
  }

  @Test fun un401RepetidoEsUnErrorSinBucle() = runBlocking {
    val transport = FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(jwt())) else json(unauthorized, 401) }
    assertEquals(SmnErrorKind.UNAUTHORIZED, failure(client(transport).first))
    assertEquals(2, transport.count(PORTAL))
    assertEquals(2, transport.count(API))
  }

  @Test fun un403OUnDesafioActivanElEnfriamientoSinReintentar() = runBlocking {
    for (blocked in listOf(
      html(CLOUDFLARE_BLOCK, 403),
      html("<html><script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1\"></script>cf-chl</html>", 200),
      SmnHttpResponse(403, mapOf("cf-mitigated" to "challenge", "Content-Type" to "text/html"), "<html></html>".toByteArray()),
    )) {
      val clock = Clock(now)
      val transport = FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(jwt())) else blocked }
      val (client, cooldown) = client(transport, clock)
      assertEquals(SmnErrorKind.BLOCKED, failure(client))
      assertEquals(1, transport.count(API))
      // During the cooldown neither the API nor the home page is requested again.
      assertEquals(SmnErrorKind.BLOCKED, failure(client))
      assertEquals(2, transport.calls.size)
      assertEquals(SmnErrorKind.BLOCKED, cooldown.active(SmnHosts.PORTAL))
      clock.now += 30 * 60_000L
      assertNull(cooldown.active(SmnHosts.API))
    }
  }

  @Test fun unaPortadaBloqueadaDetieneElFlujo() = runBlocking {
    val transport = FakeTransport { _, _ -> html(CLOUDFLARE_BLOCK, 403, mapOf("Server" to "cloudflare")) }
    val (client, _) = client(transport)
    assertEquals(SmnErrorKind.BLOCKED, failure(client))
    assertEquals(SmnErrorKind.BLOCKED, failure(client))
    assertEquals(1, transport.calls.size)
    assertEquals(0, transport.count(API))
  }

  @Test fun un429EsLimiteDeFrecuencia() = runBlocking {
    val transport = FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(jwt())) else json("{}", 429) }
    val (client, _) = client(transport)
    assertEquals(SmnErrorKind.RATE_LIMITED, failure(client))
    assertEquals(SmnErrorKind.RATE_LIMITED, failure(client))
    assertEquals(1, transport.count(API))
  }

  @Test fun un503EsServidorCaidoYSeReintentaEnLaProximaConsulta() = runBlocking {
    val transport = FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(jwt())) else html("<html>503</html>", 503) }
    val (client, _) = client(transport)
    assertEquals(SmnErrorKind.UNAVAILABLE, failure(client))
    assertEquals(SmnErrorKind.UNAVAILABLE, failure(client))
    assertEquals("No cooldown for a server outage", 2, transport.count(API))
  }

  @Test fun noJsonMalformadoDemasiadoGrandeTimeoutYRed() = runBlocking {
    fun with(response: suspend () -> SmnHttpResponse) =
      FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(jwt())) else response() }
    assertEquals(SmnErrorKind.NOT_JSON, failure(client(with { ok("pronóstico".toByteArray(), "text/plain") }).first))
    assertEquals(SmnErrorKind.MALFORMED, failure(client(with { json("{\"forecast\":") }).first))
    assertEquals(SmnErrorKind.MALFORMED, failure(client(with { json("[]") }).first))
    for (kind in listOf(SmnErrorKind.TOO_LARGE, SmnErrorKind.TIMEOUT, SmnErrorKind.NETWORK)) {
      assertEquals(kind, failure(client(with { throw SmnException(kind, "falla") }).first))
    }
  }

  @Test fun losMensajesDeErrorNoIncluyenElToken() = runBlocking {
    val token = jwt()
    val transport = FakeTransport { url, _ -> if (url.startsWith(PORTAL)) html(portal(token)) else json(unauthorized, 401) }
    val error = runCatching { client(transport).first.forecast(4864) }.exceptionOrNull()!!
    assertFalse(error.message.orEmpty().contains(token))
    assertFalse(error.toString().contains("firma"))
  }
}
