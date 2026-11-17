package com.example.phone

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

  @Test fun liveProviderEvaluationIsExplicitAndDoesNotExecuteCommands() = runBlocking {
    assumeTrue("Opt in with INSTO_GEMINI_LIVE=1 and a local GEMINI_API_KEY", System.getenv("INSTO_GEMINI_LIVE") == "1")
    val key = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    assumeTrue("Local provider credential required", key != null)
    val parser = PhoneCommandParser(::now)
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, GeminiDecisionsClient({ key })), StructuredCommandClient({ key }))
    var parserCorrect = 0
    var hybridCorrect = 0
    var rejected = 0
    var adverseEffects = 0
    val durations = mutableListOf<Long>()
    val entries = corpus().filter { it.getString("group") != "context" }
    val before = GeminiDiagnostics.snapshot()
    for (item in entries) {
      val text = item.getString("text")
      val local = parser.parse(text)
      val start = System.nanoTime()
      val result = coordinator.interpret(InterpretationRequest(text, now = now()), local)
      durations += (System.nanoTime() - start) / 1000000
      val want = if (item.has("expected")) expected(item) else null
      val got = when (result) { is InterpretationResult.Direct -> listOf(result.command); is InterpretationResult.Plan -> result.commands; else -> null }
      if (want != null && (local as? PhoneRoute.Local)?.command?.let { listOf(it) } == want) parserCorrect++
      if (want != null && got == want) hybridCorrect++
      if (result is InterpretationResult.Rejected || result is InterpretationResult.Failure) rejected++
      if (item.getString("group") == "negative" && (got != null || result is InterpretationResult.Query)) adverseEffects++
    }
    val after = GeminiDiagnostics.snapshot()
    durations.sort()
    fun percentile(p: Double) = durations[((durations.size - 1) * p).toInt()]
    println("Gemini live corpus: cases=${entries.size}, parser_exact=$parserCorrect, hybrid_exact=$hybridCorrect, rejected=$rejected, decisions=${after.decisions - before.decisions}, extractions=${after.extractions - before.extractions}, p50_ms=${percentile(.5)}, p95_ms=${percentile(.95)}, adverse_routes=$adverseEffects")
    assertEquals("Negative cases must not execute or send", 0, adverseEffects)
    assertTrue("Hybrid must improve labelled command coverage over parser", hybridCorrect > parserCorrect)
  }
}
