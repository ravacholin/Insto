package com.example.phone

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompatibleIntentPipelineTest {
  private fun coordinator(choice: String, extracted: () -> List<PhoneCommand> = { error("Native parameters must stay local") }) =
    GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision(choice, .99, .99, .99, .99) }), StructuredCommandExtractor { extracted() })

  @Test fun currentRegistryActionsAndSpotifyRemainAvailable() = runTest {
    val cases = listOf(
      Triple("spotify", "buscar rebord en Spotify", PhoneCommand.SpotifySearch("rebord")),
      Triple("brightness_set", "poné el brillo al cuarenta por ciento", PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent=40)),
      Triple("battery", "cuánta batería queda", PhoneCommand.Platform(PlatformAction.BATTERY)),
      Triple("timer", "temporizador de diez minutos", PhoneCommand.Timer(600)),
    )
    for ((choice, text, want) in cases) {
      val result = coordinator(choice).interpret(InterpretationRequest(text), PhoneCommandParser().parse(text))
      assertEquals(text, InterpretationResult.Direct(want), result)
      assertTrue(choice, choice in GeminiDecisionsClient.choices)
    }
  }
  @Test fun semanticParametersStillUseOneReviewedExtraction() = runTest {
    val result = coordinator("timer") { listOf(PhoneCommand.Timer(600,"Fideos")) }
      .interpret(InterpretationRequest("avisame en diez minutos por los fideos"), PhoneRoute.Instinct)
    assertEquals(listOf(PhoneCommand.Timer(600,"Fideos")), (result as InterpretationResult.Plan).commands)
  }
  @Test fun pendingFieldsReturnAClarificationInsteadOfAProviderFailure() = runTest {
    val result = coordinator("spotify").interpret(InterpretationRequest("buscar en Spotify"), PhoneRoute.Clarify("query", "spotify"))
    assertTrue(result is InterpretationResult.Clarification)
    assertEquals("spotify", (result as InterpretationResult.Clarification).context)
  }
}
