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

  @Test fun `enabled but disconnected auto send service still exposes accessibility settings`() {
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(mode = FloatingMode.TYPING,
          needsWhatsAppAccessibility = true), true, true, {}, {}, {}, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_enable_auto_send").performScrollTo().assertIsDisplayed()
  }

  @Test fun `text remains editable while Gemini decides but sending stays disabled`() {
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(mode = FloatingMode.SENDING, deciding = true), true, true,
          {}, {}, {}, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_text_input").assertIsEnabled()
    composeRule.onNodeWithTag("overlay_send_button").assertIsNotEnabled()
  }

  @Test fun `text focus cancels voice before any characters are entered`() {
    var focused = false
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(mode = FloatingMode.LISTENING), true, true,
          { focused = true }, {}, {}, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_text_input").performClick()
    composeRule.runOnIdle { assertTrue(focused) }
  }

  @Test fun `idle blank text cannot send but microphone can restart`() {
    var restarted = false
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(), false, false, {}, {}, { restarted = true }, {}, {})
      }
    }
    composeRule.onNodeWithTag("overlay_send_button").assertIsNotEnabled()
    composeRule.onNodeWithTag("overlay_microphone").performScrollTo().performClick()
    composeRule.runOnIdle { assertTrue(restarted) }
  }

  @Test fun `recording has immediate manual send`() {
    var sent = false
    composeRule.setContent {
      PingAssistTheme {
        AssistOverlayScreen(FloatingAssistantState(mode = FloatingMode.RECORDING), true, true,
          {}, {}, {}, { sent = true }, {})
      }
    }
    composeRule.onNodeWithTag("overlay_send_button").performClick()
    composeRule.runOnIdle { assertTrue(sent) }
  }
}
