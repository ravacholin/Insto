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

  @Test fun elRespaldoSeSuperponeConDecisionsYConservaLaRevision() = runTest {
    val command = PhoneCommand.Timer(600)
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      delay(3_000)
      throw DecisionException("Sin acceso", GeminiFailureKind.AUTH)
    }), StructuredCommandExtractor { error("Extracción primaria inesperada") },
      fallback = StructuredCommandExtractor { delay(5_000); listOf(command) })
    val result = c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
    assertEquals(5_000L, currentTime)
    assertEquals(listOf(command), (result as InterpretationResult.Plan).commands)
    assertTrue(result.fallback)
  }

  @Test fun decisionsConservaPrecedenciaYCancelaElRespaldo() = runTest {
    var started = false
    var cancelled = false
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      delay(500)
      decision("torch_on")
    }), StructuredCommandExtractor { error("Extracción primaria inesperada") },
      fallback = StructuredCommandExtractor {
        started = true
        try { awaitCancellation() } finally { cancelled = true }
      })
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)),
      c.interpret(InterpretationRequest("iluminá acá"), PhoneRoute.Instinct))
    assertTrue(started && cancelled)
    assertEquals(500L, currentTime)
  }

  @Test fun unRespaldoYaListoNoSalteaLaVerificacionNiLaSeguridad() = runTest {
    for (safety in listOf(.99, .3)) {
      var extracted = false
      var verified = false
      val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
        delay(500)
        SemanticDecision("torch_on", .89, .89, safety, .99)
      }), StructuredCommandExtractor { error("Extracción primaria inesperada") },
        verifier = IntentVerifier { _, _ -> verified = true; false },
        fallback = StructuredCommandExtractor { extracted = true; listOf(PhoneCommand.Torch(true)) })
      val result = coordinator.interpret(InterpretationRequest("iluminá acá"), PhoneRoute.Instinct)
      assertTrue(extracted)
      assertEquals(safety == .99, verified)
      assertTrue(result is InterpretationResult.Failure)
    }
  }

  @Test fun cancelarElTurnoDescartaInclusoUnaPropuestaQueLlegaTarde() = runTest {
    var delivered = false
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { awaitCancellation() }),
      StructuredCommandExtractor { error("Extracción primaria inesperada") },
      fallback = StructuredCommandExtractor {
        withContext(NonCancellable) { delay(1000) }
        listOf(PhoneCommand.Torch(true))
      })
    val turn = launch {
      c.interpret(InterpretationRequest("iluminá acá"), PhoneRoute.Instinct)
      delivered = true
    }
    runCurrent()
    turn.cancel()
    advanceUntilIdle()
    assertFalse(delivered)
  }

  @Test fun recognizedButUnparsedRequestsUseStructuredResolutionAcrossCapabilities() = runTest {
    val parser = PhoneCommandParser()
    val requests = listOf(
      "necesito la linterna ahora mismo" to PhoneCommand.Torch(true),
      "necesito el cronómetro contando ya" to PhoneCommand.Stopwatch(StopwatchOp.START),
      "¿qué tengo la próxima semana?" to CalendarSelection(from = "2026-11-23", until = "2026-11-30")
        .command(PlatformAction.CALENDAR_QUERY),
    )
    for ((text, command) in requests) {
      var decisions = 0
      val router = HybridPhoneRouter({ true }, DecisionClassifier { decisions++; decision("unsupported") })
      val local = parser.parse(text)
      assertTrue("$text: $local", local is PhoneRoute.Unsupported && local.recoverable)
      val result = GeminiCoordinator(router, StructuredCommandExtractor { listOf(command) })
        .interpret(InterpretationRequest(text), local)
      assertEquals(text, listOf(command), (result as? InterpretationResult.Plan)?.commands)
      assertEquals(text, 0, decisions)
    }
  }

  @Test fun invalidOrIncompatiblePrimaryProposalUsesTheSameFallbackForAnyRoute() = runTest {
    val text = "Encendé la linterna y abrí Spotify"
    val parser = PhoneCommandParser()
    val expected = listOf(PhoneCommand.Torch(true), PhoneCommand.OpenApp("spotify"))
    for (primary in listOf(
      StructuredCommandExtractor { throw DecisionException("malformed", GeminiFailureKind.INVALID_RESPONSE) },
      StructuredCommandExtractor { expected.reversed() },
    )) {
      val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { error("No classification") }),
        primary, fallback = StructuredCommandExtractor { expected })
      val result = c.interpret(InterpretationRequest(text), parser.parse(text)) as? InterpretationResult.Plan
      assertEquals(expected, result?.commands)
      assertEquals(true, result?.fallback)
    }
  }

  @Test fun affirmativeClassificationMismatchCanRecoverWithValidatedFallback() = runTest {
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { decision("timer") }),
      StructuredCommandExtractor { listOf(PhoneCommand.Alarm(7, 0)) },
      fallback = StructuredCommandExtractor { listOf(PhoneCommand.Timer(600)) })
    val result = c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      as? InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Timer(600)), result?.commands)
    assertEquals(true, result?.fallback)
  }

  @Test fun unsupportedCategoryWithAffirmativeSafetyGetsAnIndependentProposal() = runTest {
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { decision("unsupported") }),
      StructuredCommandExtractor { error("No classified extraction") },
      fallback = StructuredCommandExtractor { listOf(PhoneCommand.Timer(600)) })
    val result = c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      as? InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Timer(600)), result?.commands)
    assertEquals(true, result?.fallback)
  }

  @Test fun fallbackCannotAppendASecondActionToASingleRequest() = runTest {
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      throw DecisionException("offline", GeminiFailureKind.NETWORK)
    }), StructuredCommandExtractor { error("Primary extraction") },
      fallback = StructuredCommandExtractor { listOf(PhoneCommand.Timer(600), PhoneCommand.Torch(true)) })
    assertTrue(c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      is InterpretationResult.Rejected)
  }

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

  @Test fun explicitClockDeadlineWorksLocallyAcrossMidnight() = runTest {
    val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(2026, java.util.Calendar.NOVEMBER, 18, 12, 0)
    }
    val text = "silenciar teléfono hasta las 11 am"
    val parser = PhoneCommandParser { now }
    assertTrue(parser.parse(text) is PhoneRoute.Local)
    val command = (parser.parse(text) as PhoneRoute.Local).command as PhoneCommand.TemporaryMode
    assertEquals(ModeKind.SILENT, command.kind)
    assertEquals(23 * 3600, command.seconds)
    assertEquals(now.timeInMillis + 23 * 3_600_000, command.deadlineMillis)
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
    val delayed = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.MINUTE, 5) }
    assertEquals(command, DraftPlan.fromCommands(listOf(command), now).steps.single().complete(delayed))
    val dnd = parser.parse("poné no molestar hasta las 23:30") as PhoneRoute.Local
    assertEquals(PhoneCommand.TemporaryMode(ModeKind.DND, 41_400, deadlineMillis = now.timeInMillis + 41_400_000), dnd.command)
    assertTrue(parser.parse("silenciar teléfono hasta las 11") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("silenciar teléfono hasta hoy a las 11 am") is PhoneRoute.Unsupported)
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { error("Gemini called") }),
      StructuredCommandExtractor { error("Gemini extraction") }, fallback = StructuredCommandExtractor { error("Groq called") })
    assertEquals(InterpretationResult.Direct(command),
      c.interpret(InterpretationRequest(text, now = now), parser.parse(text)))
  }

  @Test fun geminiExtractionFailureUsesGroqWithReview() = runTest {
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { decision("timer") }),
      StructuredCommandExtractor { throw DecisionException("offline", GeminiFailureKind.NETWORK) },
      fallback = StructuredCommandExtractor { listOf(PhoneCommand.Timer(600)) })
    val result = c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
    assertTrue(result is InterpretationResult.Plan)
    assertTrue((result as InterpretationResult.Plan).fallback)
    assertEquals(listOf(PhoneCommand.Timer(600)), result.commands)
  }

  @Test fun uncertainCategoryUsesGroqOnlyWithAffirmativeSafety() = runTest {
    var calls = 0
    val fallback = StructuredCommandExtractor { calls++; listOf(PhoneCommand.Timer(600)) }
    for (safety in listOf(.99, .20)) {
      val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
        SemanticDecision("timer", .70, .70, safety, .99)
      }), StructuredCommandExtractor { error("Gemini extraction") }, fallback = fallback)
      val result = c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      if (safety == .99) assertTrue((result as InterpretationResult.Plan).fallback)
      else assertTrue(result is InterpretationResult.Failure)
    }
    assertEquals(1, calls)
  }

  @Test fun groqProposalCannotReplaceATimedActionWithAFixedAction() = runTest {
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      throw DecisionException("offline", GeminiFailureKind.NETWORK)
    }), StructuredCommandExtractor { error("Gemini extraction") },
      fallback = StructuredCommandExtractor { listOf(PhoneCommand.Platform(PlatformAction.SILENT_ON)) })
    val result = c.interpret(InterpretationRequest("dejá el teléfono enmudecido hasta las 11 am"), PhoneRoute.Instinct)
    assertTrue(result is InterpretationResult.Rejected)
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

  @Test fun highConfidenceIgnoresBackupAndEvenAStrongQueryPredictionCannotSendAPhoneRequest() = runTest {
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
    val result = GeminiCoordinator(router, StructuredCommandExtractor { throw DecisionException("offline") }, fallback = extractor).interpret(InterpretationRequest("necesito luz con la linterna"),
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
