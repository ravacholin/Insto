package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixed, reproducible corpus for the final semantic check. Every provider is simulated: no network, no model.
 *
 * Each request is interpreted in four ways: the local parser alone, a lossy Groq fallback proposal (Decisions
 * unavailable), a lossy Decisions + extraction proposal and a lossy learned phrase (as a fuzzy match would
 * return). A proposal "executes" when it is a [InterpretationResult.Direct] or a [InterpretationResult.Plan]
 * (a plan still needs the user's confirmation, but it is already an accepted interpretation).
 */
class SemanticGuardCorpusTest {
  private val zone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
  private fun now() = Calendar.getInstance(zone).apply {
    clear(); set(2026, Calendar.NOVEMBER, 20, 10, 0, 0)
  }
  private val tomorrow = "2026-11-21"
  private fun at(hour: Int, day: Int = 20) = Calendar.getInstance(zone).apply {
    clear(); set(2026, Calendar.NOVEMBER, day, hour, 0, 0)
  }.timeInMillis

  /** [expected]: the commands that represent the request, or null when nothing may execute. */
  internal data class Case(
    val text: String,
    val expected: List<PhoneCommand>?,
    val lossy: List<List<PhoneCommand>> = emptyList(),
    /**
     * The lossy proposals replace a whole word of the payload (a destination, an artist). A learned phrase is a
     * user alias whose payload need not appear in the text, and [LearnedPhraseIndex] only tolerates one-letter
     * transcription differences in words of five letters or more, so the store cannot return these for this text.
     * Counted apart, never hidden.
     */
    val aliasOnly: Boolean = false,
  )

  private val number = "+5491122334455"

  internal val safety: List<Case> get() = listOf(
    Case("Bajá el volumen al 30 % durante veinte minutos",
      listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 30)),
      listOf(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)),
        listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 600, 30)),
        listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 20)))),
    Case("Poné una alarma mañana a las ocho", null,
      listOf(listOf(PhoneCommand.Alarm(8, 0)), listOf(PhoneCommand.Alarm(8, 0, date = tomorrow)),
        listOf(PhoneCommand.Alarm(20, 0, date = "2026-11-20")))),
    Case("Poné una alarma mañana a las ocho de la mañana", listOf(PhoneCommand.Alarm(8, 0, date = tomorrow)),
      listOf(listOf(PhoneCommand.Alarm(8, 0)), listOf(PhoneCommand.Alarm(20, 0, date = tomorrow)))),
    Case("Prendé la linterna si empieza a llover", null, listOf(listOf(PhoneCommand.Torch(true)))),
    Case("Abrí Spotify y bajá el volumen", listOf(PhoneCommand.OpenApp("spotify"), PhoneCommand.Volume(VolumeOp.DOWN)),
      listOf(listOf(PhoneCommand.OpenApp("spotify")), listOf(PhoneCommand.Volume(VolumeOp.DOWN)),
        listOf(PhoneCommand.OpenApp("spotify"), PhoneCommand.Volume(VolumeOp.UP)))),
    Case("Mandá un mensaje a Juan que diga “no voy a ir”",
      listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "no voy a ir")),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "voy a ir")),
        listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "no voy")))),
    Case("Buscá videos sobre cómo apagar la linterna", null,
      listOf(listOf(PhoneCommand.Torch(false)))),
    Case("Poné un temporizador de diez minutos", listOf(PhoneCommand.Timer(600)),
      listOf(listOf(PhoneCommand.Timer(300)), listOf(PhoneCommand.Timer(6000)))),
    Case("En diez minutos apagá la linterna", null, listOf(listOf(PhoneCommand.Torch(false)))),
    Case("Activá No molestar durante una hora", listOf(PhoneCommand.TemporaryMode(ModeKind.DND, 3600)),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.DND_ON)), listOf(PhoneCommand.TemporaryMode(ModeKind.DND, 1800)))),
    Case("Silenciá el teléfono por dos horas", listOf(PhoneCommand.TemporaryMode(ModeKind.SILENT, 7200)),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.SILENT_ON)))),
    Case("Poné una alarma a las siete de la mañana todos los lunes", listOf(PhoneCommand.Alarm(7, 0, listOf(2))),
      listOf(listOf(PhoneCommand.Alarm(7, 0)), listOf(PhoneCommand.Alarm(19, 0, listOf(2))), listOf(PhoneCommand.Alarm(7, 0, listOf(3))))),
    Case("Llevame a Rosario a pie", listOf(PhoneCommand.Navigate("rosario", TravelMode.WALKING)),
      listOf(listOf(PhoneCommand.Navigate("rosario")), listOf(PhoneCommand.Navigate("cordoba", TravelMode.WALKING)))),
    Case("Apagá la linterna", listOf(PhoneCommand.Torch(false)), listOf(listOf(PhoneCommand.Torch(true)))),
    Case("Bajá el volumen", listOf(PhoneCommand.Volume(VolumeOp.DOWN)), listOf(listOf(PhoneCommand.Volume(VolumeOp.UP)))),
    Case("Subí el brillo al 40 %", listOf(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 40)),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 70)))),
    Case("No prendas la linterna", null, listOf(listOf(PhoneCommand.Torch(true)))),
    Case("No quiero que prendas la linterna", null, listOf(listOf(PhoneCommand.Torch(true)))),
    Case("No molestes", null, listOf(listOf(PhoneCommand.Platform(PlatformAction.DND_ON)))),
    Case("No envíes ese mensaje", null,
      listOf(listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "ese mensaje")))),
    Case("Poné un temporizador de cinco minutos y apagá la linterna",
      listOf(PhoneCommand.Timer(300), PhoneCommand.Torch(false)),
      listOf(listOf(PhoneCommand.Timer(300)), listOf(PhoneCommand.Timer(300), PhoneCommand.Torch(true)))),
    Case("Buscá en YouTube recetas de pizza", listOf(PhoneCommand.YouTubeSearch("recetas de pizza")),
      listOf(listOf(PhoneCommand.YouTubeSearch("recetas de empanadas")))),
    Case("Poné el volumen al 20 % hasta las 23", listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 46800, 20, deadlineMillis = at(23))),
      listOf(listOf(PhoneCommand.Volume(VolumeOp.SET, 20)),
        listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 39600, 20, deadlineMillis = at(21))))),
    // Paraphrases outside the local grammar: the provider's reading is the only interpretation.
    Case("Necesito el volumen en 30 durante veinte minutos", listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 30)),
      listOf(listOf(PhoneCommand.Volume(VolumeOp.SET, 30)), listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 3)))),
    Case("Mañana a las ocho despertame", null,
      listOf(listOf(PhoneCommand.Alarm(8, 0)), listOf(PhoneCommand.Alarm(8, 0, date = tomorrow)))),
    Case("Quiero una alarma para mañana a las 8 de la mañana", listOf(PhoneCommand.Alarm(8, 0, date = tomorrow)),
      listOf(listOf(PhoneCommand.Alarm(8, 0)), listOf(PhoneCommand.Alarm(8, 0, date = "2026-11-22")))),
    Case("Che, necesito diez minutos en el temporizador", listOf(PhoneCommand.Timer(600)),
      listOf(listOf(PhoneCommand.Timer(300)))),
    Case("Abrime Spotify y después bajale el volumen", listOf(PhoneCommand.OpenApp("spotify"), PhoneCommand.Volume(VolumeOp.DOWN)),
      listOf(listOf(PhoneCommand.OpenApp("spotify")))),
    Case("Escribile a Juan “no voy a ir”", listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "no voy a ir")),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "voy a ir")))),
    Case("Buscame videos de cómo prender la linterna", null, listOf(listOf(PhoneCommand.Torch(true)))),
    Case("Quiero luz dentro de diez minutos", null, listOf(listOf(PhoneCommand.Torch(true)))),
    Case("Que no me moleste nadie durante una hora", listOf(PhoneCommand.TemporaryMode(ModeKind.DND, 3600)),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.DND_ON)))),
    Case("Que el brillo quede al 40", listOf(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 40)),
      listOf(listOf(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 70)))),
    Case("Andá a Rosario caminando", listOf(PhoneCommand.Navigate("rosario", TravelMode.WALKING)),
      listOf(listOf(PhoneCommand.Navigate("cordoba", TravelMode.WALKING))), aliasOnly = true),
    Case("Necesito que suene algo de Charly García en Spotify", listOf(PhoneCommand.MusicSearch("charly garcia", MediaApp.SPOTIFY)),
      listOf(listOf(PhoneCommand.MusicSearch("luis alberto spinetta", MediaApp.SPOTIFY))), aliasOnly = true),
    Case("Necesito la linterna apagada", listOf(PhoneCommand.Torch(false)), listOf(listOf(PhoneCommand.Torch(true)))),
  )

  /** Complete, legitimate orders: they must keep executing (or asking the same question) as before. */
  internal val valid: List<Case> get() = listOf(
    Case("Bajá el volumen al 30 %", listOf(PhoneCommand.Volume(VolumeOp.SET, 30))),
    Case("Poné el volumen al 50", listOf(PhoneCommand.Volume(VolumeOp.SET, 50))),
    Case("Subí el volumen", listOf(PhoneCommand.Volume(VolumeOp.UP))),
    Case("Poné un temporizador de diez minutos", listOf(PhoneCommand.Timer(600))),
    Case("Poné un temporizador de 10 minutos para la pasta", listOf(PhoneCommand.Timer(600, "la pasta"))),
    Case("Prendé la linterna", listOf(PhoneCommand.Torch(true))),
    Case("Apagá la linterna", listOf(PhoneCommand.Torch(false))),
    Case("Poné una alarma a las 7:30", listOf(PhoneCommand.Alarm(7, 30))),
    Case("Poné una alarma a las ocho de la noche", listOf(PhoneCommand.Alarm(20, 0))),
    Case("Poné una alarma mañana a las ocho de la mañana", listOf(PhoneCommand.Alarm(8, 0, date = tomorrow))),
    Case("Despertame a las siete de la mañana todos los días", listOf(PhoneCommand.Alarm(7, 0, (1..7).toList()))),
    Case("Bajá el volumen al 30 % durante veinte minutos", listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 30))),
    Case("Activá No molestar", listOf(PhoneCommand.Platform(PlatformAction.DND_ON))),
    Case("Activá No molestar durante una hora", listOf(PhoneCommand.TemporaryMode(ModeKind.DND, 3600))),
    Case("Silenciá el teléfono por dos horas", listOf(PhoneCommand.TemporaryMode(ModeKind.SILENT, 7200))),
    Case("Abrí Spotify", listOf(PhoneCommand.OpenApp("spotify"))),
    Case("Llevame a Rosario a pie", listOf(PhoneCommand.Navigate("rosario", TravelMode.WALKING))),
    Case("Buscá en YouTube recetas de pizza", listOf(PhoneCommand.YouTubeSearch("recetas de pizza"))),
    Case("Iniciá el cronómetro", listOf(PhoneCommand.Stopwatch(StopwatchOp.START))),
    Case("Pausá la música", listOf(PhoneCommand.Media(MediaOp.PAUSE))),
    Case("Poné un temporizador de cinco minutos y apagá la linterna", listOf(PhoneCommand.Timer(300), PhoneCommand.Torch(false))),
    Case("Subí el brillo al 40 %", listOf(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 40))),
    Case("Poné el volumen al 20 % hasta las 23", listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 46800, 20, deadlineMillis = at(23)))),
    Case("Mostrá mis alarmas", listOf(PhoneCommand.ClockList(true))),
    Case("Por favor, prendé la linterna", listOf(PhoneCommand.Torch(true))),
  )

  internal enum class Path { LOCAL, GROQ_FALLBACK, DECISIONS_EXTRACTION, LEARNED_PHRASE }

  private fun coordinator(path: Path, proposal: List<PhoneCommand>?): GeminiCoordinator {
    val unavailable = DecisionClassifier { throw DecisionException("Sin acceso", GeminiFailureKind.UNAVAILABLE) }
    val workflow = DecisionClassifier { SemanticDecision("workflow", .99, .99, .99, .99) }
    val extractor = StructuredCommandExtractor { requireNotNull(proposal) }
    return when (path) {
      Path.LOCAL -> GeminiCoordinator(HybridPhoneRouter({ false }, unavailable), StructuredCommandExtractor { error("sin red") })
      Path.GROQ_FALLBACK -> GeminiCoordinator(HybridPhoneRouter({ true }, unavailable),
        StructuredCommandExtractor { error("sin Gemini") }, fallback = extractor)
      Path.DECISIONS_EXTRACTION -> GeminiCoordinator(HybridPhoneRouter({ true }, workflow), extractor)
      Path.LEARNED_PHRASE -> GeminiCoordinator(HybridPhoneRouter({ false }, unavailable),
        StructuredCommandExtractor { error("sin red") }, lookupLearnedPhrase = { proposal?.singleOrNull() })
    }
  }

  internal suspend fun interpret(text: String, path: Path, proposal: List<PhoneCommand>?): InterpretationResult {
    val request = InterpretationRequest(text, now = now())
    val local = PhoneCommandParser { now() }.parse(text)
    return coordinator(path, proposal).interpret(request, local)
  }

  internal fun executed(result: InterpretationResult): List<PhoneCommand>? = when (result) {
    is InterpretationResult.Direct -> listOf(result.command)
    is InterpretationResult.Plan -> result.commands
    else -> null
  }

  private fun same(a: List<PhoneCommand>, b: List<PhoneCommand>) = a.size == b.size && a.zip(b).all { (x, y) ->
    if (x is PhoneCommand.OpenApp && y is PhoneCommand.OpenApp) PhoneCommandParser.normalize(x.name) == PhoneCommandParser.normalize(y.name)
    else if (x is PhoneCommand.Navigate && y is PhoneCommand.Navigate) x.mode == y.mode &&
      PhoneCommandParser.normalize(x.destination) == PhoneCommandParser.normalize(y.destination)
    else x == y
  }

  internal data class Metrics(
    val proposals: Int, val incorrectAccepted: Int, val lostParameters: Int, val aliasAccepted: Int,
    val validCases: Int, val validRejected: Int, val p50Ns: Long, val p95Ns: Long,
    val details: List<String>,
  )

  internal fun measure(): Metrics = runBlocking {
    var proposals = 0; var incorrect = 0; var lost = 0; var alias = 0; val details = mutableListOf<String>()
    for (case in safety) {
      val attempts = listOf(Path.LOCAL to null) +
        case.lossy.flatMap { proposal -> listOf(Path.GROQ_FALLBACK, Path.DECISIONS_EXTRACTION, Path.LEARNED_PHRASE)
          .filter { it != Path.LEARNED_PHRASE || proposal.size == 1 }.map { it to proposal } }
      for ((path, proposal) in attempts) {
        proposals++
        val result = interpret(case.text, path, proposal)
        val commands = executed(result) ?: continue
        if (case.expected == null || !same(commands, case.expected)) {
          if (path == Path.LEARNED_PHRASE && case.aliasOnly) {
            alias++; details += "ALIAS $path «${case.text}» -> $commands"; continue
          }
          incorrect++
          // A wrong but executable reading of a request whose data was explicit: data lost or changed.
          if (case.expected != null || proposal != null) lost++
          details += "INCORRECT $path «${case.text}» -> $commands"
        }
      }
    }
    var rejected = 0
    val timings = mutableListOf<Long>()
    repeat(5) { pass ->
      for (case in valid) {
        val started = System.nanoTime()
        val result = interpret(case.text, Path.LOCAL, null)
        val elapsed = System.nanoTime() - started
        if (pass == 0) {
          val commands = executed(result)
          if (commands == null || !same(commands, case.expected!!)) {
            rejected++; details += "VALID_REJECTED «${case.text}» -> $result"
          }
        } else timings += elapsed
      }
    }
    timings.sort()
    Metrics(proposals, incorrect, lost, alias, valid.size, rejected,
      timings[timings.size / 2], timings[(timings.size * 95 + 99) / 100 - 1], details)
  }

  @Test fun semanticMetrics() {
    val m = measure()
    m.details.forEach(::println)
    println("SEMANTIC_METRICS proposals=%d incorrect_accepted=%d lost_parameters=%d learned_alias_accepted=%d valid=%d valid_rejected=%d interpret_p50_ns=%d interpret_p95_ns=%d"
      .format(m.proposals, m.incorrectAccepted, m.lostParameters, m.aliasAccepted, m.validCases, m.validRejected, m.p50Ns, m.p95Ns))
    assertEquals(m.details.joinToString("\n"), 0, m.incorrectAccepted)
    assertEquals(0, m.lostParameters)
    assertEquals(m.details.joinToString("\n"), 0, m.validRejected)
  }

  /** Cost of the final check alone over the valid corpus, without parsing. */
  @Test fun guardCost() {
    val inputs = valid.map { it.text to it.expected!! }
    val timings = mutableListOf<Long>()
    repeat(30) { pass ->
      for ((text, commands) in inputs) {
        val started = System.nanoTime()
        val verdict = SemanticCommandGuard.check(text, commands, now())
        val elapsed = System.nanoTime() - started
        assertEquals(text, SemanticCommandGuard.Verdict.Accepted, verdict)
        if (pass >= 10) timings += elapsed
      }
    }
    timings.sort()
    println("GUARD_COST checks=%d p50_ns=%d p95_ns=%d".format(timings.size, timings[timings.size / 2], timings[(timings.size * 95 + 99) / 100 - 1]))
  }
}
