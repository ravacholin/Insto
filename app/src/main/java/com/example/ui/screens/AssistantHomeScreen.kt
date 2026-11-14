package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AssistantActivationMode
import com.example.data.FloatingPillMode
import com.example.data.OrbHoldBehavior
import com.example.data.PinnedContact
import com.example.data.VoiceRecordingMode
import com.example.data.VolumeKeyTrigger
import com.example.data.WhatsAppFlavor
import com.example.ui.PingAssistUiState
import com.example.ui.components.PingOrb
import com.example.whatsapp.PhoneNumberUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssistantHomeScreen(
  uiState: PingAssistUiState,
  hasAudioPermission: Boolean,
  onEditContactClick: () -> Unit,
  onEditDrivingContactClick: () -> Unit,
  onClearDrivingContact: () -> Unit,
  onTapOpenChat: () -> Unit,
  onStartRecording: () -> Boolean,
  onStopAndSend: () -> Unit,
  onCancelRecording: () -> Unit,
  onRequestAudioPermission: () -> Unit,
  onSelectActivationMode: (AssistantActivationMode) -> Unit,
  onSelectOrbHoldBehavior: (OrbHoldBehavior) -> Unit,
  onSelectWhatsAppFlavor: (WhatsAppFlavor) -> Unit,
  onSelectVoiceRecordingMode: (VoiceRecordingMode) -> Unit,
  onSelectVolumeKeyTrigger: (VolumeKeyTrigger) -> Unit,
  onSelectFloatingPillMode: (FloatingPillMode) -> Unit,
  onToggleEyesFreeCues: (Boolean) -> Unit,
  onTestAudioCue: () -> Unit,
  onToggleBoomerangReturn: (Boolean) -> Unit,
  onToggleCarBtAutoVoice: (Boolean) -> Unit,
  onToggleNightWindow: (Boolean) -> Unit,
  onSetDefaultAssistantClick: () -> Unit,
  onClearLogs: () -> Unit,
  onDismissBanner: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  var showSetupSheet by remember { mutableStateOf(false) }

  LaunchedEffect(uiState.statusBanner) {
    val msg = uiState.statusBanner
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      onDismissBanner()
    }
  }

  val prefs = uiState.preferences
  val route = uiState.effectiveRoute
  val displayContact = route.contact ?: prefs.pinnedContact
  val system = uiState.systemState

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { data ->
        Snackbar(
          snackbarData = data,
          containerColor =
            if (uiState.isBannerError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
          contentColor =
            if (uiState.isBannerError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors =
                listOf(
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                  MaterialTheme.colorScheme.background,
                  MaterialTheme.colorScheme.background,
                )
            )
          )
          .padding(innerPadding),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 580.dp)
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Top Header + Pinned Contact
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text(
                text = "PingAssist",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = route.activeContextLabel ?: "Direct WhatsApp assistant",
                style = MaterialTheme.typography.bodyMedium,
                color =
                  if (route.activeContextLabel != null) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            OutlinedButton(
              onClick = { showSetupSheet = true },
              shape = RoundedCornerShape(20.dp),
              modifier = Modifier.height(48.dp).testTag("open_setup_sheet_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Setup")
            }
          }

          PinnedContactCard(
            contact = displayContact,
            hasWhatsApp = system.installedWhatsApp.hasAnyWhatsApp,
            isCarOrBluetoothActive = route.isCarOrBluetoothActive,
            onEditClick = onEditContactClick,
          )
        }

        // Centerpiece: Direct Action Orb (no intermediate menu!)
        PingOrb(
          contactName = displayContact?.displayName,
          recordingState = uiState.recordingState,
          hasAudioPermission = hasAudioPermission,
          holdBehavior = prefs.orbHoldBehavior,
          onTapOpenChat = {
            if (displayContact == null) onEditContactClick() else onTapOpenChat()
          },
          onStartRecording = onStartRecording,
          onStopAndSend = onStopAndSend,
          onCancelRecording = onCancelRecording,
          onRequestAudioPermission = onRequestAudioPermission,
          orbSize = 200.dp,
        )

        // Bottom Compact Readiness Strip
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!hasAudioPermission) {
            StatusPill(
              label = "Enable Mic",
              active = false,
              icon = Icons.Filled.WarningAmber,
              onClick = onRequestAudioPermission,
              modifier = Modifier.weight(1f).testTag("mic_permission_pill"),
            )
          }

          StatusPill(
            label = if (system.isDefaultAssistant) "Assistant On" else "Set Assistant",
            active = system.isDefaultAssistant,
            icon = if (system.isDefaultAssistant) Icons.Filled.CheckCircle else Icons.Filled.Assistant,
            onClick = onSetDefaultAssistantClick,
            modifier = Modifier.weight(1f).testTag("assistant_role_badge"),
          )

          StatusPill(
            label = if (system.isAutoSendEnabled) "Auto-Send On" else "Auto-Send",
            active = system.isAutoSendEnabled,
            icon =
              if (system.isAutoSendEnabled) Icons.Filled.CheckCircle
              else Icons.Filled.AccessibilityNew,
            onClick = {
              runCatching {
                context.startActivity(
                  Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                )
              }
            },
            modifier = Modifier.weight(1f).testTag("accessibility_settings_button"),
          )
        }
      }
    }
  }

  if (showSetupSheet) {
    AssistantSetupSheet(
      uiState = uiState,
      onDismiss = { showSetupSheet = false },
      onSelectActivationMode = onSelectActivationMode,
      onSelectOrbHoldBehavior = onSelectOrbHoldBehavior,
      onSelectWhatsAppFlavor = onSelectWhatsAppFlavor,
      onSelectVoiceRecordingMode = onSelectVoiceRecordingMode,
      onSelectVolumeKeyTrigger = onSelectVolumeKeyTrigger,
      onSelectFloatingPillMode = onSelectFloatingPillMode,
      onToggleEyesFreeCues = onToggleEyesFreeCues,
      onTestAudioCue = onTestAudioCue,
      onToggleBoomerangReturn = onToggleBoomerangReturn,
      onToggleCarBtAutoVoice = onToggleCarBtAutoVoice,
      onToggleNightWindow = onToggleNightWindow,
      onEditDrivingContactClick = onEditDrivingContactClick,
      onClearDrivingContact = onClearDrivingContact,
      onSetDefaultAssistantClick = onSetDefaultAssistantClick,
      onOpenAccessibilitySettings = {
        runCatching {
          context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
          )
        }
      },
      onClearLogs = onClearLogs,
    )
  }
}

@Composable
private fun StatusPill(
  label: String,
  active: Boolean,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color =
      if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
      else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
          else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f),
      ),
    modifier = modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint =
          if (active) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color =
          if (active) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onTertiaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantSetupSheet(
  uiState: PingAssistUiState,
  onDismiss: () -> Unit,
  onSelectActivationMode: (AssistantActivationMode) -> Unit,
  onSelectOrbHoldBehavior: (OrbHoldBehavior) -> Unit,
  onSelectWhatsAppFlavor: (WhatsAppFlavor) -> Unit,
  onSelectVoiceRecordingMode: (VoiceRecordingMode) -> Unit,
  onSelectVolumeKeyTrigger: (VolumeKeyTrigger) -> Unit,
  onSelectFloatingPillMode: (FloatingPillMode) -> Unit,
  onToggleEyesFreeCues: (Boolean) -> Unit,
  onTestAudioCue: () -> Unit,
  onToggleBoomerangReturn: (Boolean) -> Unit,
  onToggleCarBtAutoVoice: (Boolean) -> Unit,
  onToggleNightWindow: (Boolean) -> Unit,
  onEditDrivingContactClick: () -> Unit,
  onClearDrivingContact: () -> Unit,
  onSetDefaultAssistantClick: () -> Unit,
  onOpenAccessibilitySettings: () -> Unit,
  onClearLogs: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val prefs = uiState.preferences
  val system = uiState.systemState

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .padding(bottom = 24.dp)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "Assistant & Automation Setup",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      if (!system.installedWhatsApp.hasAnyWhatsApp) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              Icons.Filled.WarningAmber,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
              text = "WhatsApp is not installed on this device; link fallback will be used.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
          }
        }
      }

      // 1. Hold Duration & Voice Strategy
      Text(text = "1. Orb Hold Timing (Chat vs Audio)", style = MaterialTheme.typography.titleMedium)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbHoldBehavior.entries.forEach { behavior ->
          val selected = prefs.orbHoldBehavior == behavior
          Surface(
            shape = RoundedCornerShape(16.dp),
            color =
              if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border =
              BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color =
                  if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
              ),
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onSelectOrbHoldBehavior(behavior) }
                .testTag("orb_hold_${behavior.name.lowercase()}"),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(text = behavior.title, style = MaterialTheme.typography.titleMedium)
              Text(
                text = behavior.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // 2. Voice Recording Strategy (Native WhatsApp PTT vs Audio File)
      Text(text = "2. Voice Note Mode", style = MaterialTheme.typography.titleMedium)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceRecordingMode.entries.forEach { mode ->
          val selected = prefs.voiceRecordingMode == mode
          Surface(
            shape = RoundedCornerShape(16.dp),
            color =
              if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border =
              BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color =
                  if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
              ),
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onSelectVoiceRecordingMode(mode) }
                .testTag("voice_mode_${mode.name.lowercase()}"),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(text = mode.label, style = MaterialTheme.typography.titleMedium)
              Text(
                text = mode.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // 2. System & Physical Triggers
      Text(text = "2. System & Physical Triggers", style = MaterialTheme.typography.titleMedium)

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistantActivationMode.entries.forEach { mode ->
          val selected = prefs.activationMode == mode
          Surface(
            shape = RoundedCornerShape(16.dp),
            color =
              if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border =
              BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color =
                  if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
              ),
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onSelectActivationMode(mode) }
                .testTag("mode_${mode.name.lowercase()}"),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(text = mode.title, style = MaterialTheme.typography.titleMedium)
              Text(
                text = mode.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      if (
        prefs.activationMode == AssistantActivationMode.TAP_CHAT_HOLD_AUDIO &&
          !system.isAutoSendEnabled
      ) {
        Text(
          text =
            "Hold-to-record needs the Accessibility Helper (section below) to see the hotkey. " +
              "Until it is on, the hotkey always opens the chat.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("hold_mode_accessibility_hint"),
        )
      }

      Button(
        onClick = onSetDefaultAssistantClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("set_default_assistant_button"),
      ) {
        Text(
          if (system.isDefaultAssistant) "Manage Default Assistant"
          else "Set PingAssist as Default Assistant"
        )
      }

      Text(
        text = "Physical Volume-Hold Trigger (via Accessibility)",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        VolumeKeyTrigger.entries.forEach { trigger ->
          FilterChip(
            selected = prefs.volumeKeyTrigger == trigger,
            onClick = { onSelectVolumeKeyTrigger(trigger) },
            label = { Text(trigger.label, maxLines = 1) },
          )
        }
      }

      Text(
        text = "Tip: Add the PingAssist tile in Android Quick Settings for 1-tap notification shade access.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // 3. Context Automation Rules
      Text(text = "3. Context Automation", style = MaterialTheme.typography.titleMedium)
      SwitchRow(
        title = "Auto-Voice in Car / Bluetooth",
        subtitle = "Automatically switches to direct voice mode when Car Mode or Bluetooth audio is connected.",
        checked = prefs.contextRule.switchToVoiceInCarOrBluetooth,
        onCheckedChange = onToggleCarBtAutoVoice,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Driving Contact Override", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = prefs.contextRule.drivingContact?.displayName ?: "Uses primary pinned contact",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (prefs.contextRule.drivingContact != null) {
            TextButton(onClick = onClearDrivingContact) { Text("Clear") }
          }
          OutlinedButton(onClick = onEditDrivingContactClick) { Text("Set") }
        }
      }

      SwitchRow(
        title = "Night Window Voice Mode (21:00–07:00)",
        subtitle = "Prioritize instant voice recording at night.",
        checked = prefs.contextRule.nightWindowEnabled,
        onCheckedChange = onToggleNightWindow,
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // 4. Eyes-Free Audio & Haptic Cues
      Text(text = "4. Eyes-Free Feedback", style = MaterialTheme.typography.titleMedium)
      SwitchRow(
        title = "Audio & Haptic Cues",
        subtitle = "Ducks media briefly and plays distinct tones for recording start, sent, and error.",
        checked = prefs.eyesFreeAudioCues,
        onCheckedChange = onToggleEyesFreeCues,
      )
      OutlinedButton(
        onClick = onTestAudioCue,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("test_audio_cue_button"),
      ) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Test Start Audio & Haptic Cue")
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // 5. Floating Pill & Boomerang Return
      Text(
        text = "5. Floating Pill & Auto-Return (Accessibility)",
        style = MaterialTheme.typography.titleMedium,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FloatingPillMode.entries.forEach { pillMode ->
          FilterChip(
            selected = prefs.floatingPillMode == pillMode,
            onClick = { onSelectFloatingPillMode(pillMode) },
            label = { Text(pillMode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          )
        }
      }

      SwitchRow(
        title = "Boomerang Return After Send",
        subtitle = "Automatically returns to Maps / Waze ~0.7s after sending a voice note.",
        checked = prefs.boomerangReturnEnabled,
        onCheckedChange = onToggleBoomerangReturn,
      )

      Button(
        onClick = onOpenAccessibilitySettings,
        shape = RoundedCornerShape(16.dp),
        colors =
          if (system.isAutoSendEnabled) ButtonDefaults.outlinedButtonColors()
          else ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Text(
          if (system.isAutoSendEnabled) "Accessibility Helper Active (Settings)"
          else "Enable Accessibility Auto-Send & Triggers"
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      Text(text = "WhatsApp App", style = MaterialTheme.typography.titleMedium)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        WhatsAppFlavor.entries.forEach { flavor ->
          FilterChip(
            selected = prefs.preferredFlavor == flavor,
            onClick = { onSelectWhatsAppFlavor(flavor) },
            label = { Text(flavor.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          )
        }
      }

      if (prefs.recentLogs.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(text = "Recent Activity", style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = onClearLogs) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Clear")
          }
        }
        prefs.recentLogs.take(5).forEach { log ->
          val timeText = remember(log.timestampMillis) {
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(log.timestampMillis))
          }
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "${log.statusLabel} (${log.contactName})",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = timeText,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun PinnedContactCard(
  contact: PinnedContact?,
  hasWhatsApp: Boolean,
  isCarOrBluetoothActive: Boolean,
  onEditClick: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (contact != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
          else MaterialTheme.colorScheme.outline,
      ),
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(22.dp))
        .clickable { onEditClick() }
        .testTag("pinned_contact_card"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Surface(
        shape = CircleShape,
        color =
          if (contact != null) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(50.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (contact != null) {
            Text(
              text = contact.initials,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          } else {
            Icon(
              imageVector = Icons.Filled.PersonAdd,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = if (contact != null) "TARGET CONTACT" else "NO CONTACT PINNED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          if (isCarOrBluetoothActive) {
            Icon(
              imageVector = Icons.Filled.DirectionsCar,
              contentDescription = "Car or Bluetooth context active",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(14.dp),
            )
            Icon(
              imageVector = Icons.Filled.BluetoothConnected,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(14.dp),
            )
          }
        }
        Text(
          text = contact?.displayName ?: "Choose WhatsApp Contact",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            if (contact != null) {
              PhoneNumberUtils.formatForDisplay(contact.phoneNumber) +
                if (hasWhatsApp) " · Direct WhatsApp" else " · Link mode"
            } else {
              "Tap to select contact"
            },
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      IconButton(
        onClick = onEditClick,
        modifier = Modifier.size(48.dp).testTag("edit_contact_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Edit,
          contentDescription = "Change pinned contact",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}
