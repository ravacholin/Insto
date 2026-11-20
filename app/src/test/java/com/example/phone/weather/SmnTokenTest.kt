package com.example.phone.weather

import com.example.phone.weather.SmnTestSupport.jwt
import com.example.phone.weather.SmnTestSupport.portal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmnTokenTest {
  private val now = smnTime(2026, 11, 20, 12)

  @Test fun extraeElJwtConComillasSimples() {
    val token = jwt()
    val extracted = SmnTokenExtractor.extract(portal(token, '\''), now)!!
    assertEquals(token, extracted.value)
    assertEquals(now + SmnTokenExtractor.DEFAULT_LIFETIME_MILLIS, extracted.expiresAtMillis)
  }

  @Test fun extraeElJwtConComillasDobles() {
    val token = jwt()
    assertEquals(token, SmnTokenExtractor.extract(portal(token, '"'), now)!!.value)
    assertEquals(token, SmnTokenExtractor.extract("localStorage.setItem( \"token\" ,'$token' )", now)!!.value)
  }

  @Test fun unaPaginaSinTokenNoDevuelveNada() {
    assertNull(SmnTokenExtractor.extract("<html><script>localStorage.setItem('otro', 'a.b.c')</script></html>", now))
    assertNull(SmnTokenExtractor.extract("<html>Pronóstico</html>", now))
  }

  @Test fun rechazaUnJwtMalformado() {
    for (raw in listOf("a.b", "!!!.e30.x", Base64Url.encode("no json".toByteArray()) + "." + Base64Url.encode("{}".toByteArray()) + ".x",
      Base64Url.encode("{}".toByteArray()) + "." + Base64Url.encode("{}".toByteArray()) + ".x")) {
      val error = runCatching { SmnTokenExtractor.validate(raw, now) }.exceptionOrNull() as? SmnException
      assertEquals(raw, SmnErrorKind.MALFORMED, error?.kind)
    }
    val bad = jwt(payload = """{"exp":"mañana"}""")
    assertEquals(SmnErrorKind.MALFORMED, (runCatching { SmnTokenExtractor.validate(bad, now) }.exceptionOrNull() as SmnException).kind)
  }

  @Test fun usaElVencimientoDelPayloadConDosMinutosDeMargenYRechazaUnoVencido() {
    val exp = now / 1000 + 3600
    assertEquals(exp * 1000 - 120_000, SmnTokenExtractor.validate(jwt("""{"exp":$exp}"""), now).expiresAtMillis)
    val expired = jwt("""{"exp":${now / 1000 + 60}}""") // inside the renewal margin
    assertEquals(SmnErrorKind.UNAUTHORIZED, (runCatching { SmnTokenExtractor.validate(expired, now) }.exceptionOrNull() as SmnException).kind)
    val past = jwt("""{"exp":${now / 1000 - 10}}""")
    assertEquals(SmnErrorKind.UNAUTHORIZED, (runCatching { SmnTokenExtractor.validate(past, now) }.exceptionOrNull() as SmnException).kind)
  }

  @Test fun elTokenNoApareceEnTextosDeDiagnostico() {
    val token = jwt()
    val extracted = SmnTokenExtractor.extract(portal(token), now)!!
    assertFalse(extracted.toString().contains(token))
    assertFalse(extracted.toString().contains("firma"))
  }

  @Test fun reutilizaElTokenHastaPocoAntesDeVencerYLuegoLoRenueva() = runBlocking {
    val clock = Clock(now)
    // Each home-page request serves a token valid for one hour from that moment.
    val transport = FakeTransport { _, _ -> html(portal(jwt("""{"exp":${clock.now / 1000 + 3600}}"""))) }
    val provider = SmnTokenProvider(transport, SmnCooldown(clock::read), clock::read)
    val first = provider.token()
    assertSame(first, provider.token())
    assertEquals(1, transport.calls.size)
    clock.now = first.expiresAtMillis
    provider.token()
    assertEquals(2, transport.calls.size)
    assertEquals(PORTAL, transport.calls.first().url)
    assertNull(transport.calls.first().headers["Authorization"])
  }

  @Test fun unaSolaObtencionEnCursoParaPedidosConcurrentes() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val transport = FakeTransport { _, _ -> gate.await(); html(portal(jwt())) }
    val provider = SmnTokenProvider(transport, SmnCooldown({ now }), { now })
    val requests = (1..5).map { async(Dispatchers.Default) { provider.token() } }
    kotlinx.coroutines.delay(50)
    gate.complete(Unit)
    val tokens = requests.awaitAll()
    assertEquals(1, transport.calls.size)
    assertTrue(tokens.all { it === tokens.first() })
  }

  @Test fun portadaSinTokenEsNoAutorizadoYBloqueoActivaElEnfriamiento() = runBlocking {
    val cooldown = SmnCooldown({ now })
    val empty = SmnTokenProvider(FakeTransport { _, _ -> html("<html>sin token</html>") }, cooldown, { now })
    assertEquals(SmnErrorKind.UNAUTHORIZED, (runCatching { empty.token() }.exceptionOrNull() as SmnException).kind)
    val blocked = FakeTransport { _, _ -> html(CLOUDFLARE_BLOCK, 403) }
    val provider = SmnTokenProvider(blocked, cooldown, { now })
    assertEquals(SmnErrorKind.BLOCKED, (runCatching { provider.token() }.exceptionOrNull() as SmnException).kind)
    assertEquals(SmnErrorKind.BLOCKED, (runCatching { provider.token() }.exceptionOrNull() as SmnException).kind)
    assertEquals("No se vuelve a pedir la portada durante el enfriamiento", 1, blocked.calls.size)
  }

  @Test fun soloSeAdmitenLosHostsDelSmnPorHttps() {
    assertEquals("ws1.smn.gob.ar", SmnHosts.host("https://ws1.smn.gob.ar/v1/forecast/location/4864"))
    for (url in listOf("http://ws1.smn.gob.ar/v1", "https://ws1.smn.gob.ar.evil.example/v1", "https://user@ws1.smn.gob.ar/",
      "https://ws1.smn.gob.ar:8443/", "https://ws2.smn.gob.ar/", "https://api.open-meteo.com/")) {
      assertTrue(url, runCatching { SmnHosts.host(url) }.isFailure)
    }
  }

  @Test fun base64UrlIdaYVuelta() {
    for (text in listOf("", "a", "ab", "abc", "{\"exp\":1}", "ñandú")) {
      assertEquals(text, String(Base64Url.decode(Base64Url.encode(text.toByteArray()))!!))
    }
    assertNull(Base64Url.decode("a+b/"))
  }
}
