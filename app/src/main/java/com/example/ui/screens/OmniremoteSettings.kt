package com.example.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.phone.InstinctCapabilities
import com.example.phone.InstinctDeviceCapabilities
import com.example.phone.OmniremoteAdapter
import com.example.phone.OmniremoteAliases
import com.example.phone.OmniremoteLocalProvider
import com.example.phone.PhoneCommand
import com.example.ui.components.SettingsDescription
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun OmniremoteSettingsSection() {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val provider = remember { OmniremoteLocalProvider() }
  val aliasStore = remember(context) { OmniremoteAliases(context) }
  var devices by remember { mutableStateOf<List<OmniremoteAdapter.Device>?>(null) }
  var aliases by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
  var connectionError by remember { mutableStateOf<String?>(null) }
  var checking by remember { mutableStateOf(false) }
  var testing by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<String?>(null) }

  suspend fun checkConnection() {
    if (checking) return
    checking = true
    connectionError = null
    result = null
    try {
      val (catalog, savedAliases) = withContext(Dispatchers.IO) {
        provider.devices(context) to aliasStore.read()
      }
      devices = catalog.sortedBy { it.label }
      aliases = savedAliases
    } catch (error: OmniremoteAdapter.DiscoveryFailure) {
      devices = null
      connectionError = error.message
    } finally {
      checking = false
    }
  }

  LaunchedEffect(context) { checkConnection() }
  OutlinedButton(enabled = !checking && !testing, onClick = { scope.launch { checkConnection() } }) {
    Text("Comprobar conexión")
  }

  if (checking) SettingsDescription("Conectando con Omniremote…")
  connectionError?.let { SettingsDescription(it) }
  devices?.let { catalog ->
    if (!checking) SettingsDescription("Insto está conectado con Omniremote.")
    if (catalog.isEmpty()) SettingsDescription("Agregá un dispositivo en Omniremote para controlarlo desde Insto.")
    else SettingsDescription("Podés nombrar el dispositivo por su nombre, habitación o alias.")
    for (device in catalog) key(device.id) {
      Text(device.label, style = MaterialTheme.typography.titleSmall)
      SettingsDescription(device.controls.joinToString { it.label }.ifEmpty { "Sin controles disponibles en Insto." })
      OmniremoteAliasesEditor(device, aliases[device.id].orEmpty(), aliasStore, enabled = !checking) {
        aliases = aliases + (device.id to it)
      }
      val volume = OmniremoteAdapter.Control.VOLUME
      if (volume in device.controls) {
        OutlinedButton(enabled = !testing && !checking, onClick = {
          testing = true
          result = null
          scope.launch {
            try {
              result = withContext(Dispatchers.IO) {
                val params = JSONObject().put("device", device.id).put("percent", 20)
                val command = InstinctCapabilities.command(volume.capability, params) as PhoneCommand.Platform
                InstinctDeviceCapabilities.execute(context, command, UUID.randomUUID().toString(), foreground = true).message
              }
            } finally {
              testing = false
            }
          }
        }) { Text("Probar volumen al 20 %") }
      }
    }
  }
  if (testing) SettingsDescription("Probando el volumen…")
  result?.let { SettingsDescription(it) }
}

@Composable
private fun OmniremoteAliasesEditor(
  device: OmniremoteAdapter.Device,
  saved: List<String>,
  store: OmniremoteAliases,
  enabled: Boolean,
  onSaved: (List<String>) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val current = saved.joinToString()
  var input by remember(current) { mutableStateOf(current) }
  var saving by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  OutlinedTextField(
    value = input,
    onValueChange = { input = it; error = null },
    modifier = Modifier.fillMaxWidth(),
    label = { Text("Alias de ${device.name.ifBlank { device.label }}") },
    supportingText = { Text(error ?: "Separá los alias con comas.") },
    singleLine = true,
    enabled = enabled && !saving,
    isError = error != null,
  )
  OutlinedButton(enabled = enabled && !saving && input != current, onClick = {
    saving = true
    scope.launch {
      try {
        val aliases = withContext(Dispatchers.IO) { store.save(device.id, input) }
        input = aliases.joinToString()
        onSaved(aliases)
      } catch (invalid: IllegalArgumentException) {
        error = invalid.message
      } catch (_: IOException) {
        error = "No pude guardar los alias. Volvé a intentarlo."
      } finally {
        saving = false
      }
    }
  }) { Text("Guardar alias") }
}
