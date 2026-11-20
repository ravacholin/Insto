package com.example.assist

import android.content.Context
import com.example.data.WhatsAppFlavor
import com.example.whatsapp.WhatsAppDispatcher

/** Reads the current [AutoSendHealth] from the system and the local metrics. No network, no side effects. */
object AutoSendHealthReader {
  fun read(context: Context, preferred: WhatsAppFlavor): AutoSendHealth.Status {
    val pkg = WhatsAppDispatcher.resolveTargetPackage(context, preferred)
    val installed = pkg?.let { packageName ->
      val flavor = AutoSendMetrics.Flavor.of(packageName) ?: return@let null
      val code = AutoSendMetricsStore.versionCode(context, packageName) ?: return@let null
      AutoSendHealth.Installed(flavor, code, AutoSendMetricsStore.versionName(context, packageName))
    }
    return AutoSendHealth.evaluate(
      PingAutoSendAccessibilityService.isEnabled(context),
      PingAutoSendAccessibilityService.isConnected(),
      installed,
      AutoSendMetricsStore(context).read(),
    )
  }
}
