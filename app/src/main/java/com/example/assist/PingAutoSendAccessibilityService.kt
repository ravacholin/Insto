package com.example.assist

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
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
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.EyesFreeFeedbackManager
import com.example.audio.VoiceRecorderManager
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesRepository
import com.example.data.FloatingPillMode
import com.example.data.PingActionLog
import com.example.data.PingActionType
import com.example.data.VolumeKeyTrigger
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import java.util.UUID
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
 * 3) Optional physical Volume-hold voice recording trigger.
 * 4) Optional Floating Accessibility Pill (TYPE_ACCESSIBILITY_OVERLAY, no SYSTEM_ALERT_WINDOW).
 * 5) Boomerang auto-return to the previous foreground app (e.g., Maps / Waze) after sending.
 */
class PingAutoSendAccessibilityService : AccessibilityService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mainHandler = Handler(Looper.getMainLooper())

  private lateinit var repository: AssistantPreferencesRepository
  private lateinit var backgroundRecorder: VoiceRecorderManager

  @Volatile private var cachedPrefs = AssistantPreferences()

  // Volume key hold state
  private var volumeHoldRunnable: Runnable? = null
  private var volumeHoldTriggered = false
  private var volumeHoldElapsed = false
  private var volumeHoldStartedAtMs = 0L

  // Floating pill overlay state
  private var windowManager: WindowManager? = null
  private var pillView: ImageView? = null
  private var pillParams: WindowManager.LayoutParams? = null
  private var pillHoldRunnable: Runnable? = null
  private var isPillRecording = false
  private var pillRecordingStartMs = 0L

  private var audioDeviceCallback: AudioDeviceCallback? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    repository = AssistantPreferencesRepository(this)
    backgroundRecorder = VoiceRecorderManager(this)
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

    val root = rootInActiveWindow ?: return

    when (AutoSendCoordinator.currentMode()) {
      ArmedActionMode.CLICK_SEND -> handleAutoClickSend(root, pkg)
      ArmedActionMode.NATIVE_PTT_LOCK -> handleNativePttLock(root, pkg)
    }
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

    // The key is consumed so that holding it to record never changes the volume; a short tap
    // applies its one volume step on release instead.
    when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount == 0) {
          volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
          volumeHoldTriggered = false
          volumeHoldElapsed = false
          val holdTask = Runnable {
            volumeHoldElapsed = true
            volumeHoldTriggered = startBackgroundVoiceCapture()
          }
          volumeHoldRunnable = holdTask
          mainHandler.postDelayed(holdTask, 420L)
        }
        return true
      }
      KeyEvent.ACTION_UP -> {
        volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        volumeHoldRunnable = null
        if (volumeHoldTriggered) {
          volumeHoldTriggered = false
          stopAndDispatchBackgroundVoice(startedAtMs = volumeHoldStartedAtMs)
        } else if (!volumeHoldElapsed) {
          applyVolumeStep(raise = targetKeyCode == KeyEvent.KEYCODE_VOLUME_UP)
        }
        return true
      }
    }
    return super.onKeyEvent(event)
  }

  private fun applyVolumeStep(raise: Boolean) {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    // Can throw when the step would toggle Do Not Disturb without policy access.
    runCatching {
      audioManager.adjustSuggestedStreamVolume(
        if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        AudioManager.USE_DEFAULT_STREAM_TYPE,
        AudioManager.FLAG_SHOW_UI,
      )
    }
  }

  override fun onInterrupt() {
    volumeHoldRunnable?.let { mainHandler.removeCallbacks(it) }
    volumeHoldRunnable = null
    HotkeyHoldTracker.reset()
    AutoSendCoordinator.disarm()
    backgroundRecorder.cancelRecording()
  }

  override fun onDestroy() {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
    removeFloatingPill()
    HotkeyHoldTracker.reset()
    backgroundRecorder.release()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun handleAutoClickSend(root: AccessibilityNodeInfo, pkg: String) {
    val sendNode = findSendButtonNode(root, pkg) ?: return
    if (sendNode.isEnabled) {
      val clicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      if (clicked) {
        val boomerang = AutoSendCoordinator.shouldBoomerangReturn()
        val returnPkg = AutoSendCoordinator.consumeReturnPackage()
        AutoSendCoordinator.disarm()
        EyesFreeFeedbackManager.playSentCue(this, cachedPrefs.eyesFreeAudioCues)
        if (boomerang) {
          scheduleBoomerangReturn(returnPkg)
        }
      }
    }
    sendNode.recycle()
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

  private fun startBackgroundVoiceCapture(): Boolean {
    val hasMicPermission =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasMicPermission) {
      EyesFreeFeedbackManager.playErrorCue(this, cachedPrefs.eyesFreeAudioCues)
      return false
    }

    val route = ContextRoutingEngine.resolveRoute(this, cachedPrefs)
    if (route.contact == null) {
      EyesFreeFeedbackManager.playErrorCue(this, cachedPrefs.eyesFreeAudioCues)
      return false
    }

    val result = backgroundRecorder.startRecording()
    if (result.isFailure) {
      EyesFreeFeedbackManager.playErrorCue(this, cachedPrefs.eyesFreeAudioCues)
      return false
    }

    volumeHoldStartedAtMs = SystemClock.elapsedRealtime()
    EyesFreeFeedbackManager.playStartCue(this, cachedPrefs.eyesFreeAudioCues)
    return true
  }

  private fun stopAndDispatchBackgroundVoice(startedAtMs: Long) {
    val remaining = backgroundRecorder.remainingMinDurationMillis(minDurationMs = 650L)
    if (remaining > 0L) {
      mainHandler.postDelayed({ finishStopAndDispatchBackgroundVoice(startedAtMs) }, remaining)
    } else {
      finishStopAndDispatchBackgroundVoice(startedAtMs)
    }
  }

  private fun finishStopAndDispatchBackgroundVoice(startedAtMs: Long) {
    val recorded = backgroundRecorder.stopRecording()
    val durationSec =
      (((SystemClock.elapsedRealtime() - startedAtMs) / 1000L).toInt()).coerceAtLeast(1)

    if (recorded == null) {
      EyesFreeFeedbackManager.playErrorCue(this, cachedPrefs.eyesFreeAudioCues)
      return
    }

    val prefs = cachedPrefs
    val route = ContextRoutingEngine.resolveRoute(this, prefs)
    val contact = route.contact
    if (contact == null) {
      recorded.file.delete()
      EyesFreeFeedbackManager.playErrorCue(this, prefs.eyesFreeAudioCues)
      return
    }

    val dispatch =
      WhatsAppDispatcher.sendWhatsAppAudio(
        context = this,
        phoneNumber = contact.phoneNumber,
        audioFile = recorded.file,
        preferredFlavor = prefs.preferredFlavor,
        isAutoSendServiceEnabled = true,
        boomerangReturn = prefs.boomerangReturnEnabled,
        mimeType = recorded.mimeType,
      )

    if (dispatch is DispatchResult.AudioDispatched) {
      serviceScope.launch {
        repository.appendActionLog(
          PingActionLog(
            id = UUID.randomUUID().toString(),
            type = PingActionType.AUDIO_SENT,
            contactName = contact.displayName,
            timestampMillis = System.currentTimeMillis(),
            durationSeconds = durationSec,
            statusLabel = "Auto-sent ${durationSec}s audio",
          )
        )
      }
    } else {
      EyesFreeFeedbackManager.playErrorCue(this, prefs.eyesFreeAudioCues)
    }
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
        FloatingPillMode.ALWAYS_ON -> route.contact != null
        FloatingPillMode.CAR_OR_BLUETOOTH_ONLY ->
          route.contact != null && route.isCarOrBluetoothActive
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
              isPillRecording = startBackgroundVoiceCapture()
              if (isPillRecording) {
                pillRecordingStartMs = SystemClock.elapsedRealtime()
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
            stopAndDispatchBackgroundVoice(startedAtMs = pillRecordingStartMs)
          } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
            // Single tap -> open resolved WhatsApp contact directly!
            val route = ContextRoutingEngine.resolveRoute(this, cachedPrefs)
            route.contact?.let { contact ->
              WhatsAppDispatcher.openWhatsAppChat(this, contact.phoneNumber, cachedPrefs.preferredFlavor)
            }
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
