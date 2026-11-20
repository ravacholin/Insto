package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCommandGuardTest {
  private val zone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
  private fun now() = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, 20, 10, 0, 0) }
  private val number = "+5491122334455"
  private fun check(text: String, vararg commands: PhoneCommand) = SemanticCommandGuard.check(text, commands.toList(), now())
  private fun accepted(text: String, vararg commands: PhoneCommand) =
    assertEquals(text, SemanticCommandGuard.Verdict.Accepted, check(text, *commands))
  private fun rejected(text: String, vararg commands: PhoneCommand): String {
    val verdict = check(text, *commands)
    assertTrue("$text → $verdict", verdict is SemanticCommandGuard.Verdict.Rejected)
    return (verdict as SemanticCommandGuard.Verdict.Rejected).reason
  }

  private fun coordinator(proposal: List<PhoneCommand>? = null) = if (proposal == null)
    GeminiCoordinator(HybridPhoneRouter({ false }, DecisionClassifier { error("sin red") }), StructuredCommandExtractor { error("sin red") })
  else GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("caído", GeminiFailureKind.UNAVAILABLE) }),
    StructuredCommandExtractor { error("sin Gemini") }, fallback = StructuredCommandExtractor { proposal })

  private fun interpret(text: String, proposal: List<PhoneCommand>? = null) = runBlocking {
    coordinator(proposal).interpret(InterpretationRequest(text, now = now()), PhoneCommandParser { now() }.parse(text))
  }
  private fun executes(result: InterpretationResult) = result is InterpretationResult.Direct || result is InterpretationResult.Plan

  @Test fun volumenAlTreintaSeEjecuta() {
    assertEquals(InterpretationResult.Direct(PhoneCommand.Volume(VolumeOp.SET, 30)), interpret("Bajá el volumen al 30 %"))
    accepted("Bajá el volumen al 30 %", PhoneCommand.Volume(VolumeOp.SET, 30))
    accepted("Bajá el volumen al treinta por ciento", PhoneCommand.Volume(VolumeOp.SET, 30))
  }

  @Test fun volumenConDuracionUsaElModoTemporalYNuncaSoloElCambioPermanente() {
    val text = "Bajá el volumen al 30 % durante veinte minutos"
    assertEquals(InterpretationResult.Direct(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 30)), interpret(text))
    assertTrue(rejected(text, PhoneCommand.Volume(VolumeOp.SET, 30)).contains("veinte minutos"))
    rejected(text, PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 600, 30))
    rejected(text, PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 20))
    // A paraphrase only a provider understands: the lossy proposal is rejected, the complete one is offered.
    val paraphrase = "Necesito el volumen en 30 durante veinte minutos"
    assertFalse(executes(interpret(paraphrase, listOf(PhoneCommand.Volume(VolumeOp.SET, 30)))))
    accepted(paraphrase, PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 30))
  }

  @Test fun alarmaDeMananaConservaElDiaYAclaraLaHoraAmbigua() {
    val text = "Poné una alarma mañana a las ocho"
    val local = interpret(text) as InterpretationResult.Clarification
    assertEquals("intent:alarm", local.context)
    // A provider or phrase that chose the period, with or without the day, still has to ask.
    for (proposal in listOf(PhoneCommand.Alarm(8, 0, date = "2026-11-21"), PhoneCommand.Alarm(8, 0))) {
      val verdict = check(text, proposal)
      assertTrue("$proposal → $verdict", verdict is SemanticCommandGuard.Verdict.NeedsClarification)
      assertEquals("intent:alarm", (verdict as SemanticCommandGuard.Verdict.NeedsClarification).context)
    }
    // Without the day, an explicit period does not save the reading.
    rejected("Poné una alarma mañana a las ocho de la mañana", PhoneCommand.Alarm(8, 0))
    rejected("Poné una alarma mañana a las ocho de la mañana", PhoneCommand.Alarm(8, 0, date = "2026-11-22"))
    accepted("Poné una alarma mañana a las ocho de la mañana", PhoneCommand.Alarm(8, 0, date = "2026-11-21"))
    rejected("Poné una alarma a las siete de la mañana todos los lunes", PhoneCommand.Alarm(7, 0))
    rejected("Poné una alarma a las siete de la mañana todos los lunes", PhoneCommand.Alarm(19, 0, listOf(2)))
    accepted("Poné una alarma a las siete de la mañana todos los lunes", PhoneCommand.Alarm(7, 0, listOf(2)))
    accepted("Despertame a las siete de la mañana todos los días", PhoneCommand.Alarm(7, 0, (1..7).toList()))
  }

  @Test fun temporizadorDeDiezMinutosSeEjecuta() {
    assertEquals(InterpretationResult.Direct(PhoneCommand.Timer(600)), interpret("Poné un temporizador de diez minutos"))
    rejected("Poné un temporizador de diez minutos", PhoneCommand.Timer(300))
    accepted("Avisame en diez minutos", PhoneCommand.Timer(600))
    accepted("Poné un temporizador de una hora y diez minutos", PhoneCommand.Timer(4200))
  }

  @Test fun unaCondicionNuncaSeVuelveInmediata() {
    assertTrue(interpret("Prendé la linterna si empieza a llover") is InterpretationResult.Rejected)
    assertFalse(executes(interpret("Prendé la linterna si empieza a llover", listOf(PhoneCommand.Torch(true)))))
    // A delay is a restriction too: an immediate action does not represent it.
    rejected("En diez minutos apagá la linterna", PhoneCommand.Torch(false))
    rejected("Quiero luz dentro de diez minutos", PhoneCommand.Torch(true))
  }

  @Test fun dosPasosSeConservanAmbos() {
    val text = "Abrí Spotify y bajá el volumen"
    val both = listOf(PhoneCommand.OpenApp("Spotify"), PhoneCommand.Volume(VolumeOp.DOWN))
    accepted(text, *both.toTypedArray())
    assertFalse(executes(interpret(text, listOf(PhoneCommand.OpenApp("Spotify")))))
    rejected(text, PhoneCommand.Volume(VolumeOp.DOWN))
    rejected(text, PhoneCommand.OpenApp("Spotify"), PhoneCommand.Volume(VolumeOp.UP))
    // A clitic verb is a step of its own, never part of the app name.
    assertEquals(listOf("Abrime Spotify", "bajale el volumen"), ConversationSession.splitSteps("Abrime Spotify y después bajale el volumen"))
    assertFalse(interpret("Abrime Spotify y después bajale el volumen") is InterpretationResult.Direct)
  }

  @Test fun elMensajeCitadoSeConservaLiteralmenteYSuNegacionNoEsUnaProhibicion() {
    val text = "Mandá un mensaje a Juan que diga “no voy a ir”"
    assertNull(RequestSafetyPolicy.rejection(text))
    accepted(text, PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "no voy a ir"))
    accepted(text, PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "No voy a ir."))
    assertTrue(rejected(text, PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "voy a ir")).contains("no voy a ir"))
    // The local reading keeps the literal text, so the altered proposal is never used.
    assertEquals(InterpretationResult.Direct(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, "Juan", content = "no voy a ir")),
      interpret(text, listOf(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "voy a ir"))))
    // Unquoted dictation is grounded in the words said: the provider may not change them.
    rejected("Escribile a Juan que llego tarde", PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "llego temprano"))
    accepted("Escribile a Juan que llego tarde", PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "Llego tarde"))
  }

  @Test fun buscarVideosSobreLaLinternaNoLaApaga() {
    val text = "Buscá videos sobre cómo apagar la linterna"
    assertFalse(interpret(text) is InterpretationResult.Direct)
    for (proposal in listOf(PhoneCommand.Torch(false), PhoneCommand.Torch(true))) {
      val result = interpret(text, listOf(proposal))
      assertFalse("$result", executes(result))
    }
    rejected("Mandale a Juan que apague la linterna", PhoneCommand.Torch(false))
    accepted(text, PhoneCommand.YouTubeSearch("cómo apagar la linterna"))
  }

  @Test fun laDireccionDichaSobreviveYLosObjetosNoSeReemplazan() {
    rejected("Apagá la linterna", PhoneCommand.Torch(true))
    rejected("Necesito la linterna apagada", PhoneCommand.Torch(true))
    rejected("Bajá el volumen", PhoneCommand.Volume(VolumeOp.UP))
    rejected("Desactivá No molestar", PhoneCommand.Platform(PlatformAction.DND_ON))
    rejected("Llevame a Rosario a pie", PhoneCommand.Navigate("Córdoba", TravelMode.WALKING))
    rejected("Buscá en YouTube recetas de pizza", PhoneCommand.YouTubeSearch("recetas de empanadas"))
    rejected("Subí el brillo al 40 %", PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 70))
    accepted("Llevame a Rosario a pie", PhoneCommand.Navigate("Rosario", TravelMode.WALKING))
    accepted("Encontrá farmacias por acá", PhoneCommand.MapSearch("farmacias cerca"))
    accepted("Poné algo de los Beatles en Spotify", PhoneCommand.MusicSearch("The Beatles", MediaApp.SPOTIFY))
  }

  @Test fun losDetallesPrescindiblesNoBloquean() {
    accepted("Che, porfa, prendé la linterna", PhoneCommand.Torch(true))
    accepted("Bajá un poquito el volumen", PhoneCommand.Volume(VolumeOp.DOWN))
    accepted("Poné un temporizador de 10 minutos para la pasta", PhoneCommand.Timer(600))
    accepted("Poné el volumen al 20 % hasta las 23", PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 46800, 20,
      deadlineMillis = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, 20, 23, 0) }.timeInMillis))
    // Courtesy split off by a comma is not a step: this order used to be rejected as two steps.
    assertEquals(listOf("Por favor, prendé la linterna"), ConversationSession.splitSteps("Por favor, prendé la linterna"))
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)), interpret("Por favor, prendé la linterna"))
  }

  @Test fun lasFrasesAprendidasRespetanLosDatosExplicitos() = runBlocking {
    val learned = GeminiCoordinator(HybridPhoneRouter({ false }, DecisionClassifier { error("sin red") }),
      StructuredCommandExtractor { error("sin red") }, lookupLearnedPhrase = { PhoneCommand.Torch(true) })
    // A taught alias may say nothing about its payload…
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true), learned = true),
      learned.interpret(InterpretationRequest("luz del pasillo", now = now()), PhoneRoute.Instinct))
    // …but it may not drop a duration the user said.
    assertTrue(learned.interpret(InterpretationRequest("luz del pasillo durante diez minutos", now = now()), PhoneRoute.Instinct)
      is InterpretationResult.Rejected)
  }

  @Test fun lasContinuacionesDeUnBorradorNoSeRevalidanContraLaRespuesta() {
    val draft = DraftPlan(listOf(CommandDraft("s1", org.json.JSONObject().put("type", "timer"))))
    val reply = InterpretationResult.Plan(listOf(PhoneCommand.Timer(600)))
    assertEquals(reply, SemanticCommandGuard.review(InterpretationRequest("diez", draft, now()), reply))
  }

  @Test fun losHechosSeExtraenSinConfundirHorasDuracionesYPorcentajes() {
    val syntax = PhoneCommandParser.normalize("Poné el volumen al 20 % hasta las 23 y un temporizador de una hora y diez minutos")
    assertEquals(listOf(4200), SemanticCommandGuard.durations(syntax).map { it.seconds })
    assertEquals(listOf(20), SemanticCommandGuard.percents(syntax).map { it.value })
    assertEquals(listOf(ClockTime.Exact(23, 0)), SemanticCommandGuard.times(syntax).map { it.clock })
    assertEquals(listOf(1), SemanticCommandGuard.days(PhoneCommandParser.normalize("mañana a las ocho de la mañana")).map { it.offset })
    assertEquals(emptyList<Int>(), SemanticCommandGuard.percents(PhoneCommandParser.normalize("Llamá al 1144556677")).map { it.value })
    assertEquals(setOf(2, 4), SemanticCommandGuard.repetition("todos los lunes y miercoles"))
  }
}
