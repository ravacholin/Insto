package com.example.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HybridPhoneRouterTest {
  private val parser = PhoneCommandParser()
  private fun router(choice: String, score: Double = .99, safe: Double = .99,
    enabled: Boolean = true, called: () -> Unit = {}) =
    HybridPhoneRouter({ enabled }, DecisionClassifier {
      called(); SemanticDecision(choice, score, score, safe, .99)
    })

  @Test fun everyNewRequestClassifiesIncludingLiteralCommandsAndGeneralQueries() = runBlocking {
    var calls = 0
    val hybrid = router("instinct", called = { calls++ })
    for (text in listOf("prendé la linterna", "poné una alarma", "explicame física", "Instinct hola", "bajá el volumen y abrí Spotify")) {
      assertEquals(text, PhoneRoute.Instinct, hybrid.route(text, parser.parse(text)))
    }
    assertEquals(5, calls)
  }
  @Test fun disabledClassificationNeverExecutesTheParserCandidate() = runBlocking {
    try {
      router("torch_on", enabled = false, called = { fail("Disabled") })
        .route("prendé la linterna", parser.parse("prendé la linterna"))
      fail("Must retain the request for setup")
    } catch (_: DecisionException) { }
  }
  @Test fun semanticChoiceResolvesARegisteredAction() = runBlocking {
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(true)),
      router("torch_on").route("iluminá con la linterna", PhoneRoute.Instinct))
    assertEquals(PhoneRoute.Local(PhoneCommand.Media(MediaOp.NEXT)),
      router("media_next").route("pasá al tema que sigue", PhoneRoute.Instinct))
  }
  @Test fun lowConfidenceUnsafeAndNoMatchForwardOriginalInsteadOfBlocking() = runBlocking {
    for (hybrid in listOf(router("torch_on", .94), router("torch_on", safe = .01), router("unsupported"), router("instinct"))) {
      assertEquals(PhoneRoute.Instinct, hybrid.route("pedido original", PhoneRoute.Unsupported("parser viejo")))
    }
  }
  @Test fun invalidProviderScoresAndInventedIdsAreTechnicalFailures() = runBlocking {
    for (hybrid in listOf(router("torch_on", Double.NaN), router("arbitrary_intent"))) {
      try { hybrid.route("pedido", PhoneRoute.Instinct); fail("Invalid response") }
      catch (_: DecisionException) { }
    }
  }
  @Test fun selectedIntentCannotExecuteAnotherParserAction() = runBlocking {
    val route = router("alarm").route("bajá el volumen", parser.parse("bajá el volumen"))
    assertFalse(route is PhoneRoute.Local)
    assertTrue(route is PhoneRoute.Clarify)
  }
  @Test fun recognizedParametersAreValidatedWithoutGenerativeExtraction() = runBlocking {
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(600)),
      router("timer").route("temporizador de diez minutos", PhoneRoute.Instinct))
    assertTrue(router("navigate").route("guiame al lugar", PhoneRoute.Instinct) is PhoneRoute.Clarify)
    assertTrue(router("alarm").route("despertame mañana a las ocho", PhoneRoute.Instinct) is PhoneRoute.Clarify)
  }
  @Test fun networkErrorsPropagateInsteadOfBecomingUnknown() = runBlocking {
    val hybrid = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("Error de conexión") })
    try { hybrid.route("pedido", PhoneRoute.Instinct); fail("Must retain request") }
    catch (_: DecisionException) { }
  }
}
