package com.example.phone

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoutingAvailabilityTest {
  private val parser = PhoneCommandParser()
  private fun coordinator(classifier: DecisionClassifier) = GeminiCoordinator(
    HybridPhoneRouter({ true }, classifier), StructuredCommandExtractor { error("No extraction needed") })

  @Test fun abrirSpotifyDoesNotWaitForAnUnresponsiveClassifier() = runTest {
    val result = coordinator(DecisionClassifier { awaitCancellation() })
      .interpret(InterpretationRequest("abrir spotify"), parser.parse("abrir spotify"))
    assertEquals(InterpretationResult.Direct(PhoneCommand.OpenApp("spotify")), result)
    assertEquals(0, currentTime)
  }

  @Test fun prenderLinternaDoesNotDependOnModelConfidence() = runTest {
    val result = coordinator(DecisionClassifier { SemanticDecision("torch_on", .62, .62, .87, .87) })
      .interpret(InterpretationRequest("prender linterna"), parser.parse("prender linterna"))
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)), result)
  }

  @Test fun explicitInstinctQueryDoesNotWaitForGoogle() = runTest {
    val text = "Instinct explicame si mañana llueve"
    assertEquals(InterpretationResult.Query(), coordinator(DecisionClassifier { awaitCancellation() })
      .interpret(InterpretationRequest(text), parser.parse(text)))
    assertEquals(0, currentTime)
  }

  @Test fun technicalFailuresStillRouteUnhandledRequestsToInstinct() = runTest {
    for (kind in listOf(GeminiFailureKind.AUTH, GeminiFailureKind.QUOTA, GeminiFailureKind.NETWORK,
      GeminiFailureKind.SERVER, GeminiFailureKind.INVALID_RESPONSE)) {
      val text = "ayudame a estudiar"
      assertEquals(kind.toString(), InterpretationResult.Query(), coordinator(DecisionClassifier {
        throw DecisionException("provider failure", kind)
      }).interpret(InterpretationRequest(text), parser.parse(text)))
    }
  }

  @Test fun classifierTimeoutStillRoutesUnhandledRequestToInstinct() = runTest {
    assertEquals(InterpretationResult.Query(), coordinator(DecisionClassifier { awaitCancellation() })
      .interpret(InterpretationRequest("ayudame a estudiar"), PhoneRoute.Instinct))
    assertTrue(currentTime <= 4000)
  }

  @Test fun uncertaintyDoesNotExecuteAGuessOrBlockTheInstinctRoute() = runTest {
    val result = coordinator(DecisionClassifier { SemanticDecision("torch_on", .62, .62, .99, .99) })
      .interpret(InterpretationRequest("necesito ayuda con esto"), PhoneRoute.Instinct)
    assertEquals(InterpretationResult.Query(), result)
  }

  @Test fun anUnmatchedGreetingStillGoesToInstinct() = runTest {
    assertEquals(InterpretationResult.Query(), coordinator(DecisionClassifier {
      SemanticDecision("unsupported", .99, .99, .99, .99)
    }).interpret(InterpretationRequest("hola, buen día"), PhoneRoute.Instinct))
  }

  @Test fun anUnknownClassifierChoiceStillGoesToInstinct() = runTest {
    val result = coordinator(DecisionClassifier { SemanticDecision("unknown", .99, .99, .99, .99) })
      .interpret(InterpretationRequest("ayudame con esto"), PhoneRoute.Instinct)
    assertEquals(InterpretationResult.Query(), result)
  }

  @Test fun failedParameterExtractionStillGoesToInstinctForAnUnrecognizedRequest() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("timer", .99, .99, .99, .99) })
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed result expected")
      override suspend fun interpret(request: InterpretationRequest) =
        InterpretationResult.Failure(GeminiFailureKind.INVALID_RESPONSE, "No pude extraer los datos.")
    }
    val result = GeminiCoordinator(router, extractor)
      .interpret(InterpretationRequest("ayudame con esto"), PhoneRoute.Instinct)
    assertEquals(InterpretationResult.Query(), result)
  }

  @Test fun incompleteLocalCommandAsksForItsFieldWithoutWaitingForGoogle() = runTest {
    val result = coordinator(DecisionClassifier { awaitCancellation() })
      .interpret(InterpretationRequest("poné una alarma"), parser.parse("poné una alarma"))
    assertTrue(result is InterpretationResult.Clarification)
    assertEquals(0, currentTime)
  }

  @Test fun dateBearingAlarmClarificationKeepsTheSourceDateGuardWithoutGoogle() = runTest {
    val text = "alarma mañana a las siete"
    val result = coordinator(DecisionClassifier { awaitCancellation() })
      .interpret(InterpretationRequest(text), parser.parse(text)) as InterpretationResult.Clarification
    assertEquals("intent:alarm", result.context)
    assertEquals(0, currentTime)
    // A complete time alone must not replace tomorrow with the next occurrence today.
    val reply = LocalIntentRegistry.default.reply("alarm", "19:00", text to java.util.Calendar.getInstance())
    assertTrue(reply is PhoneRoute.Clarify)
    assertEquals("intent:alarm", (reply as PhoneRoute.Clarify).context)
  }

  @Test fun pendingDraftAndExplicitRestrictionsRemainLocalOnFailure() = runTest {
    val c = coordinator(DecisionClassifier { throw DecisionException("offline") })
    val draft = DraftPlan.fromCommands(listOf(PhoneCommand.Timer(600)))
    assertTrue(c.interpret(InterpretationRequest("cambiá ese dato", draft), PhoneRoute.Instinct) is InterpretationResult.Failure)
    for (text in listOf("no prender linterna", "prender linterna cuando llegue", "llevame a Retiro sin peajes"))
      assertTrue(text, c.interpret(InterpretationRequest(text), parser.parse(text)) is InterpretationResult.Rejected)
  }
}
