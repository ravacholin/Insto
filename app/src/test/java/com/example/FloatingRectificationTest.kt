package com.example

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.emptyLearnedPhrasesForTest
import com.example.phone.*
import com.example.testsupport.ConnectedAccessibility
import com.example.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rectifications reach the phone through the normal flow; prohibitions and cancellations never run anything. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingRectificationTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val audio get() = app.getSystemService(AudioManager::class.java)
  private val volume get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

  private fun TestScope.viewModel(proposal: List<PhoneCommand> = listOf(PhoneCommand.Torch(true))): MainViewModel {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    return MainViewModel(app, learnedPhrases = emptyLearnedPhrasesForTest(),
      hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { error("Recognized locally or by extraction") }),
      structuredExtractor = StructuredCommandExtractor { proposal })
  }

  private fun TestScope.say(vm: MainViewModel, text: String) {
    vm.updateFloatingText(text)
    vm.sendFloatingText { fail("«$text» must not reach Instinct") }
    runCurrent()
  }

  @Test fun unaRectificacionEjecutaLaOrdenAfirmativaConSusDatos() = runTest {
    val disconnect = ConnectedAccessibility.start(app)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
    val vm = viewModel()
    try {
      say(vm, "No, bajá el volumen al 20")
      val rectified = volume
      assertNull(vm.floatingAssistant.value.error)
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
      say(vm, "Bajá el volumen al 20")
      assertEquals(volume, rectified)
      assertTrue(rectified < audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    } finally { vm.dismissFloatingAssistant(); disconnect(); Dispatchers.resetMain() }
  }

  @Test fun unaProhibicionNoEjecutaNadaAunqueSeRepitaElNo() = runTest {
    val disconnect = ConnectedAccessibility.start(app)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
    val vm = viewModel()
    try {
      for (text in listOf("No bajes el volumen al 20", "No quiero que bajes el volumen", "No, no bajes el volumen al 20", "No molestes")) {
        say(vm, text)
        assertEquals(text, 5, volume)
        assertNotNull(text, vm.floatingAssistant.value.error)
        assertFalse(text, vm.floatingAssistant.value.needsConfirmation)
      }
    } finally { vm.dismissFloatingAssistant(); disconnect(); Dispatchers.resetMain() }
  }

  @Test fun noLoHagasCancelaLaConfirmacionPendiente() = runTest {
    val vm = viewModel()
    try {
      say(vm, "necesito la linterna ahora mismo")
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      say(vm, "No lo hagas")
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertEquals("Pedido cancelado.", vm.floatingAssistant.value.status)
      assertNull(vm.consumePhoneActionCompletion())
      // Nothing pending: the same words run nothing and say so.
      say(vm, "No envíes ese mensaje")
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertNotNull(vm.floatingAssistant.value.error)
      assertNull(vm.consumePhoneActionCompletion())
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun noEnviesEseMensajeCancelaLoPendienteSinEnviar() = runTest {
    val vm = viewModel()
    try {
      say(vm, "necesito la linterna ahora mismo")
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      say(vm, "No envíes ese mensaje")
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.isEmpty() || !vm.floatingAssistant.value.workflowActive)
      assertNull(vm.consumePhoneActionCompletion())
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun noMejorPoneloAlTreintaUsaSoloElPedidoPendienteComoReferencia() = runTest {
    val disconnect = ConnectedAccessibility.start(app)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
    val vm = viewModel(proposal = listOf(PhoneCommand.Volume(VolumeOp.SET, 50)))
    try {
      // Without a pending reference, «ponelo» names nothing: ask, never guess.
      say(vm, "No, mejor ponelo al 30")
      assertEquals(2, volume)
      assertTrue(vm.floatingAssistant.value.error!!.contains("orden completa"))
      // A paraphrase only the extractor understands leaves a volume plan pending confirmation.
      say(vm, "necesito el volumen a la mitad")
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      say(vm, "No, mejor ponelo al 30")
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      val rectified = volume
      // Exactly what the explicit order does.
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
      say(vm, "Poné el volumen al 30 %")
      assertNotEquals(2, rectified)
      assertEquals(volume, rectified)
    } finally { vm.dismissFloatingAssistant(); disconnect(); Dispatchers.resetMain() }
  }
}
