package com.example.assist

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.EyesFreeFeedbackManager
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.data.FloatingPillMode
import com.example.data.VolumeKeyTrigger
import com.example.whatsapp.WhatsAppDispatcher
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional accessibility orchestrator handling:
 * 1) Zero-tap WhatsApp "Send / Enviar" auto-click when armed after recording.
 * 2) Native WhatsApp PTT microphone press-and-lock gesture when armed.
 * 3) Optional Volume-hold entry to the same floating assistant as the Android assist gesture.
 * 4) Floating Accessibility Pill: tap/hold opens that assistant (no separate background sender).
 * 5) Boomerang auto-return to the previous foreground app (e.g., Maps / Waze) after sending.
 */
class PingAutoSendAccessibilityService : AccessibilityService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var autoSendPoll: Runnable? = null
  private var pollingRequestId = -1L
  private var clockAutomation: com.example.phone.ClockStopwatchAutomation? = null
  private var textAttempt: TextSendAttempt? = null
  private var shareAttempt: ShareSendAttempt? = null


  private lateinit var repository: AssistantPreferencesRepository

  @Volatile private var cachedPrefs = AssistantPreferences()

  // Volume key hold state
  private var volumeHoldRunnable: Runnable? = null
  private var volumeHoldTriggered = false

  // Floating pill overlay state
  private var windowManager: WindowManager? = null
  private var pillView: ImageView? = null
  private var pillParams: WindowManager.LayoutParams? = null
  private var pillHoldRunnable: Runnable? = null
  private var isPillRecording = false

  private var audioDeviceCallback: AudioDeviceCallback? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    repository = AssistantPreferencesRepository(this)
    windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    serviceScope.launch {
      repository.preferencesFlow.collect { prefs ->
        cachedPrefs = prefs
        syncFloatingPillVisibility()
      }
    }

    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val callback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
          syncFloatingPillVisibility()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
          syncFloatingPillVisibility()
        }
      }
    audioDeviceCallback = callback
    audioManager?.registerAudioDeviceCallback(callback, mainHandler)
    connectedInstance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val pkg = event?.packageName?.toString() ?: return

    // Track last non-WhatsApp foreground app for Boomerang return
    if (
      event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        pkg != packageName &&
        pkg != WhatsAppDispatcher.PACKAGE_WHATSAPP &&
        pkg != WhatsAppDispatcher.PACKAGE_WHATSAPP_BUSINESS &&
        pkg != "com.android.systemui" &&
        !pkg.contains("inputmethod")
    ) {
      AutoSendCoordinator.lastKnownForegroundPackage = pkg
    }

    // Strict Zero-Trust gate: never inspect any window unless explicitly armed for WhatsApp
    if (!AutoSendCoordinator.isArmedFor(pkg)) return

    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && AutoSendCoordinator.expectedAudioFileName != null) {
      AutoSendCoordinator.observeShareWindow(pkg, event.className?.toString(), event.eventTime, event.windowId)
    }
    if (AutoSendCoordinator.expectedText != null || AutoSendCoordinator.expectedAudioFileName != null) {
      // Explicit successful launches drive the check; an event from the armed package only brings it forward.
      kickPoll()
      return
    }
    val root = rootInActiveWindow ?: return
    try {
      if (root.packageName?.toString() != pkg) return
      when (AutoSendCoordinator.currentMode()) {
        ArmedActionMode.CLICK_SEND -> handleAutoClickSend(root, pkg)
        ArmedActionMode.NATIVE_PTT_LOCK -> handleNativePttLock(root, pkg)
      }
    } finally { root.recycle() }
  }

  private var nextPollAt = 0L

  private fun schedulePoll(poll: Runnable, delay: Long) {
    mainHandler.removeCallbacks(poll)
    nextPollAt = SystemClock.uptimeMillis() + delay
    mainHandler.postDelayed(poll, delay)
  }

  /** Runs the pending check soon after WhatsApp changed its window, instead of waiting for the next fixed tick. */
  private fun kickPoll() {
    val poll = autoSendPoll ?: return
    if (pollingRequestId != AutoSendCoordinator.requestId) return
    if (nextPollAt - SystemClock.uptimeMillis() > EVENT_DEBOUNCE_MS) schedulePoll(poll, EVENT_DEBOUNCE_MS)
  }

  private fun scheduleTextSendPoll(pkg: String, request: Long) {
    if (pollingRequestId == request) return
    autoSendPoll?.let { mainHandler.removeCallbacks(it) }
    pollingRequestId = request
    textAttempt = TextSendAttempt(request)
    val poll = object : Runnable {
      override fun run() {
        if (AutoSendCoordinator.requestId != request || !AutoSendCoordinator.isArmedFor(pkg)) return
        if (AutoSendCoordinator.textLaunchCommittedRequestId == request) {
          if (!withWhatsAppWindow(pkg) { root -> tryTextTouch(root, pkg, request) }) {
            AutoSendCoordinator.textWaitReason = "No se pudo acceder al chat visible de WhatsApp."
          }
        }
        if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) schedulePoll(this, POLL_MS)
      }
    }
    autoSendPoll = poll
    schedulePoll(poll, FIRST_POLL_MS)
  }

  private fun scheduleShareSendPoll(pkg: String, request: Long) {
    if (pollingRequestId == request) return
    autoSendPoll?.let { mainHandler.removeCallbacks(it) }
    pollingRequestId = request
    shareAttempt = ShareSendAttempt(request)
    val poll = object : Runnable {
      override fun run() {
        if (AutoSendCoordinator.requestId != request || !AutoSendCoordinator.isArmedFor(pkg)) return
        if (AutoSendCoordinator.shareLaunchCommittedRequestId == request) {
          if (!withWhatsAppWindow(pkg) { root -> handleAutoClickSend(root, pkg) }) {
            AutoSendCoordinator.textWaitReason = "No se pudo acceder a la vista de audio de WhatsApp."
          }
        }
        if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) schedulePoll(this, POLL_MS)
      }
    }
    autoSendPoll = poll
    schedulePoll(poll, FIRST_POLL_MS)
  }

  /** The keyboard can own rootInActiveWindow; also inspect the focused application window. */
  private fun withWhatsAppWindow(pkg: String, action: (AccessibilityNodeInfo) -> Unit): Boolean {
    val primary = rootInActiveWindow
    if (primary != null) {
      try {
        if (primary.packageName?.toString() == pkg) { action(primary); return true }
      } finally { primary.recycle() }
    }
    for (window in windows.orEmpty().sortedByDescending { it.layer }) {
      if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isActive && !window.isFocused)) continue
      val root = window.root ?: continue
      try {
        if (root.packageName?.toString() == pkg) { action(root); return true }
      } finally { root.recycle() }
    }
    return false
  }

  private fun fail(request: Long, reason: String, outcome: AutoSendMetrics.Outcome) {
    AutoSendCoordinator.textWaitReason = reason
    AutoSendCoordinator.reportFor(request)?.failure = outcome
  }

  private fun WhatsAppSelectors.Result.missing(): AutoSendMetrics.Outcome =
    if (this is WhatsAppSelectors.Result.Ambiguous) AutoSendMetrics.Outcome.AMBIGUOUS else AutoSendMetrics.Outcome.NOT_FOUND

  private fun UiNode.Box.toRect() = Rect(left, top, right, bottom)

  private fun finish(request: Long, outcome: AutoSendMetrics.Outcome) = AutoSendCoordinator.finishReport(this, request, outcome)

  private fun tryTextTouch(root: AccessibilityNodeInfo, pkg: String, request: Long) {
    val tree = AccessibilityUiTree(root)
    if (!matchesArmedRecipient(tree, pkg)) {
      fail(request, "El nombre o número del chat no coincide con el contacto configurado.", AutoSendMetrics.Outcome.RECIPIENT_MISMATCH)
      return
    }
    val text = AutoSendCoordinator.expectedText ?: return
    val composer = WhatsAppSelectors.composer(tree, pkg, text)
    if (composer.node?.text != text) {
      fail(request, "No se pudo verificar el mensaje en el cuadro de WhatsApp.",
        if (composer is WhatsAppSelectors.Result.Ambiguous) AutoSendMetrics.Outcome.AMBIGUOUS else AutoSendMetrics.Outcome.UNVERIFIED_CONTENT)
      return
    }
    val button = WhatsAppSelectors.sendButton(tree, pkg)
    if (button !is WhatsAppSelectors.Result.Found) {
      fail(request, if (button is WhatsAppSelectors.Result.Ambiguous) "Hay más de un botón Enviar posible; no se tocó ninguno."
        else "No se encontró el botón Enviar de WhatsApp.", button.missing())
      return
    }
    val bounds = button.node.bounds.toRect()
    if (!bounds.intersect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) ||
        bounds.width() < 8 || bounds.height() < 8) return
    if (textAttempt?.beginTouch(request, true, true, true) != true) return
    val phone = AutoSendCoordinator.recipientPhone ?: return
    val name = AutoSendCoordinator.recipientName
    val boomerang = AutoSendCoordinator.shouldBoomerangReturn()
    val returnPkg = AutoSendCoordinator.consumeReturnPackage()
    AutoSendCoordinator.reportFor(request)?.apply { strategy = button.strategy; touched = true }
    AutoSendCoordinator.disarm() // One gesture only; an uncertain outcome must never send twice.
    val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        confirmTextSubmitted(pkg, request, text, phone, name, boomerang, returnPkg)
      }
      override fun onCancelled(gestureDescription: GestureDescription?) {
        finish(request, AutoSendMetrics.Outcome.UNCERTAIN)
        showUnconfirmedTextHint()
      }
    }, mainHandler)
    if (!accepted) { finish(request, AutoSendMetrics.Outcome.UNCERTAIN); showUnconfirmedTextHint() }
  }

  private fun confirmTextSubmitted(pkg: String, request: Long, text: String, phone: String,
    name: String?, boomerang: Boolean, returnPkg: String?) {
    val deadline = SystemClock.elapsedRealtime() + 3_000
    val check = object : Runnable {
      override fun run() {
        if (AutoSendCoordinator.requestId != request) return
        var cleared = false
        withWhatsAppWindow(pkg) { root ->
          val tree = AccessibilityUiTree(root)
          if (matchesRecipient(tree, pkg, phone, name, true)) {
            val composer = WhatsAppSelectors.composer(tree, pkg, text)
            cleared = composer is WhatsAppSelectors.Result.Found && composer.node.text.isNullOrEmpty()
          }
        }
        if (cleared) {
          finish(request, AutoSendMetrics.Outcome.CONFIRMED)
          EyesFreeFeedbackManager.playSentCue(this@PingAutoSendAccessibilityService, cachedPrefs.eyesFreeAudioCues)
          if (boomerang) scheduleBoomerangReturn(returnPkg)
        } else if (SystemClock.elapsedRealtime() < deadline) mainHandler.postDelayed(this, POLL_MS)
        else { finish(request, AutoSendMetrics.Outcome.UNCERTAIN); showUnconfirmedTextHint() }
      }
    }
    mainHandler.postDelayed(check, 250L)
  }

  private fun showUnconfirmedTextHint() {
    android.widget.Toast.makeText(this,
      "No se pudo confirmar el envío. No se repetirá el toque para evitar duplicados.",
      android.widget.Toast.LENGTH_LONG).show()
  }

  override fun onKeyEvent(event: KeyEvent?): Boolean {
    if (event == null) return super.onKeyEvent(event)
    // Observe only (never consume): lets the assist hotkey tell a tap from a hold.
    HotkeyHoldTracker.onKeyEvent(event)
    val trigger = cachedPrefs.volumeKeyTrigger
    if (trigger == VolumeKeyTrigger.DISABLED) return super.onKeyEvent(event)

    val targetKeyCode =
      when (trigger) {
        VolumeKeyTrigger.VOLUME_DOWN_HOLD -> KeyEvent.KEYCODE_VOLUME_DOWN
        VolumeKeyTrigger.VOLUME_UP_HOLD -> KeyEvent.KEYCODE_VOLUME_UP
        VolumeKeyTrigger.DISABLED -> return super.onKeyEvent(event)
      }

    if (event.keyCode != targetKeyCode) return super.onKeyEvent(event)

    when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount == 0) {
          volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
          volumeHoldTriggered = false
          val holdTask = Runnable {
            volumeHoldTriggered = openFloatingAssistant()
          }
          volumeHoldRunnable = holdTask
          mainHandler.postDelayed(holdTask, 420L)
          return false // Let normal short taps adjust volume seamlessly
        }
        return volumeHoldTriggered
      }
      KeyEvent.ACTION_UP -> {
        volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        volumeHoldRunnable = null
        if (volumeHoldTriggered) {
          volumeHoldTriggered = false
          return true
        }
      }
    }
    return super.onKeyEvent(event)
  }

  override fun onInterrupt() {
    clockAutomation?.interrupt()
    clockAutomation = null
    autoSendPoll?.let { mainHandler.removeCallbacks(it) }
    volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
    volumeHoldRunnable = null
    HotkeyHoldTracker.reset()
    AutoSendCoordinator.disarm()
  }

  override fun onDestroy() {
    clockAutomation?.interrupt()
    clockAutomation = null
    autoSendPoll?.let { mainHandler.removeCallbacks(it) }
    if (connectedInstance === this) connectedInstance = null
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
    removeFloatingPill()
    HotkeyHoldTracker.reset()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun handleAutoClickSend(root: AccessibilityNodeInfo, pkg: String) {
    val request = AutoSendCoordinator.requestId
    val tree = AccessibilityUiTree(root)
    if (trySharePositiveStep(root, tree, pkg, request)) return
    if (!matchesArmedRecipient(tree, pkg)) {
      fail(request, "El nombre o número del destinatario del audio no coincide con Instinct.", AutoSendMetrics.Outcome.RECIPIENT_MISMATCH)
      return
    }
    val composerText = tree.byId("$pkg:id/entry").firstOrNull { it.visible }?.text
    val attachmentLabels = WhatsAppSelectors.attachmentLabels(tree, pkg).toMutableList()
    // Audio titles may use a different view ID; require this recording's unique name.
    val fileName = AutoSendCoordinator.expectedAudioFileName
    if (fileName != null && fileName.startsWith("instinct_") &&
      tree.walk().any { it.visible && (it.text == fileName || it.description == fileName) }) attachmentLabels.add(fileName)
    val exactFileMatches = AutoSendGuard.payloadMatches(AutoSendCoordinator.expectedText ?: AutoSendCoordinator.expectedAttachmentCaption,
      fileName, composerText, attachmentLabels)
    val ownedAudioPreview = AutoSendGuard.ownedAudioPreviewMatches(request,
      AutoSendCoordinator.shareLaunchCommittedRequestId, AutoSendCoordinator.audioPreviewRequestId,
      AutoSendCoordinator.audioPreviewWindowId, root.windowId, fileName, composerText,
      AutoSendCoordinator.expectedAttachmentCaption)
    if (!exactFileMatches && !ownedAudioPreview) {
      fail(request, "No se pudo verificar esta grabación en la vista de WhatsApp.", AutoSendMetrics.Outcome.UNVERIFIED_CONTENT)
      return
    }
    val sendNode = WhatsAppSelectors.sendButton(tree, pkg)
    if (sendNode !is WhatsAppSelectors.Result.Found) {
      fail(request, if (sendNode is WhatsAppSelectors.Result.Ambiguous) "Hay más de un botón Enviar posible; no se tocó ninguno."
        else "No se encontró el botón Enviar del audio.", sendNode.missing())
      return
    }
    val bounds = sendNode.node.bounds.toRect()
    val surface = "${sendNode.node.id}|${sendNode.node.label}"
    if (!bounds.intersect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) || bounds.width() < 8 || bounds.height() < 8) return
    touchFinalShareSend(bounds, pkg, request, root.windowId, surface, sendNode.strategy)
  }

  private fun touchFinalShareSend(bounds: Rect, pkg: String, request: Long, window: Int, surface: String, strategy: String) {
    if (AutoSendCoordinator.expectedAudioFileName != null) {
      val attempt = shareAttempt?.takeIf { it.requestId == request } ?: return
      if (attempt.hasConfirmed && !attempt.isPostConfirmationSurface(window, surface)) return
      if (!attempt.beginSend(request, true, true, true, window, surface)) return
    }
    val fileName = AutoSendCoordinator.expectedAudioFileName
    val keepUntilSubmitted = fileName?.startsWith("instinct_") == true && fileName.endsWith(".m4a")
    val phone = AutoSendCoordinator.recipientPhone
    val name = AutoSendCoordinator.recipientName
    val boomerang = if (!keepUntilSubmitted) AutoSendCoordinator.shouldBoomerangReturn() else false
    val returnPkg = if (!keepUntilSubmitted) AutoSendCoordinator.consumeReturnPackage() else null
    AutoSendCoordinator.reportFor(request)?.apply { this.strategy = strategy; touched = true }
    if (!keepUntilSubmitted) AutoSendCoordinator.disarm()
    val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        if (keepUntilSubmitted) {
          if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) shareAttempt?.sendCompleted(request)
        } else if (phone != null) confirmShareSubmitted(pkg, request, phone, name, boomerang, returnPkg)
        else {
          EyesFreeFeedbackManager.playSentCue(this@PingAutoSendAccessibilityService, cachedPrefs.eyesFreeAudioCues)
          if (boomerang) scheduleBoomerangReturn(returnPkg)
        }
      }
      override fun onCancelled(gestureDescription: GestureDescription?) {
        if (AutoSendCoordinator.requestId != request) return
        finish(request, AutoSendMetrics.Outcome.UNCERTAIN)
        AutoSendCoordinator.disarm()
        showUnconfirmedTextHint()
      }
    }, mainHandler)
    if (!accepted && AutoSendCoordinator.requestId == request) {
      finish(request, AutoSendMetrics.Outcome.UNCERTAIN)
      AutoSendCoordinator.disarm()
      showUnconfirmedTextHint()
    }
  }

  private data class ShareUiEvidence(val labels: List<String>, val hasAudioControls: Boolean)

  private fun collectShareUi(root: AccessibilityNodeInfo): ShareUiEvidence {
    val labels = mutableListOf<String>()
    var audioControls = false
    var visited = 0
    fun walk(node: AccessibilityNodeInfo, depth: Int) {
      if (visited++ >= 800 || depth > 28) return
      if (node.isVisibleToUser) {
        node.text?.toString()?.take(512)?.let { labels.add(it) }
        node.contentDescription?.toString()?.take(512)?.let { labels.add(it) }
        val id = node.viewIdResourceName.orEmpty().lowercase(java.util.Locale.ROOT)
        if (listOf("audio", "waveform", "seekbar", "play_button", "play_btn").any { id.contains(it) }) audioControls = true
        val controlLabel = (node.contentDescription?.toString() ?: node.text?.toString()).orEmpty().lowercase(java.util.Locale.ROOT).trim()
        if ((node.isClickable || node.className?.toString()?.endsWith("Button") == true) && controlLabel in
          listOf("reproducir", "play", "pausar", "pause", "reproducir audio", "play audio")) audioControls = true
      }
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        try { walk(child, depth + 1) } finally { child.recycle() }
      }
    }
    walk(root, 0)
    return ShareUiEvidence(labels, audioControls)
  }

  /** OK/Accept is part of this share, not a reason to disarm before WhatsApp's final Send. */
  private fun trySharePositiveStep(root: AccessibilityNodeInfo, tree: UiTree, pkg: String, request: Long): Boolean {
    val attempt = shareAttempt ?: return false
    val file = AutoSendCoordinator.expectedAudioFileName ?: return false
    if (!file.startsWith("instinct_") || !file.endsWith(".m4a")) return false
    if (attempt.stepInFlight) return true
    val chatCandidate = attempt.hasCompletedStep && shareSubmittedInChat(tree, pkg)
    if (attempt.observeSubmittedChat(root.windowId, SystemClock.elapsedRealtime(), chatCandidate)) {
      finish(request, AutoSendMetrics.Outcome.CONFIRMED)
      val boomerang = AutoSendCoordinator.shouldBoomerangReturn()
      val returnPkg = AutoSendCoordinator.consumeReturnPackage()
      AutoSendCoordinator.disarm()
      EyesFreeFeedbackManager.playSentCue(this, cachedPrefs.eyesFreeAudioCues)
      if (boomerang) scheduleBoomerangReturn(returnPkg)
      return true
    }
    if (chatCandidate) return true // Let the chat settle; inline chat buttons are not share confirmations.
    val found = WhatsAppSelectors.sharePositive(tree, pkg)
    if (found is WhatsAppSelectors.Result.Ambiguous) AutoSendCoordinator.reportFor(request)?.failure = AutoSendMetrics.Outcome.AMBIGUOUS
    val positive = found.node ?: return false
    val strategy = (found as WhatsAppSelectors.Result.Found).strategy
    val label = positive.label?.takeIf { it.isNotBlank() } ?: if (positive.id?.contains("send") == true) "Enviar" else null
    val surface = "${positive.id}|$label"
    val postConfirmation = attempt.isPostConfirmationSurface(root.windowId, surface)
    val postSend = attempt.isPostSendSurface(root.windowId, surface)
    val evidence = collectShareUi(root)
    val composerText = WhatsAppSelectors.composer(tree, pkg, "").node?.text
    val allowed = ShareConfirmationPolicy.canConfirm(request, AutoSendCoordinator.shareLaunchCommittedRequestId,
      AutoSendCoordinator.isFreshShareWindow(request, root.windowId) || evidence.labels.any { it == file } || postConfirmation || postSend,
      file, AutoSendCoordinator.expectedAttachmentCaption, composerText, AutoSendCoordinator.recipientName,
      AutoSendCoordinator.recipientPhone.orEmpty(), evidence.labels, label, evidence.hasAudioControls,
      recipientPreviouslyVerified = postConfirmation || postSend)
    val bounds = positive.bounds.toRect()
    if (!allowed) return false
    if (!bounds.intersect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) || bounds.width() < 8 || bounds.height() < 8) return true
    if (attempt.hasConfirmed && !postConfirmation) return true // Same old confirmation still on screen.
    if (!ShareConfirmationPolicy.isConfirmationLabel(label)) {
      touchFinalShareSend(bounds, pkg, request, root.windowId, surface, strategy)
      return true
    }
    if (!attempt.beginConfirmation(request, root.windowId, true, surface)) return true
    AutoSendCoordinator.reportFor(request)?.apply { this.strategy = strategy; touched = true }
    val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) attempt.confirmationCompleted(request)
      }
      override fun onCancelled(gestureDescription: GestureDescription?) {
        finish(request, AutoSendMetrics.Outcome.UNCERTAIN)
        if (AutoSendCoordinator.requestId == request) AutoSendCoordinator.disarm()
        showUnconfirmedTextHint()
      }
    }, mainHandler)
    if (!accepted) { finish(request, AutoSendMetrics.Outcome.UNCERTAIN); AutoSendCoordinator.disarm(); showUnconfirmedTextHint() }
    return true
  }

  private fun shareSubmittedInChat(tree: UiTree, pkg: String): Boolean {
    val phone = AutoSendCoordinator.recipientPhone ?: return false
    if (!matchesRecipient(tree, pkg, phone, AutoSendCoordinator.recipientName, true)) return false
    val composer = WhatsAppSelectors.composer(tree, pkg, "")
    // An ambiguous Send still counts as present: the chat is not shown as submitted.
    return composer is WhatsAppSelectors.Result.Found && composer.node.text.isNullOrEmpty() &&
      WhatsAppSelectors.sendButton(tree, pkg) == WhatsAppSelectors.Result.NotFound &&
      WhatsAppSelectors.voiceMic(tree, pkg) is WhatsAppSelectors.Result.Found
  }

  private fun confirmShareSubmitted(pkg: String, request: Long, phone: String, name: String?, boomerang: Boolean, returnPkg: String?) {
    val deadline = SystemClock.elapsedRealtime() + 3_000
    val check = object : Runnable {
      override fun run() {
        if (AutoSendCoordinator.requestId != request) return
        var submitted = false
        withWhatsAppWindow(pkg) { root ->
          val tree = AccessibilityUiTree(root)
          if (matchesRecipient(tree, pkg, phone, name, true)) {
            val composer = WhatsAppSelectors.composer(tree, pkg, "")
            submitted = composer is WhatsAppSelectors.Result.Found && composer.node.text.isNullOrEmpty() &&
              WhatsAppSelectors.sendButton(tree, pkg) == WhatsAppSelectors.Result.NotFound
          }
        }
        if (submitted) {
          finish(request, AutoSendMetrics.Outcome.CONFIRMED)
          EyesFreeFeedbackManager.playSentCue(this@PingAutoSendAccessibilityService, cachedPrefs.eyesFreeAudioCues)
          if (boomerang) scheduleBoomerangReturn(returnPkg)
        } else if (SystemClock.elapsedRealtime() < deadline) mainHandler.postDelayed(this, POLL_MS)
        else { finish(request, AutoSendMetrics.Outcome.UNCERTAIN); showUnconfirmedTextHint() }
      }
    }
    mainHandler.postDelayed(check, 250L)
  }

  /** Fail closed: never click Send in a different chat or an unrecognized share screen. */
  private fun matchesArmedRecipient(tree: UiTree, pkg: String): Boolean {
    val phone = AutoSendCoordinator.recipientPhone ?: return true // Legacy explicit actions.
    return matchesRecipient(tree, pkg, phone, AutoSendCoordinator.recipientName,
      AutoSendGuard.launchMatches(AutoSendCoordinator.requestId, AutoSendCoordinator.expectedText,
        AutoSendCoordinator.expectedAudioFileName, AutoSendCoordinator.textLaunchCommittedRequestId,
        AutoSendCoordinator.shareLaunchCommittedRequestId))
  }

  private fun matchesRecipient(tree: UiTree, pkg: String, phone: String,
    name: String?, launchCommitted: Boolean): Boolean {
    val headers = WhatsAppSelectors.headers(tree, pkg)
    return AutoSendGuard.recipientMatches(phone, headers, name, openedConversation = launchCommitted)
  }

  private fun handleNativePttLock(root: AccessibilityNodeInfo, pkg: String) {
    val micNode = WhatsAppSelectors.voiceMic(AccessibilityUiTree(root), pkg).node ?: return
    val bounds = micNode.bounds.toRect()

    if (bounds.width() <= 0 || bounds.height() <= 0) return

    // Disarm immediately so we only trigger the native mic lock gesture once
    AutoSendCoordinator.disarm()

    val startX = bounds.exactCenterX()
    val startY = bounds.exactCenterY()
    val slideOffsetPx =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 84f, resources.displayMetrics)
    val endY = (startY - slideOffsetPx).coerceAtLeast(40f)

    // Hold briefly on mic then slide up to lock WhatsApp's native voice recorder
    val holdPath = Path().apply { moveTo(startX, startY) }
    val lockPath =
      Path().apply {
        moveTo(startX, startY)
        lineTo(startX, endY)
      }

    val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, 260L, true)
    val slideStroke = holdStroke.continueStroke(lockPath, 0L, 280L, false)

    val gesture = GestureDescription.Builder().addStroke(holdStroke).addStroke(slideStroke).build()
    dispatchGesture(
      gesture,
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
          EyesFreeFeedbackManager.playStartCue(
            this@PingAutoSendAccessibilityService,
            cachedPrefs.eyesFreeAudioCues,
          )
        }
      },
      null,
    )
  }

  /** Every floating/volume voice activation enters the same capture → Groq → routing flow. */
  private fun openFloatingAssistant(): Boolean = runCatching {
    startActivity(Intent(this, AssistActivity::class.java).apply {
      action = Intent.ACTION_ASSIST
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    })
    true
  }.getOrElse {
    EyesFreeFeedbackManager.playErrorCue(this, cachedPrefs.eyesFreeAudioCues)
    android.widget.Toast.makeText(this, "No se pudo abrir el asistente.", android.widget.Toast.LENGTH_LONG).show()
    false
  }

  private fun scheduleBoomerangReturn(returnPkg: String?) {
    mainHandler.postDelayed(
      {
        if (returnPkg != null) {
          val launch = packageManager.getLaunchIntentForPackage(returnPkg)
          if (launch != null) {
            launch.addFlags(
              android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            val launched = runCatching { startActivity(launch); true }.getOrDefault(false)
            if (launched) return@postDelayed
          }
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
      },
      750L,
    )
  }

  private fun syncFloatingPillVisibility() {
    val prefs = cachedPrefs
    val route = ContextRoutingEngine.resolveRoute(this, prefs)
    val shouldShow =
      when (prefs.floatingPillMode) {
        FloatingPillMode.OFF -> false
        FloatingPillMode.ALWAYS_ON -> true
        FloatingPillMode.CAR_OR_BLUETOOTH_ONLY ->
          route.isCarOrBluetoothActive
      }

    if (shouldShow) {
      showFloatingPill()
    } else {
      removeFloatingPill()
    }
  }

  private fun showFloatingPill() {
    if (pillView != null) return
    val wm = windowManager ?: return

    val sizePx =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 52f, resources.displayMetrics).toInt()
    val view =
      ImageView(this).apply {
        setImageResource(R.drawable.ic_mic_pulse)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (sizePx * 0.24f).toInt()
        setPadding(pad, pad, pad, pad)
        background = buildPillDrawable(isRecording = false)
        contentDescription = "PingAssist Floating Trigger"
      }

    val params =
      WindowManager.LayoutParams(
          sizePx,
          sizePx,
          WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT,
        )
        .apply {
          gravity = Gravity.TOP or Gravity.END
          x = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
          y = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220f, resources.displayMetrics).toInt()
        }

    attachPillTouchListener(view, params, wm)

    runCatching {
      wm.addView(view, params)
      pillView = view
      pillParams = params
    }
  }

  private fun attachPillTouchListener(
    view: ImageView,
    params: WindowManager.LayoutParams,
    wm: WindowManager,
  ) {
    val touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(36f)
    var downRawX = 0f
    var downRawY = 0f
    var startX = 0
    var startY = 0
    var moved = false

    view.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downRawX = event.rawX
          downRawY = event.rawY
          startX = params.x
          startY = params.y
          moved = false
          isPillRecording = false

          val holdTask = Runnable {
            if (!moved) {
              isPillRecording = openFloatingAssistant()
              if (isPillRecording) {
                view.background = buildPillDrawable(isRecording = true)
              }
            }
          }
          pillHoldRunnable = holdTask
          mainHandler.postDelayed(holdTask, 320L)
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - downRawX
          val dy = event.rawY - downRawY
          if (!isPillRecording && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
            moved = true
            pillHoldRunnable?.let { mainHandler.removeCallbacks(it) }
            params.x = (startX - dx.toInt()).coerceAtLeast(0)
            params.y = (startY + dy.toInt()).coerceAtLeast(60)
            runCatching { wm.updateViewLayout(view, params) }
          }
          true
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> {
          pillHoldRunnable?.let { mainHandler.removeCallbacks(it) }
          pillHoldRunnable = null

          if (isPillRecording) {
            isPillRecording = false
            view.background = buildPillDrawable(isRecording = false)
          } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
            openFloatingAssistant()
          }
          true
        }
        else -> false
      }
    }
  }

  private fun removeFloatingPill() {
    pillHoldRunnable?.let { mainHandler.removeCallbacks(it) }
    pillHoldRunnable = null
    val view = pillView ?: return
    runCatching { windowManager?.removeView(view) }
    pillView = null
    pillParams = null
  }

  private fun buildPillDrawable(isRecording: Boolean): GradientDrawable {
    val strokePx =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics).toInt()
    return GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(if (isRecording) Color.parseColor("#FF5468") else Color.parseColor("#E6101B18"))
      setStroke(strokePx, if (isRecording) Color.WHITE else Color.parseColor("#10D98B"))
    }
  }

  companion object {
    private const val FIRST_POLL_MS = 450L
    private const val POLL_MS = 180L
    private const val EVENT_DEBOUNCE_MS = 60L
    @Volatile private var connectedInstance: PingAutoSendAccessibilityService? = null

    fun isConnected(): Boolean = connectedInstance != null

    fun requestStopwatch(operation: com.example.phone.StopwatchOp, onResult: (com.example.phone.PhoneActionResult) -> Unit): () -> Unit {
      val service = connectedInstance
      if (service == null) {
        onResult(com.example.phone.PhoneActionResult("Activá Accesibilidad de Insto y reintentá el cronómetro.",
          needsClockAccessibility = true, outcome = com.example.phone.PhoneActionOutcome.REJECTED))
        return {}
      }
      service.clockAutomation?.interrupt()
      val automation = com.example.phone.ClockStopwatchAutomation(service, operation, onResult)
      service.clockAutomation = automation
      // Post so the caller has stored its cancellation handle before a result can arrive.
      service.mainHandler.post { automation.start() }
      return { automation.cancel() }
    }

    fun requestTextSend(pkg: String, request: Long) {
      val service = connectedInstance ?: return
      service.mainHandler.post {
        if (AutoSendCoordinator.isArmedFor(pkg) && AutoSendCoordinator.requestId == request) {
          service.scheduleTextSendPoll(pkg, request)
        }
      }
    }

    fun prepareShareSend(pkg: String, request: Long) {
      val service = connectedInstance ?: return
      if (!AutoSendCoordinator.isArmedFor(pkg) || AutoSendCoordinator.requestId != request) return
      // Record existing window identities before startActivity, never infer freshness from a later event alone.
      val ids = runCatching {
        val current = service.windows.map { it.id }.toMutableSet()
        service.rootInActiveWindow?.let { root ->
          try { current.add(root.windowId) } finally { root.recycle() }
        }
        current.filter { it >= 0 }.toSet()
      }.getOrNull()
      AutoSendCoordinator.prepareShareLaunch(request, ids)
    }

    fun requestShareSend(pkg: String, request: Long) {
      val service = connectedInstance ?: return
      service.mainHandler.post {
        if (AutoSendCoordinator.isArmedFor(pkg) && AutoSendCoordinator.requestId == request) {
          service.scheduleShareSendPoll(pkg, request)
        }
      }
    }

    fun isEnabled(context: Context): Boolean {
      val expected = ComponentName(context, PingAutoSendAccessibilityService::class.java)
      val enabledServices =
        Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

      val splitter = TextUtils.SimpleStringSplitter(':')
      splitter.setString(enabledServices)
      while (splitter.hasNext()) {
        val component = ComponentName.unflattenFromString(splitter.next())
        if (component == expected) {
          return true
        }
      }
      return false
    }
  }
}
