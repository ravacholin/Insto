package com.example

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.PingAutoSendAccessibilityService
import com.example.testsupport.ConnectedAccessibility
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WhatsAppShareDialogRegressionTest {
  private val pkg = "com.whatsapp"
  private fun node(label: String = "", clickable: Boolean = false) = AccessibilityNodeInfo.obtain().apply {
    packageName = pkg; text = label; isVisibleToUser = true; isEnabled = true; isClickable = clickable
    if (clickable) { className = "android.widget.Button"; setBoundsInScreen(Rect(100, 100, 180, 150)) }
  }
  private fun screen(window: Int, vararg children: AccessibilityNodeInfo) = node().also { root ->
    ReflectionHelpers.setField(root, "mWindowId", window)
    children.forEach { shadowOf(root).addChild(it) }
    // Android delivers sealed snapshots to AccessibilityService. Build the tree first,
    // then seal every node so framework queries exercise that same contract.
    (children.toList() + root).forEach {
      ReflectionHelpers.callInstanceMethod<Void>(it, "setSealed",
        ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, true))
    }
  }

  @Test fun nativeQuestionWithCustomButtonIdConfirmsOnceThenSendsTheAudioOnce() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val disconnect = ConnectedAccessibility.start(app)
    val service = ReflectionHelpers.getStaticField<PingAutoSendAccessibilityService>(
      PingAutoSendAccessibilityService::class.java, "connectedInstance")
    val serviceShadow = shadowOf(service)
    try {
      serviceShadow.setRootInActiveWindow(screen(41, node("Previous screen")))
      AutoSendCoordinator.arm(pkg, recipientName = "Instinct", recipientPhone = "+447700900123",
        expectedAudioFileName = "instinct_12345678.m4a")
      val request = AutoSendCoordinator.requestId
      PingAutoSendAccessibilityService.prepareShareSend(pkg, request)
      AutoSendCoordinator.markShareLaunchCommitted(request)
      serviceShadow.setRootInActiveWindow(screen(42, node("Share with Instinct?"), node("Cancel", true),
        node("OK", true).apply { viewIdResourceName = "$pkg:id/new_dialog_positive" }))
      AutoSendCoordinator.observeShareWindow(pkg, "android.app.Dialog", SystemClock.uptimeMillis() + 1, 42)
      PingAutoSendAccessibilityService.requestShareSend(pkg, request)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
      assertEquals("The exact Share with Instinct dialog must be confirmed automatically", 1, serviceShadow.gesturesDispatched.size)
      serviceShadow.gesturesDispatched[0].let { it.callback().onCompleted(it.description()) }
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
      assertEquals("Do not touch the old OK again", 1, serviceShadow.gesturesDispatched.size)

      serviceShadow.setRootInActiveWindow(screen(43, node("Instinct"), node("Play", true), node("Send", true)))
      AutoSendCoordinator.observeShareWindow(pkg, "com.whatsapp.audiopicker.AudioPreviewActivity", SystemClock.uptimeMillis(), 43)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
      assertEquals("Continue to Send after OK", 2, serviceShadow.gesturesDispatched.size)
      serviceShadow.gesturesDispatched[1].let { it.callback().onCompleted(it.description()) }
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
      assertEquals("The same audio must not be sent twice", 2, serviceShadow.gesturesDispatched.size)
    } finally { AutoSendCoordinator.disarm(); disconnect() }
  }

  @Test fun aDifferentRecipientNeverGetsAnAutomaticConfirmation() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val disconnect = ConnectedAccessibility.start(app)
    val service = ReflectionHelpers.getStaticField<PingAutoSendAccessibilityService>(
      PingAutoSendAccessibilityService::class.java, "connectedInstance")
    try {
      shadowOf(service).setRootInActiveWindow(screen(41))
      AutoSendCoordinator.arm(pkg, recipientName = "Instinct", recipientPhone = "+447700900123",
        expectedAudioFileName = "instinct_12345678.m4a")
      val request = AutoSendCoordinator.requestId
      PingAutoSendAccessibilityService.prepareShareSend(pkg, request)
      AutoSendCoordinator.markShareLaunchCommitted(request)
      shadowOf(service).setRootInActiveWindow(screen(42, node("Share with Another Instinct?"), node("OK", true)))
      AutoSendCoordinator.observeShareWindow(pkg, "android.app.Dialog", SystemClock.uptimeMillis() + 1, 42)
      PingAutoSendAccessibilityService.requestShareSend(pkg, request)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
      assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    } finally { AutoSendCoordinator.disarm(); disconnect() }
  }
}
