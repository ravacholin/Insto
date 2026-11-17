package com.example.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import com.example.phone.AppOption
import com.example.phone.ContactOption
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.FloatingAssistantState
import com.example.ui.FloatingMode

/** Assistant input lives here; WhatsApp is only brought forward at dispatch time. */
@Composable
fun AssistOverlayScreen(
  state: FloatingAssistantState,
  hasAudioPermission: Boolean,
  autoSendEnabled: Boolean,
  onTextFocus: () -> Unit,
  onTextChange: (String) -> Unit,
  onMicrophone: () -> Unit,
  onSend: () -> Unit,
  onDismissOverlay: () -> Unit,
  onAttach: () -> Unit = {},
  onRemoveAttachment: () -> Unit = {},
  onConfigureGroq: () -> Unit = {},
  onConfigureContact: () -> Unit = {},
  onConfigureClockAccessibility: () -> Unit = {},
  onConfirm: (Boolean) -> Unit = {},
  onChooseApp: (AppOption) -> Unit = {},
  onChooseContact: (ContactOption) -> Unit = {},
  onContinueWorkflow: () -> Unit = {},
  onRetryWorkflow: () -> Unit = {},
  onCancelWorkflow: () -> Unit = {},
) {
  var showExamples by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val accent = Color.White
  val secondary = Color(0xFFAAAAAA)
  val edge = Color(0xFF555555)
  val controlShape = RoundedCornerShape(2.dp)
  val busy = state.mode == FloatingMode.SENDING
  val recording = state.mode == FloatingMode.RECORDING
  val listening = state.mode == FloatingMode.LISTENING
  val canSend = state.attachment != null || recording || state.mode == FloatingMode.AUDIO_READY ||
    (state.mode == FloatingMode.TYPING && state.text.isNotBlank())
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Box(Modifier.matchParentSize().clickable(
      interactionSource = remember { MutableInteractionSource() }, indication = null,
      onClick = onDismissOverlay))
    Surface(
      modifier = Modifier.imePadding().navigationBarsPadding().padding(12.dp)
        .widthIn(max = 560.dp).fillMaxWidth(),
      shape = RoundedCornerShape(4.dp),
      color = Color(0xFF111111),
      contentColor = Color.White,
      border = BorderStroke(1.dp, edge),
      shadowElevation = 0.dp,
      onClick = {},
    ) {
      Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(34.dp).background(Color.White, controlShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, null, tint = Color.Black, modifier = Modifier.size(19.dp))
          }
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text("Teléfono + ${state.contactName}", fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("DESTINO AUTOMÁTICO · ${com.example.BuildConfig.VERSION_NAME}", color = secondary,
              style = MaterialTheme.typography.labelSmall)
          }
          TextButton(onClick = onConfigureGroq, enabled = !busy) { Text("IA", color = secondary) }
          IconButton(onClick = onDismissOverlay, modifier = Modifier.testTag("overlay_close_button")) {
            Icon(Icons.Filled.Close, "Cerrar asistente", tint = secondary)
          }
        }
        HorizontalDivider(color = edge)
        if (!state.geminiConfigured) {
          Text("Gemini Decisions sin configurar", color = secondary, style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = onConfigureGroq, enabled = !busy) { Text("Configurar clave de Gemini", color = Color.White) }
        } else {
          Text(if (state.geminiEnabled) "Gemini Decisions activado" else "Gemini Decisions desactivado", color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (state.needsClockAccessibility) {
          TextButton(onClick = onConfigureClockAccessibility, enabled = !busy) { Text("Activar Accesibilidad para el cronómetro", color = Color.White) }
        }
        Text(
          text = when (state.mode) {
            FloatingMode.LISTENING -> "Te escucho… o tocá para escribir"
            FloatingMode.RECORDING -> "Grabando · se interpreta tras 3 s de silencio"
            FloatingMode.TYPING -> "Pedido o consulta · micrófono apagado"
            FloatingMode.SENDING -> state.status ?: "Interpretando pedido…"
            FloatingMode.AUDIO_READY -> "Audio guardado · podés reintentar sin grabar de nuevo"
            FloatingMode.IDLE -> if (hasAudioPermission) "Escribí o activá el micrófono"
              else "Podés escribir o permitir el micrófono"
          },
          color = accent, style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!busy) state.status?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.error?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (state.workflowLines.isNotEmpty()) {
          Text(state.workflowLines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
        if (state.workflowActive && !busy && !recording && !listening) {
          Column {
            if (state.workflowCanContinue) TextButton(onClick = onContinueWorkflow) { Text("Ya lo revisé · continuar sin repetir") }
            if (state.workflowCanRetry) TextButton(onClick = onRetryWorkflow) { Text("Reintentar solo este paso") }
            TextButton(onClick = onCancelWorkflow) { Text("Cancelar pasos pendientes") }
          }
        }
        state.accessSettingsAction?.let { action ->
          TextButton(onClick = { context.startActivity(android.content.Intent(action).apply {
            if (action == android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS) data = android.net.Uri.fromParts("package", context.packageName, null)
          }) }, enabled = !busy) { Text("Habilitar acceso en Ajustes") }
        }
        if (!busy && !recording && !listening) {
          TextButton(onClick = { showExamples = !showExamples }) {
            Text(if (showExamples) "Ocultar ejemplos" else "Ejemplos de pedidos", color = secondary)
          }
          if (showExamples) Text(
            "Llevame a Plaza de Mayo · Llevame a pie a Retiro\n" +
              "Guardá mi casa en Av. Córdoba 1234 · Llevame a casa\n" +
              "Buscá farmacias cerca · Llamá a Ana\n" +
              "Agendá reunión mañana a las 17:00 durante una hora\n" +
              "Abrí la cámara para grabar · Buscá jazz en YouTube\n" +
              "Poné música de Soda Stereo · Pausá la música\n" +
              "Qué tengo mañana · Mostrá mis rutinas\n" +
              "Silenciá el teléfono durante una hora\n" +
              "Las llamadas y eventos se revisan en su propia app.",
            color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (recording || listening) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.weight(1f).height(26.dp)) {
              repeat(32) { i ->
                val shape = 0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(i * 1.7f))
                val half = 2f + size.height * 0.45f * state.amplitude * shape
                val x = size.width * (i + 0.5f) / 32f
                drawLine(accent, Offset(x, size.height / 2 - half),
                  Offset(x, size.height / 2 + half), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Butt)
              }
            }
            Text(if (recording) "%d:%02d".format(state.elapsedMs / 60000, state.elapsedMs / 1000 % 60)
              else "${(4 - state.elapsedMs / 1000).coerceAtLeast(0)} s",
              style = MaterialTheme.typography.labelSmall, color = accent)
          }
        }
        OutlinedTextField(
          value = state.text, onValueChange = onTextChange,
          modifier = Modifier.fillMaxWidth().testTag("overlay_text_input")
            .onFocusChanged { if (it.isFocused) onTextFocus() },
          placeholder = { Text("Pedime algo del teléfono o consultá a ${state.contactName}", color = secondary) },
          enabled = !busy || state.deciding, maxLines = 4, shape = controlShape,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { if (canSend && !busy) onSend() }),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF191919), unfocusedContainerColor = Color(0xFF191919),
            disabledContainerColor = Color(0xFF191919), disabledTextColor = secondary,
            disabledBorderColor = edge, selectionColors = androidx.compose.foundation.text.selection.TextSelectionColors(
              handleColor = Color.White, backgroundColor = Color(0xFF555555)),
            cursorColor = accent, focusedBorderColor = accent,
            unfocusedBorderColor = edge),
        )
        state.attachment?.let { attachment ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(attachment.name, modifier = Modifier.weight(1f).testTag("overlay_attachment"),
              maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onRemoveAttachment, enabled = !busy) {
              Icon(Icons.Filled.Close, "Quitar adjunto")
            }
          }
        }
        state.transcript?.let { Text("Escuché: $it", color = secondary, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        if (state.needsConfirmation && !busy && !recording && !listening) {
          Row {
            TextButton(onClick = { onConfirm(true) }) { Text("Confirmar") }
            TextButton(onClick = { onConfirm(false) }) { Text("Cancelar") }
          }
        }
        state.appOptions.forEachIndexed { index, app ->
          TextButton(onClick = { onChooseApp(app) }, enabled = !busy && !recording && !listening) { Text("${index + 1}. ${app.label}") }
        }
        state.contactOptions.forEachIndexed { index, contact ->
          TextButton(onClick = { onChooseContact(contact) }, enabled = !busy && !recording && !listening) {
            Text("${index + 1}. ${contact.name} · ${contact.number}")
          }
        }
        if (state.needsGroqKey) TextButton(onClick = onConfigureGroq) { Text("Configurar mi clave de Groq") }
        if (state.error?.contains("contacto") == true) TextButton(onClick = onConfigureContact) { Text("Configurar contacto de Instinct") }
        if (!autoSendEnabled && state.needsWhatsAppAccessibility) {
          Text("Para enviar sin volver a tocar WhatsApp, activá el servicio de Accesibilidad de PingAssist.",
            color = secondary, style = MaterialTheme.typography.labelSmall)
          TextButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
          }, modifier = Modifier.testTag("overlay_enable_auto_send")) {
            Text("Activar envío automático", color = accent)
          }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween) {
          IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); onAttach() },
            enabled = !busy, modifier = Modifier.size(48.dp).testTag("overlay_attach_button")) {
            Icon(Icons.Filled.Add, "Adjuntar imagen o documento", tint = accent)
          }
          IconButton(
            onClick = { focus.clearFocus(); keyboard?.hide(); onMicrophone() },
            enabled = !busy && !recording && !listening && state.attachment == null,
            modifier = Modifier.size(48.dp).background(Color(0xFF292929), controlShape)
              .testTag("overlay_microphone"),
          ) {
            Icon(Icons.Filled.Mic, "Activar escucha", tint = if (busy || recording || listening) secondary else accent)
          }
          Button(onClick = onSend, enabled = canSend && !busy,
            shape = controlShape,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black,
              disabledContainerColor = Color(0xFF292929), disabledContentColor = secondary),
            modifier = Modifier.height(48.dp).testTag("overlay_send_button")) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = secondary, strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.mode == FloatingMode.AUDIO_READY) "REINTENTAR" else "LISTO", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
