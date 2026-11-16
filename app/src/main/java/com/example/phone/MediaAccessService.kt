package com.example.phone

import android.service.notification.NotificationListenerService

/** Access is used solely to resolve active MediaSessions, never to read message content. */
class MediaAccessService : NotificationListenerService()
