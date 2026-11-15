package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.FloatingAssistantState
import com.example.ui.FloatingMode
import com.example.ui.screens.AssistOverlayScreen
import com.example.ui.theme.PingAssistTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FloatingOverlayTest {
  @get:Rule val composeRule = createComposeRule()

  @Test fun `text focus cancels voice before any characters are entered`() {
    var focused = false
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(mode = FloatingMode.LISTENING), true, true,
          { focused = true }, {}, {}, {}, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_text_input").performClick()
    composeRule.runOnIdle { assertTrue(focused) }
  }

  @Test fun `idle blank text cannot send but microphone can restart`() {
    var restarted = false
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(), false, false, {}, {}, { restarted = true }, {}, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_send_button").assertIsNotEnabled()
    composeRule.onNodeWithTag("overlay_microphone").performClick()
    composeRule.runOnIdle { assertTrue(restarted) }
  }

  @Test fun `recording has immediate manual send`() {
    var sent = false
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(mode = FloatingMode.RECORDING), true, true,
          {}, {}, {}, { sent = true }, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_send_button").performClick()
    composeRule.runOnIdle { assertTrue(sent) }
  }
  @Test fun `title opens chat in every mode without sending`() {
    var opened = 0
    var sent = false
    val state = androidx.compose.runtime.mutableStateOf(FloatingAssistantState())
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(state.value, false, false, {}, {}, {}, { sent = true }, {}, { opened++ })
      }
    }
    FloatingMode.entries.forEach { mode ->
      composeRule.runOnIdle { state.value = FloatingAssistantState(mode = mode, text = "Borrador") }
      composeRule.onNodeWithTag("overlay_open_chat").assertIsEnabled().performClick()
    }
    composeRule.runOnIdle {
      assertEquals(FloatingMode.entries.size, opened)
      assertFalse(sent)
    }
  }

}
