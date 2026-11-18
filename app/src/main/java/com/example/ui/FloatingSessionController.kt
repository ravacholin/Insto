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
import com.example.audio.FloatingAudioCapture
import com.example.audio.RecordedAudio
import com.example.audio.VoiceLatency
import com.example.audio.VoiceSessionPolicy
import com.example.context.SharedTextContext
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.data.LearnedPhraseStore
import com.example.whatsapp.DispatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class FloatingSessionController(
  private val application: Application,
  parentScope: CoroutineScope,
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
  private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
  private var session: FloatingSessionState = FloatingSessionState.Idle()
  private val data: FloatingSessionData get() = session.data
  private val floatingState = MutableStateFlow(data.ui)
  val floatingAssistant: StateFlow<FloatingAssistantState> = floatingState
  private var screen: FloatingAssistantState
    get() = data.ui
    set(value) { updateSession(data.copy(ui = value)) }

  private fun reduce(event: FloatingSessionEvent): FloatingSessionEffect? {
    val transition = FloatingSessionReducer.reduce(session, event)
    session = transition.state
    floatingState.value = session.data.ui
    return transition.effect
  }

  private fun updateSession(data: FloatingSessionData) {
    reduce(FloatingSessionEvent.Update(data))
  }

  private fun invalidate(): Long {
    reduce(FloatingSessionEvent.Invalidate)
    return data.id
  }

  private fun acceptResult(id: Long, active: Boolean = true, allowPaused: Boolean = true): Boolean =
    reduce(FloatingSessionEvent.Result(id, active, allowPaused)) is FloatingSessionEffect.Deliver

  private fun startWork(work: FloatingWork): Long =
    (reduce(FloatingSessionEvent.StartWork(work)) as FloatingSessionEffect.Run).sessionId

  private fun finishWork(work: FloatingWork) {
    if (data.work == work) updateSession(data.copy(work = null))
  }

  private var floatingJob: Job? = null
  private var cancelClockAutomation: (() -> Unit)? = null

  fun resumeFloatingAssistant() { reduce(FloatingSessionEvent.Resume) }
  private val phoneParser = PhoneCommandParser()
  private val phoneCommands = PhoneCommandRunner(application)
  private val instinct = InstinctDispatcher(application)
  private val conversation = ConversationSession()
  private val groqInterpreter = com.example.phone.GroqIntentVerifier({ GroqKeyStore(application).read() })
  private val geminiCoordinator = GeminiCoordinator(hybridRouter, structuredExtractor,
    verifier = groqInterpreter, fallback = groqFallback ?: groqInterpreter, lookupLearnedPhrase = learnedPhrases::lookup)
  private val workflowStore = WorkflowStore(application)
  private val workflow = WorkflowRunner(runCatching { workflowStore.read() }.getOrElse {
    updateSession(data.copy(workflowLoadError = it.message)); null
  }, workflowStore::write)
  private fun activeWorkflow() = workflow.snapshot?.takeUnless { it.finished }

  private fun syncWorkflow() {
    val snapshot = workflow.snapshot
    val active = activeWorkflow()
    val step = active?.steps?.getOrNull(active.cursor)
    screen = screen.copy(
      workflowLines = snapshot?.steps?.mapIndexed { index, item -> "${index + 1}. ${PhoneCommandCodec.summary(item.command)}${CapabilityRequirements.missing(application,item.command).takeIf {it.isNotEmpty()}?.joinToString(prefix=" · ",separator=" · ") ?: ""} · ${when(item.status){StepStatus.PENDING->"pendiente";StepStatus.EXECUTING->"en curso";StepStatus.COMPLETED->"completado";StepStatus.HANDED_OFF->"entregado · revisá la app";StepStatus.WAITING_INPUT->"requiere tu respuesta";StepStatus.FAILED->"falló";StepStatus.UNCERTAIN->"resultado incierto · revisá antes de seguir";StepStatus.CANCELLED->"cancelado"}}" } ?: emptyList(),
      workflowActive = active != null,
      needsConfirmation = active?.confirmed == false || data.confirmation != null,
      workflowCanContinue = step?.status in listOf(StepStatus.HANDED_OFF, StepStatus.UNCERTAIN) || active?.confirmed == true && step?.status == StepStatus.PENDING,
      workflowCanRetry = step?.status in listOf(StepStatus.FAILED, StepStatus.WAITING_INPUT),
    )
  }

  private fun previewWorkflow(commands: List<PhoneCommand>) {
    try {
      if (commands.size > 1) {
        val missing = commands.filterIsInstance<PhoneCommand.OpenApp>().firstOrNull { phoneCommands.matchingApps(it.name).isEmpty() }
        if (missing != null) {
          retainDecisionRequest("No encontré la aplicación «${missing.name}». Revisá ese paso antes de confirmar el plan.")
          return
        }
      }
      workflow.prepare(commands)
      if (data.semanticDraft == null && commands.size > 1) updateSession(data.copy(semanticDraft = runCatching { DraftPlan.fromCommands(commands) }.getOrNull()))
      updateSession(data.copy(previewInvalidatedByEdit = false))
      screen = screen.copy(mode = FloatingMode.IDLE, text = "", error = null,
        status = "Revisá los pasos. Podés corregir el pedido o decir dale para ejecutarlo.", phoneActionCompletion = null)
      updateSession(data.copy(retainLocalAudio = true))
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
    updateSession(data.copy(learningPreview = null))
    val hadWorkflow = activeWorkflow() != null
    invalidate()
    floatingJob?.cancel()
    cancelClockAutomation?.invoke()
    cancelClockAutomation = null
    workflow.cancel()
    conversation.clear()
    updateSession(data.copy(
      clarification = null,
      clarificationRequest = null,
      semanticDraft = null,
      pendingQuery = null,
      approvedQueryText = null,
      confirmation = null,
      cameraCommand = null,
      contactsCommand = null,
      notificationsCommand = null,
      calendarQuery = null,
    ))
    screen = screen.copy(mode = FloatingMode.IDLE, needsConfirmation = false,
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
    conversation.clear(); updateSession(data.copy(
      semanticDraft = null,
      clarification = null,
      clarificationRequest = null,
    ))
    screen = screen.copy(mode = FloatingMode.IDLE, text = "", error = null,
      status = "Secuencia finalizada. Las entregas a otras apps se revisan allí.",
      phoneActionCompletion = "Secuencia finalizada.")
    syncWorkflow()
  }

  fun onFloatingCalendarPermission(granted: Boolean) {
    val request = data.calendarQuery ?: return
    updateSession(data.copy(calendarQuery = null))
    val effect = reduce(FloatingSessionEvent.PermissionResult(request, granted)) ?: return
    screen = screen.copy(needsCalendarRead = false)
    when (effect) {
      is FloatingSessionEffect.PermissionGranted -> queryFloatingCalendar(effect.command as PhoneCommand.Platform)
      is FloatingSessionEffect.PermissionDenied -> retainDecisionRequest("Sin el permiso de lectura no pude consultar tu calendario.")
      else -> Unit
    }
  }

  private fun queryFloatingCalendar(command: PhoneCommand.Platform) {
    val app = application
    val generation = data.id
    if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      updateSession(data.copy(calendarQuery = PendingPermission(command, generation)))
      updateSession(data.copy(retainLocalAudio = true))
      screen = screen.copy(mode = FloatingMode.IDLE, needsCalendarRead = true,
        status = "Permití el acceso para consultar tu agenda.", error = null)
      return
    }
    if (app.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
      retainDecisionRequest("Desbloqueá el teléfono para consultar tu calendario.")
      return
    }
    screen = screen.copy(mode = FloatingMode.SENDING, status = "Consultando tu calendario…", error = null)
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
      if (!acceptResult(generation)) return@launch
      if (agenda == null) {
        retainDecisionRequest("No pude consultar el calendario. Revisá el permiso y volvé a intentarlo.")
      } else {
        updateSession(data.copy(retainLocalAudio = false))
        data.audio?.file?.delete()
        updateSession(data.copy(audio = null, transcript = null))
        screen = screen.copy(mode = FloatingMode.IDLE, text = "", status = agenda, error = null)
      }
    }
  }

  fun consumePhoneActionCompletion(): String? {
    val message = screen.phoneActionCompletion ?: return null
    screen = screen.copy(phoneActionCompletion = null)
    return message
  }

  fun confirmFloatingAction(accept: Boolean, onDispatched: () -> Unit = {}) {
    data.pendingQuery?.let { query ->
      updateSession(data.copy(pendingQuery = null))
      screen = screen.copy(needsConfirmation = false, queryPreview = false)
      if (!accept || !acceptResult(query.sessionId)) {
        screen = screen.copy(status = "Consulta cancelada.")
        return
      }
      updateSession(data.copy(approvedQueryText = query.text))
      if (data.audio != null) {
        screen = screen.copy(mode = FloatingMode.AUDIO_READY)
        sendFloatingAudio(onDispatched)
      } else {
        screen = screen.copy(mode = FloatingMode.TYPING, text = query.text)
        sendFloatingText(onDispatched)
      }
      return
    }
    val learning = data.learningPreview
    updateSession(data.copy(learningPreview = null))
    if (activeWorkflow()?.confirmed == false) {
      if (data.previewInvalidatedByEdit) { retainDecisionRequest("El pedido cambió. Enviá la corrección y revisá el nuevo plan antes de confirmar."); return }
      updateSession(data.copy(semanticDraft = null))
      if (accept) {
        workflow.confirm()
        advanceWorkflow(learning)
      } else cancelFloatingWorkflow()
      return
    }
    val command = data.confirmation ?: return
    updateSession(data.copy(learnedCommandConfirmation = false, confirmation = null))
    screen = screen.copy(needsConfirmation = false)
    if (accept) runPhoneCommand(command, confirmed = true, learning = learning)
    else if (activeWorkflow() != null) cancelFloatingWorkflow()
    else screen = screen.copy(status = "Pedido cancelado.")
  }

  fun chooseFloatingApp(option: AppOption) {
    if (option !in screen.appOptions || screen.mode == FloatingMode.SENDING) return
    screen = screen.copy(appOptions = emptyList())
    val media = data.appChoiceCommand as? PhoneCommand.Media
    val settings = (data.appChoiceCommand as? PhoneCommand.Platform)?.takeIf { it.action == PlatformAction.APP_SETTINGS }
    updateSession(data.copy(appChoiceCommand = null))
    runPhoneCommand(media?.copy(packageName = option.packageName) ?: settings?.copy(argument=option.packageName) ?: PhoneCommand.OpenApp(option.packageName))
  }

  fun onFloatingCameraPermission(granted: Boolean) {
    val request = data.cameraCommand ?: return
    updateSession(data.copy(cameraCommand = null))
    val effect = reduce(FloatingSessionEvent.PermissionResult(request, granted)) ?: return
    screen = screen.copy(needsCamera = false)
    when (effect) {
      is FloatingSessionEffect.PermissionGranted -> runPhoneCommand(effect.command)
      is FloatingSessionEffect.PermissionDenied -> screen = screen.copy(error = "No se habilitó la cámara. El pedido se conserva; la linterna no se cambió.", status = null)
      else -> Unit
    }
  }

  fun onFloatingNotificationsPermission(granted: Boolean) {
    val request = data.notificationsCommand ?: return
    updateSession(data.copy(notificationsCommand = null))
    val effect = reduce(FloatingSessionEvent.PermissionResult(request, granted)) ?: return
    screen = screen.copy(needsNotifications = false)
    when (effect) {
      is FloatingSessionEffect.PermissionGranted -> runPhoneCommand(effect.command)
      is FloatingSessionEffect.PermissionDenied -> screen = screen.copy(error="Sin notificaciones no se aplicó el modo temporal. Podés habilitarlas en Ajustes y reintentar.")
      else -> Unit
    }
  }

  fun onFloatingContactsPermission(granted: Boolean) {
    val request = data.contactsCommand ?: return
    updateSession(data.copy(contactsCommand = null))
    val effect = reduce(FloatingSessionEvent.PermissionResult(request, granted)) ?: return
    screen = screen.copy(needsContacts = false)
    when (effect) {
      is FloatingSessionEffect.PermissionGranted -> runPhoneCommand(effect.command)
      is FloatingSessionEffect.PermissionDenied -> screen = screen.copy(error = "No se habilitaron los contactos. El pedido se conserva; podés indicar el número.", status = null)
      else -> Unit
    }
  }

  fun chooseFloatingContact(option: ContactOption) {
    if (option !in screen.contactOptions || screen.mode == FloatingMode.SENDING) return
    screen = screen.copy(contactOptions = emptyList())
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
    updateSession(data.copy(
      clarificationRequest = null,
      clarification = null,
      confirmation = null,
      cameraCommand = null,
      contactsCommand = null,
      notificationsCommand = null,
    ))
    if (activeWorkflow() != null) try {workflow.beginReplacement(command)} catch (_: Exception) {
      retainDecisionRequest("No se pudo guardar el paso. No se ejecutó la acción."); return
    }
    val generation = startWork(FloatingWork.PHONE_COMMAND)
    val result = phoneCommands.runPhoneCommand(command, confirmed)
    if (!acceptResult(generation)) return
    if (result.stopwatchAutomation == null) finishWork(FloatingWork.PHONE_COMMAND)
    if (learning != null && result.confirmation == command) updateSession(data.copy(learningPreview = learning))
    applyPhoneActionResult(command, confirmed, result, learning)
    result.stopwatchAutomation?.let { operation ->
      screen = screen.copy(mode = FloatingMode.SENDING)
      cancelClockAutomation = phoneCommands.startStopwatch(operation) { completed ->
        if (acceptResult(generation)) {
          cancelClockAutomation = null
          finishWork(FloatingWork.PHONE_COMMAND)
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
    updateSession(phoneCommands.applyPhoneActionResult(data, command, confirmed, result, inWorkflow))
    if (inWorkflow) {
      syncWorkflow()
      if (workflow.snapshot?.finished == true) finishWorkflow()
      else if (result.outcome == PhoneActionOutcome.COMPLETED) advanceWorkflow()
    }
    if (!result.retainRequest && !inWorkflow) {
      data.audio?.file?.delete()
      updateSession(data.copy(audio = null, transcript = null))
    }
  }

  /** Returns false only when this is a general query for Instinct. */
  private suspend fun routeFloatingRequest(text: String, onDispatched: () -> Unit): Boolean {
    updateSession(data.copy(retainLocalAudio = false))
    val normalized = PhoneCommandParser.normalize(text)
    if (data.approvedQueryText == text) { updateSession(data.copy(approvedQueryText = null)); return false }
    data.pendingQuery?.let {
      if (normalized in listOf("si", "dale", "confirmar", "confirmo", "ok", "no", "cancelar")) {
        confirmFloatingAction(normalized !in listOf("no", "cancelar"), onDispatched); return true
      }
      updateSession(data.copy(pendingQuery = null))
      screen = screen.copy(queryPreview = false, needsConfirmation = false)
    }
    if (data.workflowLoadError != null) {
      if (normalized in listOf("cancelar", "descartar")) { workflow.clear(); updateSession(data.copy(workflowLoadError = null)) }
      else { retainDecisionRequest(data.workflowLoadError + " Decí cancelar para descartar el registro dañado."); return true }
    }
    if (normalized in listOf("cancelar", "cancela", "dejalo", "olvidalo") || normalized == "no" &&
      (activeWorkflow() != null || data.semanticDraft != null || data.clarification != null || data.confirmation != null ||
        screen.contactOptions.isNotEmpty() || screen.appOptions.isNotEmpty())) {
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
    if (data.confirmation != null && normalized in listOf("si", "si confirmo", "confirmo", "dale", "acepto", "no")) {
      confirmFloatingAction(normalized != "no"); return true
    }
    val local = phoneParser.parse(text)
    val searchQuery = phoneParser.chatGptSearchQuery(text, local)
    val fresh = searchQuery != null || local is PhoneRoute.Local || RequestSafetyPolicy.isQuestion(text) || text.trimStart().startsWith("¿") ||
      Regex("^(?:que|como|por que|donde|cual|instinct|explica|conta|ayuda|necesito|quiero|abri|busca|pone|agenda|llama)\\b").containsMatchIn(normalized)
    val contacts = screen.contactOptions
    if (contacts.isNotEmpty()) {
      val index = PhoneCommandParser.choiceIndex(normalized)
      val selected = index?.let(contacts::getOrNull) ?: contacts.singleOrNull {
        PhoneCommandParser.normalize(it.name) == normalized || PhoneNumbers.canonical(text)?.let { n -> n == PhoneNumbers.canonical(it.number) } == true
      }
      if (selected != null) { chooseFloatingContact(selected); return true }
      if (index != null || !fresh) { retainDecisionRequest("Elegí una de las opciones disponibles o decí cancelar."); return true }
    }
    val options = screen.appOptions
    if (options.isNotEmpty()) {
      val index = PhoneCommandParser.choiceIndex(normalized)
      val selected = index?.let(options::getOrNull) ?: options.singleOrNull { PhoneCommandParser.normalize(it.label) == normalized }
      if (selected != null) { chooseFloatingApp(selected); return true }
      if (index != null || !fresh) { retainDecisionRequest("Elegí una de las opciones disponibles o decí cancelar."); return true }
    }
    if (data.confirmation != null && !fresh) { retainDecisionRequest("Confirmá el pedido o decí cancelar."); return true }
    if (active?.confirmed == true) { retainDecisionRequest("Hay una secuencia en curso. Continuá o cancelala antes de iniciar otra acción."); return true }
    val retry = data.retryLocalCommand
    if (retry != null && data.retryLocalText == text) { runPhoneCommand(retry.first,retry.second); return true }
    updateSession(data.copy(retryLocalCommand = null, retryLocalText = null))
    if (searchQuery == null) RequestSafetyPolicy.rejection(text)?.let { retainDecisionRequest(it); return true }
    val correction = Regex("^(?:mejor|cambia|cambiale|que dure|durante|a las?|de la|el segundo|la segunda|el primero|la primera)\\b").containsMatchIn(normalized)
    if (searchQuery == null && data.semanticDraft != null && (local !is PhoneRoute.Local || correction)) {
      workflow.invalidatePreview(); syncWorkflow()
      val result = try { data.semanticDraft!!.reply(text, java.util.Calendar.getInstance()) } catch (_: Exception) {
        retainDecisionRequest("Ese dato no es válido. Conservé el pedido; corregilo antes de confirmar."); return true
      }
      if (result is InterpretationResult.Incomplete && result.draft.selectedStepId != null && hybridRouter.isEnabled()) {
        updateSession(data.copy(semanticDraft = result.draft)); return interpretWithGemini(text, PhoneRoute.Instinct, data.semanticDraft)
      }
      if (result != null) return applyInterpretation(result,text)
      if (!hybridRouter.isEnabled()) { retainDecisionRequest(data.semanticDraft!!.prompt()); return true }
      return interpretWithGemini(text,PhoneRoute.Instinct,data.semanticDraft)
    }
    val context = data.clarification
    if (context != null && !fresh || context == "intent:calendar" && correction) {
      if (context == "intent:calendar" || !context!!.startsWith("intent:")) {
        when (val reply = conversation.accept(text, allowSemantic=hybridRouter.isEnabled())) {
          is ConversationResult.Plan -> { previewWorkflow(reply.commands); return true }
          is ConversationResult.Prompt -> {
            workflow.invalidatePreview(); syncWorkflow(); updateSession(data.copy(retainLocalAudio = true))
            screen = screen.copy(mode=FloatingMode.IDLE,text="",status=reply.message,error=null); return true
          }
          is ConversationResult.Semantic -> { updateSession(data.copy(semanticDraft = reply.draft)); return interpretWithGemini(text,PhoneRoute.Instinct,reply.draft) }
          ConversationResult.Pass -> Unit
        }
      } else {
        val reply = if (context!!.startsWith("intent:")) hybridRouter.reply(context,text,data.clarificationRequest)
          else phoneParser.parseReply(context,text).takeUnless { it == PhoneRoute.Instinct }
        if (reply != null) return applyResolvedRoute(reply,text)
      }
    }
    updateSession(data.copy(learningPreview = null))
    // A new request has one classification gateway. Continuations above retain their selected intent.
    updateSession(data.copy(semanticDraft = null)); conversation.clear(); updateSession(data.copy(
      clarification = null,
      clarificationRequest = null,
      confirmation = null,
      cameraCommand = null,
      contactsCommand = null,
      notificationsCommand = null,
      appChoiceCommand = null,
      calendarQuery = null,
    ))
    workflow.invalidatePreview(); syncWorkflow()
    screen = screen.copy(needsConfirmation=false,appOptions=emptyList(),contactOptions=emptyList(),needsCamera=false,needsContacts=false,needsNotifications=false,needsCalendarRead=false)
    if (searchQuery != null) {
      openChatGptSearch(searchQuery, onDispatched)
      return true
    }
    updateSession(data.copy(routedRequestText = text))
    return interpretWithGemini(text,local,null)
  }

  private suspend fun forgetLastLearnedPhrase() {
    val generation = startWork(FloatingWork.INTERPRET)
    val job = kotlinx.coroutines.currentCoroutineContext()[Job]
    screen = screen.copy(mode = FloatingMode.SENDING, deciding = true)
    try {
      val removed = learnedPhrases.forgetLast { job?.isActive != false }
      if (acceptResult(generation, allowPaused = false)) {
        if (removed) {
          data.audio?.file?.delete()
          updateSession(data.copy(audio = null, transcript = null))
          screen = screen.copy(mode = FloatingMode.IDLE, text = "",
            status = "Olvidé la última frase aprendida que usaste.", error = null)
        } else retainDecisionRequest("Todavía no usaste ninguna frase aprendida.")
      }
    } catch (_: java.io.IOException) {
      if (acceptResult(generation, allowPaused = false))
        retainDecisionRequest("No pude borrar la frase. Volvé a intentarlo.")
    } finally {
      if (acceptResult(generation)) {
        finishWork(FloatingWork.INTERPRET)
        screen = screen.copy(deciding = false)
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
    data.audio?.file?.delete()
    updateSession(data.copy(audio = null, transcript = null))
    screen = screen.copy(mode = FloatingMode.SENDING, error = null, status = null)
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
    val generation = startWork(FloatingWork.INTERPRET)
    screen = screen.copy(mode = FloatingMode.SENDING, deciding = true, error = null)
    return try {
      val request = InterpretationRequest(text, draft, sourceContext = screen.sourceContext)
      val result = geminiCoordinator.interpret(request, local) { stage ->
        if (acceptResult(generation, allowPaused = false)) screen = screen.copy(status = stage)
      }
      if (!acceptResult(generation, kotlinx.coroutines.currentCoroutineContext().isActive, allowPaused = false)) true
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
        updateSession(data.copy(learnedCommandConfirmation = result is InterpretationResult.Direct && result.learned && data.confirmation != null))
        updateSession(data.copy(learningPreview = request.takeIf { canLearn && (data.confirmation != null || result is InterpretationResult.Plan && activeWorkflow()?.confirmed == false && !data.previewInvalidatedByEdit) }))
        handled
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      if (acceptResult(generation)) retainDecisionRequest("Interpretación cancelada. El pedido se conserva.")
      true
    } finally {
      if (acceptResult(generation)) {
        finishWork(FloatingWork.INTERPRET)
        screen = screen.copy(deciding = false)
      }
    }
  }

  private fun applyInterpretation(result: InterpretationResult, text: String): Boolean {
    when (result) {
      is InterpretationResult.Query -> {
        if (!result.needsConfirmation) return false
        updateSession(data.copy(pendingQuery = PendingQuery(text, data.id)))
        updateSession(data.copy(retainLocalAudio = true))
        screen = screen.copy(
          mode = if (data.audio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
          status = "Groq propone enviar esta consulta a Instinct. Confirmá para enviar.",
          needsConfirmation = true, queryPreview = true, error = null)
      }
      is InterpretationResult.Direct -> {
        updateSession(data.copy(semanticDraft = null)); conversation.clear(); updateSession(data.copy(clarification = null, clarificationRequest = null))
        updateSession(data.copy(routedRequestText = text))
        if (result.command is PhoneCommand.CalendarEvent) {
          conversation.accept(text)
          updateSession(data.copy(clarification = "intent:calendar")); updateSession(data.copy(clarificationRequest = text to java.util.Calendar.getInstance()))
          previewWorkflow(listOf(result.command))
        } else if (result.command is PhoneCommand.Platform && result.command.action == PlatformAction.CALENDAR_QUERY) {
          queryFloatingCalendar(result.command)
        } else runPhoneCommand(result.command)
      }
      is InterpretationResult.Clarification -> {
        updateSession(data.copy(clarification = result.context))
        if (data.clarificationRequest == null) updateSession(data.copy(clarificationRequest = text to java.util.Calendar.getInstance()))
        conversation.accept(text)
        updateSession(data.copy(retainLocalAudio = true))
        screen = screen.copy(mode=FloatingMode.IDLE,text="",status=result.prompt,error=null)
      }
      is InterpretationResult.Plan -> {
        updateSession(data.copy(clarification = null)); conversation.clear()
        updateSession(data.copy(semanticDraft = result.draft ?: runCatching { DraftPlan.fromCommands(result.commands) }.getOrNull()))
        previewWorkflow(result.commands)
        if (result.fallback) screen = screen.copy(status = "Revisá el pedido interpretado con Groq y confirmá.")
      }
      is InterpretationResult.Incomplete -> {
        updateSession(data.copy(semanticDraft = result.draft)); conversation.clear(); updateSession(data.copy(clarification = null))
        workflow.invalidatePreview(); syncWorkflow()
        updateSession(data.copy(retainLocalAudio = true))
        screen = screen.copy(mode = FloatingMode.IDLE, text = "", error = null,
          status = result.prompt)
      }
      is InterpretationResult.Rejected -> retainDecisionRequest(result.message)
      is InterpretationResult.Failure -> retainDecisionRequest(result.message)
    }
    return true
  }

  private fun retainDecisionRequest(message: String) {
    updateSession(data.copy(retainLocalAudio = true))
    screen = screen.copy(
      mode = if (data.audio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
      error = message, status = null)
  }

  fun prepareFloatingAssistant() {
    runCatching {TemporaryModeManager(application).reconcile()}
    val prefs = kotlinx.coroutines.runBlocking { repository.getCurrentPreferences() }
    updateSession(data.copy(preferences = prefs))
    updateSession(data.copy(returnPackage = com.example.assist.AutoSendCoordinator.lastKnownForegroundPackage))
    // Loading routing preferences during a send must not erase the active text/recording state.
    screen = screen.copy(contactName = data.preferences?.pinnedContact?.displayName ?: "Instinct")
    if (activeWorkflow() != null) {
      syncWorkflow()
      screen = screen.copy(status = "Hay una secuencia pendiente. Revisá los pasos antes de continuar.")
    }
    data.workflowLoadError?.let {screen = screen.copy(error = it)}
    val gemini = com.example.phone.GeminiSettings(application)
    screen = screen.copy(geminiConfigured = gemini.readKey() != null, geminiEnabled = gemini.isEnabled())
  }

  fun setFloatingContext(context: SharedTextContext?, error: String? = null) {
    prepareFloatingAssistant()
    if (activeWorkflow()?.confirmed == true || screen.mode == FloatingMode.SENDING && data.work != FloatingWork.INTERPRET && data.work != FloatingWork.TRANSCRIBE) {
      screen = screen.copy(error = "Terminá o cancelá el pedido actual antes de cambiar el contexto.")
      return
    }
    enterFloatingTextMode() // Invalidates generations, pending queries, audio and unconfirmed plans.
    updateSession(data.copy(semanticDraft = null)); conversation.clear(); updateSession(data.copy(
      clarification = null,
      clarificationRequest = null,
      confirmation = null,
      retryLocalCommand = null,
      retryLocalText = null,
      cameraCommand = null,
      contactsCommand = null,
      notificationsCommand = null,
      appChoiceCommand = null,
    ))
    screen = screen.copy(sourceContext = context, attachment = if (context != null || error != null) null else screen.attachment,
      text = if (context != null) "" else screen.text,
      error = error, status = null, needsConfirmation = false, contactOptions = emptyList(), appOptions = emptyList(),
      needsCamera = false, needsContacts = false, needsNotifications = false, needsCalendarRead = false)
  }

  fun startFloatingListening(onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    if (screen.mode == FloatingMode.SENDING || screen.attachment != null) return
    invalidate()
    updateSession(data.copy(pendingQuery = null, approvedQueryText = null))
    screen = screen.copy(queryPreview = false)
    stopFloatingCapture(discard = true)
    data.audio?.file?.delete()
    updateSession(data.copy(audio = null, transcript = null))
    val sessionId = startWork(FloatingWork.RECORD)
    val result = floatingCapture.start()
    if (result.isFailure) {
      finishWork(FloatingWork.RECORD)
      screen = screen.copy(mode = FloatingMode.IDLE,
        error = "Micrófono no disponible. Podés escribir o volver a intentarlo.")
      return
    }
    screen = screen.copy(mode = FloatingMode.LISTENING, text = "", elapsedMs = 0, error = null, needsGroqKey = false, transcript = null, voiceLatency = null, needsWhatsAppAccessibility = false)
    floatingJob = scope.launch {
      while (acceptResult(sessionId, isActive, allowPaused = false)) {
        val sample = floatingCapture.snapshot
        if (sample.error != null) {
          pauseFloatingAssistant(sample.error)
          return@launch
        }
        when (sample.state) {
          VoiceSessionPolicy.State.IDLE -> {
            stopFloatingCapture(discard = true)
            screen = screen.copy(mode = FloatingMode.IDLE, amplitude = 0f)
            return@launch
          }
          VoiceSessionPolicy.State.SEND -> {
            sendFloatingAudio(onDispatched)
            return@launch
          }
          else -> screen = screen.copy(
            mode = if (sample.state == VoiceSessionPolicy.State.RECORDING) FloatingMode.RECORDING else FloatingMode.LISTENING,
            elapsedMs = sample.elapsedMs, amplitude = sample.amplitude)
        }
        delay(40)
      }
    }
  }

  /** Called on focus, before the keyboard appears, not just after the first character. */
  fun enterFloatingTextMode() {
    if (screen.mode == FloatingMode.SENDING && data.work != FloatingWork.INTERPRET && data.work != FloatingWork.TRANSCRIBE) return
    invalidatePreviewForEditing()
    if (data.pendingQuery != null) {
      updateSession(data.copy(pendingQuery = null, approvedQueryText = null))
      screen = screen.copy(queryPreview = false, needsConfirmation = false)
    }
    reduce(FloatingSessionEvent.Edit)
    stopFloatingCapture(discard = true)
    data.audio?.file?.delete()
    updateSession(data.copy(audio = null, transcript = null))
    screen = screen.copy(mode = FloatingMode.TYPING, deciding = false, amplitude = 0f, error = null, status = null, needsWhatsAppAccessibility = false)
  }

  fun updateFloatingText(text: String) {
    if (text != screen.text) invalidatePreviewForEditing()
    if (data.pendingQuery != null && text != data.pendingQuery?.text) {
      updateSession(data.copy(pendingQuery = null, approvedQueryText = null))
      screen = screen.copy(needsConfirmation = false, queryPreview = false)
    }
    if (screen.mode == FloatingMode.SENDING && data.work != FloatingWork.INTERPRET && data.work != FloatingWork.TRANSCRIBE) return
    if (screen.mode != FloatingMode.TYPING) enterFloatingTextMode()
    else if (text != screen.text) reduce(FloatingSessionEvent.Edit)
    screen = screen.copy(text = text)
  }

  private fun invalidatePreviewForEditing() {
    if ((data.learnedCommandConfirmation || data.learningPreview != null) && activeWorkflow() == null && data.confirmation != null) {
      updateSession(data.copy(confirmation = null))
      screen = screen.copy(needsConfirmation = false)
    }
    updateSession(data.copy(learnedCommandConfirmation = false, learningPreview = null))
    if (activeWorkflow()?.confirmed != false) return
    updateSession(data.copy(previewInvalidatedByEdit = true))
    runCatching { workflow.invalidatePreview() }.onFailure {
      screen = screen.copy(error = "No se pudo guardar la edición. El plan anterior no se ejecutará.")
    }
    syncWorkflow()
    screen = screen.copy(needsConfirmation = false)
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
      screen = screen.copy(attachment = it, error = null)
    }.onFailure {
      screen = screen.copy(error = "No se pudo abrir el archivo. Elegí otro adjunto.")
    }
  }

  fun removeFloatingAttachment() {
    if (screen.mode != FloatingMode.SENDING)
      screen = screen.copy(attachment = null)
  }

  fun sendFloatingAttachment(onDispatched: () -> Unit) {
    if (screen.mode == FloatingMode.SENDING) return
    val attachment = screen.attachment ?: return
    prepareFloatingAssistant()
    val contact = data.preferences?.pinnedContact
    val prefs = data.preferences
    if (contact == null || prefs == null) {
      screen = screen.copy(error = "Configurá el contacto de Instinct para enviar el adjunto.")
      return
    }
    screen = screen.copy(mode = FloatingMode.SENDING, error = null)
    val sessionId = startWork(FloatingWork.DISPATCH)
    val result = instinct.attachment(attachment, screen.text, contact, prefs, data.returnPackage)
    if (!acceptResult(sessionId)) return
    finishWork(FloatingWork.DISPATCH)
    if (result is DispatchResult.Error) {
      screen = screen.copy(mode = FloatingMode.TYPING, error = result.message, needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else onDispatched()
  }

  private fun stopFloatingCapture(discard: Boolean) {
    check(discard) { "Use asynchronous finalization when retaining audio" }
    floatingJob?.cancel()
    floatingJob = null
    floatingCapture.cancel()
    finishWork(FloatingWork.RECORD)
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

  private fun acceptAudio(id: Long, audio: RecordedAudio?): Boolean =
    when (val effect = reduce(FloatingSessionEvent.CapturedAudio(id, audio))) {
      is FloatingSessionEffect.Deliver -> true
      is FloatingSessionEffect.DiscardAudio -> {
        effect.audio.file.delete()
        false
      }
      else -> false
    }

  fun pauseFloatingAssistant(error: String? = null) {
    val interpreting = data.work == FloatingWork.TRANSCRIBE || data.work == FloatingWork.INTERPRET
    reduce(FloatingSessionEvent.Pause(invalidate = interpreting))
    if (interpreting) {
      floatingJob?.cancel()
      floatingJob = null
      screen = screen.copy(
        mode = if (data.audio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING,
        deciding = false, status = "Pedido guardado. Podés reintentar cuando vuelvas.")
      return
    }
    if (screen.mode != FloatingMode.LISTENING && screen.mode != FloatingMode.RECORDING) return
    floatingJob?.cancel()
    floatingJob = null
    invalidate()
    val generation = startWork(FloatingWork.FINALIZE)
    screen = screen.copy(mode = FloatingMode.SENDING, amplitude = 0f)
    floatingJob = scope.launch {
      val audio = finishFloatingCapture()
      if (!acceptAudio(generation, audio)) return@launch
      screen = screen.copy(
        mode = if (audio != null) FloatingMode.AUDIO_READY else FloatingMode.IDLE,
        error = error ?: floatingCapture.snapshot.error)
    }
  }

  fun dismissFloatingAssistant() {
    updateSession(data.copy(learningPreview = null))
    cancelClockAutomation?.invoke()
    cancelClockAutomation = null
    reduce(FloatingSessionEvent.Close)
    stopFloatingCapture(discard = true)
    data.audio?.file?.delete()
    updateSession(data.copy(
      audio = null,
      transcript = null,
      notificationsCommand = null,
      calendarQuery = null,
      semanticDraft = null,
      pendingQuery = null,
      approvedQueryText = null,
    ))
    conversation.clear()
    // Explicit close cancels future steps; effects already handed off are never replayed.
    if (activeWorkflow() != null) workflow.cancel()
    updateSession(data.copy(
      clarification = null,
      confirmation = null,
      cameraCommand = null,
      contactsCommand = null,
    ))
    screen = screen.copy(sourceContext = null, queryPreview = false, needsNotifications = false, workflowActive = false, workflowCanContinue = false, workflowCanRetry = false, deciding = false, needsConfirmation = false, needsCamera = false, appOptions = emptyList(), needsContacts = false, needsCalendarRead = false, contactOptions = emptyList(), phoneActionCompletion = null)
  }

  fun sendFloatingAudio(onDispatched: () -> Unit) {
    if (screen.mode !in listOf(FloatingMode.RECORDING, FloatingMode.AUDIO_READY)) return
    floatingJob?.cancel()
    floatingJob = null
    invalidate()
    val generation = startWork(FloatingWork.FINALIZE)
    val sendStarted = monotonicClock()
    val previousLatency = screen.voiceLatency
    val endpoint = floatingCapture.snapshot.speechEndAtMs?.let { (sendStarted - it).coerceAtLeast(0) }
    val latency = if (data.audio != null) previousLatency ?: VoiceLatency() else VoiceLatency(endpointMs = endpoint)
    reduce(FloatingSessionEvent.Resume)
    screen = screen.copy(mode = FloatingMode.SENDING, error = null, status = "Interpretando tu audio…", needsGroqKey = false, voiceLatency = latency)
    floatingJob = scope.launch {
      val reusedAudio = data.audio != null
      val finalizationStarted = monotonicClock()
      val audio = data.audio ?: finishFloatingCapture()
      if (!acceptAudio(generation, audio)) return@launch
      if (audio == null) {
        screen = screen.copy(mode = FloatingMode.IDLE,
          error = floatingCapture.snapshot.error ?: "No se pudo finalizar el audio. Activá el micrófono para volver a grabar.")
        return@launch
      }
      if (!reusedAudio) updateVoiceLatency(latency.copy(finalizationMs = monotonicClock() - finalizationStarted))
      if (data.paused) {
        screen = screen.copy(mode = FloatingMode.AUDIO_READY, status = "Audio guardado.")
        return@launch
      }
      val transcription = reduce(FloatingSessionEvent.ResolveTranscript)
      val cached = transcription as? FloatingSessionEffect.UseTranscript
      val transcriptionStarted = monotonicClock()
      val transcript = try {
        cached?.transcript ?: audioTranscriber.transcribe(audio.file).also {
          require(it.isNotBlank()) { "La grabación no produjo una transcripción." }
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (acceptResult(generation)) {
          finishWork(FloatingWork.TRANSCRIBE)
          screen = screen.copy(mode = FloatingMode.AUDIO_READY,
            error = "La interpretación se interrumpió. El audio se conserva para reintentar.", status = null)
        }
        return@launch
      } catch (failure: Exception) {
        if (!acceptResult(generation, isActive, allowPaused = false)) return@launch
        finishWork(FloatingWork.TRANSCRIBE)
        val transcriptionError = failure as? TranscriptionException
        screen = screen.copy(needsGroqKey = transcriptionError?.needsKey == true)
        val state = screen
        if (state.sourceContext == null && activeWorkflow() == null && data.workflowLoadError == null &&
          data.semanticDraft == null && data.clarification == null && data.confirmation == null && data.pendingQuery == null &&
          data.retryLocalCommand == null && state.appOptions.isEmpty() && state.contactOptions.isEmpty() &&
          !state.needsCamera && !state.needsContacts && !state.needsNotifications && !state.needsCalendarRead) {
          dispatchFloatingAudio(audio, onDispatched)
        } else {
          retainDecisionRequest(transcriptionError?.message ?: "No se pudo interpretar. El audio se conserva.")
        }
        return@launch
      }
      if (reduce(FloatingSessionEvent.Transcribed(generation, transcript, isActive)) !is FloatingSessionEffect.Deliver) return@launch
      if (cached == null) updateVoiceLatency(screen.voiceLatency!!.copy(transcriptionMs = monotonicClock() - transcriptionStarted))
      screen = screen.copy(transcript = transcript)
      val interpretationStarted = monotonicClock()
      val handled = try { routeFloatingRequest(transcript, onDispatched) } finally {
        if (acceptResult(generation, isActive, allowPaused = false)) {
          updateVoiceLatency(screen.voiceLatency!!.copy(interpretationMs = monotonicClock() - interpretationStarted))
        }
      }
      if (!acceptResult(generation, isActive, allowPaused = false)) return@launch
      if (handled) {
        if (!data.retainLocalAudio) {
          audio.file.delete()
          updateSession(data.copy(audio = null, transcript = null))
        }
        return@launch
      }
      if (screen.sourceContext != null) {
        dispatchFloatingTextQuery(transcript, onDispatched)
        return@launch
      }
      dispatchFloatingAudio(audio, onDispatched)
    }
  }

  private fun dispatchFloatingAudio(audio: RecordedAudio, onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    val contact = data.preferences?.pinnedContact
    val prefs = data.preferences
    if (contact == null || prefs == null) {
      retainDecisionRequest("Configurá el contacto de Instinct antes de enviar el audio.")
      return
    }
    if (!instinct.hasTarget(prefs)) {
      retainDecisionRequest("Instalá WhatsApp para enviar. El audio se conserva.")
      return
    }
    screen = screen.copy(status = "Enviando el audio original a Instinct…")
    val sessionId = startWork(FloatingWork.DISPATCH)
    val result = instinct.audio(audio, contact, prefs, data.returnPackage)
    if (!acceptResult(sessionId)) return
    finishWork(FloatingWork.DISPATCH)
    if (result is DispatchResult.Error) {
      retainDecisionRequest(result.message)
      screen = screen.copy(needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else {
      updateSession(data.copy(audio = null)) // FileProvider/WhatsApp owns the shared file from here.
      screen = screen.copy(needsGroqKey = false)
      onDispatched()
    }
  }

  fun openFloatingInstinct(onDispatched: () -> Unit) {
    val state = screen
    if (state.mode == FloatingMode.SENDING) return
    val app = application
    val prefs = runBlockingPrefs(app)
    val contact = prefs.pinnedContact
    if (contact == null) {
      screen = state.copy(error = "Configurá el contacto de Instinct para abrir WhatsApp.")
      return
    }
    val sessionId = startWork(FloatingWork.DISPATCH)
    val result = instinct.open(state.text, contact, prefs)
    if (!acceptResult(sessionId)) return
    finishWork(FloatingWork.DISPATCH)
    if (result is DispatchResult.Error) {
      screen = state.copy(error = result.message)
      return
    }
    stopFloatingCapture(discard = true)
    data.audio?.file?.delete()
    updateSession(data.copy(audio = null, transcript = null))
    screen = state.copy(mode = FloatingMode.SENDING, error = null)
    onDispatched()
  }

  fun sendFloatingText(onDispatched: () -> Unit) {
    if (screen.mode == FloatingMode.SENDING || screen.text.isBlank()) return
    stopFloatingCapture(discard = true)
    val text = screen.text
    val generation = invalidate()
    reduce(FloatingSessionEvent.Resume)
    floatingJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
      if (routeFloatingRequest(text, onDispatched)) return@launch
      if (!acceptResult(generation, isActive, allowPaused = false)) return@launch
      dispatchFloatingTextQuery(text, onDispatched)
    }
  }


  private fun updateVoiceLatency(latency: VoiceLatency) {
    screen = screen.copy(voiceLatency = latency)
    if (com.example.BuildConfig.DEBUG) android.util.Log.d("InstoVoiceLatency", latency.summary())
  }

  private fun dispatchFloatingTextQuery(text: String, onDispatched: () -> Unit) {
    prepareFloatingAssistant()
    val failureMode = if (data.audio != null) FloatingMode.AUDIO_READY else FloatingMode.TYPING
    val contact = data.preferences?.pinnedContact
    val prefs = data.preferences
    if (contact == null || prefs == null) {
      screen = screen.copy(mode = failureMode,
        error = "Configurá el contacto de Instinct para enviar esta consulta.")
      return
    }
    val payload = screen.sourceContext?.query(text) ?: text
    screen = screen.copy(mode = FloatingMode.SENDING, error = null, status = "Enviando a Instinct…")
    val sessionId = startWork(FloatingWork.DISPATCH)
    val result = instinct.text(payload, contact, prefs, data.returnPackage)
    if (!acceptResult(sessionId)) return
    finishWork(FloatingWork.DISPATCH)
    if (result is DispatchResult.Error) {
      screen = screen.copy(mode = failureMode, error = result.message,
        needsWhatsAppAccessibility = result.message.contains("Accesibilidad"))
    } else {
      data.audio?.file?.delete()
      updateSession(data.copy(audio = null, transcript = null))
      onDispatched()
    }
  }

  fun close() {
    dismissFloatingAssistant()
    scope.cancel()
  }
}
