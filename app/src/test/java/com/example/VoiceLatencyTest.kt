package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.ui.MainViewModel
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
class VoiceLatencyTest {
  @Test fun tracksSilenceFinalizationTranscriptionAndInterpretationSeparately() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val file = File.createTempFile("voice-timing", ".m4a", app.cacheDir).apply { writeText("audio") }
    var codecTime = 0L
    val capture = object : FloatingAudioCapture {
      override val snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1500, .5f, speechEndAtMs = 100)
      override fun start() = Result.success(Unit)
      override fun finish(): RecordedAudio { codecTime += 50; return RecordedAudio(file, "audio/mp4") }
      override fun cancel() {}
    }
    val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { delay(200); "bajá un poquito el sonido" },
      HybridPhoneRouter({ true }, DecisionClassifier { delay(300); SemanticDecision("volume_down", .99, .99, .99, .99) }),
      monotonicClock = { 1000 + codecTime + testScheduler.currentTime })
    try {
      vm.startFloatingListening { fail("Native request") }; runCurrent()
      vm.sendFloatingAudio { fail("Native request") }; advanceUntilIdle()
      val latency = vm.floatingAssistant.value.voiceLatency!!
      assertEquals(900L, latency.endpointMs)
      assertEquals(50L, latency.finalizationMs)
      assertEquals(200L, latency.transcriptionMs)
      assertEquals(300L, latency.interpretationMs)
      assertEquals(1450L, latency.totalMs)
    } finally { vm.dismissFloatingAssistant(); file.delete(); Dispatchers.resetMain() }
  }
}
