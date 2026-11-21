package com.example.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WeatherIntentTest {
  private val parser = PhoneCommandParser()
  private val temperature = PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE))
  private val feelsLike = PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE, WeatherIntentParser.FEELS_LIKE))
  private val rain = PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.WEATHER_RAIN))

  @Test fun frasesDeTemperaturaSonLocales() {
    for (text in listOf("temperatura", "¿Qué temperatura hace?", "qué temperatura hace ahora", "Cuál es la temperatura",
      "¿Cuántos grados hace?", "¿cómo está la temperatura?", "Decime la temperatura", "dime la temperatura, por favor",
      "¿Podés decirme qué temperatura hace?", "¿me podrías decir cuántos grados hay?", "Por favor, ¿qué temperatura hace?",
      "¿Qué temperatura hay en Buenos Aires?", "qué temperatura hace en capital federal", "quiero saber la temperatura",
      "che, ¿cuántos grados hace hoy?", "¿me decís la temperatura?", "Temperatura actual")) {
      assertEquals(text, temperature, parser.parse(text))
    }
  }

  @Test fun sensacionTermicaLlevaSuArgumento() {
    for (text in listOf("sensación térmica", "¿Cuál es la sensación térmica?", "decime la sensación térmica", "¿qué sensación térmica hay?",
      "cuál es la sensación térmica en Buenos Aires")) {
      assertEquals(text, feelsLike, parser.parse(text))
    }
  }

  @Test fun frasesDeLluviaSonLocales() {
    for (text in listOf("lluvia", "¿Va a llover?", "va a llover hoy", "¿Qué probabilidad de lluvia hay?", "probabilidad de lluvia",
      "¿Hay pronóstico de lluvia?", "¿va a llover en las próximas horas?", "¿Qué chances de lluvia hay?", "Decime si va a llover",
      "¿Cómo viene la lluvia para las próximas horas?", "¿podés decirme si va a llover?", "¿sabés si va a llover?",
      "va a llover o no", "¿Llueve?", "probabilidad de precipitaciones", "¿va a llover en Capital?", "necesito saber si va a llover")) {
      assertEquals(text, rain, parser.parse(text))
    }
  }

  @Test fun menciones_explicaciones_negaciones_y_otros_pedidos_no_activan() {
    for (text in listOf("lluvia de ideas", "explicame qué es la sensación térmica", "escribí un cuento sobre la lluvia",
      "no consultes la temperatura", "quiero cambiar el formato de temperatura", "si llueve", "temperatura de fusión del hierro",
      "qué calor hace", "la temperatura del agua", "prendé la linterna si va a llover")) {
      val route = parser.parse(text)
      assertFalse(text, route is PhoneRoute.Local)
      assertFalse(text, route is PhoneRoute.Unsupported && route.message.contains("SMN"))
    }
  }

  @Test fun otroDiaLugarOPasadoSeExplicaSinCambiarLaConsulta() {
    for (text in listOf("qué temperatura hubo ayer", "va a llover el sábado", "¿va a llover mañana?", "¿Qué temperatura hace en Córdoba?",
      "va a llover en Rosario", "probabilidad de lluvia para el fin de semana", "¿cuál es la temperatura máxima de hoy?",
      "temperatura mínima", "va a llover esta noche", "¿llovió ayer?")) {
      val route = parser.parse(text)
      assertTrue("$text -> $route", route is PhoneRoute.Unsupported && !route.recoverable && route.message.contains("SMN"))
    }
    assertEquals(WeatherIntentParser.RAIN_SCOPE, (parser.parse("va a llover el sábado") as PhoneRoute.Unsupported).message)
    assertEquals("Por ahora solo consulto el SMN para Capital Federal y las próximas horas.", WeatherIntentParser.RAIN_SCOPE)
  }

  @Test fun lasPreguntasIndirectasNoSonCondiciones() {
    assertNull(RequestSafetyPolicy.rejection("decime si va a llover"))
    assertNull(RequestSafetyPolicy.rejection("¿Qué temperatura hace?"))
    assertNotNull(RequestSafetyPolicy.rejection("prendé la linterna si va a llover"))
    assertNotNull(RequestSafetyPolicy.rejection("no consultes la temperatura"))
  }

  @Test fun elCoordinadorRespondeLocalSinGeminiNiGroqNiInstinct() = runBlocking {
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { error("Gemini Decisions must not be called") }),
      StructuredCommandExtractor { error("No extraction expected") })
    for ((text, expected) in listOf("¿Qué temperatura hace?" to temperature.command, "Decime si va a llover" to rain.command,
      "¿Cuál es la sensación térmica?" to feelsLike.command)) {
      val result = coordinator.interpret(InterpretationRequest(text), parser.parse(text))
      assertEquals(text, InterpretationResult.Direct(expected), result)
    }
    val rejected = coordinator.interpret(InterpretationRequest("va a llover el sábado"), parser.parse("va a llover el sábado"))
    assertEquals(InterpretationResult.Rejected(WeatherIntentParser.RAIN_SCOPE, RejectionReason.OUT_OF_SCOPE), rejected)
  }

  @Test fun registroCodecYCatalogo() {
    val registry = LocalIntentRegistry.default
    val ids = setOf("weather_temperature", "weather_rain")
    assertTrue(registry.definitions.map { it.id }.containsAll(ids))
    assertTrue(registry.definitions.filter { it.id in ids }.all { it.category == IntentCategory.WEATHER && !it.decisions })
    assertTrue("Local-only: never offered to Gemini Decisions", ids.none { it in CapabilityCatalog.choices })
    assertEquals(temperature, registry.resolve("weather_temperature", "qué temperatura hace"))
    assertEquals(rain, registry.resolve("weather_rain", "decime si va a llover"))
    for (command in listOf(temperature.command, feelsLike.command, rain.command)) {
      assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
    }
    for (invalid in listOf(PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE, "Córdoba"),
      PhoneCommand.Platform(PlatformAction.WEATHER_RAIN, "4864"), PhoneCommand.Platform(PlatformAction.WEATHER_RAIN, content = "x"))) {
      assertTrue(invalid.toString(), runCatching { PhoneCommandCodec.decode(PhoneCommandCodec.encode(invalid)) }.isFailure)
    }
    assertTrue(PhoneCommandCodec.summary(rain.command).contains("SMN"))
  }

  @Test fun quedanFueraDeSecuenciasYRutinas() {
    assertTrue(runCatching { WorkflowRunner().prepare(listOf(rain.command)) }.isFailure)
    assertTrue(runCatching { WorkflowRunner().prepare(listOf(PhoneCommand.Torch(true), temperature.command)) }.isFailure)
    val routine = """[{"name":"Mañana","commands":[{"type":"platform","action":"WEATHER_RAIN"}]}]"""
    assertTrue(runCatching { ConfiguredRoutineStore.decode(routine) }.isFailure)
    assertTrue(WorkflowRunner.isWeatherQuery(temperature.command))
    assertFalse(WorkflowRunner.isWeatherQuery(PhoneCommand.Platform(PlatformAction.BATTERY)))
  }
}
