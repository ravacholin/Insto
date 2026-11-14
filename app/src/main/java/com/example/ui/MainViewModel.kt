package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.assist.AssistantRoleHelper
import com.example.assist.PingAssistWidgetProvider
import com.example.assist.PingAutoSendAccessibilityService
import com.example.assist.ShortcutHelper
import com.example.audio.EyesFreeFeedbackManager
import com.example.audio.VoiceRecorderManager
import com.example.context.ContextRoutingEngine
import com.example.context.EffectiveContextRoute
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.data.FloatingPillMode
import com.example.data.OrbHoldBehavior
import com.example.data.PingActionLog
import com.example.data.PingActionType
import com.example.data.VoiceRecordingMode
import com.example.data.VolumeKeyTrigger
import com.example.data.WhatsAppFlavor
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.InstalledWhatsAppInfo
import com.example.whatsapp.WhatsAppDispatcher
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface RecordingUiState {
  data object Idle : RecordingUiState

  data class ListeningWindow(
    val elapsedMs: Long,
    val windowMs: Long,
    val speechEnergy: Float,
  ) : RecordingUiState

  data class Recording(
    val elapsedMs: Long,
    val amplitudes: List<Float>,
    val isLockedHandsFree: Boolean,
  ) : RecordingUiState

  data object Sending : RecordingUiState
}

data class SystemIntegrationState(
  val isDefaultAssistant: Boolean = false,
  val isAutoSendEnabled: Boolean = false,
  val installedWhatsApp: InstalledWhatsAppInfo = InstalledWhatsAppInfo(false, false),
)

data class PingAssistUiState(
  val preferences: AssistantPreferences = AssistantPreferences(),
  val effectiveRoute: EffectiveContextRoute =
    EffectiveContextRoute(
      contact = null,
      activationMode = AssistantActivationMode.DYNAMIC_VOICE_OR_CHAT,
      activeContextLabel = null,
      isCarOrBluetoothActive = false,
      connectedAudioDeviceName = null,
    ),
  val recordingState: RecordingUiState = RecordingUiState.Idle,
  val systemState: SystemIntegrationState = SystemIntegrationState(),
  val statusBanner: String? = null,
  val isBannerError: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = AssistantPreferencesRepository(application)
  private val recorder = VoiceRecorderManager(application)

  private val recordingState = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
  private val systemState = MutableStateFlow(readSystemState(application))
  private val contextRefreshTick = MutableStateFlow(0L)
  private val bannerState = MutableStateFlow<Pair<String?, Boolean>>(null to false)

  private var amplitudeJob: Job? = null
  private var stopDispatchJob: Job? = null
  private var dynamicSessionJob: Job? = null
  private var dynamicHoldLocked = false
  private var audioCallback: AudioDeviceCallback? = null

  init {
    val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val callback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
          contextRefreshTick.value = System.currentTimeMillis()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
          contextRefreshTick.value = System.currentTimeMillis()
        }
      }
    audioCallback = callback
    audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
  }

  val uiState: StateFlow<PingAssistUiState> =
    combine(
        repository.preferencesFlow,
        recordingState,
        systemState,
        bannerState,
        contextRefreshTick,
      ) { prefs, rec, sys, banner, _ ->
        val route = ContextRoutingEngine.resolveRoute(getApplication(), prefs)
        PingAssistUiState(
          preferences = prefs,
          effectiveRoute = route,
          recordingState = rec,
          systemState = sys,
          statusBanner = banner.first,
          isBannerError = banner.second,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PingAssistUiState(systemState = readSystemState(application)),
      )

  fun refreshSystemStatus() {
    systemState.value = readSystemState(getApplication())
    contextRefreshTick.value = System.currentTimeMillis()
  }

  fun clearBanner() {
    bannerState.value = null to false
  }

  fun showInfoBanner(message: String) {
    bannerState.value = message to false
  }

  fun showErrorBanner(message: String) {
    bannerState.value = message to true
  }

  fun markMicPermissionPrompted() {
    viewModelScope.launch { repository.markMicPermissionPrompted() }
  }

  fun savePinnedContact(name: String, phone: String) {
    viewModelScope.launch {
      repository.savePinnedContact(name, phone)
      val updated = repository.getCurrentPreferences().pinnedContact
      ShortcutHelper.syncShortcuts(getApplication(), updated)
      PingAssistWidgetProvider.requestUpdate(getApplication())
      bannerState.value = "Pinned ${updated?.displayName ?: phone} as your target contact." to false
    }
  }

  fun saveDrivingContact(name: String, phone: String) {
    viewModelScope.launch {
      repository.saveDrivingContact(name, phone)
      bannerState.value = "Driving contact updated." to false
    }
  }

  fun clearDrivingContact() {
    viewModelScope.launch {
      repository.clearDrivingContact()
      bannerState.value = "Using primary contact while driving." to false
    }
  }

  fun setActivationMode(mode: AssistantActivationMode) {
    viewModelScope.launch { repository.setActivationMode(mode) }
  }

  fun setOrbHoldBehavior(behavior: OrbHoldBehavior) {
    viewModelScope.launch { repository.setOrbHoldBehavior(behavior) }
  }

  fun setPreferredFlavor(flavor: WhatsAppFlavor) {
    viewModelScope.launch { repository.setPreferredFlavor(flavor) }
  }

  fun setVoiceRecordingMode(mode: VoiceRecordingMode) {
    viewModelScope.launch { repository.setVoiceRecordingMode(mode) }
  }

  fun setVolumeKeyTrigger(trigger: VolumeKeyTrigger) {
    viewModelScope.launch { repository.setVolumeKeyTrigger(trigger) }
  }

  fun setFloatingPillMode(mode: FloatingPillMode) {
    viewModelScope.launch { repository.setFloatingPillMode(mode) }
  }

  fun setEyesFreeAudioCues(enabled: Boolean) {
    viewModelScope.launch { repository.setEyesFreeAudioCues(enabled) }
  }

  fun setBoomerangReturn(enabled: Boolean) {
    viewModelScope.launch { repository.setBoomerangReturn(enabled) }
  }

  fun setCarBtAutoVoice(enabled: Boolean) {
    viewModelScope.launch { repository.setCarBtAutoVoice(enabled) }
  }

  fun setNightWindowEnabled(enabled: Boolean) {
    viewModelScope.launch { repository.setNightWindowEnabled(enabled) }
  }

  fun testEyesFreeCue() {
    EyesFreeFeedbackManager.playStartCue(
      getApplication(),
      uiState.value.preferences.eyesFreeAudioCues,
    )
    bannerState.value = "Played start audio & haptic cue." to false
  }

  fun clearLogs() {
    viewModelScope.launch { repository.clearLogs() }
  }

  fun openPinnedWhatsAppChat(): Boolean {
    val prefs = uiState.value.preferences
    val contact = uiState.value.effectiveRoute.contact ?: prefs.pinnedContact
    if (contact == null) {
      bannerState.value = "Select a WhatsApp contact first." to true
      return false
    }

    val result =
      WhatsAppDispatcher.openWhatsAppChat(
        context = getApplication(),
        phoneNumber = contact.phoneNumber,
        preferredFlavor = prefs.preferredFlavor,
      )

    return when (result) {
      is DispatchResult.OpenedChat -> {
        val status =
          if (result.usedFallbackBrowser) "Opened via wa.me link"
          else "Opened ${contact.displayName}"
        logAction(
          type = PingActionType.OPEN_CHAT,
          contactName = contact.displayName,
          durationSeconds = null,
          statusLabel = status,
        )
        bannerState.value = status to false
        true
      }
      is DispatchResult.Error -> {
        EyesFreeFeedbackManager.playErrorCue(getApplication(), prefs.eyesFreeAudioCues)
        bannerState.value = result.message to true
        false
      }
      else -> false
    }
  }

  /**
   * Starts a 2.2s dynamic assistant window:
   * - Listens silently via mic (capturing audio immediately so first syllables are never lost).
   * - If speech energy rises OR the user holds the pill -> seamlessly locks into Recording and auto-sends on trailing silence or release.
   * - If 2.2s elapses silently without holding -> cancels temp audio and opens the WhatsApp chat directly.
   */
  fun startDynamicAssistantSession(
    onFinished: () -> Unit,
  ) {
    dynamicSessionJob?.cancel()
    dynamicHoldLocked = false

    val app = getApplication<Application>()
    val prefs = runBlockingPrefs(app)
    val hasMicPermission =
      ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    if (!hasMicPermission) {
      openPinnedWhatsAppChat()
      onFinished()
      return
    }

    val startResult = recorder.startRecording()
    if (startResult.isFailure) {
      openPinnedWhatsAppChat()
      onFinished()
      return
    }

    dynamicSessionJob =
      viewModelScope.launch {
        val windowMs = 2_200L
        val tickMs = 65L
        val speechThreshold = 1_850
        val silenceThreshold = 1_250
        val maxTrailingSilenceMs = 1_550L

        var speechHits = 0
        var lockedIntoVoice = false
        var startedRecordingPhaseAt = 0L
        var silenceAccumulatedMs = 0L
        val rolling = MutableList(24) { 0.12f }

        while (isActive && recorder.isRecording) {
          val rawAmp = recorder.sampleRawMaxAmplitude()
          val normAmp = (rawAmp / 22_000f).coerceIn(0.08f, 1f)
          val elapsed = recorder.elapsedMillis()

          if (!lockedIntoVoice) {
            if (rawAmp >= speechThreshold) {
              speechHits++
            } else {
              speechHits = (speechHits - 1).coerceAtLeast(0)
            }

            if (dynamicHoldLocked || speechHits >= 2) {
              lockedIntoVoice = true
              startedRecordingPhaseAt = elapsed
              silenceAccumulatedMs = 0L
              EyesFreeFeedbackManager.playStartCue(app, prefs.eyesFreeAudioCues)
              recordingState.value =
                RecordingUiState.Recording(
                  elapsedMs = elapsed,
                  amplitudes = rolling.toList(),
                  isLockedHandsFree = !dynamicHoldLocked,
                )
            } else if (elapsed >= windowMs) {
              recorder.cancelRecording()
              recordingState.value = RecordingUiState.Idle
              openPinnedWhatsAppChat()
              onFinished()
              return@launch
            } else {
              recordingState.value =
                RecordingUiState.ListeningWindow(
                  elapsedMs = elapsed,
                  windowMs = windowMs,
                  speechEnergy = (rawAmp / 6_000f).coerceIn(0f, 1f),
                )
            }
          } else {
            rolling.removeAt(0)
            rolling.add(normAmp)

            val recordingDuration = elapsed - startedRecordingPhaseAt
            if (!dynamicHoldLocked) {
              if (rawAmp < silenceThreshold) {
                silenceAccumulatedMs += tickMs
              } else {
                silenceAccumulatedMs = 0L
              }
              if (recordingDuration >= 1_200L && silenceAccumulatedMs >= maxTrailingSilenceMs) {
                stopAndSendRecording { onFinished() }
                return@launch
              }
            }

            recordingState.value =
              RecordingUiState.Recording(
                elapsedMs = elapsed,
                amplitudes = rolling.toList(),
                isLockedHandsFree = !dynamicHoldLocked,
              )
          }

          delay(tickMs)
        }
      }
  }

  fun lockDynamicSessionByHold() {
    dynamicHoldLocked = true
  }

  fun skipDynamicWindowAndOpenChat(onFinished: () -> Unit) {
    dynamicSessionJob?.cancel()
    dynamicSessionJob = null
    recorder.cancelRecording()
    recordingState.value = RecordingUiState.Idle
    openPinnedWhatsAppChat()
    onFinished()
  }

  fun startRecording(lockedHandsFree: Boolean = false): Boolean {
    dynamicSessionJob?.cancel()
    dynamicSessionJob = null
    if (recorder.isRecording) return true
    if (stopDispatchJob?.isActive == true) return false

    val app = getApplication<Application>()
    val prefs = uiState.value.preferences

    val hasMicPermission =
      ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasMicPermission) {
      EyesFreeFeedbackManager.playErrorCue(app, prefs.eyesFreeAudioCues)
      bannerState.value = "Microphone permission is required to record voice notes." to true
      recordingState.value = RecordingUiState.Idle
      return false
    }

    val contact = uiState.value.effectiveRoute.contact ?: prefs.pinnedContact
    if (contact == null) {
      EyesFreeFeedbackManager.playErrorCue(app, prefs.eyesFreeAudioCues)
      bannerState.value = "Pin a WhatsApp contact before recording." to true
      return false
    }

    val startResult = recorder.startRecording()
    if (startResult.isFailure) {
      EyesFreeFeedbackManager.playErrorCue(app, prefs.eyesFreeAudioCues)
      bannerState.value =
        "Microphone unavailable: ${startResult.exceptionOrNull()?.localizedMessage ?: "Hardware error"}" to
          true
      recordingState.value = RecordingUiState.Idle
      return false
    }

    EyesFreeFeedbackManager.playStartCue(app, prefs.eyesFreeAudioCues)

    val initialBars = List(24) { 0.14f }
    recordingState.value =
      RecordingUiState.Recording(
        elapsedMs = 0L,
        amplitudes = initialBars,
        isLockedHandsFree = lockedHandsFree,
      )

    amplitudeJob?.cancel()
    amplitudeJob =
      viewModelScope.launch {
        val rolling = initialBars.toMutableList()
        while (isActive && recorder.isRecording) {
          val amp = recorder.sampleNormalizedAmplitude()
          rolling.removeAt(0)
          rolling.add(amp)
          val current = recordingState.value
          val locked = (current as? RecordingUiState.Recording)?.isLockedHandsFree ?: lockedHandsFree
          recordingState.value =
            RecordingUiState.Recording(
              elapsedMs = recorder.elapsedMillis(),
              amplitudes = rolling.toList(),
              isLockedHandsFree = locked,
            )
          delay(65L)
        }
      }
    return true
  }

  fun cancelRecording() {
    dynamicSessionJob?.cancel()
    dynamicSessionJob = null
    stopDispatchJob?.cancel()
    stopDispatchJob = null
    amplitudeJob?.cancel()
    amplitudeJob = null
    recorder.cancelRecording()
    recordingState.value = RecordingUiState.Idle
    bannerState.value = "Voice recording cancelled." to false
  }

  fun stopAndSendRecording(onComplete: (Boolean) -> Unit = {}) {
    dynamicSessionJob?.cancel()
    dynamicSessionJob = null
    if (!recorder.isRecording) {
      recordingState.value = RecordingUiState.Idle
      onComplete(false)
      return
    }
    if (stopDispatchJob?.isActive == true) return

    stopDispatchJob =
      viewModelScope.launch {
        val remainingMs = recorder.remainingMinDurationMillis(minDurationMs = 650L)
        recordingState.value = RecordingUiState.Sending
        if (remainingMs > 0L) {
          delay(remainingMs)
        }

        val elapsedSec = ((recorder.elapsedMillis() / 1000L).toInt()).coerceAtLeast(1)
        amplitudeJob?.cancel()
        amplitudeJob = null

        val prefs = uiState.value.preferences
        val recordedAudio = recorder.stopRecording()
        if (recordedAudio == null) {
          recordingState.value = RecordingUiState.Idle
          EyesFreeFeedbackManager.playErrorCue(getApplication(), prefs.eyesFreeAudioCues)
          bannerState.value = "Recording failed to save audio." to true
          onComplete(false)
          return@launch
        }

        val contact = uiState.value.effectiveRoute.contact ?: prefs.pinnedContact
        if (contact == null) {
          recordedAudio.file.delete()
          recordingState.value = RecordingUiState.Idle
          EyesFreeFeedbackManager.playErrorCue(getApplication(), prefs.eyesFreeAudioCues)
          bannerState.value = "No target contact pinned." to true
          onComplete(false)
          return@launch
        }

        val autoSendEnabled = PingAutoSendAccessibilityService.isEnabled(getApplication())

        val dispatchResult =
          WhatsAppDispatcher.sendWhatsAppAudio(
            context = getApplication(),
            phoneNumber = contact.phoneNumber,
            audioFile = recordedAudio.file,
            preferredFlavor = prefs.preferredFlavor,
            isAutoSendServiceEnabled = autoSendEnabled,
            boomerangReturn = prefs.boomerangReturnEnabled,
            mimeType = recordedAudio.mimeType,
          )

        recordingState.value = RecordingUiState.Idle

        val success =
          when (dispatchResult) {
            is DispatchResult.AudioDispatched -> {
              if (!dispatchResult.autoSendArmed) {
                EyesFreeFeedbackManager.playSentCue(getApplication(), prefs.eyesFreeAudioCues)
              }
              val status =
                when {
                  dispatchResult.autoSendArmed -> "Auto-sending ${elapsedSec}s audio"
                  dispatchResult.usedChooserFallback -> "Choose app to send ${elapsedSec}s audio"
                  else -> "Prepared ${elapsedSec}s audio in chat"
                }
              logAction(
                type = PingActionType.AUDIO_SENT,
                contactName = contact.displayName,
                durationSeconds = elapsedSec,
                statusLabel = status,
              )
              bannerState.value = status to false
              true
            }
            is DispatchResult.Error -> {
              EyesFreeFeedbackManager.playErrorCue(getApplication(), prefs.eyesFreeAudioCues)
              bannerState.value = dispatchResult.message to true
              false
            }
            else -> false
          }
        onComplete(success)
      }
  }

  private fun runBlockingPrefs(context: Context): AssistantPreferences {
    return kotlinx.coroutines.runBlocking { repository.getCurrentPreferences() }
  }

  private fun logAction(
    type: PingActionType,
    contactName: String,
    durationSeconds: Int?,
    statusLabel: String,
  ) {
    viewModelScope.launch {
      repository.appendActionLog(
        PingActionLog(
          id = UUID.randomUUID().toString(),
          type = type,
          contactName = contactName,
          timestampMillis = System.currentTimeMillis(),
          durationSeconds = durationSeconds,
          statusLabel = statusLabel,
        )
      )
    }
  }

  private fun readSystemState(context: Context): SystemIntegrationState {
    return SystemIntegrationState(
      isDefaultAssistant = AssistantRoleHelper.isDefaultAssistant(context),
      isAutoSendEnabled = PingAutoSendAccessibilityService.isEnabled(context),
      installedWhatsApp = WhatsAppDispatcher.detectInstalledFlavors(context),
    )
  }

  override fun onCleared() {
    val audioManager =
      getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    audioCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
    dynamicSessionJob?.cancel()
    stopDispatchJob?.cancel()
    amplitudeJob?.cancel()
    recorder.release()
    super.onCleared()
  }
}
