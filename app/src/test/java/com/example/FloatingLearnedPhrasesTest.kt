package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTestDataStore
import com.example.phone.*
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingLearnedPhrasesTest {
  private val phrase = "Un poco menos de sonido"
  private val command = PhoneCommand.Volume(VolumeOp.DOWN)

  @Test fun aprendeDespuesDeConfirmarYRepiteSinModelo() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    var classifications = 0
    val vm = MainViewModel(app,
      hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
        classifications++
        throw DecisionException("Sin conexión")
      }),
      groqFallback = StructuredCommandExtractor { listOf(command) }, learnedPhrases = store,
    )
    try {
      vm.updateFloatingText(phrase)
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertNull(store.lookup(InterpretationRequest(phrase)))

      vm.confirmFloatingAction(true)
      runCurrent()
      assertEquals(command, store.lookup(InterpretationRequest(phrase)))

      vm.updateFloatingText(phrase)
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      assertEquals(1, classifications)
      assertEquals("Volumen multimedia ajustado.", vm.consumePhoneActionCompletion())

      vm.updateFloatingText("Olvidá esta frase")
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      assertNull(store.lookup(InterpretationRequest(phrase)))
    } finally {
      vm.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
  }

  @Test fun unaPropuestaObsoletaNoSeEjecutaNiSeAprende() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    val vm = MainViewModel(app,
      hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("Sin conexión") }),
      groqFallback = StructuredCommandExtractor {
        withContext(NonCancellable) { delay(500) }
        listOf(command)
      },
      learnedPhrases = store,
    )
    try {
      vm.updateFloatingText(phrase)
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      vm.updateFloatingText("Otro pedido")
      advanceUntilIdle()
      vm.confirmFloatingAction(true)
      assertEquals("Otro pedido", vm.floatingAssistant.value.text)
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertNull(vm.consumePhoneActionCompletion())
      assertNull(store.lookup(InterpretationRequest(phrase)))
    } finally {
      vm.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
  }

  @Test fun ensenaYCorrigeUnaFrasePorVozSinModelo() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val store = LearnedPhraseStore(PhraseTestDataStore(), { false }, updates = this)
    val vm = MainViewModel(app,
      hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { fail("Se llamó a Decisions"); error("Decisions") }),
      groqFallback = StructuredCommandExtractor { fail("Se llamó a Groq"); emptyList() }, learnedPhrases = store,
    )
    try {
      vm.updateFloatingText("Cuando diga menos ruido, bajá el volumen")
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      assertEquals(command, store.lookup(InterpretationRequest("menos ruido")))
      assertTrue(vm.floatingAssistant.value.status.orEmpty().startsWith("Aprendí «menos ruido»"))

      vm.updateFloatingText("Corregí esta frase: subí el volumen")
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      assertEquals(PhoneCommand.Volume(VolumeOp.UP), store.lookup(InterpretationRequest("menos ruido")))

      vm.updateFloatingText("Cuando diga subí el volumen, bajá el volumen")
      vm.sendFloatingText { fail("Se envió a Instinct") }
      runCurrent()
      assertTrue(vm.floatingAssistant.value.error.orEmpty().startsWith("Insto ya entiende"))
      assertNull(store.lookup(InterpretationRequest("subí el volumen")))
    } finally {
      vm.dismissFloatingAssistant()
      Dispatchers.resetMain()
    }
  }
}
