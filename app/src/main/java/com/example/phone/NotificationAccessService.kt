package com.example.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Separate opt-in from media access. Content is queried for an explicit notification intent and, only with the
 * separate switch «Recibir propuestas de Instinct» on, the newest message of the configured contact's WhatsApp chat
 * is read to look for proposal lines (see [InstinctProposalIntake]). With that switch off a posted notification is
 * not read at all. With «Responderle a Instinct» on, the «Responder» action of an accepted message is kept in memory
 * to acknowledge the orders ([InstinctReplyHandle]).
 */
class NotificationAccessService : NotificationListenerService() {
  override fun onListenerConnected() { connected=this }
  override fun onListenerDisconnected() { if(connected===this) connected=null }
  override fun onDestroy() { if(connected===this) connected=null;super.onDestroy() }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    // Checked before touching the notification: with the switch off nothing of it is read.
    if (sbn == null || !InstinctProposalSettings(this).isEnabled()) return
    runCatching {
      InstinctProposalIntake.onPosted(this, {
        ExtrasProposalNotification(sbn.packageName, sbn.key) { sbn.notification.extras }
      }) {
        // Only WhatsApp's «Responder» action, only for an accepted message and only with replies on: kept in memory.
        InstinctReplyHandle.from(sbn.notification)
      }
    }
  }

  companion object {
    @Volatile internal var connected:NotificationAccessService?=null
      private set
  }
}
