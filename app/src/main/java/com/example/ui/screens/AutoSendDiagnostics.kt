package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.assist.AutoSendHealthReader
import com.example.assist.AutoSendMetricsStore
import com.example.data.WhatsAppFlavor
import com.example.ui.components.SettingsDescription

/**
 * Configuración → WhatsApp y notas de voz → Diagnóstico del auto-envío. Shows the evidence behind the panel's
 * indicator and lets the user delete the local counters. Nothing here sends or arms anything.
 */
@Composable
fun AutoSendDiagnostics(flavor: WhatsAppFlavor) {
  val context = LocalContext.current
  var revision by remember { mutableIntStateOf(0) }
  var confirmClear by remember { mutableStateOf(false) }
  val health = remember(flavor, revision) { runCatching { AutoSendHealthReader.read(context, flavor) }.getOrNull() }
  val metrics = remember(revision) { AutoSendMetricsStore(context).read() }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("autosend_diagnostics")) {
    Text("Diagnóstico del auto-envío", style = MaterialTheme.typography.titleMedium)
    health?.let { Text("${it.state.title}. ${it.reason}", style = MaterialTheme.typography.bodyMedium) }
    SettingsDescription("Un servicio activado no garantiza el envío: WhatsApp puede cambiar su pantalla. «Listo» significa que " +
      "hubo un envío confirmado con la versión instalada. Si algo falla, el panel ofrece abrir el chat para enviar a mano.")
    if (metrics.rows.isEmpty()) SettingsDescription("Todavía no hay intentos registrados.")
    else {
      SettingsDescription("Confirmados: ${metrics.confirmed} · Sin confirmar o fallidos: ${metrics.failed}")
      for (row in metrics.rows.take(6)) {
        Text("${row.flavor.label} ${row.versionCode} · ${row.kind.label} · ${row.outcome.label} · ${row.strategy} · ${row.count}",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    SettingsDescription("Se guardan solo resultados, la forma en que se encontró el botón y la versión de WhatsApp. " +
      "Nunca mensajes, audios, transcripciones, teléfonos ni contactos.")
    TextButton(onClick = { confirmClear = true }, enabled = metrics.rows.isNotEmpty() || metrics.lastAttempt != null,
      modifier = Modifier.testTag("autosend_clear_metrics")) { Text("Borrar métricas", color = MaterialTheme.colorScheme.error) }
  }
  if (confirmClear) AlertDialog(
    onDismissRequest = { confirmClear = false },
    title = { Text("¿Borrar las métricas del auto-envío?") },
    text = { Text("El indicador va a mostrar «necesita revisión» hasta el próximo envío confirmado.") },
    confirmButton = {
      TextButton(onClick = { AutoSendMetricsStore(context).clear(); confirmClear = false; revision++ }) {
        Text("Borrar", color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
  )
}
