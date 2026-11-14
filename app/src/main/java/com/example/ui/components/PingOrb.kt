package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.OrbHoldBehavior
import com.example.ui.RecordingUiState
import com.example.ui.theme.CoralPulse
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun PingOrb(
  contactName: String?,
  recordingState: RecordingUiState,
  hasAudioPermission: Boolean,
  holdBehavior: OrbHoldBehavior = OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO,
  onTapOpenChat: () -> Unit,
  onStartRecording: () -> Boolean,
  onStopAndSend: () -> Unit,
  onCancelRecording: () -> Unit,
  onRequestAudioPermission: () -> Unit,
  modifier: Modifier = Modifier,
  orbSize: Dp = 192.dp,
  compactMode: Boolean = false,
) {
  val haptic = LocalHapticFeedback.current
  val coroutineScope = rememberCoroutineScope()
  var isFingerDown by remember { mutableStateOf(false) }
  var holdElapsedMs by remember { mutableLongStateOf(0L) }

  val holdThresholdMs =
    when (holdBehavior) {
      OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO -> 2_500L
      OrbHoldBehavior.INSTANT_HOLD_TO_RECORD -> 280L
    }

  val currentHasContact by rememberUpdatedState(contactName != null)
  val currentHasPermission by rememberUpdatedState(hasAudioPermission)
  val currentRecordingState by rememberUpdatedState(recordingState)
  val currentHoldThresholdMs by rememberUpdatedState(holdThresholdMs)
  val currentOnTapOpenChat by rememberUpdatedState(onTapOpenChat)
  val currentOnStartRecording by rememberUpdatedState(onStartRecording)
  val currentOnStopAndSend by rememberUpdatedState(onStopAndSend)
  val currentOnCancelRecording by rememberUpdatedState(onCancelRecording)
  val currentOnRequestAudioPermission by rememberUpdatedState(onRequestAudioPermission)

  val isRecording = recordingState is RecordingUiState.Recording
  val isSending = recordingState is RecordingUiState.Sending
  val isTapToStopMode =
    (recordingState as? RecordingUiState.Recording)?.isLockedHandsFree == true

  val holdProgress =
    if (isFingerDown && !isRecording && contactName != null && holdThresholdMs > 0L) {
      (holdElapsedMs.toFloat() / holdThresholdMs.toFloat()).coerceIn(0f, 1f)
    } else {
      0f
    }

  val primaryColor = MaterialTheme.colorScheme.primary
  val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
  val activeColor by
    animateColorAsState(
      targetValue =
        when {
          isRecording -> CoralPulse
          isFingerDown && contactName != null && holdProgress > 0.72f -> CoralPulse.copy(alpha = 0.85f)
          contactName != null -> primaryColor
          else -> MaterialTheme.colorScheme.secondary
        },
      animationSpec = tween(160),
      label = "orb_active_color",
    )

  val orbScale by
    animateFloatAsState(
      targetValue =
        when {
          isRecording -> 1.07f
          isFingerDown -> 0.94f
          else -> 1f
        },
      animationSpec =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
      label = "orb_scale",
    )

  val infiniteTransition = rememberInfiniteTransition(label = "orb_sonar")
  val sonarProgress by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "sonar_progress",
    )

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(orbSize + 84.dp),
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = orbSize.toPx() / 2f

        if (recordingState is RecordingUiState.Recording) {
          val amps = recordingState.amplitudes
          val barCount = amps.size.coerceAtLeast(1)
          val maxBarLength = (size.minDimension / 2f) - baseRadius - 10.dp.toPx()
          for (i in 0 until barCount) {
            val angleRad = ((i.toFloat() / barCount) * 2.0 * PI) - (PI / 2.0)
            val amp = amps[i].coerceIn(0.08f, 1f)
            val startR = baseRadius + 8.dp.toPx()
            val endR = startR + (maxBarLength * amp)
            val start =
              Offset(
                x = center.x + (startR * cos(angleRad)).toFloat(),
                y = center.y + (startR * sin(angleRad)).toFloat(),
              )
            val end =
              Offset(
                x = center.x + (endR * cos(angleRad)).toFloat(),
                y = center.y + (endR * sin(angleRad)).toFloat(),
              )
            drawLine(
              color = CoralPulse.copy(alpha = 0.45f + 0.55f * amp),
              start = start,
              end = end,
              strokeWidth = 5.dp.toPx(),
              cap = StrokeCap.Round,
            )
          }
        } else if (holdProgress > 0f) {
          // Progressive live hold arc: 0 -> 100% fills up to transition from Open Chat into Voice Recording
          val ringRadius = baseRadius + 10.dp.toPx()
          drawCircle(
            color = activeColor.copy(alpha = 0.22f),
            radius = ringRadius,
            center = center,
            style = Stroke(width = 6.dp.toPx()),
          )
          drawArc(
            color = if (holdProgress > 0.72f) CoralPulse else primaryColor,
            startAngle = -90f,
            sweepAngle = 360f * holdProgress,
            useCenter = false,
            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
            size = Size(ringRadius * 2f, ringRadius * 2f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
          )
        } else {
          for (ringIdx in 0..1) {
            val phase = (sonarProgress + ringIdx * 0.5f) % 1f
            val ringRadius = baseRadius + (phase * 42.dp.toPx())
            val alpha = (1f - phase) * if (contactName != null) 0.36f else 0.16f
            drawCircle(
              color = activeColor.copy(alpha = alpha),
              radius = ringRadius,
              center = center,
              style = Stroke(width = 2.dp.toPx()),
            )
          }
        }
      }

      // Stationary pointer hit target Box — visual scaling is applied strictly to inner children
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.size(orbSize)
            .testTag("assistant_ping_orb")
            .semantics {
              contentDescription =
                if (contactName != null) {
                  "Direct WhatsApp button for $contactName. Release within 2.5 seconds to open chat, or keep holding past 2.5 seconds to record and send audio."
                } else {
                  "Select WhatsApp contact"
                }
            }
            .pointerInput(holdBehavior) {
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                isFingerDown = true
                holdElapsedMs = 0L

                var longPressTriggered = false
                var recordingStarted = false
                val downPos = down.position
                val maxDragBeforeTriggerPx = size.width.toFloat().coerceAtLeast(180f)
                val targetThresholdMs = currentHoldThresholdMs

                val holdJob =
                  coroutineScope.launch {
                    val stepMs = 40L
                    var elapsed = 0L
                    while (isActive && elapsed < targetThresholdMs) {
                      val chunk = (targetThresholdMs - elapsed).coerceAtMost(stepMs)
                      delay(chunk)
                      elapsed += chunk
                      holdElapsedMs = elapsed
                    }
                    if (!isActive) return@launch

                    longPressTriggered = true
                    isFingerDown = false
                    when {
                      !currentHasContact -> currentOnTapOpenChat()
                      !currentHasPermission -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnRequestAudioPermission()
                      }
                      currentRecordingState is RecordingUiState.Idle -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        recordingStarted = currentOnStartRecording()
                      }
                    }
                  }

                var wasCancelled = false
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Main)
                  val change =
                    event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                  if (change == null) break

                  if (!longPressTriggered) {
                    val dist = (change.position - downPos).getDistance()
                    if (dist > maxDragBeforeTriggerPx) {
                      wasCancelled = true
                      change.consume()
                      break
                    }
                  }

                  if (change.changedToUpIgnoreConsumed()) {
                    change.consume()
                    break
                  }

                  if (change.isConsumed && !longPressTriggered) {
                    wasCancelled = true
                    break
                  }
                }

                holdJob.cancel()
                isFingerDown = false
                holdElapsedMs = 0L

                if (!wasCancelled) {
                  if (longPressTriggered) {
                    if (recordingStarted) {
                      currentOnStopAndSend()
                    }
                  } else {
                    when (currentRecordingState) {
                      is RecordingUiState.Recording -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnStopAndSend()
                      }
                      is RecordingUiState.Idle,
                      is RecordingUiState.ListeningWindow -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnTapOpenChat()
                      }
                      RecordingUiState.Sending -> Unit
                    }
                  }
                } else if (recordingStarted) {
                  currentOnCancelRecording()
                }
              }
            },
      ) {
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.size(orbSize)
              .scale(orbScale)
              .clip(CircleShape)
              .background(
                brush =
                  Brush.radialGradient(
                    colors = listOf(activeColor.copy(alpha = 0.28f), surfaceColor)
                  )
              ),
        ) {
          Surface(
            shape = CircleShape,
            color = activeColor,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.size(orbSize - 26.dp),
          ) {
            Column(
              modifier = Modifier.fillMaxSize().padding(14.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Icon(
                imageVector =
                  when {
                    isRecording -> Icons.Filled.Mic
                    isFingerDown && contactName != null && holdProgress > 0.72f -> Icons.Filled.Mic
                    contactName == null -> Icons.Filled.TouchApp
                    else -> Icons.Filled.ChatBubble
                  },
                contentDescription = null,
                tint =
                  if (isRecording || (isFingerDown && contactName != null && holdProgress > 0.72f))
                    Color.White
                  else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(if (compactMode) 34.dp else 44.dp),
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text =
                  when (recordingState) {
                    is RecordingUiState.Recording -> formatElapsed(recordingState.elapsedMs)
                    RecordingUiState.Sending -> "Sending..."
                    is RecordingUiState.ListeningWindow -> "LISTENING…"
                    RecordingUiState.Idle ->
                      when {
                        contactName == null -> "SET CONTACT"
                        isFingerDown && holdBehavior == OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO -> {
                          val remainingTenths = ((holdThresholdMs - holdElapsedMs).coerceAtLeast(0L) / 100L)
                          val sec = remainingTenths / 10f
                          String.format(Locale.US, "CHAT · %.1fs → MIC", sec)
                        }
                        isFingerDown -> "HOLD FOR AUDIO"
                        else -> "OPEN CHAT"
                      }
                  },
                style =
                  if (isRecording) MaterialTheme.typography.titleLarge
                  else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color =
                  if (isRecording || (isFingerDown && contactName != null && holdProgress > 0.72f))
                    Color.White
                  else MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
              )
              if (isRecording) {
                Text(
                  text = if (isTapToStopMode) "TAP TO SEND" else "RELEASE TO SEND",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White.copy(alpha = 0.92f),
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }
      }

      if (isRecording && isTapToStopMode) {
        FilledTonalIconButton(
          onClick = onCancelRecording,
          modifier =
            Modifier.align(Alignment.BottomEnd)
              .padding(8.dp)
              .size(48.dp)
              .testTag("cancel_recording_button"),
        ) {
          Icon(Icons.Filled.Close, contentDescription = "Cancel recording")
        }
      }
    }

    Spacer(modifier = Modifier.height(if (compactMode) 6.dp else 10.dp))

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ) {
      Text(
        text =
          when {
            isRecording && isTapToStopMode ->
              "Recording for ${contactName ?: "contact"}… Tap orb to send"
            isRecording ->
              "Recording audio for ${contactName ?: "contact"}… Release to send"
            isSending -> "Sending directly to WhatsApp…"
            isFingerDown && contactName != null && holdBehavior == OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO ->
              "Release now to open chat · Keep holding for audio…"
            isFingerDown && contactName != null -> "Keep holding to start voice note…"
            contactName == null -> "Tap to choose your WhatsApp contact"
            !hasAudioPermission ->
              "Tap/0–2.5s: open $contactName · Hold 2.5s+: grant mic & record"
            holdBehavior == OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO ->
              "0–2.5s: open $contactName · Keep holding (2.5s+): record audio"
            else -> "Tap: open $contactName · Hold & release: send audio"
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      )
    }
  }
}

private fun formatElapsed(ms: Long): String {
  val totalSeconds = (ms / 1000L).toInt()
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
