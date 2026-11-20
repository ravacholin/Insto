package com.example

import android.app.Application
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.example.backup.AndroidBackup
import com.example.backup.BackupCategory
import com.example.backup.BackupCrypto
import com.example.backup.FileRestoreMarker
import com.example.phone.SavedPlacesRepository
import com.example.ui.screens.BackupRestoreSettings
import com.example.ui.theme.PingAssistTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupScreenTest {
  @get:Rule val composeRule = createComposeRule()
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  @After fun clean() { File(app.noBackupFilesDir, AndroidBackup.MARKER).delete() }

  /** Answers every document picker with [answer], like the system picker returning a location or nothing. */
  private fun picker(answer: () -> Uri?) = object : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
      override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, answer() as O)
      }
    }
  }

  private fun show(answer: () -> Uri?) = composeRule.setContent {
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker(answer)) {
      PingAssistTheme { Surface(color = MaterialTheme.colorScheme.background) { BackupRestoreSettings() } }
    }
  }

  private fun export(password: String = "una contraseña larga", confirm: String = password) {
    composeRule.onNodeWithTag("backup_export").performClick()
    composeRule.onNodeWithTag("backup_password").performTextInput(password)
    composeRule.onNodeWithTag("backup_password_confirm").performTextInput(confirm)
  }

  private fun waitForMessage(text: String) = composeRule.waitUntil(20_000) {
    composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
  }

  @Test fun passwordsMustMatchAndBeLongEnough() {
    show { null }
    export(password = "corta", confirm = "corta")
    composeRule.onNodeWithTag("backup_create").assertIsNotEnabled()
    composeRule.onNodeWithTag("backup_password").performTextInput(" pero ahora larga")
    composeRule.onNodeWithTag("backup_create").assertIsNotEnabled() // Confirmation differs now.
  }

  @Test fun cancellingThePickerSavesNothing() {
    show { null }
    export()
    composeRule.onNodeWithTag("backup_create").performClick()
    waitForMessage("Exportación cancelada")
  }

  @Test fun theCopyIsReportedOnlyAfterTheWriteSucceeded() {
    SavedPlacesRepository(app).save("casa", "Calle Falsa 123")
    val target = File(app.cacheDir, "copia.insto").apply { delete() }
    show { Uri.fromFile(target) }
    export()
    composeRule.onNodeWithTag("backup_create").performClick()
    waitForMessage("Copia creada")
    val bytes = target.readBytes()
    assertTrue(BackupCrypto.isEnvelope(bytes))
    assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Calle Falsa"))
  }

  @Test fun aWriteErrorIsShownAsSuch() {
    show { Uri.fromFile(File("/no/existe/esta/carpeta/copia.insto")) }
    export()
    composeRule.onNodeWithTag("backup_create").performClick()
    waitForMessage("No se pudo escribir la copia")
    composeRule.onAllNodesWithText("Copia creada", substring = true).assertCountEquals(0)
  }

  @Test fun anInterruptedRestoreIsReported() {
    FileRestoreMarker(File(app.noBackupFilesDir, AndroidBackup.MARKER)).begin(listOf(BackupCategory.PHRASES))
    show { null }
    composeRule.onNodeWithText("La última restauración se interrumpió", substring = true).assertExists()
  }
}
