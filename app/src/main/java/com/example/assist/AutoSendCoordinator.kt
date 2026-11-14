package com.example.assist

import android.os.SystemClock

enum class ArmedActionMode {
  CLICK_SEND,
  NATIVE_PTT_LOCK,
}

/**
 * Coordinates short-lived, explicit automation windows between PingAssist and the optional
 * PingAutoSendAccessibilityService.
 */
object AutoSendCoordinator {

  private const val DEFAULT_ARM_WINDOW_MS = 10_000L

  @Volatile private var armedUntilElapsedMs: Long = 0L

  @Volatile private var expectedPackage: String? = null

  @Volatile private var armedMode: ArmedActionMode = ArmedActionMode.CLICK_SEND

  @Volatile private var boomerangEnabled: Boolean = false

  @Volatile private var returnPackageName: String? = null

  @Volatile var lastKnownForegroundPackage: String? = null

  fun arm(
    targetPackage: String,
    mode: ArmedActionMode = ArmedActionMode.CLICK_SEND,
    boomerangReturn: Boolean = false,
    durationMs: Long = DEFAULT_ARM_WINDOW_MS,
  ) {
    expectedPackage = targetPackage
    armedMode = mode
    boomerangEnabled = boomerangReturn
    returnPackageName = if (boomerangReturn) lastKnownForegroundPackage else null
    armedUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
  }

  fun isArmedFor(packageName: String?): Boolean {
    if (packageName == null) return false
    val until = armedUntilElapsedMs
    val expected = expectedPackage ?: return false
    if (SystemClock.elapsedRealtime() > until) {
      disarm()
      return false
    }
    return packageName == expected
  }

  fun currentMode(): ArmedActionMode = armedMode

  fun shouldBoomerangReturn(): Boolean = boomerangEnabled

  fun consumeReturnPackage(): String? {
    val pkg = returnPackageName
    returnPackageName = null
    boomerangEnabled = false
    return pkg
  }

  fun disarm() {
    armedUntilElapsedMs = 0L
    expectedPackage = null
  }
}
