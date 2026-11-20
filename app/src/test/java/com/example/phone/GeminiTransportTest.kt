package com.example.phone

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Calendar
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiTransportTest {
  private fun response(content: String) = JSONObject().put("candidates", org.json.JSONArray().put(JSONObject()
    .put("finishReason", "STOP").put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", content)))))).toString()

  @Test fun structuredRequestKeepsJsonSchemaAndUsesMinimalThinking() {
    val config = StructuredCommandClient.buildRequest("pedido", Calendar.getInstance()).getJSONObject("generationConfig")
    assertFalse(config.has("temperature"))
    assertEquals("minimal", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    assertEquals(2048, config.getInt("maxOutputTokens"))
    assertEquals("application/json", config.getString("responseMimeType"))
    assertEquals(StructuredCommandClient.proposalSchema().toString(), config.getJSONObject("responseJsonSchema").toString())
  }

  @Test fun structuredClientRetriesOnlyModelErrorsAndReportsTheRespondingModel() = runBlocking {
    val missing = """{"error":{"status":"INVALID_ARGUMENT","message":"models/gemini-3.5-flash-lite is not found for API version v1beta, or is not supported for generateContent."}}"""
    val unsupported = """{"error":{"status":"INVALID_ARGUMENT","message":"Model is not supported."}}"""
    val invalidConfig = """{"error":{"status":"INVALID_ARGUMENT","message":"Thinking level is not supported for this model."}}"""
    for ((status, body, retries) in listOf(
      Triple(404, "", true), Triple(400, missing, true), Triple(400, unsupported, true),
      Triple(400, """{"error":{"message":"Unsupported model: gemini-3.5-flash-lite"}}""", true),
      Triple(400, invalidConfig, false), Triple(400, "bad JSON", false),
      Triple(401, missing, false), Triple(403, missing, false), Triple(429, missing, false),
      Triple(408, missing, false), Triple(405, missing, false), Triple(501, missing, false),
      Triple(200, response("""{"kind":"instinct","steps":[]}"""), false),
    )) {
      val http = FakeHttp { exchange ->
        if (exchange.path.contains("gemini-3.5")) FakeHttp.Reply(status, body)
        else FakeHttp.Reply(200, response("""{"kind":"instinct","steps":[]}"""))
      }
      val client = StructuredCommandClient({ "key" }, http)
      val result = runCatching { client.interpret(InterpretationRequest("pedido")) }
      assertEquals(retries || status == 200, result.getOrNull() is InterpretationResult.Query)
      val expected = listOf("/v1beta/models/gemini-3.5-flash-lite:generateContent")
      assertEquals(if (retries) expected + "/v1beta/models/gemini-3.1-flash-lite:generateContent" else expected,
        http.exchanges.map { it.path })
      if (retries || status == 200) {
        assertEquals(if (retries) "gemini-3.1-flash-lite" else "gemini-3.5-flash-lite",
          GeminiDiagnostics.snapshot().lastExtractionModel)
      }
    }
  }

  @Test fun twoMissingModelsStopAfterTheFallback() = runBlocking {
    val http = FakeHttp { check(it.path.isNotEmpty()); FakeHttp.Reply(404, "") }
    val error = runCatching { StructuredCommandClient({ "key" }, http).extract("pedido") }.exceptionOrNull() as DecisionException
    assertEquals(GeminiFailureKind.UNAVAILABLE, error.kind)
    assertEquals(2, http.exchanges.size)
  }

  @Test fun socketTimeoutDoesNotRetry() = runBlocking {
    val http = FakeHttp { throw SocketTimeoutException() }
    val error = runCatching { StructuredCommandClient({ "key" }, http).extract("pedido") }.exceptionOrNull() as DecisionException
    assertEquals(GeminiFailureKind.TIMEOUT, error.kind)
    assertEquals(1, http.exchanges.size)
  }

  @Test fun fallbackHasOnlyTheTimeRemainingInThePrimaryDeadline() = runBlocking {
    val http = FakeHttp { exchange ->
      if (exchange.path.contains("gemini-3.5")) { Thread.sleep(350); FakeHttp.Reply(404, "") }
      else exchange.awaitCancel()
    }
    val error = runCatching { StructuredCommandClient({ "key" }, http, 1000).extract("pedido") }.exceptionOrNull()
    assertTrue(error is TimeoutCancellationException)
    assertEquals(2, http.exchanges.size)
    val fallback = http.exchanges[1]
    assertTrue(fallback.isCanceled)
    assertTrue(fallback.readMillis in 1..750)
    assertTrue(fallback.connectMillis in 1..750)
    assertTrue(fallback.writeMillis in 1..750)
  }

  @Test fun structuredClientUsesTypedDraftInsteadOfTreatingMissingDataAsNetworkFailure() = runBlocking {
    val http = FakeHttp.json(200, response("""{"kind":"draft","steps":[{"id":"s1","command":{"type":"timer","label":"Fideos"}}]}"""))
    val result = StructuredCommandClient({ "private-key" }, http).interpret(InterpretationRequest("un aviso para fideos")) as InterpretationResult.Incomplete
    assertEquals(setOf("seconds"), result.draft.steps.single().missing())
    assertEquals("Fideos", result.draft.steps.single().json().getString("label"))
    assertTrue(http.closed)
    assertEquals("private-key", http.last.header("x-goog-api-key"))
    assertFalse(http.last.bodyText().contains("private-key"))
  }
  @Test fun groqUsesBearerAuthAndAcceptsOnlyAnExplicitConfirmation() = runBlocking {
    for ((answer, expected) in listOf("SI" to true, "NO" to false, "SI, ejecutalo" to false)) {
      val http = FakeHttp.json(200, """{"choices":[{"finish_reason":"stop","message":{"content":"$answer"}}]}""")
      val verifier = GroqIntentVerifier({ "private-key" }, http)
      assertEquals(expected, verifier.confirm(InterpretationRequest("cortá Spotify"), "media_stop_spotify"))
      assertEquals("Bearer private-key", http.last.header("Authorization"))
      assertNull(http.last.header("x-goog-api-key"))
      assertFalse(http.last.bodyText().contains("private-key"))
      val request = JSONObject(http.last.bodyText())
      assertEquals("openai/gpt-oss-20b", request.getString("model"))
      assertEquals("low", request.getString("reasoning_effort"))
      assertEquals(256, request.getInt("max_completion_tokens"))
      assertEquals("cortá Spotify", request.getJSONArray("messages").getJSONObject(1).getString("content"))
    }
    assertNull(GroqIntentVerifier({ null }, FakeHttp { error("No network without credentials") })
      .confirm(InterpretationRequest("cortá Spotify"), "media_stop_spotify"))
  }

  @Test fun independentGroqInterpretsTypedJsonWithTheExistingDraftValidation() = runBlocking {
    fun completion(payload: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
      .put("finish_reason", "stop").put("message", JSONObject().put("content", payload)))).toString()
    val cases = listOf(
      """{"kind":"plan","steps":[{"id":"s1","command":{"type":"timer","seconds":600}}]}""" to InterpretationResult.Plan::class.java,
      """{"kind":"draft","steps":[{"id":"s1","command":{"type":"timer","label":"Fideos"}}]}""" to InterpretationResult.Incomplete::class.java,
      """{"kind":"unsupported","steps":[]}""" to InterpretationResult.Rejected::class.java,
      """{"kind":"instinct","steps":[]}""" to InterpretationResult.Query::class.java,
      """{"kind":"draft","steps":[{"id":"s1","command":{"type":"timer","seconds":600}}]}""" to null,
      """{"kind":"plan","steps":[{"id":"s1","command":{"type":"shell","command":"anything"}}]}""" to null
    )
    for ((payload, expected) in cases) {
      val http = FakeHttp.json(200, completion(payload))
      val client = GroqIntentVerifier({ "groq-key" }, http)
      val result = try { client.interpret(InterpretationRequest("un aviso para fideos")) }
      catch (error: DecisionException) {
        assertEquals(GeminiFailureKind.INVALID_RESPONSE, error.kind)
        null
      }
      assertEquals(expected, result?.javaClass)
      val body = JSONObject(http.last.bodyText())
      assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
      assertEquals("openai/gpt-oss-20b", body.getString("model"))
      assertEquals("Bearer groq-key", http.last.header("Authorization"))
      assertNull(http.last.header("x-goog-api-key"))
      assertTrue(http.closed)
    }
  }

  @Test fun decisionsHttpErrorsAndBadJsonCallOnlyGroqForTheBackup() = runBlocking {
    for (status in listOf(429, 500, 502, 503, 504, 404, 200)) {
      val google = FakeHttp.json(status, "invalid-provider-response")
      val router = HybridPhoneRouter({ true }, GeminiDecisionsClient({ "gemini-key" }, google))
      var groqCalls = 0
      val c = GeminiCoordinator(router, StructuredCommandExtractor { error("Gemini extraction") },
        fallback = StructuredCommandExtractor { groqCalls++; listOf(PhoneCommand.Timer(600)) })
      val proposal = c.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct)
      assertEquals(status.toString(), listOf(PhoneCommand.Timer(600)), (proposal as InterpretationResult.Plan).commands)
      assertTrue(proposal.fallback)
      assertEquals(1, groqCalls)
      assertEquals("Decisions is sent once, never retried", 1, google.exchanges.size)
    }
  }

  @Test fun bothClientsClassifyHttpFailuresWithoutProviderBodyLeakage() = runBlocking {
    for ((status, kind) in listOf(401 to GeminiFailureKind.AUTH, 403 to GeminiFailureKind.AUTH,
      429 to GeminiFailureKind.QUOTA, 404 to GeminiFailureKind.UNAVAILABLE, 405 to GeminiFailureKind.UNAVAILABLE,
      501 to GeminiFailureKind.UNAVAILABLE, 500 to GeminiFailureKind.SERVER, 503 to GeminiFailureKind.SERVER,
      408 to GeminiFailureKind.TIMEOUT, 422 to GeminiFailureKind.INVALID_REQUEST, 302 to GeminiFailureKind.INVALID_RESPONSE)) {
      for (structured in listOf(false, true)) {
        val http = FakeHttp.json(status, "private-key secret-provider-body")
        try {
          if (structured) StructuredCommandClient({ "private-key" }, http).interpret(InterpretationRequest("pedido"))
          else GeminiDecisionsClient({ "private-key" }, http).classify("pedido")
          fail("Expected HTTP rejection")
        } catch (error: DecisionException) {
          assertEquals("$status", kind, error.kind)
          assertFalse(error.message.orEmpty().contains("private-key"))
          assertFalse(error.message.orEmpty().contains("secret-provider-body"))
          assertTrue(http.closed)
          // Only a missing extraction model moves to the documented fallback model; nothing else is resent.
          val expected = if (structured && status == 404) 2 else 1
          assertEquals("No automatic retry after $status", expected, http.exchanges.size)
          assertEquals(expected, http.exchanges.map { it.path }.distinct().size)
        }
      }
    }
  }
  @Test fun malformedOversizeAndTransportErrorsAreSanitized() = runBlocking {
    for (body in listOf("not-json", "x".repeat(65537))) {
      val http = FakeHttp.json(200, body)
      try { StructuredCommandClient({ "key" }, http).extract("pedido"); fail() }
      catch (error: DecisionException) { assertEquals(GeminiFailureKind.INVALID_RESPONSE, error.kind); assertTrue(http.closed) }
    }
    try { StructuredCommandClient({ "key" }, FakeHttp { throw IOException("private-key") }).extract("pedido"); fail() }
    catch (error: DecisionException) { assertEquals(GeminiFailureKind.NETWORK, error.kind); assertFalse(error.message.orEmpty().contains("private-key")) }
  }
  @Test fun cancellationCancelsStructuredRequestsAndDropsLateResponses() = runBlocking {
    val entered = java.util.concurrent.CountDownLatch(1)
    val http = FakeHttp { entered.countDown(); it.awaitCancel() }
    val job = async(Dispatchers.Default) { StructuredCommandClient({ "key" }, http).extract("pedido") }
    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)); job.cancelAndJoin()
    assertTrue(job.isCancelled); assertTrue(http.last.isCanceled)
    assertEquals(1, http.exchanges.size)
  }

  @Test fun cancellationIsNeverAProviderFailureAndNeverTriesGroq() = runBlocking {
    val entered = java.util.concurrent.CountDownLatch(1)
    val gemini = FakeHttp { entered.countDown(); it.awaitCancel() }
    var groqCalls = 0
    val router = HybridPhoneRouter({ true }, GeminiDecisionsClient({ "key" }, gemini))
    val coordinator = GeminiCoordinator(router, StructuredCommandClient({ "key" }, gemini),
      fallback = StructuredCommandExtractor { groqCalls++; listOf(PhoneCommand.Timer(600)) })
    val outcome = async(Dispatchers.Default) {
      runCatching { coordinator.interpret(InterpretationRequest("avisame en diez minutos"), PhoneRoute.Instinct) }
    }
    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
    outcome.cancel()
    outcome.join()
    assertTrue(outcome.isCancelled)
    assertTrue(gemini.exchanges.all { it.isCanceled })
    // The speculative Groq request may have started before the cancellation, never after it.
    val started = groqCalls
    delay(200)
    assertEquals(started, groqCalls)
  }
}
