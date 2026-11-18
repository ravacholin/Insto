package com.example.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal class WhatsAppSendAutomation(
  private val service: AccessibilityService,
  private val handler: Handler,
  private val onSent: () -> Unit,
  private val onUnconfirmed: () -> Unit,
  private val onReturn: (Long, String?) -> Unit,
) {
  private class Attempt(
    val request: Long,
    val pkg: String,
    val text: Boolean,
    val advance: (WhatsAppSendEvent) -> WhatsAppSendAction,
  ) {
    var disarmedForTouch = false
    var returnToPrevious = false
    var returnPackage: String? = null
  }

  private var active: Attempt? = null
  private var poll: Runnable? = null
  private var lastPolledRequest = -1L

  fun startText(pkg: String, request: Long) {
    val text = AutoSendCoordinator.expectedText ?: return
    val phone = AutoSendCoordinator.recipientPhone ?: return
    val machine = TextSendMachine(request, text, phone, AutoSendCoordinator.recipientName)
    var state: TextSendMachine.State = TextSendMachine.State.Waiting
    startPolling(Attempt(request, pkg, text = true) { event ->
      val next = machine.reduce(state, event)
      state = next.state
      next.action
    })
  }

  fun startShare(pkg: String, request: Long) = startPolling(shareAttempt(pkg, request))

  fun inspectLegacyShare(root: AccessibilityNodeInfo, pkg: String) {
    val request = AutoSendCoordinator.requestId
    val attempt = active?.takeIf { it.request == request } ?: shareAttempt(pkg, request).also(::replace)
    accept(attempt, screen(root, attempt), fromPoll = false)
  }

  fun stop() {
    poll?.let(handler::removeCallbacks)
    poll = null
    active = null
  }

  private fun shareAttempt(pkg: String, request: Long): Attempt {
    val machine = AudioShareMachine(request, AutoSendCoordinator.expectedAudioFileName,
      AutoSendCoordinator.expectedText ?: AutoSendCoordinator.expectedAttachmentCaption,
      AutoSendCoordinator.recipientPhone, AutoSendCoordinator.recipientName)
    var state = AudioShareMachine.State()
    return Attempt(request, pkg, text = false) { event ->
      val next = machine.reduce(state, event)
      state = next.state
      next.action
    }
  }

  private fun replace(attempt: Attempt) {
    stop()
    active = attempt
  }

  private fun startPolling(attempt: Attempt) {
    if (lastPolledRequest == attempt.request) return
    lastPolledRequest = attempt.request
    replace(attempt)
    schedule(attempt, 450)
  }

  private fun isCurrent(attempt: Attempt): Boolean =
    active === attempt && AutoSendCoordinator.requestId == attempt.request &&
      (attempt.disarmedForTouch || AutoSendCoordinator.isArmedFor(attempt.pkg))

  private fun schedule(attempt: Attempt, delayMs: Long) {
    poll?.let(handler::removeCallbacks)
    val runnable = Runnable {
      poll = null
      if (isCurrent(attempt)) {
        val event = withWhatsAppWindow(attempt.pkg) { screen(it, attempt) }
          ?: WhatsAppSendEvent.Elapsed(SystemClock.elapsedRealtime())
        accept(attempt, event, fromPoll = true)
      } else if (active === attempt) stop()
    }
    poll = runnable
    handler.postDelayed(runnable, delayMs)
  }

  private fun accept(attempt: Attempt, event: WhatsAppSendEvent, fromPoll: Boolean) {
    if (!isCurrent(attempt)) return
    when (val action = attempt.advance(event)) {
      is WhatsAppSendAction.Wait -> {
        action.reason?.let { AutoSendCoordinator.textWaitReason = it }
        if (fromPoll || event is WhatsAppSendEvent.Gesture) schedule(attempt, action.delayMs)
      }
      is WhatsAppSendAction.Touch -> {
        if (action.disarm) disarm(attempt)
        if (!action.disarm && fromPoll) schedule(attempt, 180)
        touch(attempt, action)
      }
      WhatsAppSendAction.Abort -> {
        AutoSendCoordinator.disarm()
        stop()
        onUnconfirmed()
      }
      WhatsAppSendAction.Confirm -> {
        if (!attempt.disarmedForTouch) disarm(attempt)
        stop()
        onSent()
        if (attempt.returnToPrevious) onReturn(attempt.request, attempt.returnPackage)
      }
      WhatsAppSendAction.None -> Unit
    }
  }

  private fun disarm(attempt: Attempt) {
    attempt.returnToPrevious = AutoSendCoordinator.shouldBoomerangReturn()
    attempt.returnPackage = AutoSendCoordinator.consumeReturnPackage()
    attempt.disarmedForTouch = true
    AutoSendCoordinator.disarm()
  }

  private fun touch(attempt: Attempt, action: WhatsAppSendAction.Touch) {
    val bounds = action.bounds
    val path = Path().apply { moveTo((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f) }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
    fun result(completed: Boolean) {
      accept(attempt, WhatsAppSendEvent.Gesture(SystemClock.elapsedRealtime(), action.step, completed), fromPoll = false)
    }
    val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) = result(true)
      override fun onCancelled(gestureDescription: GestureDescription?) = result(false)
    }, handler)
    if (!accepted) result(false)
  }

  private fun screen(root: AccessibilityNodeInfo, attempt: Attempt): WhatsAppSendEvent.Screen {
    val windowId = root.windowId
    val display = service.resources.displayMetrics
    val snapshot = root.snapshot()
    WhatsAppSnapshotDump.writeIfEnabled(service, snapshot)
    return WhatsAppSendEvent.Screen(
      at = SystemClock.elapsedRealtime(),
      window = windowId,
      reader = WhatsAppScreenReader(snapshot, attempt.pkg),
      display = UiBounds(0, 0, display.widthPixels, display.heightPixels),
      launchRequest = if (attempt.text) AutoSendCoordinator.textLaunchCommittedRequestId
        else AutoSendCoordinator.shareLaunchCommittedRequestId,
      freshShareWindow = AutoSendCoordinator.isFreshShareWindow(attempt.request, windowId),
      audioPreviewRequest = AutoSendCoordinator.audioPreviewRequestId,
      audioPreviewWindow = AutoSendCoordinator.audioPreviewWindowId,
    )
  }

  /** The keyboard can own rootInActiveWindow. Also inspect the focused application window. */
  private fun <T : Any> withWhatsAppWindow(pkg: String, read: (AccessibilityNodeInfo) -> T): T? {
    fun readMatching(root: AccessibilityNodeInfo): T? = try {
      if (root.packageName?.toString() == pkg) read(root) else null
    } finally {
      root.recycle()
    }

    service.rootInActiveWindow?.let { readMatching(it)?.let { result -> return result } }
    for (window in service.windows.orEmpty().sortedByDescending { it.layer }) {
      if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isActive && !window.isFocused)) continue
      val root = window.root ?: continue
      readMatching(root)?.let { return it }
    }
    return null
  }
}
