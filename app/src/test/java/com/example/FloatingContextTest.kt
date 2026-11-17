package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.context.SharedTextContext
import com.example.data.AssistantPreferencesRepository
import com.example.phone.*
import com.example.speech.AudioTranscriber
import com.example.testsupport.ConnectedAccessibility
import com.example.ui.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingContextTest {
  private val reference = SharedTextContext("El documento dice: bajá el volumen", SharedTextContext.Source.SHARED)

  @Test fun newReferenceReplacesAPreviouslySelectedAttachment() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val provider = object : android.content.ContentProvider() {
      override fun onCreate() = true
      override fun getType(uri: android.net.Uri) = "text/plain"
      override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
        android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("anterior.txt")) }
      override fun openAssetFile(uri: android.net.Uri, mode: String) = android.content.res.AssetFileDescriptor(
        android.os.ParcelFileDescriptor.open(File.createTempFile("attachment", ".txt", app.cacheDir), android.os.ParcelFileDescriptor.MODE_READ_ONLY), 0, -1)
      override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
      override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
      override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("context-test", provider)
    val vm = MainViewModel(app)
    try {
      vm.selectFloatingAttachment(android.net.Uri.parse("content://context-test/anterior"))
      assertNotNull(vm.floatingAssistant.value.attachment)
      vm.setFloatingContext(reference)
      assertNull(vm.floatingAssistant.value.attachment)
      assertEquals(reference, vm.floatingAssistant.value.sourceContext)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun receivingContextAloneDoesNotInterpretOrExecuteAnything() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var decisions = 0
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      decisions++; SemanticDecision("instinct", .99, .99, .99, .99)
    }))
    try {
      vm.setFloatingContext(reference)
      runCurrent()
      assertEquals(reference, vm.floatingAssistant.value.sourceContext)
      assertEquals(0, decisions)
      assertNull(shadowOf(app).nextStartedActivity)
      vm.dismissFloatingAssistant()
      assertNull(vm.floatingAssistant.value.sourceContext)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun removingContextCancelsLateDecisionAndInvalidatesItsPreview() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val answer = CompletableDeferred<Unit>()
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      withContext(NonCancellable) { answer.await() }
      SemanticDecision("instinct", .99, .99, .99, .99)
    }))
    try {
      vm.setFloatingContext(reference)
      vm.updateFloatingText("Resumime esto"); vm.sendFloatingText { fail("Stale context") }; runCurrent()
      vm.setFloatingContext(null)
      answer.complete(Unit); runCurrent()
      assertNull(vm.floatingAssistant.value.sourceContext)
      assertFalse(vm.floatingAssistant.value.deciding)
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun replacingContextInvalidatesAnUnconfirmedCalendar() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val event = PhoneCommand.CalendarEvent("Cita", System.currentTimeMillis() + 86400000, System.currentTimeMillis() + 90000000)
    val vm = MainViewModel(app, hybridRouter = HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("calendar", .99, .99, .99, .99)
    }), structuredExtractor = StructuredCommandExtractor { listOf(event) })
    try {
      vm.setFloatingContext(reference)
      vm.updateFloatingText("Agendá la fecha que aparece acá"); vm.sendFloatingText { fail("Calendar stays local") }; runCurrent()
      assertTrue(vm.floatingAssistant.value.needsConfirmation)
      vm.setFloatingContext(reference.copy(text = "Otro documento"))
      assertFalse(vm.floatingAssistant.value.needsConfirmation)
      vm.confirmFloatingAction(true)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally { vm.dismissFloatingAssistant(); Dispatchers.resetMain() }
  }

  @Test fun spokenContextualQuerySendsInstructionAndReferenceAsTextOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val app = ApplicationProvider.getApplicationContext<Application>()
    val file = File.createTempFile("context-voice", ".m4a", app.cacheDir).apply { writeText("original audio") }
    val disconnect = ConnectedAccessibility.start(app)
    val capture = object : FloatingAudioCapture {
      override val snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1500, .5f)
      override fun start() = Result.success(Unit)
      override fun finish() = RecordedAudio(file, "audio/mp4")
      override fun cancel() {}
    }
    val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "Resumime esto" },
      HybridPhoneRouter({ true }, DecisionClassifier { input ->
        assertEquals("Resumime esto", JSONObject(input).getString("turn"))
        SemanticDecision("instinct", .99, .99, .99, .99)
      }))
    try {
      withContext(Dispatchers.IO) { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
      shadowOf(app.packageManager).installPackage(PackageInfo().apply {
        packageName = "com.whatsapp"; applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
      })
      vm.setFloatingContext(reference)
      var sent = 0
      vm.startFloatingListening { sent++ }; runCurrent()
      vm.sendFloatingAudio { sent++ }; advanceUntilIdle()
      val intent = shadowOf(app).nextStartedActivity
      assertEquals(Intent.ACTION_VIEW, intent.action)
      assertEquals("whatsapp", intent.data?.scheme)
      assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
      val payload = JSONObject(intent.data!!.getQueryParameter("text")!!)
      assertEquals("Resumime esto", payload.getString("instruction"))
      assertEquals(reference.text, payload.getJSONObject("sourceContext").getString("text"))
      assertEquals(1, sent)
      assertFalse(file.exists())
      vm.sendFloatingAudio { sent++ }; runCurrent()
      assertEquals(1, sent)
    } finally { vm.dismissFloatingAssistant(); disconnect(); file.delete(); Dispatchers.resetMain() }
  }
}
