package com.example.phone

import android.service.notification.NotificationListenerService

/** Access is used solely to resolve active MediaSessions, never to read message content. */
class MediaAccessService : NotificationListenerService() {
  override fun onListenerConnected() {
    super.onListenerConnected()
    CapabilityHealthRuntime.current()?.report("notifications.media", HealthCode.VERIFIED)
  }

  override fun onListenerDisconnected() {
    CapabilityHealthRuntime.current()?.report("notifications.media", HealthCode.SYSTEM)
    super.onListenerDisconnected()
  }
}
