package com.example.alarmvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PingAssistTheme
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * The minimal screen shown over the lock screen while an alarm rings: Android only lets an app open the microphone
 * while it is visible, so listening starts here, in [onResume].
 */
class AlarmVoiceStopActivity : ComponentActivity() {
  private var alarmId = -1L
  private var test = false
  private var strategy: String? = null
  private var stopping = false
  /** Listening starts once per alarm shown; coming back to the screen does not restart it. */
  private var listenedOnce = false

  private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) startListening() else AlarmVoiceCoordinator.finishUi("Sin permiso de micrófono no puedo escuchar.")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
    else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
      WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    readIntent(intent)
    setContent { PingAssistTheme { Screen() } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    listenedOnce = false
    stopping = false
    readIntent(intent)
  }

  private fun readIntent(intent: Intent?) {
    alarmId = intent?.getLongExtra(AlarmVoiceCoordinator.EXTRA_ALARM_ID, -1L) ?: -1L
    test = intent?.getBooleanExtra(AlarmVoiceCoordinator.EXTRA_TEST, false) == true
    strategy = intent?.getStringExtra(AlarmVoiceCoordinator.EXTRA_STRATEGY)
    if (test) AlarmVoiceCoordinator.state.value = AlarmVoiceUiState()
  }

  override fun onResume() {
    super.onResume()
    if (!test) {
      if (!AlarmVoiceCoordinator.isCurrent(alarmId)) {
        AlarmVoiceCoordinator.finishUi("La alarma ya no está sonando.")
        return
      }
      AlarmVoiceCoordinator.activityShown(this, alarmId, strategy)
    }
    if (listenedOnce || AlarmVoiceStopService.isRunning) return
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
  }

  private fun startListening() {
    if (stopping || listenedOnce || AlarmVoiceStopService.isRunning) return
    listenedOnce = true
    AlarmVoiceCoordinator.state.value = AlarmVoiceUiState()
    AlarmVoiceStopService.start(this, alarmId, test)
  }

  override fun onDestroy() {
    // Closing the screen ends listening; the service restores the volume in its finally, this covers the rest.
    if (isFinishing && !isChangingConfigurations) {
      AlarmVoiceStopService.requestStop("pantalla cerrada")
      if (!AlarmVoiceStopService.isRunning) AlarmVolumeDucker.restore(this)
    }
    super.onDestroy()
  }

  private fun stopAlarmNow() {
    if (stopping) return
    stopping = true
    AlarmVoiceStopService.requestStop("botón Detener")
    val app = applicationContext
    thread(name = "alarm-voice-dismiss") {
      if (test) { AlarmVoiceCoordinator.finishUi("Era una prueba: no había alarma."); return@thread }
      val method = AlarmVoiceCoordinator.dismiss(app)
      AlarmVoiceLog.add(app, "Apagada con el botón «Detener» de Insto: $method")
      com.example.audio.EyesFreeFeedbackManager.playSentCue(app, false)
      AlarmVoiceCoordinator.finishUi("Alarma apagada.")
    }
  }

  private fun stopListening() {
    AlarmVoiceStopService.requestStop("dejé de escuchar")
    if (!AlarmVoiceStopService.isRunning) AlarmVolumeDucker.restore(this)
    finish()
  }

  @androidx.compose.runtime.Composable
  private fun Screen() {
    val ui by AlarmVoiceCoordinator.uiState.collectAsState()
    val words = androidx.compose.runtime.remember { AlarmVoiceSettings(this).keywords() }
    LaunchedEffect(ui.finished) { if (ui.finished) { delay(if (test) 2_500 else 1_200); finish() } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(ui.status, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        ui.remainingSeconds?.let { Text("$it s", style = MaterialTheme.typography.titleMedium) }
        ui.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
        Text("Decí: " + words.joinToString(" · ") { "«$it»" }, style = MaterialTheme.typography.titleLarge,
          textAlign = TextAlign.Center)
        if (test) Text("Prueba: no se apaga ninguna alarma.", style = MaterialTheme.typography.bodyMedium)
        Button(
          onClick = ::stopAlarmNow,
          enabled = !ui.finished && !stopping,
          modifier = Modifier.fillMaxWidth().height(96.dp),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Detener", fontSize = 28.sp) }
        OutlinedButton(onClick = ::stopListening, modifier = Modifier.fillMaxWidth().height(56.dp)) {
          Text("Dejar de escuchar")
        }
      }
    }
  }
}
