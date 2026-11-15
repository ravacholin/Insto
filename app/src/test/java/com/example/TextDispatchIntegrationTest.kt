package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutoSendCoordinator
import com.example.assist.PingAutoSendAccessibilityService
import com.example.data.WhatsAppFlavor
import com.example.whatsapp.DispatchResult
import com.example.whatsapp.WhatsAppDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TextDispatchIntegrationTest {
  @Test fun `enabled but disconnected helper preserves draft instead of opening WhatsApp`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    shadowOf(context.packageManager).installPackage(PackageInfo().apply {
      packageName = "com.whatsapp"
      applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp" }
    })
    assertFalse(PingAutoSendAccessibilityService.isConnected())
    val result = WhatsAppDispatcher.sendWhatsAppText(context, "+447700900123", "Hola",
      WhatsAppFlavor.WHATSAPP, true, "Instinct")
    assertTrue(result is DispatchResult.Error)
    assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
  }

  @Test fun `only successful launch of current request enables text automation`() {
    AutoSendCoordinator.arm("com.whatsapp", recipientPhone = "+447700900123", expectedText = "Hola")
    val request = AutoSendCoordinator.requestId
    assertEquals(-1L, AutoSendCoordinator.textLaunchCommittedRequestId)
    AutoSendCoordinator.markTextLaunchCommitted(request - 1)
    assertEquals(-1L, AutoSendCoordinator.textLaunchCommittedRequestId)
    AutoSendCoordinator.markTextLaunchCommitted(request)
    assertEquals(request, AutoSendCoordinator.textLaunchCommittedRequestId)
    AutoSendCoordinator.disarm()
    assertEquals(-1L, AutoSendCoordinator.textLaunchCommittedRequestId)
  }
}
