package com.example.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.audio.EyesFreeFeedbackManager
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.whatsapp.WhatsAppDispatcher
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

  private val volumeKeys = FloatingVolumeKeys(mainHandler, ::openFloatingAssistant)
  private val pill = FloatingPill(this, mainHandler, { cachedPrefs }, ::openFloatingAssistant)

  override fun onServiceConnected() {
    super.onServiceConnected()
    repository = AssistantPreferencesRepository(this)
    pill.connect()

    serviceScope.launch {
      repository.preferencesFlow.collect { prefs ->
        cachedPrefs = prefs
        pill.syncVisibility()
      }
    }

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
    if (AutoSendCoordinator.expectedText != null || AutoSendCoordinator.expectedAudioFileName != null) return // Explicit successful launches drive polling.
    val root = rootInActiveWindow ?: return
    try {
      if (root.packageName?.toString() != pkg) return
      when (AutoSendCoordinator.currentMode()) {
        ArmedActionMode.CLICK_SEND -> handleAutoClickSend(root, pkg)
        ArmedActionMode.NATIVE_PTT_LOCK -> handleNativePttLock(root, pkg)
      }
    } finally { root.recycle() }
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
        if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) {
          mainHandler.postDelayed(this, 180L)
        }
      }
    }
    autoSendPoll = poll
    mainHandler.postDelayed(poll, 450L)
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
        if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) mainHandler.postDelayed(this, 180L)
      }
    }
    autoSendPoll = poll
    mainHandler.postDelayed(poll, 450L)
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

  private fun findTextComposer(root: AccessibilityNodeInfo, pkg: String, expected: String): AccessibilityNodeInfo? {
    for (id in listOf("entry", "message_input")) {
      val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
      val visible = nodes.filter { it.isVisibleToUser && it.isEnabled }
      val chosen = visible.firstOrNull { it.text?.toString() == expected } ?: visible.singleOrNull()
      val result = chosen?.let { AccessibilityNodeInfo.obtain(it) }
      nodes.forEach { it.recycle() }
      if (result != null) return result
    }
    val candidates = mutableListOf<AccessibilityNodeInfo>()
    var visited = 0
    fun walk(node: AccessibilityNodeInfo, depth: Int) {
      if (visited++ >= 800 || depth > 28) return
      if (node.isEditable && node.isVisibleToUser && node.isEnabled) candidates.add(AccessibilityNodeInfo.obtain(node))
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        try { walk(child, depth + 1) } finally { child.recycle() }
      }
    }
    walk(root, 0)
    val chosen = candidates.firstOrNull { it.text?.toString() == expected } ?: candidates.singleOrNull()
    candidates.filter { it != chosen }.forEach { it.recycle() }
    return chosen
  }

  private fun findTextSendTarget(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
    for (id in listOf("send", "send_button", "send_btn", "media_caption_send_btn")) {
      val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
      val chosen = nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
      val result = chosen?.let { AccessibilityNodeInfo.obtain(it) }
      nodes.forEach { it.recycle() }
      if (result != null) return result
    }
    return findSendButtonNode(root, pkg)
  }

  private fun tryTextTouch(root: AccessibilityNodeInfo, pkg: String, request: Long) {
    if (!matchesArmedRecipient(root, pkg)) {
      AutoSendCoordinator.textWaitReason = "El nombre o número del chat no coincide con el contacto configurado."
      return
    }
    val text = AutoSendCoordinator.expectedText ?: return
    val composer = findTextComposer(root, pkg, text)
    val matches = composer?.text?.toString() == text
    composer?.recycle()
    if (!matches) {
      AutoSendCoordinator.textWaitReason = "No se pudo verificar el mensaje en el cuadro de WhatsApp."
      return
    }
    val button = findTextSendTarget(root, pkg)
    if (button == null) { AutoSendCoordinator.textWaitReason = "No se encontró el botón Enviar de WhatsApp."; return }
    val bounds = Rect()
    button.getBoundsInScreen(bounds)
    button.recycle()
    if (!bounds.intersect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) ||
        bounds.width() < 8 || bounds.height() < 8) return
    if (textAttempt?.beginTouch(request, true, true, true) != true) return
    val phone = AutoSendCoordinator.recipientPhone ?: return
    val name = AutoSendCoordinator.recipientName
    val boomerang = AutoSendCoordinator.shouldBoomerangReturn()
    val returnPkg = AutoSendCoordinator.consumeReturnPackage()
    AutoSendCoordinator.disarm() // One gesture only; an uncertain outcome must never send twice.
    val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        confirmTextSubmitted(pkg, request, text, phone, name, boomerang, returnPkg)
      }
      override fun onCancelled(gestureDescription: GestureDescription?) { showUnconfirmedTextHint() }
    }, mainHandler)
    if (!accepted) showUnconfirmedTextHint()
  }

  private fun confirmTextSubmitted(pkg: String, request: Long, text: String, phone: String,
    name: String?, boomerang: Boolean, returnPkg: String?) {
    val deadline = SystemClock.elapsedRealtime() + 3_000
    val check = object : Runnable {
      override fun run() {
        if (AutoSendCoordinator.requestId != request) return
        var cleared = false
        withWhatsAppWindow(pkg) { root ->
          if (matchesRecipient(root, pkg, phone, name, true)) {
            val composer = findTextComposer(root, pkg, text)
            cleared = composer != null && composer.text.isNullOrEmpty()
            composer?.recycle()
          }
        }
        if (cleared) {
          EyesFreeFeedbackManager.playSentCue(this@PingAutoSendAccessibilityService, cachedPrefs.eyesFreeAudioCues)
          if (boomerang) scheduleBoomerangReturn(returnPkg)
        } else if (SystemClock.elapsedRealtime() < deadline) mainHandler.postDelayed(this, 180L)
        else showUnconfirmedTextHint()
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
    return volumeKeys.handle(event, cachedPrefs.volumeKeyTrigger) ?: super.onKeyEvent(event)
  }

  override fun onInterrupt() {
    clockAutomation?.interrupt()
    clockAutomation = null
    autoSendPoll?.let { mainHandler.removeCallbacks(it) }
    volumeKeys.reset()
    AutoSendCoordinator.disarm()
  }

  override fun onDestroy() {
    clockAutomation?.interrupt()
    clockAutomation = null
    autoSendPoll?.let { mainHandler.removeCallbacks(it) }
    if (connectedInstance === this) connectedInstance = null
    pill.disconnect()
    volumeKeys.reset()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun handleAutoClickSend(root: AccessibilityNodeInfo, pkg: String) {
    val request = AutoSendCoordinator.requestId
    if (trySharePositiveStep(root, pkg, request)) return
    if (!matchesArmedRecipient(root, pkg)) {
      AutoSendCoordinator.textWaitReason = "El nombre o número del destinatario del audio no coincide con Instinct."
      return
    }
    val fields = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")
    val composerText = fields.firstOrNull { it.isVisibleToUser }?.text?.toString()
    fields.forEach { it.recycle() }
    val attachmentLabels = listOf("file_name", "filename", "document_name", "audio_title").flatMap { id ->
      val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
      val labels = nodes.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
      nodes.forEach { it.recycle() }
      labels
    }.toMutableList()
    // Audio titles may use a different view ID; require this recording's unique name.
    val fileName = AutoSendCoordinator.expectedAudioFileName
    if (fileName != null && fileName.startsWith("instinct_")) {
      var visited = 0
      fun findName(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (visited++ >= 800 || depth > 28) return false
        if (node.isVisibleToUser && (node.text?.toString() == fileName || node.contentDescription?.toString() == fileName)) return true
        for (i in 0 until node.childCount) {
          val child = node.getChild(i) ?: continue
          try { if (findName(child, depth + 1)) return true } finally { child.recycle() }
        }
        return false
      }
      if (findName(root, 0)) attachmentLabels.add(fileName)
    }
    val exactFileMatches = AutoSendGuard.payloadMatches(AutoSendCoordinator.expectedText ?: AutoSendCoordinator.expectedAttachmentCaption,
      fileName, composerText, attachmentLabels)
    val ownedAudioPreview = AutoSendGuard.ownedAudioPreviewMatches(request,
      AutoSendCoordinator.shareLaunchCommittedRequestId, AutoSendCoordinator.audioPreviewRequestId,
      AutoSendCoordinator.audioPreviewWindowId, root.windowId, fileName, composerText,
      AutoSendCoordinator.expectedAttachmentCaption)
    if (!exactFileMatches && !ownedAudioPreview) {
      AutoSendCoordinator.textWaitReason = "No se pudo verificar esta grabación en la vista de WhatsApp."
      return
    }
    val sendNode = findTextSendTarget(root, pkg)
    if (sendNode == null) { AutoSendCoordinator.textWaitReason = "No se encontró el botón Enviar del audio."; return }
    val bounds = Rect()
    sendNode.getBoundsInScreen(bounds)
    val surface = "${sendNode.viewIdResourceName}|${sendNode.text?.toString()?.takeIf { it.isNotBlank() } ?: sendNode.contentDescription?.toString()}"
    sendNode.recycle()
    if (!bounds.intersect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) || bounds.width() < 8 || bounds.height() < 8) return
    touchFinalShareSend(bounds, pkg, request, root.windowId, surface)
  }

  private fun touchFinalShareSend(bounds: Rect, pkg: String, request: Long, window: Int, surface: String) {
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
        AutoSendCoordinator.disarm()
        showUnconfirmedTextHint()
      }
    }, mainHandler)
    if (!accepted && AutoSendCoordinator.requestId == request) { AutoSendCoordinator.disarm(); showUnconfirmedTextHint() }
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

  private fun findSharePositiveButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
    // WhatsApp's dialog IDs vary between builds. Exact accessible button labels remain
    // usable, and the recipient/payload/freshness policy below still authorizes the touch.
    var labelled: AccessibilityNodeInfo? = null
    var foundConfirmation = false
    var visited = 0
    fun walk(node: AccessibilityNodeInfo, depth: Int) {
      if (visited++ >= 800 || depth > 28) return
      val label = node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString()
      val confirmation = ShareConfirmationPolicy.isConfirmationLabel(label)
      if (node.isVisibleToUser && node.isEnabled &&
        (node.isClickable || node.className?.toString()?.endsWith("Button") == true) &&
        ShareConfirmationPolicy.isPositiveLabel(label) &&
        (labelled == null || confirmation && !foundConfirmation)) {
        labelled?.recycle()
        labelled = AccessibilityNodeInfo.obtain(node)
        foundConfirmation = confirmation
      }
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        try { walk(child, depth + 1) } finally { child.recycle() }
      }
    }
    walk(root, 0)
    labelled?.let { return it }
    val ids = listOf("android:id/button1", "$pkg:id/button1", "$pkg:id/confirm", "$pkg:id/confirm_button", "$pkg:id/ok",
      "$pkg:id/ok_button", "$pkg:id/positive_button", "$pkg:id/dialog_positive_button",
      "$pkg:id/send", "$pkg:id/send_button", "$pkg:id/send_btn", "$pkg:id/media_caption_send_btn")
    for (id in ids) {
      val nodes = root.findAccessibilityNodeInfosByViewId(id)
      val node = nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
      val result = node?.let { AccessibilityNodeInfo.obtain(it) }
      nodes.forEach { it.recycle() }
      if (result != null) return result
    }
    return null
  }

  /** OK/Accept is part of this share, not a reason to disarm before WhatsApp's final Send. */
  private fun trySharePositiveStep(root: AccessibilityNodeInfo, pkg: String, request: Long): Boolean {
    val attempt = shareAttempt ?: return false
    val file = AutoSendCoordinator.expectedAudioFileName ?: return false
    if (!file.startsWith("instinct_") || !file.endsWith(".m4a")) return false
    if (attempt.stepInFlight) return true
    val chatCandidate = attempt.hasCompletedStep && shareSubmittedInChat(root, pkg)
    if (attempt.observeSubmittedChat(root.windowId, SystemClock.elapsedRealtime(), chatCandidate)) {
      val boomerang = AutoSendCoordinator.shouldBoomerangReturn()
      val returnPkg = AutoSendCoordinator.consumeReturnPackage()
      AutoSendCoordinator.disarm()
      EyesFreeFeedbackManager.playSentCue(this, cachedPrefs.eyesFreeAudioCues)
      if (boomerang) scheduleBoomerangReturn(returnPkg)
      return true
    }
    if (chatCandidate) return true // Let the chat settle; inline chat buttons are not share confirmations.
    val positive = findSharePositiveButton(root, pkg) ?: return false
    val label = positive.text?.toString()?.takeIf { it.isNotBlank() } ?: positive.contentDescription?.toString()?.takeIf { it.isNotBlank() }
      ?: if (positive.viewIdResourceName?.contains("send") == true) "Enviar" else null
    val surface = "${positive.viewIdResourceName}|$label"
    val postConfirmation = attempt.isPostConfirmationSurface(root.windowId, surface)
    val postSend = attempt.isPostSendSurface(root.windowId, surface)
    val evidence = collectShareUi(root)
    val composer = findTextComposer(root, pkg, "")
    val composerText = composer?.text?.toString()
    composer?.recycle()
    val allowed = ShareConfirmationPolicy.canConfirm(request, AutoSendCoordinator.shareLaunchCommittedRequestId,
      AutoSendCoordinator.isFreshShareWindow(request, root.windowId) || evidence.labels.any { it == file } || postConfirmation || postSend,
      file, AutoSendCoordinator.expectedAttachmentCaption, composerText, AutoSendCoordinator.recipientName,
      AutoSendCoordinator.recipientPhone.orEmpty(), evidence.labels, label, evidence.hasAudioControls,
      recipientPreviouslyVerified = postConfirmation || postSend)
    val bounds = Rect()
    positive.getBoundsInScreen(bounds)
    positive.recycle()
    if (!allowed) return false
    if (!bounds.intersect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) || bounds.width() < 8 || bounds.height() < 8) return true
    if (attempt.hasConfirmed && !postConfirmation) return true // Same old confirmation still on screen.
    if (!ShareConfirmationPolicy.isConfirmationLabel(label)) {
      touchFinalShareSend(bounds, pkg, request, root.windowId, surface)
      return true
    }
    if (!attempt.beginConfirmation(request, root.windowId, true, surface)) return true
    val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        if (AutoSendCoordinator.requestId == request && AutoSendCoordinator.isArmedFor(pkg)) attempt.confirmationCompleted(request)
      }
      override fun onCancelled(gestureDescription: GestureDescription?) {
        if (AutoSendCoordinator.requestId == request) AutoSendCoordinator.disarm()
        showUnconfirmedTextHint()
      }
    }, mainHandler)
    if (!accepted) { AutoSendCoordinator.disarm(); showUnconfirmedTextHint() }
    return true
  }

  private fun shareSubmittedInChat(root: AccessibilityNodeInfo, pkg: String): Boolean {
    val phone = AutoSendCoordinator.recipientPhone ?: return false
    if (!matchesRecipient(root, pkg, phone, AutoSendCoordinator.recipientName, true)) return false
    val composer = findTextComposer(root, pkg, "")
    val send = findTextSendTarget(root, pkg)
    val chatMic = findNativeVoiceMicNode(root, pkg)
    val submitted = composer != null && composer.text.isNullOrEmpty() && send == null && chatMic != null
    composer?.recycle()
    send?.recycle()
    chatMic?.recycle()
    return submitted
  }

  private fun confirmShareSubmitted(pkg: String, request: Long, phone: String, name: String?, boomerang: Boolean, returnPkg: String?) {
    val deadline = SystemClock.elapsedRealtime() + 3_000
    val check = object : Runnable {
      override fun run() {
        if (AutoSendCoordinator.requestId != request) return
        var submitted = false
        withWhatsAppWindow(pkg) { root ->
          if (matchesRecipient(root, pkg, phone, name, true)) {
            val composer = findTextComposer(root, pkg, "")
            val send = findTextSendTarget(root, pkg)
            submitted = composer != null && composer.text.isNullOrEmpty() && send == null
            composer?.recycle()
            send?.recycle()
          }
        }
        if (submitted) {
          EyesFreeFeedbackManager.playSentCue(this@PingAutoSendAccessibilityService, cachedPrefs.eyesFreeAudioCues)
          if (boomerang) scheduleBoomerangReturn(returnPkg)
        } else if (SystemClock.elapsedRealtime() < deadline) mainHandler.postDelayed(this, 180L)
        else showUnconfirmedTextHint()
      }
    }
    mainHandler.postDelayed(check, 250L)
  }

  /** Fail closed: never click Send in a different chat or an unrecognized share screen. */
  private fun matchesArmedRecipient(root: AccessibilityNodeInfo, pkg: String): Boolean {
    val phone = AutoSendCoordinator.recipientPhone ?: return true // Legacy explicit actions.
    return matchesRecipient(root, pkg, phone, AutoSendCoordinator.recipientName,
      AutoSendGuard.launchMatches(AutoSendCoordinator.requestId, AutoSendCoordinator.expectedText,
        AutoSendCoordinator.expectedAudioFileName, AutoSendCoordinator.textLaunchCommittedRequestId,
        AutoSendCoordinator.shareLaunchCommittedRequestId))
  }

  private fun matchesRecipient(root: AccessibilityNodeInfo, pkg: String, phone: String,
    name: String?, launchCommitted: Boolean): Boolean {
    val headers = listOf("conversation_contact_name", "recipient_name", "toolbar_title", "action_bar_title").flatMap { id ->
      val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
      val labels = nodes.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString()?.trim() }
      nodes.forEach { it.recycle() }
      labels
    }
    return AutoSendGuard.recipientMatches(phone, headers, name, openedConversation = launchCommitted)
  }

  private fun handleNativePttLock(root: AccessibilityNodeInfo, pkg: String) {
    val micNode = findNativeVoiceMicNode(root, pkg) ?: return
    val bounds = Rect()
    micNode.getBoundsInScreen(bounds)
    micNode.recycle()

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

  private fun findSendButtonNode(
    root: AccessibilityNodeInfo,
    packageName: String,
  ): AccessibilityNodeInfo? {
    val candidateIds =
      listOf(
        "$packageName:id/send",
        "$packageName:id/send_button",
        "$packageName:id/media_caption_send_btn",
      )
    for (viewId in candidateIds) {
      val matches = root.findAccessibilityNodeInfosByViewId(viewId)
      if (!matches.isNullOrEmpty()) {
        val clickable = matches.firstOrNull { it.isClickable && it.isVisibleToUser }
        matches.filter { it != clickable }.forEach { it.recycle() }
        if (clickable != null) return clickable
      }
    }

    val sendLabels = listOf("Send", "Enviar", "Invia", "Envoyer", "Senden")
    for (label in sendLabels) {
      val textMatches = root.findAccessibilityNodeInfosByText(label)
      if (!textMatches.isNullOrEmpty()) {
        val clickable =
          textMatches.firstOrNull { node ->
            node.isClickable &&
              node.isVisibleToUser &&
              (node.contentDescription?.toString()?.equals(label, ignoreCase = true) == true ||
                node.text?.toString()?.equals(label, ignoreCase = true) == true)
          }
        textMatches.filter { it != clickable }.forEach { it.recycle() }
        if (clickable != null) return clickable
      }
    }

    return findClickableByKeywords(
      node = root,
      keywords = sendLabels,
      idSuffixes = listOf(":id/send", ":id/send_button"),
    )
  }

  private fun findNativeVoiceMicNode(
    root: AccessibilityNodeInfo,
    packageName: String,
  ): AccessibilityNodeInfo? {
    val micIds = listOf("$packageName:id/voice_note_btn", "$packageName:id/record_button")
    for (viewId in micIds) {
      val matches = root.findAccessibilityNodeInfosByViewId(viewId)
      if (!matches.isNullOrEmpty()) {
        val visible = matches.firstOrNull { it.isVisibleToUser }
        matches.filter { it != visible }.forEach { it.recycle() }
        if (visible != null) return visible
      }
    }

    val micLabels =
      listOf(
        "Voice message",
        "Mensaje de voz",
        "Nota de voz",
        "Grabar mensaje de voz",
        "Record voice message",
      )
    return findClickableByKeywords(
      node = root,
      keywords = micLabels,
      idSuffixes = listOf(":id/voice_note_btn"),
    )
  }

  private fun findClickableByKeywords(
    node: AccessibilityNodeInfo,
    keywords: List<String>,
    idSuffixes: List<String>,
  ): AccessibilityNodeInfo? {
    if (node.isClickable && node.isVisibleToUser) {
      val desc = node.contentDescription?.toString().orEmpty()
      val viewId = node.viewIdResourceName.orEmpty()
      if (
        keywords.any { desc.contains(it, ignoreCase = true) } ||
          idSuffixes.any { viewId.endsWith(it) }
      ) {
        return AccessibilityNodeInfo.obtain(node)
      }
    }
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val match = findClickableByKeywords(child, keywords, idSuffixes)
      child.recycle()
      if (match != null) return match
    }
    return null
  }

  companion object {
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
