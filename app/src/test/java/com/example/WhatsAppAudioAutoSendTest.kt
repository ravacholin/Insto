package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.PingAutoSendAccessibilityService
import com.example.data.WhatsAppFlavor
import com.example.testsupport.ConnectedAccessibility
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WhatsAppAudioAutoSendTest {
  @Before fun attachFileProviderToCurrentApplication() {
    // FileProvider caches roots by authority, while Robolectric gives each test a new cacheDir.
    // Its normal attachInfo lifecycle invalidates any roots left by a previous test.
    Robolectric.buildContentProvider(FileProvider::class.java).create()
  }

  private fun installWhatsApp(app: Application) {
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"
      applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
  }
  @Test fun disconnectedServiceRetainsRecordingInsteadOfOpeningManualPreview() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    installWhatsApp(app)
    val dir = File(app.cacheDir, "voice_notes").apply { mkdirs() }
    val audio = File.createTempFile("instinct_", ".m4a", dir).apply { writeText("original") }
    try {
      assertFalse(PingAutoSendAccessibilityService.isConnected())
      val result = WhatsAppDispatcher.sendWhatsAppAudio(app, "+447700900123", audio, WhatsAppFlavor.WHATSAPP,
        true, recipientName = "Instinct")
      assertTrue(result is DispatchResult.Error)
      assertTrue((result as DispatchResult.Error).message.contains("Accesibilidad"))
      assertEquals("original", audio.readText())
      assertNull(shadowOf(app).nextStartedActivity)
    } finally { audio.delete() }
  }
  @Test fun successfulTargetedAudioShareCommitsTheCurrentLaunch() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    installWhatsApp(app)
    val dir = File(app.cacheDir, "voice_notes").apply { mkdirs() }
    val audio = File.createTempFile("instinct_", ".m4a", dir).apply { writeText("original") }
    val disconnect = ConnectedAccessibility.start(app)
    try {
      val result = WhatsAppDispatcher.sendWhatsAppAudio(app, "+447700900123", audio, WhatsAppFlavor.WHATSAPP,
        true, recipientName = "Instinct")
      assertTrue(result is DispatchResult.AudioDispatched)
      assertEquals(AutoSendCoordinator.requestId, AutoSendCoordinator.shareLaunchCommittedRequestId)
      assertEquals(-1L, AutoSendCoordinator.textLaunchCommittedRequestId)
      assertEquals(audio.name, AutoSendCoordinator.expectedAudioFileName)
      assertEquals(Intent.ACTION_SEND, shadowOf(app).nextStartedActivity.action)
    } finally { AutoSendCoordinator.disarm(); disconnect(); audio.delete() }
  }
  @Test fun bubbleOpensTheInterpreterInsteadOfOpeningWhatsApp() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val disconnect = ConnectedAccessibility.start(app)
    try {
      val field = PingAutoSendAccessibilityService::class.java.getDeclaredField("connectedInstance").apply { isAccessible = true }
      val service = field.get(null) as PingAutoSendAccessibilityService
      val method = service.javaClass.getDeclaredMethod("openFloatingAssistant").apply { isAccessible = true }
      assertEquals(true, method.invoke(service))
      val intent = shadowOf(app).nextStartedActivity
      assertEquals("com.example.assist.AssistActivity", intent.component!!.className)
      assertEquals(Intent.ACTION_ASSIST, intent.action)
    } finally { disconnect() }
  }
}
