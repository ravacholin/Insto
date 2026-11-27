package com.example.ui.screens

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.phone.InstinctAutonomy
import com.example.phone.InstinctAutonomySettings
import com.example.phone.InstinctCapabilities
import com.example.phone.InstinctCategory
import com.example.phone.InstinctDeviceCapabilities
import com.example.phone.InstinctMailboxSettings
import com.example.phone.PhoneCommand
import com.example.ui.components.SettingsDescription
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun OmniremoteSettingsSection() {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val controls = remember {
    mapOf(
      "omniremote_power" to "Encendido y apagado",
      "omniremote_volume" to "Volumen",
      "omniremote_mute" to "Silencio",
    )
  }
  var discovery by remember { mutableStateOf<Map<String, InstinctDeviceCapabilities.Discovery>?>(null) }
  var busy by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<String?>(null) }

  if (!InstinctMailboxSettings(context).enabled()) {
    SettingsDescription("Para recibir órdenes de Instinct, activá el Buzón firmado en Propuestas de Instinct.")
  }
  if (InstinctAutonomySettings(context).policy().level(InstinctCategory.APPS) == InstinctAutonomy.NUNCA) {
    SettingsDescription("Instinct tiene bloqueados estos controles. Revisá Propuestas de Instinct → Autonomía → Abrir apps y búsquedas.")
  }

  OutlinedButton(enabled = !busy, onClick = {
    busy = true
    result = null
    scope.launch {
      try {
        discovery = withContext(Dispatchers.IO) {
          controls.keys.associateWith { id ->
            InstinctDeviceCapabilities.discover(context, InstinctCapabilities.specs.getValue(id))
          }
        }
      } finally {
        busy = false
      }
    }
  }) { Text("Comprobar conexión") }

  discovery?.let { statuses ->
    statuses.forEach { (id, status) ->
      val detail = status.blocker?.reason ?: status.targets.joinToString()
      SettingsDescription("${controls.getValue(id)} · $detail")
    }
    statuses["omniremote_volume"]?.targets.orEmpty().forEach { target ->
      OutlinedButton(enabled = !busy, onClick = {
        busy = true
        result = null
        scope.launch {
          try {
            result = withContext(Dispatchers.IO) {
              val params = JSONObject().put("device", target).put("percent", 20)
              val command = InstinctCapabilities.command("omniremote_volume", params) as PhoneCommand.Platform
              InstinctDeviceCapabilities.execute(context, command, UUID.randomUUID().toString(), foreground = true).message
            }
          } finally {
            busy = false
          }
        }
      }) { Text("Probar volumen al 20 % · $target") }
    }
  }
  if (busy) SettingsDescription("Consultando OMNIREMOTE…")
  result?.let { SettingsDescription(it) }
}
