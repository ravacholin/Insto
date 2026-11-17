package com.example

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.testsupport.ConnectedAccessibility
import com.example.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingAvailabilityRegressionTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  @Before fun attachProvider() { Robolectric.buildContentProvider(FileProvider::class.java).create() }

  @Test fun abrirSpotifyLaunchesTheInstalledAppWithoutWaitingForGemini() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val info = ActivityInfo().apply {
      packageName = "com.spotify.music"; name = "com.spotify.music.Main"; exported = true; enabled = true
      nonLocalizedLabel = "Spotify"
      applicationInfo = ApplicationInfo().apply { packageName = "com.spotify.music"; enabled = true; nonLocalizedLabel = "Spotify" }
    }
    val pm = shadowOf(app.packageManager)
    pm.installPackage(PackageInfo().apply { packageName = info.packageName; applicationInfo = info.applicationInfo })
    pm.addOrUpdateActivity(info)
    pm.addIntentFilterForActivity(ComponentName(info.packageName, info.name), IntentFilter(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_LAUNCHER)
    })
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier { awaitCancellation() }))
    try {
      vm.updateFloatingText("abrir spotify"); vm.sendFloatingText { fail("A native app request must not be sent") }; runCurrent()
      assertEquals("com.spotify.music", shadowOf(app).nextStartedActivity.component!!.packageName)
      assertNotNull(vm.consumePhoneActionCompletion())
      assertNull(vm.floatingAssistant.value.error)
      assertEquals(0, currentTime)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun prenderLinternaReachesAndroidPermissionInsteadOfConfidenceRejection() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("torch_on", .62, .62, .87, .87)
    }))
    try {
      vm.updateFloatingText("prender linterna"); vm.sendFloatingText { fail("Native request") }; runCurrent()
      assertTrue(vm.floatingAssistant.value.needsCamera)
      assertFalse(vm.floatingAssistant.value.status.orEmpty().contains("Gemini"))
      assertEquals("prender linterna", vm.floatingAssistant.value.text)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  private suspend fun configureInstinct() {
    withContext(Dispatchers.IO) { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
  }

  @Test fun googleQuotaFailureStillDispatchesTheOriginalTextToInstinctExactlyOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    configureInstinct()
    val disconnect = ConnectedAccessibility.start(app)
    var sent = 0
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      throw DecisionException("quota", GeminiFailureKind.QUOTA)
    }))
    try {
      vm.updateFloatingText("ayudame a estudiar"); vm.sendFloatingText { sent++ }; advanceUntilIdle()
      assertEquals(1, sent)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals("com.whatsapp", intent.`package`)
      assertEquals("ayudame a estudiar", intent.data!!.getQueryParameter("text"))
      vm.sendFloatingText { sent++ }; runCurrent()
      assertEquals(1, sent)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); disconnect(); Dispatchers.resetMain() }
  }

  @Test fun googleTimeoutStillSharesTheOriginalAudioToInstinctExactlyOnce() = assertAudioQueryDispatchesOnce(
    "ayudame a estudiar", DecisionClassifier { awaitCancellation() })

  @Test fun bocaQuestionSharesOriginalAudioWithoutAskingToRetryOrConfirm() = assertAudioQueryDispatchesOnce(
    "¿Cuándo es la final de Boca por la Copa Sudamericana?", DecisionClassifier { SemanticDecision("unsupported", .99, .99, .99, .99) })

  @Test fun unavailableDecisionsStillSharesAnUnmatchedAudioWithoutConfirmation() = assertAudioQueryDispatchesOnce(
    "ayudame a estudiar", DecisionClassifier { throw DecisionException("unavailable", GeminiFailureKind.UNAVAILABLE) },
    object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed boundary")
      override suspend fun interpret(request: InterpretationRequest) = InterpretationResult.Query()
    })

  private fun assertAudioQueryDispatchesOnce(transcript: String, classifier: DecisionClassifier,
    extractor: StructuredCommandExtractor = StructuredCommandExtractor { error("No extraction") }) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    configureInstinct()
    val disconnect = ConnectedAccessibility.start(app)
    val file = File.createTempFile("instinct_", ".m4a", File(app.cacheDir, "voice_notes").apply { mkdirs() })
      .apply { writeText("original audio bytes") }
    val capture = object : FloatingAudioCapture {
      override val snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1500, .5f)
      override fun start() = Result.success(Unit)
      override fun finish() = RecordedAudio(file, "audio/mp4")
      override fun cancel() {}
    }
    var sent = 0
    val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { transcript },
      HybridPhoneRouter({ true }, classifier), structuredExtractor = extractor)
    try {
      vm.startFloatingListening { sent++ }; runCurrent(); vm.sendFloatingAudio { sent++ }; advanceUntilIdle()
      assertEquals(1, sent)
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertFalse(vm.floatingAssistant.value.queryPreview)
      assertNull(vm.floatingAssistant.value.error)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals(Intent.ACTION_SEND, intent.action)
      val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
      assertEquals("original audio bytes", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
      vm.sendFloatingAudio { sent++ }; runCurrent()
      assertEquals(1, sent)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); disconnect(); file.delete(); Dispatchers.resetMain() }
  }
}
