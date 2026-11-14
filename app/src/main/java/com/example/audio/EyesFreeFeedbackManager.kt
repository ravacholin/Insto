package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Eyes-free audio and haptic cues that temporarily duck media (e.g., car Bluetooth music) and play
 * distinct tones/vibrations for Start Recording, Sent Confirmation, and Error states.
 */
object EyesFreeFeedbackManager {

  private val mainHandler = Handler(Looper.getMainLooper())

  fun playStartCue(context: Context, audioCuesEnabled: Boolean) {
    vibratePattern(context, longArrayOf(0L, 45L, 55L, 65L))
    if (audioCuesEnabled) {
      playToneWithDucking(context, ToneGenerator.TONE_PROP_BEEP2, durationMs = 150)
    }
  }

  fun playSentCue(context: Context, audioCuesEnabled: Boolean) {
    vibratePattern(context, longArrayOf(0L, 90L))
    if (audioCuesEnabled) {
      playToneWithDucking(context, ToneGenerator.TONE_PROP_ACK, durationMs = 180)
    }
  }

  fun playErrorCue(context: Context, audioCuesEnabled: Boolean) {
    vibratePattern(context, longArrayOf(0L, 60L, 60L, 60L, 60L, 90L))
    if (audioCuesEnabled) {
      playToneWithDucking(context, ToneGenerator.TONE_PROP_NACK, durationMs = 240)
    }
  }

  private fun playToneWithDucking(context: Context, toneType: Int, durationMs: Int) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    var focusRequest: AudioFocusRequest? = null

    if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val attrs =
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(attrs)
          .build()
      runCatching { audioManager.requestAudioFocus(focusRequest) }
    }

    runCatching {
      val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
      generator.startTone(toneType, durationMs)
      mainHandler.postDelayed(
        {
          runCatching { generator.release() }
          if (audioManager != null &&
            focusRequest != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
          ) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
          }
        },
        durationMs.toLong() + 120L,
      )
    }
  }

  private fun vibratePattern(context: Context, timings: LongArray) {
    runCatching {
      val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val manager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
          manager?.defaultVibrator
        } else {
          @Suppress("DEPRECATION")
          context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

      if (!vibrator.hasVibrator()) return

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
      } else {
        @Suppress("DEPRECATION") vibrator.vibrate(timings, -1)
      }
    }
  }
}
