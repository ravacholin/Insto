package com.example.phone

import java.util.Locale
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Offline checks verify policy and state; only the opt-in run measures provider understanding. */
class GeminiCorpusTest {
  private fun corpus(): List<JSONObject> {
    val stream = requireNotNull(javaClass.getResourceAsStream("/gemini-es-corpus.json"))
    val data = JSONArray(stream.bufferedReader().use { it.readText() })
    return (0 until data.length()).map(data::getJSONObject)
  }
  private fun now(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 17, 12, 0) }
  private fun expected(item: JSONObject): List<PhoneCommand> = item.getJSONArray("expected").let { list ->
    (0 until list.length()).map { PhoneCommandCodec.decode(list.getJSONObject(it)) }
  }
  @Test fun labelledLocalCommandsAndQueriesRetainTheirRouteWithoutNetwork() = runBlocking {
    val entries = corpus()
    assertTrue(entries.size >= 120)
    val parser = PhoneCommandParser(::now)
    val router = HybridPhoneRouter({ false }, DecisionClassifier { error("Local-only mode must not call Google") })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor { error("Local-only mode must not extract") })
    for (item in entries.filter { it.getString("group") in listOf("local", "query") }) {
      val text = item.getString("text")
      val local = parser.parse(text)
      if (item.getString("group") == "local") assertEquals(text, InterpretationResult.Direct(expected(item).single()), coordinator.interpret(InterpretationRequest(text), local))
      else assertEquals(text, InterpretationResult.Query(), coordinator.interpret(InterpretationRequest(text), local))
    }
  }
  @Test fun adverseRequestsNeverExecuteOrBecomeInstinctMessages() = runBlocking {
    val parser = PhoneCommandParser(::now)
    val router = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("unsupported", .99, .99, .99, .99) })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor { error("Negative request must not extract") })
    for (item in corpus().filter { it.getString("group") == "negative" }) {
      val text = item.getString("text")
      val local = parser.parse(text)
      val result = coordinator.interpret(InterpretationRequest(text, now = now()), local)
      assertTrue("Unsafe route for: $text -> $result", result is InterpretationResult.Rejected || result is InterpretationResult.Clarification)
    }
  }
  @Test fun contextCasesPreserveFieldsAndResolveTheCorrectStep() {
    for (item in corpus().filter { it.getString("group") == "context" }) {
      val initial = item.getJSONArray("draft")
      var draft = DraftPlan((0 until initial.length()).map { CommandDraft("s${it + 1}", initial.getJSONObject(it)) })
      val turns = item.getJSONArray("turns")
      var last: InterpretationResult? = null
      for (index in 0 until turns.length()) {
        last = draft.reply(turns.getString(index), now())
        if (last is InterpretationResult.Incomplete) draft = last.draft
      }
      assertTrue(item.toString(), last is InterpretationResult.Plan)
      assertEquals(item.toString(), expected(item), (last as InterpretationResult.Plan).commands)
    }
  }
  @Test fun paraphraseFixturesExerciseEverySemanticCapabilityWithoutClaimingProviderAccuracy() = runBlocking {
    for (item in corpus().filter { it.getString("group") == "paraphrase" }) {
      val choice = item.getString("choice")
      val want = expected(item)
      val router = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision(choice, .99, .99, .99, .99) })
      val coordinator = GeminiCoordinator(router, StructuredCommandExtractor { want })
      val result = coordinator.interpret(InterpretationRequest(item.getString("text"), now = now()), PhoneRoute.Instinct)
      val got = when (result) { is InterpretationResult.Direct -> listOf(result.command); is InterpretationResult.Plan -> result.commands; else -> null }
      assertEquals(item.getString("text"), want, got)
    }
  }

  @Test fun comparativeReplayUsesTheSameSpanishFixturesAtPoint93() = runBlocking {
    val entries = corpus().filter { it.getString("group") != "context" }
    suspend fun replay(label: String, policy: DecisionsPolicy): Evaluation {
      val parser = PhoneCommandParser(::now)
      val summary = Evaluation()
      val before = GeminiDiagnostics.snapshot()
      for (item in entries) {
        val group = item.getString("group")
        val text = item.getString("text")
        val choice = when (group) {
          "query" -> "instinct"
          "paraphrase" -> item.getString("choice")
          "negative" -> "torch_on" // Deliberately misleading response.
          else -> "unsupported"
        }
        val score = if (group == "query") .99 else .93
        val router = HybridPhoneRouter({ true }, DecisionClassifier {
          SemanticDecision(choice, score, score, .99, .99)
        }, policy = policy)
        val c = GeminiCoordinator(router, StructuredCommandExtractor { expected(item) },
          verifier = IntentVerifier { _, _ -> group == "paraphrase" })
        summary.record(item, c.interpret(InterpretationRequest(text, now = now()), parser.parse(text)))
      }
      summary.report(label, before)
      return summary
    }
    val strict = replay("replay 0.95", DecisionsPolicy(.95, .95))
    val tiered = replay("replay escalonado", DecisionsPolicy())
    assertTrue(tiered.correct > strict.correct)
    assertEquals(0, tiered.incorrect)
    assertEquals(0, tiered.unsafeRoutes)
  }

  @Test fun liveProviderEvaluationIsExplicitAndDoesNotExecuteCommands() = runBlocking {
    assumeTrue("Opt in with INSTO_GEMINI_LIVE=1", System.getenv("INSTO_GEMINI_LIVE") == "1")
    val key = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    val groqKey = System.getenv("GROQ_API_KEY")?.takeIf { it.isNotBlank() }
    assumeTrue("Gemini and Groq credentials required for comparison", key != null && groqKey != null)
    val parser = PhoneCommandParser(::now)
    val groq = GroqIntentVerifier({ groqKey })
    val entries = corpus().filter { it.getString("group") != "context" }
    suspend fun measure(label: String, policy: DecisionsPolicy, verifier: IntentVerifier?): Evaluation {
      val coordinator = GeminiCoordinator(
        HybridPhoneRouter({ true }, GeminiDecisionsClient({ key }), policy = policy),
        StructuredCommandClient({ key }), verifier = verifier, fallback = groq,
      )
      val summary = Evaluation()
      val before = GeminiDiagnostics.snapshot()
      for (item in entries) {
        val text = item.getString("text")
        val local = parser.parse(text)
        val start = System.nanoTime()
        val result = coordinator.interpret(InterpretationRequest(text, now = now()), local)
        summary.durations += (System.nanoTime() - start) / 1_000_000
        summary.record(item, result)
      }
      summary.report(label, before)
      return summary
    }
    val strict = measure("live 0.95", DecisionsPolicy(.95, .95), null)
    val tiered = measure("live escalonado", DecisionsPolicy(), groq)
    assertEquals("Negative cases must not execute or send", 0, tiered.unsafeRoutes)
    assertTrue("Tiered policy must not accept more incorrect proposals", tiered.incorrect <= strict.incorrect)
  }

  private inner class Evaluation {
    var cases = 0
    var positive = 0
    var correct = 0
    var falseRejections = 0
    var incorrect = 0
    var unsafeRoutes = 0
    val durations = mutableListOf<Long>()

    fun record(item: JSONObject, result: InterpretationResult) {
      cases++
      val want = if (item.has("expected")) expected(item) else null
      val got = when (result) {
        is InterpretationResult.Direct -> listOf(result.command)
        is InterpretationResult.Plan -> result.commands
        else -> null
      }
      val query = result is InterpretationResult.Query
      if (item.getString("group") == "negative") {
        if (got != null || query) { unsafeRoutes++; incorrect++ }
        return
      }
      positive++
      val expectedQuery = item.getString("group") == "query"
      when {
        expectedQuery && query || want != null && got == want -> correct++
        got != null || query -> incorrect++
        else -> falseRejections++
      }
    }

    fun report(label: String, before: GeminiDiagnostics.Snapshot) {
      val after = GeminiDiagnostics.snapshot()
      val calls = (after.decisions - before.decisions) + (after.extractions - before.extractions) +
        (after.fallbacks - before.fallbacks) + (after.verifications - before.verifications)
      println(String.format(Locale.ROOT,
        "%s cases=%d positive=%d correct=%d (%.2f%%) false_rejections=%d (%.2f%%) incorrect=%d (%.2f%%) calls_per_request=%.2f",
        label, cases, positive, correct, 100.0 * correct / positive, falseRejections, 100.0 * falseRejections / positive,
        incorrect, 100.0 * incorrect / cases, calls.toDouble() / cases))
      if (durations.isNotEmpty()) {
        durations.sort()
        println("$label p50_ms=${durations[(durations.size - 1) / 2]} p95_ms=${durations[((durations.size - 1) * .95).toInt()]}")
      }
    }
  }
}
