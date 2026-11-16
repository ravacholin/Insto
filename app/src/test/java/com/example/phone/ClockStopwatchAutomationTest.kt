package com.example.phone

import android.app.Application
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.assist.PingAutoSendAccessibilityService
import com.example.testsupport.installGoogleClock
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ClockStopwatchAutomationTest {
  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val clock = "com.google.android.deskclock"

  private fun node(label: String = "", id: String = "", clickable: Boolean = false, pkg: String = clock) =
    AccessibilityNodeInfo.obtain().apply {
      packageName = pkg
      text = label
      viewIdResourceName = id
      isVisibleToUser = true
      isEnabled = true
      isClickable = clickable
    }

  private fun screen(button: AccessibilityNodeInfo): AccessibilityNodeInfo = node().also {
    shadowOf(it).addChild(node(id = "$clock:id/stopwatch_time_text"))
    shadowOf(it).addChild(button)
  }

  @Test fun clicksStartOnceAndOnlyCompletesAfterClockReportsPause() {
    installGoogleClock(app)
    val controller = Robolectric.buildService(PingAutoSendAccessibilityService::class.java).create()
    val service = controller.get()
    val results = mutableListOf<PhoneActionResult>()
    var clicks = 0
    val start = node("Iniciar", clickable = true)
    shadowOf(start).setOnPerformActionListener { _, _ ->
      clicks++
      shadowOf(service).setRootInActiveWindow(screen(node("Pausar", clickable = true)))
      true
    }
    shadowOf(service).setRootInActiveWindow(screen(start))
    val automation = ClockStopwatchAutomation(service, StopwatchOp.START, results::add)
    try {
      automation.start()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(1, clicks)
      assertTrue(results.isEmpty())
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
      assertEquals(1, clicks)
      assertEquals(PhoneActionOutcome.COMPLETED, results.single().outcome)
    } finally { automation.cancel(); controller.destroy() }
  }

  @Test fun tabContainerAndItsLabelResolveToOneControlBeforeStarting() {
    installGoogleClock(app)
    val controller = Robolectric.buildService(PingAutoSendAccessibilityService::class.java).create()
    val service = controller.get()
    val results = mutableListOf<PhoneActionResult>()
    var tabs = 0
    var starts = 0
    val start = node("Iniciar", clickable = true)
    shadowOf(start).setOnPerformActionListener { _, _ ->
      starts++
      shadowOf(service).setRootInActiveWindow(screen(node("Pausar", clickable = true)))
      true
    }
    val tab = node(clickable = true).apply { contentDescription = "Cronómetro" }
    shadowOf(tab).addChild(node("Cronómetro"))
    shadowOf(tab).setOnPerformActionListener { _, _ ->
      tabs++
      shadowOf(service).setRootInActiveWindow(screen(start))
      true
    }
    val root = node()
    shadowOf(root).addChild(tab)
    shadowOf(service).setRootInActiveWindow(root)
    val automation = ClockStopwatchAutomation(service, StopwatchOp.START, results::add)
    try {
      automation.start()
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
      assertEquals(1, tabs)
      assertEquals(1, starts)
      assertEquals(PhoneActionOutcome.COMPLETED, results.single().outcome)
    } finally { automation.cancel(); controller.destroy() }
  }

  @Test fun aStaleStartControlIsNeverPressedTwiceAndNeverCountsAsSuccess() {
    installGoogleClock(app)
    val controller = Robolectric.buildService(PingAutoSendAccessibilityService::class.java).create()
    val service = controller.get()
    val results = mutableListOf<PhoneActionResult>()
    var clicks = 0
    val start = node("Iniciar", clickable = true)
    shadowOf(start).setOnPerformActionListener { _, _ -> clicks++; true }
    shadowOf(service).setRootInActiveWindow(screen(start))
    val automation = ClockStopwatchAutomation(service, StopwatchOp.START, results::add)
    try {
      automation.start()
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
      assertEquals(1, clicks)
      assertEquals(PhoneActionOutcome.REJECTED, results.single().outcome)
      assertTrue(results.single().retainRequest)
    } finally { automation.cancel(); controller.destroy() }
  }

  @Test fun controlsInAnotherAppCannotBeOperated() {
    installGoogleClock(app)
    val controller = Robolectric.buildService(PingAutoSendAccessibilityService::class.java).create()
    val service = controller.get()
    val results = mutableListOf<PhoneActionResult>()
    var clicks = 0
    val root = node(pkg = "another.app")
    val start = node("Iniciar", "$clock:id/stopwatch_time_text", true, "another.app")
    shadowOf(start).setOnPerformActionListener { _, _ -> clicks++; true }
    shadowOf(root).addChild(start)
    shadowOf(service).setRootInActiveWindow(root)
    val automation = ClockStopwatchAutomation(service, StopwatchOp.START, results::add)
    try {
      automation.start()
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
      assertEquals(0, clicks)
      assertEquals(PhoneActionOutcome.REJECTED, results.single().outcome)
    } finally { automation.cancel(); controller.destroy() }
  }
}
