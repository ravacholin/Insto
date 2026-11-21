package com.example.speech

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.phone.GeminiSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KeySettingsUnreadableTest {
  @get:Rule val compose = createEmptyComposeRule()
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private fun damage(provider: String) = File(app.noBackupFilesDir, "$provider-key.enc").writeBytes(byteArrayOf(9, 9, 9))

  @Test fun unreadableKeysAreReportedAndNeverTreatedAsValid() {
    damage("groq"); damage("gemini")
    val gemini = GeminiSettings(app)
    assertEquals(StoredKey.Status.UNREADABLE, gemini.keyStatus())
    assertNull(gemini.readKey())
    gemini.setEnabled(true)
    assertFalse("Decisions never runs on an unreadable key", gemini.isEnabled())
    assertNull(GroqKeyStore(app).readValue())

    ActivityScenario.launch(GroqSettingsActivity::class.java).use {
      compose.onAllNodesWithText("Clave ilegible").assertCountEquals(2)
      compose.onAllNodesWithText(GroqKeyStore.UNREADABLE_MESSAGE).assertCountEquals(2)
      compose.onAllNodesWithText("Clave guardada").assertCountEquals(0)
      compose.onAllNodesWithText("Activo").assertCountEquals(0)
    }
  }

  @Test fun missingKeysKeepTheirExistingLabel() {
    ActivityScenario.launch(GroqSettingsActivity::class.java).use {
      compose.onAllNodesWithText("Sin clave").assertCountEquals(2)
      compose.onAllNodesWithText(GroqKeyStore.UNREADABLE_MESSAGE).assertCountEquals(0)
      compose.onNodeWithText("Configurar Groq").assertExists()
    }
  }
}
