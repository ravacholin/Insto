package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.ui.*
import com.example.data.AssistantPreferencesRepository
import com.example.testsupport.ConnectedAccessibility
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingHybridRoutingTest {
  @Test fun semanticLocalActionWorksWithoutWhatsAppAndCompletesOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    var calls = 0
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      calls++; SemanticDecision("volume_down", .99, .99, .99, .99)
    }))
    try {
      vm.updateFloatingText("bajá un poquito el sonido")
      vm.sendFloatingText { fail("Native action") }
      runCurrent()
      assertEquals(1, calls)
      assertNotNull(vm.consumePhoneActionCompletion())
      assertNull(vm.consumePhoneActionCompletion())
      assertEquals("", vm.floatingAssistant.value.text)
      vm.sendFloatingText { fail("Already completed") }
      assertEquals(1, calls)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun lowConfidenceForLiteralCommandForwardsExactOriginalTextWithoutExecuting() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val disconnect = ConnectedAccessibility.start(app)
    val audio = app.getSystemService(android.media.AudioManager::class.java)
    val before = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    var calls = 0
    val original = "¿Podés bajar el volumen, por favor?"
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { text ->
      calls++; assertEquals(original, text); SemanticDecision("volume_down", .60, .60, .99, .99)
    }))
    try {
      withContext(Dispatchers.IO) { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
      shadowOf(app.packageManager).installPackage(PackageInfo().apply {
        packageName = "com.whatsapp"
        applicationInfo = ApplicationInfo().apply {packageName="com.whatsapp"}
      })
      var dispatched = 0
      vm.updateFloatingText(original); vm.sendFloatingText {dispatched++}; runCurrent()
      assertEquals(1, calls)
      assertEquals(1, dispatched)
      assertEquals(before, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
      assertNull(vm.consumePhoneActionCompletion())
      assertEquals(original, shadowOf(app).nextStartedActivity.data!!.getQueryParameter("text"))
    } finally {vm.dismissFloatingAssistant();disconnect();Dispatchers.resetMain()}
  }

  @Test fun recognizedExecutionFailureNeverFallsBackToInstinct() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    var calls=0
    val vm = MainViewModel(app,hybridRouter=HybridPhoneRouter({true},DecisionClassifier {
      calls++; SemanticDecision("camera_photo",.99,.99,.99,.99)
    }))
    try {
      vm.updateFloatingText("abrí la cámara");vm.sendFloatingText {fail("Execution errors must stay local")}
      assertEquals(1,calls)
      assertNotNull(vm.floatingAssistant.value.error)
      assertEquals("abrí la cámara",vm.floatingAssistant.value.text)
      assertNull(vm.consumePhoneActionCompletion())
    } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
  }

  @Test fun editingFailedRequestRequiresClassificationEvenWhenOnlyPunctuationChanged() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app=ApplicationProvider.getApplicationContext<Application>()
    var calls=0
    val vm=MainViewModel(app,hybridRouter=HybridPhoneRouter({true},DecisionClassifier {calls++;SemanticDecision("app_open",.99,.99,.99,.99)}))
    try {
      vm.updateFloatingText("abrí AplicacionInexistente");vm.sendFloatingText {fail("No dispatch")}
      assertEquals(1,calls)
      vm.updateFloatingText("abrí AplicacionInexistente!");vm.sendFloatingText {fail("No dispatch")}
      assertEquals(2,calls)
    } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
  }

  @Test fun semanticQuerySharesTheOriginalAudioExactlyOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val directory = File(app.cacheDir, "voice_notes").apply { mkdirs() }
    val file = File.createTempFile("hybrid-query", ".m4a", directory).apply { writeText("original bytes") }
    val disconnect = ConnectedAccessibility.start(app)
    val capture = object : FloatingAudioCapture {
      override val snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1500, .5f)
      override fun start() = Result.success(Unit)
      override fun finish() = RecordedAudio(file, "audio/mp4")
      override fun cancel() {}
    }
    var decisions = 0
    var dispatched = 0
    val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "ayudame a estudiar" },
      HybridPhoneRouter({ true }, DecisionClassifier { decisions++; SemanticDecision("instinct", .99, .99, .99, .99) }))
    try {
      withContext(Dispatchers.IO) { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
      shadowOf(app.packageManager).installPackage(PackageInfo().apply {
        packageName = "com.whatsapp"
        applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
      })
      vm.startFloatingListening { dispatched++ }
      runCurrent()
      vm.sendFloatingAudio { dispatched++ }
      advanceUntilIdle()
      assertEquals(1, decisions)
      assertEquals(1, dispatched)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals(Intent.ACTION_SEND, intent.action)
      val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
      assertEquals("original bytes", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
      vm.sendFloatingAudio { dispatched++ }
      runCurrent()
      assertEquals(1, dispatched)
    } finally { vm.dismissFloatingAssistant(); disconnect(); file.delete(); Dispatchers.resetMain() }
  }

  @Test fun clarificationRepliesStayLocalEvenWithHybridEnabled() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    var calls = 0
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      calls++; SemanticDecision("alarm", .99, .99, .99, .99)
    }))
    try {
      vm.updateFloatingText("poné una alarma")
      vm.sendFloatingText { fail("No WhatsApp") }
      assertTrue(vm.floatingAssistant.value.status!!.contains("hora"))
      vm.updateFloatingText("a las siete de la mañana")
      vm.sendFloatingText { fail("No WhatsApp") }
      assertFalse(vm.floatingAssistant.value.status.orEmpty().contains("Gemini"))
      assertEquals(1, calls)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun textFailureRetainsDraftAndPauseCancelsWithoutDispatch() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    var calls = 0
    var cancelled = false
    val hybrid = HybridPhoneRouter({ true }, DecisionClassifier {
      calls++
      if (calls == 1) throw DecisionException("Gemini alcanzó su cuota.")
      try { awaitCancellation() } finally { cancelled = true }
    })
    val vm = MainViewModel(app, hybridRouter = hybrid)
    try {
      vm.updateFloatingText("iluminá acá")
      vm.sendFloatingText { fail("No dispatch") }
      runCurrent()
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertEquals("iluminá acá", vm.floatingAssistant.value.text)
      assertTrue(vm.floatingAssistant.value.error!!.contains("cuota"))
      vm.sendFloatingText { fail("No dispatch") }
      runCurrent()
      assertEquals(FloatingMode.SENDING, vm.floatingAssistant.value.mode)
      vm.pauseFloatingAssistant()
      runCurrent()
      assertTrue(cancelled)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertEquals("iluminá acá", vm.floatingAssistant.value.text)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun cancelledLateDecisionCannotOverwriteNewSession() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val answer = CompletableDeferred<Unit>()
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      withContext(NonCancellable) { answer.await() }
      SemanticDecision("instinct", .99, .99, .99, .99)
    }))
    try {
      vm.updateFloatingText("pedido desconocido")
      vm.sendFloatingText { fail("Stale decision") }
      runCurrent()
      assertTrue(vm.floatingAssistant.value.deciding)
      vm.updateFloatingText("nuevo borrador")
      answer.complete(Unit)
      runCurrent()
      assertEquals("nuevo borrador", vm.floatingAssistant.value.text)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertFalse(vm.floatingAssistant.value.deciding)
      assertNull(vm.consumePhoneActionCompletion())
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun audioRetryReusesOriginalRecordingAndTranscript() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val file = File.createTempFile("hybrid-original", ".m4a", app.cacheDir).apply { writeText("original audio") }
    var transcripts = 0
    var calls = 0
    val capture = object : FloatingAudioCapture {
      override val snapshot get() = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1500, .5f)
      override fun start() = Result.success(Unit)
      override fun finish() = RecordedAudio(file, "audio/mp4")
      override fun cancel() {}
    }
    val vm = MainViewModel(app, capture, UnconfinedTestDispatcher(testScheduler),
      AudioTranscriber { transcripts++; "pedido desconocido" },
      HybridPhoneRouter({ true }, DecisionClassifier { calls++; throw DecisionException("Error de conexión") }))
    try {
      vm.startFloatingListening { fail("No dispatch") }
      runCurrent()
      vm.sendFloatingAudio { fail("No dispatch") }
      runCurrent()
      assertEquals(FloatingMode.AUDIO_READY, vm.floatingAssistant.value.mode)
      vm.sendFloatingAudio { fail("No dispatch") }
      runCurrent()
      assertEquals(1, transcripts)
      assertEquals(2, calls)
      assertEquals("original audio", file.readText())
      assertNotNull(vm.floatingAssistant.value.error)
    } finally { vm.dismissFloatingAssistant(); file.delete(); Dispatchers.resetMain() }
  }
}
