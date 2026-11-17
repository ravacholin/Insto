package com.example

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
  val vm=MainViewModel(app,hybridRouter=router("instinct"))
  try {
   vm.updateFloatingText("bajá el volumen y abrí AplicacionInexistente")
   vm.sendFloatingText {}
   assertFalse(vm.floatingAssistant.value.needsConfirmation)
   assertFalse(vm.floatingAssistant.value.workflowActive)
   assertNull(vm.consumePhoneActionCompletion())
  } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
 }
 @Test fun classifiedCalendarCanBeCompletedWithoutRepeatingTitle() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  var calls=0
  val vm=MainViewModel(app,hybridRouter=HybridPhoneRouter({true},DecisionClassifier {calls++;SemanticDecision("calendar",.99,.99,.99,.99)}))
  try {
   vm.updateFloatingText("agendá reunión mañana");vm.sendFloatingText {fail("No WhatsApp")}
   assertEquals(1,calls)
   vm.updateFloatingText("a las 17:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertEquals(1,calls)
   assertTrue(vm.floatingAssistant.value.needsConfirmation)
   assertTrue(vm.floatingAssistant.value.workflowLines.single().contains("reunión"))
  } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
 }
 @Test fun editingCancelsDecisionBeforeItCanPrepareAPlan() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  val gate=CompletableDeferred<Unit>()
  val vm=MainViewModel(app,hybridRouter=HybridPhoneRouter({true},DecisionClassifier {
   withContext(NonCancellable) {gate.await()};SemanticDecision("calendar",.99,.99,.99,.99)
  }))
  try {
   vm.updateFloatingText("agendá reunión mañana a las 17:00");vm.sendFloatingText {fail("No WhatsApp")}
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
  val vm=MainViewModel(app,hybridRouter=router("calendar"))
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
