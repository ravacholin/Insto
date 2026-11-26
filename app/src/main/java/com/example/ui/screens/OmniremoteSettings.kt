package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.example.phone.InstinctCatalogPublisher
import com.example.phone.InstinctDeviceCapabilities
import com.example.phone.OMNIREMOTE_PACKAGE
import com.example.phone.OmniremoteAdapter
import com.example.phone.OmniremotePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OmniremoteSettings() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val preferences = remember(context) { OmniremotePreferences(context) }
  var saved by remember { mutableStateOf(OmniremotePreferences.Settings()) }
  var snapshot by remember { mutableStateOf<OmniremoteAdapter.Catalog?>(null) }
  var busy by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf<Long?>(null) }
  suspend fun load(refresh: Boolean) {
    busy = true
    try {
      val (catalog, config) = withContext(Dispatchers.IO) {
        InstinctDeviceCapabilities.omniremote.catalog(context, refresh) to preferences.read()
      }
      snapshot = catalog
      saved = config
      if (refresh) InstinctCatalogPublisher.changed(context)
    } finally {
      busy = false
    }
  }
  LaunchedEffect(context) { load(false) }
  Text(when {
    busy -> "Consultando la API local…"
    snapshot?.error != null -> snapshot?.error.orEmpty()
    snapshot == null -> "Consultando la conexión…"
    else -> "Insto autorizado · API local v1 · ${snapshot?.devices?.size ?: 0} dispositivos"
  }, style = MaterialTheme.typography.bodySmall)
  Row {
    OutlinedButton(enabled = !busy, onClick = { scope.launch { load(true) } }) { Text("Probar conexión") }
    Spacer(Modifier.width(8.dp))
    OutlinedButton(onClick = { openOmniremoteSettings(context) }) { Text("Abrir OmniRemote") }
  }
  if (snapshot?.devices?.isEmpty() == true) {
    Text("El vínculo se autoriza con la firma de Insto en una versión compatible de OmniRemote. Agregá y emparejá tus equipos en OmniRemote.",
      style = MaterialTheme.typography.bodySmall)
  }
  snapshot?.devices.orEmpty().forEach { device ->
    key(device.id) {
      OutlinedButton(onClick = { selected = device.id.takeUnless { it == selected } }) {
        Text(device.label + if (saved.default == device.id) " · predeterminado" else "")
      }
      if (selected == device.id) {
        var aliases by remember(saved) { mutableStateOf(saved.aliases[device.id].orEmpty().joinToString(", ")) }
        var primary by remember(saved) { mutableStateOf(saved.default == device.id) }
        var notice by remember { mutableStateOf<String?>(null) }
        Text("${device.type} · ${device.target} · ${device.commands.sorted().joinToString()}",
          style = MaterialTheme.typography.bodySmall)
        if (device.apps.isNotEmpty()) Text(device.apps.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
        if (device.television) {
          Row {
            Switch(checked = primary, onCheckedChange = { primary = it })
            Spacer(Modifier.width(8.dp))
            Text("Televisor predeterminado")
          }
        }
        OutlinedTextField(value = aliases, onValueChange = { aliases = it }, singleLine = true,
          label = { Text("Aliases separados por coma") })
        OutlinedButton(onClick = {
          scope.launch {
            val result = withContext(Dispatchers.IO) {
              runCatching { preferences.save(device.id, aliases.split(','), primary); preferences.read() }
            }
            result.onSuccess { saved = it; notice = "Guardado" }
              .onFailure { notice = it.message ?: "No se pudieron guardar los aliases." }
          }
        }) { Text("Guardar") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}

private fun openOmniremoteSettings(context: Context) {
  val intent = context.packageManager.getLaunchIntentForPackage(OMNIREMOTE_PACKAGE)
    ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$OMNIREMOTE_PACKAGE"))
  runCatching { context.startActivity(intent) }
}
