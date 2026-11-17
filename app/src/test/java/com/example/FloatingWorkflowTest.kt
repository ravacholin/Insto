package com.example
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 @Test fun sequenceWaitsForConfirmationAndDoesNotCloseAtIntermediateStep() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()
  try {
   val vm=MainViewModel(app)
   vm.updateFloatingText("bajá el volumen y abrí AplicacionInexistente")
   vm.sendFloatingText {fail("No debe ir a WhatsApp")}
   assertTrue(vm.floatingAssistant.value.needsConfirmation)
   assertEquals(2,vm.floatingAssistant.value.workflowLines.size)
   assertNull(vm.consumePhoneActionCompletion())
   vm.confirmFloatingAction(true)
   assertNull(vm.consumePhoneActionCompletion())
   assertNotNull(vm.floatingAssistant.value.error)
   assertTrue(vm.floatingAssistant.value.workflowLines[0].contains("completado"))
   vm.dismissFloatingAssistant()
  } finally {Dispatchers.resetMain();java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()}
 }
 @Test fun calendarCanBeCompletedWithoutRepeatingTitle() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()
  try {
   val vm=MainViewModel(app)
   vm.updateFloatingText("agendá reunión mañana");vm.sendFloatingText {fail("No WhatsApp")}
   assertNull(vm.consumePhoneActionCompletion())
   vm.updateFloatingText("a las 17:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertTrue(vm.floatingAssistant.value.needsConfirmation)
   assertTrue(vm.floatingAssistant.value.workflowLines.single().contains("reunión"))
   vm.dismissFloatingAssistant()
  } finally {Dispatchers.resetMain();java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()}
 }
 @Test fun editingCancelsStructuredAnswerBeforeItCanPrepareAPlan() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()
  val gate=kotlinx.coroutines.CompletableDeferred<List<com.example.phone.PhoneCommand>>()
  try {
   val router=com.example.phone.HybridPhoneRouter({true},com.example.phone.DecisionClassifier {com.example.phone.SemanticDecision("timer",.99,.99,.99,.99)})
   val vm=MainViewModel(app,hybridRouter=router,structuredExtractor=com.example.phone.StructuredCommandExtractor {gate.await()})
   vm.updateFloatingText("avisame en diez minutos");vm.sendFloatingText {fail("No WhatsApp")}
   runCurrent();assertTrue(vm.floatingAssistant.value.deciding)
   vm.updateFloatingText("otro pedido")
   gate.complete(listOf(com.example.phone.PhoneCommand.Timer(600)))
   advanceUntilIdle()
   assertFalse(vm.floatingAssistant.value.workflowActive)
   assertNull(vm.consumePhoneActionCompletion())
   assertEquals("otro pedido",vm.floatingAssistant.value.text)
   vm.dismissFloatingAssistant()
  }finally {Dispatchers.resetMain();java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()}
 }

 @Test fun invalidCorrectionInvalidatesEarlierPreview() = runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  val app=ApplicationProvider.getApplicationContext<Application>()
  java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()
  try {
   val vm=MainViewModel(app)
   vm.updateFloatingText("agendá reunión mañana a las 17:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertTrue(vm.floatingAssistant.value.needsConfirmation)
   vm.updateFloatingText("mejor a las 99:00");vm.sendFloatingText {fail("No WhatsApp")}
   assertFalse(vm.floatingAssistant.value.needsConfirmation)
   assertFalse(vm.floatingAssistant.value.workflowActive)
   vm.confirmFloatingAction(true)
   assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
   vm.dismissFloatingAssistant()
  }finally {Dispatchers.resetMain();java.io.File(app.noBackupFilesDir,"phone-workflow.json").delete()}
 }

}
