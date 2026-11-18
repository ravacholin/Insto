package com.example.phone

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiCoordinatorTest {
  private fun decision(choice: String) = SemanticDecision(choice, .99, .99, .99, .99)
  private fun coordinator(choice: String, extractor: StructuredCommandExtractor = StructuredCommandExtractor { error("No extraction expected") }) =
    GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { decision(choice) }), extractor)

  @Test fun generalQueriesAndFixedActionsNeverExtract() = runTest {
    assertEquals(InterpretationResult.Query(false), coordinator("instinct").interpret(InterpretationRequest("ayudame a estudiar"), PhoneRoute.Instinct))
    assertEquals(InterpretationResult.Direct(PhoneCommand.Volume(VolumeOp.DOWN)), coordinator("volume_down").interpret(InterpretationRequest("un poquito menos de sonido"), PhoneRoute.Instinct))
  }
  @Test fun explanatoryQueriesMayMentionConditionsWithoutBecomingCommands() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("instinct", .99, .99, .99, .99) })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("No extraction") })
    for (text in listOf("explicame qué pasa cuando uso wifi", "Instinct contame si mañana llueve")) {
      assertEquals(InterpretationResult.Query(), c.interpret(InterpretationRequest(text), PhoneRoute.Instinct))
    }
  }

  @Test fun onlyParameterActionsExtractAndRequirePreview() = runTest {
    val result = coordinator("timer", StructuredCommandExtractor { listOf(PhoneCommand.Timer(600)) })
      .interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
    assertEquals(listOf(PhoneCommand.Timer(600)), (result as InterpretationResult.Plan).commands)
  }

  @Test fun disagreementBetweenClassificationAndExtractionDefersAnUnrecognizedRequestToInstinct() = runTest {
    val result = coordinator("timer", StructuredCommandExtractor { listOf(PhoneCommand.Alarm(7, 0)) })
      .interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
    assertEquals(InterpretationResult.Query(), result)
  }

  @Test fun unavailableDecisionsUsesOneFallbackWithoutAddingQueryConfirmation() = runTest {
    for (kind in listOf(GeminiFailureKind.UNAVAILABLE, GeminiFailureKind.QUOTA, GeminiFailureKind.AUTH, GeminiFailureKind.INVALID_RESPONSE)) {
      var calls = 0
      val router = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("error", kind) })
      val extractor = object : StructuredCommandExtractor {
        override suspend fun extract(text: String): List<PhoneCommand> = error("Typed interpretation required")
        override suspend fun interpret(request: InterpretationRequest): InterpretationResult { calls++; return InterpretationResult.Query(false) }
      }
      val result = GeminiCoordinator(router, extractor).interpret(InterpretationRequest("ayudame a estudiar"), PhoneRoute.Instinct)
      if (kind == GeminiFailureKind.UNAVAILABLE) {
        assertEquals(InterpretationResult.Query(), result); assertEquals(1, calls)
      } else { assertEquals(InterpretationResult.Query(), result); assertEquals(0, calls) }
    }
  }

  @Test fun lowConfidenceAndLocalRestrictionsCannotBeBypassed() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("torch_on", .94, .94, .99, .99) })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Must not extract") })
    assertEquals(InterpretationResult.Query(), c.interpret(InterpretationRequest("iluminá acá"), PhoneRoute.Instinct))
    for (text in listOf("no prendas la linterna", "prendé la linterna cuando llegue", "llevame a Retiro sin peajes")) {
      assertTrue(text, coordinator("torch_on").interpret(InterpretationRequest(text), PhoneRoute.Instinct) is InterpretationResult.Rejected)
    }
  }

  @Test fun nearbyGroqAgreementKeepsTargetAndRequiresReview() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("media_stop_spotify", .94, .94, .99, .99)
    })
    val verifier = IntentVerifier { request, choice ->
      assertEquals("cortá lo que está reproduciendo Spotify", request.text)
      assertEquals("media_stop_spotify", choice)
      true
    }
    val result = GeminiCoordinator(router, StructuredCommandExtractor { error("No extraction") }, verifier)
      .interpret(InterpretationRequest("cortá lo que está reproduciendo Spotify"), PhoneRoute.Instinct)
    assertEquals(InterpretationResult.Plan(listOf(PhoneCommand.Media(MediaOp.STOP, MediaApp.SPOTIFY.packageName))), result)
  }

  @Test fun groqDisagreementOrTimeoutRetainsTheRequest() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("vibrate_on", .945, .945, .98, .98)
    })
    for (verifier in listOf(IntentVerifier { _, _ -> false }, IntentVerifier { _, _ -> awaitCancellation() })) {
      val result = GeminiCoordinator(router, StructuredCommandExtractor { error("No extraction") }, verifier)
        .interpret(InterpretationRequest("dejalo solo con vibraciones"), PhoneRoute.Instinct)
      assertTrue(result is InterpretationResult.Failure)
    }
  }

  @Test fun groqIsSkippedForCertainDecisionsAndUncertainSafety() = runTest {
    val extractor = StructuredCommandExtractor { error("No extraction") }
    val verifier = IntentVerifier { _, _ -> error("Groq must be skipped") }
    val certain = HybridPhoneRouter({ true }, DecisionClassifier { decision("vibrate_on") })
    assertEquals(InterpretationResult.Direct(PhoneCommand.Platform(PlatformAction.VIBRATE_ON)),
      GeminiCoordinator(certain, extractor, verifier)
        .interpret(InterpretationRequest("dejalo solo con vibraciones"), PhoneRoute.Instinct))
    for (reading in listOf(
      SemanticDecision("vibrate_on", .939, .939, .99, .99),
      SemanticDecision("vibrate_on", .945, .945, .97, .99),
      SemanticDecision("workflow", .945, .945, .99, .99),
    )) {
      val router = HybridPhoneRouter({ true }, DecisionClassifier { reading })
      assertEquals(InterpretationResult.Query(), GeminiCoordinator(router, extractor, verifier)
        .interpret(InterpretationRequest("dejalo solo con vibraciones"), PhoneRoute.Instinct))
    }
  }

  @Test fun workflowCanReviewSoundModeAndTargetedMediaTogether() = runTest {
    val commands = listOf(PhoneCommand.Platform(PlatformAction.VIBRATE_ON),
      PhoneCommand.Media(MediaOp.STOP, MediaApp.YOUTUBE.packageName))
    val result = coordinator("workflow", StructuredCommandExtractor { commands })
      .interpret(InterpretationRequest("activá vibración y detené YouTube"), PhoneRoute.Instinct)
    assertEquals(commands, (result as InterpretationResult.Plan).commands)
    assertTrue(CapabilityCatalog.choices.getValue("media_stop_youtube").contains("parar YouTube"))
    assertTrue(CapabilityCatalog.choices.getValue("media_pause").contains("Equivalentes"))
  }

  @Test fun extractionCannotExtendTheSharedDeadline() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { delay(3900); decision("timer") })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { delay(9000); listOf(PhoneCommand.Timer(1)) })
    val start = currentTime
    val result = c.interpret(InterpretationRequest("avisame más tarde"), PhoneRoute.Instinct)
    assertEquals(InterpretationResult.Query(), result)
    assertTrue(currentTime - start <= 12000)
  }

  @Test fun parentCancellationPropagatesWithoutFallback() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { awaitCancellation() })
    val job = async { GeminiCoordinator(router, StructuredCommandExtractor { error("No fallback") })
      .interpret(InterpretationRequest("iluminá acá"), PhoneRoute.Instinct) }
    runCurrent(); job.cancelAndJoin(); assertTrue(job.isCancelled)
  }
  @Test fun unavailableFallbackCannotReclassifyRecognizedPhoneRequestAsQuery() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("unavailable", GeminiFailureKind.UNAVAILABLE) })
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed result expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult = InterpretationResult.Query()
    }
    val result = GeminiCoordinator(router, extractor).interpret(InterpretationRequest("necesito luz con la linterna"),
      PhoneRoute.Unsupported("Recognized phone request", recoverable = true))
    assertTrue(result is InterpretationResult.Rejected)
  }
  @Test fun fixedDecisionCannotDiscardDurationOrOtherSteps() = runTest {
    for (text in listOf("encendeme la linterna durante diez minutos", "bajá el volumen y abrí Spotify")) {
      val result = coordinator("torch_on").interpret(InterpretationRequest(text), PhoneRoute.Instinct)
      assertTrue(text, result is InterpretationResult.Rejected)
    }
  }
}
