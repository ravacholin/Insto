package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.speech.GroqKeyStore
import com.example.speech.TranscriptionException
import com.example.assist.PingAutoSendAccessibilityService
import com.example.audio.FloatingAudioCapture
import com.example.audio.RecordedAudio
import com.example.audio.VoiceLatency
import com.example.audio.VoiceSessionPolicy
import com.example.context.SharedTextContext
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.data.LearnedPhraseStore
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class FloatingSessionController(
  private val application: Application,
  private val scope: kotlinx.coroutines.CoroutineScope,
  private val repository: AssistantPreferencesRepository,
  private val floatingCapture: FloatingAudioCapture,
  private val audioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
  private val audioTranscriber: AudioTranscriber,
  private val hybridRouter: HybridPhoneRouter,
  structuredExtractor: StructuredCommandExtractor,
  private val monotonicClock: () -> Long,
  groqFallback: StructuredCommandExtractor?,
  private val learnedPhrases: LearnedPhraseStore,
) {
  private val floatingState = MutableStateFlow(FloatingAssistantState())
  val floatingAssistant: StateFlow<FloatingAssistantState> = floatingState
  private var floatingJob: Job? = null
  private var floatingGeneration = 0L
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
  private val phoneExecutor = PhoneActionExecutor(application)
  private val conversation = ConversationSession()
  private val groqInterpreter = com.example.phone.GroqIntentVerifier({ GroqKeyStore(application).read() })
  private val geminiCoordinator = GeminiCoordinator(hybridRouter, structuredExtractor,
    verifier = groqInterpreter, fallback = groqFallback ?: groqInterpreter, lookupLearnedPhrase = learnedPhrases::lookup)
  private var learningPreview: InterpretationRequest? = null
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
      workflowLines = snapshot?.steps?.mapIndexed { index, item -> "${index + 1}. ${PhoneCommandCodec.summary(item.command)}${CapabilityRequirements.missing(application,item.command).takeIf {it.isNotEmpty()}?.joinToString(prefix=" · ",separator=" · ") ?: ""} · ${when(item.status){StepStatus.PENDING->"pendiente";StepStatus.EXECUTING->"en curso";StepStatus.COMPLETED->"completado";StepStatus.HANDED_OFF->"entregado · revisá la app";StepStatus.WAITING_INPUT->"requiere tu respuesta";StepStatus.FAILED->"falló";StepStatus.UNCERTAIN->"resultado incierto · revisá antes de seguir";StepStatus.CANCELLED->"cancelado"}}" } ?: emptyList(),
      workflowActive = active != null,
      needsConfirmation = active?.confirmed == false || confirmation != null,
      workflowCanContinue = step?.status in listOf(StepStatus.HANDED_OFF, StepStatus.UNCERTAIN) || active?.confirmed == true && step?.status == StepStatus.PENDING,
      workflowCanRetry = step?.status in listOf(StepStatus.FAILED, StepStatus.WAITING_INPUT),
    )
  }

  private fun previewWorkflow(commands: List<PhoneCommand>) {
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
    val hadWorkflow = activeWorkflow() != null
    floatingGeneration++; floatingJob?.cancel()
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
    val app = application
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
    scope.launch {
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
  private var appChoiceCommand: PhoneCommand? = null
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
    runPhoneCommand(PhoneCommand.Dial(option.number))
  }

  private fun runPhoneCommand(command: PhoneCommand, confirmed: Boolean = false, learning: InterpretationRequest? = null) {
    if(command is PhoneCommand.Platform && command.action==PlatformAction.ROUTINE_RUN) {
      try {
        val routines=ConfiguredRoutineStore(java.io.File(application.noBackupFilesDir,"configured-routines.json")).read()
        val routine=routines.singleOrNull {PhoneCommandParser.normalize(it.name)==PhoneCommandParser.normalize(command.argument!!)}
        if(routine==null) retainDecisionRequest("No encontré esa rutina configurada. Podés consultar tus rutinas o importar plantillas revisadas.")
        else previewWorkflow(routine.commands)
      } catch(_:Exception) {retainDecisionRequest("No se pudo leer la configuración de rutinas. No se ejecutó ningún paso.")}
      return
    }
    clarificationRequest = null
    clarification = null
    confirmation = null
    cameraCommand = null
    contactsCommand = null
    notificationsCommand = null
    if (activeWorkflow() != null) try {workflow.beginReplacement(command)} catch (_: Exception) {
      retainDecisionRequest("No se pudo guardar el paso. No se ejecutó la acción."); return
    }
    val result = phoneExecutor.execute(command, confirmed)
    if (learning != null && result.confirmation == command) learningPreview = learning
    applyPhoneActionResult(command, confirmed, result, learning)
    result.stopwatchAutomation?.let { operation ->
      val generation = floatingGeneration
      floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING)
      cancelClockAutomation = com.example.assist.PingAutoSendAccessibilityService.requestStopwatch(operation) { completed ->
        if (generation == floatingGeneration) {
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
    confirmation = result.confirmation
    if (result.needsCamera) cameraCommand = command
    if (result.needsContacts) contactsCommand = command
    if (result.needsNotifications) notificationsCommand = command
    appChoiceCommand = command.takeIf {result.appOptions.isNotEmpty()}
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
    val active = activeWorkflow()
    if (normalized == "olvida esta frase" && text.none { it == '?' || it == '¿' }) {
      forgetLastLearnedPhrase()
      return true
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
    if (retry != null && retryLocalText == text) { runPhoneCommand(retry.first,retry.second); return true }
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
    // A new request has one classification gateway. Continuations above retain their selected intent.
    semanticDraft = null; conversation.clear(); clarification = null; clarificationRequest = null
    confirmation=null; cameraCommand=null; contactsCommand=null; notificationsCommand=null; appChoiceCommand=null; calendarQuery=null
    workflow.invalidatePreview(); syncWorkflow()
    floatingState.value = floatingState.value.copy(needsConfirmation=false,appOptions=emptyList(),contactOptions=emptyList(),needsCamera=false,needsContacts=false,needsNotifications=false,needsCalendarRead=false)
    if (searchQuery != null) {
      openChatGptSearch(searchQuery, onDispatched)
      return true
    }
    routedRequestText = text
    return interpretWithGemini(text,local,null)
  }

  private suspend fun forgetLastLearnedPhrase() {
    val generation = floatingGeneration
    val job = kotlinx.coroutines.currentCoroutineContext()[Job]
    decidingRequest = true
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, deciding = true)
    try {
      val removed = learnedPhrases.forgetLast { job?.isActive != false }
      if (generation == floatingGeneration && !floatingPaused) {
        if (removed) {
          pendingFloatingAudio?.file?.delete()
          pendingFloatingAudio = null
          pendingTranscript = null
          floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "",
            status = "Olvidé la última frase aprendida que usaste.", error = null)
        } else retainDecisionRequest("Todavía no usaste ninguna frase aprendida.")
      }
    } catch (_: java.io.IOException) {
      if (generation == floatingGeneration && !floatingPaused)
        retainDecisionRequest("No pude borrar la frase. Volvé a intentarlo.")
    } finally {
      if (generation == floatingGeneration) {
        decidingRequest = false
        floatingState.value = floatingState.value.copy(deciding = false)
      }
    }
  }

  private fun openChatGptSearch(query: String, onDispatched: () -> Unit) {
    val url = Uri.parse("https://chatgpt.com/").buildUpon()
      .appendQueryParameter("q", query)
      .appendQueryParameter("hints", "search")
      .build()
    val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      application.startActivity(intent)
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
          is InterpretationResult.Direct -> result.command.takeIf { !result.learned && local !is PhoneRoute.Local }
          else -> null
        }
        val canLearn = proposed != null && LearnedPhraseStore.allows(proposed) &&
          LearnedPhraseStore.key(request) != null
        if (result is InterpretationResult.Direct && result.learned) {
          learnedPhrases.saveUse(request, result.command)
        }
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
        } else runPhoneCommand(result.command)
      }
      is InterpretationResult.Clarification -> {
        clarification = result.context
        if (clarificationRequest == null) clarificationRequest = text to java.util.Calendar.getInstance()
        conversation.accept(text)
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(mode=FloatingMode.IDLE,text="",status=result.prompt,error=null)
      }
      is InterpretationResult.Plan -> {
        clarification = null; conversation.clear()
        semanticDraft = result.draft ?: runCatching { DraftPlan.fromCommands(result.commands) }.getOrNull()
        previewWorkflow(result.commands)
        if (result.fallback) floatingState.value = floatingState.value.copy(status = "Revisá el pedido interpretado con Groq y confirmá.")
      }
      is InterpretationResult.Incomplete -> {
        semanticDraft = result.draft; conversation.clear(); clarification = null
        workflow.invalidatePreview(); syncWorkflow()
        retainLocalAudio = true
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE, text = "", error = null,
          status = result.prompt)
      }
      is InterpretationResult.Rejected -> retainDecisionRequest(result.message)
      is InterpretationResult.Failure -> retainDecisionRequest(result.message)
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
    runCatching {TemporaryModeManager(application).reconcile()}
    val prefs = kotlinx.coroutines.runBlocking { repository.getCurrentPreferences() }
    floatingPrefs = prefs
    floatingReturnPackage = com.example.assist.AutoSendCoordinator.lastKnownForegroundPackage
    floatingContact = prefs.pinnedContact
    // Loading routing preferences during a send must not erase the active text/recording state.
    floatingState.value = floatingState.value.copy(contactName = floatingContact?.displayName ?: "Instinct")
    if (activeWorkflow() != null) {
      syncWorkflow()
      floatingState.value = floatingState.value.copy(status = "Hay una secuencia pendiente. Revisá los pasos antes de continuar.")
    }
    workflowLoadError?.let {floatingState.value = floatingState.value.copy(error = it)}
    val gemini = com.example.phone.GeminiSettings(application)
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
    cameraCommand = null; contactsCommand = null; notificationsCommand = null; appChoiceCommand = null
    floatingState.value = floatingState.value.copy(sourceContext = context, attachment = if (context != null || error != null) null else floatingState.value.attachment,
      text = if (context != null) "" else floatingState.value.text,
      error = error, status = null, needsConfirmation = false, contactOptions = emptyList(), appOptions = emptyList(),
      needsCamera = false, needsContacts = false, needsNotifications = false, needsCalendarRead = false)
  }

  fun startFloatingListening(onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    if (floatingState.value.mode == FloatingMode.SENDING || floatingState.value.attachment != null) return
    floatingGeneration++
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
    floatingJob = scope.launch {
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
    floatingGeneration++
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
      val resolver = application.contentResolver
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
    val result = WhatsAppDispatcher.sendWhatsAppAttachment(application, contact.phoneNumber,
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
    scope.launch {
      val audio = finishFloatingCapture()
      if (generation != floatingGeneration) { audio?.file?.delete(); return@launch }
      pendingFloatingAudio = audio
      floatingState.value = floatingState.value.copy(
        mode = if (audio != null) FloatingMode.AUDIO_READY else FloatingMode.IDLE,
        error = error ?: floatingCapture.snapshot.error)
    }
  }

  fun dismissFloatingAssistant() {
    learningPreview = null
    cancelClockAutomation?.invoke()
    cancelClockAutomation = null
    floatingGeneration++
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
    val generation = ++floatingGeneration
    val sendStarted = monotonicClock()
    val previousLatency = floatingState.value.voiceLatency
    val endpoint = floatingCapture.snapshot.speechEndAtMs?.let { (sendStarted - it).coerceAtLeast(0) }
    val latency = if (pendingFloatingAudio != null) previousLatency ?: VoiceLatency() else VoiceLatency(endpointMs = endpoint)
    floatingPaused = false
    floatingState.value = floatingState.value.copy(mode = FloatingMode.SENDING, error = null, status = "Interpretando tu audio…", needsGroqKey = false, voiceLatency = latency)
    floatingJob = scope.launch {
      val reusedAudio = pendingFloatingAudio != null
      val finalizationStarted = monotonicClock()
      val audio = pendingFloatingAudio ?: finishFloatingCapture()
      if (generation != floatingGeneration) { audio?.file?.delete(); return@launch }
      if (audio == null) {
        floatingState.value = floatingState.value.copy(mode = FloatingMode.IDLE,
          error = floatingCapture.snapshot.error ?: "No se pudo finalizar el audio. Activá el micrófono para volver a grabar.")
        return@launch
      }
      pendingFloatingAudio = audio
      if (!reusedAudio) updateVoiceLatency(latency.copy(finalizationMs = monotonicClock() - finalizationStarted))
      if (floatingPaused) {
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
    if (WhatsAppDispatcher.resolveTargetPackage(application, prefs.preferredFlavor) == null) {
      retainDecisionRequest("Instalá WhatsApp para enviar. El audio se conserva.")
      return
    }
    floatingState.value = floatingState.value.copy(status = "Enviando el audio original a Instinct…")
    val result = WhatsAppDispatcher.sendWhatsAppAudio(
      application, contact.phoneNumber, audio.file, prefs.preferredFlavor,
      PingAutoSendAccessibilityService.isEnabled(application), boomerangReturn = true,
      mimeType = audio.mimeType, recipientName = contact.displayName, returnPackage = floatingReturnPackage)
    if (result is DispatchResult.Error) {
      retainDecisionRequest(result.message)
      floatingState.value = floatingState.value.copy(needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else {
      pendingFloatingAudio = null // FileProvider/WhatsApp owns the shared file from here.
      floatingState.value = floatingState.value.copy(needsGroqKey = false)
      onDispatched()
    }
  }

  fun openFloatingInstinct(onDispatched: () -> Unit) {
    val state = floatingState.value
    if (state.mode == FloatingMode.SENDING) return
    val app = application
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

  fun sendFloatingText(onDispatched: () -> Unit) {
    if (floatingState.value.mode == FloatingMode.SENDING || floatingState.value.text.isBlank()) return
    stopFloatingCapture(discard = true)
    val text = floatingState.value.text
    val generation = ++floatingGeneration
    floatingPaused = false
    floatingJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
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
    val result = WhatsAppDispatcher.sendWhatsAppText(application, contact.phoneNumber,
      payload, prefs.preferredFlavor, PingAutoSendAccessibilityService.isEnabled(application), contact.displayName, floatingReturnPackage)
    if (result is DispatchResult.Error) {
      floatingState.value = floatingState.value.copy(mode = failureMode, error = result.message,
        needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else {
      pendingFloatingAudio?.file?.delete()
      pendingFloatingAudio = null; pendingTranscript = null
      onDispatched()
    }
  }
}
