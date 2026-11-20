package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTestDataStore
import com.example.phone.*
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingLocalLearningTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private var decisions = 0
  private var groq = 0
  private var now = 0L
  private val context = OperationalContext(clock = { now })

  private fun viewModel(store: LearnedPhraseStore, proposal: (String) -> List<PhoneCommand> = { emptyList() }) = MainViewModel(app,
    hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { decisions++; throw DecisionException("Sin conexión") }),
    groqFallback = StructuredCommandExtractor { input -> groq++; proposal(input) },
    learnedPhrases = store, operationalContext = context,
  )

  private fun TestScope.send(vm: MainViewModel, text: String) {
    vm.updateFloatingText(text)
    vm.sendFloatingText { fail("Se envió a Instinct: $text") }
    runCurrent()
  }

  private fun installSpotify() {
    val info = ActivityInfo().apply {
      packageName = MediaApp.SPOTIFY.packageName; name = "com.spotify.music.Main"; exported = true; enabled = true
      nonLocalizedLabel = "Spotify"
      applicationInfo = ApplicationInfo().apply { packageName = MediaApp.SPOTIFY.packageName; enabled = true; nonLocalizedLabel = "Spotify" }
    }
    val pm = shadowOf(app.packageManager)
    pm.installPackage(PackageInfo().apply { packageName = info.packageName; applicationInfo = info.applicationInfo })
    pm.addOrUpdateActivity(info)
    val component = ComponentName(info.packageName, info.name)
    pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    pm.addIntentFilterForActivity(component, IntentFilter("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
      addCategory(Intent.CATEGORY_DEFAULT)
    })
  }

  @Test fun tresConfirmacionesEnsenanLaEstructuraYLaCuartaNoUsaProveedores() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    val vm = viewModel(store) { text ->
      listOf(PhoneCommand.Volume(VolumeOp.SET, Regex("[0-9]+").find(text)!!.value.toInt()))
    }
    try {
      for (text in listOf("Música al 40", "Música al 55 por ciento", "Música al 70")) {
        send(vm, text)
        assertTrue(text, vm.floatingAssistant.value.needsConfirmation)
        vm.confirmFloatingAction(true)
        runCurrent()
        assertNotNull(vm.consumePhoneActionCompletion())
        advanceUntilIdle()
        // Since 1.12 a confirmed phrase also works with other values; switched off here to exercise structures.
        store.setGeneralize(requireNotNull(LearnedPhraseStore.key(InterpretationRequest(text))), false)
      }
      assertEquals(3, groq)
      assertEquals("musica al {porcentaje}", store.uiSettings.first().activePatterns.single().template)
      val decisionsBefore = decisions

      send(vm, "Música al 25")
      assertEquals("Volumen multimedia ajustado.", vm.consumePhoneActionCompletion())
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertEquals(3, groq)
      assertEquals(decisionsBefore, decisions)
      advanceUntilIdle()
      assertEquals(1L, store.uiSettings.first().activePatterns.single().uses)

      // A negated or compound request with the same shape is not resolved by the structure.
      send(vm, "Música al 25 y prendé la linterna")
      assertNull(vm.consumePhoneActionCompletion())
    } finally {
      vm.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
  }

  @Test fun unaConfirmacionAlcanzaParaUsarLaFraseConOtroValorSinProveedores() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    val vm = viewModel(store) { text ->
      listOf(PhoneCommand.Volume(VolumeOp.SET, Regex("[0-9]+").find(text)!!.value.toInt()))
    }
    try {
      send(vm, "Música al 40")
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      vm.confirmFloatingAction(true)
      runCurrent()
      assertNotNull(vm.consumePhoneActionCompletion())
      advanceUntilIdle()
      val providerCalls = groq to decisions
      send(vm, "Che, música al 55 por ciento")
      assertEquals("Volumen multimedia ajustado.", vm.consumePhoneActionCompletion())
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertEquals(providerCalls, groq to decisions)
      advanceUntilIdle()
      // The use is recorded on the phrase it came from; no new phrase is learned from a local match.
      assertEquals(listOf("musica al 40"), store.uiSettings.first().entries.map { it.phrase })
      assertEquals(2L, store.uiSettings.first().entries.single().uses)
      // A negation with the same shape still never executes.
      send(vm, "No, música al 25 no")
      assertNull(vm.consumePhoneActionCompletion())
    } finally {
      vm.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
  }

  @Test fun abrirSpotifyYLuegoPonerUnArtistaEnOtroPanelSinGemini() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    installSpotify()
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    val first = viewModel(store)
    try {
      send(first, "Abrí Spotify")
      assertNotNull(first.consumePhoneActionCompletion())
      assertEquals(MediaApp.SPOTIFY.packageName, shadowOf(app).nextStartedActivity.component!!.packageName)
    } finally {
      // The panel closes itself after the handoff; it is not a user cancellation.
      first.dismissFloatingAssistant(endOperationalContext = false)
    }
    val second = viewModel(store)
    try {
      now += 30_000
      send(second, "Ahora poné Soda Stereo")
      val started = shadowOf(app).nextStartedActivity
      assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", started.action)
      assertEquals(MediaApp.SPOTIFY.packageName, started.`package`)
      assertEquals(0, decisions + groq)
      assertNotNull(second.consumePhoneActionCompletion())

      send(second, "Bajá un poco el volumen")
      assertEquals("Volumen multimedia ajustado.", second.consumePhoneActionCompletion())
      assertEquals(MediaApp.SPOTIFY.packageName, context.preferredMediaPackage())
      assertEquals(0, decisions + groq)
    } finally {
      second.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
    // The user closed the assistant: nothing remains for the next session.
    assertNull(context.snapshot())
  }

  @Test fun cancelarCaducarOFallarLaAperturaNoDejanReferente() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    val vm = viewModel(store)
    try {
      // Spotify is not installed: the failed opening establishes nothing.
      send(vm, "Abrí Spotify")
      assertNotNull(vm.floatingAssistant.value.error)
      assertNull(context.snapshot())

      installSpotify()
      send(vm, "Abrí Spotify")
      assertNotNull(context.snapshot())
      send(vm, "cancelar")
      assertNull(context.snapshot())

      send(vm, "Abrí Spotify")
      now += OperationalContext.TTL_MILLIS
      send(vm, "Ahora poné Soda Stereo")
      assertTrue(decisions + groq > 0)
    } finally {
      vm.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
  }
}
