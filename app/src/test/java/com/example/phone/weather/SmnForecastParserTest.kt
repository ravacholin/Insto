package com.example.phone.weather

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Fixture structure is NOT verified against a live ws1 response; see resources/smn/README.md. */
class SmnForecastParserTest {
  private val forecast = SmnForecastParser.parse(String(fixture("forecast-4864.unverified.json"), Charsets.UTF_8), 4864)

  @Test fun leeLocalidadActualizacionYSoloLosBloquesPublicados() {
    assertEquals("Capital Federal", forecast.locationName)
    assertEquals(4864, forecast.locationId)
    assertEquals(smnTime(2026, 11, 20, 9), forecast.updatedAtMillis)
    // 20/11 early_morning and 21/11 afternoon are null: left out, not assumed.
    assertEquals(listOf(
      "2026-11-20 MORNING", "2026-11-20 AFTERNOON", "2026-11-20 NIGHT",
      "2026-11-21 EARLY_MORNING", "2026-11-21 MORNING", "2026-11-21 NIGHT",
    ), forecast.periods.map { "${it.date} ${it.period}" })
  }

  @Test fun probabilidadesPublicadasSinDeducciones() {
    val byKey = forecast.periods.associateBy { "${it.date} ${it.period}" }
    assertEquals(RainProbability.Exact(0), byKey.getValue("2026-11-20 MORNING").probability)
    assertEquals(RainProbability.Range(40, 70), byKey.getValue("2026-11-20 AFTERNOON").probability)
    assertEquals("[40,70]", byKey.getValue("2026-11-20 AFTERNOON").originalRange)
    // rain06h = 3.1 mm is not a probability.
    assertEquals(RainProbability.Missing, byKey.getValue("2026-11-21 MORNING").probability)
    assertNull(byKey.getValue("2026-11-21 MORNING").originalRange)
    assertEquals(RainProbability.Missing, byKey.getValue("2026-11-21 NIGHT").probability)
    val night = byKey.getValue("2026-11-20 NIGHT")
    assertEquals(smnTime(2026, 11, 20, 18), night.startMillis)
    assertEquals(smnTime(2026, 11, 21, 0), night.endMillis)
  }

  @Test fun rangosInvalidosSonAusentes() {
    for (raw in listOf("[]", "[10]", "[10,20,30]", "[70,40]", "[-1,10]", "[10,101]", "[10.5,20]", "[\"10\",\"20\"]", "[null,10]")) {
      assertEquals(raw, RainProbability.Missing, SmnForecastParser.probability(JSONArray(raw)))
    }
    assertEquals(RainProbability.Missing, SmnForecastParser.probability(null))
    assertEquals(RainProbability.Exact(100), SmnForecastParser.probability(JSONArray("[100,100]")))
  }

  @Test fun fechasConYSinZonaHoraria() {
    val local = smnTime(2026, 11, 20, 9)
    assertEquals(local, SmnForecastParser.instant("2026-11-20T09:00:00"))
    assertEquals(local, SmnForecastParser.instant("2026-11-20T12:00:00Z"))
    assertEquals(local, SmnForecastParser.instant("2026-11-20T09:00:00.000-03:00"))
    assertEquals(local, SmnForecastParser.instant("2026-11-20T09:00:00-0300"))
    assertNull(SmnForecastParser.instant("20/11/2026 09:00"))
    assertNull(SmnForecastParser.instant("2026-02-30T09:00:00"))
  }

  @Test fun sinActualizacionOSinDiasEsMalformado() {
    for (text in listOf("""{"forecast":[]}""", """{"updated":"ayer","forecast":[]}""", """{"updated":"2026-11-20T09:00:00"}""")) {
      assertEquals(text, SmnErrorKind.MALFORMED, (runCatching { SmnForecastParser.parse(text, 4864) }.exceptionOrNull() as SmnException).kind)
    }
    val unnamed = SmnForecastParser.parse("""{"updated":"2026-11-20T09:00:00","location":{"id":4864},"forecast":[]}""", 4864)
    assertNull(unnamed.locationName)
  }
}
