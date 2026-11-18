package com.example

import com.example.testsupport.localActionDecisions

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import com.example.audio.*
import com.example.phone.ContactOption
import com.example.phone.HybridPhoneRouter
import com.example.speech.AudioTranscriber
import com.example.testsupport.installNativeHandler
import com.example.ui.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingEverydayRoutingTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  @Test fun shortSilenceVoiceCommandChangesRingerModeWithoutGemini() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
    val audioManager = app.getSystemService(AudioManager::class.java)
    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    val audio = File.createTempFile("silence", ".m4a").apply { writeText("original") }
    val capture = Capture(audio)
    val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "Modo Silenciar" },
      hybridRouter = HybridPhoneRouter({ false }, { error("Gemini is disabled") }))
    try {
      vm.startFloatingListening { fail("Must not dispatch to Instinct") }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent()
      vm.sendFloatingAudio { fail("Must not dispatch to Instinct") }
      advanceUntilIdle()
      assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
      assertNotNull(vm.consumePhoneActionCompletion())
      assertFalse(audio.exists())
    } finally {
      vm.dismissFloatingAssistant()
      audio.delete()
      Dispatchers.resetMain()
    }
  }

  @Test fun searchVoiceOpensOneNewChatAndReleasesTheRecording() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    installNativeHandler(app, Intent.ACTION_VIEW, "https")
    val audio = File.createTempFile("search", ".m4a").apply { writeText("original") }
    val capture = Capture(audio)
    val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler),
      AudioTranscriber { "Buscá Córdoba & C++" },
      hybridRouter = HybridPhoneRouter({ true }, { error("Search should bypass Decisions") }))
    try {
      var handoffs = 0
      vm.startFloatingListening { handoffs++ }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent()
      vm.sendFloatingAudio { handoffs++ }
      advanceUntilIdle()

      assertEquals(1, handoffs)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals(Intent.ACTION_VIEW, intent.action)
      val url = requireNotNull(intent.data)
      assertEquals("https", url.scheme)
      assertEquals("chatgpt.com", url.host)
      assertEquals("/", url.path)
      assertEquals("Córdoba & C++", url.getQueryParameter("q"))
      assertEquals("search", url.getQueryParameter("hints"))
      assertFalse(audio.exists())
      assertNull(shadowOf(app).nextStartedActivity)
    } finally {
      vm.dismissFloatingAssistant()
      audio.delete()
      Dispatchers.resetMain()
    }
  }

  @Test fun navigationVoiceHandsOffWithoutWhatsAppAndReleasesOnlyAfterSuccess() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    installNativeHandler(app, Intent.ACTION_VIEW, "https")
    val audio = File.createTempFile("everyday", ".m4a").apply { writeText("original") }
    try {
      val capture = Capture(audio)
      val vm = MainViewModel(app, capture, StandardTestDispatcher(testScheduler), AudioTranscriber { "Llevame a Plaza de Mayo" }, hybridRouter = localActionDecisions())
      vm.startFloatingListening { fail("No WhatsApp") }
      capture.snapshot = FloatingVoiceCapture.Snapshot(VoiceSessionPolicy.State.RECORDING, 1000)
      runCurrent(); vm.sendFloatingAudio { fail("No WhatsApp") }; advanceUntilIdle()
      val intent = shadowOf(app).nextStartedActivity
      assertEquals(Intent.ACTION_VIEW, intent.action)
      assertEquals("Plaza de Mayo", intent.data!!.getQueryParameter("destination"))
      assertNotNull(vm.consumePhoneActionCompletion())
      assertFalse(audio.exists())
      assertNull(shadowOf(app).nextStartedActivity)
      vm.dismissFloatingAssistant()
    } finally { audio.delete(); Dispatchers.resetMain() }
  }
  @Test fun missingCameraAppRetainsTheTextRatherThanSendingItAsAQuery() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("abrí la cámara para grabar")
      vm.sendFloatingText { fail("No compatible camera") }
      assertEquals("abrí la cámara para grabar", vm.floatingAssistant.value.text)
      assertEquals(FloatingMode.TYPING, vm.floatingAssistant.value.mode)
      assertNotNull(vm.floatingAssistant.value.error)
      assertNull(vm.consumePhoneActionCompletion())
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }
  @Test fun deniedContactsPermissionPreservesTheNameAndCloseInvalidatesItsCallback() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
    try {
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("llamá a Ana")
      vm.sendFloatingText { fail("No WhatsApp") }
      assertTrue(vm.floatingAssistant.value.needsContacts)
      assertNull(vm.consumePhoneActionCompletion())
      vm.onFloatingContactsPermission(false)
      assertEquals("llamá a Ana", vm.floatingAssistant.value.text)
      assertNotNull(vm.floatingAssistant.value.error)
      vm.sendFloatingText { fail("No WhatsApp") }
      vm.dismissFloatingAssistant()
      shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
      vm.onFloatingContactsPermission(true)
      assertNull(shadowOf(app).nextStartedActivity)
      assertNull(vm.consumePhoneActionCompletion())
    } finally { Dispatchers.resetMain() }
  }
  @Test fun duplicateContactsNeedAnExplicitNumberChoiceAndCannotAcceptAnInjectedOption() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    installNativeHandler(app, Intent.ACTION_DIAL, "tel")
    ShadowContentResolver.registerProviderInternal("com.android.contacts", ContactsFixture())
    try {
      val vm = MainViewModel(app, hybridRouter = localActionDecisions())
      vm.updateFloatingText("llamá a Ana")
      vm.sendFloatingText { fail("No WhatsApp") }
      assertEquals(2, vm.floatingAssistant.value.contactOptions.size)
      assertNull(shadowOf(app).nextStartedActivity)
      assertNull(vm.consumePhoneActionCompletion())
      vm.chooseFloatingContact(ContactOption("otra persona", "999999"))
      assertNull(shadowOf(app).nextStartedActivity)
      vm.updateFloatingText("9"); vm.sendFloatingText { fail("Invalid choice must not send") }
      assertEquals(2, vm.floatingAssistant.value.contactOptions.size)
      vm.chooseFloatingContact(vm.floatingAssistant.value.contactOptions[1])
      assertEquals("tel:654321", shadowOf(app).nextStartedActivity.dataString)
      assertNotNull(vm.consumePhoneActionCompletion())
      vm.chooseFloatingContact(ContactOption("Ana", "654321"))
      assertNull(shadowOf(app).nextStartedActivity)
      vm.dismissFloatingAssistant()
    } finally { Dispatchers.resetMain() }
  }
  @Test fun cancelingAContactSelectionStaysLocalAndDoesNotDispatch() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    ShadowContentResolver.registerProviderInternal("com.android.contacts", ContactsFixture())
    val vm=MainViewModel(app,hybridRouter=localActionDecisions())
    try {
      vm.updateFloatingText("llamá a Ana");vm.sendFloatingText {fail("No dispatch")}
      assertEquals(2,vm.floatingAssistant.value.contactOptions.size)
      vm.updateFloatingText("cancelar");vm.sendFloatingText {fail("Cancel selection must not reach Instinct")}
      assertTrue(vm.floatingAssistant.value.contactOptions.isEmpty())
      assertEquals("Pedido cancelado.",vm.floatingAssistant.value.status)
      assertNull(vm.floatingAssistant.value.error)
      assertNull(shadowOf(app).nextStartedActivity)
    } finally {vm.dismissFloatingAssistant();Dispatchers.resetMain()}
  }
  private class Capture(val file: File) : FloatingAudioCapture {
    override var snapshot = FloatingVoiceCapture.Snapshot()
    override fun start() = Result.success(Unit)
    override fun finish() = RecordedAudio(file, "audio/mp4")
    override fun cancel() {}
  }
  private class ContactsFixture : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
      val cursor = MatrixCursor(arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER))
      if (uri.path == "/data/phones/filter/Ana") {
        cursor.addRow(arrayOf("Ana", "123456")); cursor.addRow(arrayOf("Ana", "654321"))
      }
      return cursor
    }
    override fun getType(uri: Uri) = Phone.CONTENT_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
  }
}
