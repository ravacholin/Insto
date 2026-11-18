package com.example.ui

import com.example.audio.RecordedAudio
import com.example.phone.PhoneCommand
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingSessionReducerTest {
  private val audio = RecordedAudio(File("synthetic.m4a"), "audio/mp4")
  private val idle = FloatingSessionState.Idle()

  @Test fun receivesTheUiTransitionsForCaptureAndCommandReview() {
    val cases = listOf(
      FloatingSessionData(ui = FloatingAssistantState(mode = FloatingMode.LISTENING)) to FloatingSessionState.Listening::class,
      FloatingSessionData(work = FloatingWork.FINALIZE) to FloatingSessionState.Finalizing::class,
      sending(FloatingWork.TRANSCRIBE) to FloatingSessionState.Transcribing::class,
      sending(FloatingWork.INTERPRET) to FloatingSessionState.Interpreting::class,
      FloatingSessionData(clarification = "intent:calendar") to FloatingSessionState.AwaitingClarification::class,
      FloatingSessionData(ui = FloatingAssistantState(needsConfirmation = true)) to FloatingSessionState.AwaitingConfirmation::class,
      FloatingSessionData(ui = FloatingAssistantState(needsCamera = true)) to FloatingSessionState.AwaitingPermission::class,
      FloatingSessionData(ui = FloatingAssistantState(workflowActive = true)) to FloatingSessionState.RunningWorkflow::class,
      sending(FloatingWork.DISPATCH) to FloatingSessionState.Dispatching::class,
      FloatingSessionData(audio = audio, ui = FloatingAssistantState(mode = FloatingMode.AUDIO_READY)) to FloatingSessionState.Retained::class,
      FloatingSessionData(ui = FloatingAssistantState(phoneActionCompletion = "Completado")) to FloatingSessionState.Done::class,
    )
    for ((data, type) in cases) {
      val result = reduce(idle, FloatingSessionEvent.Update(data))
      assertEquals(type, result.state::class)
    }
  }

  @Test fun pauseThenRetryReusesTranscriptAndIgnoresThePreviousResult() {
    val state = reduce(idle, FloatingSessionEvent.Update(sending(FloatingWork.INTERPRET).copy(
      audio = audio, transcript = "texto de prueba",
    ))).state
    val paused = reduce(state, FloatingSessionEvent.Pause(invalidate = true)).state
    assertNull(reduce(paused, FloatingSessionEvent.Result(state.data.id)).effect)
    assertEquals(audio, paused.data.audio)
    assertEquals("texto de prueba", paused.data.transcript)

    val resumed = reduce(paused, FloatingSessionEvent.Resume).state
    val retry = reduce(resumed, FloatingSessionEvent.ResolveTranscript)
    assertEquals(FloatingSessionEffect.UseTranscript(resumed.data.id, "texto de prueba"), retry.effect)
  }

  @Test fun closeAndEditingInvalidateLateResultsAndReleaseAnUnclaimedCapture() {
    val start = reduce(idle, FloatingSessionEvent.StartWork(FloatingWork.INTERPRET))
    assertEquals(FloatingSessionEffect.Run(idle.data.id, FloatingWork.INTERPRET), start.effect)
    for (event in listOf(FloatingSessionEvent.Edit, FloatingSessionEvent.Close)) {
      val changed = reduce(start.state, event).state
      assertNull(reduce(changed, FloatingSessionEvent.Result(idle.data.id)).effect)
      assertNull(reduce(changed, FloatingSessionEvent.Transcribed(idle.data.id, "obsoleto", true)).effect)
      val late = reduce(changed, FloatingSessionEvent.CapturedAudio(idle.data.id, audio))
      assertEquals(FloatingSessionEffect.DiscardAudio(idle.data.id, audio), late.effect)
      assertEquals(changed, late.state)
    }
  }

  @Test fun permissionsOnlyDeliverTheCurrentGrantOrDenial() {
    val command = PhoneCommand.Torch(true)
    val request = PendingPermission(command, idle.data.id)
    val waiting = reduce(idle, FloatingSessionEvent.Update(FloatingSessionData(
      cameraCommand = request, ui = FloatingAssistantState(needsCamera = true), audio = audio,
    ))).state
    assertTrue(waiting is FloatingSessionState.AwaitingPermission)
    assertEquals(FloatingSessionEffect.PermissionGranted(waiting.data.id, command),
      reduce(waiting, FloatingSessionEvent.PermissionResult(request, granted = true)).effect)
    val denial = reduce(waiting, FloatingSessionEvent.PermissionResult(request, granted = false))
    assertEquals(FloatingSessionEffect.PermissionDenied(waiting.data.id), denial.effect)
    assertEquals(audio, denial.state.data.audio)
    val edited = reduce(waiting, FloatingSessionEvent.Edit).state
    assertNull(reduce(edited, FloatingSessionEvent.PermissionResult(request, granted = true)).effect)
  }

  private fun reduce(state: FloatingSessionState, event: FloatingSessionEvent) = FloatingSessionReducer.reduce(state, event)

  private fun sending(work: FloatingWork) =
    FloatingSessionData(work = work, ui = FloatingAssistantState(mode = FloatingMode.SENDING))
}
