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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.phone.InstinctAutonomy
import com.example.phone.InstinctAutonomySettings
import com.example.phone.InstinctCategory
import com.example.phone.QuietHours
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
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
    "según la autonomía que elijas. Instinct también puede hacerte preguntas con opciones si configuraste la bitácora. " +
    "La pregunta y tu respuesta quedan en el diario de Insto y se comparten con Instinct por la bitácora. " +
    "Instinct no puede pedir llamadas, mensajes ni archivos."

/** Configuración → Propuestas de Instinct. A separate opt-in, off by default. */
@Composable
fun InstinctProposalSettingsSection(contact: PinnedContact?, flavor: WhatsAppFlavor) {
  val context = LocalContext.current
  val settings = remember { InstinctProposalSettings(context) }
  var revision by remember { mutableIntStateOf(0) }
  var explain by remember { mutableStateOf(false) }
  val enabled = remember(revision) { settings.isEnabled() }
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
    InstinctReactivationCard(revision) { revision++ }
    if (enabled) {
      if (!listenerEnabled) {
        SettingsDescription("Falta habilitar «Insto · notificaciones» en Android para recibir las órdenes.")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
          modifier = Modifier.testTag("instinct_proposals_listener")) { Text("Habilitar acceso en Ajustes") }
      }
      InstinctReadinessSection(revision) { revision++ }
      InstinctAutonomySection(revision) { revision++ }
      InstinctExecutionsSection(revision) { revision++ }
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
      InstinctStateSection(revision) { revision++ }
      InstinctDeviceCapabilitiesSection(revision) { revision++ }
      InstinctCatalogSection(revision) { revision++ }
      InstinctMailboxSection(replyMode, revision) { revision++ }
      InstinctScheduledList(revision) { revision++ }
      InstinctContextRulesList(revision) { revision++ }
      InstinctPlansList(revision) { revision++ }
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

/**
 * «Preparar Insto para funcionar bloqueado» (1.20.0): what Android requires granting by hand, once. Only what is missing
 * is listed, the ones an order already needed first; each button opens its own page in Settings, only when tapped.
 */
@Composable
private fun InstinctReadinessSection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val missing = remember(revision) { com.example.phone.InstinctReadiness.forgetGranted(context); com.example.phone.InstinctReadiness.missing(context) }
  val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChange() }
  val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChange() }
  Text("Funcionar con el teléfono bloqueado", style = MaterialTheme.typography.bodyLarge)
  if (missing.isEmpty()) {
    SettingsDescription("Listo: Insto tiene todo lo que Android pide para cambiar volumen, linterna, silencio, No molestar y " +
      "música con el teléfono bloqueado o la pantalla apagada.")
    return
  }
  SettingsDescription("Con el teléfono bloqueado Insto hace solo lo que no abre una pantalla. Para eso Android pide " +
    "concederle a mano lo siguiente, una sola vez. Insto nunca lo pide en segundo plano.")
  missing.forEach { item ->
    val c = item.capability
    SettingsDescription("• ${c.label}: ${c.why}" + if (item.neededAt != null) " Una orden de Instinct lo necesitó." else "")
    OutlinedButton(onClick = {
      when {
        c == com.example.phone.InstinctReadiness.Capability.CAMERA -> camera.launch(Manifest.permission.CAMERA)
        c == com.example.phone.InstinctReadiness.Capability.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33 ->
          notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else -> com.example.phone.InstinctReadiness.settingsIntent(context, c)?.let { runCatching { context.startActivity(it) } }
      }
      onChange()
    }, modifier = Modifier.testTag("instinct_ready_${c.name.lowercase()}")) { Text("Conceder: ${c.label}") }
  }
  TextButton(onClick = onChange) { Text("Ya lo concedí, revisar de nuevo") }
}

/**
 * «Reactivar Instinct en este teléfono» (1.20.0): after a restore, what the copy had on stays off until the person
 * authorizes it here, once. Nothing turns on remote control by itself on another phone.
 */
@Composable
private fun InstinctReactivationCard(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val request = remember(revision) { com.example.phone.InstinctReactivation.pending(context) }
  var steps by remember { mutableStateOf<List<com.example.phone.InstinctReactivation.Step>?>(null) }
  var busy by remember { mutableStateOf(false) }
  steps?.let { done ->
    Text("Reactivación", style = MaterialTheme.typography.bodyLarge)
    done.forEach { SettingsDescription((if (it.ok) "✓ " else "✗ ") + "${it.name}: ${it.detail}") }
  }
  if (request == null) return
  Text("Reactivar Instinct en este teléfono", style = MaterialTheme.typography.bodyLarge)
  val parts = listOfNotNull("recibir órdenes".takeIf { request.proposals }, "buzón".takeIf { request.mailbox },
    "timbre".takeIf { request.doorbell }, "${request.recurrences.size} órdenes recurrentes".takeIf { request.recurrences.isNotEmpty() },
    "${request.contextRules.size} reglas contextuales".takeIf { request.contextRules.isNotEmpty() })
  SettingsDescription("La copia restaurada tenía encendido: ${parts.joinToString(", ")}. En este teléfono quedó apagado hasta que " +
    "lo autorices. Al reactivar, Insto revisa los permisos, enciende lo que estaba encendido, registra el timbre de nuevo " +
    "(claves y dirección propias de este teléfono, que se le avisan al Apps Script), prueba la bitácora y el buzón, vuelve " +
    "a programar las recurrentes, restaura las reglas y publica el catálogo. Las reglas activas pueden dispararse cuando se cumplan sus condiciones.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(enabled = !busy, onClick = {
      busy = true
      scope.launch {
        steps = withContext(Dispatchers.IO) { runCatching { com.example.phone.InstinctReactivation.run(context) }.getOrDefault(emptyList()) }
        busy = false; onChange()
      }
    }, modifier = Modifier.testTag("instinct_reactivate")) { Text(if (busy) "Reactivando…" else "Reactivar") }
    TextButton(enabled = !busy, onClick = { com.example.phone.InstinctReactivation.dismiss(context); onChange() }) { Text("Dejar apagado") }
  }
}

/** «Ejecuciones recientes» (1.20.0): the journal's last rows, as «ver ejecuciones» answers them. */
@Composable
private fun InstinctExecutionsSection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  var lines by remember(revision) { mutableStateOf<String?>(null) }
  LaunchedEffect(revision) {
    lines = withContext(Dispatchers.IO) { runCatching { com.example.phone.InstinctExecutionQueries.list(context) }.getOrNull() }
  }
  Text("Ejecuciones recientes", style = MaterialTheme.typography.bodyLarge)
  SettingsDescription((lines ?: "Leyendo el diario…").split(" | ").joinToString("\n"))
  TextButton(onClick = onChange) { Text("Actualizar") }
}

internal fun autonomyLabel(level: InstinctAutonomy): String = when (level) {
  InstinctAutonomy.SOLO -> "Solo"
  InstinctAutonomy.CONFIRMAR -> "Confirmar"
  InstinctAutonomy.NUNCA -> "Nunca"
}

/**
 * «Autonomía»: one row per category with three levels, the quiet hours and their exception. Replaces «Pedirme
 * confirmación antes de ejecutar» (1.15.0). A category the ledger lowered says when.
 */
@Composable
private fun InstinctAutonomySection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val autonomy = remember { InstinctAutonomySettings(context) }
  val policy = remember(revision) { autonomy.policy() }
  var start by remember(revision) { mutableStateOf(QuietHours.clock(policy.quiet?.start ?: 0)) }
  var end by remember(revision) { mutableStateOf(QuietHours.clock(policy.quiet?.end ?: 7 * 60)) }
  var notice by remember { mutableStateOf<String?>(null) }
  val day = remember { java.text.SimpleDateFormat("dd/MM", java.util.Locale.US) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_autonomy")) {
    Text("Autonomía", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("Qué puede hacer Instinct solo, por tipo de acción. «Solo»: lo hace y te avisa (con Deshacer cuando se puede). " +
      "«Confirmar»: espera tu toque o un «dale». «Nunca»: lo rechaza. Las acciones sin interfaz pueden ejecutarse con la pantalla apagada. " +
      "Abrir una pantalla requiere desbloquear el teléfono.")
    InstinctCategory.entries.forEach { category ->
      Text(category.label.replaceFirstChar { it.uppercaseChar() }, style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InstinctAutonomy.entries.forEach { level ->
          FilterChip(selected = policy.level(category) == level, onClick = { autonomy.set(category, level); onChange() },
            label = { Text(autonomyLabel(level)) },
            modifier = Modifier.testTag("instinct_autonomy_${category.name.lowercase()}_${level.name.lowercase()}"))
        }
      }
      policy.adjusted[category]?.let { SettingsDescription("Ajustada automáticamente el ${day.format(java.util.Date(it))}.") }
    }
    if (InstinctCategory.AVISOS.let { policy.level(it) } != InstinctAutonomy.NUNCA)
      SettingsDescription("«Avisos para vos» es «#insto avisarme …»: una notificación de Insto, solo desde el buzón firmado.")
    SettingsSwitch("Mostrar preguntas en la pantalla bloqueada",
      remember(revision) { autonomy.showQuestionsOnLockScreen() },
      { autonomy.setShowQuestionsOnLockScreen(it); onChange() },
      "Muestra el texto y las opciones si Android permite mostrar notificaciones en la pantalla bloqueada.")
    SettingsSwitch("Horas de silencio para Instinct", policy.quiet != null, { on ->
      if (!on) autonomy.setQuiet(null)
      else autonomy.setQuiet(QuietHours(QuietHours.parse(start) ?: 0, QuietHours.parse(end) ?: 7 * 60))
      onChange()
    }, "En ese rango, lo que haría solo espera tu confirmación. Se evalúa cuando se ejecuta: una orden programada, cuando se dispara.")
    if (policy.quiet != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(start, { start = it; notice = null }, label = { Text("Desde") }, singleLine = true,
          isError = QuietHours.parse(start) == null, modifier = Modifier.weight(1f).testTag("instinct_quiet_start"))
        OutlinedTextField(end, { end = it; notice = null }, label = { Text("Hasta") }, singleLine = true,
          isError = QuietHours.parse(end) == null, modifier = Modifier.weight(1f).testTag("instinct_quiet_end"))
      }
      OutlinedButton(onClick = {
        val from = QuietHours.parse(start); val to = QuietHours.parse(end)
        notice = if (from == null || to == null || from == to) "Escribí dos horas distintas en formato 24 horas (00:00 a 23:59)."
          else { autonomy.setQuiet(QuietHours(from, to)); onChange(); "Horas de silencio guardadas: ${QuietHours(from, to).label}." }
      }, modifier = Modifier.testTag("instinct_quiet_save")) { Text("Guardar horas de silencio") }
      SettingsSwitch("Permitir que silencie el teléfono durante las horas de silencio", policy.silenceInQuiet,
        { autonomy.setSilenceInQuiet(it); onChange() }, "Silenciar, poner en vibración o activar No molestar se sigue haciendo solo.")
    }
    notice?.let { SettingsDescription(it) }
  }
}

/** Orders Instinct scheduled for later, with their trigger or recurrence and a button to cancel each one. */
@Composable
private fun InstinctScheduledList(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val pending = remember(revision) { com.example.phone.InstinctScheduler.pending(context) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_scheduled")) {
    Text("Órdenes programadas por Instinct", style = MaterialTheme.typography.bodyLarge)
    if (pending.isEmpty()) SettingsDescription("No hay órdenes programadas.")
    // One row per order, a recurrence once (its next occurrence): the trigger or recurrence in words, and its id.
    pending.distinctBy { it.series }.forEach { order ->
      SettingsDescription(com.example.phone.InstinctScheduler.line(order))
      TextButton(onClick = { com.example.phone.InstinctScheduler.cancelShort(context, order.shortId); onChange() },
        modifier = Modifier.testTag("instinct_scheduled_cancel")) { Text(if (order.recurring) "Cancelar la recurrente" else "Cancelar esta orden") }
    }
  }
}

@Composable
private fun InstinctContextRulesList(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val scheduler = com.example.phone.InstinctContextScheduler
  val scope = rememberCoroutineScope()
  var rules by remember { mutableStateOf(emptyList<com.example.phone.ContextRule>()) }
  var feedback by remember { mutableStateOf<String?>(null) }
  val now = com.example.phone.InstinctExecutionJournal.clock()
  LaunchedEffect(revision) { rules = withContext(Dispatchers.IO) { scheduler.rules(context) } }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_context_rules")) {
    Text("Reglas contextuales", style = MaterialTheme.typography.bodyLarge)
    if (rules.isEmpty()) SettingsDescription("No hay reglas contextuales. Instinct puede crearlas desde el buzón firmado.")
    else {
      TextButton(onClick = onChange) { Text("Actualizar reglas") }
      rules.forEach { rule ->
        SettingsDescription(scheduler.status(context, rule, now))
        SettingsSwitch("Activar regla ${rule.shortId}", rule.enabled && rule.expiresAt > now, { active ->
          scope.launch {
            feedback = withContext(Dispatchers.IO) { scheduler.change(context, rule.shortId, active).detail }
            onChange()
          }
        }, enabled = rule.expiresAt > now)
        TextButton(onClick = {
          scope.launch {
            feedback = withContext(Dispatchers.IO) { scheduler.describe(context, rule.shortId) }
          }
        }) { Text("Diagnóstico de ${rule.shortId}") }
      }
    }
    feedback?.let { SettingsDescription(it) }
  }
}

@Composable
private fun InstinctDeviceCapabilitiesSection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val grants = remember(context) { com.example.phone.InstinctCalendarAccess(context) }
  var calendars by remember { mutableStateOf(emptyList<com.example.phone.InstinctCalendarActions.Access>()) }
  var clock by remember { mutableStateOf(emptyList<com.example.phone.InstinctClockEntry>()) }
  var calendarUndo by remember { mutableStateOf(emptyList<com.example.phone.ExecutionRecord>()) }
  var feedback by remember { mutableStateOf<String?>(null) }
  val read = Manifest.permission.READ_CALENDAR
  val write = Manifest.permission.WRITE_CALENDAR
  val missing = listOf(read, write).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
  val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChange() }
  LaunchedEffect(revision, missing) {
    withContext(Dispatchers.IO) {
      val available = if (read in missing) emptyList() else runCatching {
        com.example.phone.InstinctCalendarActions(context.contentResolver).calendars()
      }.getOrDefault(emptyList())
      val pending = runCatching { com.example.phone.InstinctLocalClock.pending(context) }.getOrDefault(emptyList())
      val undo = runCatching {
        com.example.phone.InstinctExecutions.journal(context).recent().filter {
          it.state == com.example.phone.ExecutionState.VERIFIED && it.executionData?.contains("\"undo\"") == true
        }
      }.getOrDefault(emptyList())
      Triple(available, pending, undo)
    }.let { (available, pending, undo) -> calendars = available; clock = pending; calendarUndo = undo }
  }
  Text("Calendarios para planes de Instinct", style = MaterialTheme.typography.bodyLarge)
  SettingsDescription("Elegí los calendarios a los que Instinct puede acceder desde el buzón firmado. Las escrituras dependen también de la autonomía y los permisos de Android.")
  if (missing.isNotEmpty()) TextButton(onClick = { request.launch(missing.toTypedArray()) }) {
    Text(if (read in missing) "Permitir acceso al calendario" else "Permitir cambios en el calendario")
  }
  val enabled = remember(revision) { grants.enabled() }
  calendars.forEach { calendar ->
    SettingsSwitch(calendar.label, calendar.id in enabled, { grants.set(calendar.id, it); onChange() },
      if (calendar.writable) "Admite cambios." else "Solo lectura.")
  }
  calendarUndo.forEach { row ->
    TextButton(onClick = { scope.launch {
      feedback = withContext(Dispatchers.IO) { com.example.phone.InstinctCalendarActions.undo(context, row.executionId) }
      onChange()
    } }) { Text("Deshacer cambio de calendario ${row.shortId}") }
  }
  if (clock.isNotEmpty()) Text("Alarmas y temporizadores de Insto", style = MaterialTheme.typography.bodyLarge)
  clock.forEach { timer ->
    SettingsDescription(timer.label + " · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timer.at)))
    TextButton(onClick = { scope.launch {
      feedback = withContext(Dispatchers.IO) { com.example.phone.InstinctLocalClock.cancel(context, timer.id).message }
      onChange()
    } }) { Text("Cancelar aviso") }
  }
  feedback?.let { SettingsDescription(it) }
}

/** Plans of several steps (1.23.0): their state, «Cancelar» while active and «Reanudar» when suspended. */
@Composable
private fun InstinctPlansList(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val engine = com.example.phone.InstinctPlanEngine
  val scope = rememberCoroutineScope()
  var plans by remember { mutableStateOf(emptyList<com.example.phone.PlanRecord>()) }
  var feedback by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(revision) { plans = withContext(Dispatchers.IO) { engine.plans(context) } }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_plans")) {
    Text("Planes de Instinct", style = MaterialTheme.typography.bodyLarge)
    if (plans.isEmpty()) SettingsDescription("No hay planes. Instinct los pide con «#insto plan: …».")
    else {
      TextButton(onClick = onChange) { Text("Actualizar planes") }
      plans.forEach { plan ->
        SettingsDescription(com.example.phone.PlanText.line(plan, null) + " · " + plan.line.removePrefix("plan").trimStart(':', ' ').take(120))
        if (!plan.state.terminal) TextButton(onClick = {
          scope.launch { feedback = withContext(Dispatchers.IO) { engine.cancel(context, plan.shortId) }; onChange() }
        }, modifier = Modifier.testTag("instinct_plan_cancel")) { Text("Cancelar ${plan.shortId}") }
        if (plan.state == com.example.phone.PlanState.SUSPENDED) TextButton(onClick = {
          scope.launch { feedback = withContext(Dispatchers.IO) { engine.resume(context, plan.shortId) }; onChange() }
        }, modifier = Modifier.testTag("instinct_plan_resume")) { Text("Reanudar ${plan.shortId}") }
      }
    }
    feedback?.let { SettingsDescription(it) }
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
      (if (channel.usesLog) " La copia incluye la URL y el token de la bitácora, porque Instinct los necesita para leerla; la que se envía por WhatsApp no los lleva." else "") +
      " Nunca incluyen la clave del buzón: esa se copia aparte, con «Copiar clave».")
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

/**
 * «Estado para Instinct» (1.16.0): always on while the log is configured. Only an informative line, «Publicar estado
 * ahora» and, once, the calendar permission with what it is for.
 */
@Composable
private fun InstinctStateSection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var notice by remember { mutableStateOf<String?>(null) }
  val line = remember(revision) { com.example.phone.InstinctStatePublisher.diagnosis(context) }
  val configured = remember(revision) { InstinctLogSettings(context).configured() }
  val calendar = remember(revision) { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED }
  val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    com.example.phone.NotificationAccessService.connected?.calendarPermissionChanged()
    com.example.phone.InstinctContextScheduler.signal(context)
    onChange()
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_state")) {
    Text("Estado para Instinct", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("Con la bitácora configurada, Insto publica ahí un resumen del teléfono para que Instinct decida con contexto: " +
      "modo de sonido y No molestar, hora de la próxima alarma, evento en curso y próximo, si hay auriculares o Bluetooth, si está " +
      "bloqueado, el modo temporal y las órdenes programadas. Nunca volumen, batería, red, ubicación, contactos ni notificaciones.")
    SettingsDescription(line, modifier = Modifier.testTag("instinct_state_line"))
    if (!calendar) {
      SettingsDescription("Para el evento en curso y el próximo, Insto necesita leer el calendario (solo título y horario). Sin permiso, el campo sale «sin permiso».")
      OutlinedButton(onClick = { askCalendar.launch(Manifest.permission.READ_CALENDAR) },
        modifier = Modifier.testTag("instinct_state_calendar")) { Text("Permitir leer el calendario") }
    }
    OutlinedButton(onClick = {
      notice = "Publicando…"
      scope.launch {
        val outcome = withContext(Dispatchers.IO) {
          com.example.phone.InstinctStatePublisher.publish(context, com.example.phone.InstinctState.Reason.MANUAL)
        }
        notice = when (outcome) {
          com.example.phone.InstinctStatePublisher.Outcome.PUBLISHED -> "Estado agregado a la bandeja de la bitácora."
          com.example.phone.InstinctStatePublisher.Outcome.NOT_CONFIGURED -> "Falta configurar la bitácora."
          com.example.phone.InstinctStatePublisher.Outcome.DISABLED -> "«Recibir órdenes de Instinct» está apagado."
          com.example.phone.InstinctStatePublisher.Outcome.UNCHANGED -> "No se publicó."
        }
        onChange()
      }
    }, enabled = configured, modifier = Modifier.testTag("instinct_state_publish")) { Text("Publicar estado ahora") }
    notice?.let { SettingsDescription(it) }
  }
}

/**
 * «Catálogo para Instinct» (1.18.0): the rules in force, published in the sheet when they change. One line and
 * «Publicar catálogo ahora»; never the address, the token or the key.
 */
@Composable
private fun InstinctCatalogSection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val publisher = com.example.phone.InstinctCatalogPublisher
  var line by remember { mutableStateOf<String?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  // The line computes the catalog (it parses the examples): off the main thread.
  LaunchedEffect(revision) { line = withContext(Dispatchers.IO) { publisher.line(context) } }
  val shown = line ?: return
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_catalog")) {
    Text("Catálogo para Instinct", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("Insto publica en la planilla las reglas vigentes (qué puede pedir Instinct, la autonomía, los límites) cada " +
      "vez que cambian, para que Instinct no dependa de una copia pegada.")
    SettingsDescription(shown, modifier = Modifier.testTag("instinct_catalog_line"))
    OutlinedButton(onClick = {
      busy = true; notice = "Publicando…"
      scope.launch {
        notice = when (val outcome = withContext(Dispatchers.IO) { publisher.publish(context, force = true) }) {
          is com.example.phone.InstinctCatalogPublisher.Outcome.Published -> "Catálogo publicado."
          com.example.phone.InstinctCatalogPublisher.Outcome.Unchanged -> "Catálogo publicado."
          com.example.phone.InstinctCatalogPublisher.Outcome.Inactive -> "Falta configurar la bitácora o encender «Recibir órdenes de Instinct»."
          is com.example.phone.InstinctCatalogPublisher.Outcome.Failed -> "No se pudo publicar (motivo: ${outcome.reason})."
        }
        busy = false; onChange()
      }
    }, enabled = !busy, modifier = Modifier.testTag("instinct_catalog_publish")) { Text("Publicar catálogo ahora") }
    notice?.let { SettingsDescription(it) }
  }
}

internal fun acceptLabel(value: com.example.phone.InstinctMailboxAccept): String = when (value) {
  com.example.phone.InstinctMailboxAccept.WHATSAPP_Y_BUZON -> "WhatsApp y buzón"
  com.example.phone.InstinctMailboxAccept.SOLO_BUZON -> "Solo buzón"
}

/**
 * «Buzón» (1.16.0): signed orders through the same sheet. The key is never shown whole; its field starts empty and
 * «Copiar clave» is the only way it leaves Insto (to the clipboard, apart from the instructions).
 */
@Composable
private fun InstinctMailboxSection(replyMode: InstinctReplyMode, revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val box = remember { com.example.phone.InstinctMailboxSettings(context) }
  val scope = rememberCoroutineScope()
  val enabled = remember(revision) { box.enabled() }
  val hasKey = remember(revision) { box.hasKey() }
  val masked = remember(revision) { box.maskedKey() }
  val logReady = remember(revision) { InstinctLogSettings(context).configured() }
  val accept = remember(revision) { box.accept() }
  val cap = remember(revision) { box.whatsappCap() }
  val interval = remember(revision) { box.intervalMinutes() }
  val diagnosis = remember(revision) { com.example.phone.InstinctMailbox.diagnosis(context) }
  var key by remember(revision) { mutableStateOf("") }
  var notice by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  fun copy(label: String, text: String) {
    context.getSystemService(android.content.ClipboardManager::class.java)
      ?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_mailbox")) {
    Text("Buzón", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("Instinct también puede dejar órdenes firmadas en la planilla de la bitácora, sin escribir en el chat. Insto " +
      "las revisa cada pocos minutos y solo ejecuta las que tienen la firma de la clave que comparten. La planilla y el Apps Script " +
      "nunca conocen la clave.")
    SettingsSwitch("Recibir órdenes por el buzón", enabled, { box.setEnabled(it); onChange() },
      when {
        !logReady -> "Configurá primero la bitácora (URL y token)."
        !hasKey -> "Generá o pegá la clave antes de encenderlo."
        else -> "Clave guardada: $masked."
      }, enabled = enabled || (logReady && hasKey))
    OutlinedTextField(key, { key = it; notice = null }, label = { Text("Clave (guardada: $masked)") }, singleLine = true,
      visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth().testTag("instinct_mailbox_key"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      OutlinedButton(onClick = {
        notice = if (box.saveKey(key)) "Clave guardada." else "No se guardó: la clave tiene que tener de 32 a 200 caracteres, sin espacios."
        key = ""; onChange()
      }, enabled = key.isNotBlank(), modifier = Modifier.testTag("instinct_mailbox_key_save")) { Text("Guardar clave") }
      OutlinedButton(onClick = {
        copy("Clave del buzón de Insto", box.generateKey())
        notice = "Se generó una clave nueva y se copió. Pasásela a Instinct por un canal seguro; la anterior deja de valer."
        onChange()
      }, modifier = Modifier.testTag("instinct_mailbox_key_generate")) { Text("Generar clave") }
    }
    OutlinedButton(onClick = {
      box.key()?.let { copy("Clave del buzón de Insto", it); notice = "Clave copiada. No la pegues en WhatsApp." }
    }, enabled = hasKey, modifier = Modifier.testTag("instinct_mailbox_key_copy")) { Text("Copiar clave") }
    Text("Aceptar órdenes de", style = MaterialTheme.typography.bodyMedium)
    SettingsChoices(com.example.phone.InstinctMailboxAccept.entries, accept, { box.setAccept(it); onChange() }, label = ::acceptLabel,
      description = { if (it == com.example.phone.InstinctMailboxAccept.SOLO_BUZON) "Las líneas #insto de WhatsApp se ignoran: nadie puede usar el nombre del contacto para darte órdenes." else null },
      itemModifier = { Modifier.testTag("instinct_mailbox_accept_${it.name.lowercase()}") })
    Text("Las órdenes por WhatsApp (sin firma) como máximo en", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(InstinctAutonomy.SOLO, InstinctAutonomy.CONFIRMAR).forEach { level ->
        FilterChip(selected = cap == level, onClick = { box.setWhatsappCap(level); onChange() }, label = { Text(autonomyLabel(level)) },
          modifier = Modifier.testTag("instinct_mailbox_cap_${level.name.lowercase()}"))
      }
    }
    Text("Revisar el buzón cada", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      com.example.phone.InstinctMailboxSettings.INTERVALS.forEach { minutes ->
        FilterChip(selected = interval == minutes, onClick = { box.setIntervalMinutes(minutes); onChange() }, label = { Text("$minutes min") },
          modifier = Modifier.testTag("instinct_mailbox_interval_$minutes"))
      }
    }
    SettingsDescription("Con el teléfono en reposo, Android puede demorar la revisión (Doze). Además se revisa enseguida al abrir Insto o el " +
      "panel flotante y cuando llega un mensaje de Instinct por WhatsApp.")
    if (enabled && replyMode == InstinctReplyMode.NEVER)
      SettingsDescription("«Responderle a Instinct» está en Nunca: sin acuses, Instinct no se entera de qué pasó con sus órdenes del buzón. Te conviene subirlo.")
    OutlinedButton(onClick = {
      busy = true; notice = "Probando…"
      scope.launch {
        notice = withContext(Dispatchers.IO) { com.example.phone.InstinctMailbox.test(context) }
        busy = false; onChange()
      }
    }, enabled = !busy && logReady && hasKey, modifier = Modifier.testTag("instinct_mailbox_test")) { Text("Probar buzón") }
    SettingsDescription(diagnosis, modifier = Modifier.testTag("instinct_mailbox_line"))
    notice?.let { SettingsDescription(it) }
    InstinctDoorbellSection(revision, onChange)
  }
}

/**
 * «Timbre instantáneo (ntfy)» (1.17.0): a UnifiedPush push that only says «hay algo en el buzón». Off by default; it
 * needs the mailbox on and a distributor. The endpoint is only ever shown masked. 1.19.0: the encryption line, the
 * detail of the transport and «Probar de punta a punta».
 */
@Composable
private fun InstinctDoorbellSection(revision: Int, onChange: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val doorbell = com.example.phone.InstinctDoorbell
  val settings = remember { doorbell.settings(context) }
  val enabled = remember(revision) { settings.enabled() }
  val installed = remember(revision) { doorbell.distributors(context) }
  val current = remember(revision) { doorbell.distributor(context) }
  val blocker = remember(revision) { doorbell.blocker(context) }
  val status = remember(revision) { doorbell.status(context) }
  val withOrder = remember(revision) { settings.withOrder() }
  val withOrderStatus = remember(revision) { doorbell.withOrderStatus(context) }
  val encryption = remember(revision) { doorbell.encryptionStatus(context) }
  // Read off the main thread: the inbox and the states are files.
  var details by remember { mutableStateOf<List<String>>(emptyList()) }
  LaunchedEffect(revision, enabled) {
    details = if (enabled) withContext(Dispatchers.IO) { runCatching { doorbell.details(context) }.getOrDefault(emptyList()) } else emptyList()
  }
  var steps by remember { mutableStateOf<List<String>>(emptyList()) }
  var notice by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("instinct_doorbell")) {
    SettingsSwitch("Timbre instantáneo (ntfy)", enabled, { doorbell.setEnabled(context, it); onChange() },
      blocker ?: "Cuando Instinct deja una orden, el Apps Script toca el timbre y Insto la toma en segundos. El timbre no lleva la " +
        "orden: Insto la lee y verifica la firma como siempre. Si ntfy no está vivo, queda la revisión de siempre.",
      enabled = enabled || blocker == null)
    if (installed.isEmpty()) SettingsDescription(com.example.phone.InstinctDoorbellText.NO_DISTRIBUTOR + ".")
    if (installed.size > 1) {
      Text("Distribuidor", style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        installed.forEach { pkg ->
          FilterChip(selected = current == pkg, onClick = { doorbell.choose(context, pkg); onChange() },
            label = { Text(distributorLabel(context, pkg)) }, modifier = Modifier.testTag("instinct_doorbell_distributor_$pkg"))
        }
      }
    }
    if (enabled) {
      SettingsDescription(status, modifier = Modifier.testTag("instinct_doorbell_status"))
      SettingsDescription(encryption, modifier = Modifier.testTag("instinct_doorbell_encryption"))
      // 1.18.0: the signed order inside the doorbell. Off by default; the script hears it like the endpoint.
      Column(Modifier.testTag("instinct_doorbell_with_order")) {
        SettingsSwitch(com.example.phone.InstinctDoorbellText.WITH_ORDER_LABEL, withOrder,
          { doorbell.setWithOrder(context, it); onChange() }, com.example.phone.InstinctDoorbellText.WITH_ORDER_DESCRIPTION)
      }
      if (withOrder) SettingsDescription(withOrderStatus, modifier = Modifier.testTag("instinct_doorbell_with_order_status"))
      OutlinedButton(onClick = {
        busy = true; notice = "Pidiéndole al script que toque el timbre…"
        scope.launch {
          notice = withContext(Dispatchers.IO) { doorbell.test(context) }
          busy = false; onChange()
        }
      }, enabled = !busy, modifier = Modifier.testTag("instinct_doorbell_test")) { Text("Probar timbre") }
      OutlinedButton(onClick = {
        busy = true; notice = "Publicando una orden de prueba que no ejecuta nada…"; steps = emptyList()
        scope.launch {
          steps = withContext(Dispatchers.IO) { runCatching { doorbell.endToEnd(context).map { it.toString() } }.getOrDefault(listOf("No se pudo probar.")) }
          notice = null; busy = false; onChange()
        }
      }, enabled = !busy, modifier = Modifier.testTag("instinct_doorbell_end_to_end")) { Text("Probar de punta a punta") }
      SettingsDescription("La prueba de punta a punta publica en el buzón una orden de solo lectura («${com.example.phone.InstinctDoorbell.END_TO_END_LINE}»): " +
        "no cambia el volumen, no crea alarmas ni ejecuta nada.")
      Column(Modifier.testTag("instinct_doorbell_steps")) { steps.forEach { SettingsDescription(it) } }
      if (details.isNotEmpty()) {
        Text("Detalle del timbre", style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.testTag("instinct_doorbell_details")) { details.forEach { SettingsDescription(it) } }
      }
    }
    notice?.let { SettingsDescription(it) }
  }
}

/** The app's name, or its package when it has none. */
private fun distributorLabel(context: android.content.Context, pkg: String): String = runCatching {
  context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

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
    val stateLine = remember(revision) { com.example.phone.InstinctStatePublisher.diagnosis(context) }
    SettingsDescription(stateLine, modifier = Modifier.testTag("instinct_state_diagnosis"))
    val mailboxLine = remember(revision) {
      if (com.example.phone.InstinctMailboxSettings(context).enabled()) com.example.phone.InstinctMailbox.diagnosis(context) else null
    }
    mailboxLine?.let { SettingsDescription(it, modifier = Modifier.testTag("instinct_mailbox_diagnosis")) }
    val doorbellLine = remember(revision) {
      if (com.example.phone.InstinctDoorbell.settings(context).enabled()) com.example.phone.InstinctDoorbell.diagnosis(context) else null
    }
    doorbellLine?.let { SettingsDescription(it, modifier = Modifier.testTag("instinct_doorbell_diagnosis")) }
    TextButton(onClick = onRefresh) { Text("Actualizar diagnóstico") }
  }
}
