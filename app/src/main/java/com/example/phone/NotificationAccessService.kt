package com.example.phone

import android.service.notification.NotificationListenerService

/** Separate opt-in from media access. Content is queried only for an explicit notification intent. */
class NotificationAccessService : NotificationListenerService() {
  override fun onListenerConnected() { connected=this }
  override fun onListenerDisconnected() { if(connected===this) connected=null }
  override fun onDestroy() { if(connected===this) connected=null;super.onDestroy() }
  companion object {
    @Volatile internal var connected:NotificationAccessService?=null
      private set
  }
}
