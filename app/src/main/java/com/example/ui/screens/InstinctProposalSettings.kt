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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.data.PinnedContact
import com.example.data.WhatsAppFlavor
import com.example.phone.InstinctProposalIntake
import com.example.phone.InstinctProposalSettings
import com.example.phone.InstinctAckChannel
import com.example.phone.InstinctLog
import com.example.phone.InstinctLogConfig
import com.example.phone.InstinctLogSettings
import com.example.phone.InstinctReplyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
  val channel = remember(revision) { settings.channel() }
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
      Text("Canal de estados", style = MaterialTheme.typography.bodyLarge)
      SettingsDescription("Por dónde van esas respuestas. «Bitácora» las anota en una planilla de Google, a través de tu Apps " +
        "Script, y no escribe nada en el chat. Las órdenes siguen llegando por WhatsApp.")
      SettingsChoices(InstinctAckChannel.entries, channel, { settings.setChannel(it); revision++ }, label = ::channelLabel,
        itemModifier = { Modifier.testTag("instinct_channel_${it.name.lowercase()}") })
      if (channel.usesLog) InstinctLogConfiguration(revision) { revision++ }
      InstinctScheduledList(revision) { revision++ }
      InstinctRoutineSwitches(revision) { revision++ }
      InstinctCatalogActions(replyMode, channel, revision) { revision++ }
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

/** Orders Instinct scheduled for later, with their time and a button to cancel each one. */
@Composable
private fun InstinctScheduledList(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val pending = remember(revision) { com.example.phone.InstinctScheduler.pending(context) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_scheduled")) {
    Text("Órdenes programadas por Instinct", style = MaterialTheme.typography.bodyLarge)
    if (pending.isEmpty()) SettingsDescription("No hay órdenes programadas.")
    val zone = java.util.Calendar.getInstance()
    pending.forEach { order ->
      SettingsDescription("${com.example.phone.InstinctDeferral.clock(order.fireAt, zone)}" +
        (order.condition?.let { " ${it.label}" } ?: "") + ": ${order.inner}")
      TextButton(onClick = { com.example.phone.InstinctScheduler.cancel(context, order.id); onChange() },
        modifier = Modifier.testTag("instinct_scheduled_cancel")) { Text("Cancelar esta orden") }
    }
  }
}

/** «Instinct puede ejecutarla», one switch per configured routine. Off by default; disabled with the reason when not eligible. */
@Composable
private fun InstinctRoutineSwitches(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val grants = remember { com.example.phone.InstinctRoutineGrants(context) }
  val routines = remember(revision) { grants.configured() }
  val access = remember(revision) { grants.access() }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_routines")) {
    Text("Rutinas para Instinct", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription(if (routines.isEmpty()) "No hay rutinas configuradas. Importá una para que Instinct pueda pedirla por su nombre."
      else "Instinct puede pedir «#insto ejecutar la rutina <nombre>» solo para las que enciendas acá. Si la rutina cambia, se apaga sola.")
    routines.forEach { routine ->
      val blocker = com.example.phone.InstinctRoutines.blocker(routine)
      val on = access.resolve(routine.name) != null
      SettingsSwitch("Instinct puede ejecutar «${routine.name}»", on, { value -> grants.set(routine, value); onChange() },
        blocker ?: "${routine.commands.size} pasos. Cuenta como una sola orden.", enabled = blocker == null || on)
    }
  }
}

/** Copy the generated catalog, or send it to Instinct while a «Responder» action is valid. */
@Composable
private fun InstinctCatalogActions(replyMode: InstinctReplyMode, channel: InstinctAckChannel, revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  var notice by remember { mutableStateOf<String?>(null) }
  val canSend = remember(revision, replyMode, channel) {
    replyMode != InstinctReplyMode.NEVER && channel.usesChat && com.example.phone.InstinctProposalCenter.acks.latestHandle() != null
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_catalog")) {
    Text("Instrucciones para Instinct", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("Se generan con la gramática de Insto (${com.example.phone.InstinctProtocolCatalog.TITLE}) y las rutinas que habilitaste. Pegalas en el prompt de Instinct." +
      if (channel.usesLog) " La copia incluye la URL y el token de la bitácora, porque Instinct los necesita para leerla; la que se envía por WhatsApp no los lleva." else "")
    OutlinedButton(onClick = {
      val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
      clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Instrucciones para Instinct",
        com.example.phone.InstinctProposalCenter.catalog(context)))
      notice = "Instrucciones copiadas."
    }, modifier = Modifier.testTag("instinct_catalog_copy")) { Text("Copiar instrucciones para Instinct") }
    if (canSend) OutlinedButton(onClick = {
      notice = if (com.example.phone.InstinctProposalCenter.sendCatalog(context)) "Instrucciones enviadas a Instinct."
        else "No se pudieron enviar: WhatsApp no aceptó la respuesta o venció."
      onChange()
    }, modifier = Modifier.testTag("instinct_catalog_send")) { Text("Enviarlas a Instinct") }
    notice?.let { SettingsDescription(it) }
  }
}

internal fun channelLabel(channel: InstinctAckChannel): String = when (channel) {
  InstinctAckChannel.WHATSAPP -> "WhatsApp"
  InstinctAckChannel.BITACORA -> "Bitácora"
  InstinctAckChannel.AMBOS -> "Ambos"
}

/**
 * The Apps Script address and token, and «Probar bitácora». The token field starts empty and the saved token is
 * only ever shown as its last four characters.
 */
@Composable
private fun InstinctLogConfiguration(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val log = remember { InstinctLogSettings(context) }
  val scope = rememberCoroutineScope()
  var url by remember(revision) { mutableStateOf(log.url().orEmpty()) }
  var token by remember(revision) { mutableStateOf("") }
  var notice by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  val masked = remember(revision) { log.maskedToken() }
  val urlError = url.isNotBlank() && !InstinctLogConfig.validUrl(url)
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_log")) {
    Text("Bitácora", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("URL de la implementación de tu Apps Script (empieza con ${InstinctLogConfig.URL_PREFIX} y termina en " +
      "${InstinctLogConfig.URL_SUFFIX}) y el token que definiste en el script. Se guardan solo en este teléfono, fuera de las copias de seguridad.")
    OutlinedTextField(url, { url = it; notice = null }, label = { Text("URL del Apps Script") }, singleLine = true, isError = urlError,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("instinct_log_url"))
    if (urlError) SettingsDescription("La URL tiene que empezar con ${InstinctLogConfig.URL_PREFIX} y terminar en ${InstinctLogConfig.URL_SUFFIX}.")
    OutlinedTextField(token, { token = it; notice = null }, label = { Text("Token (guardado: $masked)") }, singleLine = true,
      visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth().testTag("instinct_log_token"))
    OutlinedButton(onClick = {
      notice = if (log.save(url, token)) "Bitácora guardada." else
        "No se guardó: revisá la URL y el token (de 8 a 200 caracteres, sin espacios)."
      token = ""
      onChange()
    }, enabled = !busy, modifier = Modifier.testTag("instinct_log_save")) { Text("Guardar bitácora") }
    OutlinedButton(onClick = {
      busy = true; notice = "Probando…"
      scope.launch {
        notice = withContext(Dispatchers.IO) { InstinctLog.test(context) }
        busy = false
        onChange()
      }
    }, enabled = !busy && log.configured(), modifier = Modifier.testTag("instinct_log_test")) { Text("Probar bitácora") }
    notice?.let { SettingsDescription(it) }
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
    val context = LocalContext.current
    val logLine = remember(revision) {
      if (InstinctProposalSettings(context).channel().usesLog || InstinctLog.outbox(context).size() > 0) InstinctLog.diagnosis(context) else null
    }
    logLine?.let { SettingsDescription(it, modifier = Modifier.testTag("instinct_log_diagnosis")) }
    TextButton(onClick = onRefresh) { Text("Actualizar diagnóstico") }
  }
}
