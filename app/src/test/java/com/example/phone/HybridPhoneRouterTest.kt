package com.example.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HybridPhoneRouterTest {
  private val parser = PhoneCommandParser()
  private fun router(choice: String, probability: Double = .99, confidence: Double = .99,
    safe: Double = .99, enabled: Boolean = true, called: () -> Unit = {}) =
    HybridPhoneRouter({ enabled }, DecisionClassifier {
      called(); SemanticDecision(choice, probability, confidence, safe, .99)
    })

  @Test fun clearLocalActionsAndExplicitRestrictionsNeverCallGoogle() = runBlocking {
    val hybrid = router("torch_on", called = { fail("Must stay local") })
    for (text in listOf("prendé la linterna", "poné una alarma", "llevame a Córdoba sin peajes", "reiniciá el cronómetro", "poné una alarma mañana a las 7", "encendeme la linterna durante diez minutos")) {
      val local = parser.parse(text)
      assertEquals(text, local, hybrid.route(text, local))
    }
  }
  @Test fun disabledModeAndExplicitInstinctKeepLegacyRoute() = runBlocking {
    assertEquals(PhoneRoute.Instinct, router("torch_on", enabled = false,
      called = { fail("Disabled") }).route("iluminá acá", PhoneRoute.Instinct))
    assertEquals(PhoneRoute.Instinct, router("torch_on",
      called = { fail("Explicit query") }).route("Instinct explicame la linterna", PhoneRoute.Instinct))
  }
  @Test fun unknownWordingCanRecoverAClosedAction() = runBlocking {
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(true)),
      router("torch_on").route("necesito luz con la linterna", parser.parse("necesito luz con la linterna")))
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(false)),
      router("torch_off").route("dejá de iluminar", PhoneRoute.Instinct))
  }
  @Test fun confidenceSafetyAndUnknownChoicesCannotDispatch() = runBlocking {
    val variants = listOf(router("torch_on", probability = .94), router("instinct", confidence = .94),
      router("torch_on", safe = .01), router("torch_on", probability = Double.NaN), router("arbitrary_intent"))
    variants.forEach { assertTrue(it.route("pedido desconocido", PhoneRoute.Instinct) is PhoneRoute.Unsupported) }
  }
  @Test fun queryIsExplicitButFreeParametersRequireClarification() = runBlocking {
    assertEquals(PhoneRoute.Instinct, router("instinct").route("contame algo interesante", PhoneRoute.Instinct))
    assertTrue(router("navigate").route("guiame al lugar", PhoneRoute.Instinct) is PhoneRoute.Clarify)
    assertTrue(router("alarm").route("despertador temprano", PhoneRoute.Instinct) is PhoneRoute.Clarify)
    assertTrue(router("unsupported").route("apagá el teléfono", PhoneRoute.Instinct) is PhoneRoute.Unsupported)
  }
  @Test fun networkErrorsPropagateInsteadOfBecomingInstinct() = runBlocking {
    val hybrid = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("Error de conexión") })
    try { hybrid.route("pedido desconocido", PhoneRoute.Instinct); fail("Must retain request") }
    catch (_: DecisionException) { }
  }
}
