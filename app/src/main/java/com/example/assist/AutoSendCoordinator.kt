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

  @Volatile var requestId: Long = 0
    private set

  @Volatile var armedAtUptimeMs: Long = 0
    private set

  @Volatile var textLaunchCommittedRequestId: Long = -1
    private set
  @Volatile var textWaitReason: String = "Esperando el chat de WhatsApp."

  private const val DEFAULT_ARM_WINDOW_MS = 10_000L

  @Volatile private var armedUntilElapsedMs: Long = 0L

  @Volatile private var expectedPackage: String? = null

  @Volatile private var armedMode: ArmedActionMode = ArmedActionMode.CLICK_SEND

  @Volatile private var boomerangEnabled: Boolean = false

  @Volatile var recipientName: String? = null
    private set
  @Volatile var recipientPhone: String? = null
    private set
  @Volatile var expectedAudioFileName: String? = null
    private set
  @Volatile var expectedAttachmentCaption: String? = null
    private set
  @Volatile var expectedText: String? = null
    private set

  @Volatile private var returnPackageName: String? = null

  @Volatile var lastKnownForegroundPackage: String? = null

  fun arm(
    targetPackage: String,
    mode: ArmedActionMode = ArmedActionMode.CLICK_SEND,
    boomerangReturn: Boolean = false,
    durationMs: Long = DEFAULT_ARM_WINDOW_MS,
    recipientName: String? = null,
    recipientPhone: String? = null,
    expectedText: String? = null,
    expectedAudioFileName: String? = null,
    returnPackage: String? = null,
    expectedAttachmentCaption: String? = null,
  ) {
    requestId++
    textLaunchCommittedRequestId = -1
    textWaitReason = "Esperando el chat de WhatsApp."
    armedAtUptimeMs = SystemClock.uptimeMillis()
    this.recipientName = recipientName
    this.recipientPhone = recipientPhone
    this.expectedAttachmentCaption = expectedAttachmentCaption
    this.expectedText = expectedText
    this.expectedAudioFileName = expectedAudioFileName
    expectedPackage = targetPackage
    armedMode = mode
    boomerangEnabled = boomerangReturn
    returnPackageName = if (boomerangReturn) returnPackage ?: lastKnownForegroundPackage else null
    armedUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
  }

  fun markTextLaunchCommitted(request: Long) {
    if (requestId == request && expectedText != null) textLaunchCommittedRequestId = request
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
    recipientName = null
    recipientPhone = null
    expectedAttachmentCaption = null
    expectedText = null
    textLaunchCommittedRequestId = -1
    expectedAudioFileName = null
  }
}
