package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.emptyLearnedPhrasesForTest
import com.example.phone.*
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic concurrency scenarios on the real ViewModel (virtual time): a late answer never changes another
 * request, a cancelled request never executes, and a retained request still completes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingSessionIsolationTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val scheduler = TestCoroutineScheduler()

  @Before fun main() { Dispatchers.setMain(StandardTestDispatcher(scheduler)) }
  @After fun reset() { Dispatchers.resetMain() }

  /** Decisions answers «timer» after [decisionMillis]; extraction reads the minutes from the text. */
  private fun vm(decisionMillis: (String) -> Long = { 3_000 }, groq: StructuredCommandExtractor? = null,
    decisions: DecisionClassifier? = null, extraction: StructuredCommandExtractor? = null): MainViewModel {
    val router = HybridPhoneRouter({ true }, decisions ?: DecisionClassifier { text ->
      delay(decisionMillis(text)); SemanticDecision("timer", .99, .99, .99, .99)
    })
    return MainViewModel(app, learnedPhrases = emptyLearnedPhrasesForTest(), hybridRouter = router,
      structuredExtractor = extraction ?: StructuredCommandExtractor { text ->
        listOf(PhoneCommand.Timer(if ("veinte" in text) 1200 else 600))
      }, groqFallback = groq)
  }

  private fun MainViewModel.send(text: String) {
    enterFloatingTextMode(); updateFloatingText(text); sendFloatingText { fail("Phone request must not go to Instinct") }
  }

  private fun summary(seconds: Int) = PhoneCommandCodec.summary(PhoneCommand.Timer(seconds))

  @Test fun cancellingWhileGeminiAnswersNeverShowsOrRunsThePlan() = runTest(scheduler) {
    val vm = vm()
    try {
      vm.send("avisame en diez minutos"); runCurrent()
      advanceTimeBy(1_000)
      vm.cancelFloatingWorkflow()
      advanceUntilIdle()
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.isEmpty())
      assertNull(vm.consumePhoneActionCompletion())
      assertEquals("Pedido cancelado.", vm.floatingAssistant.value.status)
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun groqAnsweringAfterTheAssistantClosedIsDropped() = runTest(scheduler) {
    var delivered = 0
    val vm = vm(decisions = DecisionClassifier { throw DecisionException("Sin servicio", GeminiFailureKind.SERVER) },
      groq = StructuredCommandExtractor {
        // The provider ignores cancellation and answers late anyway.
        withContext(NonCancellable) { delay(2_000) }
        delivered++
        listOf(PhoneCommand.Timer(600))
      })
    vm.send("avisame en diez minutos"); runCurrent()
    advanceTimeBy(500)
    vm.dismissFloatingAssistant()
    advanceUntilIdle()
    assertEquals(1, delivered)
    assertFalse(vm.floatingAssistant.value.needsConfirmation)
    assertTrue(vm.floatingAssistant.value.workflowLines.isEmpty())
    assertNull(vm.consumePhoneActionCompletion())
  }

  @Test fun twoRequestsStartedAlmostTogetherKeepOnlyTheLatest() = runTest(scheduler) {
    val vm = vm(decisionMillis = { if ("veinte" in it) 100 else 3_000 })
    try {
      vm.send("avisame en diez minutos"); runCurrent()
      advanceTimeBy(10)
      vm.send("avisame en veinte minutos"); runCurrent()
      advanceUntilIdle()
      val lines = vm.floatingAssistant.value.workflowLines
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(lines.toString(), lines.single().contains(summary(1200)))
      assertFalse(lines.toString(), lines.any { it.contains(summary(600)) })
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun editingTheOrderDuringInterpretationDropsTheOldAnswer() = runTest(scheduler) {
    val vm = vm()
    try {
      vm.send("avisame en diez minutos"); runCurrent()
      advanceTimeBy(1_000)
      vm.enterFloatingTextMode(); vm.updateFloatingText("avisame en veinte")
      advanceUntilIdle()
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.isEmpty())
      assertEquals("avisame en veinte", vm.floatingAssistant.value.text)
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun anOlderAnswerArrivingWhileANewDraftExistsDoesNotReplaceIt() = runTest(scheduler) {
    val extraction = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed boundary expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult =
        if ("fideos" in request.text) InterpretationResult.Incomplete(
          DraftPlan(listOf(CommandDraft("s1", JSONObject("""{"type":"timer","label":"Fideos"}""")))))
        else InterpretationResult.Plan(listOf(PhoneCommand.Timer(600)))
    }
    val vm = vm(decisionMillis = { if ("fideos" in it) 100 else 3_000 }, extraction = extraction)
    try {
      vm.send("avisame en diez minutos"); runCurrent()
      advanceTimeBy(10)
      vm.send("necesito un aviso para los fideos"); runCurrent()
      advanceUntilIdle()
      // The draft asks for its missing duration; the older complete plan never appears.
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.isEmpty())
      assertNotNull(vm.floatingAssistant.value.status)
      vm.send("diez minutos"); runCurrent(); advanceUntilIdle()
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.single().contains("Fideos"))
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun recreatingTheActivityKeepsTheRequestInFlight() = runTest(scheduler) {
    val vm = vm()
    try {
      vm.send("avisame en diez minutos"); runCurrent()
      advanceTimeBy(1_000)
      // What a recreated AssistActivity does with the retained ViewModel (savedInstanceState != null).
      vm.prepareFloatingAssistant()
      advanceUntilIdle()
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertTrue(vm.floatingAssistant.value.workflowLines.single().contains(summary(600)))
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun aCancelledPlanCannotBeConfirmedLater() = runTest(scheduler) {
    val vm = vm(decisionMillis = { 0 })
    try {
      vm.send("avisame en diez minutos"); runCurrent(); advanceUntilIdle()
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      vm.cancelFloatingWorkflow()
      vm.confirmFloatingAction(true)
      advanceUntilIdle()
      assertNull(vm.consumePhoneActionCompletion())
      assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant() }
  }
}
