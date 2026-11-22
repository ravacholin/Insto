package com.example.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferences
import com.example.data.AssistantPreferencesCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class PingQuickSettingsTileService : TileService() {
  /** Lives from onStartListening to onStopListening: the tile follows preference changes only while visible. */
  private var listening: CoroutineScope? = null

  override fun onStartListening() {
    super.onStartListening()
    val cache = AssistantPreferencesCache.of(this)
    // Paint at once from the cache (or neutral during a cold start), then whenever the value changes.
    render(cache.current)
    listening?.cancel()
    listening = MainScope().also { scope ->
      scope.launch { cache.preferences.filterNotNull().collect { render(it) } }
    }
  }

  override fun onStopListening() {
    listening?.cancel()
    listening = null
    super.onStopListening()
  }

  override fun onDestroy() {
    listening?.cancel()
    listening = null
    super.onDestroy()
  }

  private fun render(prefs: AssistantPreferences?) {
    val tile = qsTile ?: return
    if (prefs == null) {
      // Not loaded yet: neither a contact nor a missing-contact prompt, since neither is known.
      tile.state = Tile.STATE_INACTIVE
      tile.label = "Insto"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = null
      tile.updateTile()
      return
    }
    val route = ContextRoutingEngine.resolveRoute(this, prefs)
    val contact = route.contact
    if (contact != null) {
      tile.state = Tile.STATE_ACTIVE
      tile.label = "Insto: ${contact.displayName}".take(20)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        tile.subtitle =
          route.activeContextLabel
            ?: when (route.activationMode) {
              AssistantActivationMode.TAP_CHAT_HOLD_AUDIO -> "Tocar: chat · Mantener: audio"
              AssistantActivationMode.DYNAMIC_VOICE_OR_CHAT -> "Chat o audio"
              AssistantActivationMode.INSTANT_RECORD_AUDIO -> "Grabar audio"
              AssistantActivationMode.INSTANT_OPEN_CHAT -> "Abrir chat"
            }
      }
    } else {
      tile.state = Tile.STATE_INACTIVE
      tile.label = "Insto"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        tile.subtitle = "Elegir contacto"
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
