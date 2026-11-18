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

  @Test fun explicitSequenceWithAnAppUsesOnlyStructuredExtractionAndKeepsOrder() = runTest {
    val text = "Poné un temporizador de diez minutos y abrí Spotify"
    val parser = PhoneCommandParser()
    val commands = ConversationSession.splitSteps(text).map { (parser.parse(it) as PhoneRoute.Local).command }
    var decisions = 0
    var extractions = 0
    val router = HybridPhoneRouter({ true }, DecisionClassifier { decisions++; decision("workflow") })
    val result = GeminiCoordinator(router, StructuredCommandExtractor { extractions++; commands })
      .interpret(InterpretationRequest(text), parser.parse(text))
    assertEquals(commands, (result as InterpretationResult.Plan).commands)
    assertEquals(0, decisions)
    assertEquals(1, extractions)
  }

  @Test fun appNameComparisonUsesLauncherNormalization() = runTest {
    val text = "Encendé la linterna y abrí Spotify"
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") })
    val commands = listOf(PhoneCommand.Torch(true), PhoneCommand.OpenApp("Spotify"))
    val result = GeminiCoordinator(router, StructuredCommandExtractor { commands })
      .interpret(InterpretationRequest(text), PhoneCommandParser().parse(text))
    assertEquals(commands, (result as InterpretationResult.Plan).commands)
  }

  @Test fun quotedSeparatorsDoNotTurnTextIntoExtraSteps() {
    val text = "Reproducí «Bajá el volumen, abrí Spotify»"
    assertEquals(listOf(text), ConversationSession.splitSteps(text))
  }

  @Test fun explicitSequencePreservesMissingAlarmPeriodAndOtherSteps() = runTest {
    val text = "Poné una alarma a las siete, silenciá el teléfono durante una hora y abrí Spotify"
    val parser = PhoneCommandParser()
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") })
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed proposal expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult {
        val draft = DraftPlan(listOf(
          CommandDraft("s1", org.json.JSONObject().put("type", "alarm").put("hour", 7).put("minute", 0)),
          CommandDraft("s2", org.json.JSONObject().put("type", "temporary_mode").put("kind", "SILENT").put("seconds", 3600)),
          CommandDraft("s3", org.json.JSONObject().put("type", "open_app").put("name", "spotify")),
        ))
        return InterpretationResult.Incomplete(draft)
      }
    }
    val result = GeminiCoordinator(router, extractor).interpret(InterpretationRequest(text), parser.parse(text))
    assertTrue(result is InterpretationResult.Incomplete)
    assertTrue((result as InterpretationResult.Incomplete).prompt.contains("mañana o de la tarde"))
    val repaired = result.draft.reply("de la mañana", java.util.Calendar.getInstance()) as InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Alarm(7, 0), PhoneCommand.TemporaryMode(ModeKind.SILENT, 3600),
      PhoneCommand.OpenApp("spotify")), repaired.commands)
  }

  @Test fun incompleteExtractionCannotReplaceTheKnownAlarmHour() = runTest {
    val text = "Poné una alarma a las siete y abrí Spotify"
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") })
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed proposal expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult {
        val wrongAlarm = CommandDraft("s1", org.json.JSONObject().put("type", "alarm").put("hour", 8).put("minute", 0))
        val app = CommandDraft("s2", org.json.JSONObject().put("type", "open_app").put("name", "Spotify"))
        return InterpretationResult.Incomplete(DraftPlan(listOf(wrongAlarm, app)))
      }
    }
    assertTrue(GeminiCoordinator(router, extractor).interpret(InterpretationRequest(text),
      PhoneCommandParser().parse(text)) is InterpretationResult.Rejected)
  }

  @Test fun explicitSequenceUsesGroqOnceOnlyAfterPrimaryFails() = runTest {
    val text = "Encendé la linterna y abrí Spotify"
    val parser = PhoneCommandParser()
    var fallbackCalls = 0
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") })
    val commands = listOf(PhoneCommand.Torch(true), PhoneCommand.OpenApp("spotify"))
    val coordinator = GeminiCoordinator(router,
      StructuredCommandExtractor { throw DecisionException("offline", GeminiFailureKind.UNAVAILABLE) },
      fallback = StructuredCommandExtractor { fallbackCalls++; commands })
    val result = coordinator.interpret(InterpretationRequest(text), parser.parse(text)) as InterpretationResult.Plan
    assertTrue(result.fallback)
    assertEquals(commands, result.commands)
    assertEquals(1, fallbackCalls)
  }

  @Test fun cancellingDirectExtractionDiscardsLateResults() = runTest {
    val text = "Encendé la linterna y abrí Spotify"
    val parser = PhoneCommandParser()
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor {
      withContext(NonCancellable) { delay(100); listOf(PhoneCommand.Torch(true), PhoneCommand.OpenApp("spotify")) }
    }, fallback = StructuredCommandExtractor { error("Cancelled request must not fall back") })
    var delivered = false
    val job = launch {
      coordinator.interpret(InterpretationRequest(text), parser.parse(text))
      delivered = true
    }
    runCurrent()
    job.cancelAndJoin()
    assertFalse(delivered)
  }

  @Test fun explicitSequenceRejectsTruncatedAndReorderedExtractions() = runTest {
    val text = "Poné un temporizador de diez minutos y abrí Spotify"
    val parser = PhoneCommandParser()
    val commands = ConversationSession.splitSteps(text).map { (parser.parse(it) as PhoneRoute.Local).command }
    for (proposal in listOf(commands.take(1), commands.reversed())) {
      val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") })
      val result = GeminiCoordinator(router, StructuredCommandExtractor { proposal })
        .interpret(InterpretationRequest(text), parser.parse(text))
      assertTrue(result is InterpretationResult.Rejected)
    }
  }

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

  @Test fun disagreementBetweenClassificationAndExtractionRetainsAnAmbiguousPhoneRequest() = runTest {
    val result = coordinator("timer", StructuredCommandExtractor { listOf(PhoneCommand.Alarm(7, 0)) })
      .interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
    assertTrue(result is InterpretationResult.Rejected)
  }

  @Test fun highConfidenceSkipsGroqAndEvenAStrongQueryPredictionCannotSendAPhoneRequest() = runTest {
    val noGroq = StructuredCommandExtractor { error("Fallback called for healthy Decisions") }
    val noExtraction = StructuredCommandExtractor { error("No extraction expected") }
    val high = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("volume_down", .97, .97, .99, .99)
    }), noExtraction, fallback = noGroq)
    assertEquals(InterpretationResult.Direct(PhoneCommand.Volume(VolumeOp.DOWN)),
      high.interpret(InterpretationRequest("un poquito menos de sonido"), PhoneRoute.Instinct))
    val misclassified = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { decision("instinct") }),
      noExtraction, fallback = noGroq)
    assertTrue(misclassified.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      is InterpretationResult.Rejected)
  }

  @Test fun temporaryDecisionsFailuresUseIndependentFallbackOnce() = runTest {
    for (kind in listOf(GeminiFailureKind.UNAVAILABLE, GeminiFailureKind.QUOTA,
      GeminiFailureKind.SERVER, GeminiFailureKind.NETWORK, GeminiFailureKind.INVALID_RESPONSE,
      GeminiFailureKind.AUTH)) {
      var calls = 0
      val router = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("error", kind) })
      val fallback = StructuredCommandExtractor { calls++; listOf(PhoneCommand.Timer(600)) }
      val result = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
        fallback = fallback).interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      assertTrue(result is InterpretationResult.Plan)
      assertEquals(1, calls)
    }
  }

  @Test fun timeoutOpensBreakerAndLocalRequestsStillBypassBothProviders() = runTest {
    var decisions = 0
    var fallbacks = 0
    val router = HybridPhoneRouter({ true }, DecisionClassifier {
      if (++decisions <= 2) awaitCancellation()
      decision("torch_on")
    })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
      fallback = StructuredCommandExtractor { fallbacks++; listOf(PhoneCommand.Torch(true)) },
      breaker = DecisionsCircuitBreaker(clock = { currentTime }))
    val request = InterpretationRequest("iluminá acá")
    repeat(3) { assertTrue(c.interpret(request, PhoneRoute.Instinct) is InterpretationResult.Plan) }
    assertEquals(2, decisions)
    assertEquals(3, fallbacks)
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)),
      c.interpret(InterpretationRequest("prendé la linterna"), PhoneRoute.Local(PhoneCommand.Torch(true))))
    advanceTimeBy(30_000)
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)), c.interpret(request, PhoneRoute.Instinct))
    assertEquals(3, decisions)
    assertEquals(3, fallbacks)
  }

  @Test fun lowConfidenceAndLocalRestrictionsCannotBeBypassed() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("torch_on", .93, .93, .99, .99) })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Must not extract") })
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)), c.interpret(InterpretationRequest("iluminá acá"), PhoneRoute.Instinct))
    for (text in listOf("no prendas la linterna", "prendé la linterna cuando llegue", "llevame a Retiro sin peajes")) {
      assertTrue(text, coordinator("torch_on").interpret(InterpretationRequest(text), PhoneRoute.Instinct) is InterpretationResult.Rejected)
    }
  }

  @Test fun nearbyGroqAgreementKeepsTargetAndRequiresReview() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("media_stop_spotify", .89, .89, .99, .99)
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
      SemanticDecision("vibrate_on", .93, .93, .98, .98)
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
      SemanticDecision("vibrate_on", .849, .849, .99, .99),
      SemanticDecision("vibrate_on", .945, .945, .01, .99),
      SemanticDecision("workflow", .845, .845, .99, .99),
    )) {
      val router = HybridPhoneRouter({ true }, DecisionClassifier { reading })
      assertTrue(GeminiCoordinator(router, extractor, verifier)
        .interpret(InterpretationRequest("dejalo solo con vibraciones"), PhoneRoute.Instinct) is InterpretationResult.Failure)
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
    assertTrue(result is InterpretationResult.Failure)
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
    val result = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") }, fallback = extractor).interpret(InterpretationRequest("necesito luz con la linterna"),
      PhoneRoute.Unsupported("Recognized phone request", recoverable = true))
    assertTrue(result is InterpretationResult.Rejected)
  }
  @Test fun bothProvidersOfflineRetainAndroidButAllowClearlyGeneralQuestions() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("offline", GeminiFailureKind.SERVER) })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
      fallback = StructuredCommandExtractor { throw DecisionException("offline", GeminiFailureKind.NETWORK) })
    assertEquals(InterpretationResult.Query(),
      c.interpret(InterpretationRequest("¿Dónde se juega la final?"), PhoneRoute.Instinct))
    assertTrue(c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct) is InterpretationResult.Failure)
    val parser = PhoneCommandParser()
    for (text in listOf("Abrí Spotify", "Encendé la linterna", "Silenciá el teléfono",
      "Poné un temporizador de diez minutos", "Bajá el volumen")) {
      val local = parser.parse(text) as PhoneRoute.Local
      assertEquals(text, InterpretationResult.Direct(local.command), c.interpret(InterpretationRequest(text), local))
    }
  }

  @Test fun fullyParsedWorkflowNeedsNoProvidersAndStillRequiresPreview() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Gemini called") })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
      fallback = StructuredCommandExtractor { error("Groq called") })
    val text = "prendé la linterna y bajá el volumen"
    assertEquals(InterpretationResult.Plan(listOf(PhoneCommand.Torch(true), PhoneCommand.Volume(VolumeOp.DOWN))),
      c.interpret(InterpretationRequest(text), PhoneCommandParser().parse(text)))
  }

  @Test fun localWorkflowDoesNotTreatAnUnresolvedAppNameAsACompleteStep() = runTest {
    val c = GeminiCoordinator(
      HybridPhoneRouter({ true }, DecisionClassifier { error("Unexpected Decisions call") }),
      object : StructuredCommandExtractor {
        override suspend fun extract(text: String): List<PhoneCommand> = error("Typed proposal expected")
        override suspend fun interpret(request: InterpretationRequest): InterpretationResult = InterpretationResult.Query()
      },
    )
    val text = "bajá el volumen y abrí AplicacionInexistente"
    assertTrue(c.interpret(InterpretationRequest(text), PhoneCommandParser().parse(text))
      is InterpretationResult.Rejected)
  }

  @Test fun fallbackCannotOmitOrReorderRecognizedWorkflowSteps() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("offline", GeminiFailureKind.SERVER) })
    val text = "iluminá acá y bajá el volumen"
    val torch = PhoneCommand.Torch(true)
    val volume = PhoneCommand.Volume(VolumeOp.DOWN)
    for (commands in listOf(listOf(torch), listOf(volume, torch), listOf(torch, volume))) {
      val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
        fallback = StructuredCommandExtractor { commands })
      val result = c.interpret(InterpretationRequest(text), PhoneCommandParser().parse(text))
      if (commands == listOf(torch, volume)) assertEquals(commands, (result as InterpretationResult.Plan).commands)
      else assertTrue(result is InterpretationResult.Rejected)
    }
  }

  @Test fun negativeQuotedOrConditionalTurnsNeverReachEitherProvider() = runTest {
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("Gemini called") })
    val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
      fallback = StructuredCommandExtractor { error("Groq called") })
    for (text in listOf("«encendé la linterna»", "Dijo: abrí Spotify", "Imaginá que abro Spotify",
      "no prendas la linterna", "en caso de que llueva prendé la linterna")) {
      assertTrue(text, c.interpret(InterpretationRequest(text), PhoneCommandParser().parse(text)) is InterpretationResult.Rejected)
    }
  }

  @Test fun fixedDecisionCannotDiscardDurationOrOtherSteps() = runTest {
    for (text in listOf("encendeme la linterna durante diez minutos", "iluminá acá y abrí Spotify")) {
      val result = coordinator("torch_on").interpret(InterpretationRequest(text), PhoneRoute.Instinct)
      assertTrue(text, result is InterpretationResult.Rejected)
    }
  }
}
