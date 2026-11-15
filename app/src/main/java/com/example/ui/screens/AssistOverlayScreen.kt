package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
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
) {
  val context = LocalContext.current
  val accent = Color.White
  val secondary = Color(0xFFAAAAAA)
  val edge = Color(0xFF555555)
  val controlShape = RoundedCornerShape(2.dp)
  val busy = state.mode == FloatingMode.SENDING
  val recording = state.mode == FloatingMode.RECORDING
  val listening = state.mode == FloatingMode.LISTENING
  val canSend = recording || state.mode == FloatingMode.AUDIO_READY ||
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
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(34.dp).background(Color.White, controlShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, null, tint = Color.Black, modifier = Modifier.size(19.dp))
          }
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text(state.contactName, fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("ASISTENTE / WHATSAPP", color = secondary,
              style = MaterialTheme.typography.labelSmall)
          }
          IconButton(onClick = onDismissOverlay, modifier = Modifier.testTag("overlay_close_button")) {
            Icon(Icons.Filled.Close, "Cerrar asistente", tint = secondary)
          }
        }
        HorizontalDivider(color = edge)
        Text(
          text = when (state.mode) {
            FloatingMode.LISTENING -> "Te escucho… o tocá para escribir"
            FloatingMode.RECORDING -> "Grabando · se envía tras 3 s de silencio"
            FloatingMode.TYPING -> "Mensaje de texto · micrófono apagado"
            FloatingMode.SENDING -> "Preparando envío…"
            FloatingMode.AUDIO_READY -> "Audio guardado · tocá Enviar para intentarlo"
            FloatingMode.IDLE -> if (hasAudioPermission) "Escribí o activá el micrófono"
              else "Podés escribir o permitir el micrófono"
          },
          color = accent, style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
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
          placeholder = { Text("Mensaje para ${state.contactName}", color = secondary) },
          enabled = !busy, maxLines = 4, shape = controlShape,
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
        state.error?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (!autoSendEnabled) {
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
          IconButton(
            onClick = { focus.clearFocus(); keyboard?.hide(); onMicrophone() },
            enabled = !busy && !recording && !listening,
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
            Text("ENVIAR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
