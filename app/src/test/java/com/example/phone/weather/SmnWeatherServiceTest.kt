package com.example.phone.weather

import com.example.phone.PhoneCommand
import com.example.phone.PlatformAction
import com.example.phone.WeatherIntentParser
import com.example.phone.weather.SmnTestSupport.jwt
import com.example.phone.weather.SmnTestSupport.portal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmnWeatherServiceTest {
  private val zip = fixture("tiepre-20261120.zip")
  private val forecastJson = String(fixture("forecast-4864.unverified.json"), Charsets.UTF_8)
  private val temperature = PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE)
  private val feelsLike = PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE, WeatherIntentParser.FEELS_LIKE)
  private val rain = PhoneCommand.Platform(PlatformAction.WEATHER_RAIN)

  private fun service(clock: Clock, station: String = SmnWeatherService.DEFAULT_STATION,
                      forecast: String = forecastJson, fallback: Boolean = false,
                      handler: (suspend (String) -> SmnHttpResponse)? = null): Pair<SmnWeatherService, FakeTransport> {
    val transport = FakeTransport { url, _ ->
      handler?.invoke(url) ?: when {
        url.startsWith(OPEN_DATA) -> ok(zip, "application/octet-stream")
        url.startsWith(PORTAL) -> html(portal(jwt("""{"exp":${clock.now / 1000 + 3600}}""")))
        url.startsWith(OPEN_METEO) -> json(OPEN_METEO_SAMPLE)
        else -> json(forecast)
      }
    }
    return SmnWeatherService(transport, clock::read, Dispatchers.Unconfined, station,
      fallback = if (fallback) OpenMeteoFallback(transport) else null) to transport
  }

  @Test fun temperaturaConSensacionTermica() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 12, 40)))
    val reply = service.answer(temperature)
    assertTrue(reply.answered)
    assertEquals("Según el SMN, en Buenos Aires hay 28,3 grados (medición de las 12:00). La sensación térmica es de 28,4.", reply.text)
    assertEquals("Según el SMN, la sensación térmica en Buenos Aires es de 28,4 grados (medición de las 12:00).", service.answer(feelsLike).text)
  }

  @Test fun noSeCalculaNoSeInventa() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 12, 40)), station = "Aeroparque Buenos Aires")
    assertEquals("Según el SMN, en Aeroparque Buenos Aires hay 25,5 grados (medición de las 12:00).", service.answer(temperature).text)
    assertEquals("El SMN no calcula la sensación térmica para Aeroparque Buenos Aires en la medición de las 12:00.", service.answer(feelsLike).text)
  }

  @Test fun unaMedicionDeMasDeTresHorasNoSePresentaComoActual() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 15, 1)))
    val reply = service.answer(temperature)
    assertEquals("La última medición del SMN para Buenos Aires es de hace más de tres horas; no la informo como temperatura actual.", reply.text)
    assertFalse(reply.text.contains("28,3"))
    val (pigue, _) = service(Clock(smnTime(2026, 11, 20, 12, 40)), station = "Pigué")
    assertTrue(pigue.answer(temperature).text.startsWith("La última medición del SMN para Pigué"))
  }

  @Test fun estacionAusenteYBloqueoSeInformanSinReintentar() = runBlocking {
    val (missing, _) = service(Clock(smnTime(2026, 11, 20, 12, 40)), station = "Observatorio Inexistente")
    assertFalse(missing.answer(temperature).answered)
    val (blocked, transport) = service(Clock(smnTime(2026, 11, 20, 12, 40))) { html(CLOUDFLARE_BLOCK, 403) }
    repeat(2) {
      val reply = blocked.answer(temperature)
      assertFalse(reply.answered)
      assertEquals(SmnWeatherText.temperatureFailure, reply.text)
    }
    assertEquals(1, transport.calls.size)
  }

  @Test fun cacheDeCincoMinutosQueNoCruzaLaHoraSiguiente() = runBlocking {
    val clock = Clock(smnTime(2026, 11, 20, 12, 40))
    val (service, transport) = service(clock)
    service.answer(temperature); service.answer(feelsLike)
    assertEquals(1, transport.calls.size)
    clock.now += 4 * 60_000L
    service.answer(temperature)
    assertEquals(1, transport.calls.size)
    clock.now += 2 * 60_000L
    service.answer(temperature)
    assertEquals(2, transport.calls.size)
    clock.now = smnTime(2026, 11, 20, 12, 58)
    service.answer(temperature)
    assertEquals(3, transport.calls.size)
    clock.now = smnTime(2026, 11, 20, 13, 0)
    service.answer(temperature)
    assertEquals("A new hourly file may exist after 13:00", 4, transport.calls.size)
  }

  @Test fun pedidosConcurrentesCompartenUnaDescarga() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val clock = Clock(smnTime(2026, 11, 20, 12, 40))
    val transport = FakeTransport { _, _ -> gate.await(); ok(zip, "application/octet-stream") }
    val service = SmnWeatherService(transport, clock::read, Dispatchers.Default)
    val replies = (1..4).map { async(Dispatchers.Default) { service.answer(temperature) } }
    kotlinx.coroutines.delay(50)
    gate.complete(Unit)
    assertTrue(replies.awaitAll().all { it.answered })
    assertEquals(1, transport.calls.size)
  }

  @Test fun lluviaParaLaTardeYLaNoche() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 13)))
    val reply = service.answer(rain)
    assertTrue(reply.answered)
    assertEquals("Según el SMN, en Capital Federal, para esta tarde (12 a 18) hay entre un 40 y un 70 por ciento de probabilidad de precipitaciones. " +
      "Para la noche (18 a 24), entre un 10 y un 40 por ciento.", reply.text)
  }

  @Test fun ceroPublicadoSeDiceComoCero() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 7)))
    assertEquals("En Capital Federal, para esta mañana (6 a 12), el SMN indica un cero por ciento de probabilidad de precipitaciones. " +
      "Para la tarde (12 a 18), entre un 40 y un 70 por ciento.", service.answer(rain).text)
  }

  @Test fun laVentanaQueCruzaLaMedianocheNombraElDiaSiguiente() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 21)))
    assertEquals("Según el SMN, en Capital Federal, para esta noche (18 a 24) hay entre un 10 y un 40 por ciento de probabilidad de precipitaciones. " +
      "Para mañana a la madrugada (0 a 6), entre un cero y un 10 por ciento.", service.answer(rain).text)
  }

  @Test fun sinPorcentajeNoSeDeduceNiSeTrataComoCero() = runBlocking {
    // 21/11 06:00–12:00 has rain06h but no rain_prob_range; 12–18 is null; 18–24 has rain_prob_range null.
    val (service, _) = service(Clock(smnTime(2026, 11, 21, 8)))
    val reply = service.answer(rain)
    assertEquals("El SMN no informa una probabilidad de precipitaciones para Capital Federal en las próximas horas.", reply.text)
    assertFalse(reply.text.contains("cero"))
    assertFalse(reply.text.contains("no va a llover"))
  }

  @Test fun pronosticoViejoBloqueoYCache() = runBlocking {
    val (stale, _) = service(Clock(smnTime(2026, 11, 21, 9, 1)))
    assertFalse(stale.answer(rain).answered)

    val clock = Clock(smnTime(2026, 11, 20, 13))
    val (service, transport) = service(clock)
    service.answer(rain)
    clock.now += 14 * 60_000L
    service.answer(rain)
    assertEquals(1, transport.count(API))
    clock.now += 2 * 60_000L
    service.answer(rain)
    assertEquals(2, transport.count(API))
    assertEquals("Token reused", 1, transport.count(PORTAL))

    val (blocked, blockedTransport) = service(Clock(smnTime(2026, 11, 20, 13))) { url ->
      if (url.startsWith(PORTAL)) html(CLOUDFLARE_BLOCK, 403) else json(forecastJson)
    }
    repeat(2) { assertEquals(SmnWeatherText.rainFailure, blocked.answer(rain).text) }
    assertEquals(1, blockedTransport.calls.size)

    val (failing, _) = service(Clock(smnTime(2026, 11, 20, 13))) { url ->
      if (url.startsWith(PORTAL)) throw SmnException(SmnErrorKind.TIMEOUT, "lento") else json(forecastJson)
    }
    assertEquals(SmnWeatherText.rainFailure, failing.answer(rain).text)
  }

  @Test fun laVentanaTomaComoMaximoDosBloquesYDescartaLosTerminados() {
    val (service, _) = service(Clock(0))
    val periods = SmnForecastParser.parse(forecastJson, 4864).periods
    val at = smnTime(2026, 11, 20, 17, 59)
    assertEquals(listOf(SmnPeriod.AFTERNOON, SmnPeriod.NIGHT), service.window(periods, at).map { it.period })
    assertEquals(listOf(SmnPeriod.NIGHT), service.window(periods, smnTime(2026, 11, 20, 18)).map { it.period })
    assertTrue(service.window(periods, smnTime(2026, 11, 22, 0)).isEmpty())
  }

  @Test fun conElSmnCaidoRespondeOpenMeteoYReintentaElSmnEnLaProximaConsulta() = runBlocking {
    val clock = Clock(smnTime(2026, 11, 20, 15, 10))
    val (service, transport) = service(clock, fallback = true) { url ->
      if (url.startsWith(OPEN_METEO)) json(OPEN_METEO_SAMPLE) else html(SMN_OUTAGE_503, 503)
    }
    val reply = service.answer(temperature)
    assertTrue(reply.answered)
    assertEquals("Según Open-Meteo (el SMN no está respondiendo), en Buenos Aires hay 24,4 grados (dato de las 15:00). " +
      "La sensación térmica es de 24,2.", reply.text)
    assertEquals("Según Open-Meteo (el SMN no está respondiendo), la sensación térmica en Buenos Aires es de 24,2 grados (dato de las 15:00).",
      service.answer(feelsLike).text)
    assertEquals("An outage is not a block: no cooldown", 2, transport.count(OPEN_DATA))
    assertEquals("Backup cached", 1, transport.count(OPEN_METEO))

    val rainReply = service.answer(rain)
    assertTrue(rainReply.answered)
    assertEquals("Según Open-Meteo (el SMN no está respondiendo), en Buenos Aires entre las 15:00 y las 20:00 la probabilidad " +
      "de lluvia va de un 46 a un 75 por ciento; la más alta es a las 17:00.", rainReply.text)
    assertFalse(rainReply.text.contains("no permitió"))
  }

  @Test fun unaMedicionViejaDelSmnUsaElRespaldo() = runBlocking {
    val (service, _) = service(Clock(smnTime(2026, 11, 20, 15, 30)), fallback = true)
    assertTrue(service.answer(temperature).text.startsWith("Según Open-Meteo"))
  }
}
