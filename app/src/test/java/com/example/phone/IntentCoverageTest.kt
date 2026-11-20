package com.example.phone

import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTestDataStore
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentCoverageTest {
  private data class Case(
    val text: String,
    val group: String,
    val choice: String = "unsupported",
    val commands: List<PhoneCommand> = emptyList(),
  )

  private val extra = listOf(
    Case("Prendé la linterna", "direct", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Apagá la linterna", "direct", "torch_off", listOf(PhoneCommand.Torch(false))),
    Case("Poné un timer de siete minutos", "direct", "timer", listOf(PhoneCommand.Timer(420))),
    Case("Subí el volumen", "direct", "volume_up", listOf(PhoneCommand.Volume(VolumeOp.UP))),
    Case("Pasá a la siguiente canción", "direct", "media_next", listOf(PhoneCommand.Media(MediaOp.NEXT))),
    Case("Abrí Spotify", "direct", "app_open", listOf(PhoneCommand.OpenApp("spotify"))),
    Case("Dame un poco de luz", "alternative", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Bajá un poquito el sonido", "alternative", "volume_down", listOf(PhoneCommand.Volume(VolumeOp.DOWN))),
    Case("Necesito una cuenta regresiva de diez minutos", "alternative", "timer", listOf(PhoneCommand.Timer(600))),
    Case("Poné algo de los Beatles", "alternative", "music", listOf(PhoneCommand.MusicSearch("los Beatles"))),
    Case("Abrí música", "ambiguous"),
    Case("Apagá la luz", "ambiguous"),
    Case("Bajá Spotify", "ambiguous"),
    Case("Detené todo", "ambiguous"),
    Case("No prendas la linterna", "restriction", "torch_on"),
    Case("¿Cómo se prende la linterna?", "query", "instinct"),
    Case("Si fuera de noche, prendería la linterna", "restriction", "torch_on"),
    Case("Explicame qué significa prender la linterna", "query", "instinct"),
    Case("Escribí un cuento sobre alguien que pone una alarma", "query", "instinct"),
    Case("Apagá la linterna dentro de veinte minutos", "restriction", "torch_off"),
    Case("Explicame la Revolución francesa", "instinct", "instinct"),
    Case("Investigá las últimas noticias de inteligencia artificial", "instinct", "instinct"),
    Case("Prepará un análisis de este texto", "instinct", "instinct"),
    Case("Organizame una investigación compleja", "instinct", "instinct"),
  )

  private fun now() = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(2026, Calendar.NOVEMBER, 17, 12, 0)
  }

  private val parser = PhoneCommandParser(::now)

  private data class Observation(
    val case: Case,
    val result: InterpretationResult,
    val decisions: List<String>,
    val extractions: Int,
  ) {
    val commands: List<PhoneCommand> get() = when (result) {
      is InterpretationResult.Direct -> listOf(result.command)
      is InterpretationResult.Plan -> result.commands
      else -> emptyList()
    }
    val executes: Boolean get() = commands.isNotEmpty()
    val route: String get() = when (result) {
      is InterpretationResult.Direct -> "Direct ${PhoneCommandCodec.encode(result.command)}"
      is InterpretationResult.Plan -> "Plan ${commands.map(PhoneCommandCodec::encode)}"
      is InterpretationResult.Query -> "Query"
      is InterpretationResult.Clarification -> "Clarification ${result.context}"
      is InterpretationResult.Rejected -> "Rejected"
      is InterpretationResult.Incomplete -> "Incomplete"
      is InterpretationResult.Failure -> "Failure ${result.kind}"
    }
    val local: Boolean get() = decisions.isEmpty() && extractions == 0
  }

  private suspend fun observe(case: Case): Observation {
    val decisions = mutableListOf<String>()
    var extractions = 0
    val router = HybridPhoneRouter({ true }, DecisionClassifier {
      decisions += case.choice
      SemanticDecision(case.choice, .99, .99, .99, .99)
    })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor {
      extractions++
      case.commands
    })
    val result = coordinator.interpret(InterpretationRequest(case.text, now = now()), parser.parse(case.text))
    return Observation(case, result, decisions, extractions)
  }

  @Test fun newCasesFollowTheirExpectedRoutes() = runBlocking {
    val failures = extra.mapNotNull { case ->
      val result = observe(case)
      val passes = when (case.group) {
        "direct" -> result.local && result.result is InterpretationResult.Direct && result.commands == case.commands
        "alternative" -> result.commands == case.commands && (result.local || result.decisions == listOf(case.choice))
        "ambiguous" -> !result.executes && (result.result is InterpretationResult.Clarification ||
          result.decisions.isNotEmpty())
        "restriction" -> result.result is InterpretationResult.Rejected ||
          result.result is InterpretationResult.Clarification
        "query", "instinct" -> result.result == InterpretationResult.Query()
        else -> error("Unexpected group: ${case.group}")
      }
      if (passes) null else "${case.text}: ${result.route}, Decisions=${result.decisions}"
    }
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
  }

  @Test fun explicitQueriesAndAmbiguousTargetsCannotBeTurnedIntoActionsByDecisions() = runBlocking {
    val misleadingChoices = mapOf(
      "Abrí música" to "app_open",
      "Apagá la luz" to "torch_off",
      "Bajá Spotify" to "volume_down",
      "Detené todo" to "workflow",
    )
    val queriesAndAmbiguous = extra.filter { it.group in setOf("ambiguous", "query", "instinct") } +
      Case("Escribime un cuento sobre alguien que pone una alarma", "query")
    val failures = queriesAndAmbiguous.mapNotNull { case ->
      val result = observe(case.copy(choice = misleadingChoices[case.text] ?: "torch_on",
        commands = listOf(PhoneCommand.OpenApp("Spotify"), PhoneCommand.Torch(false))))
      val expectedRoute = if (case.group == "ambiguous") result.result is InterpretationResult.Clarification
        else result.result == InterpretationResult.Query()
      if (result.local && expectedRoute) null else "${case.text}: ${result.route}, Decisions=${result.decisions}"
    }
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
  }

  @Test fun localExpansionsDoNotDropTimingRestrictionsOrReclassifyOtherTargets() {
    for (text in listOf("Prepará el marcador para Ana mañana", "Prepará el marcador para Ana a las ocho",
      "Poné algo de Netflix", "Poné algo de comer")) {
      assertFalse(text, parser.parse(text) is PhoneRoute.Local)
    }
  }

  @Test fun offlineMetrics() = runBlocking {
    val stream = requireNotNull(javaClass.getResourceAsStream("/gemini-es-corpus.json"))
    val corpus = JSONArray(stream.bufferedReader().use { it.readText() }).objects()
    val observations = buildList {
      for (item in corpus) {
        val group = item.getString("group")
        if (group == "context") {
          // The corpus's draft continuations enter DraftPlan.reply directly.
          var draft = DraftPlan(item.getJSONArray("draft").objects().mapIndexed { i, json ->
            CommandDraft("s${i + 1}", json)
          })
          val turns = item.getJSONArray("turns")
          for (i in 0 until turns.length()) {
            val turn = turns.getString(i)
            val result = requireNotNull(draft.reply(turn, now()))
            add(Observation(Case(turn, group), result, emptyList(), 0))
            if (result is InterpretationResult.Incomplete) draft = result.draft
          }
        } else {
          val choice = when (group) {
            "query" -> "instinct"
            "negative" -> "torch_on"
            else -> item.optString("choice", "unsupported")
          }
          val commands = item.optJSONArray("expected")?.objects().orEmpty().map(PhoneCommandCodec::decode)
          add(observe(Case(item.getString("text"), group, choice, commands)))
        }
      }
      for (case in extra) add(observe(case))
    }
    val sizes = observations.map { observation ->
      val bytes = GeminiDecisionsClient.buildRequest(observation.case.text).toString().toByteArray(Charsets.UTF_8).size
      println("${observation.case.group}\t${observation.case.text}\t${observation.route}\tdecisions=${observation.decisions.joinToString()}\textractions=${observation.extractions}\tbytes=$bytes")
      bytes
    }
    val texts = observations.filter { it.case.group != "context" }.map { it.case.text }
    repeat(10) { texts.forEach(parser::parse) }
    val nanos = buildList {
      repeat(30) {
        for (text in texts) {
          val start = System.nanoTime()
          parser.parse(text)
          add(System.nanoTime() - start)
        }
      }
    }.sorted()
    val local = observations.count { it.local && (it.executes ||
      it.result is InterpretationResult.Query || it.result is InterpretationResult.Clarification) }
    val calls = observations.sumOf { it.decisions.size }
    val falsePositives = observations.count {
      it.case.group in setOf("negative", "restriction", "ambiguous", "query", "instinct") && it.executes
    }
    val incorrectInstinct = observations.count {
      it.result is InterpretationResult.Query && it.case.group !in setOf("query", "instinct")
    }
    val missedInstinct = observations.count {
      it.case.group in setOf("query", "instinct") && it.result !is InterpretationResult.Query
    }
    val instinctExecutions = observations.count { it.case.group == "instinct" && it.executes }
    println(String.format(Locale.ROOT,
      "METRICS requests=%d local=%d (%.2f%%) decisions=%d calls_per_request=%.4f mean_bytes=%.2f min_bytes=%d max_bytes=%d p50_ns=%d p95_ns=%d false_positives=%d incorrect_instinct=%d missed_instinct=%d instinct_executions=%d",
      observations.size, local, 100.0 * local / observations.size, calls, calls.toDouble() / observations.size,
      sizes.average(), sizes.min(), sizes.max(), nanos.percentile(.50), nanos.percentile(.95),
      falsePositives, incorrectInstinct, missedInstinct, instinctExecutions))
  }

  /** Phrases a user taught (manual) or confirmed (automatic) before the measurement. */
  private data class Seed(val text: String, val command: PhoneCommand, val manual: Boolean, val launched: String? = null)

  private val seeds = listOf(
    Seed("Modo cine", PhoneCommand.Volume(VolumeOp.SET, 20), manual = true),
    Seed("Luz del pasillo", PhoneCommand.Torch(true), manual = true),
    Seed("El fideo en 10 minutos", PhoneCommand.Timer(600), manual = true),
    Seed("Tocá diana a las 6:30", PhoneCommand.Alarm(6, 30), manual = true),
    Seed("Prende modo fiesta", PhoneCommand.Torch(true), manual = true),
    Seed("Prender modo fiesta", PhoneCommand.Media(MediaOp.PLAY), manual = true),
    Seed("Dejalo tranqui en 20", PhoneCommand.Volume(VolumeOp.SET, 20), manual = true),
    Seed("Bajá la música al 30", PhoneCommand.Volume(VolumeOp.SET, 30), manual = false),
    Seed("Poneme la app de Netflix", PhoneCommand.OpenApp("netflix"), manual = false, launched = "com.netflix.mediaclient"),
    Seed("Iluminame el cuarto", PhoneCommand.Torch(true), manual = false),
    Seed("Cortá la música un toque", PhoneCommand.Media(MediaOp.PAUSE), manual = false),
    Seed("Arrancá a contar el tiempo", PhoneCommand.Stopwatch(StopwatchOp.START), manual = false),
  )
  private val installedApps = mapOf(
    "netflix" to PhoneCommand.OpenApp("netflix", "com.netflix.mediaclient"),
    "spotify" to PhoneCommand.OpenApp("spotify", "com.spotify.music"),
    "youtube" to PhoneCommand.OpenApp("youtube", "com.google.android.youtube"),
  )

  /** Requests measured with the seeded phrases. [choice] is what a plausible classifier would answer. */
  private val learnedCases = listOf(
    Case("Che, modo cine", "learned_rioplatense", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 20))),
    Case("Dale, luz del pasillo porfa", "learned_rioplatense", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Che, iluminame el cuarto", "learned_rioplatense", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Che, cortá la música un toque", "learned_rioplatense", "media_pause", listOf(PhoneCommand.Media(MediaOp.PAUSE))),
    Case("Bueno, arrancá a contar el tiempo", "learned_rioplatense", "stopwatch_start", listOf(PhoneCommand.Stopwatch(StopwatchOp.START))),
    Case("A ver, el fideo en 10 minutos", "learned_rioplatense", "timer", listOf(PhoneCommand.Timer(600))),
    Case("Modo cine por favor", "learned_rioplatense", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 20))),
    Case("Bajá la música al 50", "learned_param", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 50))),
    Case("Bajá la música al veinte", "learned_param", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 20))),
    Case("Dejalo tranqui en 35", "learned_param", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 35))),
    Case("El fideo en 12 minutos", "learned_param", "timer", listOf(PhoneCommand.Timer(720))),
    Case("El fideo en media hora", "learned_param", "timer", listOf(PhoneCommand.Timer(1800))),
    Case("Tocá diana a las 7:15", "learned_param", "alarm", listOf(PhoneCommand.Alarm(7, 15))),
    Case("Poneme la app de Spotify", "learned_param", "app_open", listOf(PhoneCommand.OpenApp("spotify", "com.spotify.music"))),
    Case("Poneme la app de YouTube", "learned_param", "app_open", listOf(PhoneCommand.OpenApp("youtube", "com.google.android.youtube"))),
    Case("Bajar la música al 40", "learned_variant", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 40))),
    Case("Bajame la musica al 40", "learned_variant", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 40))),
    Case("Bajá la músca al 40", "learned_variant", "volume_set", listOf(PhoneCommand.Volume(VolumeOp.SET, 40))),
    Case("Luz del pasilo", "learned_variant", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Arrancar a contar el tiempo", "learned_variant", "stopwatch_start", listOf(PhoneCommand.Stopwatch(StopwatchOp.START))),
    Case("El fidoe en 10 minutos", "learned_variant", "timer", listOf(PhoneCommand.Timer(600))),
    Case("Prendé modo fiesta", "learned_variant", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Prendeme modo fiesta", "learned_ambiguous", "torch_on", listOf(PhoneCommand.Torch(true))),
    Case("Poneme la app de Mensajes", "learned_ambiguous", "app_open", listOf(PhoneCommand.OpenApp("mensajes"))),
    Case("Bajá la música al 150", "learned_ambiguous", "volume_set"),
    Case("Tocá diana a las 7", "learned_ambiguous", "alarm"),
    Case("Bajá la música al 30 mañana", "learned_ambiguous", "volume_set"),
    Case("Poneme la app de Netflix y Spotify", "learned_ambiguous", "app_open"),
    Case("Luz del pasillo y modo cine", "learned_ambiguous", "torch_on"),
    Case("Modo cena", "learned_ambiguous", "volume_set"),
    Case("No bajes la música al 30", "learned_negation", "volume_set"),
    Case("Bajá la música al 30 si no hay nadie", "learned_negation", "volume_set"),
    Case("Ni se te ocurra prender la luz del pasillo", "learned_negation", "torch_on"),
    Case("Che, no pongas modo cine", "learned_negation", "volume_set"),
    Case("Modo cine cuando llegue", "learned_negation", "volume_set"),
    Case("Luz del pasillo salvo que esté durmiendo", "learned_negation", "torch_on"),
    Case("¿Me ponés modo cine?", "learned_negation", "volume_set"),
    Case("Dale, no, luz del pasillo no", "learned_negation", "torch_on"),
  )

  private suspend fun seededStore(): LearnedPhraseStore {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { true }, resolveApp = { installedApps[it] })
    for (seed in seeds) {
      val request = InterpretationRequest(seed.text, now = now())
      if (seed.manual) {
        check(store.teach(requireNotNull(LearnedPhraseStore.key(request)), seed.command)) { seed.text }
      } else {
        store.learn(request, seed.command, true, PhoneActionResult("ok",
          outcome = if (seed.launched != null) PhoneActionOutcome.HANDOFF else PhoneActionOutcome.COMPLETED,
          launchedAppPackage = seed.launched))
      }
    }
    check(store.uiSettings.first().entries.size == seeds.size) { "Seeds were not all stored" }
    return store
  }

  private suspend fun observeLearned(case: Case, store: LearnedPhraseStore): Observation {
    val decisions = mutableListOf<String>()
    var extractions = 0
    val router = HybridPhoneRouter({ true }, DecisionClassifier {
      decisions += case.choice
      SemanticDecision(case.choice, .99, .99, .99, .99)
    })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor {
      extractions++
      case.commands
    }, lookupLearnedPhrase = store::lookup, lookupLearnedPattern = store::lookupPattern)
    val result = coordinator.interpret(InterpretationRequest(case.text, now = now()), parser.parse(case.text))
    return Observation(case, result, decisions, extractions)
  }

  private data class LearnedMetrics(val requests: Int, val local: Int, val decisions: Int, val restrictionFalsePositives: Int,
    val improperLocal: Int, val incorrectInstinct: Int)

  private suspend fun learnedMetrics(): LearnedMetrics {
    val store = seededStore()
    val observations = learnedCases.map { observeLearned(it, store) }
    for (o in observations) {
      println("${o.case.group}\t${o.case.text}\t${o.route}\tdecisions=${o.decisions.joinToString()}\textractions=${o.extractions}")
    }
    val guarded = setOf("learned_ambiguous", "learned_negation")
    return LearnedMetrics(
      requests = observations.size,
      local = observations.count { it.local && (it.executes || it.result is InterpretationResult.Clarification) },
      decisions = observations.sumOf { it.decisions.size },
      restrictionFalsePositives = observations.count { it.case.group == "learned_negation" && it.executes },
      improperLocal = observations.count {
        it.local && it.executes && (it.case.group in guarded || it.commands != it.case.commands)
      },
      incorrectInstinct = observations.count { it.result is InterpretationResult.Query },
    )
  }

  /** A learned phrase never turns a negation, a condition or an ambiguity into a local action. */
  @Test fun learnedPhrasesKeepRestrictionsAndAmbiguitiesOutOfLocalActions() = runBlocking {
    val metrics = learnedMetrics()
    println(String.format(Locale.ROOT,
      "LEARNED_METRICS requests=%d local=%d (%.2f%%) decisions=%d calls_per_request=%.4f restriction_false_positives=%d improper_local=%d incorrect_instinct=%d",
      metrics.requests, metrics.local, 100.0 * metrics.local / metrics.requests, metrics.decisions,
      metrics.decisions.toDouble() / metrics.requests, metrics.restrictionFalsePositives, metrics.improperLocal,
      metrics.incorrectInstinct))
    assertTrue("Acciones locales indebidas: ${metrics.improperLocal}", metrics.improperLocal == 0)
    assertTrue("Derivaciones incorrectas a Instinct: ${metrics.incorrectInstinct}", metrics.incorrectInstinct == 0)
    assertTrue("Falsos positivos en restricciones: ${metrics.restrictionFalsePositives}", metrics.restrictionFalsePositives == 0)
  }

  private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
  private fun List<Long>.percentile(p: Double) = this[ceil(size * p).toInt() - 1]
}
