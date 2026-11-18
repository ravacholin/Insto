package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.phone.*
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingGeminiCoordinatorTest {
  @Test fun editingAnUnconfirmedPlanImmediatelyPreventsItsExecution() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("timer", .99, .99, .99, .99) }),
      structuredExtractor = StructuredCommandExtractor { listOf(PhoneCommand.Timer(600)) })
    try {
      vm.updateFloatingText("avisame en diez minutos"); vm.sendFloatingText { fail("Phone request") }; runCurrent()
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      vm.updateFloatingText("mejor veinte minutos")
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      vm.confirmFloatingAction(true)
      assertNull(vm.consumePhoneActionCompletion())
      assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }
  @Test fun missingDurationPreservesLabelAndAsksOnlyOnceBeforePreview() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    var decisions = 0
    val router = HybridPhoneRouter({ true }, DecisionClassifier { decisions++; SemanticDecision("timer", .99, .99, .99, .99) })
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed boundary expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult = InterpretationResult.Incomplete(
        DraftPlan(listOf(CommandDraft("s1", JSONObject("""{"type":"timer","label":"Fideos"}""")))))
    }
    val vm = MainViewModel(app, hybridRouter = router, structuredExtractor = extractor)
    try {
      vm.updateFloatingText("avisame cuando termine el tiempo para los fideos")
      // Use an unconditional paraphrase; explicit conditions remain rejected.
      vm.updateFloatingText("necesito un aviso para los fideos")
      vm.sendFloatingText { fail("Incomplete phone request") }; runCurrent()
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      vm.updateFloatingText("diez minutos"); vm.sendFloatingText { fail("Phone preview") }; runCurrent()
      assertEquals(1, decisions)
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.single().contains("Fideos"))
      assertNull(vm.consumePhoneActionCompletion())
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun fallbackQueryDoesNotAskForConfirmationAndRetainsTextWhenContactIsMissing() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val router = HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("unavailable", GeminiFailureKind.UNAVAILABLE) })
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed boundary expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult = InterpretationResult.Query()
    }
    val vm = MainViewModel(app, hybridRouter = router, groqFallback = extractor)
    try {
      vm.updateFloatingText("ayudame a estudiar"); vm.sendFloatingText { fail("No contact configured") }; advanceUntilIdle()
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertFalse(vm.floatingAssistant.value.queryPreview)
      assertEquals("ayudame a estudiar", vm.floatingAssistant.value.text)
      assertNotNull(vm.floatingAssistant.value.error)
      vm.enterFloatingTextMode()
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      vm.updateFloatingText("otro pedido")
      vm.confirmFloatingAction(true)
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertEquals("otro pedido", vm.floatingAssistant.value.text)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }
}
