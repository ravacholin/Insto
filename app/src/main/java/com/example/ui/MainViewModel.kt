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
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.speech.GroqTranscriber
import com.example.speech.GroqKeyStore
import com.example.speech.TranscriptionException
import com.example.assist.AssistantRoleHelper
import com.example.assist.HotkeyHoldTracker
import com.example.assist.PingAssistWidgetProvider
import com.example.assist.PingAutoSendAccessibilityService
import com.example.assist.ShortcutHelper
import com.example.audio.EyesFreeFeedbackManager
import com.example.audio.FloatingAudioCapture
import com.example.audio.FloatingVoiceCapture
import com.example.audio.RecordedAudio
import com.example.audio.VoiceSessionPolicy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RecordingUiState {
  data object Idle : RecordingUiState

  /** Hotkey is down but not long enough yet to know whether it is a tap or a hold. */
  data object HoldPending : RecordingUiState

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
      activationMode = AssistantActivationMode.TAP_CHAT_HOLD_AUDIO,
      activeContextLabel = null,
      isCarOrBluetoothActive = false,
      connectedAudioDeviceName = null,
    ),
  val recordingState: RecordingUiState = RecordingUiState.Idle,
  val systemState: SystemIntegrationState = SystemIntegrationState(),
  val statusBanner: String? = null,
  val isBannerError: Boolean = false,
)

enum class FloatingMode { IDLE, LISTENING, RECORDING, TYPING, SENDING, AUDIO_READY }

data class FloatingAttachment(val uri: android.net.Uri, val name: String, val mimeType: String)

data class FloatingAssistantState(
  val mode: FloatingMode = FloatingMode.IDLE,
  val text: String = "",
  val contactName: String = "Instinct",
  val attachment: FloatingAttachment? = null,
  val elapsedMs: Long = 0,
  val amplitude: Float = 0f,
  val error: String? = null,
  val status: String? = null,
  val transcript: String? = null,
  val needsGroqKey: Boolean = false,
  val needsCamera: Boolean = false,
  val needsContacts: Boolean = false,
  val contactOptions: List<ContactOption> = emptyList(),
  val needsConfirmation: Boolean = false,
  val needsWhatsAppAccessibility: Boolean = false,
  val appOptions: List<AppOption> = emptyList(),
  val phoneActionCompletion: String? = null,
  val deciding: Boolean = false,
)

class MainViewModel @JvmOverloads constructor(
  application: Application,
  private val floatingCapture: FloatingAudioCapture = FloatingVoiceCapture(application),
  private val audioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
  private val audioTranscriber: AudioTranscriber = GroqTranscriber({ GroqKeyStore(application).read() }),
  private val hybridRouter: com.example.phone.HybridPhoneRouter = com.example.phone.HybridPhoneRouter(
    { com.example.phone.GeminiSettings(application).isEnabled() },
    com.example.phone.GeminiDecisionsClient({ com.example.phone.GeminiSettings(application).readKey() })),
) : AndroidViewModel(application) {

  private val repository = AssistantPreferencesRepository(application)
  private val recorder = VoiceRecorderManager(application)
  private val floatingState = MutableStateFlow(FloatingAssistantState())
  val floatingAssistant: StateFlow<FloatingAssistantState> = floatingState
  private var floatingJob: Job? = null
  private var floatingGeneration = 0L
  private var floatingReturnPackage: String? = null
  private var floatingPrefs: AssistantPreferences? = null
  private var floatingContact: com.example.data.PinnedContact? = null
  private var pendingFloatingAudio: RecordedAudio? = null
  private var pendingTranscript: String? = null
  private var transcribingAudio = false
  private var decidingRequest = false
  private var floatingPaused = false

  fun resumeFloatingAssistant() { floatingPaused = false }
  private val phoneParser = PhoneCommandParser()
  private val phoneExecutor = PhoneActionExecutor(application)
  private var clarification: String? = null
  private var confirmation: PhoneCommand? = null
  private var cameraCommand: PhoneCommand? = null
  private var contactsCommand: PhoneCommand? = null
  private var retainLocalAudio = false
  private var routedRequestText = ""
  private var retryLocalCommand: Pair<PhoneCommand, Boolean>? = null
  private var retryLocalText: String? = null

  fun consumePhoneActionCompletion(): String? {
    val message = floatingState.value.phoneActionCompletion ?: return null
    floatingState.value = floatingState.value.copy(phoneActionCompletion = null)
    return message
  }

  fun confirmFloatingAction(accept: Boolean) {
    val command = confirmation ?: return
    confirmation = null
    floatingState.value = floatingState.value.copy(needsConfirmation = false)
    if (accept) runPhoneCommand(command, confirmed = true)
    else floatingState.value = floatingState.value.copy(status = "Pedido cancelado.")
  }

  fun chooseFloatingApp(option: AppOption) {
    if (option !in floatingState.value.appOptions || floatingState.value.mode == FloatingMode.SENDING) return
    floatingState.value = floatingState.value.copy(appOptions = emptyList())
    runPhoneCommand(PhoneCommand.OpenApp(option.packageName))
  }

  fun onFloatingCameraPermission(granted: Boolean) {
    val command = cameraCommand ?: return
    cameraCommand = null
    floatingState.value = floatingState.value.copy(needsCamera = false)
    if (granted) runPhoneCommand(command)
    else floatingState.value = floatingState.value.copy(error = "No se habilitó la cámara. El pedido se conserva; la linterna no se cambió.", status = null)
  }

  fun onFloatingContactsPermission(granted: Boolean) {
    val command = contactsCommand ?: return
    contactsCommand = null
    floatingState.value = floatingState.value.copy(needsContacts = false)
    if (granted) runPhoneCommand(command)
    else floatingState.value = floatingState.value.copy(error = "No se habilitaron los contactos. El pedido se conserva; podés indicar el número.", status = null)
  }

  fun chooseFloatingContact(option: ContactOption) {
    if (option !in floatingState.value.contactOptions || floatingState.value.mode == FloatingMode.SENDING) return
    floatingState.value = floatingState.value.copy(contactOptions = emptyList())
    runPhoneCommand(PhoneCommand.Dial(option.number))
  }

  private fun runPhoneCommand(command: PhoneCommand, confirmed: Boolean = false) {
    clarification = null
    confirmation = null
    cameraCommand = null
    contactsCommand = null
    val result = phoneExecutor.execute(command, confirmed)
    confirmation = result.confirmation
    if (result.needsCamera) cameraCommand = command
    if (result.needsContacts) contactsCommand = command
    retainLocalAudio = result.retainRequest
    retryLocalCommand = if (result.retainRequest) command to confirmed else null
    retryLocalText = if (result.retainRequest) routedRequestText else null
    val nextMode = if (result.retainRequest && pendingFloatingAudio != null) FloatingMode.AUDIO_READY
      else if (result.retainRequest && floatingState.value.text.isNotBlank()) FloatingMode.TYPING else FloatingMode.IDLE
    floatingState.value = floatingState.value.copy(mode = nextMode,
      text = if (result.retainRequest) floatingState.value.text else "",
      error = if (result.outcome == PhoneActionOutcome.REJECTED) result.message else null,
      status = if (result.outcome == PhoneActionOutcome.REJECTED) null else result.message, needsWhatsAppAccessibility = false, needsConfirmation = result.confirmation != null,
      appOptions = result.appOptions, needsCamera = result.needsCamera, needsGroqKey = false,
      needsContacts = result.needsContacts, contactOptions = result.contactOptions,
      phoneActionCompletion = result.message.takeIf {
        result.outcome == PhoneActionOutcome.COMPLETED || result.outcome == PhoneActionOutcome.HANDOFF
      })
    if (!result.retainRequest) {
      pendingFloatingAudio?.file?.delete()
      pendingFloatingAudio = null
      pendingTranscript = null
    }
  }

  /** Returns false only when this is a general query for Instinct. */
  private suspend fun routeFloatingRequest(text: String): Boolean {
    retainLocalAudio = false
    val normalized = PhoneCommandParser.normalize(text)
    val retry = retryLocalCommand
    if (retry != null && retryLocalText?.let(PhoneCommandParser::normalize) == normalized) {
      runPhoneCommand(retry.first, retry.second)
      return true
    }
    retryLocalCommand = null
    retryLocalText = null
    routedRequestText = text
    if (confirmation != null && normalized in listOf("si", "si confirmo", "confirmo", "dale", "acepto", "no", "cancelar")) {
      confirmFloatingAction(normalized !in listOf("no", "cancelar"))
      return true
    }
    val contacts = floatingState.value.contactOptions
    if (contacts.isNotEmpty()) {
      val index = PhoneCommandParser.number(normalized)?.minus(1)
      val selected = if (index != null) contacts.getOrNull(index) else contacts.singleOrNull {
        PhoneCommandParser.normalize(it.name) == normalized ||
          PhoneNumbers.canonical(text)?.let { number -> number == PhoneNumbers.canonical(it.number) } == true
      }
      if (selected != null) { chooseFloatingContact(selected); return true }
      if (normalized != "no" && phoneParser.parse(text) == PhoneRoute.Instinct) {
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(
          mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
          error = "Elegí una opción por su número, o decí cancelar.", status = null)
        return true
      }
    }
    val options = floatingState.value.appOptions
    if (options.isNotEmpty()) {
      val index = PhoneCommandParser.number(normalized)?.minus(1)
      val selected = if (index != null) options.getOrNull(index) else options.singleOrNull { PhoneCommandParser.normalize(it.label) == normalized }
      if (selected != null) { runPhoneCommand(PhoneCommand.OpenApp(selected.packageName)); return true }
    }
    val pendingContext = clarification
    val local = if (contacts.isNotEmpty() && normalized == "no") PhoneRoute.Cancel
      else clarification?.let { phoneParser.parseReply(it, text) } ?: phoneParser.parse(text)
    // Unresolved choices/confirmations stay local; never reinterpret "two" or "maybe" as a fresh request.
    if ((options.isNotEmpty() || confirmation != null) && local == PhoneRoute.Instinct) {
      retainLocalAudio = true
      floatingState.value = floatingState.value.copy(
        mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
        error = "Elegí una opción, confirmá el pedido o decí cancelar.", status = null)
      return true
    }
    val generation = floatingGeneration
    val route = if (pendingContext == null && hybridRouter.needsDecision(text, local)) {
      decidingRequest = true
      floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, deciding = true, status = "Decidiendo con Gemini…", error = null)
      try {
        val result = hybridRouter.route(text, local)
        if (generation != floatingGeneration || !kotlinx.coroutines.currentCoroutineContext().isActive || floatingPaused) return true
        result
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (generation == floatingGeneration) retainDecisionRequest("La interpretación se interrumpió. El pedido se conserva para reintentar.")
        return true
      } catch (failure: Exception) {
        if (generation == floatingGeneration) retainDecisionRequest(
          (failure as? com.example.phone.DecisionException)?.message ?: "No se pudo decidir con Gemini. El pedido se conserva para reintentar.")
        return true
      } finally {
        if (generation == floatingGeneration) {
          decidingRequest = false
          floatingState.value = floatingState.value.copy(deciding = false)
        }
      }
    } else local
    // A new complete request replaces any earlier clarification/confirmation.
    clarification = null
    confirmation = null
    cameraCommand = null
    contactsCommand = null
    retryLocalCommand = null
    retryLocalText = null
    floatingState.value = floatingState.value.copy(needsConfirmation = false, appOptions = emptyList(), needsCamera = false, needsContacts = false, contactOptions = emptyList())
    when (route) {
      is PhoneRoute.Local -> runPhoneCommand(route.command)
      is PhoneRoute.Clarify -> {
        clarification = route.context
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", status = route.prompt, error = null)
      }
      is PhoneRoute.Unsupported -> {
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING, status = null, error = route.message)
      }
      PhoneRoute.Cancel -> floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", status = "Pedido cancelado.", error = null)
      PhoneRoute.Instinct -> return false
    }
    return true
  }

  private fun retainDecisionRequest(message: String) {
    retainLocalAudio = true
    floatingState.value = floatingState.value.copy(
      mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
      error = message, status = null)
  }

  fun prepareFloatingAssistant() {
    val initialized = floatingPrefs != null
    val prefs = runBlockingPrefs(getApplication())
    floatingPrefs = prefs
    floatingReturnPackage = com.example.assist.AutoSendCoordinator.lastKnownForegroundPackage
    floatingContact = ContextRoutingEngine.resolveRoute(getApplication(), prefs).contact ?: prefs.pinnedContact
    floatingState.value = if (initialized) floatingState.value.copy(contactName = floatingContact?.displayName ?: "Instinct")
      else FloatingAssistantState(contactName = floatingContact?.displayName ?: "Instinct")
  }

  fun startFloatingListening(onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    if (floatingState.value.mode == FloatingMode.SENDING || floatingState.value.attachment != null) return
    floatingGeneration++
    stopFloatingCapture(discard = true)
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    val result = floatingCapture.start()
    if (result.isFailure) {
      floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE,
        error = "Micrófono no disponible. Podés escribir o volver a intentarlo.")
      return
    }
    floatingState.value = floatingState.value.copy(mode = FloatingMode.LISTENING, text = "", elapsedMs = 0, error = null, needsGroqKey = false, transcript = null, needsWhatsAppAccessibility = false)
    floatingJob = viewModelScope.launch {
      while (isActive) {
        val sample = floatingCapture.snapshot
        if (sample.error != null) {
          pauseFloatingAssistant(sample.error)
          return@launch
        }
        when (sample.state) {
          VoiceSessionPolicy.State.IDLE -> {
            stopFloatingCapture(discard = true)
            floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, amplitude = 0f)
            return@launch
          }
          VoiceSessionPolicy.State.SEND -> {
            sendFloatingAudio(onDispatched)
            return@launch
          }
          else -> floatingState.value = floatingState.value.copy(
            mode = if (sample.state == VoiceSessionPolicy.State.RECORDING) FloatingMode.RECORDING else FloatingMode.LISTENING,
            elapsedMs = sample.elapsedMs, amplitude = sample.amplitude)
        }
        delay(40)
      }
    }
  }

  /** Called on focus, before the keyboard appears, not just after the first character. */
  fun enterFloatingTextMode() {
    if (floatingState.value.mode == FloatingMode.SENDING && !decidingRequest && !transcribingAudio) return
    floatingGeneration++
    decidingRequest = false
    transcribingAudio = false
    stopFloatingCapture(discard = true)
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    floatingState.value = floatingState.value.copy(mode = FloatingMode.TYPING, deciding = false, amplitude = 0f, error = null, status = null, needsWhatsAppAccessibility = false)
  }

  fun updateFloatingText(text: String) {
    if (floatingState.value.mode == FloatingMode.SENDING && !decidingRequest && !transcribingAudio) return
    if (floatingState.value.mode != FloatingMode.TYPING) enterFloatingTextMode()
    floatingState.value = floatingState.value.copy(text = text)
  }

  fun selectFloatingAttachment(uri: android.net.Uri) {
    enterFloatingTextMode()
    runCatching {
      val resolver = getApplication<Application>().contentResolver
      require(uri.scheme == "content")
      val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      } ?: "Adjunto"
      resolver.openAssetFileDescriptor(uri, "r")?.use { } ?: error("Archivo no disponible")
      FloatingAttachment(uri, name, resolver.getType(uri) ?: "application/octet-stream")
    }.onSuccess {
      floatingState.value = floatingState.value.copy(attachment = it, error = null)
    }.onFailure {
      floatingState.value = floatingState.value.copy(error = "No se pudo abrir el archivo. Elegí otro adjunto.")
    }
  }

  fun removeFloatingAttachment() {
    if (floatingState.value.mode != FloatingMode.SENDING)
      floatingState.value = floatingState.value.copy(attachment = null)
  }

  fun sendFloatingAttachment(onDispatched: () -> Unit) {
    if (floatingState.value.mode == FloatingMode.SENDING) return
    val attachment = floatingState.value.attachment ?: return
    prepareFloatingAssistant()
    val contact = floatingContact
    val prefs = floatingPrefs
    if (contact == null || prefs == null) {
      floatingState.value = floatingState.value.copy(error = "Configurá el contacto de Instinct para enviar el adjunto.")
      return
    }
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null)
    val result = WhatsAppDispatcher.sendWhatsAppAttachment(getApplication(), contact.phoneNumber,
      attachment.uri, attachment.name, attachment.mimeType, floatingState.value.text,
      prefs.preferredFlavor, contact.displayName, floatingReturnPackage)
    if (result is DispatchResult.Error) {
      floatingState.value = floatingState.value.copy(mode = FloatingMode.TYPING, error = result.message, needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else onDispatched()
  }

  private fun stopFloatingCapture(discard: Boolean) {
    check(discard) { "Use asynchronous finalization when retaining audio" }
    floatingJob?.cancel()
    floatingJob = null
    floatingCapture.cancel()
  }

  private suspend fun finishFloatingCapture(): RecordedAudio? {
    var audio: RecordedAudio? = null
    try {
      return withContext(audioDispatcher) { floatingCapture.finish().also { audio = it } }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      audio?.file?.delete()
      throw cancelled
    }
  }

  fun pauseFloatingAssistant(error: String? = null) {
    floatingPaused = true
    if (transcribingAudio || decidingRequest) {
      floatingGeneration++
      floatingJob?.cancel()
      floatingJob = null
      transcribingAudio = false
      decidingRequest = false
      floatingState.value = floatingState.value.copy(
        mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
        deciding = false, status = "Pedido guardado. Podés reintentar cuando vuelvas.")
      return
    }
    if (floatingState.value.mode != FloatingMode.LISTENING && floatingState.value.mode != FloatingMode.RECORDING) return
    floatingJob?.cancel()
    floatingJob = null
    val generation = ++floatingGeneration
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, amplitude = 0f)
    viewModelScope.launch {
      val audio = finishFloatingCapture()
      if (generation != floatingGeneration) { audio?.file?.delete(); return@launch }
      pendingFloatingAudio = audio
      floatingState.value = floatingState.value.copy(
        mode = if (audio != null) FloatingMode.AUDIO_READY else FloatingMode.IDLE,
        error = error ?: floatingCapture.snapshot.error)
    }
  }

  fun dismissFloatingAssistant() {
    floatingGeneration++
    transcribingAudio = false
    decidingRequest = false
    stopFloatingCapture(discard = true)
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    clarification = null
    confirmation = null
    cameraCommand = null
    contactsCommand = null
    floatingState.value = floatingState.value.copy(deciding = false, needsConfirmation = false, needsCamera = false, appOptions = emptyList(), needsContacts = false, contactOptions = emptyList(), phoneActionCompletion = null)
  }

  fun sendFloatingAudio(onDispatched: () -> Unit) {
    if (floatingState.value.mode !in listOf(FloatingMode.RECORDING, FloatingMode.AUDIO_READY)) return
    floatingJob?.cancel()
    floatingJob = null
    val generation = ++floatingGeneration
    floatingPaused = false
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = "Interpretando tu audio…", needsGroqKey = false)
    floatingJob = viewModelScope.launch {
      val audio = pendingFloatingAudio ?: finishFloatingCapture()
      if (generation != floatingGeneration) { audio?.file?.delete(); return@launch }
      if (audio == null) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE,
          error = floatingCapture.snapshot.error ?: "No se pudo finalizar el audio. Activá el micrófono para volver a grabar.")
        return@launch
      }
      pendingFloatingAudio = audio
      if (floatingPaused) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY, status = "Audio guardado.")
        return@launch
      }
      transcribingAudio = true
      val transcript = try {
        pendingTranscript ?: audioTranscriber.transcribe(audio.file)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (generation == floatingGeneration) transcribingAudio = false
        if (generation == floatingGeneration) floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY,
          error = "La interpretación se interrumpió. El audio se conserva para reintentar.", status = null)
        return@launch
      } catch (failure: Exception) {
        if (generation == floatingGeneration) transcribingAudio = false
        if (generation == floatingGeneration) floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY,
          status = null, error = (failure as? TranscriptionException)?.message ?: "No se pudo interpretar. El audio se conserva.",
          needsGroqKey = (failure as? TranscriptionException)?.needsKey == true)
        return@launch
      }
      if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
      transcribingAudio = false
      pendingTranscript = transcript
      floatingState.value = floatingState.value.copy(transcript = transcript)
      if (routeFloatingRequest(transcript)) {
        if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
        if (!retainLocalAudio) {
          audio.file.delete()
          pendingFloatingAudio = null
          pendingTranscript = null
        }
        return@launch
      }
      prepareFloatingAssistant()
      val contact = floatingContact
      val prefs = floatingPrefs
      if (contact == null || prefs == null) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY,
          error = "Configurá el contacto de WhatsApp antes de enviar.")
        return@launch
      }
      if (WhatsAppDispatcher.resolveTargetPackage(getApplication(), prefs.preferredFlavor) == null) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY,
          error = "Instalá WhatsApp para enviar. El audio se conserva.")
        return@launch
      }
      floatingState.value = floatingState.value.copy(status = "Enviando el audio original a Instinct…")
      val result = WhatsAppDispatcher.sendWhatsAppAudio(
        getApplication(), contact.phoneNumber, audio.file, prefs.preferredFlavor,
        PingAutoSendAccessibilityService.isEnabled(getApplication()), boomerangReturn = true,
        mimeType = audio.mimeType, recipientName = contact.displayName, returnPackage = floatingReturnPackage)
      if (result is DispatchResult.Error) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY, error = result.message, needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
      } else {
        pendingFloatingAudio = null // FileProvider/WhatsApp owns the shared file from here.
        onDispatched()
      }
    }
  }

  fun sendFloatingText(onDispatched: () -> Unit) {
    if (floatingState.value.mode == FloatingMode.SENDING || floatingState.value.text.isBlank()) return
    stopFloatingCapture(discard = true)
    val text = floatingState.value.text
    val generation = ++floatingGeneration
    floatingPaused = false
    floatingJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
      if (routeFloatingRequest(text)) return@launch
      if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
      prepareFloatingAssistant()
      val contact = floatingContact
      val prefs = floatingPrefs
      if (contact == null || prefs == null) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.TYPING, error = "Configurá el contacto de Instinct para enviar esta consulta.")
        return@launch
      }
      floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = "Enviando a Instinct…")
      val result = WhatsAppDispatcher.sendWhatsAppText(getApplication(), contact.phoneNumber,
        text, prefs.preferredFlavor,
        PingAutoSendAccessibilityService.isEnabled(getApplication()), contact.displayName, floatingReturnPackage)
      if (result is DispatchResult.Error) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.TYPING, error = result.message, needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
      } else onDispatched()
    }
  }



  private val recordingState = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
  private val systemState = MutableStateFlow(readSystemState(application))
  private val contextRefreshTick = MutableStateFlow(0L)
  private val bannerState = MutableStateFlow<Pair<String?, Boolean>>(null to false)

  private var amplitudeJob: Job? = null
  private var stopDispatchJob: Job? = null
  private var dynamicSessionJob: Job? = null
  private var holdSessionJob: Job? = null
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

  /**
   * @param freshPrefs pass freshly loaded preferences when calling right after launch, before
   *   [uiState] has had time to emit real data.
   */
  fun openPinnedWhatsAppChat(freshPrefs: AssistantPreferences? = null): Boolean {
    val prefs = freshPrefs ?: uiState.value.preferences
    val contact =
      if (freshPrefs != null) {
        ContextRoutingEngine.resolveRoute(getApplication(), freshPrefs).contact
      } else {
        uiState.value.effectiveRoute.contact
      } ?: prefs.pinnedContact
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

  /**
   * Tap-vs-hold session for the assistant hotkey. The mic starts immediately so no syllable is
   * lost, then:
   * - key released within [HOLD_GRACE_MS] -> it was a tap: discard audio and open the chat.
   * - key still down after that -> it is a hold: keep recording and send when the key is released.
   */
  fun startHoldToTalkSession(heldKey: HotkeyHoldTracker.HeldKey, onFinished: () -> Unit) {
    if (holdSessionJob?.isActive == true || stopDispatchJob?.isActive == true) return
    dynamicSessionJob?.cancel()
    dynamicSessionJob = null

    val app = getApplication<Application>()
    val prefs = runBlockingPrefs(app)

    if (recorder.startRecording().isFailure) {
      openPinnedWhatsAppChat(prefs)
      onFinished()
      return
    }
    recordingState.value = RecordingUiState.HoldPending

    holdSessionJob =
      viewModelScope.launch {
        val releasedAsTap =
          withTimeoutOrNull(HOLD_GRACE_MS) { HotkeyHoldTracker.awaitRelease(heldKey) } != null
        if (releasedAsTap) {
          recorder.cancelRecording()
          recordingState.value = RecordingUiState.Idle
          openPinnedWhatsAppChat(prefs)
          onFinished()
          return@launch
        }

        EyesFreeFeedbackManager.playStartCue(app, prefs.eyesFreeAudioCues)
        launchAmplitudeUpdates(lockedHandsFree = false)
        HotkeyHoldTracker.awaitRelease(heldKey)
        holdSessionJob = null
        stopAndSendRecording { onFinished() }
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

    launchAmplitudeUpdates(lockedHandsFree)
    return true
  }

  private fun launchAmplitudeUpdates(lockedHandsFree: Boolean) {
    val initialBars = List(24) { 0.14f }
    recordingState.value =
      RecordingUiState.Recording(
        elapsedMs = recorder.elapsedMillis(),
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
  }

  fun cancelRecording() {
    holdSessionJob?.cancel()
    holdSessionJob = null
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
    // Detach (don't cancel mid-flight): the hold session itself may be the caller.
    holdSessionJob.also { holdSessionJob = null }?.cancel()
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
    holdSessionJob?.cancel()
    dynamicSessionJob?.cancel()
    stopDispatchJob?.cancel()
    amplitudeJob?.cancel()
    recorder.release()
    dismissFloatingAssistant()
    super.onCleared()
  }

  companion object {
    /** How long the hotkey must stay down, after the assistant fires, to count as a hold. */
    const val HOLD_GRACE_MS = 350L
  }
}
