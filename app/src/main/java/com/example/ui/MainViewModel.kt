package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.speech.GroqKeyStore
import com.example.speech.GroqTranscriber
import com.example.speech.TranscriptionException
import com.example.speech.VocabularyHintProvider
import com.example.assist.AssistantRoleHelper
import com.example.assist.HotkeyHoldTracker
import com.example.assist.PingAssistWidgetProvider
import com.example.assist.PingAutoSendAccessibilityService
import com.example.assist.ShortcutHelper
import com.example.audio.EyesFreeFeedbackManager
import com.example.audio.FloatingAudioCapture
import com.example.audio.FloatingVoiceCapture
import com.example.audio.RecordedAudio
import com.example.audio.VoiceLatency
import com.example.audio.VoiceSessionPolicy
import com.example.audio.VoiceRecorderManager
import com.example.context.SharedTextContext
import com.example.context.ContextRoutingEngine
import com.example.context.EffectiveContextRoute
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTeaching
import com.example.data.PhraseVoiceCommand
import com.example.data.PhraseVoiceCommands
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
import kotlinx.coroutines.flow.first
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
  val sourceContext: SharedTextContext? = null,
  val voiceLatency: VoiceLatency? = null,
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
  val needsCalendarRead: Boolean = false,
  val needsNotifications: Boolean = false,
  val needsClockAccessibility: Boolean = false,
  val geminiConfigured: Boolean = false,
  val geminiEnabled: Boolean = false,
  val contactOptions: List<ContactOption> = emptyList(),
  val needsConfirmation: Boolean = false,
  val needsWhatsAppAccessibility: Boolean = false,
  val appOptions: List<AppOption> = emptyList(),
  val phoneActionCompletion: String? = null,
  val deciding: Boolean = false,
  val queryPreview: Boolean = false,
  val workflowLines: List<String> = emptyList(),
  val workflowCanContinue: Boolean = false,
  val workflowCanRetry: Boolean = false,
  val workflowActive: Boolean = false,
  val accessSettingsAction: String? = null,
  /** Evidence about automatic WhatsApp sending, computed when the panel opens. Null without a contact. */
  val autoSendHealth: com.example.assist.AutoSendHealth.Status? = null,
)

class MainViewModel @JvmOverloads constructor(
  application: Application,
  private val floatingCapture: FloatingAudioCapture = FloatingVoiceCapture(application),
  private val audioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
  audioTranscriber: AudioTranscriber? = null,
  private val hybridRouter: com.example.phone.HybridPhoneRouter = com.example.phone.HybridPhoneRouter(
    { com.example.phone.GeminiSettings(application).isEnabled() },
    com.example.phone.GeminiDecisionsClient({ com.example.phone.GeminiSettings(application).readKey() })),
  private val structuredExtractor: StructuredCommandExtractor = StructuredCommandClient({ GeminiSettings(application).readKey() }),
  private val monotonicClock: () -> Long = android.os.SystemClock::elapsedRealtime,
  groqFallback: StructuredCommandExtractor? = null,
  private val learnedPhrases: LearnedPhraseStore = LearnedPhraseStore(application),
  /** Process-wide and in memory only: the panel closes after each handoff, the next order may refer to it. */
  private val operationalContext: com.example.phone.OperationalContext = com.example.phone.OperationalContext.shared,
  /** SMN temperature and rain queries; process-wide in-memory cache and cooldown. */
  private val weather: com.example.phone.weather.WeatherQueries = com.example.phone.weather.SmnWeatherService.shared,
) : AndroidViewModel(application) {

  private val repository = AssistantPreferencesRepository(application)
  private val vocabularyHints = VocabularyHintProvider(application, learnedPhrases)
  private val audioTranscriber = audioTranscriber ?: GroqTranscriber(
    { GroqKeyStore(application).read() }, prompt = vocabularyHints::cached,
  )
  private val recorder = VoiceRecorderManager(application)
  private val floatingState = MutableStateFlow(FloatingAssistantState())
  val floatingAssistant: StateFlow<FloatingAssistantState> = floatingState
  private var floatingJob: Job? = null
  /** Identity and state of floating requests: a result applies only while its request is current. */
  private val sessions = com.example.session.SessionLifecycle()
  private val floatingGeneration: Long get() = sessions.current
  /** Exactly-once outcome recording and the uncertain-result retry guard. */
  private val executions = com.example.session.ActionExecutionLedger()
  private var cancelClockAutomation: (() -> Unit)? = null
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
  private val phoneExecutor = PhoneActionExecutor(application, preferredMediaPackage = operationalContext::preferredMediaPackage)
  private val conversation = ConversationSession()
  private val groqInterpreter = com.example.phone.GroqIntentVerifier({ GroqKeyStore(application).read() })
  private val geminiCoordinator = GeminiCoordinator(hybridRouter, structuredExtractor,
    verifier = groqInterpreter, fallback = groqFallback ?: groqInterpreter, lookupLearnedPhrase = learnedPhrases::lookup,
    lookupLearnedPattern = learnedPhrases::lookupPattern, operationalContext = operationalContext)
  private var learningPreview: InterpretationRequest? = null
  /** A model interpretation executed without confirmation: an example for learned structures once it succeeds. */
  private var patternExample: Pair<InterpretationRequest, PhoneCommand>? = null
  private var learnedCommandConfirmation = false
  private var semanticDraft: DraftPlan? = null
  private data class QueryPreview(val text: String, val generation: Long)
  private var pendingQuery: QueryPreview? = null
  private var approvedQueryText: String? = null
  private var previewInvalidatedByEdit = false
  private val workflowStore = WorkflowStore(application)
  private var workflowLoadError: String? = null
  private val workflow = WorkflowRunner(runCatching { workflowStore.read() }.getOrElse {
    workflowLoadError = it.message; null
  }, workflowStore::write)
  private fun activeWorkflow() = workflow.snapshot?.takeUnless { it.finished }

  private fun syncWorkflow() {
    val snapshot = workflow.snapshot
    val active = activeWorkflow()
    val step = active?.steps?.getOrNull(active.cursor)
    floatingState.value = floatingState.value.copy(
      workflowLines = snapshot?.steps?.mapIndexed { index, item -> "${index + 1}. ${PhoneCommandCodec.summary(item.command)}${CapabilityRequirements.missing(getApplication(),item.command).takeIf {it.isNotEmpty()}?.joinToString(prefix=" · ",separator=" · ") ?: ""} · ${when(item.status){StepStatus.PENDING->"pendiente";StepStatus.EXECUTING->"en curso";StepStatus.COMPLETED->"completado";StepStatus.HANDED_OFF->"entregado · revisá la app";StepStatus.WAITING_INPUT->"requiere tu respuesta";StepStatus.FAILED->"falló";StepStatus.UNCERTAIN->"resultado incierto · revisá antes de seguir";StepStatus.CANCELLED->"cancelado"}}" } ?: emptyList(),
      workflowActive = active != null,
      needsConfirmation = active?.confirmed == false || confirmation != null,
      workflowCanContinue = step?.status in listOf(StepStatus.HANDED_OFF, StepStatus.UNCERTAIN) || active?.confirmed == true && step?.status == StepStatus.PENDING,
      workflowCanRetry = step?.status in listOf(StepStatus.FAILED, StepStatus.WAITING_INPUT),
    )
  }

  private fun previewWorkflow(commands: List<PhoneCommand>) {
    if (commands.any(WorkflowRunner::isWeatherQuery)) {
      retainDecisionRequest("Las consultas al SMN se hacen de a una, fuera de secuencias. Pedila sola.")
      return
    }
    try {
      if (commands.size > 1) {
        val missing = commands.filterIsInstance<PhoneCommand.OpenApp>().firstOrNull { phoneExecutor.matchingApps(it.name).isEmpty() }
        if (missing != null) {
          retainDecisionRequest("No encontré la aplicación «${missing.name}». Revisá ese paso antes de confirmar el plan.")
          return
        }
      }
      workflow.prepare(commands)
      if (semanticDraft == null && commands.size > 1) semanticDraft = runCatching { DraftPlan.fromCommands(commands) }.getOrNull()
      previewInvalidatedByEdit = false
      floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", error = null,
        status = "Revisá los pasos. Podés corregir el pedido o decir dale para ejecutarlo.", phoneActionCompletion = null)
      retainLocalAudio = true
      syncWorkflow()
    } catch (_: Exception) {
      retainDecisionRequest("No se pudo guardar el plan. No se ejecutó ninguna acción.")
    }
  }

  fun continueFloatingWorkflow() {
    val active = activeWorkflow() ?: return
    val step = active.steps[active.cursor]
    if (step.status == StepStatus.UNCERTAIN) workflow.acknowledgeUncertain() else workflow.resumeHandoff()
    if (workflow.snapshot?.finished == true) finishWorkflow() else advanceWorkflow()
  }

  fun retryFloatingWorkflow() {
    if (activeWorkflow() == null) return
    workflow.retry(); advanceWorkflow()
  }

  fun cancelFloatingWorkflow() {
    learningPreview = null
    patternExample = null
    // Cancelling ends the operational session: the next order cannot lean on what came before.
    operationalContext.clear()
    val hadWorkflow = activeWorkflow() != null
    sessions.cancel(); floatingJob?.cancel()
    cancelClockAutomation?.invoke(); cancelClockAutomation = null
    workflow.cancel(); conversation.clear(); clarification=null; clarificationRequest=null; semanticDraft = null; pendingQuery = null; approvedQueryText = null
    confirmation = null; cameraCommand = null; contactsCommand = null; notificationsCommand = null; calendarQuery = null
    floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, needsConfirmation = false,
      appOptions = emptyList(), contactOptions = emptyList(), needsCamera = false, needsContacts = false, needsNotifications = false, needsCalendarRead = false,
      status = if (hadWorkflow) "Se detuvieron los pasos pendientes. Los ya realizados no se revierten." else "Pedido cancelado.", error = null)
    syncWorkflow()
  }

  private fun advanceWorkflow(learning: InterpretationRequest? = null) {
    try {
      val command = workflow.next()
      syncWorkflow()
      if (command != null) runPhoneCommand(command, learning = learning)
    } catch (_: Exception) { retainDecisionRequest("No se pudo guardar el progreso. Revisá la app antes de continuar.") }
  }

  private fun finishWorkflow() {
    conversation.clear(); semanticDraft = null; clarification = null; clarificationRequest = null
    floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", error = null,
      status = "Secuencia finalizada. Las entregas a otras apps se revisan allí.",
      phoneActionCompletion = "Secuencia finalizada.")
    syncWorkflow()
  }
  private var clarification: String? = null
  private var clarificationRequest: Pair<String,java.util.Calendar>? = null
  private var confirmation: PhoneCommand? = null
  private var cameraCommand: PhoneCommand? = null
  private var contactsCommand: PhoneCommand? = null
  private var calendarQuery: Pair<PhoneCommand.Platform, Long>? = null

  fun onFloatingCalendarPermission(granted: Boolean) {
    val (command, generation) = calendarQuery ?: return
    calendarQuery = null
    if (generation != floatingGeneration) return
    floatingState.value = floatingState.value.copy(needsCalendarRead = false)
    if (granted) queryFloatingCalendar(command)
    else retainDecisionRequest("Sin el permiso de lectura no pude consultar tu calendario.")
  }

  private fun queryFloatingCalendar(command: PhoneCommand.Platform) {
    val app = getApplication<Application>()
    val generation = floatingGeneration
    if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      calendarQuery = command to generation
      retainLocalAudio = true
      floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, needsCalendarRead = true,
        status = "Permití el acceso para consultar tu agenda.", error = null)
      return
    }
    if (app.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
      retainDecisionRequest("Desbloqueá el teléfono para consultar tu calendario.")
      return
    }
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, status = "Consultando tu calendario…", error = null)
    viewModelScope.launch {
      val agenda = try {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
          val selection = com.example.phone.CalendarSelection.decode(command)
          com.example.phone.DeviceCalendar(app.contentResolver).agenda(selection)
        }
      } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
      } catch (_: Exception) {
        null
      }
      if (generation != floatingGeneration) return@launch
      if (agenda == null) {
        retainDecisionRequest("No pude consultar el calendario. Revisá el permiso y volvé a intentarlo.")
      } else {
        retainLocalAudio = false
        pendingFloatingAudio?.file?.delete()
        pendingFloatingAudio = null
        pendingTranscript = null
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", status = agenda, error = null)
      }
    }
  }

  /**
   * Same pattern as [queryFloatingCalendar]: the generation guards a stale answer, cancellation propagates and
   * failures keep the request for retrying. Network and parsing run on Dispatchers.IO inside [weather].
   * The answer is text on the floating surface only: no browser, WhatsApp, Instinct or speech.
   */
  private fun queryFloatingWeather(command: PhoneCommand.Platform) {
    val generation = floatingGeneration
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, status = "Consultando el SMN…", error = null)
    viewModelScope.launch {
      val reply = try {
        weather.answer(command)
      } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
      } catch (_: Exception) {
        null
      }
      if (generation != floatingGeneration) return@launch
      if (reply == null || !reply.answered) {
        retainDecisionRequest(reply?.text ?: "No pude consultar el SMN. Volvé a intentarlo.")
      } else {
        retainLocalAudio = false
        pendingFloatingAudio?.file?.delete()
        pendingFloatingAudio = null
        pendingTranscript = null
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", status = reply.text, error = null)
      }
    }
  }

  private var appChoiceCommand: PhoneCommand? = null
  private var contactChoiceCommand: PhoneCommand? = null
  private var notificationsCommand: PhoneCommand? = null
  private var retainLocalAudio = false
  private var routedRequestText = ""
  private var retryLocalCommand: Pair<PhoneCommand, Boolean>? = null
  private var retryLocalText: String? = null

  fun consumePhoneActionCompletion(): String? {
    val message = floatingState.value.phoneActionCompletion ?: return null
    floatingState.value = floatingState.value.copy(phoneActionCompletion = null)
    return message
  }

  fun confirmFloatingAction(accept: Boolean, onDispatched: () -> Unit = {}) {
    pendingQuery?.let { query ->
      pendingQuery = null
      floatingState.value = floatingState.value.copy(needsConfirmation = false, queryPreview = false)
      if (!accept || query.generation != floatingGeneration) {
        floatingState.value = floatingState.value.copy(status = "Consulta cancelada.")
        return
      }
      approvedQueryText = query.text
      if (pendingFloatingAudio != null) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY)
        sendFloatingAudio(onDispatched)
      } else {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.TYPING, text = query.text)
        sendFloatingText(onDispatched)
      }
      return
    }
    val learning = learningPreview
    learningPreview = null
    if (activeWorkflow()?.confirmed == false) {
      if (previewInvalidatedByEdit) { retainDecisionRequest("El pedido cambió. Enviá la corrección y revisá el nuevo plan antes de confirmar."); return }
      semanticDraft = null
      if (accept) {
        workflow.confirm()
        advanceWorkflow(learning)
      } else cancelFloatingWorkflow()
      return
    }
    val command = confirmation ?: return
    learnedCommandConfirmation = false
    confirmation = null
    floatingState.value = floatingState.value.copy(needsConfirmation = false)
    if (accept) runPhoneCommand(command, confirmed = true, learning = learning)
    else if (activeWorkflow() != null) cancelFloatingWorkflow()
    else floatingState.value = floatingState.value.copy(status = "Pedido cancelado.")
  }

  fun chooseFloatingApp(option: AppOption) {
    if (option !in floatingState.value.appOptions || floatingState.value.mode == FloatingMode.SENDING) return
    floatingState.value = floatingState.value.copy(appOptions = emptyList())
    val media = appChoiceCommand as? PhoneCommand.Media
    val settings = (appChoiceCommand as? PhoneCommand.Platform)?.takeIf { it.action == PlatformAction.APP_SETTINGS }
    appChoiceCommand = null
    runPhoneCommand(media?.copy(packageName = option.packageName) ?: settings?.copy(argument=option.packageName) ?: PhoneCommand.OpenApp(option.packageName))
  }

  fun onFloatingCameraPermission(granted: Boolean) {
    val command = cameraCommand ?: return
    cameraCommand = null
    floatingState.value = floatingState.value.copy(needsCamera = false)
    if (granted) runPhoneCommand(command)
    else floatingState.value = floatingState.value.copy(error = "No se habilitó la cámara. El pedido se conserva; la linterna no se cambió.", status = null)
  }

  fun onFloatingNotificationsPermission(granted: Boolean) {
    val command=notificationsCommand?:return
    notificationsCommand=null
    floatingState.value=floatingState.value.copy(needsNotifications=false)
    if(granted)runPhoneCommand(command)
    else floatingState.value=floatingState.value.copy(error="Sin notificaciones no se aplicó el modo temporal. Podés habilitarlas en Ajustes y reintentar.")
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
    val pending = contactChoiceCommand
    contactChoiceCommand = null
    val number = PhoneNumbers.canonical(option.number) ?: return
    // The chosen number replaces the name; a WhatsApp message still goes through its confirmation.
    runPhoneCommand(if (pending is PhoneCommand.Platform && pending.action == PlatformAction.MESSAGE_SEND)
      pending.copy(argument = number) else PhoneCommand.Dial(option.number))
  }

  private fun runPhoneCommand(command: PhoneCommand, confirmed: Boolean = false, learning: InterpretationRequest? = null) {
    if(command is PhoneCommand.Platform && command.action==PlatformAction.ROUTINE_RUN) {
      try {
        val routines=ConfiguredRoutineStore(java.io.File(getApplication<Application>().noBackupFilesDir,"configured-routines.json")).read()
        val routine=routines.singleOrNull {PhoneCommandParser.normalize(it.name)==PhoneCommandParser.normalize(command.argument!!)}
        if(routine==null) retainDecisionRequest("No encontré esa rutina configurada. Podés consultar tus rutinas o importar plantillas revisadas.")
        else previewWorkflow(routine.commands)
      } catch(_:Exception) {retainDecisionRequest("No se pudo leer la configuración de rutinas. No se ejecutó ningún paso.")}
      return
    }
    val session = floatingGeneration
    // A cancelled or finished request never executes, whatever answer or callback arrives for it.
    if (!sessions.canExecute(session) || !sessions.transition(session, com.example.session.SessionState.EXECUTING)) return
    clarificationRequest = null
    clarification = null
    confirmation = null
    cameraCommand = null
    contactsCommand = null
    notificationsCommand = null
    if (activeWorkflow() != null) try {workflow.beginReplacement(command)} catch (_: Exception) {
      retainDecisionRequest("No se pudo guardar el paso. No se ejecutó la acción."); return
    }
    val ticket = executions.open(session, command)
    val result = phoneExecutor.execute(command, confirmed)
    // An automation result arrives later; anything else is final now.
    if (result.stopwatchAutomation == null) executions.complete(ticket, result.outcome)
    if (learning != null && result.confirmation == command) learningPreview = learning
    applyPhoneActionResult(command, confirmed, result, learning)
    result.stopwatchAutomation?.let { operation ->
      val generation = floatingGeneration
      floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING)
      cancelClockAutomation = com.example.assist.PingAutoSendAccessibilityService.requestStopwatch(operation) { completed ->
        // A callback delivered twice records and applies its outcome once.
        if (executions.complete(ticket, completed.outcome) && generation == floatingGeneration) {
          cancelClockAutomation = null
          applyPhoneActionResult(command, confirmed, completed, learning)
        }
      }
    }
  }

  private fun applyPhoneActionResult(
    command: PhoneCommand,
    confirmed: Boolean,
    result: com.example.phone.PhoneActionResult,
    learning: InterpretationRequest? = null,
  ) {
    val inWorkflow = activeWorkflow() != null
    if (inWorkflow) try {workflow.accept(result)} catch (_: Exception) {
      workflow.markUncertainInMemory()
      retainDecisionRequest("No se pudo guardar el resultado. Revisá la app antes de continuar; no se repetirá el paso.")
      syncWorkflow(); return
    }
    if (learning != null && result.stopwatchAutomation == null) learnedPhrases.saveConfirmed(learning, command, result)
    patternExample?.let { (request, proposed) ->
      // A pending confirmation keeps the example until the same command is executed.
      if (proposed == command && result.confirmation == null) {
        patternExample = null
        if (!inWorkflow) learnedPhrases.savePatternExample(request, command, result)
      }
    }
    operationalContext.record(command, result)
    confirmation = result.confirmation
    if (result.needsCamera) cameraCommand = command
    if (result.needsContacts) contactsCommand = command
    if (result.needsNotifications) notificationsCommand = command
    appChoiceCommand = command.takeIf {result.appOptions.isNotEmpty()}
    contactChoiceCommand = (result.pendingCommand ?: command).takeIf {result.contactOptions.isNotEmpty()}
    retainLocalAudio = result.retainRequest
    retryLocalCommand = if (result.retainRequest) command to confirmed else null
    retryLocalText = if (result.retainRequest) routedRequestText else null
    val nextMode = if (result.retainRequest && pendingFloatingAudio != null) FloatingMode.AUDIO_READY
      else if (result.retainRequest && floatingState.value.text.isNotBlank()) FloatingMode.TYPING else FloatingMode.IDLE
    floatingState.value = floatingState.value.copy(mode = nextMode,
      text = if (result.retainRequest) floatingState.value.text else "",
      error = if (result.outcome == PhoneActionOutcome.REJECTED) result.message else null,
      status = if (result.outcome == PhoneActionOutcome.REJECTED) null else result.message, needsWhatsAppAccessibility = false, needsConfirmation = result.confirmation != null,
      needsClockAccessibility = result.needsClockAccessibility,
      accessSettingsAction = result.accessSettingsAction,
      appOptions = result.appOptions, needsCamera = result.needsCamera, needsGroqKey = false,
      needsContacts = result.needsContacts, needsNotifications = result.needsNotifications, contactOptions = result.contactOptions,
      phoneActionCompletion = result.message.takeIf {
        !inWorkflow && (result.outcome == PhoneActionOutcome.COMPLETED || result.outcome == PhoneActionOutcome.HANDOFF)
      })
    sessions.transition(floatingGeneration, when {
      result.stopwatchAutomation != null -> com.example.session.SessionState.EXECUTING
      inWorkflow && workflow.snapshot?.finished != true -> com.example.session.SessionState.AWAITING_CONFIRMATION
      result.confirmation != null -> com.example.session.SessionState.AWAITING_CONFIRMATION
      result.outcome == PhoneActionOutcome.NEEDS_INPUT || result.needsCamera || result.needsContacts ||
        result.needsNotifications || result.appOptions.isNotEmpty() || result.contactOptions.isNotEmpty() -> com.example.session.SessionState.AWAITING_CLARIFICATION
      result.outcome == PhoneActionOutcome.REJECTED -> com.example.session.SessionState.FAILED
      else -> com.example.session.SessionState.COMPLETED
    })
    if (inWorkflow) {
      syncWorkflow()
      if (workflow.snapshot?.finished == true) finishWorkflow()
      else if (result.outcome == PhoneActionOutcome.COMPLETED) advanceWorkflow()
    }
    if (!result.retainRequest && !inWorkflow) {
      pendingFloatingAudio?.file?.delete()
      pendingFloatingAudio = null
      pendingTranscript = null
    }
  }

  /** Returns false only when this is a general query for Instinct. */
  private suspend fun routeFloatingRequest(text: String, onDispatched: () -> Unit): Boolean {
    retainLocalAudio = false
    val normalized = PhoneCommandParser.normalize(text)
    if (approvedQueryText == text) { approvedQueryText = null; return false }
    pendingQuery?.let {
      if (normalized in listOf("si", "dale", "confirmar", "confirmo", "ok", "no", "cancelar")) {
        confirmFloatingAction(normalized !in listOf("no", "cancelar"), onDispatched); return true
      }
      pendingQuery = null
      floatingState.value = floatingState.value.copy(queryPreview = false, needsConfirmation = false)
    }
    if (workflowLoadError != null) {
      if (normalized in listOf("cancelar", "descartar")) { workflow.clear(); workflowLoadError = null }
      else { retainDecisionRequest(workflowLoadError + " Decí cancelar para descartar el registro dañado."); return true }
    }
    if (normalized in listOf("cancelar", "cancela", "dejalo", "olvidalo") || normalized == "no" &&
      (activeWorkflow() != null || semanticDraft != null || clarification != null || confirmation != null ||
        floatingState.value.contactOptions.isNotEmpty() || floatingState.value.appOptions.isNotEmpty())) {
      cancelFloatingWorkflow(); return true
    }
    val pendingAction = activeWorkflow()?.confirmed == false || semanticDraft != null || clarification != null ||
      confirmation != null || floatingState.value.contactOptions.isNotEmpty() || floatingState.value.appOptions.isNotEmpty()
    // «No lo hagas», «No envíes ese mensaje»: cancels what is pending and never runs or sends it.
    if (RequestSafetyPolicy.cancelsPending(text)) {
      if (pendingAction) cancelFloatingWorkflow()
      else retainDecisionRequest("No hay ninguna acción pendiente. No se ejecutó ni se envió nada.")
      return true
    }
    // «No, prendé la linterna»: the «no» answers what came before; the affirmative order is routed again from the
    // start, through the same safety rules, confirmations and final semantic check as any other order.
    RequestSafetyPolicy.rectification(text)?.let { rectification ->
      if (activeWorkflow()?.confirmed == true) {
        retainDecisionRequest("Hay una secuencia en curso. Continuá o cancelala antes de iniciar otra acción."); return true
      }
      // Pending references only: the plan awaiting confirmation (or its draft, which survives a typed edit).
      val context = listOfNotNull(confirmation) + (activeWorkflow()?.steps?.map { it.command } ?: emptyList()) +
        (semanticDraft?.steps?.mapNotNull { runCatching { it.complete(java.util.Calendar.getInstance()) }.getOrNull() } ?: emptyList()) +
        listOfNotNull(PhoneCommand.Volume(VolumeOp.SET).takeIf { clarification == "volume" })
      val order = if (!rectification.anaphoric) rectification.order
        else RequestSafetyPolicy.resolveAnaphora(rectification.order, context) ?: run {
          retainDecisionRequest("¿Qué querés cambiar? Decí la orden completa, por ejemplo «poné el volumen al 30»."); return true
        }
      if (pendingAction) cancelFloatingWorkflow()
      return routeFloatingRequest(order, onDispatched)
    }
    val active = activeWorkflow()
    when (val phraseCommand = PhraseVoiceCommands.parse(text, phoneParser)) {
      PhraseVoiceCommand.ForgetLast -> { forgetLastLearnedPhrase(); return true }
      PhraseVoiceCommand.Show -> { openLearnedPhrases(onDispatched); return true }
      is PhraseVoiceCommand.Teach, is PhraseVoiceCommand.CorrectLast -> {
        if (active != null || confirmation != null || semanticDraft != null) {
          retainDecisionRequest("Terminá o cancelá el pedido en curso antes de enseñarme una frase.")
        } else manageLearnedPhrase(phraseCommand)
        return true
      }
      null -> Unit
    }
    if (active != null) {
      if (!active.confirmed && normalized in listOf("si", "dale", "ejecutar", "confirmar", "confirmo", "ok")) { confirmFloatingAction(true); return true }
      if (active.confirmed && normalized in listOf("continua", "continuar", "listo", "ya esta", "verificado", "omiti ese paso")) { continueFloatingWorkflow(); return true }
      if (normalized in listOf("reintenta", "reintentar", "intenta de nuevo")) { retryFloatingWorkflow(); return true }
    }
    if (confirmation != null && normalized in listOf("si", "si confirmo", "confirmo", "dale", "acepto", "no")) {
      confirmFloatingAction(normalized != "no"); return true
    }
    val local = phoneParser.parse(text)
    val searchQuery = phoneParser.chatGptSearchQuery(text, local)
    val fresh = searchQuery != null || local is PhoneRoute.Local || RequestSafetyPolicy.isQuestion(text) || text.trimStart().startsWith("¿") ||
      Regex("^(?:que|como|por que|donde|cual|instinct|explica|conta|ayuda|necesito|quiero|abri|busca|pone|agenda|llama)\\b").containsMatchIn(normalized)
    val contacts = floatingState.value.contactOptions
    if (contacts.isNotEmpty()) {
      val index = PhoneCommandParser.choiceIndex(normalized)
      val selected = index?.let(contacts::getOrNull) ?: contacts.singleOrNull {
        PhoneCommandParser.normalize(it.name) == normalized || PhoneNumbers.canonical(text)?.let { n -> n == PhoneNumbers.canonical(it.number) } == true
      }
      if (selected != null) { chooseFloatingContact(selected); return true }
      if (index != null || !fresh) { retainDecisionRequest("Elegí una de las opciones disponibles o decí cancelar."); return true }
    }
    val options = floatingState.value.appOptions
    if (options.isNotEmpty()) {
      val index = PhoneCommandParser.choiceIndex(normalized)
      val selected = index?.let(options::getOrNull) ?: options.singleOrNull { PhoneCommandParser.normalize(it.label) == normalized }
      if (selected != null) { chooseFloatingApp(selected); return true }
      if (index != null || !fresh) { retainDecisionRequest("Elegí una de las opciones disponibles o decí cancelar."); return true }
    }
    if (confirmation != null && !fresh) { retainDecisionRequest("Confirmá el pedido o decí cancelar."); return true }
    if (active?.confirmed == true) { retainDecisionRequest("Hay una secuencia en curso. Continuá o cancelala antes de iniciar otra acción."); return true }
    val retry = retryLocalCommand
    if (retry != null && retryLocalText == text) {
      // The previous attempt may have happened: repeating it needs a new, explicit confirmation.
      if (executions.needsReconfirmation(retry.first)) confirmRepeat(retry.first) else runPhoneCommand(retry.first, retry.second)
      return true
    }
    retryLocalCommand = null; retryLocalText = null
    if (searchQuery == null) RequestSafetyPolicy.rejection(text)?.let { retainDecisionRequest(it); return true }
    val correction = Regex("^(?:mejor|cambia|cambiale|que dure|durante|a las?|de la|el segundo|la segunda|el primero|la primera)\\b").containsMatchIn(normalized)
    if (searchQuery == null && semanticDraft != null && (local !is PhoneRoute.Local || correction)) {
      workflow.invalidatePreview(); syncWorkflow()
      val result = try { semanticDraft!!.reply(text, java.util.Calendar.getInstance()) } catch (_: Exception) {
        retainDecisionRequest("Ese dato no es válido. Conservé el pedido; corregilo antes de confirmar."); return true
      }
      if (result is InterpretationResult.Incomplete && result.draft.selectedStepId != null && hybridRouter.isEnabled()) {
        semanticDraft = result.draft; return interpretWithGemini(text, PhoneRoute.Instinct, semanticDraft)
      }
      if (result != null) return applyInterpretation(result,text)
      if (!hybridRouter.isEnabled()) { retainDecisionRequest(semanticDraft!!.prompt()); return true }
      return interpretWithGemini(text,PhoneRoute.Instinct,semanticDraft)
    }
    val context = clarification
    if (context != null && !fresh || context == "intent:calendar" && correction) {
      if (context == "intent:calendar" || !context!!.startsWith("intent:")) {
        when (val reply = conversation.accept(text, allowSemantic=hybridRouter.isEnabled())) {
          is ConversationResult.Plan -> { previewWorkflow(reply.commands); return true }
          is ConversationResult.Prompt -> {
            workflow.invalidatePreview(); syncWorkflow(); retainLocalAudio = true
            floatingState.value = floatingState.value.copy(mode=FloatingMode.IDLE,text="",status=reply.message,error=null); return true
          }
          is ConversationResult.Semantic -> { semanticDraft=reply.draft; return interpretWithGemini(text,PhoneRoute.Instinct,reply.draft) }
          ConversationResult.Pass -> Unit
        }
      } else {
        val reply = if (context!!.startsWith("intent:")) hybridRouter.reply(context,text,clarificationRequest)
          else phoneParser.parseReply(context,text).takeUnless { it == PhoneRoute.Instinct }
        if (reply != null) return applyResolvedRoute(reply,text)
      }
    }
    learningPreview = null
    patternExample = null
    // A new request has one classification gateway. Continuations above retain their selected intent.
    semanticDraft = null; conversation.clear(); clarification = null; clarificationRequest = null
    confirmation=null; cameraCommand=null; contactsCommand=null; notificationsCommand=null; appChoiceCommand=null; contactChoiceCommand=null; calendarQuery=null
    workflow.invalidatePreview(); syncWorkflow()
    floatingState.value = floatingState.value.copy(needsConfirmation=false,appOptions=emptyList(),contactOptions=emptyList(),needsCamera=false,needsContacts=false,needsNotifications=false,needsCalendarRead=false)
    operationalContext.beginTurn(local)
    if (searchQuery != null) {
      openChatGptSearch(searchQuery, onDispatched)
      return true
    }
    routedRequestText = text
    return interpretWithGemini(text,local,null)
  }

  private suspend fun forgetLastLearnedPhrase() = phraseManagement("No pude borrar la frase. Volvé a intentarlo.") { isCurrent ->
    if (learnedPhrases.forgetLast(isCurrent)) PhraseReply.Done("Olvidé la última frase aprendida que usaste.")
    else PhraseReply.Retain("Todavía no usaste ni aprendiste ninguna frase.")
  }

  /** Teaches or corrects a phrase. The action must be a request the local parser understands. */
  private suspend fun manageLearnedPhrase(order: PhraseVoiceCommand) =
    phraseManagement("No pude guardar la frase. Volvé a intentarlo.") { isCurrent ->
      val target = when (order) {
        is PhraseVoiceCommand.Teach -> order.target
        is PhraseVoiceCommand.CorrectLast -> order.target
        else -> return@phraseManagement PhraseReply.Retain("No entendí qué frase querés cambiar.")
      }
      val resolved = when (val check = PhraseTeaching.resolveTarget(target, phoneParser)) {
        is PhraseTeaching.Target.Invalid -> return@phraseManagement PhraseReply.Retain(check.message)
        is PhraseTeaching.Target.Valid -> check
      }
      val state = learnedPhrases.uiSettings.first()
      val disabled = if (state.enabled) "" else " Activá «Aprender y usar frases» en IA → Frases aprendidas para usarla."
      when (order) {
        is PhraseVoiceCommand.Teach -> {
          val phrase = PhraseTeaching.checkPhrase(order.phrase, resolved.command, state.entries.map { it.phrase }, parser = phoneParser)
          if (phrase is PhraseTeaching.Phrase.Invalid) return@phraseManagement PhraseReply.Retain(phrase.message)
          phrase as PhraseTeaching.Phrase.Valid
          if (!isCurrent() || !learnedPhrases.teach(phrase.key, resolved.command, target, display = order.phrase))
            return@phraseManagement PhraseReply.Retain("No pude guardar la frase. Volvé a intentarlo.")
          PhraseReply.Done("Aprendí «${order.phrase}»: ${resolved.summary}." +
            (if (phrase.replaces) " Reemplacé la acción anterior." else "") +
            " Si no es así, decí «olvidá esta frase»." + disabled)
        }
        else -> {
          val phrase = learnedPhrases.correctLast(resolved.command, target, isCurrent)
            ?: return@phraseManagement PhraseReply.Retain("Todavía no usaste ninguna frase aprendida para corregir.")
          PhraseReply.Done("Corregí «$phrase»: ahora ${resolved.summary}." + disabled)
        }
      }
    }

  private sealed interface PhraseReply {
    data class Done(val status: String) : PhraseReply
    data class Retain(val message: String) : PhraseReply
  }

  private suspend fun phraseManagement(failure: String, action: suspend (isCurrent: () -> Boolean) -> PhraseReply) {
    val generation = floatingGeneration
    val job = kotlinx.coroutines.currentCoroutineContext()[Job]
    val isCurrent = { job?.isActive != false && generation == floatingGeneration }
    decidingRequest = true
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, deciding = true)
    try {
      val reply = action(isCurrent)
      if (generation == floatingGeneration && !floatingPaused) when (reply) {
        is PhraseReply.Done -> {
          pendingFloatingAudio?.file?.delete()
          pendingFloatingAudio = null
          pendingTranscript = null
          floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", status = reply.status, error = null)
        }
        is PhraseReply.Retain -> retainDecisionRequest(reply.message)
      }
    } catch (_: java.io.IOException) {
      if (generation == floatingGeneration && !floatingPaused) retainDecisionRequest(failure)
    } finally {
      if (generation == floatingGeneration) {
        decidingRequest = false
        floatingState.value = floatingState.value.copy(deciding = false)
      }
    }
  }

  private fun openLearnedPhrases(onDispatched: () -> Unit) {
    val intent = Intent(getApplication(), com.example.speech.GroqSettingsActivity::class.java)
      .putExtra(com.example.speech.GroqSettingsActivity.EXTRA_PROVIDER, com.example.speech.GroqSettingsActivity.SECTION_PHRASES)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      getApplication<Application>().startActivity(intent)
    } catch (_: RuntimeException) {
      retainDecisionRequest("No pude abrir las frases aprendidas. Están en IA → Frases aprendidas.")
      return
    }
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = null)
    onDispatched()
  }

  private fun openChatGptSearch(query: String, onDispatched: () -> Unit) {
    val url = Uri.parse("https://chatgpt.com/").buildUpon()
      .appendQueryParameter("q", query)
      .appendQueryParameter("hints", "search")
      .build()
    val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      getApplication<Application>().startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      retainDecisionRequest("No se encontró una app para abrir ChatGPT. El pedido se conserva.")
      return
    } catch (_: SecurityException) {
      retainDecisionRequest("No se pudo abrir ChatGPT. El pedido se conserva.")
      return
    }
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = null)
    onDispatched()
  }

  private fun applyResolvedRoute(route: PhoneRoute, text: String): Boolean = when (route) {
    is PhoneRoute.Local -> applyInterpretation(InterpretationResult.Direct(route.command),text)
    is PhoneRoute.Clarify -> applyInterpretation(InterpretationResult.Clarification(route.prompt,route.context),text)
    is PhoneRoute.Unsupported -> { retainDecisionRequest(route.message); true }
    PhoneRoute.Cancel -> { cancelFloatingWorkflow(); true }
    PhoneRoute.Instinct -> false
  }

  private suspend fun interpretWithGemini(text: String, local: PhoneRoute, draft: DraftPlan?): Boolean {
    val generation = floatingGeneration
    sessions.transition(generation, com.example.session.SessionState.INTERPRETING)
    decidingRequest = true
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, deciding = true, error = null)
    return try {
      val request = InterpretationRequest(text, draft, sourceContext = floatingState.value.sourceContext)
      val result = geminiCoordinator.interpret(request, local) { stage ->
        if (generation == floatingGeneration && !floatingPaused) floatingState.value = floatingState.value.copy(status = stage)
      }
      if (generation != floatingGeneration || !kotlinx.coroutines.currentCoroutineContext().isActive || floatingPaused) true
      else {
        val proposed = when (result) {
          is InterpretationResult.Plan -> result.commands.singleOrNull()
          is InterpretationResult.Direct -> result.command.takeIf { !result.learned && !result.contextual && local !is PhoneRoute.Local }
          else -> null
        }
        val canLearn = proposed != null && LearnedPhraseStore.allows(proposed) &&
          LearnedPhraseStore.key(request) != null
        if (result is InterpretationResult.Direct && result.learned) {
          result.pattern?.let(learnedPhrases::savePatternUse) ?: learnedPhrases.saveUse(request, result.command)
        }
        patternExample = (result as? InterpretationResult.Direct)?.takeIf {
          !it.learned && !it.contextual && local !is PhoneRoute.Local && com.example.phone.PhrasePatterns.actionFor(it.command) != null
        }?.let { request to it.command }
        val handled = applyInterpretation(result, text)
        learnedCommandConfirmation = result is InterpretationResult.Direct && result.learned && confirmation != null
        learningPreview = request.takeIf { canLearn && (confirmation != null ||
          result is InterpretationResult.Plan && activeWorkflow()?.confirmed == false && !previewInvalidatedByEdit) }
        handled
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      if (generation == floatingGeneration) retainDecisionRequest("Interpretación cancelada. El pedido se conserva.")
      true
    } finally {
      if (generation == floatingGeneration) {
        decidingRequest = false
        floatingState.value = floatingState.value.copy(deciding = false)
      }
    }
  }

  private fun applyInterpretation(result: InterpretationResult, text: String): Boolean {
    when (result) {
      is InterpretationResult.Query -> {
        if (!result.needsConfirmation) return false
        sessions.transition(floatingGeneration, com.example.session.SessionState.AWAITING_CONFIRMATION)
        pendingQuery = QueryPreview(text, floatingGeneration)
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(
          mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
          status = "Groq propone enviar esta consulta a Instinct. Confirmá para enviar.",
          needsConfirmation = true, queryPreview = true, error = null)
      }
      is InterpretationResult.Direct -> {
        semanticDraft = null; conversation.clear(); clarification = null; clarificationRequest = null
        routedRequestText = text
        if (result.command is PhoneCommand.CalendarEvent) {
          conversation.accept(text)
          clarification = "intent:calendar"; clarificationRequest = text to java.util.Calendar.getInstance()
          previewWorkflow(listOf(result.command))
        } else if (result.command is PhoneCommand.Platform && result.command.action == PlatformAction.CALENDAR_QUERY) {
          queryFloatingCalendar(result.command)
        } else if (result.command is PhoneCommand.Platform && WorkflowRunner.isWeatherQuery(result.command)) {
          queryFloatingWeather(result.command)
        } else runPhoneCommand(result.command)
      }
      is InterpretationResult.Clarification -> {
        sessions.transition(floatingGeneration, com.example.session.SessionState.AWAITING_CLARIFICATION)
        clarification = result.context
        // A learned structure or a contextual reading clarifies the request the local grammar understood.
        val source = result.source ?: text
        if (clarificationRequest == null) clarificationRequest = source to java.util.Calendar.getInstance()
        conversation.accept(source)
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(mode=FloatingMode.IDLE,text="",status=result.prompt,error=null)
      }
      is InterpretationResult.Plan -> {
        clarification = null; conversation.clear()
        val weatherQuery = result.commands.singleOrNull() as? PhoneCommand.Platform
        if (weatherQuery != null && WorkflowRunner.isWeatherQuery(weatherQuery)) {
          semanticDraft = null; routedRequestText = text
          queryFloatingWeather(weatherQuery); return true
        }
        semanticDraft = result.draft ?: runCatching { DraftPlan.fromCommands(result.commands) }.getOrNull()
        sessions.transition(floatingGeneration, com.example.session.SessionState.AWAITING_CONFIRMATION)
        previewWorkflow(result.commands)
        if (result.fallback) floatingState.value = floatingState.value.copy(status = "Revisá el pedido interpretado con Groq y confirmá.")
      }
      is InterpretationResult.Incomplete -> {
        sessions.transition(floatingGeneration, com.example.session.SessionState.AWAITING_CLARIFICATION)
        semanticDraft = result.draft; conversation.clear(); clarification = null
        workflow.invalidatePreview(); syncWorkflow()
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", error = null,
          status = result.prompt)
      }
      is InterpretationResult.Rejected -> { sessions.transition(floatingGeneration, com.example.session.SessionState.FAILED); retainDecisionRequest(result.message) }
      is InterpretationResult.Failure -> { sessions.transition(floatingGeneration, com.example.session.SessionState.FAILED); retainDecisionRequest(result.message) }
    }
    return true
  }

  private fun confirmRepeat(command: PhoneCommand) {
    retryLocalCommand = null; retryLocalText = null
    confirmation = command
    sessions.transition(floatingGeneration, com.example.session.SessionState.AWAITING_CONFIRMATION)
    floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, needsConfirmation = true, error = null,
      status = "El intento anterior pudo haberse realizado: ${PhoneCommandCodec.summary(command)}. " +
        "Revisalo antes de repetir. Confirmá solo si querés hacerlo de nuevo.")
  }

  private fun retainDecisionRequest(message: String) {
    retainLocalAudio = true
    floatingState.value = floatingState.value.copy(
      mode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
      error = message, status = null)
  }

  /** After a restore: preferences and phrases refresh through their flows; derived caches are rebuilt. Nothing runs. */
  fun onConfigurationImported() {
    vocabularyHints.invalidate()
  }

  fun prepareFloatingAssistant() {
    runCatching {TemporaryModeManager(getApplication()).reconcile()}
    val prefs = runBlockingPrefs(getApplication())
    vocabularyHints.refresh(viewModelScope, prefs)
    floatingPrefs = prefs
    floatingReturnPackage = com.example.assist.AutoSendCoordinator.lastKnownForegroundPackage
    floatingContact = prefs.pinnedContact
    // Loading routing preferences during a send must not erase the active text/recording state.
    floatingState.value = floatingState.value.copy(contactName = floatingContact?.displayName ?: "Instinct",
      autoSendHealth = floatingContact?.let {
        runCatching { com.example.assist.AutoSendHealthReader.read(getApplication(), prefs.preferredFlavor) }.getOrNull()
      })
    if (activeWorkflow() != null) {
      syncWorkflow()
      floatingState.value = floatingState.value.copy(status = "Hay una secuencia pendiente. Revisá los pasos antes de continuar.")
    }
    workflowLoadError?.let {floatingState.value = floatingState.value.copy(error = it)}
    val gemini = com.example.phone.GeminiSettings(getApplication())
    floatingState.value = floatingState.value.copy(geminiConfigured = gemini.readKey() != null, geminiEnabled = gemini.isEnabled())
  }

  fun setFloatingContext(context: SharedTextContext?, error: String? = null) {
    prepareFloatingAssistant()
    if (activeWorkflow()?.confirmed == true || floatingState.value.mode == FloatingMode.SENDING && !decidingRequest && !transcribingAudio) {
      floatingState.value = floatingState.value.copy(error = "Terminá o cancelá el pedido actual antes de cambiar el contexto.")
      return
    }
    enterFloatingTextMode() // Invalidates generations, pending queries, audio and unconfirmed plans.
    semanticDraft = null; conversation.clear(); clarification = null; clarificationRequest = null
    confirmation = null; retryLocalCommand = null; retryLocalText = null
    cameraCommand = null; contactsCommand = null; notificationsCommand = null; appChoiceCommand = null; contactChoiceCommand = null
    floatingState.value = floatingState.value.copy(sourceContext = context, attachment = if (context != null || error != null) null else floatingState.value.attachment,
      text = if (context != null) "" else floatingState.value.text,
      error = error, status = null, needsConfirmation = false, contactOptions = emptyList(), appOptions = emptyList(),
      needsCamera = false, needsContacts = false, needsNotifications = false, needsCalendarRead = false)
  }

  fun startFloatingListening(onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    if (floatingState.value.mode == FloatingMode.SENDING || floatingState.value.attachment != null) return
    sessions.begin(com.example.session.SessionState.CAPTURING)
    pendingQuery = null; approvedQueryText = null
    floatingState.value = floatingState.value.copy(queryPreview = false)
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
    floatingState.value = floatingState.value.copy(mode = FloatingMode.LISTENING, text = "", elapsedMs = 0, error = null, needsGroqKey = false, transcript = null, voiceLatency = null, needsWhatsAppAccessibility = false)
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
    invalidatePreviewForEditing()
    if (pendingQuery != null) {
      pendingQuery = null; approvedQueryText = null
      floatingState.value = floatingState.value.copy(queryPreview = false, needsConfirmation = false)
    }
    sessions.begin(com.example.session.SessionState.CAPTURING)
    floatingJob?.cancel()
    decidingRequest = false
    transcribingAudio = false
    stopFloatingCapture(discard = true)
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    floatingState.value = floatingState.value.copy(mode = FloatingMode.TYPING, deciding = false, amplitude = 0f, error = null, status = null, needsWhatsAppAccessibility = false)
  }

  fun updateFloatingText(text: String) {
    if (text != floatingState.value.text) invalidatePreviewForEditing()
    if (pendingQuery != null && text != pendingQuery?.text) {
      pendingQuery = null; approvedQueryText = null
      floatingState.value = floatingState.value.copy(needsConfirmation = false, queryPreview = false)
    }
    if (floatingState.value.mode == FloatingMode.SENDING && !decidingRequest && !transcribingAudio) return
    if (floatingState.value.mode != FloatingMode.TYPING) enterFloatingTextMode()
    floatingState.value = floatingState.value.copy(text = text)
  }

  private fun invalidatePreviewForEditing() {
    if ((learnedCommandConfirmation || learningPreview != null) && activeWorkflow() == null && confirmation != null) {
      confirmation = null
      floatingState.value = floatingState.value.copy(needsConfirmation = false)
    }
    learnedCommandConfirmation = false
    learningPreview = null
    if (activeWorkflow()?.confirmed != false) return
    previewInvalidatedByEdit = true
    runCatching { workflow.invalidatePreview() }.onFailure {
      floatingState.value = floatingState.value.copy(error = "No se pudo guardar la edición. El plan anterior no se ejecutará.")
    }
    syncWorkflow()
    floatingState.value = floatingState.value.copy(needsConfirmation = false)
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
      // The request is kept for a retry; its late answer must not apply.
      sessions.cancel()
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
    val generation = sessions.begin(com.example.session.SessionState.CAPTURING)
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, amplitude = 0f)
    viewModelScope.launch {
      val audio = finishFloatingCapture()
      if (generation != floatingGeneration) { audio?.file?.delete(); return@launch }
      sessions.transition(generation, com.example.session.SessionState.IDLE)
      pendingFloatingAudio = audio
      floatingState.value = floatingState.value.copy(
        mode = if (audio != null) FloatingMode.AUDIO_READY else FloatingMode.IDLE,
        error = error ?: floatingCapture.snapshot.error)
    }
  }

  /**
   * [endOperationalContext] is true when the user closes the assistant. The panel also closes itself after a
   * handoff (the ViewModel is cleared), and then the brief context remains for the next order until it expires.
   */
  fun dismissFloatingAssistant(endOperationalContext: Boolean = true) {
    if (endOperationalContext) operationalContext.clear()
    learningPreview = null
    patternExample = null
    cancelClockAutomation?.invoke()
    cancelClockAutomation = null
    sessions.cancel()
    transcribingAudio = false
    decidingRequest = false
    stopFloatingCapture(discard = true)
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    notificationsCommand=null
    calendarQuery = null
    semanticDraft = null; pendingQuery = null; approvedQueryText = null
    conversation.clear()
    // Explicit close cancels future steps; effects already handed off are never replayed.
    if (activeWorkflow() != null) workflow.cancel()
    clarification = null
    confirmation = null
    cameraCommand = null
    contactsCommand = null
    floatingState.value = floatingState.value.copy(sourceContext = null, queryPreview = false, needsNotifications = false, workflowActive = false, workflowCanContinue = false, workflowCanRetry = false, deciding = false, needsConfirmation = false, needsCamera = false, appOptions = emptyList(), needsContacts = false, needsCalendarRead = false, contactOptions = emptyList(), phoneActionCompletion = null)
  }

  fun sendFloatingAudio(onDispatched: () -> Unit) {
    if (floatingState.value.mode !in listOf(FloatingMode.RECORDING, FloatingMode.AUDIO_READY)) return
    floatingJob?.cancel()
    floatingJob = null
    val generation = sessions.begin(com.example.session.SessionState.TRANSCRIBING)
    val sendStarted = monotonicClock()
    val previousLatency = floatingState.value.voiceLatency
    val endpoint = floatingCapture.snapshot.speechEndAtMs?.let { (sendStarted - it).coerceAtLeast(0) }
    val latency = if (pendingFloatingAudio != null) previousLatency ?: VoiceLatency() else VoiceLatency(endpointMs = endpoint)
    floatingPaused = false
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = "Interpretando tu audio…", needsGroqKey = false, voiceLatency = latency)
    floatingJob = viewModelScope.launch {
      val reusedAudio = pendingFloatingAudio != null
      val finalizationStarted = monotonicClock()
      val audio = pendingFloatingAudio ?: finishFloatingCapture()
      if (generation != floatingGeneration) { audio?.file?.delete(); return@launch }
      if (audio == null) {
        sessions.transition(generation, com.example.session.SessionState.FAILED)
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE,
          error = floatingCapture.snapshot.error ?: "No se pudo finalizar el audio. Activá el micrófono para volver a grabar.")
        return@launch
      }
      pendingFloatingAudio = audio
      if (!reusedAudio) updateVoiceLatency(latency.copy(finalizationMs = monotonicClock() - finalizationStarted))
      if (floatingPaused) {
        sessions.transition(generation, com.example.session.SessionState.IDLE)
        floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY, status = "Audio guardado.")
        return@launch
      }
      transcribingAudio = true
      val reusedTranscript = pendingTranscript != null
      val transcriptionStarted = monotonicClock()
      val transcript = try {
        pendingTranscript ?: audioTranscriber.transcribe(audio.file).also {
          require(it.isNotBlank()) { "La grabación no produjo una transcripción." }
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (generation == floatingGeneration) transcribingAudio = false
        if (generation == floatingGeneration) floatingState.value = floatingState.value.copy(mode = FloatingMode.AUDIO_READY,
          error = "La interpretación se interrumpió. El audio se conserva para reintentar.", status = null)
        return@launch
      } catch (failure: Exception) {
        if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
        transcribingAudio = false
        sessions.transition(generation, com.example.session.SessionState.IDLE)
        val transcriptionError = failure as? TranscriptionException
        floatingState.value = floatingState.value.copy(needsGroqKey = transcriptionError?.needsKey == true)
        val state = floatingState.value
        if (state.sourceContext == null && activeWorkflow() == null && workflowLoadError == null &&
          semanticDraft == null && clarification == null && confirmation == null && pendingQuery == null &&
          retryLocalCommand == null && state.appOptions.isEmpty() && state.contactOptions.isEmpty() &&
          !state.needsCamera && !state.needsContacts && !state.needsNotifications && !state.needsCalendarRead) {
          dispatchFloatingAudio(audio, onDispatched)
        } else {
          retainDecisionRequest(transcriptionError?.message ?: "No se pudo interpretar. El audio se conserva.")
        }
        return@launch
      }
      if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
      transcribingAudio = false
      sessions.transition(generation, com.example.session.SessionState.INTERPRETING)
      if (!reusedTranscript) updateVoiceLatency(floatingState.value.voiceLatency!!.copy(transcriptionMs = monotonicClock() - transcriptionStarted))
      pendingTranscript = transcript
      floatingState.value = floatingState.value.copy(transcript = transcript)
      val interpretationStarted = monotonicClock()
      val handled = try { routeFloatingRequest(transcript, onDispatched) } finally {
        if (generation == floatingGeneration && isActive && !floatingPaused) {
          updateVoiceLatency(floatingState.value.voiceLatency!!.copy(interpretationMs = monotonicClock() - interpretationStarted))
        }
      }
      if (handled) {
        if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
        if (!retainLocalAudio) {
          audio.file.delete()
          pendingFloatingAudio = null
          pendingTranscript = null
        }
        return@launch
      }
      if (floatingState.value.sourceContext != null) {
        dispatchFloatingTextQuery(transcript, onDispatched)
        return@launch
      }
      dispatchFloatingAudio(audio, onDispatched)
    }
  }

  private fun dispatchFloatingAudio(audio: RecordedAudio, onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    val contact = floatingContact
    val prefs = floatingPrefs
    if (contact == null || prefs == null) {
      retainDecisionRequest("Configurá el contacto de Instinct antes de enviar el audio.")
      return
    }
    if (WhatsAppDispatcher.resolveTargetPackage(getApplication(), prefs.preferredFlavor) == null) {
      retainDecisionRequest("Instalá WhatsApp para enviar. El audio se conserva.")
      return
    }
    floatingState.value = floatingState.value.copy(status = "Enviando el audio original a Instinct…")
    val result = WhatsAppDispatcher.sendWhatsAppAudio(
      getApplication(), contact.phoneNumber, audio.file, prefs.preferredFlavor,
      PingAutoSendAccessibilityService.isEnabled(getApplication()), boomerangReturn = true,
      mimeType = audio.mimeType, recipientName = contact.displayName, returnPackage = floatingReturnPackage)
    if (result is DispatchResult.Error) {
      retainDecisionRequest(result.message)
      floatingState.value = floatingState.value.copy(needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else {
      sessions.transition(floatingGeneration, com.example.session.SessionState.COMPLETED)
      pendingFloatingAudio = null // FileProvider/WhatsApp owns the shared file from here.
      floatingState.value = floatingState.value.copy(needsGroqKey = false)
      onDispatched()
    }
  }

  fun openFloatingInstinct(onDispatched: () -> Unit) {
    val state = floatingState.value
    if (state.mode == FloatingMode.SENDING) return
    val app = getApplication<Application>()
    val prefs = runBlockingPrefs(app)
    val contact = prefs.pinnedContact
    if (contact == null) {
      floatingState.value = state.copy(error = "Configurá el contacto de Instinct para abrir WhatsApp.")
      return
    }
    val result = when {
      state.text.isBlank() -> WhatsAppDispatcher.openWhatsAppChat(app, contact.phoneNumber, prefs.preferredFlavor)
      PingAutoSendAccessibilityService.isEnabled(app) && PingAutoSendAccessibilityService.isConnected() ->
        WhatsAppDispatcher.sendWhatsAppText(app, contact.phoneNumber, state.text, prefs.preferredFlavor,
          isAutoSendServiceEnabled = true, recipientName = contact.displayName)
      else -> WhatsAppDispatcher.openWhatsAppTextDraft(app, contact.phoneNumber, state.text, prefs.preferredFlavor)
    }
    if (result is DispatchResult.Error) {
      floatingState.value = state.copy(error = result.message)
      return
    }
    stopFloatingCapture(discard = true)
    pendingFloatingAudio?.file?.delete()
    pendingFloatingAudio = null
    pendingTranscript = null
    floatingState.value = state.copy(mode = FloatingMode.SENDING, error = null)
    onDispatched()
  }

  /**
   * The manual path when automatic sending needs review: opens the Instinct chat with the prepared text or audio and
   * never arms the accessibility service. After an uncertain send it only opens the chat, so nothing is sent twice.
   */
  fun openFloatingInstinctManually(onDispatched: () -> Unit) {
    val state = floatingState.value
    if (state.mode == FloatingMode.SENDING) return
    val app = getApplication<Application>()
    val prefs = runBlockingPrefs(app)
    val contact = prefs.pinnedContact ?: run {
      floatingState.value = state.copy(error = "Configurá el contacto de Instinct para abrir WhatsApp.")
      return
    }
    val audio = pendingFloatingAudio
    val reviewOnly = state.autoSendHealth?.lastUncertain == true
    val result = when {
      reviewOnly || state.text.isBlank() && audio == null ->
        WhatsAppDispatcher.openWhatsAppChat(app, contact.phoneNumber, prefs.preferredFlavor)
      state.text.isBlank() && audio != null -> WhatsAppDispatcher.sendWhatsAppAudio(app, contact.phoneNumber, audio.file,
        prefs.preferredFlavor, isAutoSendServiceEnabled = false, mimeType = audio.mimeType)
      else -> WhatsAppDispatcher.openWhatsAppTextDraft(app, contact.phoneNumber, state.text, prefs.preferredFlavor)
    }
    if (result is DispatchResult.Error) {
      floatingState.value = state.copy(error = result.message)
      return
    }
    if (reviewOnly) { onDispatched(); return } // The prepared request stays here; the user decides after checking.
    stopFloatingCapture(discard = true)
    if (result is DispatchResult.AudioDispatched) pendingFloatingAudio = null // WhatsApp owns the shared file now.
    pendingTranscript = null
    floatingState.value = state.copy(mode = FloatingMode.SENDING, error = null)
    onDispatched()
  }

  fun sendFloatingText(onDispatched: () -> Unit) {
    if (floatingState.value.mode == FloatingMode.SENDING || floatingState.value.text.isBlank()) return
    stopFloatingCapture(discard = true)
    val text = floatingState.value.text
    val generation = sessions.begin(com.example.session.SessionState.INTERPRETING)
    floatingPaused = false
    floatingJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
      if (routeFloatingRequest(text, onDispatched)) return@launch
      if (generation != floatingGeneration || !isActive || floatingPaused) return@launch
      dispatchFloatingTextQuery(text, onDispatched)
    }
  }


  private fun updateVoiceLatency(latency: VoiceLatency) {
    floatingState.value = floatingState.value.copy(voiceLatency = latency)
    if (com.example.BuildConfig.DEBUG) android.util.Log.d("InstoVoiceLatency", latency.summary())
  }

  private fun dispatchFloatingTextQuery(text: String, onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    val failureMode = if (pendingFloatingAudio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING
    val contact = floatingContact
    val prefs = floatingPrefs
    if (contact == null || prefs == null) {
      floatingState.value = floatingState.value.copy(mode = failureMode,
        error = "Configurá el contacto de Instinct para enviar esta consulta.")
      return
    }
    val payload = floatingState.value.sourceContext?.query(text) ?: text
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = "Enviando a Instinct…")
    val result = WhatsAppDispatcher.sendWhatsAppText(getApplication(), contact.phoneNumber,
      payload, prefs.preferredFlavor, PingAutoSendAccessibilityService.isEnabled(getApplication()), contact.displayName, floatingReturnPackage)
    if (result is DispatchResult.Error) {
      floatingState.value = floatingState.value.copy(mode = failureMode, error = result.message,
        needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else {
      sessions.transition(floatingGeneration, com.example.session.SessionState.COMPLETED)
      pendingFloatingAudio?.file?.delete()
      pendingFloatingAudio = null; pendingTranscript = null
      onDispatched()
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
    dismissFloatingAssistant(endOperationalContext = false)
    super.onCleared()
  }

  companion object {
    /** How long the hotkey must stay down, after the assistant fires, to count as a hold. */
    const val HOLD_GRACE_MS = 350L
  }
}
