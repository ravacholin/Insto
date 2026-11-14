package com.example.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferencesRepository
import kotlinx.coroutines.runBlocking

class PingQuickSettingsTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    val prefs = runBlocking { AssistantPreferencesRepository(this@PingQuickSettingsTileService).getCurrentPreferences() }
    val route = ContextRoutingEngine.resolveRoute(this, prefs)

    val tile = qsTile ?: return
    val contact = route.contact
    if (contact != null) {
      tile.state = Tile.STATE_ACTIVE
      tile.label = "Ping: ${contact.displayName}".take(20)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        tile.subtitle =
          route.activeContextLabel
            ?: when (route.activationMode) {
              AssistantActivationMode.DYNAMIC_VOICE_OR_CHAT -> "Smart Chat / Voice"
              AssistantActivationMode.INSTANT_RECORD_AUDIO -> "Record Voice"
              AssistantActivationMode.INSTANT_OPEN_CHAT -> "Open Chat"
            }
      }
    } else {
      tile.state = Tile.STATE_INACTIVE
      tile.label = "PingAssist"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        tile.subtitle = "Set Contact"
      }
    }
    tile.updateTile()
  }

  override fun onClick() {
    super.onClick()
    val launchIntent =
      Intent(this, AssistActivity::class.java).apply {
        action = Intent.ACTION_ASSIST
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pendingIntent =
        PendingIntent.getActivity(
          this,
          201,
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      startActivityAndCollapse(pendingIntent)
    } else {
      @Suppress("DEPRECATION") startActivityAndCollapse(launchIntent)
    }
  }
}
