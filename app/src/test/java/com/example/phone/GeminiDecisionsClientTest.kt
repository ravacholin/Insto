package com.example.phone

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiDecisionsClientTest {
  private fun response(): JSONObject {
    val probabilities = JSONObject()
    GeminiDecisionsClient.choices.keys.forEach { probabilities.put(it, if (it == "torch_on") .99 else .01 / (GeminiDecisionsClient.choices.size - 1)) }
    return JSONObject().put("answers", JSONObject()
      .put("action", JSONObject().put("type", "choice").put("choice", "torch_on").put("probabilities", probabilities).put("confidence", .99))
      .put("unconditional", JSONObject().put("type", "noul").put("noul", .99).put("confidence", .99)))
  }
  @Test fun postsTextOnlyWithHeaderAndTypedQuestions() = runBlocking {
    val http = FakeHttp.json(200, response().toString())
    val result = GeminiDecisionsClient({ "personal-key" }, http).classify("iluminá acá")
    assertEquals("torch_on", result.choice)
    assertEquals("personal-key", http.last.header("x-goog-api-key"))
    assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", http.last.url)
    val request = JSONObject(http.last.bodyText())
    assertEquals("gemini-decision-1", request.getString("model"))
    assertEquals("iluminá acá", request.getString("input"))
    assertEquals("choice", request.getJSONObject("questions").getJSONObject("action").getString("type"))
    assertTrue(request.getJSONObject("questions").getJSONObject("action").getJSONObject("criteria").keys().asSequence().toSet().containsAll(LocalIntentRegistry.default.choices.keys))
    assertFalse(request.has("generationConfig"))
    assertFalse(request.toString().contains("personal-key"))
    assertTrue(http.closed)
    // Decisions has a 4 s budget: every limit is finite and inside it.
    assertEquals(4000, http.last.connectMillis)
    assertEquals(4000, http.last.writeMillis)
    assertEquals(4000, http.last.readMillis)
  }
  @Test fun rejectsMissingFieldsWrongTypesAndContradictoryProbabilities() = runBlocking {
    val wrongChoice = response().apply { getJSONObject("answers").getJSONObject("action").put("choice", "evil") }
    val wrongTop = response().apply { getJSONObject("answers").getJSONObject("action").getJSONObject("probabilities").put("torch_off", 1) }
    val wrongNumber = response().apply { getJSONObject("answers").getJSONObject("action").put("confidence", "0.99") }
    for (body in listOf("{}", "not-json", wrongChoice.toString(), wrongTop.toString(), wrongNumber.toString(), "x".repeat(65537))) {
      try { GeminiDecisionsClient({ "key" }, FakeHttp.json(200, body)).classify("pedido"); fail(body.take(20)) }
      catch (error: DecisionException) { assertEquals(GeminiFailureKind.INVALID_RESPONSE, error.kind) }
    }
  }
  @Test fun providerErrorsDoNotExposeKeyOrBodyOrRetry() = runBlocking {
    for (code in listOf(301, 401, 403, 429, 500)) {
      val http = FakeHttp.json(code, "personal-key private-provider-error")
      try { GeminiDecisionsClient({ "personal-key" }, http).classify("pedido"); fail("Must fail") }
      catch (e: DecisionException) {
        assertFalse(e.message.orEmpty().contains("personal-key"))
        assertFalse(e.message.orEmpty().contains("private-provider-error"))
        assertEquals(1, http.exchanges.size)
        assertTrue(http.closed)
      }
    }
  }
  @Test fun missingKeyDoesNotConnect() = runBlocking {
    try { GeminiDecisionsClient({ null }, FakeHttp { error("Must not connect") }).classify("pedido"); fail("Must fail") }
    catch (_: DecisionException) { }
  }
  @Test fun cancellationCancelsBlockedRequestAndCannotDeliverAnAnswer() = runBlocking {
    val entered = java.util.concurrent.CountDownLatch(1)
    val http = FakeHttp { entered.countDown(); it.awaitCancel() }
    val job = async(Dispatchers.Default) { GeminiDecisionsClient({ "key" }, http).classify("pedido") }
    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
    job.cancelAndJoin()
    assertTrue(job.isCancelled)
    assertTrue(http.last.isCanceled)
  }
}
