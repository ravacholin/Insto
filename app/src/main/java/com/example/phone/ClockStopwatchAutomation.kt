package com.example.phone

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Bounded automation of Google's visible stopwatch, armed only by an explicit phone command. */
internal class ClockStopwatchAutomation(
  private val service: AccessibilityService,
  private val operation: StopwatchOp,
  private val onResult: (PhoneActionResult) -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val policy = StopwatchUiPolicy(operation)
  private var active = true
  private var observedClock = false
  private var deadline = 0L
  private val poll = object : Runnable {
    override fun run() {
      if (!active) return
      if (SystemClock.elapsedRealtime() >= deadline || service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
        fail(); return
      }
      val root = service.rootInActiveWindow
      if (root != null) {
        try {
          if (root.packageName?.toString() == NativeStopwatchController.GOOGLE_CLOCK) {
            observedClock = true
            inspect(root)
          } else if (observedClock) {
            fail(); return
          }
        } catch (_: Exception) { fail(); return }
        finally { root.recycle() }
      }
      if (active) handler.postDelayed(this, 150)
    }
  }

  fun start() {
    if (!active) return
    val intent = service.packageManager.getLaunchIntentForPackage(NativeStopwatchController.GOOGLE_CLOCK)
    if (intent == null) { fail(); return }
    deadline = SystemClock.elapsedRealtime() + 8_000
    try {
      service.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
      handler.post(poll)
    } catch (_: Exception) { fail() }
  }

  fun cancel() { active = false; handler.removeCallbacks(poll) }

  fun interrupt() { if (active) fail() }

  private fun fail() {
    if (!active) return
    cancel()
    val message = "No pude comprobar el cambio del cronómetro. Revisá Reloj de Google; el pedido quedó guardado en Insto."
    Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    // It may have clicked a control before losing sight of the stopwatch: the state is unknown, not refused.
    onResult(PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED, effectUnverified = true))
  }

  private fun inspect(root: AccessibilityNodeInfo) {
    val nodes = mutableListOf<AccessibilityNodeInfo>()
    fun visit(node: AccessibilityNodeInfo) {
      if (nodes.size >= 300) { node.recycle(); return }
      nodes.add(node)
      for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
    // Own every retained node separately from the caller's root.
    visit(AccessibilityNodeInfo.obtain(root))
    val targets = mutableListOf<AccessibilityNodeInfo?>()
    try {
      val visible = nodes.filter { it.isVisibleToUser && it.packageName?.toString() == NativeStopwatchController.GOOGLE_CLOCK }
      val controls = mutableListOf<StopwatchControl>()
      visible.forEach { node ->
        val target = clickable(node)
        val control = StopwatchControl(
          listOfNotNull(node.contentDescription?.toString(), node.text?.toString()).distinct().joinToString("|"),
          node.viewIdResourceName.orEmpty(), node.isSelected || target?.isSelected == true, target != null)
        val existing = if (target == null) -1 else targets.indexOfFirst { it == target }
        if (existing >= 0) {
          // Tab containers and their labels often expose the very same clickable parent.
          val previous = controls[existing]
          controls[existing] = previous.copy(label = previous.label + "|" + control.label,
            selected = previous.selected || control.selected)
          target?.recycle()
        } else {
          targets.add(target)
          controls.add(control)
        }
      }
      when (val step = policy.next(controls)) {
        is StopwatchUiStep.Select -> click(targets[step.index])
        is StopwatchUiStep.Click -> click(targets[step.index])
        StopwatchUiStep.Done -> {
          cancel()
          onResult(PhoneActionResult(when (operation) {
            StopwatchOp.START, StopwatchOp.RESUME -> "Cronómetro en marcha en Reloj de Google."
            StopwatchOp.PAUSE -> "Cronómetro pausado en Reloj de Google."
            StopwatchOp.RESET -> "Cronómetro en cero en Reloj de Google."
            StopwatchOp.STATUS -> "Cronómetro abierto en Reloj de Google."
          }, outcome = PhoneActionOutcome.COMPLETED))
        }
        StopwatchUiStep.Wait -> Unit
      }
    } finally { targets.forEach { it?.recycle() }; nodes.forEach { it.recycle() } }
  }

  private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isClickable && node.isEnabled && node.isVisibleToUser) return AccessibilityNodeInfo.obtain(node)
    var parent = node.parent
    repeat(3) {
      val current = parent ?: return null
      if (current.packageName?.toString() != NativeStopwatchController.GOOGLE_CLOCK) { current.recycle(); return null }
      if (current.isClickable && current.isEnabled && current.isVisibleToUser) return current
      parent = current.parent
      current.recycle()
    }
    parent?.recycle()
    return null
  }

  private fun click(target: AccessibilityNodeInfo?) {
    if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail()
  }
}
