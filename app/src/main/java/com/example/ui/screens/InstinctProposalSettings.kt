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
import com.example.phone.InstinctProposalIntake
import com.example.phone.InstinctProposalSettings
import com.example.phone.InstinctReplyMode
import com.example.ui.components.SettingsChoices
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSwitch

/** The exact explanation shown before turning passive reading on. Kept here so the screen and the docs match. */
internal fun proposalReadingExplanation(contactName: String): String =
  "Instinct va a poder pedirle acciones al teléfono por su cuenta. Insto mira las notificaciones de WhatsApp: solo lee " +
    "el último mensaje de la conversación individual con «$contactName» en la app de WhatsApp configurada, y de ese " +
    "mensaje usa únicamente las líneas que empiezan con «#insto»; el resto no se interpreta. Las notificaciones de " +
    "otras apps, de grupos y de otros contactos se descartan sin leer su contenido. Las acciones permitidas (alarmas, " +
    "temporizadores, volumen, linterna, silencio, música, abrir apps, buscar en Spotify o YouTube, eventos) se ejecutan " +
    "sin preguntarte y después te llega un aviso. Nunca llamadas, mensajes ni archivos. El texto no se guarda, no se " +
    "registra y no sale del teléfono."

/** Configuración → Propuestas de Instinct. A separate opt-in, off by default. */
@Composable
fun InstinctProposalSettingsSection(contact: PinnedContact?, flavor: WhatsAppFlavor) {
  val context = LocalContext.current
  val settings = remember { InstinctProposalSettings(context) }
  var revision by remember { mutableIntStateOf(0) }
  var explain by remember { mutableStateOf(false) }
  val enabled = remember(revision) { settings.isEnabled() }
  val asksFirst = remember(revision) { settings.asksFirst() }
  val replyMode = remember(revision) { settings.replyMode() }
  val listenerEnabled = remember(revision) {
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
  }
  val postPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("instinct_proposals_settings")) {
    SettingsDescription("Instinct puede pedirle acciones al teléfono escribiendo líneas «#insto …» en su chat. Insto " +
      "las ejecuta y te avisa. Nunca llamadas, mensajes ni archivos.")
    SettingsSwitch("Recibir órdenes de Instinct", enabled, { value ->
      if (value) explain = true else { settings.setEnabled(false); revision++ }
    }, when {
      !InstinctProposalSettings.supported -> "Requiere Android 9 o posterior para excluir los grupos."
      contact == null -> "Configurá primero el contacto de Instinct."
      else -> "Lee solo los mensajes de «${contact.displayName}»."
    }, enabled = InstinctProposalSettings.supported && (contact != null || enabled))
    if (enabled) {
      if (!listenerEnabled) {
        SettingsDescription("Falta habilitar «Insto · notificaciones» en Android para recibir las órdenes.")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
          modifier = Modifier.testTag("instinct_proposals_listener")) { Text("Habilitar acceso en Ajustes") }
      }
      SettingsSwitch("Pedirme confirmación antes de ejecutar", asksFirst, { settings.setAsksFirst(it); revision++ },
        "Apagado: Insto ejecuta y avisa después. Encendido: cada orden espera tu toque o un «dale».")
      Text("Responderle a Instinct", style = MaterialTheme.typography.bodyLarge)
      SettingsDescription("Insto contesta en el mismo chat, con la acción «Responder» de la notificación de WhatsApp, " +
        "una línea por orden (#insto-ok, #insto-espera, #insto-error, #insto-incierto). No abre WhatsApp. " +
        "Solo usa el texto del resultado que ya te muestra a vos.")
      SettingsChoices(InstinctReplyMode.entries, replyMode, { settings.setReplyMode(it); revision++ }, label = ::replyModeLabel,
        itemModifier = { Modifier.testTag("instinct_reply_${it.name.lowercase()}") })
      ProposalDiagnostics(contact, revision) { revision++ }
      if (flavor == WhatsAppFlavor.AUTO) SettingsDescription("Se usa la app de WhatsApp que Insto elige para enviar.")
    }
  }
  if (explain && contact != null) {
    AlertDialog(
      onDismissRequest = { explain = false },
      title = { Text("¿Recibir órdenes de Instinct?") },
      text = { Text(proposalReadingExplanation(contact.displayName), modifier = Modifier.testTag("instinct_proposals_explanation")) },
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

internal fun replyModeLabel(mode: InstinctReplyMode): String = when (mode) {
  InstinctReplyMode.NEVER -> "Nunca"
  InstinctReplyMode.PROBLEMS -> "Solo errores y esperas"
  InstinctReplyMode.ALWAYS -> "Siempre"
}

/** Why orders are or are not arriving. Shows states and the last result, never a message or a name read. */
@Composable
private fun ProposalDiagnostics(contact: PinnedContact?, revision: Int, onRefresh: () -> Unit) {
  val now = android.os.SystemClock.elapsedRealtime()
  val connected = remember(revision) { com.example.phone.NotificationAccessService.connected != null }
  val accessibility = remember(revision) { com.example.assist.PingAutoSendAccessibilityService.isConnected() }
  val diagnosis = InstinctProposalIntake.lastDiagnosis
  val detail = InstinctProposalIntake.lastDetail
  val at = InstinctProposalIntake.lastDiagnosisAt
  val ago = ((now - at) / 60_000L).let { if (it < 1) "hace menos de un minuto" else "hace $it min" }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("instinct_proposals_diagnostics")) {
    Text("Diagnóstico", style = MaterialTheme.typography.titleMedium)
    SettingsDescription(if (connected) "«Insto · notificaciones»: conectado."
      else "«Insto · notificaciones»: no conectado. Desactivalo y volvé a activarlo en Ajustes de Android.")
    SettingsDescription("Contacto esperado: «${contact?.displayName ?: "sin configurar"}». Tiene que ser exactamente el nombre que muestra WhatsApp.")
    SettingsDescription(if (accessibility) "Accesibilidad de Insto: activa. Las órdenes que abren otra app (alarma, temporizador, Spotify…) se ejecutan solas."
      else "Accesibilidad de Insto: inactiva. Volumen, linterna, silencio y música se ejecutan solos; lo que abre otra app (alarma, temporizador, Spotify…) espera tu toque, porque Android no deja abrir apps en segundo plano.")
    SettingsDescription(if (diagnosis == null) "Último mensaje de WhatsApp: ninguno desde que se inició Insto."
      else "Último mensaje de WhatsApp ($ago): ${diagnosis.label}" + (detail?.let { " $it" } ?: ""))
    val reply = InstinctProposalIntake.lastReply
    if (reply != null) {
      val replyAgo = ((now - InstinctProposalIntake.lastReplyAt) / 60_000L).let { if (it < 1) "hace menos de un minuto" else "hace $it min" }
      SettingsDescription("Última respuesta a Instinct ($replyAgo): ${reply.label}" +
        (InstinctProposalIntake.lastReplyDetail?.let { " $it" } ?: ""))
    }
    TextButton(onClick = onRefresh) { Text("Actualizar diagnóstico") }
  }
}
