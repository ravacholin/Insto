package com.example.testsupport

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.assist.PingAutoSendAccessibilityService
import org.robolectric.Robolectric

object ConnectedAccessibility {
  fun start(context: Context): () -> Unit {
    val controller = Robolectric.buildService(PingAutoSendAccessibilityService::class.java).create()
    val service = controller.get()
    PingAutoSendAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ComponentName(context, PingAutoSendAccessibilityService::class.java).flattenToString())
    return {
      controller.destroy()
      Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }
  }
}
