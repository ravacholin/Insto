package com.example.phone

import java.util.Calendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each main rejection route carries a typed reason; the message the user sees is unchanged. */
@OptIn(ExperimentalCoroutinesApi::class)
class RejectionReasonTest {
  private fun decision(choice: String) = SemanticDecision(choice, .99, .99, .99, .99)
  private fun coordinator(choice: String) =
    GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { decision(choice) }),
      StructuredCommandExtractor { error("No extraction expected") })

  private suspend fun rejected(text: String, choice: String = "torch_on", local: PhoneRoute = PhoneRoute.Instinct) =
    coordinator(choice).interpret(InterpretationRequest(text), local).let {
      assertTrue("$text → $it", it is InterpretationResult.Rejected)
      it as InterpretationResult.Rejected
    }

  @Test fun defaultReasonKeepsExistingCallersCompiling() {
    assertEquals(RejectionReason.NOT_UNDERSTOOD, InterpretationResult.Rejected("x").reason)
    assertEquals(RejectionReason.UNAVAILABLE, PhoneRoute.Unsupported("x").reason)
  }

  @Test fun safetyPolicySeparatesHypothesisNegationConditionAndUnavailableRestriction() = runTest {
    val hypothesis = rejected("Suponete que apagás el wifi")
    assertEquals(RejectionReason.UNSAFE_OR_HYPOTHETICAL, hypothesis.reason)
    assertEquals("El pedido parece una cita o una hipótesis. Indicá qué querés hacer ahora.", hypothesis.message)

    val negation = rejected("no prendas la linterna")
    val condition = rejected("prendé la linterna cuando llegue")
    assertEquals(RejectionReason.UNSAFE_OR_HYPOTHETICAL, negation.reason)
    assertEquals(RejectionReason.BLOCKING_CONDITION, condition.reason)
    // One message for both, as before; only the type tells them apart.
    val message = "El pedido contiene una negación o condición. No se ejecutó ni se envió; indicá qué querés hacer ahora."
    assertEquals(message, negation.message)
    assertEquals(message, condition.message)

    val route = rejected("llevame a Retiro sin peajes")
    assertEquals(RejectionReason.UNAVAILABLE, route.reason)
    assertEquals("Esa restricción de ruta no está disponible. No se omitió ni se ejecutó el pedido.", route.message)

    // The string API stays the same for its other callers.
    assertEquals(RequestSafetyPolicy.refusal("no prendas la linterna")!!.message, RequestSafetyPolicy.rejection("no prendas la linterna"))
  }

  @Test fun registryResolutionKeepsTheSafetyReasonThroughUnsupported() {
    // The path a model choice takes: the registry refuses the text and the reason survives into Unsupported.
    val route = LocalIntentRegistry.default.resolve("torch_on", "no prendas la linterna")
    assertTrue(route.toString(), route is PhoneRoute.Unsupported)
    assertEquals(RejectionReason.UNSAFE_OR_HYPOTHETICAL, (route as PhoneRoute.Unsupported).reason)
  }

  @Test fun pendingRequestIsABlockingCondition() = runTest {
    val result = rejected("avisame en diez minutos", choice = "instinct")
    assertEquals(RejectionReason.BLOCKING_CONDITION, result.reason)
    assertEquals("Hay un pedido del teléfono pendiente. Completalo o cancelalo; no se envió a Instinct.", result.message)
  }

  @Test fun unsupportedLocalRouteIsUnavailableAndWeatherOutsideItsScopeIsOutOfScope() = runTest {
    val unavailable = rejected("activá la vibración temporal", local = PhoneRoute.Unsupported("El modo vibración temporal no está disponible."))
    assertEquals(RejectionReason.UNAVAILABLE, unavailable.reason)
    assertEquals("El modo vibración temporal no está disponible.", unavailable.message)

    val weather = PhoneCommandParser().parse("qué temperatura hizo ayer")
    assertTrue(weather.toString(), weather is PhoneRoute.Unsupported)
    assertEquals(RejectionReason.OUT_OF_SCOPE, (weather as PhoneRoute.Unsupported).reason)
    assertEquals(WeatherIntentParser.TEMPERATURE_SCOPE, weather.message)
  }

  @Test fun semanticGuardRejectionsAreNotUnderstoodExceptAPhoneActionAsTopic() {
    val changed = SemanticCommandGuard.review(InterpretationRequest("poné un timer de diez minutos"),
      InterpretationResult.Direct(PhoneCommand.Timer(300)))
    assertTrue(changed.toString(), changed is InterpretationResult.Rejected)
    assertEquals(RejectionReason.NOT_UNDERSTOOD, (changed as InterpretationResult.Rejected).reason)

    val topic = SemanticCommandGuard.check("buscá cómo prender la linterna", listOf(PhoneCommand.Torch(true)), Calendar.getInstance())
    assertEquals(SemanticCommandGuard.Verdict.Rejected(
      "La acción del teléfono aparece como tema de una búsqueda o un mensaje, no como orden. No se ejecutó.",
      RejectionReason.OUT_OF_SCOPE), topic)
  }

  @Test fun cancellationWithoutPendingRequestIsAWithdrawal() = runTest {
    val result = rejected("cancelá", local = PhoneRoute.Cancel)
    assertEquals("Pedido cancelado.", result.message)
    assertEquals(RejectionReason.UNSAFE_OR_HYPOTHETICAL, result.reason)
  }
}
