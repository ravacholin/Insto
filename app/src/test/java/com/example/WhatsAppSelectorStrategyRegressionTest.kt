package com.example

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.AutoSendMetrics
import com.example.assist.AutoSendMetricsStore
import com.example.assist.PingAutoSendAccessibilityService
import com.example.testsupport.ConnectedAccessibility
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** The service with the ordered selector strategies: exact labels without IDs, ambiguity, events and metrics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WhatsAppSelectorStrategyRegressionTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val draft = "Hola, esto es una prueba"

  @After fun reset() {
    AutoSendCoordinator.disarm()
    AutoSendMetricsStore(app).clear()
  }

  private fun node(pkg: String, text: String? = null, desc: String? = null, id: String? = null, clickable: Boolean = false,
    editable: Boolean = false, bounds: Rect = Rect(0, 0, 10, 10)) = AccessibilityNodeInfo.obtain().apply {
    packageName = pkg; this.text = text; contentDescription = desc; isVisibleToUser = true; isEnabled = true
    isClickable = clickable; isEditable = editable; id?.let { viewIdResourceName = "$pkg:id/$it" }
    className = if (editable) "android.widget.EditText" else if (clickable) "android.widget.ImageButton" else "android.widget.TextView"
    setBoundsInScreen(bounds)
  }

  private fun screen(pkg: String, vararg children: AccessibilityNodeInfo) = node(pkg).also { root ->
    ReflectionHelpers.setField(root, "mWindowId", 50)
    children.forEach { shadowOf(root).addChild(it) }
    (children.toList() + root).forEach {
      ReflectionHelpers.callInstanceMethod<Void>(it, "setSealed", ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, true))
    }
  }

  private fun chat(pkg: String, composer: String?, vararg sends: Rect) = screen(pkg,
    node(pkg, "Instinct", id = "action_bar_title"),
    node(pkg, "Enviar ubicación", clickable = true, bounds = Rect(20, 200, 200, 230)),
    node(pkg, composer, editable = true, clickable = true, bounds = Rect(20, 400, 240, 440)),
    *sends.map { node(pkg, desc = "Enviar", clickable = true, bounds = it) }.toTypedArray(),
  )

  private fun service() = ReflectionHelpers.getStaticField<PingAutoSendAccessibilityService>(
    PingAutoSendAccessibilityService::class.java, "connectedInstance")

  private fun armText(pkg: String): Long {
    AutoSendCoordinator.arm(pkg, recipientName = "Instinct", recipientPhone = "+447700900123", expectedText = draft)
    val request = AutoSendCoordinator.requestId
    AutoSendCoordinator.markTextLaunchCommitted(request)
    PingAutoSendAccessibilityService.requestTextSend(pkg, request)
    return request
  }

  private fun waitForMetrics(): AutoSendMetrics.Snapshot {
    repeat(100) {
      val snapshot = AutoSendMetricsStore(app).read()
      if (snapshot.lastAttempt != null) return snapshot
      Thread.sleep(20)
    }
    fail("No se registró el resultado")
    error("unreachable")
  }

  @Test fun exactLabelWithoutIdsSendsOnceAndRecordsTheStrategyWithoutContent() {
    val pkg = "com.whatsapp"
    shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply {
      packageName = pkg; versionName = "2.26.20"; longVersionCode = 262000
      applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg }
    })
    val disconnect = ConnectedAccessibility.start(app)
    val shadow = shadowOf(service())
    try {
      shadow.setRootInActiveWindow(chat(pkg, draft, Rect(250, 400, 300, 440)))
      armText(pkg)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
      assertEquals("«Enviar ubicación» must never be touched; the exact «Enviar» once", 1, shadow.gesturesDispatched.size)
      shadow.setRootInActiveWindow(chat(pkg, null, Rect(250, 400, 300, 440)))
      shadow.gesturesDispatched[0].let { it.callback().onCompleted(it.description()) }
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
      assertEquals(1, shadow.gesturesDispatched.size)
      val metrics = waitForMetrics()
      assertEquals(AutoSendMetrics.Outcome.CONFIRMED, metrics.lastAttempt?.outcome)
      assertEquals("send.label", metrics.rows.single().strategy)
      assertEquals(262000L, metrics.lastConfirmed[AutoSendMetrics.Flavor.WHATSAPP])
      val raw = File(app.noBackupFilesDir, AutoSendMetricsStore.FILE_NAME).readText()
      for (secret in listOf(draft, "Instinct", "447700900123")) assertFalse(secret, raw.contains(secret))
    } finally { disconnect() }
  }

  @Test fun twoPossibleSendButtonsAreNeverTouched() {
    val pkg = "com.whatsapp.w4b"
    val disconnect = ConnectedAccessibility.start(app)
    val shadow = shadowOf(service())
    try {
      shadow.setRootInActiveWindow(chat(pkg, draft, Rect(250, 400, 300, 440), Rect(250, 100, 300, 140)))
      armText(pkg)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
      assertEquals(0, shadow.gesturesDispatched.size)
      assertTrue(AutoSendCoordinator.textWaitReason, AutoSendCoordinator.textWaitReason.contains("más de un botón"))
      assertEquals(AutoSendMetrics.Outcome.AMBIGUOUS, AutoSendCoordinator.report?.failure)
    } finally { disconnect() }
  }

  @Test fun anEventFromTheArmedPackageBringsTheCheckForward() {
    val pkg = "com.whatsapp"
    val disconnect = ConnectedAccessibility.start(app)
    val service = service()
    val shadow = shadowOf(service)
    try {
      shadow.setRootInActiveWindow(chat(pkg, draft, Rect(250, 400, 300, 440)))
      armText(pkg)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
      assertEquals(0, shadow.gesturesDispatched.size)
      service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = pkg })
      // An event from any other app is ignored.
      service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = "com.other" })
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
      assertEquals("Checked about 60 ms after the event instead of the fixed 450 ms", 1, shadow.gesturesDispatched.size)
    } finally { disconnect() }
  }
}
