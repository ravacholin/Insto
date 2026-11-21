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
  /** The phone's state for Instinct (1.16.0): system changes signal a publication; nothing is read from notifications. */
  private val state by lazy { InstinctStateWatcher(applicationContext) }

  override fun onListenerConnected() { connected=this; runCatching { state.start() } }
  override fun onListenerDisconnected() { if(connected===this) connected=null; runCatching { state.stop() } }
  override fun onDestroy() { if(connected===this) connected=null; runCatching { state.stop() }; super.onDestroy() }

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
