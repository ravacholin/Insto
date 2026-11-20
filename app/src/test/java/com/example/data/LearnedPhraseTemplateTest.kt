package com.example.data

import com.example.phone.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Learned phrases with a value generalize; canonical forms, collisions and manual phrases stay protected. */
class LearnedPhraseTemplateTest {
  private val ok = PhoneActionResult("Listo.")
  private fun request(text: String) = InterpretationRequest(text)
  private val apps = mapOf("spotify" to PhoneCommand.OpenApp("spotify", "com.spotify.music"))
  private fun store(clock: () -> Long = { 1L }) =
    LearnedPhraseStore(PhraseTestDataStore(), { true }, clock = clock, resolveApp = { apps[it] })

  @Test fun anAutomaticVolumePhraseWorksWithOtherValues() = runTest {
    val store = store()
    store.learn(request("Bajá la música al 30"), PhoneCommand.Volume(VolumeOp.SET, 30), true, ok)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 50), store.lookup(request("bajá la música al 50")))
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 45), store.lookup(request("Che, bajame la música al cuarenta y cinco porfa")))
    for (text in listOf("bajá la música al 150", "no bajes la música al 50", "bajá la música al 50 si llego",
      "¿bajás la música al 50?", "bajá la música al 50 y prendé la luz")) {
      assertNull(text, store.lookup(request(text)))
    }
    val entry = store.uiSettings.first().entries.single()
    assertEquals("Bajá la música al 30", entry.display)
    assertEquals("Bajá la música al {número}", store.template(entry))
    // A use of the template counts for the phrase it came from.
    store.recordUse(request("bajá la música al 50"), PhoneCommand.Volume(VolumeOp.SET, 50))
    assertEquals(2L, store.uiSettings.first().entries.single().uses)
  }

  @Test fun generalizationCanBeTurnedOffPerPhrase() = runTest {
    val store = store()
    store.teach("modo pelicula al 20", PhoneCommand.Volume(VolumeOp.SET, 20), display = "Modo película al 20")
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 40), store.lookup(request("modo película al 40")))
    store.setGeneralize("modo pelicula al 20", false)
    assertNull(store.lookup(request("modo película al 40")))
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 20), store.lookup(request("modo película al 20")))
    assertNull(store.template(store.uiSettings.first().entries.single()))
  }

  @Test fun automaticLearningNeverShadowsAManualPhraseInAnotherSpelling() = runTest {
    val store = store()
    store.teach("prende la luz del patio", PhoneCommand.Torch(true))
    store.learn(request("Prendeme la luz del patio"), PhoneCommand.Torch(false), true, ok)
    assertEquals(1, store.uiSettings.first().entries.size)
    assertEquals(PhoneCommand.Torch(true), store.lookup(request("Prendeme la luz del patio porfa")))
  }

  @Test fun collisionsAndUnknownAppsDoNotExecute() = runTest {
    val store = store()
    store.teach("prende modo fiesta", PhoneCommand.Torch(true))
    store.teach("prender modo fiesta", PhoneCommand.Media(MediaOp.PLAY))
    assertEquals(PhoneCommand.Torch(true), store.lookup(request("prendé modo fiesta")))
    assertNull(store.lookup(request("prendeme modo fiesta")))
    store.learn(request("Poneme la app de musica"), PhoneCommand.OpenApp("musica"), true,
      ok.copy(outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = "com.example.music"))
    assertEquals(apps["spotify"], store.lookup(request("poneme la app de spotify")))
    assertNull(store.lookup(request("poneme la app de netflix")))
  }

  @Test fun oldEntriesWithoutNewFieldsStillReadAndExportKeepsThem() = runTest {
    val store = store()
    store.teach("modo cine", PhoneCommand.Volume(VolumeOp.SET, 20), display = "Modo cine")
    store.teach("bajo la musica al 10", PhoneCommand.Volume(VolumeOp.SET, 10), generalize = false)
    val exported = store.export()
    val phrases = org.json.JSONObject(exported).getJSONArray("phrases")
    assertEquals("Modo cine", (0 until phrases.length()).map { phrases.getJSONObject(it) }.first { it.getString("phrase") == "modo cine" }.getString("display"))
    val restored = store()
    restored.import(exported)
    fun view(s: LearnedPhraseStore.Settings) = s.entries.map { Triple(it.phrase, it.display, it.generalize) }.toSet()
    assertEquals(view(store.uiSettings.first()), view(restored.uiSettings.first()))
    assertFalse(restored.uiSettings.first().entries.first { it.phrase == "bajo la musica al 10" }.generalize)
    // An entry written by 1.11 has neither field.
    val legacy = JSONArray().put(org.json.JSONObject().put("phrase", "luz").put("command", PhoneCommandCodec.encode(PhoneCommand.Torch(true)))
      .put("date", 1L).put("uses", 0L).put("source", "manual"))
    val decoded = LearnedPhraseStore.decodeExport(org.json.JSONObject().put("format", "insto-learned-phrases").put("version", 1)
      .put("phrases", legacy).toString())
    assertNull(decoded.single().display)
    assertTrue(decoded.single().generalize)
  }
}
