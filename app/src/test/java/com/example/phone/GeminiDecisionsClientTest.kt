package com.example.phone

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiDecisionsClientTest {
  private open class Connection(val code: Int, val body: String) : HttpURLConnection(URL("https://generativelanguage.googleapis.com/v1beta/decisions")) {
    val output = ByteArrayOutputStream()
    var disconnected = false
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = code
    override fun getInputStream(): java.io.InputStream = body.byteInputStream()
    override fun getOutputStream() = output
  }
  private fun response(): JSONObject {
    val probabilities = JSONObject()
    GeminiDecisionsClient.choices.keys.forEach { probabilities.put(it, if (it == "torch_on") .99 else .01 / (GeminiDecisionsClient.choices.size - 1)) }
    return JSONObject().put("answers", JSONObject()
      .put("action", JSONObject().put("type", "choice").put("choice", "torch_on").put("probabilities", probabilities).put("confidence", .99))
      .put("unconditional", JSONObject().put("type", "noul").put("noul", .99).put("confidence", .99)))
  }
  @Test fun postsTextOnlyWithHeaderAndTypedQuestions() = runBlocking {
    val c = Connection(200, response().toString())
    val result = GeminiDecisionsClient({ "personal-key" }, { c }).classify("iluminá acá")
    assertEquals("torch_on", result.choice)
    assertEquals("personal-key", c.getRequestProperty("x-goog-api-key"))
    assertFalse(c.instanceFollowRedirects)
    val request = JSONObject(c.output.toString("UTF-8"))
    assertEquals("gemini-decision-1", request.getString("model"))
    assertEquals("iluminá acá", request.getString("input"))
    assertEquals("choice", request.getJSONObject("questions").getJSONObject("action").getString("type"))
    assertEquals(LocalIntentRegistry.default.choices.keys,request.getJSONObject("questions").getJSONObject("action").getJSONObject("criteria").keys().asSequence().toSet())
    assertFalse(request.has("generationConfig"))
    assertFalse(request.toString().contains("personal-key"))
    assertTrue(c.disconnected)
  }
  @Test fun rejectsMissingFieldsWrongTypesAndContradictoryProbabilities() = runBlocking {
    val wrongChoice = response().apply { getJSONObject("answers").getJSONObject("action").put("choice", "evil") }
    val wrongTop = response().apply { getJSONObject("answers").getJSONObject("action").getJSONObject("probabilities").put("torch_off", 1) }
    val wrongNumber = response().apply { getJSONObject("answers").getJSONObject("action").put("confidence", "0.99") }
    for (body in listOf("{}", "not-json", wrongChoice.toString(), wrongTop.toString(), wrongNumber.toString(), "x".repeat(65537))) {
      try { GeminiDecisionsClient({ "key" }, { Connection(200, body) }).classify("pedido"); fail(body.take(20)) }
      catch (_: DecisionException) { }
    }
  }
  @Test fun providerErrorsDoNotExposeKeyOrBodyOrRetry() = runBlocking {
    for (code in listOf(301, 401, 403, 429, 500)) {
      var calls = 0
      val c = Connection(code, "personal-key private-provider-error")
      try { GeminiDecisionsClient({ "personal-key" }, { calls++; c }).classify("pedido"); fail("Must fail") }
      catch (e: DecisionException) {
        assertFalse(e.message.orEmpty().contains("personal-key"))
        assertFalse(e.message.orEmpty().contains("private-provider-error"))
        assertEquals(1, calls)
        assertTrue(c.disconnected)
      }
    }
  }
  @Test fun missingKeyDoesNotConnect() = runBlocking {
    try { GeminiDecisionsClient({ null }, { error("Must not connect") }).classify("pedido"); fail("Must fail") }
    catch (_: DecisionException) { }
  }
  @Test fun cancellationDisconnectsBlockedRequestAndCannotDeliverAnAnswer() = runBlocking {
    val entered = java.util.concurrent.CountDownLatch(1)
    val released = java.util.concurrent.CountDownLatch(1)
    val c = object : Connection(200, response().toString()) {
      override fun getInputStream(): java.io.InputStream {
        entered.countDown()
        released.await()
        return super.getInputStream()
      }
      override fun disconnect() { super.disconnect(); released.countDown() }
    }
    val job = async(Dispatchers.Default) { GeminiDecisionsClient({ "key" }, { c }).classify("pedido") }
    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
    job.cancelAndJoin()
    assertTrue(job.isCancelled)
    assertTrue(c.disconnected)
  }
}
