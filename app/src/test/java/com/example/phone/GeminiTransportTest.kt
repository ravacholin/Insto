package com.example.phone

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiTransportTest {
  private open class Connection(val status: Int, val body: String) : HttpURLConnection(URL("https://example.test")) {
    val output = ByteArrayOutputStream()
    var closed = false
    override fun connect() {}
    override fun usingProxy() = false
    override fun disconnect() { closed = true }
    override fun getResponseCode() = status
    override fun getOutputStream() = output
    override fun getInputStream(): java.io.InputStream = body.byteInputStream()
  }
  private fun response(content: String) = JSONObject().put("candidates", org.json.JSONArray().put(JSONObject()
    .put("finishReason", "STOP").put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", content)))))).toString()

  @Test fun structuredClientUsesTypedDraftInsteadOfTreatingMissingDataAsNetworkFailure() = runBlocking {
    val c = Connection(200, response("""{"kind":"draft","steps":[{"id":"s1","command":{"type":"timer","label":"Fideos"}}]}"""))
    val result = StructuredCommandClient({ "private-key" }, { c }).interpret(InterpretationRequest("un aviso para fideos")) as InterpretationResult.Incomplete
    assertEquals(setOf("seconds"), result.draft.steps.single().missing())
    assertEquals("Fideos", result.draft.steps.single().json().getString("label"))
    assertTrue(c.closed)
    assertFalse(c.instanceFollowRedirects)
    assertEquals("private-key", c.getRequestProperty("x-goog-api-key"))
    assertFalse(c.output.toString().contains("private-key"))
  }
  @Test fun bothClientsClassifyHttpFailuresWithoutProviderBodyLeakage() = runBlocking {
    for ((status, kind) in listOf(401 to GeminiFailureKind.AUTH, 403 to GeminiFailureKind.AUTH,
      429 to GeminiFailureKind.QUOTA, 404 to GeminiFailureKind.UNAVAILABLE, 405 to GeminiFailureKind.UNAVAILABLE,
      501 to GeminiFailureKind.UNAVAILABLE, 503 to GeminiFailureKind.SERVER, 302 to GeminiFailureKind.INVALID_RESPONSE)) {
      for (structured in listOf(false, true)) {
        val c = Connection(status, "private-key secret-provider-body")
        try {
          if (structured) StructuredCommandClient({ "private-key" }, { c }).interpret(InterpretationRequest("pedido"))
          else GeminiDecisionsClient({ "private-key" }, { c }).classify("pedido")
          fail("Expected HTTP rejection")
        } catch (error: DecisionException) {
          assertEquals(kind, error.kind)
          assertFalse(error.message.orEmpty().contains("private-key"))
          assertFalse(error.message.orEmpty().contains("secret-provider-body"))
          assertTrue(c.closed)
        }
      }
    }
  }
  @Test fun malformedOversizeAndFactoryErrorsAreSanitized() = runBlocking {
    for (body in listOf("not-json", "x".repeat(65537))) {
      val c = Connection(200, body)
      try { StructuredCommandClient({ "key" }, { c }).extract("pedido"); fail() }
      catch (error: DecisionException) { assertEquals(GeminiFailureKind.INVALID_RESPONSE, error.kind); assertTrue(c.closed) }
    }
    try { StructuredCommandClient({ "key" }, { error("private-key") }).extract("pedido"); fail() }
    catch (error: DecisionException) { assertEquals(GeminiFailureKind.NETWORK, error.kind); assertFalse(error.message.orEmpty().contains("private-key")) }
  }
  @Test fun cancellationDisconnectsStructuredRequestsAndDropsLateResponses() = runBlocking {
    val entered = java.util.concurrent.CountDownLatch(1)
    val released = java.util.concurrent.CountDownLatch(1)
    val c = object : Connection(200, response("""{"kind":"instinct","steps":[]}""")) {
      override fun getInputStream(): java.io.InputStream { entered.countDown(); released.await(); return super.getInputStream() }
      override fun disconnect() { super.disconnect(); released.countDown() }
    }
    val job = async(Dispatchers.Default) { StructuredCommandClient({ "key" }, { c }).extract("pedido") }
    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)); job.cancelAndJoin()
    assertTrue(job.isCancelled); assertTrue(c.closed)
  }
}
