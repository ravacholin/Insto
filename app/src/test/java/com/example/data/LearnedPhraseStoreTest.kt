package com.example.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.phone.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

internal class PhraseTestDataStore : DataStore<Preferences> {
  override val data = MutableStateFlow<Preferences>(emptyPreferences())

  override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
    return transform(data.value).also { data.value = it }
  }
}

internal fun emptyLearnedPhrasesForTest() = LearnedPhraseStore(PhraseTestDataStore(), { false })

@OptIn(ExperimentalCoroutinesApi::class)
class LearnedPhraseStoreTest {
  private val torch = PhoneCommand.Torch(true)
  private val success = PhoneActionResult("Linterna encendida.")
  private fun request(text: String) = InterpretationRequest(text)

  @Test fun coincideSoloConLaFraseNormalizadaYNoConPreguntasOCondiciones() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.learn(request("  Iluminá acá  "), torch, true, success)
    assertEquals(torch, store.lookup(request("ilumina aca")))
    for (text in listOf("iluminá acá mismo", "¿iluminá acá?", "no iluminá acá", "iluminá acá si llego")) {
      assertNull(text, store.lookup(request(text)))
    }
    store.setEnabled(false)
    assertNull(store.lookup(request("iluminá acá")))
    assertEquals(1, store.uiSettings.first().entries.size)
  }

  @Test fun guardaSoloComandosAdmitidosConfirmadosYCompletados() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    val turn = request("Iluminá acá")
    store.learn(turn, torch, false, success)
    for (outcome in listOf(PhoneActionOutcome.NEEDS_INPUT, PhoneActionOutcome.REJECTED,
      PhoneActionOutcome.UNCERTAIN, PhoneActionOutcome.HANDOFF)) {
      store.learn(turn, torch, true, success.copy(outcome = outcome))
    }
    for (command in listOf(PhoneCommand.Timer(60), PhoneCommand.Alarm(7, 0), PhoneCommand.Dial("123"),
      PhoneCommand.Navigate("Córdoba"), PhoneCommand.YouTubeSearch("jazz"),
      PhoneCommand.Platform(PlatformAction.MESSAGE_SEND), PhoneCommand.Platform(PlatformAction.CALENDAR_QUERY))) {
      store.learn(turn, command, true, success)
      assertFalse(LearnedPhraseStore.allows(command))
    }
    assertTrue(store.uiSettings.first().entries.isEmpty())
    assertTrue(LearnedPhraseStore.allows(PhoneCommand.Stopwatch(StopwatchOp.RESET)))
    assertTrue(LearnedPhraseStore.allows(PhoneCommand.Media(MediaOp.PAUSE)))
    assertTrue(LearnedPhraseStore.allows(PhoneCommand.Platform(PlatformAction.VIBRATE_ON)))
    store.learn(turn, torch, true, success)
    assertEquals(torch, store.lookup(turn))
  }

  @Test fun conservaLaRecienteYOlvidaLaUltimaQueSeUso() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, clock = { 1L })
    for (i in 0 until 200) store.learn(request("Iluminá sector $i"), torch, true, success)
    store.recordUse(request("Iluminá sector 0"), torch)
    store.learn(request("Iluminá sector 200"), torch, true, success)
    assertEquals(200, store.uiSettings.first().entries.size)
    assertEquals(torch, store.lookup(request("iluminá sector 0")))
    assertNull(store.lookup(request("iluminá sector 1")))
    assertEquals(2L, store.uiSettings.first().entries.first { it.phrase == "ilumina sector 0" }.uses)
    // Lo último aprendido cuenta como lo último usado.
    assertTrue(store.forgetLast())
    assertNull(store.lookup(request("iluminá sector 200")))
    assertEquals(torch, store.lookup(request("iluminá sector 0")))
    assertFalse(store.forgetLast())
  }

  @Test fun unaAppDesinstaladaInvalidaElPaqueteGuardado() = runTest {
    var installed = true
    val store = LearnedPhraseStore(PhraseTestDataStore(), { installed && it == "com.ejemplo.app" })
    val turn = request("Quiero entrar al reproductor")
    store.learn(turn, PhoneCommand.OpenApp("Reproductor"), true,
      PhoneActionResult("Abriendo Reproductor.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = "com.ejemplo.app"))
    assertEquals("com.ejemplo.app", (store.lookup(turn) as PhoneCommand.OpenApp).packageName)
    installed = false
    assertNull(store.lookup(turn))
    assertTrue(store.uiSettings.first().entries.isEmpty())
  }

  @Test fun usaLaFraseSinRedPeroPriorizaElParserLocal() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    val turn = request("Iluminá acá")
    store.learn(turn, torch, true, success)
    val coordinator = GeminiCoordinator(
      HybridPhoneRouter({ true }, DecisionClassifier { fail("Se llamó a Decisions"); error("Decisions") }),
      StructuredCommandExtractor { fail("Se llamó a extracción"); emptyList() },
      fallback = StructuredCommandExtractor { fail("Se llamó a Groq"); emptyList() }, lookupLearnedPhrase = store::lookup,
    )
    assertEquals(InterpretationResult.Direct(torch, learned = true), coordinator.interpret(turn, PhoneRoute.Instinct))
    val local = PhoneCommand.Volume(VolumeOp.UP)
    assertEquals(InterpretationResult.Direct(local), coordinator.interpret(turn, PhoneRoute.Local(local)))
    assertTrue(coordinator.interpret(turn, PhoneRoute.Clarify("¿Qué app?", "app")) is InterpretationResult.Clarification)
  }

  @Test fun unaCoincidenciaTardiaNoSeEntregaTrasLaCancelacion() = runTest {
    val prefs = PhraseTestDataStore()
    val store = LearnedPhraseStore(prefs, { true })
    val turn = request("Quiero entrar al reproductor")
    store.learn(turn, PhoneCommand.OpenApp("Reproductor"), true,
      PhoneActionResult("Abriendo Reproductor.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = "com.ejemplo.app"))
    var started = false
    val delayedStore = object : DataStore<Preferences> by prefs {
      override val data = kotlinx.coroutines.flow.flow {
        started = true
        withContext(NonCancellable) { delay(1_000) }
        emit(prefs.data.value)
      }
    }
    val coordinator = GeminiCoordinator(
      HybridPhoneRouter({ true }, DecisionClassifier { fail("Se llamó a la red"); error("Decisions") }),
      StructuredCommandExtractor { error("Extracción inesperada") },
      lookupLearnedPhrase = LearnedPhraseStore(delayedStore, { true })::lookup,
    )
    val turnJob = async { coordinator.interpret(turn, PhoneRoute.Instinct) }
    runCurrent()
    assertTrue(started)
    turnJob.cancel()
    advanceUntilIdle()
    turnJob.cancelAndJoin()
    assertTrue(turnJob.isCancelled)
  }

  @Test fun unaFraseEnsenadaSeUsaYNoLaPisaElAprendizajeAutomatico() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    assertTrue(store.teach("luz", torch, target = "prendé la linterna"))
    assertEquals(torch, store.lookup(request("Luz")))
    val entry = store.uiSettings.first().entries.single()
    assertEquals(LearnedPhraseStore.Source.MANUAL, entry.source)
    assertEquals("prendé la linterna", entry.target)
    store.learn(request("luz"), PhoneCommand.Volume(VolumeOp.UP), true, PhoneActionResult("Listo."))
    assertEquals(torch, store.lookup(request("luz")))
    assertFalse(store.teach("no luz", torch))
    assertFalse(store.teach("llamá a mamá", PhoneCommand.Dial("123")))
  }

  @Test fun unaAppEnsenadaSinPaqueteSeResuelveAlEjecutar() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.teach("musica", PhoneCommand.OpenApp("spotify"))
    assertEquals(PhoneCommand.OpenApp("spotify"), store.lookup(request("música")))
  }

  @Test fun editarCambiaLaFraseYConservaLosUsos() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.teach("luz", torch)
    store.recordUse(request("luz"), torch)
    assertTrue(store.teach("claridad", torch, replacing = "luz"))
    assertNull(store.lookup(request("luz")))
    assertEquals(1L, store.uiSettings.first().entries.single().uses)
  }

  @Test fun corregirCambiaLaAccionDeLaUltimaFrase() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    val volume = PhoneCommand.Volume(VolumeOp.UP)
    assertNull(store.correctLast(volume))
    store.learn(request("iluminá acá"), torch, true, success)
    assertEquals("ilumina aca", store.correctLast(volume, "subí el volumen"))
    assertEquals(volume, store.lookup(request("iluminá acá")))
    assertEquals(LearnedPhraseStore.Source.MANUAL, store.uiSettings.first().entries.single().source)
    assertNull(store.correctLast(PhoneCommand.Dial("123")))
  }

  @Test fun olvidarBorraTambienLoQueSeAcabaDeAprender() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.learn(request("iluminá acá"), torch, true, success)
    assertTrue(store.forgetLast())
    assertTrue(store.uiSettings.first().entries.isEmpty())
  }

  @Test fun alLlegarAlLimiteSeDescartanPrimeroLasAutomaticas() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.teach("luz vieja", torch)
    for (i in 0 until LearnedPhraseStore.LIMIT) store.learn(request("iluminá sector $i"), torch, true, success)
    val entries = store.uiSettings.first().entries
    assertEquals(LearnedPhraseStore.LIMIT, entries.size)
    assertTrue(entries.any { it.phrase == "luz vieja" })
    assertTrue(entries.none { it.phrase == "ilumina sector 0" })
  }

  @Test fun exportarEImportarConservaElOrigenYRechazaArchivosInvalidos() = runTest {
    val source = LearnedPhraseStore(PhraseTestDataStore(), { true })
    source.teach("luz", torch, target = "prendé la linterna")
    source.learn(request("quiero entrar al reproductor"), PhoneCommand.OpenApp("Reproductor"), true,
      PhoneActionResult("Abriendo.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = "com.ejemplo.app"))
    val json = source.export()
    val target = LearnedPhraseStore(PhraseTestDataStore(), { true })
    target.teach("luz", PhoneCommand.Volume(VolumeOp.UP))
    assertEquals(2, target.import(json))
    assertEquals(torch, target.lookup(request("luz")))
    assertEquals("com.ejemplo.app", (target.lookup(request("quiero entrar al reproductor")) as PhoneCommand.OpenApp).packageName)
    assertEquals(LearnedPhraseStore.Source.MANUAL, target.uiSettings.first().entries.first { it.phrase == "luz" }.source)
    val dangerous = json.replace("\"luz\"", "\"no luz\"")
    for (bad in listOf("[]", "{}", dangerous)) {
      try { target.import(bad); fail(bad) } catch (_: IllegalArgumentException) { }
    }
    assertEquals(2, target.uiSettings.first().entries.size)
  }
}
