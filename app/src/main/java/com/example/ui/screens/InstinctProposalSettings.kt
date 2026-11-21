package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.app.NotificationManagerCompat
import com.example.data.PinnedContact
import com.example.data.WhatsAppFlavor
import com.example.phone.InstinctProposalSettings
import com.example.ui.components.SettingsChoices
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSwitch

/** The exact explanation shown before turning passive reading on. Kept here so the screen and the docs match. */
internal fun proposalReadingExplanation(contactName: String, minutes: Int): String =
  "Insto va a mirar las notificaciones de WhatsApp que llegan al teléfono. Solo lee el último mensaje de la " +
    "conversación individual con «$contactName» en la app de WhatsApp configurada, y solo durante los $minutes minutos " +
    "posteriores a un envío confirmado de Insto a ese contacto. De ese mensaje usa únicamente las líneas que empiezan " +
    "con «#insto»; el resto no se interpreta. Las notificaciones de otras apps, de grupos y de otros contactos se " +
    "descartan sin leer su contenido. El texto no se guarda, no se registra, no se incluye en copias de seguridad y no " +
    "sale del teléfono. Ninguna propuesta se ejecuta sin tu confirmación, y nunca se proponen llamadas ni mensajes."

/** Configuración → Propuestas de Instinct. A separate opt-in, off by default. */
@Composable
fun InstinctProposalSettingsSection(contact: PinnedContact?, flavor: WhatsAppFlavor) {
  val context = LocalContext.current
  val settings = remember { InstinctProposalSettings(context) }
  var revision by remember { mutableIntStateOf(0) }
  var explain by remember { mutableStateOf(false) }
  val enabled = remember(revision) { settings.isEnabled() }
  val minutes = remember(revision) { settings.windowMinutes() }
  val listenerEnabled = remember(revision) {
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
  }
  val postPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("instinct_proposals_settings")) {
    SettingsDescription("Instinct puede responderte con acciones en un formato fijo («#insto …»). Insto te las ofrece para " +
      "confirmar con un toque o un «dale»; nunca las ejecuta solo.")
    SettingsSwitch("Recibir propuestas de Instinct", enabled, { value ->
      if (value) explain = true else { settings.setEnabled(false); revision++ }
    }, when {
      !InstinctProposalSettings.supported -> "Requiere Android 9 o posterior para excluir los grupos."
      contact == null -> "Configurá primero el contacto de Instinct."
      else -> "Lee solo mensajes de «${contact.displayName}» después de enviarle algo desde Insto."
    }, enabled = InstinctProposalSettings.supported && (contact != null || enabled))
    if (enabled) {
      if (!listenerEnabled) {
        SettingsDescription("Falta habilitar «Insto · notificaciones» en Android para recibir las propuestas.")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
          modifier = Modifier.testTag("instinct_proposals_listener")) { Text("Habilitar acceso en Ajustes") }
      }
      Text("Ventana después de cada envío", style = MaterialTheme.typography.titleMedium)
      SettingsChoices(WINDOW_CHOICES, minutes, { settings.setWindowMinutes(it); revision++ }, label = { "$it min" })
      if (flavor == WhatsAppFlavor.AUTO) SettingsDescription("Se usa la app de WhatsApp que Insto elige para enviar.")
    }
  }
  if (explain && contact != null) {
    AlertDialog(
      onDismissRequest = { explain = false },
      title = { Text("¿Recibir propuestas de Instinct?") },
      text = { Text(proposalReadingExplanation(contact.displayName, minutes), modifier = Modifier.testTag("instinct_proposals_explanation")) },
      dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancelar") } },
      confirmButton = {
        TextButton(onClick = {
          explain = false
          settings.setEnabled(true)
          revision++
          if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            postPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
          if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, modifier = Modifier.testTag("instinct_proposals_accept")) { Text("Activar") }
      },
    )
  }
}

private val WINDOW_CHOICES = listOf(5, 10, 15, 30, 60)
