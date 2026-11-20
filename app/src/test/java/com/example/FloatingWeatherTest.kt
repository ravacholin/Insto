package com.example

import com.example.data.emptyLearnedPhrasesForTest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.phone.DecisionClassifier
import com.example.phone.HybridPhoneRouter
import com.example.phone.PhoneCommand
import com.example.phone.PlatformAction
import com.example.phone.WeatherIntentParser
import com.example.phone.weather.SmnWeatherReply
import com.example.phone.weather.WeatherQueries
import com.example.ui.FloatingMode
import com.example.ui.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The floating surface answers SMN queries locally: no Gemini, no Instinct, no activity. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingWeatherTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private class FakeWeather : WeatherQueries {
    val requests = mutableListOf<PhoneCommand.Platform>()
    val reply = CompletableDeferred<SmnWeatherReply>()
    override suspend fun answer(command: PhoneCommand.Platform): SmnWeatherReply { requests += command; return reply.await() }
  }

  private fun viewModel(weather: WeatherQueries) = MainViewModel(app, learnedPhrases = emptyLearnedPhrasesForTest(),
    hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { error("SMN queries never reach Gemini Decisions") }),
    weather = weather)

  @Test fun temperaturaSeConsultaYSeMuestraEnLaSuperficie() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val weather = FakeWeather()
    val vm = viewModel(weather)
    try {
      vm.updateFloatingText("¿Cuál es la sensación térmica?"); vm.sendFloatingText { fail("Must not be sent to Instinct") }; runCurrent()
      assertEquals(listOf(PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE, WeatherIntentParser.FEELS_LIKE)), weather.requests)
      assertEquals(FloatingMode.SENDING, vm.floatingAssistant.value.mode)
      assertEquals("Consultando el SMN…", vm.floatingAssistant.value.status)
      weather.reply.complete(SmnWeatherReply("Según el SMN, la sensación térmica en Buenos Aires es de 28,4 grados (medición de las 12:00).", true))
      runCurrent()
      val state = vm.floatingAssistant.value
      assertEquals(FloatingMode.IDLE, state.mode)
      assertEquals("Según el SMN, la sensación térmica en Buenos Aires es de 28,4 grados (medición de las 12:00).", state.status)
      assertNull(state.error)
      assertEquals("", state.text)
      assertNull("No browser, WhatsApp or other app", shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun unFalloConservaElPedidoParaReintentar() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val weather = FakeWeather()
    val vm = viewModel(weather)
    try {
      vm.updateFloatingText("Decime si va a llover"); vm.sendFloatingText { fail("Must not be sent to Instinct") }; runCurrent()
      assertEquals(listOf(PhoneCommand.Platform(PlatformAction.WEATHER_RAIN)), weather.requests)
      weather.reply.complete(SmnWeatherReply("El SMN no permitió la consulta automática del pronóstico en este momento.", false))
      runCurrent()
      val state = vm.floatingAssistant.value
      assertEquals("El SMN no permitió la consulta automática del pronóstico en este momento.", state.error)
      assertEquals("Decime si va a llover", state.text)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun unaRespuestaTardiaSeDescartaSiCambioLaGeneracion() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val weather = FakeWeather()
    val vm = viewModel(weather)
    try {
      vm.updateFloatingText("¿Qué temperatura hace?"); vm.sendFloatingText { fail("Must not be sent to Instinct") }; runCurrent()
      vm.dismissFloatingAssistant()
      weather.reply.complete(SmnWeatherReply("Según el SMN, en Buenos Aires hay 28,3 grados (medición de las 12:00).", true))
      runCurrent()
      assertNotEquals("Según el SMN, en Buenos Aires hay 28,3 grados (medición de las 12:00).", vm.floatingAssistant.value.status)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun otroDiaSeExplicaSinConsultar() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val weather = FakeWeather()
    val vm = viewModel(weather)
    try {
      vm.updateFloatingText("¿va a llover el sábado?"); vm.sendFloatingText { fail("Must not be sent to Instinct") }; runCurrent()
      assertTrue(weather.requests.isEmpty())
      assertEquals(WeatherIntentParser.RAIN_SCOPE, vm.floatingAssistant.value.error)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }
}
