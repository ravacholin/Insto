package com.example.assist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AssistantPreferencesRepository
import kotlinx.coroutines.runBlocking

class PingAssistWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val contactName = runBlocking {
      AssistantPreferencesRepository(context).getCurrentPreferences().pinnedContact?.displayName
    }
    for (widgetId in appWidgetIds) {
      updateWidget(context, appWidgetManager, widgetId, contactName)
    }
  }

  companion object {
    fun requestUpdate(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val component = ComponentName(context, PingAssistWidgetProvider::class.java)
      val ids = manager.getAppWidgetIds(component)
      if (ids.isEmpty()) return
      val contactName = runBlocking {
        AssistantPreferencesRepository(context).getCurrentPreferences().pinnedContact?.displayName
      }
      ids.forEach { id -> updateWidget(context, manager, id, contactName) }
    }

    private fun updateWidget(
      context: Context,
      manager: AppWidgetManager,
      widgetId: Int,
      contactName: String?,
    ) {
      val views = RemoteViews(context.packageName, R.layout.widget_ping_assist)
      views.setTextViewText(R.id.widget_contact_name, contactName ?: "Tap to Set Contact")

      val openAppIntent = Intent(context, MainActivity::class.java)
      val openAppPending =
        PendingIntent.getActivity(
          context,
          100,
          openAppIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      views.setOnClickPendingIntent(R.id.widget_open_area, openAppPending)

      val openChatIntent =
        Intent(context, AssistActivity::class.java).apply {
          action = AssistActivity.ACTION_OPEN_CHAT
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
      val openChatPending =
        PendingIntent.getActivity(
          context,
          101,
          openChatIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      views.setOnClickPendingIntent(R.id.widget_btn_open_chat, openChatPending)

      val voiceIntent =
        Intent(context, AssistActivity::class.java).apply {
          action = AssistActivity.ACTION_RECORD_AUDIO
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
      val voicePending =
        PendingIntent.getActivity(
          context,
          102,
          voiceIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      views.setOnClickPendingIntent(R.id.widget_btn_voice_note, voicePending)

      manager.updateAppWidget(widgetId, views)
    }
  }
}
