package com.example.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.alarmvoice.AlarmVoiceCoordinator
import com.example.alarmvoice.AlarmVoiceSettings
import com.example.alarmvoice.AlarmVolumeDucker

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

  override fun onListenerConnected() {
    connected=this; runCatching { state.start() }
    // «Callar alarma con la voz»: a volume lowered by a process that died is restored here.
    runCatching { AlarmVolumeDucker.restoreLeftover(this) }
  }
  override fun onListenerDisconnected() { if(connected===this) connected=null; runCatching { state.stop() } }
  override fun onDestroy() { if(connected===this) connected=null; runCatching { state.stop() }; super.onDestroy() }

  internal fun calendarPermissionChanged() { state.watchCalendar() }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    // «Callar alarma con la voz» (1.25.0): its own branch, before the proposals' early return and independent of it.
    // With its switch off nothing runs; on, only the package is read first, and only a known clock's category,
    // structural flags and action titles after that.
    if (sbn != null && AlarmVoiceSettings(this).isEnabled()) runCatching { AlarmVoiceCoordinator.onPosted(this, sbn) }
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

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    // Only compares the key with the ringing alarm kept in memory; nothing of the notification is read.
    val key = sbn?.key ?: return
    if (AlarmVoiceCoordinator.active != null) runCatching { AlarmVoiceCoordinator.onRemoved(this, key) }
  }

  companion object {
    @Volatile internal var connected:NotificationAccessService?=null
      private set
  }
}
