package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.PingAutoSendAccessibilityService
import com.example.data.WhatsAppFlavor
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [AudioDispatchIntegrationTest.SearchableNodeShadow::class])
class AudioDispatchIntegrationTest {
  private lateinit var app: Application
  private lateinit var audio: File
  private var service: PingAutoSendAccessibilityService? = null

  @Before fun setup() {
    app = ApplicationProvider.getApplicationContext()
    shadowOf(app.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"
      applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    audio = File(app.cacheDir, "voice_notes/instinct_new.m4a").apply {
      parentFile!!.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3))
    }
    AutoSendCoordinator.disarm()
  }

  @After fun cleanup() {
    AutoSendCoordinator.disarm()
    service?.onDestroy()
    audio.delete()
  }

  private fun connect() {
    service = Robolectric.buildService(PingAutoSendAccessibilityService::class.java).create().get()
    ReflectionHelpers.callInstanceMethod<Void>(service!!, "onServiceConnected")
  }

  private fun send(context: Context = app): DispatchResult = WhatsAppDispatcher.sendWhatsAppAudio(
    context, "+447700900123", audio, WhatsAppFlavor.WHATSAPP, true, recipientName = "Instinct")

  @Test fun `disconnected accessibility retains audio without opening a manual confirmation`() {
    assertFalse(PingAutoSendAccessibilityService.isConnected())
    assertTrue(send() is DispatchResult.Error)
    assertTrue(audio.exists())
    assertNull(shadowOf(app).nextStartedActivity)
    assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp"))
  }

  @Test fun `successful audio share commits the exact recipient and stream before automation`() {
    connect()
    assertTrue(send() is DispatchResult.AudioDispatched)
    assertEquals(AutoSendCoordinator.requestId, AutoSendCoordinator.audioLaunchCommittedRequestId)
    assertEquals("Instinct", AutoSendCoordinator.recipientName)
    assertEquals("instinct_new.m4a", AutoSendCoordinator.expectedAudioFileName)
    val intent = shadowOf(app).nextStartedActivity
    assertEquals(Intent.ACTION_SEND, intent.action)
    assertEquals("com.whatsapp", intent.`package`)
    assertEquals("447700900123@s.whatsapp.net", intent.getStringExtra("jid"))
    assertEquals("audio/mp4", intent.type)
    assertNotNull(intent.clipData?.getItemAt(0)?.uri)
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
  }

  @Test fun `failed launch disarms automation and retains the recording`() {
    connect()
    val broken = object : ContextWrapper(app) {
      override fun startActivity(intent: Intent) { throw IllegalStateException("launch failed") }
    }
    assertTrue(send(broken) is DispatchResult.Error)
    assertTrue(audio.exists())
    assertEquals(-1L, AutoSendCoordinator.audioLaunchCommittedRequestId)
    assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp"))
  }

  @Test fun `stale launches and rearmed requests cannot authorize audio automation`() {
    AutoSendCoordinator.arm("com.whatsapp", recipientPhone = "+447700900123", expectedAudioFileName = audio.name)
    val request = AutoSendCoordinator.requestId
    AutoSendCoordinator.markAudioLaunchCommitted(request - 1)
    assertEquals(-1L, AutoSendCoordinator.audioLaunchCommittedRequestId)
    AutoSendCoordinator.markAudioLaunchCommitted(request)
    assertEquals(request, AutoSendCoordinator.audioLaunchCommittedRequestId)
    AutoSendCoordinator.arm("com.whatsapp", recipientPhone = "+447700900999", expectedAudioFileName = "other.m4a")
    AutoSendCoordinator.markAudioLaunchCommitted(request)
    assertEquals(-1L, AutoSendCoordinator.audioLaunchCommittedRequestId)
    AutoSendCoordinator.disarm()
    assertEquals(-1L, AutoSendCoordinator.audioLaunchCommittedRequestId)
  }

  private fun showConfirmation(recipient: String) {
    val root = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.whatsapp"
      isVisibleToUser = true
    }
    val title = AccessibilityNodeInfo.obtain().apply {
      text = "Share with $recipient?"
      isVisibleToUser = true
    }
    val ok = AccessibilityNodeInfo.obtain().apply {
      text = "OK"
      isVisibleToUser = true
      isEnabled = true
      setBoundsInScreen(Rect(120, 100, 200, 150))
    }
    shadowOf(root).addChild(title)
    shadowOf(root).addChild(ok)
    shadowOf(service!!).setRootInActiveWindow(root)
    shadowOf(service!!).setCanDispatchGestures(true)
  }

  @Test fun `polling touches OK automatically without WhatsApp events and never repeats it`() {
    connect()
    showConfirmation("Instinct")
    send()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    val gestures = shadowOf(service!!).gesturesDispatched
    assertEquals(1, gestures.size)
    gestures.single().callback().onCompleted(gestures.single().description())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    assertEquals(1, shadowOf(service!!).gesturesDispatched.size)
  }

  @Test fun `polling never confirms another recipients share dialog`() {
    connect()
    showConfirmation("Someone else")
    send()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    assertTrue(shadowOf(service!!).gesturesDispatched.isEmpty())
  }

  private fun showPreviewOrEmptyChat(preview: Boolean) {
    val root = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.whatsapp"
      isVisibleToUser = true
    }
    fun add(id: String, label: String, editable: Boolean = false) {
      shadowOf(root).addChild(AccessibilityNodeInfo.obtain().apply {
        viewIdResourceName = "com.whatsapp:id/$id"
        text = label
        isVisibleToUser = true
        isEditable = editable
        isEnabled = editable
      })
    }
    add("conversation_contact_name", "Instinct")
    if (preview) {
      add("file_name", "instinct_new.m4a")
      add("media_caption_send_btn", "Enviar") // Preview still loading; Send is disabled.
    } else {
      add("entry", "", editable = true)
    }
    shadowOf(service!!).setRootInActiveWindow(root)
  }

  @Test fun `confirmation then cancelled preview never reports a submitted audio`() {
    connect()
    showConfirmation("Instinct")
    send()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    val gesture = shadowOf(service!!).gesturesDispatched.single()
    gesture.callback().onCompleted(gesture.description())
    showPreviewOrEmptyChat(preview = true)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(360))
    showPreviewOrEmptyChat(preview = false)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(360))
    assertTrue(AutoSendCoordinator.isArmedFor("com.whatsapp"))
    assertEquals(1, shadowOf(service!!).gesturesDispatched.size)
  }

  /** Robolectric models child nodes but not Android's remote accessibility tree queries. */
  @Implements(AccessibilityNodeInfo::class)
  class SearchableNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo

    @Implementation fun findAccessibilityNodeInfosByText(text: String): List<AccessibilityNodeInfo> =
      search { it.text?.toString()?.contains(text, ignoreCase = true) == true }

    @Implementation fun findAccessibilityNodeInfosByViewId(id: String): List<AccessibilityNodeInfo> =
      search { it.viewIdResourceName == id }

    private fun search(matches: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
      val found = mutableListOf<AccessibilityNodeInfo>()
      fun walk(current: AccessibilityNodeInfo) {
        if (matches(current)) found.add(AccessibilityNodeInfo.obtain(current))
        for (i in 0 until current.childCount) current.getChild(i)?.let { child ->
          try { walk(child) } finally { child.recycle() }
        }
      }
      walk(node)
      return found
    }
  }
}
