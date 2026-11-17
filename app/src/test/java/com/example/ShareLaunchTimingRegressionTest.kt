package com.example

import android.os.SystemClock
import com.example.assist.AutoSendCoordinator
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShareLaunchTimingRegressionTest {
  @After fun disarm() = AutoSendCoordinator.disarm()

  private fun prepare(windows: Set<Int>): Long {
    AutoSendCoordinator.arm("com.whatsapp", recipientName = "Instinct", recipientPhone = "+447700900123",
      expectedAudioFileName = "instinct_12345678.m4a")
    return AutoSendCoordinator.requestId.also { AutoSendCoordinator.prepareShareLaunch(it, windows) }
  }

  @Test fun dialogCreatedInsideStartActivityRemainsFreshWhenItsEventArrivesAfterCommit() {
    val request = prepare(setOf(41))
    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    val createdAt = SystemClock.uptimeMillis()
    ShadowSystemClock.advanceBy(Duration.ofMillis(25))
    AutoSendCoordinator.markShareLaunchCommitted(request)
    AutoSendCoordinator.observeShareWindow("com.whatsapp", "android.app.Dialog", createdAt, 42)
    assertTrue(AutoSendCoordinator.isFreshShareWindow(request, 42))
  }

  @Test fun eventDeliveredDuringLaunchIsRememberedButCannotAuthorizeAnUncommittedShare() {
    val request = prepare(setOf(41))
    AutoSendCoordinator.observeShareWindow("com.whatsapp", "android.app.Dialog", SystemClock.uptimeMillis(), 42)
    assertFalse(AutoSendCoordinator.isFreshShareWindow(request, 42))
    AutoSendCoordinator.markShareLaunchCommitted(request)
    assertTrue(AutoSendCoordinator.isFreshShareWindow(request, 42))
  }

  @Test fun successfulEmptyWindowSnapshotDoesNotBlockTheNewDialog() {
    val request = prepare(emptySet())
    AutoSendCoordinator.markShareLaunchCommitted(request)
    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    AutoSendCoordinator.observeShareWindow("com.whatsapp", "android.app.Dialog", SystemClock.uptimeMillis(), 42)
    assertTrue(AutoSendCoordinator.isFreshShareWindow(request, 42))
  }

  @Test fun oldWindowsAndEventsAndOtherPackagesCannotAuthorizeTheShare() {
    val oldEventAt = SystemClock.uptimeMillis()
    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    val request = prepare(setOf(41))
    AutoSendCoordinator.markShareLaunchCommitted(request)
    AutoSendCoordinator.observeShareWindow("com.whatsapp", "android.app.Dialog", oldEventAt, 42)
    AutoSendCoordinator.observeShareWindow("com.whatsapp", "android.app.Dialog", SystemClock.uptimeMillis(), 41)
    AutoSendCoordinator.observeShareWindow("com.other", "android.app.Dialog", SystemClock.uptimeMillis(), 43)
    assertFalse(AutoSendCoordinator.isFreshShareWindow(request, 41))
    assertFalse(AutoSendCoordinator.isFreshShareWindow(request, 42))
    assertFalse(AutoSendCoordinator.isFreshShareWindow(request, 43))
    AutoSendCoordinator.arm("com.whatsapp", expectedAudioFileName = "instinct_next.m4a")
    AutoSendCoordinator.markShareLaunchCommitted(request)
    assertFalse(AutoSendCoordinator.isFreshShareWindow(request, 42))
  }

  @Test fun missingSnapshotDoesNotCountAsASuccessfulEmptySnapshot() {
    AutoSendCoordinator.arm("com.whatsapp", expectedAudioFileName = "instinct_12345678.m4a")
    val request = AutoSendCoordinator.requestId
    AutoSendCoordinator.markShareLaunchCommitted(request)
    AutoSendCoordinator.observeShareWindow("com.whatsapp", "android.app.Dialog", SystemClock.uptimeMillis(), 42)
    assertFalse(AutoSendCoordinator.isFreshShareWindow(request, 42))
  }
}
