package com.example.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.audio.EyesFreeFeedbackManager
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferencesRepository
import com.example.data.PingActionLog
import com.example.data.PingActionType
import com.example.data.VoiceRecordingMode
import com.example.ui.MainViewModel
import com.example.ui.screens.AssistOverlayScreen
import com.example.ui.theme.PingAssistTheme
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import java.util.UUID
import kotlinx.coroutines.runBlocking

class AssistActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleAssistIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleAssistIntent(intent)
  }

  private fun handleAssistIntent(incomingIntent: Intent?) {
    val repository = AssistantPreferencesRepository(this)
    val prefs = runBlocking { repository.getCurrentPreferences() }
    val route = ContextRoutingEngine.resolveRoute(this, prefs)
    val contact = route.contact

    if (contact == null) {
      startActivity(
        Intent(this, MainActivity::class.java).apply {
          putExtra(MainActivity.EXTRA_PROMPT_CONTACT_SETUP, true)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
      )
      finish()
      return
    }

    val action = incomingIntent?.action
    val shouldInstantOpenChat =
      action == ACTION_OPEN_CHAT ||
        (action != ACTION_RECORD_AUDIO &&
          route.activationMode == AssistantActivationMode.INSTANT_OPEN_CHAT)

    if (shouldInstantOpenChat) {
      openChatAndFinish(repository, contact, prefs.preferredFlavor)
      return
    }

    // Tap = chat, hold = voice note. A hold is only knowable when the accessibility helper saw the
    // hotkey go down and it is still down now; otherwise this press is treated as a tap.
    var heldHotkey: HotkeyHoldTracker.HeldKey? = null
    if (
      action != ACTION_RECORD_AUDIO &&
        route.activationMode == AssistantActivationMode.TAP_CHAT_HOLD_AUDIO
    ) {
      val hasMic =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
      heldHotkey = HotkeyHoldTracker.heldCandidate()
      // Without mic access a hold can't record; only keep the overlay to ask for it the first time.
      if (heldHotkey == null || (!hasMic && prefs.hasPromptedMicPermission)) {
        openChatAndFinish(repository, contact, prefs.preferredFlavor)
        return
      }
    }
    val holdKey = heldHotkey

    val isDynamicWindow =
      action != ACTION_RECORD_AUDIO &&
        route.activationMode == AssistantActivationMode.DYNAMIC_VOICE_OR_CHAT

    if (!isDynamicWindow && holdKey == null) {
      val canUseNativePtt =
        prefs.voiceRecordingMode == VoiceRecordingMode.NATIVE_WHATSAPP_PTT &&
          PingAutoSendAccessibilityService.isEnabled(this) &&
          WhatsAppDispatcher.resolveTargetPackage(this, prefs.preferredFlavor) != null

      if (canUseNativePtt) {
        val pttResult =
          WhatsAppDispatcher.launchNativeWhatsAppPtt(
            context = this,
            phoneNumber = contact.phoneNumber,
            preferredFlavor = prefs.preferredFlavor,
            boomerangReturn = prefs.boomerangReturnEnabled,
          )
        if (pttResult is DispatchResult.NativePttArmed) {
          runBlocking {
            repository.appendActionLog(
              PingActionLog(
                id = UUID.randomUUID().toString(),
                type = PingActionType.NATIVE_PTT,
                contactName = contact.displayName,
                timestampMillis = System.currentTimeMillis(),
                durationSeconds = null,
                statusLabel = "Native WhatsApp PTT",
              )
            )
          }
          finish()
          return
        }
      }
    }

    setContent {
      PingAssistTheme {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        var hasAudioPermission by remember {
          mutableStateOf(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
              PackageManager.PERMISSION_GRANTED
          )
        }

        val permissionLauncher =
          rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
          ) { granted ->
            hasAudioPermission = granted
            if (holdKey != null) {
              // The key was released long ago while the dialog was up: behave as a tap.
              openChatAndFinish(repository, contact, prefs.preferredFlavor)
            } else if (granted) {
              if (isDynamicWindow) {
                viewModel.startDynamicAssistantSession { finish() }
              } else {
                viewModel.startRecording(lockedHandsFree = true)
              }
            } else if (isDynamicWindow) {
              // If user refused mic in dynamic mode, jump straight to WhatsApp chat instead of blocking
              openChatAndFinish(repository, contact, prefs.preferredFlavor)
            } else {
              EyesFreeFeedbackManager.playErrorCue(this, prefs.eyesFreeAudioCues)
              viewModel.showErrorBanner("Microphone permission is needed to record audio.")
            }
          }

        LaunchedEffect(Unit) {
          if (hasAudioPermission) {
            if (holdKey != null) {
              viewModel.startHoldToTalkSession(holdKey) { finish() }
            } else if (isDynamicWindow) {
              viewModel.startDynamicAssistantSession { finish() }
            } else {
              viewModel.startRecording(lockedHandsFree = true)
            }
          } else if (isDynamicWindow && prefs.hasPromptedMicPermission) {
            openChatAndFinish(repository, contact, prefs.preferredFlavor)
          } else {
            viewModel.markMicPermissionPrompted()
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
        }

        AssistOverlayScreen(
          uiState = uiState,
          hasAudioPermission = hasAudioPermission,
          onStartRecording = { viewModel.startRecording(lockedHandsFree = true) },
          onLockDynamicHold = { viewModel.lockDynamicSessionByHold() },
          onSkipDynamicAndOpenChat = {
            viewModel.skipDynamicWindowAndOpenChat { finish() }
          },
          onStopAndSend = {
            viewModel.stopAndSendRecording { success ->
              if (success) {
                finish()
              }
            }
          },
          onRequestAudioPermission = {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          },
          onDismissOverlay = {
            viewModel.cancelRecording()
            finish()
          },
        )
      }
    }
  }

  private fun openChatAndFinish(
    repository: AssistantPreferencesRepository,
    contact: com.example.data.PinnedContact,
    preferredFlavor: com.example.data.WhatsAppFlavor,
  ) {
    val result =
      WhatsAppDispatcher.openWhatsAppChat(
        context = this,
        phoneNumber = contact.phoneNumber,
        preferredFlavor = preferredFlavor,
      )
    if (result is DispatchResult.OpenedChat) {
      runBlocking {
        repository.appendActionLog(
          PingActionLog(
            id = UUID.randomUUID().toString(),
            type = PingActionType.OPEN_CHAT,
            contactName = contact.displayName,
            timestampMillis = System.currentTimeMillis(),
            durationSeconds = null,
            statusLabel = "Opened ${contact.displayName}",
          )
        )
      }
    }
    finish()
  }

  companion object {
    const val ACTION_OPEN_CHAT = "com.example.assist.ACTION_OPEN_CHAT"
    const val ACTION_RECORD_AUDIO = "com.example.assist.ACTION_RECORD_AUDIO"
    const val EXTRA_FROM_SYSTEM_ASSIST = "extra_from_system_assist"
  }
}
