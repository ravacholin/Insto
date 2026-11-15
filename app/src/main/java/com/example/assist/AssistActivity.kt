package com.example.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.example.data.AssistantPreferencesRepository
import com.example.context.ContextRoutingEngine
import com.example.ui.MainViewModel
import com.example.ui.FloatingMode
import com.example.ui.screens.AssistOverlayScreen
import com.example.ui.theme.PingAssistTheme
import kotlinx.coroutines.runBlocking

class AssistActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var autoSendEnabled by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    val prefs = runBlocking { AssistantPreferencesRepository(this@AssistActivity).getCurrentPreferences() }
    if ((ContextRoutingEngine.resolveRoute(this, prefs).contact ?: prefs.pinnedContact) == null) {
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
    setContent {
      PingAssistTheme {
        val state by viewModel.floatingAssistant.collectAsStateWithLifecycle()
        var hasMic by remember { mutableStateOf(hasMicrophonePermission()) }
        var startAfterPermission by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          hasMic = granted
          if (granted && startAfterPermission && state.mode != FloatingMode.TYPING) {
            viewModel.startFloatingListening { finish() }
          }
          startAfterPermission = false
        }
        LaunchedEffect(Unit) {
          if (savedInstanceState == null && hasMic) viewModel.startFloatingListening { finish() }
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
          onSend = {
            if (state.mode == FloatingMode.TYPING) viewModel.sendFloatingText { finish() }
            else viewModel.sendFloatingAudio { finish() }
          },
          onDismissOverlay = dismiss,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    autoSendEnabled = PingAutoSendAccessibilityService.isEnabled(this)
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
