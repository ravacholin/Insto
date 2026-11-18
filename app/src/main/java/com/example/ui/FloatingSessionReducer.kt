package com.example.ui

import com.example.audio.RecordedAudio
import com.example.data.AssistantPreferences
import com.example.phone.DraftPlan
import com.example.phone.InterpretationRequest
import com.example.phone.PhoneCommand
import java.util.Calendar

internal data class PendingQuery(val text: String, val sessionId: Long)
internal data class PendingPermission(val command: PhoneCommand, val sessionId: Long)

internal data class FloatingSessionData(
  val id: Long = 0,
  val ui: FloatingAssistantState = FloatingAssistantState(),
  val work: FloatingWork? = null,
  val paused: Boolean = false,
  val closed: Boolean = false,
  val returnPackage: String? = null,
  val preferences: AssistantPreferences? = null,
  val audio: RecordedAudio? = null,
  val transcript: String? = null,
  val learningPreview: InterpretationRequest? = null,
  val learnedCommandConfirmation: Boolean = false,
  val semanticDraft: DraftPlan? = null,
  val pendingQuery: PendingQuery? = null,
  val approvedQueryText: String? = null,
  val previewInvalidatedByEdit: Boolean = false,
  val workflowLoadError: String? = null,
  val clarification: String? = null,
  val clarificationRequest: Pair<String, Calendar>? = null,
  val confirmation: PhoneCommand? = null,
  val cameraCommand: PendingPermission? = null,
  val contactsCommand: PendingPermission? = null,
  val calendarQuery: PendingPermission? = null,
  val appChoiceCommand: PhoneCommand? = null,
  val notificationsCommand: PendingPermission? = null,
  val retainLocalAudio: Boolean = false,
  val routedRequestText: String = "",
  val retryLocalCommand: Pair<PhoneCommand, Boolean>? = null,
  val retryLocalText: String? = null,
)

internal enum class FloatingPermission { CAMERA, CONTACTS, CALENDAR, NOTIFICATIONS }
internal enum class FloatingChoice { APP, CONTACT }
internal enum class FloatingWork { RECORD, FINALIZE, TRANSCRIBE, INTERPRET, PHONE_COMMAND, DISPATCH }

internal sealed interface FloatingSessionState {
  val data: FloatingSessionData
  data class Idle(override val data: FloatingSessionData = FloatingSessionData()) : FloatingSessionState
  data class Listening(override val data: FloatingSessionData) : FloatingSessionState
  data class Finalizing(override val data: FloatingSessionData) : FloatingSessionState
  data class Transcribing(override val data: FloatingSessionData) : FloatingSessionState
  data class Interpreting(override val data: FloatingSessionData) : FloatingSessionState
  data class AwaitingClarification(override val data: FloatingSessionData) : FloatingSessionState
  data class AwaitingConfirmation(override val data: FloatingSessionData) : FloatingSessionState
  data class AwaitingPermission(override val data: FloatingSessionData, val permission: FloatingPermission) : FloatingSessionState
  data class AwaitingChoice(override val data: FloatingSessionData, val choice: FloatingChoice) : FloatingSessionState
  data class RunningWorkflow(override val data: FloatingSessionData) : FloatingSessionState
  data class Dispatching(override val data: FloatingSessionData) : FloatingSessionState
  data class Retained(override val data: FloatingSessionData) : FloatingSessionState
  data class Done(override val data: FloatingSessionData) : FloatingSessionState
}

internal sealed interface FloatingSessionEvent {
  data class Update(val data: FloatingSessionData) : FloatingSessionEvent
  data object Invalidate : FloatingSessionEvent
  data object Edit : FloatingSessionEvent
  data object Close : FloatingSessionEvent
  data class Pause(val invalidate: Boolean) : FloatingSessionEvent
  data object Resume : FloatingSessionEvent
  data class StartWork(val work: FloatingWork) : FloatingSessionEvent
  data class Result(val sessionId: Long, val active: Boolean = true, val allowPaused: Boolean = false) : FloatingSessionEvent
  data class CapturedAudio(val sessionId: Long, val audio: RecordedAudio?) : FloatingSessionEvent
  data object ResolveTranscript : FloatingSessionEvent
  data class Transcribed(val sessionId: Long, val transcript: String, val active: Boolean) : FloatingSessionEvent
  data class PermissionResult(val request: PendingPermission, val granted: Boolean) : FloatingSessionEvent
}

internal sealed interface FloatingSessionEffect {
  val sessionId: Long
  data class Run(override val sessionId: Long, val work: FloatingWork) : FloatingSessionEffect
  data class DiscardAudio(override val sessionId: Long, val audio: RecordedAudio) : FloatingSessionEffect
  data class UseTranscript(override val sessionId: Long, val transcript: String) : FloatingSessionEffect
  data class Deliver(override val sessionId: Long) : FloatingSessionEffect
  data class PermissionGranted(override val sessionId: Long, val command: PhoneCommand) : FloatingSessionEffect
  data class PermissionDenied(override val sessionId: Long) : FloatingSessionEffect
}

internal data class FloatingSessionTransition(
  val state: FloatingSessionState,
  val effect: FloatingSessionEffect? = null,
)

internal object FloatingSessionReducer {
  fun reduce(state: FloatingSessionState, event: FloatingSessionEvent): FloatingSessionTransition {
    val data = state.data
    return when (event) {
      is FloatingSessionEvent.Update -> transition(event.data)
      FloatingSessionEvent.Invalidate -> transition(data.copy(id = data.id + 1, work = null, closed = false))
      FloatingSessionEvent.Edit -> transition(data.copy(id = data.id + 1, work = null, closed = false,
        cameraCommand = null, calendarQuery = null, contactsCommand = null, notificationsCommand = null,
        ui = data.ui.copy(needsCamera = false, needsCalendarRead = false, needsContacts = false, needsNotifications = false)))
      FloatingSessionEvent.Close -> transition(data.copy(id = data.id + 1, work = null, closed = true))
      is FloatingSessionEvent.Pause -> transition(data.copy(
        id = if (event.invalidate) data.id + 1 else data.id,
        work = if (event.invalidate) null else data.work,
        paused = true,
      ))
      FloatingSessionEvent.Resume -> transition(data.copy(paused = false, closed = false))
      is FloatingSessionEvent.StartWork ->
        transition(data.copy(work = event.work, closed = false), FloatingSessionEffect.Run(data.id, event.work))
      is FloatingSessionEvent.Result -> if (event.active && accepts(data, event.sessionId, event.allowPaused)
      ) FloatingSessionTransition(state, FloatingSessionEffect.Deliver(data.id))
      else FloatingSessionTransition(state)
      is FloatingSessionEvent.CapturedAudio -> when {
        accepts(data, event.sessionId, allowPaused = true) ->
          transition(data.copy(audio = event.audio, work = null), FloatingSessionEffect.Deliver(data.id))
        event.audio != null && event.audio != data.audio ->
          FloatingSessionTransition(state, FloatingSessionEffect.DiscardAudio(event.sessionId, event.audio))
        else -> FloatingSessionTransition(state)
      }
      FloatingSessionEvent.ResolveTranscript -> {
        val transcript = data.transcript
        if (transcript != null) FloatingSessionTransition(state, FloatingSessionEffect.UseTranscript(data.id, transcript))
        else transition(data.copy(work = FloatingWork.TRANSCRIBE), FloatingSessionEffect.Run(data.id, FloatingWork.TRANSCRIBE))
      }
      is FloatingSessionEvent.Transcribed -> {
        if (event.active && accepts(data, event.sessionId))
          transition(data.copy(transcript = event.transcript, work = null), FloatingSessionEffect.Deliver(data.id))
        else FloatingSessionTransition(state)
      }
      is FloatingSessionEvent.PermissionResult -> {
        if (data.closed || event.request.sessionId != data.id) FloatingSessionTransition(state)
        else FloatingSessionTransition(state, if (event.granted)
          FloatingSessionEffect.PermissionGranted(data.id, event.request.command)
        else FloatingSessionEffect.PermissionDenied(data.id))
      }
    }
  }

  private fun accepts(data: FloatingSessionData, sessionId: Long, allowPaused: Boolean = false): Boolean =
    !data.closed && sessionId == data.id && (!data.paused || allowPaused)

  private fun transition(data: FloatingSessionData, effect: FloatingSessionEffect? = null) =
    FloatingSessionTransition(phase(data), effect)

  private fun phase(data: FloatingSessionData): FloatingSessionState {
    val ui = data.ui
    return when {
      data.closed -> FloatingSessionState.Idle(data)
      data.work == FloatingWork.FINALIZE -> FloatingSessionState.Finalizing(data)
      ui.mode == FloatingMode.LISTENING || ui.mode == FloatingMode.RECORDING -> FloatingSessionState.Listening(data)
      ui.mode == FloatingMode.SENDING && data.work == FloatingWork.TRANSCRIBE -> FloatingSessionState.Transcribing(data)
      ui.mode == FloatingMode.SENDING && data.work == FloatingWork.INTERPRET -> FloatingSessionState.Interpreting(data)
      ui.needsCamera -> FloatingSessionState.AwaitingPermission(data, FloatingPermission.CAMERA)
      ui.needsContacts -> FloatingSessionState.AwaitingPermission(data, FloatingPermission.CONTACTS)
      ui.needsCalendarRead -> FloatingSessionState.AwaitingPermission(data, FloatingPermission.CALENDAR)
      ui.needsNotifications -> FloatingSessionState.AwaitingPermission(data, FloatingPermission.NOTIFICATIONS)
      ui.appOptions.isNotEmpty() -> FloatingSessionState.AwaitingChoice(data, FloatingChoice.APP)
      ui.contactOptions.isNotEmpty() -> FloatingSessionState.AwaitingChoice(data, FloatingChoice.CONTACT)
      ui.needsConfirmation -> FloatingSessionState.AwaitingConfirmation(data)
      ui.workflowActive -> FloatingSessionState.RunningWorkflow(data)
      ui.phoneActionCompletion != null -> FloatingSessionState.Done(data)
      ui.mode == FloatingMode.SENDING -> FloatingSessionState.Dispatching(data)
      ui.error != null || ui.mode == FloatingMode.AUDIO_READY || data.paused ->
        FloatingSessionState.Retained(data)
      data.clarification != null || data.semanticDraft != null -> FloatingSessionState.AwaitingClarification(data)
      else -> FloatingSessionState.Idle(data)
    }
  }
}
