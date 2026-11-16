package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import com.example.testsupport.ConnectedAccessibility
import com.example.data.AssistantPreferencesRepository
import org.robolectric.Shadows.shadowOf
import org.junit.Before
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.speech.AudioTranscriber
import com.example.speech.TranscriptionException
import com.example.ui.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingPhoneRoutingTest {
  @Before fun clearStopwatch() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("local_stopwatch", 0).edit().clear().commit()
  }
  @Test fun generalVoiceSendsOriginalAudioRatherThanTranscript() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val dir = File(app.cacheDir, "voice_notes").apply { mkdirs() }
    val audio = File.createTempFile("route-audio", ".m4a", dir).apply { writeText("original-audio-bytes") }
    val disconnect = ConnectedAccessibility.start(app)
    try {
      withContext(Dispatchers.IO) { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
      shadowOf(app.packageManager).installPackage(PackageInfo().apply {
        packageName = "com.whatsapp"
        applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
      })
      var dispatched = 0
      val capture = Capture(audio)
      val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "ayudame a estudiar" })
      vm.startFloatingListening { dispatched++ }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio { dispatched++ }; advanceUntilIdle()
      assertEquals(1, dispatched)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals(Intent.ACTION_SEND, intent.action)
      assertEquals("audio/mp4", intent.type)
      val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
      assertEquals("original-audio-bytes", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
      vm.sendFloatingAudio { dispatched++ }
      runCurrent()
      assertEquals(1, dispatched)
      vm.dismissFloatingAssistant()
    } finally { disconnect(); audio.delete(); Dispatchers.resetMain() }
  }

  private class Capture(val file: File) : FloatingAudioCapture {
    override var snapshot = FloatingVoiceCapture.Snapshot()
    override fun start() = Result.success(Unit)
    override fun finish() = RecordedAudio(file, "audio/mp4")
    override fun cancel() {}
  }
  @Test fun voiceLocalRequestWorksWithoutPinnedContact() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "iniciá el cronómetro" })
      vm.startFloatingListening { fail("Must not dispatch") }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent()
      vm.sendFloatingAudio { fail("Must not dispatch") }
      advanceUntilIdle()
      assertEquals(FloatingMode.IDLE, vm.floatingAssistant.value.mode)
      assertTrue(vm.floatingAssistant.value.stopwatchRunning)
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun rejectedLocalTextKeepsDraft() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
      vm.updateFloatingText("timer de diez minutos")
      vm.sendFloatingText { fail("No clock installed") }
      assertEquals("timer de diez minutos", vm.floatingAssistant.value.text)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertNotNull(vm.floatingAssistant.value.error)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }
  @Test fun rejectedLocalAudioKeepsOriginalForRetry() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "timer de diez minutos" })
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio { fail("Must not dispatch") }; advanceUntilIdle()
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      assertEquals("original", audio.readText())
      vm.runFloatingStopwatch(com.example.phone.StopwatchOp.START)
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      assertEquals("original", audio.readText())
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun pauseRejectsLateUncooperativeTranscriptionAndRetryReusesAudio() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val release = CompletableDeferred<Unit>()
      var calls = 0
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber {
        calls++
        if (calls == 1) withContext(NonCancellable) { release.await() }
        "iniciá el cronómetro"
      })
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio {}; runCurrent()
      vm.pauseFloatingAssistant(); release.complete(Unit); advanceUntilIdle()
      assertFalse(vm.floatingAssistant.value.stopwatchRunning)
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      assertTrue(audio.exists())
      vm.resumeFloatingAssistant(); vm.sendFloatingAudio {}; advanceUntilIdle()
      assertEquals(2, calls)
      assertTrue(vm.floatingAssistant.value.stopwatchRunning)
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun missingKeyRetainsAudioForRetry() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { throw TranscriptionException("Configure", true) })
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio { fail("Must not dispatch") }; advanceUntilIdle()
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      assertTrue(vm.floatingAssistant.value.needsGroqKey)
      assertEquals("original", audio.readText())
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun closingDuringTranscriptionCannotExecuteLateResult() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val answer = CompletableDeferred<String>()
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { answer.await() })
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio {}; runCurrent(); vm.dismissFloatingAssistant()
      answer.complete("iniciá el cronómetro"); advanceUntilIdle()
      assertFalse(vm.floatingAssistant.value.stopwatchRunning)
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
}
