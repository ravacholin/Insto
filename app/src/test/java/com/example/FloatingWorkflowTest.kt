package com.example

import com.example.data.emptyLearnedPhrasesForTest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.phone.*
import com.example.ui.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class FloatingWorkflowTest {
 private fun router(choice:String)=HybridPhoneRouter({true},DecisionClassifier {SemanticDecision(choice,.99,.99,.99,.99)})
 @Test fun newFreeSequenceDoesNotCreateOrExecuteLocalWorkflow() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  val vm=MainViewModel(app,learnedPhrases = emptyLearnedPhrasesForTest(), hybridRouter=router("instinct"))
  try {
   vm.updateFloatingText("bajá el volumen y abrí AplicacionInexistente")
   vm.sendFloatingText {}
   assertFalse(vm.floatingAssistant.value.needsConfirmation)
   assertFalse(vm.floatingAssistant.value.workflowActive)
   assertNull(vm.consumePhoneActionCompletion())
  } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
 }
 @Test fun localCalendarPermissionDenialDoesNotCallAiOrSendTheRequest() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app = ApplicationProvider.getApplicationContext<Application>()
  org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALENDAR)
  val vm = MainViewModel(app, learnedPhrases = emptyLearnedPhrasesForTest(), hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
   error("Calendar lookup must bypass Decisions")
  }))
  try {
   vm.updateFloatingText("qué tengo hoy en la agenda?")
   vm.sendFloatingText { fail("Calendar lookup must stay local") }
   runCurrent()
   assertTrue(vm.floatingAssistant.value.needsCalendarRead)
   vm.onFloatingCalendarPermission(false)
   assertFalse(vm.floatingAssistant.value.needsCalendarRead)
   assertTrue(vm.floatingAssistant.value.error.orEmpty().contains("permiso de lectura"))
  } finally {
   vm.dismissFloatingAssistant()
   Dispatchers.resetMain()
  }
 }
 @Test fun localCalendarCanBeCompletedWithoutRepeatingTitleOrWaitingForGoogle() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  var calls=0
  val vm=MainViewModel(app,learnedPhrases = emptyLearnedPhrasesForTest(), hybridRouter=HybridPhoneRouter({true},DecisionClassifier {calls++;SemanticDecision("calendar",.99,.99,.99,.99)}))
  try {
   vm.updateFloatingText("agendá reunión mañana");vm.sendFloatingText {fail("No WhatsApp")}
   assertEquals(0,calls)
   vm.updateFloatingText("a las 17:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertEquals(0,calls)
   assertTrue(vm.floatingAssistant.value.needsConfirmation)
   assertTrue(vm.floatingAssistant.value.workflowLines.single().contains("reunión"))
  } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
 }
 @Test fun editingCancelsDecisionBeforeItCanPrepareAPlan() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  val gate=CompletableDeferred<Unit>()
  val vm=MainViewModel(app,learnedPhrases = emptyLearnedPhrasesForTest(), hybridRouter=HybridPhoneRouter({true},DecisionClassifier {
   withContext(NonCancellable) {gate.await()};SemanticDecision("calendar",.99,.99,.99,.99)
  }))
  try {
   vm.updateFloatingText("reservame un encuentro mañana a eso de las cinco");vm.sendFloatingText {fail("No WhatsApp")}
   runCurrent();assertTrue(vm.floatingAssistant.value.deciding)
   vm.updateFloatingText("otro pedido");gate.complete(Unit);advanceUntilIdle()
   assertFalse(vm.floatingAssistant.value.workflowActive)
   assertNull(vm.consumePhoneActionCompletion())
   assertEquals("otro pedido",vm.floatingAssistant.value.text)
  }finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
 }
 @Test fun invalidCorrectionInvalidatesEarlierCalendarPreview() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  val vm=MainViewModel(app,learnedPhrases = emptyLearnedPhrasesForTest(), hybridRouter=router("calendar"))
  try {
   vm.updateFloatingText("agendá reunión mañana a las 17:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertTrue(vm.floatingAssistant.value.needsConfirmation)
   vm.updateFloatingText("mejor a las 99:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertFalse(vm.floatingAssistant.value.needsConfirmation)
   assertFalse(vm.floatingAssistant.value.workflowActive)
   vm.confirmFloatingAction(true)
   assertNull(vm.consumePhoneActionCompletion())
  }finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
 }
}
