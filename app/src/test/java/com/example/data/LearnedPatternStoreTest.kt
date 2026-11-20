package com.example.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.phone.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearnedPatternStoreTest {
  private val handoff = PhoneActionResult("Solicitud enviada al Reloj.", outcome = PhoneActionOutcome.HANDOFF)
  private fun request(text: String) = InterpretationRequest(text)
  private val alarms = listOf(
    "Tirame una alarma para las 8" to PhoneCommand.Alarm(8, 0),
    "Tirame una alarma para las 9 de la noche" to PhoneCommand.Alarm(21, 0),
    "Tirame una alarma para las 7:30" to PhoneCommand.Alarm(7, 30),
  )

  private suspend fun LearnedPhraseStore.train(examples: List<Pair<String, PhoneCommand>> = alarms) {
    for ((text, command) in examples) observePattern(request(text), command, handoff)
  }

  private suspend fun LearnedPhraseStore.match(text: String) = (lookupPattern(request(text)) as? PhrasePatterns.Match.Command)?.command

  @Test fun tresEjemplosDistintosActivanLaEstructuraYDosNo() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.train(alarms.take(2))
    assertNull(store.lookupPattern(request("tirame una alarma para las 6:45")))
    assertEquals(1, store.uiSettings.first().patterns.size)
    assertTrue(store.uiSettings.first().activePatterns.isEmpty())
    store.train(alarms.drop(2))
    assertEquals(PhoneCommand.Alarm(6, 45), store.match("Tirame una alarma para las 6:45"))
    val pattern = store.uiSettings.first().activePatterns.single()
    assertEquals("tirame una alarma para las {hora}", pattern.template)
    assertEquals(listOf("8", "9 de la noche", "7:30"), pattern.examples)
    // Exact phrases are not created by structure examples.
    assertTrue(store.uiSettings.first().entries.isEmpty())
  }

  @Test fun repetirElMismoValorNoEsDiversidad() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.train(listOf(alarms[0], alarms[0], "Tirame una alarma para las 8 de la mañana" to PhoneCommand.Alarm(8, 0)))
    assertTrue(store.uiSettings.first().activePatterns.isEmpty())
    assertNull(store.lookupPattern(request("tirame una alarma para las 7:30")))
  }

  @Test fun soloCuentanEjecucionesCorrectas() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    for (outcome in listOf(PhoneActionOutcome.REJECTED, PhoneActionOutcome.NEEDS_INPUT, PhoneActionOutcome.UNCERTAIN)) {
      for ((text, command) in alarms) store.observePattern(request(text), command, handoff.copy(outcome = outcome))
    }
    // Pending confirmation (an hour that already passed today) is not an execution yet.
    for ((text, command) in alarms) store.observePattern(request(text), command, handoff.copy(confirmation = command))
    // Volume is applied in the phone: a handoff is not proof.
    for ((text, percent) in listOf("música al 40" to 40, "música al 50" to 50, "música al 60" to 60))
      store.observePattern(request(text), PhoneCommand.Volume(VolumeOp.SET, percent), handoff)
    assertTrue(store.uiSettings.first().patterns.isEmpty())
    for ((text, percent) in listOf("música al 40" to 40, "música al 50" to 50, "música al 60" to 60))
      store.observePattern(request(text), PhoneCommand.Volume(VolumeOp.SET, percent), PhoneActionResult("Volumen multimedia ajustado."))
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 15), store.match("música al 15"))
  }

  @Test fun noAprendeDePedidosInsegurosNiConElAprendizajeApagado() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.train(listOf(
      "¿Tirame una alarma para las 8?" to PhoneCommand.Alarm(8, 0),
      "Si puedo, tirame una alarma para las 9" to PhoneCommand.Alarm(9, 0),
      "Tirame una alarma para las 7:30 y prendé la linterna" to PhoneCommand.Alarm(7, 30),
      "No tires una alarma para las 6" to PhoneCommand.Alarm(6, 0),
    ))
    assertTrue(store.uiSettings.first().patterns.isEmpty())
    store.setEnabled(false)
    store.train()
    assertTrue(store.uiSettings.first().patterns.isEmpty())
    store.setEnabled(true)
    store.train()
    store.setEnabled(false)
    assertNull(store.lookupPattern(request("tirame una alarma para las 6:45")))
  }

  @Test fun desactivarBorrarYBorrarTodo() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    store.train()
    val template = "tirame una alarma para las {hora}"
    store.setPatternEnabled(template, false)
    assertNull(store.lookupPattern(request("tirame una alarma para las 6:45")))
    // A disabled structure is not re-enabled by new examples.
    store.train(listOf("Tirame una alarma para las 10 de la noche" to PhoneCommand.Alarm(22, 0)))
    assertFalse(store.uiSettings.first().patterns.single().enabled)
    assertNull(store.lookupPattern(request("tirame una alarma para las 6:45")))
    store.setPatternEnabled(template, true)
    assertEquals(PhoneCommand.Alarm(6, 45), store.match("tirame una alarma para las 6:45"))
    store.deletePattern(template)
    assertTrue(store.uiSettings.first().patterns.isEmpty())
    assertNull(store.lookupPattern(request("tirame una alarma para las 6:45")))
    store.train()
    store.clear()
    assertTrue(store.uiSettings.first().patterns.isEmpty())
  }

  @Test fun persisteEntreInstanciasYCuentaUsos() = runTest {
    val data = PhraseTestDataStore()
    LearnedPhraseStore(data, { false }).train()
    val reopened = LearnedPhraseStore(data, { false }, updates = this)
    assertEquals(PhoneCommand.Alarm(6, 45), reopened.match("tirame una alarma para las 6:45"))
    reopened.savePatternUse("tirame una alarma para las {hora}")
    testScheduler.advanceUntilIdle()
    assertEquals(1L, reopened.uiSettings.first().patterns.single().uses)
    val raw = JSONObject(data.data.value[stringPreferencesKey("patterns")]!!)
    assertEquals(1, raw.getInt("version"))
  }

  @Test fun migracionNoDestructivaDesdeUnStoreSinEstructuras() = runTest {
    val legacyEntries = """[{"phrase":"ilumina aca","command":{"type":"torch","enabled":true},"date":5,"uses":2}]"""
    val data = PhraseTestDataStore().apply {
      this.data.value = mutablePreferencesOf(stringPreferencesKey("entries") to legacyEntries, booleanPreferencesKey("enabled") to true)
    }
    val store = LearnedPhraseStore(data, { false })
    assertEquals(PhoneCommand.Torch(true), store.lookup(request("Iluminá acá")))
    assertTrue(store.uiSettings.first().patterns.isEmpty())
    store.train()
    // The phrase list is left byte for byte as it was.
    assertEquals(legacyEntries, data.data.value[stringPreferencesKey("entries")])
    assertEquals(PhoneCommand.Torch(true), store.lookup(request("Iluminá acá")))
    assertNotNull(store.lookupPattern(request("tirame una alarma para las 6:45")))
  }

  @Test fun datosDaniadosODemasiadoAmpliosSeIgnoranSinAfectarLasFrases() = runTest {
    val legacyEntries = """[{"phrase":"ilumina aca","command":{"type":"torch","enabled":true},"date":5,"uses":2}]"""
    for (raw in listOf(
      "no es json",
      """{"version":99,"patterns":[]}""",
      """{"version":1,"patterns":[{"prefix":"","suffix":"","action":"alarm","values":["08:00","09:00","07:30"],"examples":["8"]}]}""",
      """{"version":1,"patterns":[{"prefix":"a las ","suffix":"","action":"alarm","values":["08:00","09:00","07:30"],"examples":["8"]}]}""",
      """{"version":1,"patterns":[{"prefix":"mandale a ana ","suffix":"","action":"message","values":["1","2","3"],"examples":["8"]}]}""",
      """{"version":1,"patterns":[{"prefix":"(.*) ","suffix":"","action":"volume","values":["1","2","3"],"examples":["8"]}]}""",
    )) {
      val data = PhraseTestDataStore().apply {
        this.data.value = mutablePreferencesOf(stringPreferencesKey("entries") to legacyEntries, stringPreferencesKey("patterns") to raw)
      }
      val store = LearnedPhraseStore(data, { false })
      assertTrue(raw, store.uiSettings.first().patterns.isEmpty())
      assertNull(raw, store.lookupPattern(request("a las 8")))
      assertEquals(PhoneCommand.Torch(true), store.lookup(request("ilumina aca")))
    }
  }

  @Test fun unaFraseManualConElMismoTextoNoSeReemplaza() = runTest {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false })
    assertTrue(store.teach("tirame una alarma para las 6", PhoneCommand.Alarm(18, 0)))
    store.train()
    assertEquals(PhoneCommand.Alarm(18, 0), store.lookup(request("tirame una alarma para las 6")))
    assertEquals(PhoneCommand.Alarm(18, 0), store.uiSettings.first().entries.single().command)
  }

  @Test fun limitaLaCantidadDeEstructurasDescartandoPrimeroLasIncompletas() = runTest {
    val active = LearnedPhraseStore.PatternEntry("a ", "", PhrasePatterns.Action.ALARM, listOf("1", "2", "3"), listOf("8"), 1)
    val pending = active.copy(values = listOf("1"))
    val trimmed = LearnedPhraseStore.trimPatterns(List(LearnedPhraseStore.PATTERN_LIMIT) { active } + pending)
    assertEquals(LearnedPhraseStore.PATTERN_LIMIT, trimmed.size)
    assertTrue(trimmed.all { it.active })
  }
}
