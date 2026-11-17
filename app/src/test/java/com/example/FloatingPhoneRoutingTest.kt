package com.example

import com.example.testsupport.localActionDecisions

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import com.example.testsupport.ConnectedAccessibility
import com.example.testsupport.installGoogleClock
import com.example.data.AssistantPreferencesRepository
import org.robolectric.Shadows.shadowOf
import org.junit.Before
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
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
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingPhoneRoutingTest {
  @Before fun installClock() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    installGoogleClock(app, listOf("com.android.deskclock.action.START_STOPWATCH"))
    // Each test has a fresh cacheDir; attach the provider to refresh its cached roots.
    Robolectric.buildContentProvider(FileProvider::class.java).create()
  }
  @Test fun nativeTextHandsOffOnceAndCompletionIsConsumedOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    try {
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("iniciá el cronómetro")
      vm.sendFloatingText { fail("Phone request must not send WhatsApp") }
      assertEquals("com.android.deskclock.action.START_STOPWATCH", shadowOf(app).nextStartedActivity.action)
      assertEquals(FloatingMode.IDLE, vm.floatingAssistant.value.mode)
      assertEquals("", vm.floatingAssistant.value.text)
      val completion = vm.floatingAssistant.value.phoneActionCompletion
      assertNotNull(completion)
      assertEquals(completion, vm.consumePhoneActionCompletion())
      assertNull(vm.consumePhoneActionCompletion())
      vm.sendFloatingText { fail("Consumed request cannot dispatch twice") }
      assertNull(shadowOf(app).nextStartedActivity)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

  @Test fun unsupportedNativePausePreservesDraftAndRequestsAccessibility() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    try {
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("pausá el cronómetro")
      vm.sendFloatingText { fail("Phone request must not send WhatsApp") }
      assertEquals("pausá el cronómetro", vm.floatingAssistant.value.text)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertNotNull(vm.floatingAssistant.value.error)
      assertNull(vm.consumePhoneActionCompletion())
      assertNull(shadowOf(app).nextStartedActivity)
      assertTrue(vm.floatingAssistant.value.needsClockAccessibility)
      assertTrue(vm.floatingAssistant.value.appOptions.isEmpty())
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

  @Test fun nativeResetStaysOpenUntilConfirmedAndCancelDoesNotLaunchClock() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    installGoogleClock(app, listOf("com.android.deskclock.action.RESET_STOPWATCH"))
    try {
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("reiniciá el cronómetro")
      vm.sendFloatingText { fail("Phone request must not send WhatsApp") }
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      assertNull(vm.consumePhoneActionCompletion())
      assertNull(shadowOf(app).nextStartedActivity)
      vm.confirmFloatingAction(false)
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertNull(vm.consumePhoneActionCompletion())
      assertNull(shadowOf(app).nextStartedActivity)
      vm.updateFloatingText("reiniciá el cronómetro")
      vm.sendFloatingText { fail("Phone request must not send WhatsApp") }
      vm.confirmFloatingAction(true)
      assertEquals("com.android.deskclock.action.RESET_STOPWATCH", shadowOf(app).nextStartedActivity.action)
      assertNotNull(vm.consumePhoneActionCompletion())
      vm.confirmFloatingAction(true)
      assertNull(shadowOf(app).nextStartedActivity)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }

  @Test fun volumeChangesTheNativeMusicStreamAndCompletesWithoutAnotherScreen() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    try {
      val audio = app.getSystemService(android.media.AudioManager::class.java)
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("poné el volumen al cincuenta por ciento")
      vm.sendFloatingText { fail("Phone request must not send WhatsApp") }
      assertEquals(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) / 2,
        audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
      assertNotNull(vm.consumePhoneActionCompletion())
      assertNull(shadowOf(app).nextStartedActivity)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
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
      val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "ayudame a estudiar" }, hybridRouter = localActionDecisions())
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
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "iniciá el cronómetro" }, hybridRouter = localActionDecisions())
      vm.startFloatingListening { fail("Must not dispatch") }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent()
      vm.sendFloatingAudio { fail("Must not dispatch") }
      advanceUntilIdle()
      assertEquals(FloatingMode.IDLE, vm.floatingAssistant.value.mode)
      assertEquals("com.android.deskclock.action.START_STOPWATCH", shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity.action)
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun reportedVoicePhraseReachesTorchPermissionInsteadOfOnlyShowingTranscript() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA)
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "quiero que enciendas la linterna" }, hybridRouter = localActionDecisions())
      vm.startFloatingListening { fail("Phone action must not reach WhatsApp") }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio { fail("Phone action must not reach WhatsApp") }; advanceUntilIdle()
      assertEquals("quiero que enciendas la linterna", vm.floatingAssistant.value.transcript)
      assertTrue(vm.floatingAssistant.value.needsCamera)
      assertNull(vm.consumePhoneActionCompletion())
      assertTrue(vm.floatingAssistant.value.status.orEmpty().contains("cámara"))
      vm.onFloatingCameraPermission(false)
      assertFalse(vm.floatingAssistant.value.needsCamera)
      assertNotNull(vm.floatingAssistant.value.error)
      assertEquals("original", audio.readText())
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun rejectedLocalTextKeepsDraft() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), hybridRouter = localActionDecisions())
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
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "timer de diez minutos" }, hybridRouter = localActionDecisions())
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio { fail("Must not dispatch") }; advanceUntilIdle()
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      assertEquals("original", audio.readText())
      vm.sendFloatingAudio { fail("Rejected local request must not dispatch") }; advanceUntilIdle()
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
      }, hybridRouter = localActionDecisions())
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio {}; runCurrent()
      vm.pauseFloatingAssistant(); release.complete(Unit); advanceUntilIdle()
      assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      assertTrue(audio.exists())
      vm.resumeFloatingAssistant(); vm.sendFloatingAudio {}; advanceUntilIdle()
      assertEquals(2, calls)
      assertEquals("com.android.deskclock.action.START_STOPWATCH", shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity.action)
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun missingKeyRetainsAudioForRetry() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audio = File.createTempFile("route-audio", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { throw TranscriptionException("Configure", true) }, hybridRouter = localActionDecisions())
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
      val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), capture, StandardTestDispatcher(testScheduler), AudioTranscriber { answer.await() }, hybridRouter = localActionDecisions())
      vm.startFloatingListening {}
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio {}; runCurrent(); vm.dismissFloatingAssistant()
      answer.complete("iniciá el cronómetro"); advanceUntilIdle()
      assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
}
