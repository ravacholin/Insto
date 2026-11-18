package com.example.assist

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.example.R
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantPreferences
import com.example.data.FloatingPillMode
import kotlin.math.abs

internal class FloatingPill(
  private val context: Context,
  private val mainHandler: Handler,
  private val preferences: () -> AssistantPreferences,
  private val openFloatingAssistant: () -> Boolean,
) {
  // Floating pill overlay state
  private var windowManager: WindowManager? = null
  private var pillView: ImageView? = null
  private var pillHoldRunnable: Runnable? = null
  private var isPillRecording = false

  private var audioDeviceCallback: AudioDeviceCallback? = null

  fun connect() {
    windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val callback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
          syncVisibility()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
          syncVisibility()
        }
      }
    audioDeviceCallback = callback
    audioManager?.registerAudioDeviceCallback(callback, mainHandler)
  }

  fun disconnect() {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
    audioDeviceCallback = null
    removeFloatingPill()
  }

  fun syncVisibility() {
    val prefs = preferences()
    val route = ContextRoutingEngine.resolveRoute(context, prefs)
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
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 52f, context.resources.displayMetrics).toInt()
    val view =
      ImageView(context).apply {
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
          x = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics).toInt()
          y = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220f, context.resources.displayMetrics).toInt()
        }

    attachPillTouchListener(view, params, wm)

    runCatching {
      wm.addView(view, params)
      pillView = view
    }
  }

  private fun attachPillTouchListener(
    view: ImageView,
    params: WindowManager.LayoutParams,
    wm: WindowManager,
  ) {
    val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(36f)
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

  fun removeFloatingPill() {
    pillHoldRunnable?.let { mainHandler.removeCallbacks(it) }
    pillHoldRunnable = null
    val view = pillView ?: return
    runCatching { windowManager?.removeView(view) }
    pillView = null
  }

  private fun buildPillDrawable(isRecording: Boolean): GradientDrawable {
    val strokePx =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics).toInt()
    return GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(if (isRecording) Color.parseColor("#FF5468") else Color.parseColor("#E6101B18"))
      setStroke(strokePx, if (isRecording) Color.WHITE else Color.parseColor("#10D98B"))
    }
  }

}
