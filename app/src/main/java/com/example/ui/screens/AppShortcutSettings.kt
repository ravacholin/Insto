package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.phone.AndroidShortcutEngine
import com.example.phone.AppShortcut
import com.example.phone.PhoneActionExecutor
import com.example.phone.PhoneCommand
import com.example.phone.PhoneCommandParser
import com.example.phone.ShortcutAccess
import com.example.phone.validShortcutAlias
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppShortcutSettings() {
  val context = LocalContext.current
  val engine = remember(context) { AndroidShortcutEngine.of(context) }
  val catalog by engine.state.collectAsStateWithLifecycle()
  var search by remember { mutableStateOf("") }
  var expanded by remember { mutableStateOf<String?>(null) }
  var feedback by remember { mutableStateOf<String?>(null) }
  var refreshing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val groups = remember(catalog, search) {
    val query = PhoneCommandParser.normalize(search)
    catalog.entries.filter { entry ->
      query.isBlank() || listOf(entry.name, entry.app, entry.preferences.alias).any { query in PhoneCommandParser.normalize(it) }
    }.groupBy { it.key.packageName to it.key.profile }.values.sortedBy { PhoneCommandParser.normalize(it.first().app) }
  }
  SettingsDescription(when (catalog.access) {
    ShortcutAccess.CHECKING -> "Comprobando el permiso de Android…"
    ShortcutAccess.GRANTED -> "Android concede a Insto el permiso de acceso a los shortcuts."
    ShortcutAccess.LOCKED -> "Desbloqueá el teléfono para consultar los accesos directos."
    ShortcutAccess.ERROR -> "Android no pudo leer el catálogo. Tocá Actualizar para reintentar."
    ShortcutAccess.DENIED -> if (Build.VERSION.SDK_INT < 25) "Los shortcuts requieren Android 7.1 o posterior."
      else "Android no concede el permiso. Revisá que Insto esté seleccionado como asistente digital."
  })
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(enabled = !refreshing, onClick = {
      feedback = null
      refreshing = true
      scope.launch {
        try { engine.refresh() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { feedback = "No pude actualizar el catálogo." }
        finally { refreshing = false }
      }
    }) { Text(if (refreshing) "Actualizando…" else "Actualizar catálogo") }
    if (catalog.access == ShortcutAccess.DENIED && Build.VERSION.SDK_INT >= 25) {
      TextButton(onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
          .onFailure { feedback = "No pude abrir los ajustes del asistente desde este teléfono." }
      }) { Text("Ajustes del asistente") }
    }
  }
  feedback?.let { SettingsDescription(it) }
  if (catalog.access != ShortcutAccess.GRANTED) return
  if (catalog.partial) SettingsDescription("Android no permitió consultar todos los perfiles. Revisá si hay alguno bloqueado o pausado.")
  OutlinedTextField(search, { search = it }, label = { Text("Buscar accesos directos") },
    modifier = Modifier.fillMaxWidth(), singleLine = true)
  if (groups.isEmpty()) SettingsDescription(if (search.isBlank()) "Android no devolvió accesos directos." else "Sin resultados.")
  else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
    groups.forEach { group ->
      val first = group.first()
      item(key = "app-" + first.key.packageName + "-" + first.key.profile) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
          Text(first.app, style = MaterialTheme.typography.titleSmall)
          SettingsDescription(first.key.packageName + if (first.key.profile > 0) " · Perfil " + first.key.profile else "")
        }
      }
      items(group.sortedWith(compareByDescending<AppShortcut> { it.preferences.favorite }.thenBy { it.name }),
        key = { it.key.token }) { entry ->
        ShortcutSettingsRow(entry, engine, expanded == entry.key.token, {
          expanded = if (expanded == entry.key.token) null else entry.key.token
        })
      }
    }
  }
}

@Composable
private fun ShortcutSettingsRow(entry: AppShortcut, engine: AndroidShortcutEngine, expanded: Boolean, toggle: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var alias by remember(entry.key, entry.preferences.alias) { mutableStateOf(entry.preferences.alias) }
  var feedback by remember(entry.key) { mutableStateOf<String?>(null) }
  var confirmation by remember(entry.key) { mutableStateOf<PhoneCommand.Platform?>(null) }
  var grantConfirmation by remember(entry.key) { mutableStateOf(false) }
  var busy by remember(entry.key) { mutableStateOf(false) }
  val icon by produceState<ImageBitmap?>(null, entry.key, entry.revision, entry.available) {
    value = withContext(Dispatchers.IO) { engine.icon(entry)?.toBitmap(64, 64)?.asImageBitmap() }
  }
  fun runChange(action: suspend () -> Unit) {
    if (busy) return
    busy = true
    scope.launch {
      try { action() }
      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { feedback = "No pude guardar o ejecutar el cambio." }
      finally { busy = false }
    }
  }
  Column {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val image = icon
      if (image != null) Image(image, contentDescription = null, Modifier.size(32.dp))
      else Icon(Icons.Default.Apps, contentDescription = null, Modifier.size(32.dp))
      Column(Modifier.weight(1f)) {
        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
        SettingsDescription(listOfNotNull(
          "Manifiesto".takeIf { entry.manifest }, "Dinámico".takeIf { entry.dynamic }, "Fijado".takeIf { entry.pinned },
          "No disponible".takeUnless { entry.available }, "Deshabilitado".takeUnless { entry.preferences.enabled },
          "Favorito".takeIf { entry.preferences.favorite }, entry.preferences.alias.takeIf(String::isNotBlank),
        ).joinToString(" · "))
      }
      TextButton(onClick = toggle) { Text(if (expanded) "Cerrar" else "Opciones") }
    }
    if (expanded) {
      OutlinedTextField(alias, { if (it.length <= 100) alias = it }, label = { Text("Alias de voz") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
      TextButton(enabled = !busy && alias.trim() != entry.preferences.alias && validShortcutAlias(alias.trim()), onClick = {
        runChange { engine.setPreferences(entry) { it.copy(alias = alias.trim()) } }
      }) { Text("Guardar alias") }
      SettingsSwitch("Favorito", entry.preferences.favorite,
        { checked -> runChange { engine.setPreferences(entry) { it.copy(favorite = checked) } } }, enabled = !busy)
      SettingsSwitch("Habilitado en Insto", entry.preferences.enabled,
        { checked -> runChange { engine.setPreferences(entry) { it.copy(enabled = checked) } } }, enabled = !busy)
      SettingsSwitch("Autorizar a Instinct", entry.grant != null, { checked ->
        if (checked) grantConfirmation = true
        else runChange { engine.setGrant(entry, false) }
      }, "Cada lanzamiento requiere tu confirmación con Insto abierto.", enabled = !busy && (entry.usable || entry.grant != null))
      if (grantConfirmation) {
        SettingsDescription("Instinct podrá solicitar este acceso directo por su identificador. Confirmá si reconocés lo que hace.")
        Row {
          TextButton(enabled = !busy, onClick = {
            grantConfirmation = false
            runChange { engine.setGrant(entry, true) }
          }) { Text("Autorizar") }
          TextButton(onClick = { grantConfirmation = false }) { Text("Cancelar") }
        }
      }
      entry.grant?.let { grant ->
        TextButton(onClick = {
          context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Acceso directo de Instinct", grant))
        }) { Text("Copiar identificador de Instinct") }
      }
      if (confirmation == null) OutlinedButton(enabled = entry.usable && !busy, onClick = {
        runChange {
          val result = withContext(Dispatchers.IO) { PhoneActionExecutor(context).execute(entry.command()) }
          confirmation = result.confirmation as? PhoneCommand.Platform
          feedback = result.message
        }
      }) { Text("Probar") }
      feedback?.let { SettingsDescription(it) }
      confirmation?.let { command ->
        Row {
          TextButton(enabled = !busy, onClick = {
            confirmation = null
            runChange {
              val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
              feedback = withContext(Dispatchers.IO) { PhoneActionExecutor(context).execute(command, confirmed = true,
                canLaunch = { job?.isActive != false }) }.message
            }
          }) { Text("Abrir acceso directo") }
          TextButton(onClick = { confirmation = null; feedback = null }) { Text("Cancelar") }
        }
      }
    }
    HorizontalDivider()
  }
}
