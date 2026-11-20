package com.example.data

import com.example.phone.*

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Order: safety, explicit grammar, exact phrases, learned structures, operational context, then providers. */
class LocalLearningRoutingTest {
  private fun now() = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, 20, 6, 0) }
  private val parser = PhoneCommandParser(::now)
  private var decisions = 0
  private var extractions = 0
  private var patternLookups = 0
  private val seenByProviders = mutableListOf<String>()
  private val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, clock = { 1L })
  private val handoff = PhoneActionResult("Solicitud enviada al Reloj.", outcome = PhoneActionOutcome.HANDOFF)
  private val adjusted = PhoneActionResult("Volumen multimedia ajustado.")

  init {
    // Three executed model interpretations per structure, each with a different value.
    runBlocking {
      for ((text, command) in listOf(
        "Tirame una alarma para las 8" to PhoneCommand.Alarm(8, 0),
        "Tirame una alarma para las 9 de la noche" to PhoneCommand.Alarm(21, 0),
        "Tirame una alarma para las 7:30" to PhoneCommand.Alarm(7, 30),
      )) store.observePattern(InterpretationRequest(text, now = now()), command, handoff)
      for ((text, percent) in listOf("Música al 40" to 40, "Música al 55 por ciento" to 55, "Música al 70%" to 70))
        store.observePattern(InterpretationRequest(text, now = now()), PhoneCommand.Volume(VolumeOp.SET, percent), adjusted)
    }
  }
  private var context = OperationalContext(clock = { 0L })

  private fun coordinator(
    enabled: Boolean = true,
    phrase: (InterpretationRequest) -> PhoneCommand? = { null },
    proposal: List<PhoneCommand> = listOf(PhoneCommand.Torch(true)),
  ) = GeminiCoordinator(
    HybridPhoneRouter({ enabled }, DecisionClassifier { input ->
      decisions++; seenByProviders += input
      SemanticDecision("unsupported", .99, .99, .99, .99)
    }),
    StructuredCommandExtractor { input -> extractions++; seenByProviders += input; proposal },
    lookupLearnedPhrase = { phrase(it) },
    lookupLearnedPattern = { request -> patternLookups++; store.lookupPattern(request) },
    operationalContext = context,
  )

  private fun interpret(text: String, coordinator: GeminiCoordinator = coordinator()) = runBlocking {
    coordinator.interpret(InterpretationRequest(text, now = now()), parser.parse(text))
  }

  @Test fun unaCuartaFraseNuevaSeResuelveSinGeminiNiGroq() {
    assertEquals(listOf("musica al {porcentaje}", "tirame una alarma para las {hora}"),
      runBlocking { store.uiSettings.first().activePatterns.map { it.template } })
    val result = interpret("Tirame una alarma para las 6:45") as InterpretationResult.Direct
    assertEquals(PhoneCommand.Alarm(6, 45), result.command)
    assertTrue(result.learned)
    assertEquals("tirame una alarma para las {hora}", result.pattern)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 35), (interpret("Música al 35") as InterpretationResult.Direct).command)
    // Gemini apagado: la estructura sigue resolviendo en el teléfono.
    assertEquals(PhoneCommand.Alarm(7, 30), (interpret("tirame una alarma para las 7:30", coordinator(enabled = false)) as InterpretationResult.Direct).command)
    assertEquals(0, decisions + extractions)
  }

  @Test fun unaHoraAmbiguaPideAclaracionYNoEjecuta() {
    val result = interpret("tirame una alarma para las 8") as InterpretationResult.Clarification
    assertEquals("alarm", result.context)
    assertEquals("poner una alarma a las 8", result.source)
    assertEquals(0, decisions + extractions)
  }

  @Test fun lasOrdenesExplicitasYLasFrasesManualesTienenPrioridad() {
    val explicit = interpret("Poné una alarma a las 9 de la noche") as InterpretationResult.Direct
    assertEquals(PhoneCommand.Alarm(21, 0), explicit.command)
    assertFalse(explicit.learned)
    // The taught action must represent the phrase's explicit time (SemanticCommandGuard). Its label tells it
    // apart from the structure's reading, so the phrase's priority stays observable.
    val manual = PhoneCommand.Alarm(6, 45, label = "gimnasio")
    val taught = interpret("tirame una alarma para las 6:45", coordinator(phrase = { manual })) as InterpretationResult.Direct
    assertEquals(manual, taught.command)
    assertEquals(null, taught.pattern)
    assertEquals(0, patternLookups)
  }

  @Test fun negacionesPreguntasHipotesisCitasYOrdenesMultiplesNuncaUsanLaEstructura() {
    for (text in listOf(
      "No me tires una alarma para las 6:45",
      "¿Tirame una alarma para las 6:45?",
      "Suponé que tirame una alarma para las 6:45",
      "\"Tirame una alarma para las 6:45\"",
      "Si llueve, tirame una alarma para las 6:45",
      "Tirame una alarma para las 6:45 y bajá el volumen",
      "Tirame una alarma para las 6:45 cuando llegue",
      "Música al 35 durante diez minutos",
    )) {
      val result = interpret(text)
      assertFalse(text, result is InterpretationResult.Direct && result.learned)
    }
  }

  @Test fun elContextoMultimediaResuelveLocalmenteYNoSeEnviaAProveedores() {
    context.record(PhoneCommand.OpenApp("spotify"), PhoneActionResult("Abriendo Spotify.", outcome = PhoneActionOutcome.HANDOFF,
      launchedAppPackage = MediaApp.SPOTIFY.packageName))
    val played = interpret("Ahora poné Soda Stereo") as InterpretationResult.Direct
    assertEquals(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.SPOTIFY), played.command)
    assertTrue(played.contextual)
    assertFalse(played.learned)
    // Una orden independiente no cambia: el volumen se ejecuta tal cual.
    assertEquals(InterpretationResult.Direct(PhoneCommand.Volume(VolumeOp.DOWN)), interpret("Bajá un poco el volumen"))
    assertEquals(PhoneCommand.Volume(VolumeOp.DOWN), (interpret("Ahora, bajá el volumen") as InterpretationResult.Direct).command)
    assertEquals(PhoneCommand.MusicSearch("Cerati", MediaApp.SPOTIFY), (interpret("Dale, poné Cerati") as InterpretationResult.Direct).command)
    // La orden explícita de música recibe la app solo porque no nombró otra.
    assertEquals(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.SPOTIFY),
      (interpret("Poné música de Soda Stereo") as InterpretationResult.Direct).command)
    assertEquals(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.YOUTUBE),
      (interpret("Poné Soda Stereo en YouTube") as InterpretationResult.Direct).command)
    assertEquals(0, decisions + extractions)
    // Sin un referente comprobado, el mismo pedido sigue el circuito normal y el proveedor no ve contexto.
    context.clear()
    interpret("Ahora poné Soda Stereo")
    assertTrue(decisions + extractions > 0)
    assertTrue(seenByProviders.none { "spotify" in it.lowercase() })
  }

  @Test fun sinContextoNiEstructurasElCircuitoDeGeminiNoCambia() {
    val plain = GeminiCoordinator(
      HybridPhoneRouter({ true }, DecisionClassifier { decisions++; SemanticDecision("torch_on", .99, .99, .99, .99) }),
      StructuredCommandExtractor { extractions++; emptyList() },
    )
    assertEquals(InterpretationResult.Direct(PhoneCommand.Torch(true)), interpret("Dame un poco de luz", plain))
    assertEquals(1, decisions)
  }
}
