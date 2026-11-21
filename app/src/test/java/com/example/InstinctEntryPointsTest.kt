package com.example

import android.app.Application
import com.example.testsupport.awaitCachedPreferences
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.ShareToInstinctActivity
import com.example.data.AssistantPreferencesRepository
import com.example.testsupport.ConnectedAccessibility
import com.example.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstinctEntryPointsTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()

  @Before fun configureInstinct() {
    AutoSendCoordinator.disarm()
    runBlocking { AssistantPreferencesRepository(app).savePinnedContact("Instinct", "+447700900123") }
    awaitCachedPreferences(app) { it.pinnedContact?.phoneNumber == "+447700900123" }
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"
      applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
  }

  @Test fun blankFloatingIconOpensTheDefaultChat() {
    val vm = MainViewModel(app)
    var opened = false
    try {
      vm.openFloatingInstinct { opened = true }
      assertTrue(opened)
      val intent = shadowOf(app).nextStartedActivity
      assertEquals("com.whatsapp", intent.`package`)
      assertEquals("447700900123", intent.data?.getQueryParameter("phone"))
      assertNull(intent.data?.getQueryParameter("text"))
    } finally { vm.dismissFloatingAssistant() }
  }

  @Test fun floatingIconPassesTypedTextToInstinctEvenWhenItLooksLikeAPhoneCommand() {
    val vm = MainViewModel(app)
    val disconnect = ConnectedAccessibility.start(app)
    val text = "Abrí Spotify, por favor 🎵"
    try {
      vm.updateFloatingText(text)
      vm.openFloatingInstinct {}
      val intent = shadowOf(app).nextStartedActivity
      assertEquals("com.whatsapp", intent.`package`)
      assertEquals("447700900123", intent.data?.getQueryParameter("phone"))
      assertEquals(text, intent.data?.getQueryParameter("text"))
      assertTrue(AutoSendCoordinator.isArmedFor("com.whatsapp"))
    } finally { AutoSendCoordinator.disarm(); vm.dismissFloatingAssistant(); disconnect() }
  }

  @Test fun androidSharesResolveToTheDirectInstinctEntryPoint() {
    for (mimeType in listOf("text/plain", "image/jpeg", "application/pdf")) {
      val share = Intent(Intent.ACTION_SEND).setType(mimeType).setPackage(app.packageName)
      val targets = app.packageManager.queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY)
      assertEquals(listOf(ShareToInstinctActivity::class.java.name), targets.map { it.activityInfo.name })
      assertEquals("Insto", targets.single().loadLabel(app.packageManager).toString())
    }
  }

  @Test fun sharedLinkOpensInTheDefaultChatWithoutArmingAnExternalSend() {
    val disconnect = ConnectedAccessibility.start(app)
    val link = "https://example.com/nota?lang=es&ref=Insto"
    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link).apply {
      clipData = ClipData.newRawUri("Link preview", Uri.parse("content://example/thumbnail.jpg"))
    }
    val controller = Robolectric.buildActivity(ShareToInstinctActivity::class.java, share).setup()
    try {
      val activity = controller.get()
      val deadline = System.nanoTime() + 5_000_000_000L
      while (!activity.isFinishing && System.nanoTime() < deadline) {
        ShadowLooper.shadowMainLooper().idle()
        Thread.sleep(10)
      }
      assertTrue(activity.isFinishing)
      val intent = shadowOf(activity).nextStartedActivity
      assertEquals("com.whatsapp", intent.`package`)
      assertEquals("447700900123", intent.data?.getQueryParameter("phone"))
      assertEquals(link, intent.data?.getQueryParameter("text"))
      assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp"))
    } finally { controller.pause().stop().destroy(); disconnect() }
  }
}
