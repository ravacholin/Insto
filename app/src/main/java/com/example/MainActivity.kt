package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assist.AssistantRoleHelper
import com.example.speech.GroqSettingsActivity
import com.example.ui.MainViewModel
import com.example.ui.components.ContactEditorSheet
import com.example.ui.screens.AssistantHomeScreen
import com.example.ui.theme.PingAssistTheme

private enum class ContactEditorTarget {
  PRIMARY,
  DRIVING,
}

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onResume() {
    super.onResume()
    // Insto opened: the signed mailbox is read at once (off the main thread, at most every 20 seconds).
    runCatching { com.example.phone.InstinctMailbox.pollSoon(this) }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val openContactOnLaunch = intent?.getBooleanExtra(EXTRA_PROMPT_CONTACT_SETUP, false) == true

    setContent {
      PingAssistTheme {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val lifecycleOwner = LocalLifecycleOwner.current

        var editorTarget by remember {
          mutableStateOf(if (openContactOnLaunch) ContactEditorTarget.PRIMARY else null)
        }
        var hasAudioPermission by remember {
          mutableStateOf(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
              PackageManager.PERMISSION_GRANTED
          )
        }
        var attemptedPermissionInSession by remember { mutableStateOf(false) }

        val audioPermissionLauncher =
          rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
          ) { granted ->
            hasAudioPermission = granted
            if (granted) {
              viewModel.showInfoBanner("Microphone ready! Hold & release the orb to send audio.")
            } else {
              viewModel.showErrorBanner(
                "Microphone permission denied. Tap Enable Mic or open App Settings."
              )
            }
          }

        fun requestMicOrOpenSettings() {
          val grantedNow =
            ContextCompat.checkSelfPermission(
              this@MainActivity,
              Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
          if (grantedNow) {
            hasAudioPermission = true
            return
          }

          val shouldShowRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(
              this@MainActivity,
              Manifest.permission.RECORD_AUDIO,
            )

          if (attemptedPermissionInSession && !shouldShowRationale) {
            val settingsIntent =
              Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
              )
            runCatching { startActivity(settingsIntent) }
          } else {
            attemptedPermissionInSession = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
        }

        val assistantRoleLauncher =
          rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
          ) {
            viewModel.refreshSystemStatus()
          }

        DisposableEffect(lifecycleOwner) {
          val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
              viewModel.refreshSystemStatus()
              hasAudioPermission =
                ContextCompat.checkSelfPermission(
                  this@MainActivity,
                  Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            }
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(openContactOnLaunch) {
          if (openContactOnLaunch) {
            editorTarget = ContactEditorTarget.PRIMARY
          }
        }

        LaunchedEffect(uiState.preferences.hasPromptedMicPermission, hasAudioPermission) {
          if (!hasAudioPermission && !uiState.preferences.hasPromptedMicPermission) {
            viewModel.markMicPermissionPrompted()
            attemptedPermissionInSession = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
        }

        AssistantHomeScreen(
          uiState = uiState,
          hasAudioPermission = hasAudioPermission,
          onEditContactClick = { editorTarget = ContactEditorTarget.PRIMARY },
          onEditDrivingContactClick = { editorTarget = ContactEditorTarget.DRIVING },
          onClearDrivingContact = { viewModel.clearDrivingContact() },
          onTapOpenChat = { viewModel.openPinnedWhatsAppChat() },
          onStartRecording = { viewModel.startRecording(lockedHandsFree = false) },
          onStopAndSend = { viewModel.stopAndSendRecording() },
          onCancelRecording = { viewModel.cancelRecording() },
          onRequestAudioPermission = { requestMicOrOpenSettings() },
          onOpenProviderSettings = {
            startActivity(Intent(this@MainActivity, GroqSettingsActivity::class.java))
          },
          onOpenLearnedPhrases = {
            startActivity(Intent(this@MainActivity, GroqSettingsActivity::class.java)
              .putExtra(GroqSettingsActivity.EXTRA_PROVIDER, GroqSettingsActivity.SECTION_PHRASES))
          },
          onConfigurationImported = { viewModel.onConfigurationImported() },
          onSelectOrbHoldBehavior = { viewModel.setOrbHoldBehavior(it) },
          onSelectWhatsAppFlavor = { viewModel.setPreferredFlavor(it) },
          onSelectVolumeKeyTrigger = { viewModel.setVolumeKeyTrigger(it) },
          onSelectFloatingPillMode = { viewModel.setFloatingPillMode(it) },
          onToggleEyesFreeCues = { viewModel.setEyesFreeAudioCues(it) },
          onTestAudioCue = { viewModel.testEyesFreeCue() },
          onToggleBoomerangReturn = { viewModel.setBoomerangReturn(it) },
          onToggleCarBtAutoVoice = { viewModel.setCarBtAutoVoice(it) },
          onToggleNightWindow = { viewModel.setNightWindowEnabled(it) },
          onSetDefaultAssistantClick = {
            val intent = AssistantRoleHelper.buildSetDefaultAssistantIntent(this@MainActivity)
            runCatching { assistantRoleLauncher.launch(intent) }
          },
          onClearLogs = { viewModel.clearLogs() },
          onDismissBanner = { viewModel.clearBanner() },
        )

        val currentEditor = editorTarget
        if (currentEditor != null) {
          ContactEditorSheet(
            initialContact =
              when (currentEditor) {
                ContactEditorTarget.PRIMARY -> uiState.preferences.pinnedContact
                ContactEditorTarget.DRIVING -> uiState.preferences.contextRule.drivingContact
              },
            onDismiss = { editorTarget = null },
            onSaveContact = { name, phone ->
              when (currentEditor) {
                ContactEditorTarget.PRIMARY -> {
                  viewModel.savePinnedContact(name, phone)
                  if (!hasAudioPermission) {
                    attemptedPermissionInSession = true
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }
                }
                ContactEditorTarget.DRIVING -> viewModel.saveDrivingContact(name, phone)
              }
              editorTarget = null
            },
          )
        }
      }
    }
  }

  companion object {
    const val EXTRA_PROMPT_CONTACT_SETUP = "extra_prompt_contact_setup"
  }
}
