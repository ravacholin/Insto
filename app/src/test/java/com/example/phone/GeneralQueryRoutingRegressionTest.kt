package com.example.phone

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GeneralQueryRoutingRegressionTest {
  @Test fun questionsWithCuandoOrSiReachInstinctWithoutConfirmation() = runTest {
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("unsupported", .99, .99, .99, .99)
    }), StructuredCommandExtractor { error("No extraction") })
    for (text in listOf(
      "¿Cuándo es la final de Boca por la Copa Sudamericana?",
      "Cuando es la final de Boca por la Copa Sudamericana",
      "¿Cuándo sale el tren?",
      "Cuándo cierran los bancos",
      "¿Quién gana si Boca empata?",
      "Qué pasa si no llueve mañana",
      "Dónde puedo ver cuándo juega Boca",
    )) {
      assertEquals(text, InterpretationResult.Query(), coordinator.interpret(InterpretationRequest(text), PhoneCommandParser().parse(text)))
    }
  }

  @Test fun unavailableDecisionsDoesNotAddConfirmationToAnUnmatchedQuery() = runTest {
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed boundary")
      override suspend fun interpret(request: InterpretationRequest) = InterpretationResult.Query()
    }
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      throw DecisionException("unavailable", GeminiFailureKind.UNAVAILABLE)
    }), extractor)
    assertEquals(InterpretationResult.Query(), coordinator.interpret(
      InterpretationRequest("ayudame a estudiar"), PhoneRoute.Instinct))
  }

  @Test fun questionsDoNotReplaceNativeBatteryQueriesOrAuthorizeConditionalActions() = runTest {
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("battery", .99, .99, .99, .99)
    }), StructuredCommandExtractor { error("No extraction") })
    val battery = "cuánta batería queda"
    assertEquals(InterpretationResult.Direct(PhoneCommand.Platform(PlatformAction.BATTERY)),
      coordinator.interpret(InterpretationRequest(battery), PhoneCommandParser().parse(battery)))
    val unmatched = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("unsupported", .99, .99, .99, .99)
    }), StructuredCommandExtractor { error("No extraction") })
    for (text in listOf("no prendas la linterna", "prendé la linterna cuando llegue", "si llueve pausá la música",
      "cuando llegue a casa, prendé la linterna", "cuando termina el partido, apagá la música",
      "cuando es de noche, prendé la linterna")) {
      assertTrue(text, unmatched.interpret(InterpretationRequest(text), PhoneCommandParser().parse(text)) is InterpretationResult.Rejected)
    }
  }
}
