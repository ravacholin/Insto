package com.example.data

import com.example.phone.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline comparison of the routing before and after learned structures and the brief operational context.
 * Providers are simulated: the counts are calls the coordinator would make, not network measurements.
 * «Before» runs the same coordinator without the two new stages, which is the previous routing order.
 */
class LocalLearningMetricsTest {
  private fun now() = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, 20, 6, 0) }

  private sealed interface Step {
    /** A request. [expected] null means it must not execute anything locally (negative case). */
    data class Ask(val text: String, val expected: PhoneCommand?, val contextual: Boolean = false, val clarify: Boolean = false) : Step
    data class Executed(val command: PhoneCommand, val result: PhoneActionResult) : Step
    data class Wait(val millis: Long) : Step
  }

  private val opened = { app: MediaApp -> Step.Executed(PhoneCommand.OpenApp(app.label.lowercase()),
    PhoneActionResult("Abriendo ${app.label}.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = app.packageName)) }

  /** Structures taught with three executed model interpretations each, before the evaluation starts. */
  private val training = listOf(
    "Tirame una alarma para las 8" to PhoneCommand.Alarm(8, 0),
    "Tirame una alarma para las 9 de la noche" to PhoneCommand.Alarm(21, 0),
    "Tirame una alarma para las 7:30" to PhoneCommand.Alarm(7, 30),
    "Haceme sonar el celu a las 6:15" to PhoneCommand.Alarm(6, 15),
    "Haceme sonar el celu a las 7 de la mañana" to PhoneCommand.Alarm(7, 0),
    "Haceme sonar el celu a las 23:00" to PhoneCommand.Alarm(23, 0),
    "Avisame en 10 minutos" to PhoneCommand.Timer(600),
    "Avisame en cinco minutos" to PhoneCommand.Timer(300),
    "Avisame en una hora" to PhoneCommand.Timer(3600),
    "Música al 40" to PhoneCommand.Volume(VolumeOp.SET, 40),
    "Música al 55 por ciento" to PhoneCommand.Volume(VolumeOp.SET, 55),
    "Música al 70%" to PhoneCommand.Volume(VolumeOp.SET, 70),
    "Dejame el sonido en 20" to PhoneCommand.Volume(VolumeOp.SET, 20),
    "Dejame el sonido en 35" to PhoneCommand.Volume(VolumeOp.SET, 35),
    "Dejame el sonido en 80" to PhoneCommand.Volume(VolumeOp.SET, 80),
  )

  private fun ask(text: String, expected: PhoneCommand?, contextual: Boolean = false, clarify: Boolean = false) =
    Step.Ask(text, expected, contextual, clarify)

  /** Each scenario starts with an empty context. */
  private val scenarios: List<List<Step>> = listOf(
    // New values for learned structures.
    listOf(ask("Tirame una alarma para las 6:45", PhoneCommand.Alarm(6, 45))),
    listOf(ask("Tirame una alarma para las diez y cuarto de la noche", PhoneCommand.Alarm(22, 15))),
    listOf(ask("Tirame una alarma para las 8", null, clarify = true)),
    listOf(ask("Haceme sonar el celu a las 5:50", PhoneCommand.Alarm(5, 50))),
    listOf(ask("Avisame en 25 minutos", PhoneCommand.Timer(1500))),
    listOf(ask("Avisame en 90 segundos", PhoneCommand.Timer(90))),
    listOf(ask("Avisame en dos horas", PhoneCommand.Timer(7200))),
    listOf(ask("Música al 25", PhoneCommand.Volume(VolumeOp.SET, 25))),
    listOf(ask("Música al cien por ciento", PhoneCommand.Volume(VolumeOp.SET, 100))),
    listOf(ask("Dejame el sonido en 60", PhoneCommand.Volume(VolumeOp.SET, 60))),
    // Consecutive orders with a verified media app.
    listOf(opened(MediaApp.SPOTIFY), ask("Ahora poné Soda Stereo", PhoneCommand.MusicSearch("Soda Stereo", MediaApp.SPOTIFY), contextual = true),
      ask("Bajá un poco el volumen", PhoneCommand.Volume(VolumeOp.DOWN)),
      ask("Poné Charly García", PhoneCommand.MusicSearch("Charly García", MediaApp.SPOTIFY), contextual = true)),
    listOf(opened(MediaApp.YOUTUBE), ask("Reproducí Cerati", PhoneCommand.MusicSearch("Cerati", MediaApp.YOUTUBE), contextual = true)),
    listOf(opened(MediaApp.SPOTIFY), ask("Poné música de Los Redondos", PhoneCommand.MusicSearch("Los Redondos", MediaApp.SPOTIFY), contextual = true)),
    listOf(opened(MediaApp.SPOTIFY), ask("Poné Soda Stereo en YouTube", PhoneCommand.MusicSearch("Soda Stereo", MediaApp.YOUTUBE))),
    listOf(opened(MediaApp.SPOTIFY), ask("Ahora, subí un poco el volumen", PhoneCommand.Volume(VolumeOp.UP))),
    listOf(opened(MediaApp.SPOTIFY), ask("Volvé a abrirla", PhoneCommand.OpenApp("spotify", MediaApp.SPOTIFY.packageName), contextual = true)),
    // Context that must not be used: expired, unverified, replaced or turned stale.
    listOf(opened(MediaApp.SPOTIFY), Step.Wait(OperationalContext.TTL_MILLIS), ask("Ahora poné Soda Stereo", null)),
    listOf(Step.Executed(PhoneCommand.OpenApp("spotify"), PhoneActionResult("No encontré una aplicación llamada spotify.", outcome = PhoneActionOutcome.REJECTED)),
      ask("Ahora poné Soda Stereo", null)),
    listOf(Step.Executed(PhoneCommand.OpenApp("spotify"), PhoneActionResult("Abriendo.", outcome = PhoneActionOutcome.HANDOFF)),
      ask("Poné Soda Stereo", null)),
    listOf(opened(MediaApp.SPOTIFY), Step.Executed(PhoneCommand.OpenApp("whatsapp"),
      PhoneActionResult("Abriendo WhatsApp.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = "com.whatsapp")), ask("Poné Soda Stereo", null)),
    listOf(opened(MediaApp.SPOTIFY), ask("Prendé la linterna", PhoneCommand.Torch(true)), ask("Apagá la linterna", PhoneCommand.Torch(false)),
      ask("Abrí la cámara", PhoneCommand.Camera()), ask("Poné Soda Stereo", null)),
    // Negative cases with active structures and context: nothing may execute locally.
    listOf(opened(MediaApp.SPOTIFY), ask("No pongas Soda Stereo", null), ask("¿Ponés Soda Stereo?", null),
      ask("Si llego temprano, poné Soda Stereo", null), ask("Poné Soda Stereo y mandale un mensaje a Ana", null),
      ask("Poné la tele", null), ask("Poné algo", null), ask("Mandale un mensaje", null), ask("Llamalo", null), ask("Borralo", null)),
    listOf(ask("No me tires una alarma para las 6:45", null), ask("¿Tirame una alarma para las 6:45?", null),
      ask("Suponé que tirame una alarma para las 6:45", null), ask("\"Tirame una alarma para las 6:45\"", null),
      ask("Tirame una alarma para las 6:45 y prendé la linterna", null), ask("Tirame una alarma para las 6:45 llamada gimnasio", null),
      ask("Tirame una alarma para las 6:45 todos los días", null), ask("Tirame una alarma para las 6:45 mañana", null),
      ask("Avisame en 10 minutos que salga", null), ask("Música al 140", null), ask("Música al máximo", null),
      ask("Música al 25 durante diez minutos", null), ask("Cuando termine, música al 25", null)),
  )

  private class Run(val before: Boolean) {
    var requests = 0; var local = 0; var decisions = 0; var groq = 0
    var correct = 0; var wrong = 0; var falsePositives = 0
    var contextualExpected = 0; var contextualResolved = 0; var contextLeaks = 0
    val nanos = mutableListOf<Long>()
    val byText = linkedMapOf<String, MutableList<Long>>()
  }

  private suspend fun measure(before: Boolean, store: LearnedPhraseStore, repetitions: Int): Run {
    val run = Run(before)
    repeat(repetitions) { round ->
      for (scenario in scenarios) {
        var clock = 0L
        val context = if (before) null else OperationalContext(clock = { clock })
        for (step in scenario) when (step) {
          is Step.Wait -> clock += step.millis
          is Step.Executed -> context?.record(step.command, step.result)
          is Step.Ask -> {
            var decisions = 0; var groq = 0
            // Providers never receive the context: they can only propose the request without its reference.
            val proposal = listOfNotNull(when (val expected = step.expected) {
              is PhoneCommand.MusicSearch -> if (step.contextual) expected.copy(app = null) else expected
              is PhoneCommand.OpenApp -> if (step.contextual) expected.copy(packageName = null) else expected
              else -> expected
            })
            val coordinator = GeminiCoordinator(
              HybridPhoneRouter({ true }, DecisionClassifier { decisions++; SemanticDecision("unsupported", .99, .99, .99, .99) }),
              StructuredCommandExtractor { decisions++; proposal },
              fallback = StructuredCommandExtractor { groq++; proposal },
              lookupLearnedPhrase = store::lookup,
              lookupLearnedPattern = if (before) { _ -> null } else store::lookupPattern,
              operationalContext = context,
            )
            val parser = PhoneCommandParser(::now)
            val start = System.nanoTime()
            val local = parser.parse(step.text)
            context?.beginTurn(local)
            val result = coordinator.interpret(InterpretationRequest(step.text, now = now()), local)
            val elapsed = System.nanoTime() - start
            if (round > 0 || repetitions == 1) {
              val providerFree = decisions == 0 && groq == 0
              if (providerFree) { run.nanos += elapsed; run.byText.getOrPut(step.text) { mutableListOf() } += elapsed }
              if (round == repetitions - 1) {
                run.requests++
                run.decisions += decisions; run.groq += groq
                val executed = when (result) {
                  is InterpretationResult.Direct -> result.command
                  is InterpretationResult.Plan -> result.commands.singleOrNull()
                  else -> null
                }
                val resolvedLocally = providerFree && (executed != null || result is InterpretationResult.Clarification)
                if (resolvedLocally) run.local++
                when {
                  step.expected == null && step.clarify -> if (result is InterpretationResult.Clarification) run.correct++ else run.wrong++
                  step.expected == null -> if (executed != null && providerFree) run.falsePositives++ else run.correct++
                  executed == step.expected -> run.correct++
                  executed != null -> run.wrong++
                }
                if (step.contextual) {
                  run.contextualExpected++
                  if (providerFree && executed == step.expected) run.contextualResolved++
                }
                if (result is InterpretationResult.Direct && result.contextual && step.expected == null) run.contextLeaks++
                println("${if (before) "BEFORE" else "AFTER"}\t${step.text}\t${result::class.simpleName}\t$executed\tproviders=${decisions + groq}")
              }
            }
            if (!before && executed(result) != null) context?.record(executed(result)!!, success(executed(result)!!))
          }
        }
      }
    }
    return run
  }

  private fun executed(result: InterpretationResult) = when (result) {
    is InterpretationResult.Direct -> result.command
    else -> null
  }

  private fun success(command: PhoneCommand) = when (command) {
    is PhoneCommand.OpenApp -> PhoneActionResult("Abriendo.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = command.packageName)
    is PhoneCommand.Volume, is PhoneCommand.Torch -> PhoneActionResult("Listo.")
    else -> PhoneActionResult("Entregado.", outcome = PhoneActionOutcome.HANDOFF)
  }

  @Test fun compararAntesYDespues() = runBlocking {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    for ((text, command) in training) store.observePattern(InterpretationRequest(text, now = now()), command,
      if (command is PhoneCommand.Volume) PhoneActionResult("Volumen multimedia ajustado.") else PhoneActionResult("Reloj.", outcome = PhoneActionOutcome.HANDOFF))
    assertEquals(5, store.lookupPatternCountForTest())
    val before = measure(before = true, store = store, repetitions = 1)
    val after = measure(before = false, store = store, repetitions = 1)
    // Latency: warm-up round plus measured rounds, provider-free turns only.
    val beforeTiming = measure(before = true, store = store, repetitions = 31)
    val afterTiming = measure(before = false, store = store, repetitions = 31)
    for ((label, run, timing) in listOf(Triple("BEFORE", before, beforeTiming), Triple("AFTER", after, afterTiming))) {
      val sorted = timing.nanos.sorted()
      println(String.format(Locale.ROOT,
        "LEARNING_METRICS %s requests=%d local=%d (%.2f%%) decisions_per_100=%.1f groq_per_100=%.1f correct=%d wrong=%d false_positives=%d contextual=%d/%d context_leaks=%d local_p50_ns=%d local_p95_ns=%d",
        label, run.requests, run.local, 100.0 * run.local / run.requests, 100.0 * run.decisions / run.requests,
        100.0 * run.groq / run.requests, run.correct, run.wrong, run.falsePositives, run.contextualResolved, run.contextualExpected,
        run.contextLeaks, sorted.percentile(.50), sorted.percentile(.95)))
    }
    for ((label, timing) in listOf("BEFORE" to beforeTiming, "AFTER" to afterTiming)) for ((text, values) in timing.byText) {
      println("LATENCY $label\t$text\tp50_ns=${values.sorted().percentile(.5)}")
    }
    assertEquals(0, after.falsePositives)
    assertEquals(0, after.wrong)
    assertEquals(0, after.contextLeaks)
    assertEquals(after.contextualExpected, after.contextualResolved)
    assertTrue(after.local > before.local)
    assertTrue(after.decisions + after.groq < before.decisions + before.groq)
  }

  private fun List<Long>.percentile(p: Double) = if (isEmpty()) 0 else this[ceil(size * p).toInt() - 1]
}

private suspend fun LearnedPhraseStore.lookupPatternCountForTest(): Int = uiSettings.first().activePatterns.size
