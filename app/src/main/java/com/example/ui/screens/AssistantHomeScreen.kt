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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.data.FloatingPillMode
import com.example.data.OrbHoldBehavior
import com.example.data.PinnedContact
import com.example.data.VolumeKeyTrigger
import com.example.data.WhatsAppFlavor
import com.example.ui.PingAssistUiState
import com.example.ui.components.PingOrb
import com.example.ui.components.SettingsCategory
import com.example.ui.components.SettingsChoices
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSwitch
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
  onOpenProviderSettings: () -> Unit,
  onOpenLearnedPhrases: () -> Unit = {},
  onConfigurationImported: () -> Unit = {},
  onSelectOrbHoldBehavior: (OrbHoldBehavior) -> Unit,
  onSelectWhatsAppFlavor: (WhatsAppFlavor) -> Unit,
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
  var showSetupSheet by rememberSaveable { mutableStateOf(false) }

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
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = "Insto",
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
              Text("Ajustes")
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
      onOpenProviderSettings = onOpenProviderSettings,
      onOpenLearnedPhrases = onOpenLearnedPhrases,
      onConfigurationImported = onConfigurationImported,
      onSelectOrbHoldBehavior = onSelectOrbHoldBehavior,
      onSelectWhatsAppFlavor = onSelectWhatsAppFlavor,
      onSelectVolumeKeyTrigger = onSelectVolumeKeyTrigger,
      onSelectFloatingPillMode = onSelectFloatingPillMode,
      onToggleEyesFreeCues = onToggleEyesFreeCues,
      onTestAudioCue = onTestAudioCue,
      onToggleBoomerangReturn = onToggleBoomerangReturn,
      onToggleCarBtAutoVoice = onToggleCarBtAutoVoice,
      onToggleNightWindow = onToggleNightWindow,
      onEditDrivingContactClick = {
        showSetupSheet = false
        onEditDrivingContactClick()
      },
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
  onOpenProviderSettings: () -> Unit,
  onOpenLearnedPhrases: () -> Unit = {},
  onConfigurationImported: () -> Unit = {},
  onSelectOrbHoldBehavior: (OrbHoldBehavior) -> Unit,
  onSelectWhatsAppFlavor: (WhatsAppFlavor) -> Unit,
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

  var confirmClearLogs by rememberSaveable { mutableStateOf(false) }
  var expandedCategory by rememberSaveable { mutableStateOf<String?>(null) }
  val toggleCategory: (String) -> Unit = { category ->
    expandedCategory = if (expandedCategory == category) null else category
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.background,
  ) {
    Column(
      Modifier.align(Alignment.CenterHorizontally).widthIn(max = 620.dp).fillMaxWidth()
        .navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ajustes", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        IconButton(onClick = onDismiss) {
          Icon(Icons.Filled.Close, contentDescription = "Cerrar ajustes")
        }
      }

      SettingsCategory("Asistente y accesos rápidos", expandedCategory == "access", { toggleCategory("access") }) {
        Text("Asistente predeterminado", style = MaterialTheme.typography.titleMedium)
        SettingsDescription(
          if (system.isDefaultAssistant) "Insto está activo como asistente de Android."
          else "Elegí Insto en Android para abrir el panel flotante y hablar o escribir.",
          modifier = Modifier.testTag("floating_assistant_hint"),
        )
        OutlinedButton(onClick = onSetDefaultAssistantClick, modifier = Modifier.testTag("set_default_assistant_button")) {
          Text(if (system.isDefaultAssistant) "Cambiar asistente" else "Elegir asistente")
        }
        HorizontalDivider()
        Text("Servicio de accesibilidad", style = MaterialTheme.typography.titleMedium)
        SettingsDescription(
          if (system.isAutoSendEnabled) "Activo. Controla los atajos de volumen, el botón flotante y el envío automático."
          else "Activá «Insto · accesibilidad» en Android para usar los atajos de volumen, el botón flotante y el envío automático.",
        )
        OutlinedButton(onClick = onOpenAccessibilitySettings) { Text("Abrir Accesibilidad") }
        Text("Mantener presionado un botón de volumen", style = MaterialTheme.typography.titleMedium)
        SettingsChoices(VolumeKeyTrigger.entries, prefs.volumeKeyTrigger, onSelectVolumeKeyTrigger, label = {
          when (it) {
            VolumeKeyTrigger.DISABLED -> "Desactivado"
            VolumeKeyTrigger.VOLUME_DOWN_HOLD -> "Bajar volumen"
            VolumeKeyTrigger.VOLUME_UP_HOLD -> "Subir volumen"
          }
        })
        Text("Botón flotante", style = MaterialTheme.typography.titleMedium)
        SettingsChoices(FloatingPillMode.entries, prefs.floatingPillMode, onSelectFloatingPillMode, label = {
          when (it) {
            FloatingPillMode.OFF -> "Oculto"
            FloatingPillMode.CAR_OR_BLUETOOTH_ONLY -> "Solo en el auto o con Bluetooth"
            FloatingPillMode.ALWAYS_ON -> "Siempre visible"
          }
        })
      }

      SettingsCategory("WhatsApp y notas de voz", expandedCategory == "whatsapp", { toggleCategory("whatsapp") }) {
        if (!system.installedWhatsApp.hasAnyWhatsApp) {
          Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Text("WhatsApp no está instalado. Insto usará un enlace para abrir el chat.", Modifier.padding(12.dp))
          }
        }
        Text("Aplicación para abrir chats", style = MaterialTheme.typography.titleMedium)
        SettingsChoices(WhatsAppFlavor.entries, prefs.preferredFlavor, onSelectWhatsAppFlavor, label = {
          if (it == WhatsAppFlavor.AUTO) "Detectar automáticamente" else it.label
        })
        HorizontalDivider()
        AutoSendDiagnostics(prefs.preferredFlavor)
        HorizontalDivider()
        Text("Botón principal", style = MaterialTheme.typography.titleMedium)
        SettingsDescription("Tocá para abrir el chat. Mantené presionado para grabar y soltá para enviar.")
        SettingsChoices(
          OrbHoldBehavior.entries, prefs.orbHoldBehavior, onSelectOrbHoldBehavior,
          label = { if (it == OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO) "Grabar después de 2,5 s" else "Grabar después de 0,3 s" },
          itemModifier = { Modifier.testTag("orb_hold_${it.name.lowercase()}") },
        )
      }

      SettingsCategory("Propuestas de Instinct", expandedCategory == "proposals", { toggleCategory("proposals") }) {
        InstinctProposalSettingsSection(prefs.pinnedContact, prefs.preferredFlavor)
      }

      SettingsCategory("Callar alarma con la voz", expandedCategory == "alarmvoice", { toggleCategory("alarmvoice") }) {
        AlarmVoiceSettingsSection()
      }

      SettingsCategory("Conducción y horarios", expandedCategory == "driving", { toggleCategory("driving") }) {
        SettingsSwitch("Modo de voz en el auto o con Bluetooth", prefs.contextRule.switchToVoiceInCarOrBluetooth,
          onToggleCarBtAutoVoice, "Da prioridad a la grabación de voz cuando detecta el modo auto o audio Bluetooth.")
        HorizontalDivider()
        Text("Contacto al conducir", style = MaterialTheme.typography.titleMedium)
        SettingsDescription(prefs.contextRule.drivingContact?.displayName ?: "Se usa el contacto fijado.")
        OutlinedButton(onClick = onEditDrivingContactClick) {
          Text(if (prefs.contextRule.drivingContact == null) "Elegir contacto" else "Cambiar contacto")
        }
        if (prefs.contextRule.drivingContact != null) {
          TextButton(onClick = onClearDrivingContact) { Text("Usar contacto fijado") }
        }
        HorizontalDivider()
        SettingsSwitch("Volver a Maps o Waze", prefs.boomerangReturnEnabled, onToggleBoomerangReturn,
          "Vuelve a la navegación después de enviar una nota de voz. Requiere el servicio de Accesibilidad.")
        SettingsSwitch("Modo de voz nocturno", prefs.contextRule.nightWindowEnabled, onToggleNightWindow,
          "Da prioridad a la grabación de voz de 21:00 a 07:00.")
      }

      SettingsCategory("Sonido y vibración", expandedCategory == "feedback", { toggleCategory("feedback") }) {
        SettingsSwitch("Avisos al grabar y enviar", prefs.eyesFreeAudioCues, onToggleEyesFreeCues,
          "Reproduce señales distintas al empezar a grabar, al enviar y si ocurre un error.")
        OutlinedButton(onClick = onTestAudioCue, modifier = Modifier.testTag("test_audio_cue_button")) {
          Text("Probar aviso de grabación")
        }
      }

      SettingsCategory("Accesos directos", expandedCategory == "shortcuts", { toggleCategory("shortcuts") }) {
        AppShortcutSettings()
      }

      SettingsCategory("Frases aprendidas", expandedCategory == "phrases", { toggleCategory("phrases") }) {
        SettingsDescription("Atajos con tus palabras: elegí qué hace Insto cuando decís una frase. Podés agregarlas, editarlas y borrarlas.")
        OutlinedButton(onClick = onOpenLearnedPhrases) { Text("Editar frases") }
      }

      SettingsCategory("Reconocimiento e interpretación", expandedCategory == "providers", { toggleCategory("providers") }) {
        SettingsDescription("Configurá Groq para transcribir audio y Gemini para interpretar pedidos.")
        OutlinedButton(onClick = onOpenProviderSettings) { Text("Abrir ajustes de voz e IA") }
      }

      SettingsCategory("Copia de seguridad y restauración", expandedCategory == "backup", { toggleCategory("backup") }) {
        BackupRestoreSettings(onImported = onConfigurationImported)
      }

      if (prefs.recentLogs.isNotEmpty()) {
        SettingsCategory("Actividad reciente", expandedCategory == "activity", { toggleCategory("activity") }) {
          prefs.recentLogs.take(5).forEach { log ->
            val timeText = remember(log.timestampMillis) {
              SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(log.timestampMillis))
            }
            Column {
              Text("${log.statusLabel} (${log.contactName})", style = MaterialTheme.typography.bodyMedium)
              SettingsDescription(timeText)
            }
          }
          TextButton(onClick = { confirmClearLogs = true }) {
            Text("Borrar historial", color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }
  if (confirmClearLogs) {
    AlertDialog(
      onDismissRequest = { confirmClearLogs = false },
      title = { Text("¿Borrar la actividad reciente?") },
      dismissButton = { TextButton(onClick = { confirmClearLogs = false }) { Text("Cancelar") } },
      confirmButton = {
        TextButton(onClick = {
          confirmClearLogs = false
          onClearLogs()
        }) { Text("Borrar historial", color = MaterialTheme.colorScheme.error) }
      },
    )
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
