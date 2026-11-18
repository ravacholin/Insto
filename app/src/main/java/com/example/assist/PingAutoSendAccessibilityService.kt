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
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
  private var clockAutomation: com.example.phone.ClockStopwatchAutomation? = null
  private var pendingReturn: Runnable? = null
  private val sendAutomation = WhatsAppSendAutomation(
    this, mainHandler,
    onSent = { EyesFreeFeedbackManager.playSentCue(this, cachedPrefs.eyesFreeAudioCues) },
    onUnconfirmed = ::showUnconfirmedTextHint,
    onReturn = ::scheduleBoomerangReturn,
  )

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
        ArmedActionMode.CLICK_SEND -> sendAutomation.inspectLegacyShare(root, pkg)
        ArmedActionMode.NATIVE_PTT_LOCK -> handleNativePttLock(root, pkg)
      }
    } finally { root.recycle() }
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
    sendAutomation.stop()
    pendingReturn?.let(mainHandler::removeCallbacks)
    pendingReturn = null
    volumeKeys.reset()
    AutoSendCoordinator.disarm()
  }

  override fun onDestroy() {
    clockAutomation?.interrupt()
    clockAutomation = null
    sendAutomation.stop()
    pendingReturn?.let(mainHandler::removeCallbacks)
    pendingReturn = null
    if (connectedInstance === this) connectedInstance = null
    pill.disconnect()
    volumeKeys.reset()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun handleNativePttLock(root: AccessibilityNodeInfo, pkg: String) {
    val mic = WhatsAppScreenReader(root.snapshot(), pkg).nativeVoiceMic() ?: return
    val bounds = Rect(mic.bounds.left, mic.bounds.top, mic.bounds.right, mic.bounds.bottom)

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

  private fun scheduleBoomerangReturn(request: Long, returnPkg: String?) {
    pendingReturn?.let(mainHandler::removeCallbacks)
    val task = Runnable {
      pendingReturn = null
      if (AutoSendCoordinator.requestId != request) return@Runnable
      if (returnPkg != null) {
        val launch = packageManager.getLaunchIntentForPackage(returnPkg)
        if (launch != null) {
          launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
          val launched = runCatching { startActivity(launch); true }.getOrDefault(false)
          if (launched) return@Runnable
        }
      }
      performGlobalAction(GLOBAL_ACTION_BACK)
    }
    pendingReturn = task
    mainHandler.postDelayed(task, 750L)
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
          service.sendAutomation.startText(pkg, request)
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
          service.sendAutomation.startShare(pkg, request)
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
