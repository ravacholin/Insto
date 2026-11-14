package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.PingAssistUiState
import com.example.ui.RecordingUiState
import com.example.ui.theme.CoralPulse
import java.util.Locale

/**
 * Minimal reactive overlay shown for either:
 * 1) Dynamic 2.2s Smart Window (speaks/holds -> auto voice recording; silence -> opens chat directly)
 * 2) Direct voice recording mode (zero intermediate menus).
 */
@Composable
fun AssistOverlayScreen(
  uiState: PingAssistUiState,
  hasAudioPermission: Boolean,
  onStartRecording: () -> Unit,
  onLockDynamicHold: () -> Unit,
  onSkipDynamicAndOpenChat: () -> Unit,
  onStopAndSend: () -> Unit,
  onRequestAudioPermission: () -> Unit,
  onDismissOverlay: () -> Unit,
) {
  // Tap-vs-hold not decided yet: stay invisible so a quick tap jumps to the chat with no flash.
  if (uiState.recordingState is RecordingUiState.HoldPending) return

  val contact = uiState.effectiveRoute.contact ?: uiState.preferences.pinnedContact
  val listening = uiState.recordingState as? RecordingUiState.ListeningWindow
  val recording = uiState.recordingState as? RecordingUiState.Recording
  val isListening = listening != null
  val isRecording = recording != null

  val pulse = rememberInfiniteTransition(label = "mic_pulse")
  val scale by
    pulse.animateFloat(
      initialValue = 0.94f,
      targetValue = 1.08f,
      animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
      label = "pulse_scale",
    )

  Box(
    modifier =
      Modifier.fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onDismissOverlay,
        ),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      border =
        BorderStroke(
          1.5.dp,
          when {
            isRecording -> CoralPulse
            isListening -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
          },
        ),
      tonalElevation = 12.dp,
      shadowElevation = 24.dp,
      modifier =
        Modifier.widthIn(max = 520.dp)
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 16.dp)
          .navigationBarsPadding()
          .pointerInput(isListening) {
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              if (isListening) {
                onLockDynamicHold()
                waitForUpOrCancellation()
                onStopAndSend()
              }
            }
          },
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
          if (listening != null) {
            val progress =
              (listening.elapsedMs.toFloat() / listening.windowMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
            CircularProgressIndicator(
              progress = { progress },
              modifier = Modifier.size(52.dp),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
              strokeWidth = 3.dp,
            )
          }
          Surface(
            shape = CircleShape,
            color =
              when {
                isRecording -> CoralPulse
                else -> MaterialTheme.colorScheme.primary
              },
            modifier =
              Modifier.size(44.dp)
                .scale(
                  when {
                    isRecording -> scale
                    listening != null -> 1f + (listening.speechEnergy * 0.14f)
                    else -> 1f
                  }
                ),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (isListening) Icons.Filled.ChatBubble else Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = contact?.displayName ?: "WhatsApp Contact",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text =
              when {
                listening != null -> "Opening chat… Speak or hold to record"
                recording != null -> {
                  val secs = (recording.elapsedMs / 1000L).toInt()
                  val hint = if (recording.isLockedHandsFree) "…" else " · release to send"
                  String.format(Locale.US, "Recording %02d:%02d%s", secs / 60, secs % 60, hint)
                }
                !hasAudioPermission -> "Microphone permission needed"
                uiState.statusBanner != null -> uiState.statusBanner
                else -> "Starting microphone…"
              },
            style = MaterialTheme.typography.bodyMedium,
            color =
              when {
                isRecording -> CoralPulse
                isListening -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        if (isRecording) {
          Button(
            onClick = onStopAndSend,
            shape = RoundedCornerShape(20.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = CoralPulse,
                contentColor = Color.White,
              ),
            modifier = Modifier.height(48.dp).testTag("overlay_send_button"),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.Send,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Send")
          }
        } else if (isListening) {
          Button(
            onClick = onSkipDynamicAndOpenChat,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(48.dp).testTag("overlay_open_now_button"),
          ) {
            Text("Open")
          }
        } else if (!hasAudioPermission) {
          Button(
            onClick = onRequestAudioPermission,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(48.dp),
          ) {
            Text("Allow")
          }
        } else {
          Button(
            onClick = onStartRecording,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(48.dp),
          ) {
            Text("Record")
          }
        }

        IconButton(
          onClick = onDismissOverlay,
          modifier = Modifier.size(48.dp).testTag("overlay_close_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Cancel",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
