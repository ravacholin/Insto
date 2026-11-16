package com.example.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.speech.GroqSettingsActivity
import com.example.MainActivity
import com.example.data.AssistantPreferencesRepository
import com.example.context.ContextRoutingEngine
import com.example.ui.MainViewModel
import com.example.ui.FloatingMode
import com.example.ui.screens.AssistOverlayScreen
import com.example.ui.theme.PingAssistTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AssistActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var autoSendEnabled by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    val prefs = runBlocking { AssistantPreferencesRepository(this@AssistActivity).getCurrentPreferences() }
    if (intent?.action == ACTION_OPEN_CHAT && (ContextRoutingEngine.resolveRoute(this, prefs).contact ?: prefs.pinnedContact) == null) {
      startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROMPT_CONTACT_SETUP, true))
      finish()
      return
    }
    if (intent?.action == ACTION_OPEN_CHAT) {
      viewModel.openPinnedWhatsAppChat(prefs)
      finish()
      return
    }
    viewModel.prepareFloatingAssistant()
    // A native handoff can stop this window before Compose gets another frame.
    // Observe until destruction so completion still closes the stopped overlay.
    lifecycleScope.launch {
      viewModel.floatingAssistant.collect {
        viewModel.consumePhoneActionCompletion()?.let { message ->
          Toast.makeText(this@AssistActivity, message, Toast.LENGTH_SHORT).show()
          finish()
        }
      }
    }
    setContent {
      PingAssistTheme {
        val state by viewModel.floatingAssistant.collectAsStateWithLifecycle()
        val gemini = remember { com.example.phone.GeminiSettings(this@AssistActivity) }
        var showGeminiSetup by remember { mutableStateOf(gemini.shouldPromptSetup()) }
        var hasMic by remember { mutableStateOf(hasMicrophonePermission()) }
        var startAfterPermission by remember { mutableStateOf(false) }
        var startAfterGeminiSetup by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          hasMic = granted
          if (granted && startAfterPermission && state.mode != FloatingMode.TYPING) {
            viewModel.startFloatingListening { finish() }
          }
          startAfterPermission = false
        }
        val groqSetup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
          if (result.resultCode == RESULT_OK && viewModel.floatingAssistant.value.needsGroqKey) {
            viewModel.sendFloatingAudio { finish() }
          } else if (startAfterGeminiSetup && result.resultCode == RESULT_OK && hasMic &&
            viewModel.floatingAssistant.value.mode == FloatingMode.IDLE) {
            viewModel.resumeFloatingAssistant()
            viewModel.startFloatingListening { finish() }
          }
          startAfterGeminiSetup = false
        }
        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          viewModel.onFloatingCameraPermission(granted)
        }
        LaunchedEffect(state.needsCamera) {
          if (state.needsCamera) cameraPermission.launch(Manifest.permission.CAMERA)
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          viewModel.onFloatingNotificationsPermission(granted)
        }
        LaunchedEffect(state.needsNotifications) {
          if (state.needsNotifications && android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          viewModel.onFloatingContactsPermission(granted)
        }
        LaunchedEffect(state.needsContacts) {
          if (state.needsContacts) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
          if (uri != null) viewModel.selectFloatingAttachment(uri)
        }
        LaunchedEffect(Unit) {
          if (savedInstanceState == null && hasMic && !showGeminiSetup && !state.workflowActive) viewModel.startFloatingListening { finish() }
        }
        if (showGeminiSetup) {
          AlertDialog(
            onDismissRequest = { showGeminiSetup = false },
            title = { Text("Configurá Gemini Decisions") },
            text = { Text("Falta tu clave de Gemini. Groq transcribe la voz; Gemini ayuda a entender pedidos que el intérprete local no reconoce. Para activarlo, cargá tu clave personal de Google AI Studio. Los pedidos claros del teléfono funcionan sin Gemini.") },
            confirmButton = {
              TextButton(onClick = {
                showGeminiSetup = false
                startAfterGeminiSetup = true
                viewModel.pauseFloatingAssistant()
                groqSetup.launch(Intent(this@AssistActivity, GroqSettingsActivity::class.java))
              }) { Text("Configurar Gemini") }
            },
            dismissButton = {
              TextButton(onClick = {
                gemini.continueLocally()
                showGeminiSetup = false
                if (hasMic && viewModel.floatingAssistant.value.mode == FloatingMode.IDLE) {
                  viewModel.resumeFloatingAssistant()
                  viewModel.startFloatingListening { finish() }
                }
              }) { Text("Continuar sin Gemini") }
            },
          )
        }
        val dismiss = { viewModel.dismissFloatingAssistant(); finish() }
        BackHandler(onBack = dismiss)
        AssistOverlayScreen(
          state = state,
          hasAudioPermission = hasMic,
          autoSendEnabled = autoSendEnabled,
          onTextFocus = {
            startAfterPermission = false
            viewModel.enterFloatingTextMode()
          },
          onTextChange = viewModel::updateFloatingText,
          onMicrophone = {
            hasMic = hasMicrophonePermission()
            if (hasMic) viewModel.startFloatingListening { finish() }
            else {
              startAfterPermission = true
              permission.launch(Manifest.permission.RECORD_AUDIO)
            }
          },
          onAttach = {
            startAfterPermission = false
            viewModel.enterFloatingTextMode()
            attachmentPicker.launch(arrayOf("*/*"))
          },
          onRemoveAttachment = viewModel::removeFloatingAttachment,
          onSend = {
            if (state.attachment != null) viewModel.sendFloatingAttachment { finish() }
            else if (state.mode == FloatingMode.TYPING) viewModel.sendFloatingText { finish() }
            else viewModel.sendFloatingAudio { finish() }
          },
          onConfigureGroq = {
            viewModel.pauseFloatingAssistant()
            groqSetup.launch(Intent(this, GroqSettingsActivity::class.java))
          },
          onConfigureClockAccessibility = {
            viewModel.pauseFloatingAssistant()
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
          },
          onConfigureContact = {
            viewModel.pauseFloatingAssistant()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROMPT_CONTACT_SETUP, true))
          },
          onConfirm = viewModel::confirmFloatingAction,
          onChooseApp = viewModel::chooseFloatingApp,
          onChooseContact = viewModel::chooseFloatingContact,
          onContinueWorkflow = viewModel::continueFloatingWorkflow,
          onRetryWorkflow = viewModel::retryFloatingWorkflow,
          onCancelWorkflow = viewModel::cancelFloatingWorkflow,
          onDismissOverlay = dismiss,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.resumeFloatingAssistant()
    autoSendEnabled = PingAutoSendAccessibilityService.isEnabled(this)
    viewModel.prepareFloatingAssistant()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // A repeated hotkey brings the existing panel forward without destroying its draft.
  }

  override fun onStop() {
    viewModel.pauseFloatingAssistant()
    super.onStop()
  }

  private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this,
    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  companion object {
    const val ACTION_OPEN_CHAT = "com.example.assist.ACTION_OPEN_CHAT"
    const val ACTION_RECORD_AUDIO = "com.example.assist.ACTION_RECORD_AUDIO"
    const val EXTRA_FROM_SYSTEM_ASSIST = "extra_from_system_assist"
  }
}
