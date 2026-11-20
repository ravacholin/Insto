package com.example.phone

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducible replay of provider routing: same corpus, same simulated providers, virtual time.
 *
 * Every provider call is counted when it starts, including speculative calls that are later cancelled, so wasted
 * requests are visible (GeminiDiagnostics counts a fallback only when it is used). Latencies are declared
 * simulation inputs, not field measurements: they show relative effects of routing, not real network time.
 */
class InterpretationRoutingMetricsTest {
  private data class Case(val text: String, val group: String, val choice: String?, val expected: List<PhoneCommand>?)

  private fun now(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 17, 12, 0) }

  private fun corpus(): List<Case> {
    val stream = requireNotNull(javaClass.getResourceAsStream("/gemini-es-corpus.json"))
    val data = JSONArray(stream.bufferedReader().use { it.readText() })
    return (0 until data.length()).map(data::getJSONObject).filter { it.getString("group") != "context" }.map { item ->
      Case(item.getString("text"), item.getString("group"), item.optString("choice").takeIf { it.isNotEmpty() },
        item.optJSONArray("expected")?.let { list -> (0 until list.length()).map { PhoneCommandCodec.decode(list.getJSONObject(it)) } })
    } + rioplatense
  }

  /** Added for this refactor: everyday Buenos Aires phrasing. Expected values are the intended reading. */
  private val rioplatense = listOf(
    Case("Prendeme la linterna", "local", null, listOf(PhoneCommand.Torch(true))),
    Case("Poneme una alarma a las siete y media", "clarify", null, null), // AM/PM is not stated: ask, never guess.
    Case("Dale, prendé la linterna y poné un timer de diez minutos", "compound", null,
      listOf(PhoneCommand.Torch(true), PhoneCommand.Timer(600))),
    Case("Che, bajá el volumen", "paraphrase", "volume_down", listOf(PhoneCommand.Volume(VolumeOp.DOWN))),
    Case("Tirame un timer de cinco minutos", "paraphrase", "timer", listOf(PhoneCommand.Timer(300))),
    Case("Necesito que se vea algo acá, está re oscuro", "paraphrase", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Bajale un toque al sonido", "paraphrase", "volume_down", listOf(PhoneCommand.Volume(VolumeOp.DOWN))),
    Case("Avisame cuando pasen diez minutos", "paraphrase", "timer", listOf(PhoneCommand.Timer(600))),
    Case("¿Cuál es la capital de Uruguay?", "query", "instinct", null),
    Case("Explicame qué es la fotosíntesis", "query", "instinct", null),
    Case("No prendas la linterna", "negative", null, null),
    Case("Ni se te ocurra poner una alarma", "negative", null, null),
    Case("Si llueve, prendé la linterna", "negative", null, null),
    Case("Suponete que apagás el wifi", "negative", null, null),
    Case("Imaginate que prendés la linterna", "negative", null, null),
    Case("Supongamos que bajás el volumen", "negative", null, null),
    Case("No, no le mandes nada a Ana", "negative", null, null),
  )

  private enum class Fault { NONE, SERVER, TIMEOUT }

  private data class Scenario(
    val name: String,
    val decisions: Fault = Fault.NONE,
    val extraction: Fault = Fault.NONE,
    val groq: Fault = Fault.NONE,
    val decisionsUnsupported: Boolean = false,
  )

  private val scenarios = listOf(
    Scenario("healthy"),
    Scenario("decisions_5xx", decisions = Fault.SERVER),
    Scenario("decisions_timeout", decisions = Fault.TIMEOUT),
    Scenario("decisions_unsupported", decisionsUnsupported = true),
    Scenario("extraction_5xx", extraction = Fault.SERVER),
    Scenario("extraction_timeout", extraction = Fault.TIMEOUT),
    Scenario("all_providers_down", decisions = Fault.SERVER, extraction = Fault.SERVER, groq = Fault.SERVER),
  )

  /** Declared simulation latencies (ms of virtual time). */
  private object Latency { const val DECISIONS = 700L; const val EXTRACTION = 900L; const val GROQ = 800L; const val VERIFY = 400L; const val ERROR = 250L }

  private class Counts { var decisions = 0; var extraction = 0; var groq = 0; var verify = 0 }

  private class Summary(val label: String) {
    var cases = 0; var positive = 0; var correct = 0; var incorrect = 0; var falseRejections = 0
    var unsafe = 0; var wrongInstinct = 0; var lostParameters = 0
    val counts = Counts()
    val latencies = mutableListOf<Long>()
    /** Only requests that reached at least one provider. */
    val providerLatencies = mutableListOf<Long>()
    fun p(q: Double, values: List<Long> = latencies) = values.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * q).toInt()] }
    fun total() = counts.decisions + counts.extraction + counts.groq + counts.verify
    fun line(): String = String.format(Locale.ROOT,
      "ROUTING_METRICS %s cases=%d correct=%d/%d (%.2f%%) incorrect_accepted=%d false_rejections=%d unsafe=%d wrong_instinct=%d " +
        "lost_parameters=%d decisions=%d gemini_extraction=%d groq=%d groq_verify=%d calls_per_request=%.3f p50_ms=%d p95_ms=%d " +
        "provider_requests=%d provider_p50_ms=%d provider_p95_ms=%d",
      label, cases, correct, positive, 100.0 * correct / positive, incorrect, falseRejections, unsafe, wrongInstinct,
      lostParameters, counts.decisions, counts.extraction, counts.groq, counts.verify,
      total().toDouble() / cases, p(.5), p(.95), providerLatencies.size, p(.5, providerLatencies), p(.95, providerLatencies))
  }

  private suspend fun fault(fault: Fault, provider: String) {
    when (fault) {
      Fault.NONE -> Unit
      Fault.SERVER -> { delay(Latency.ERROR); throw DecisionException("$provider 5xx", GeminiFailureKind.SERVER) }
      Fault.TIMEOUT -> awaitCancellation()
    }
  }

  private suspend fun TestScope.replay(scenario: Scenario, speculation: FallbackSpeculation): Summary {
    val summary = Summary("${scenario.name} ${speculation.name.lowercase(Locale.ROOT)}")
    val parser = PhoneCommandParser(::now)
    // One breaker per run, on virtual time, as one app process would keep it across requests.
    val breaker = DecisionsCircuitBreaker(clock = { currentTime })
    for (case in corpus()) {
      val counts = summary.counts
      val misleading = listOf(PhoneCommand.Torch(true))
      val decisions = DecisionClassifier {
        counts.decisions++
        delay(Latency.DECISIONS)
        fault(scenario.decisions, "Decisions")
        when (case.group) {
          "query" -> SemanticDecision("instinct", .99, .99, .99, .99)
          "paraphrase" -> if (scenario.decisionsUnsupported) SemanticDecision("unsupported", .99, .99, .99, .99)
            else SemanticDecision(case.choice!!, .93, .93, .99, .99)
          "negative" -> SemanticDecision("torch_on", .93, .93, .99, .99) // Deliberately misleading.
          else -> SemanticDecision("unsupported", .99, .99, .99, .99)
        }
      }
      val extraction = StructuredCommandExtractor {
        counts.extraction++
        delay(Latency.EXTRACTION)
        fault(scenario.extraction, "Gemini")
        if (case.group == "negative") misleading else case.expected ?: error("No proposal")
      }
      val groq = object : StructuredCommandExtractor {
        override suspend fun extract(text: String): List<PhoneCommand> {
          counts.groq++
          delay(Latency.GROQ)
          fault(scenario.groq, "Groq")
          return if (case.group == "negative") misleading else case.expected ?: error("No proposal")
        }
        override suspend fun interpret(request: InterpretationRequest): InterpretationResult =
          if (case.group == "query") { counts.groq++; delay(Latency.GROQ); fault(scenario.groq, "Groq"); InterpretationResult.Query() }
          else InterpretationResult.Plan(extract(request.modelInput()), fallback = true)
      }
      val verifier = IntentVerifier { _, _ ->
        counts.verify++
        delay(Latency.VERIFY)
        fault(scenario.groq, "Groq")
        case.group == "paraphrase"
      }
      val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, decisions), extraction, verifier = verifier,
        fallback = groq, breaker = breaker, speculation = speculation)
      val started = currentTime
      val callsBefore = summary.total()
      val result = coordinator.interpret(InterpretationRequest(case.text, now = now()), parser.parse(case.text))
      summary.latencies += currentTime - started
      if (summary.total() > callsBefore) summary.providerLatencies += currentTime - started
      record(summary, case, result)
      // Requests are separated by a human pause; the breaker's recovery window runs on the same clock.
      delay(5_000)
    }
    return summary
  }

  private fun record(summary: Summary, case: Case, result: InterpretationResult) {
    summary.cases++
    val got = when (result) {
      is InterpretationResult.Direct -> listOf(result.command)
      is InterpretationResult.Plan -> result.commands
      else -> null
    }
    val query = result is InterpretationResult.Query
    if (case.group == "negative") {
      if (got != null || query) { summary.unsafe++; summary.incorrect++; println("UNSAFE ${summary.label} «${case.text}» -> $result") }
      return
    }
    summary.positive++
    when {
      case.group == "query" && query || case.expected != null && got == case.expected ||
        case.group == "clarify" && result is InterpretationResult.Clarification -> summary.correct++
      query -> { summary.wrongInstinct++; summary.incorrect++ }
      got != null -> {
        summary.incorrect++
        if (case.expected != null && got.size == case.expected.size && got.map { it::class } == case.expected.map { it::class })
          summary.lostParameters++
      }
      else -> { summary.falseRejections++; println("REJECTED ${summary.label} «${case.text}» -> $result") }
    }
  }

  @Test fun speculativeGroqIsMeasuredAgainstNeedTriggeredFallback() = runTest {
    val results = mutableMapOf<Pair<String, FallbackSpeculation>, Summary>()
    for (scenario in scenarios) for (policy in FallbackSpeculation.entries) {
      results[scenario.name to policy] = replay(scenario, policy).also { println(it.line()) }
    }
    for (scenario in scenarios) {
      val always = results.getValue(scenario.name to FallbackSpeculation.ALWAYS)
      for (policy in FallbackSpeculation.entries) {
        val summary = results.getValue(scenario.name to policy)
        // Safety never depends on the speculation policy.
        assertEquals(summary.label, 0, summary.unsafe)
        assertEquals(summary.label, 0, summary.incorrect)
        assertEquals(summary.label, 0, summary.lostParameters)
        // The chosen default must not lose a single correct interpretation compared with the original.
        if (policy == FallbackSpeculation.WHEN_DEGRADED) assertTrue(summary.label, summary.correct >= always.correct)
      }
    }
    val healthyAlways = results.getValue("healthy" to FallbackSpeculation.ALWAYS)
    val healthyDefault = results.getValue("healthy" to FallbackSpeculation.WHEN_DEGRADED)
    assertTrue("Healthy providers: speculation must not spend more Groq calls than needed",
      healthyDefault.counts.groq < healthyAlways.counts.groq)
    for (summary in results.values) {
      val total = summary.counts.decisions + summary.counts.extraction + summary.counts.groq + summary.counts.verify
      val always = results.getValue(summary.label.substringBefore(' ') to FallbackSpeculation.ALWAYS)
      val alwaysTotal = always.counts.decisions + always.counts.extraction + always.counts.groq + always.counts.verify
      assertTrue(summary.label, total <= alwaysTotal)
    }
  }

  @Test fun locallyKnownOrdersNeverReachAProvider() = runTest {
    for (scenario in scenarios) {
      val summary = Summary("local ${scenario.name}")
      val parser = PhoneCommandParser(::now)
      for (case in corpus().filter { it.group == "local" }) {
        val coordinator = GeminiCoordinator(
          HybridPhoneRouter({ true }, DecisionClassifier { summary.counts.decisions++; error("No provider for a local order") }),
          StructuredCommandExtractor { summary.counts.extraction++; error("No provider for a local order") },
          verifier = IntentVerifier { _, _ -> summary.counts.verify++; false },
          fallback = StructuredCommandExtractor { summary.counts.groq++; error("No provider for a local order") },
          speculation = FallbackSpeculation.ALWAYS)
        val result = coordinator.interpret(InterpretationRequest(case.text, now = now()), parser.parse(case.text))
        record(summary, case, result)
      }
      assertEquals(summary.label, summary.positive, summary.correct)
      assertEquals(summary.label, 0, summary.counts.decisions + summary.counts.extraction + summary.counts.groq + summary.counts.verify)
    }
  }

}
