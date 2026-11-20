package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTestDataStore
import com.example.phone.InterpretationRequest
import com.example.phone.PhoneCommand
import com.example.ui.screens.LearnedPhrasesContent
import com.example.ui.theme.PingAssistTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])

class LearnedPhrasesScreenTest {
  @get:Rule val composeRule = createComposeRule()

  /** The editor checks the phrase after a short pause. */
  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
  }

  @Test fun agregaYEditaUnaFraseTocandoSinEscribirPedidos() {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { true })
    runBlocking {
      store.teach("modo estudio", PhoneCommand.Platform(com.example.phone.PlatformAction.ROUTINE_RUN, "Estudio"))
      store.learn(InterpretationRequest("un poco menos de sonido"), PhoneCommand.Volume(com.example.phone.VolumeOp.DOWN), true,
        com.example.phone.PhoneActionResult("Listo."))
    }
    composeRule.setContent {
      PingAssistTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
          Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LearnedPhrasesContent(store, onMessage = {})
          }
        }
      }
    }

    composeRule.onNode(hasText("Agregar frase") and hasClickAction()).performScrollTo().performClick()
    composeRule.onNodeWithText("Cuando diga").performTextInput("luz")
    composeRule.onNodeWithText("Elegir acción").performClick()
    composeRule.onNodeWithText("Encender la linterna").performClick()
    settle()
    composeRule.onNodeWithText("Guardar").assertIsEnabled().performClick()
    composeRule.waitForIdle()
    assertEquals(PhoneCommand.Torch(true), runBlocking { store.lookup(InterpretationRequest("luz")) })

    // Tocar la frase abre la edición; se cambia la acción por otra del menú.
    composeRule.onNodeWithText("«luz»").performScrollTo().performClick()
    composeRule.onNodeWithText("Cambiar acción").performClick()
    composeRule.onNodeWithText("Poner el volumen en un porcentaje").performScrollTo().performClick()
    composeRule.onNodeWithText("Porcentaje (0 a 100)").performTextInput("30")
    composeRule.onNodeWithText("Listo").performClick()
    composeRule.onNodeWithText("Cuando diga").performTextClearance()
    composeRule.onNodeWithText("Cuando diga").performTextInput("claridad")
    settle()
    composeRule.onNodeWithText("Guardar").assertIsEnabled().performClick()
    composeRule.waitForIdle()
    assertNull(runBlocking { store.lookup(InterpretationRequest("luz")) })
    assertEquals(PhoneCommand.Volume(com.example.phone.VolumeOp.SET, 30), runBlocking { store.lookup(InterpretationRequest("claridad")) })
  }

  @Test fun unaFraseQueInstoYaEntiendeNoSePuedeGuardar() {
    val store = LearnedPhraseStore(PhraseTestDataStore(), { true })
    composeRule.setContent { PingAssistTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LearnedPhrasesContent(store, onMessage = {}) } } }
    composeRule.onNode(hasText("Agregar frase") and hasClickAction()).performScrollTo().performClick()
    composeRule.onNodeWithText("Cuando diga").performTextInput("subí el volumen")
    composeRule.onNodeWithText("Elegir acción").performClick()
    composeRule.onNodeWithText("Encender la linterna").performClick()
    settle()
    composeRule.onNodeWithText("Insto ya entiende", substring = true).assertExists()
    composeRule.onNodeWithText("Guardar").assertIsNotEnabled()
  }
}
