package com.example.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.alarmvoice.AlarmVoiceCoordinator
import com.example.alarmvoice.AlarmVoiceDiagnostics
import com.example.alarmvoice.AlarmVoiceLog
import com.example.alarmvoice.AlarmVoiceModel
import com.example.alarmvoice.AlarmVoiceSettings
import com.example.alarmvoice.KeywordMatcher
import com.example.assist.AssistantRoleHelper
import com.example.phone.NotificationAccessService
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ALARM_VOICE_EXPLANATION =
  "Cuando suena una alarma o termina un temporizador del Reloj, Insto abre una pantalla sobre el bloqueo y escucha " +
    "unos segundos. Si decís una de tus palabras, apaga la alarma con su botón «Detener» (nunca «Posponer»). " +
    "El reconocimiento es 100 % en el teléfono: no se graba ni se envía audio a ningún lado. De las notificaciones " +
    "del Reloj solo se leen el paquete, la categoría y los títulos de los botones; el contenido de otras " +
    "notificaciones no se lee."

/** Configuración → «Callar alarma con la voz». Off by default; needs the downloaded model to turn on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmVoiceSettingsSection() {
  val context = LocalContext.current
  val settings = remember { AlarmVoiceSettings(context) }
  val model = remember { AlarmVoiceModel(context) }
  val scope = rememberCoroutineScope()
  var revision by remember { mutableIntStateOf(0) }
  LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose { } }
  val enabled = remember(revision) { settings.isEnabled() }
  val installed = remember(revision) { model.isInstalled() }
  var progress by remember { mutableStateOf<Float?>(null) }
  var downloadError by remember { mutableStateOf<String?>(null) }
  var download by remember { mutableStateOf<Job?>(null) }
  var askDownload by remember { mutableStateOf(false) }
  val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
  val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
  val assistantRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }

  fun turnOn() {
    if (!settings.setEnabled(true)) { askDownload = true; return }
    revision++
    if (!hasMic(context)) mic.launch(Manifest.permission.RECORD_AUDIO)
    else if (Build.VERSION.SDK_INT >= 33 && !AlarmVoiceCoordinator.canPostNotifications(context))
      notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  fun startDownload(enableAfter: Boolean) {
    downloadError = null
    progress = 0f
    download = scope.launch {
      try {
        withContext(Dispatchers.IO) { model.download { p -> progress = p } }
        progress = null
        revision++
        if (enableAfter) turnOn()
      } catch (e: CancellationException) {
        progress = null; throw e
      } catch (e: Throwable) {
        progress = null
        downloadError = e.message ?: "No se pudo descargar el modelo (${e.javaClass.simpleName})."
      }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("alarm_voice_settings")) {
    SettingsDescription(ALARM_VOICE_EXPLANATION)
    SettingsSwitch("Callar alarma con la voz", enabled, { value ->
      if (value) { if (installed) turnOn() else askDownload = true } else { settings.setEnabled(false); revision++ }
    }, if (installed) "Apagada por defecto. Con el switch apagado Insto no mira las notificaciones del Reloj."
    else "Necesita descargar el modelo de voz en español (unos 40 MB) antes de activarse.")

    progress?.let { p ->
      Text("Descargando el modelo: ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
      LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
      TextButton(onClick = { download?.cancel(); progress = null }) { Text("Cancelar descarga") }
    }
    downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

    HorizontalDivider()
    Requirements(revision, installed, progress != null, onDownload = { startDownload(enableAfter = false) },
      onMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
      onNotifications = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
      onAssistant = { runCatching { assistantRole.launch(AssistantRoleHelper.buildSetDefaultAssistantIntent(context)) } },
      onChanged = { revision++ })

    HorizontalDivider()
    KeywordsEditor(settings, revision) { revision++ }

    Text("Ventana de escucha", style = MaterialTheme.typography.bodyLarge)
    SettingsDescription("Cuánto escucha como máximo. Al vencer deja de escuchar y la alarma sigue sonando.")
    val window = remember(revision) { settings.windowSeconds() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AlarmVoiceSettings.WINDOW_CHOICES.forEach { s ->
        FilterChip(selected = window == s, onClick = { settings.setWindowSeconds(s); revision++ }, label = { Text("$s s") })
      }
    }

    val duck = remember(revision) { settings.duckEnabled() }
    SettingsSwitch("Bajar el volumen de la alarma mientras escucho", duck, { settings.setDuckEnabled(it); revision++ },
      "Así el micrófono te oye mejor. El volumen original se restaura siempre al terminar.")
    if (duck) {
      val percent = remember(revision) { settings.duckPercent() }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AlarmVoiceSettings.DUCK_CHOICES.forEach { p ->
          FilterChip(selected = percent == p, onClick = { settings.setDuckPercent(p); revision++ }, label = { Text("$p %") })
        }
      }
    }

    OutlinedButton(enabled = installed && progress == null, onClick = {
      if (!hasMic(context)) mic.launch(Manifest.permission.RECORD_AUDIO)
      else context.startActivity(AlarmVoiceCoordinator.activityIntent(context, -1L, AlarmVoiceCoordinator.STRATEGY_TEST, test = true))
    }, modifier = Modifier.testTag("alarm_voice_test")) { Text("Probar el reconocimiento") }

    HorizontalDivider()
    Diagnostics(settings, revision) { revision++ }
  }

  if (askDownload) {
    AlertDialog(
      onDismissRequest = { askDownload = false },
      title = { Text("Descargar el modelo de voz") },
      text = {
        Text("Para reconocer tus palabras sin conexión Insto necesita el modelo chico de español de Vosk " +
          "(unos 40 MB), desde alphacephei.com. Se verifica su integridad y queda solo en este teléfono. " +
          "Conviene usar Wi-Fi.")
      },
      dismissButton = { TextButton(onClick = { askDownload = false }) { Text("Cancelar") } },
      confirmButton = {
        TextButton(onClick = { askDownload = false; startDownload(enableAfter = true) },
          modifier = Modifier.testTag("alarm_voice_download")) { Text("Descargar y activar") }
      },
    )
  }
}

private fun diagnosticTime(at: Long): String = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(at))

private fun hasMic(context: Context) =
  context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun listenerEnabled(context: Context): Boolean =
  Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    .contains(ComponentName(context, NotificationAccessService::class.java).flattenToString())

private fun batteryUnrestricted(context: Context): Boolean =
  context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
private fun Requirement(ok: Boolean, label: String, why: String, actions: @Composable () -> Unit = {}) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text((if (ok) "✓ " else "✗ ") + label, style = MaterialTheme.typography.bodyLarge,
      color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    if (!ok) { SettingsDescription(why); actions() }
  }
}

@Composable
private fun Requirements(
  revision: Int,
  installed: Boolean,
  downloading: Boolean,
  onDownload: () -> Unit,
  onMic: () -> Unit,
  onNotifications: () -> Unit,
  onAssistant: () -> Unit,
  onChanged: () -> Unit,
) {
  val context = LocalContext.current
  val listener = remember(revision) { listenerEnabled(context) }
  val mic = remember(revision) { hasMic(context) }
  val post = remember(revision) { AlarmVoiceCoordinator.canPostNotifications(context) }
  val assistant = remember(revision) { AssistantRoleHelper.isDefaultAssistant(context) }
  val fullScreen = remember(revision) { AlarmVoiceCoordinator.canUseFullScreenIntent(context) }
  val battery = remember(revision) { batteryUnrestricted(context) }
  val model = remember { AlarmVoiceModel(context) }
  val open: (Intent) -> Unit = { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
  val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

  Text("Requisitos", style = MaterialTheme.typography.titleMedium)
  Requirement(listener, "Acceso a notificaciones", "Habilitá «Insto · notificaciones» para que Insto vea cuándo suena el Reloj.") {
    OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Abrir acceso a notificaciones") }
  }
  Requirement(mic, "Micrófono", "Para escuchar tu palabra mientras suena la alarma.") {
    OutlinedButton(onClick = onMic) { Text("Permitir micrófono") }
  }
  Requirement(installed, "Modelo de voz descargado", "Unos 40 MB; el reconocimiento funciona sin conexión.") {
    OutlinedButton(enabled = !downloading, onClick = onDownload) { Text("Descargar modelo") }
  }
  if (installed) {
    val size = remember(revision) { model.sizeOnDisk() / (1024 * 1024) }
    SettingsDescription("Modelo ${AlarmVoiceModel.MODEL_NAME} · $size MB en el teléfono.")
  }
  Requirement(assistant || fullScreen, "Abrir la pantalla sobre el bloqueo",
    "Insto necesita ser el asistente digital predeterminado o tener el permiso de pantalla completa. Sin ninguno, " +
      "solo podrá mostrarte una notificación con el botón «Escuchar».") {
    OutlinedButton(onClick = onAssistant) { Text("Elegir Insto como asistente") }
    if (Build.VERSION.SDK_INT >= 34) OutlinedButton(onClick = {
      open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null)))
    }) { Text("Permitir pantalla completa") }
  }
  if (assistant || fullScreen) SettingsDescription(
    "Insto es asistente: ${if (assistant) "sí" else "no"} · Pantalla completa: ${if (fullScreen) "permitida" else "no"}.")
  Requirement(post, "Notificaciones de Insto", "Para el aviso de pantalla completa y el botón «Escuchar».") {
    OutlinedButton(onClick = onNotifications) { Text("Permitir notificaciones") }
  }
  Requirement(battery, "Batería «Sin restricciones»",
    "Motorola cierra con facilidad los servicios en segundo plano. En Info de la app → Batería elegí «Sin restricciones».") {
    OutlinedButton(onClick = { open(appDetails) }) { Text("Abrir info de la app") }
    TextButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Lista de optimización de batería") }
  }
  if (installed) TextButton(onClick = {
    AlarmVoiceSettings(context).setEnabled(false); model.delete(); onChanged()
  }) { Text("Borrar el modelo (desactiva la función)", color = MaterialTheme.colorScheme.error) }
  TextButton(onClick = onChanged) { Text("Revisar de nuevo") }
}

@Composable
private fun KeywordsEditor(settings: AlarmVoiceSettings, revision: Int, onChanged: () -> Unit) {
  val current = remember(revision) { settings.keywords() }
  var text by remember(revision) { mutableStateOf(current.joinToString(", ")) }
  val parsed = KeywordMatcher.parse(text)
  Text("Palabras para apagar", style = MaterialTheme.typography.bodyLarge)
  SettingsDescription("Separadas por comas, una palabra cada una (máximo ${KeywordMatcher.MAX_KEYWORDS}). Tiene que " +
    "reconocerse la palabra completa: «basta» no se activa con «bastante». Se comparan sin tildes.")
  OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("alarm_voice_keywords"), singleLine = true,
    label = { Text("Palabras") })
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(enabled = parsed.isNotEmpty() && parsed != current, onClick = { settings.setKeywords(parsed); onChanged() }) {
      Text("Guardar")
    }
    TextButton(onClick = { settings.setKeywords(KeywordMatcher.DEFAULT_KEYWORDS); onChanged() }) { Text("Restablecer") }
  }
  if (parsed.isEmpty()) Text("Escribí al menos una palabra.", color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Diagnostics(settings: AlarmVoiceSettings, revision: Int, onChanged: () -> Unit) {
  val context = LocalContext.current
  val diagnostics = remember(revision) { settings.diagnostics() }
  val last = remember(revision) { AlarmVoiceDiagnostics.last(context) }
  val log = remember(revision) { AlarmVoiceLog.lines(context).takeLast(15).reversed() }
  Text("Diagnóstico", style = MaterialTheme.typography.titleMedium)
  SettingsSwitch("Modo diagnóstico", diagnostics, { settings.setDiagnostics(it); onChanged() },
    "Además de los relojes conocidos, registra apps con nombre de reloj que todavía no están en la lista. " +
      "Solo paquete, categoría y botones, nunca el texto.")
  Text("Última notificación de reloj", style = MaterialTheme.typography.bodyLarge)
  if (last == null) SettingsDescription("Todavía ninguna. Con la función activada, poné una alarma o un temporizador de prueba.")
  else {
    val time = diagnosticTime(last.at)
    SettingsDescription("Paquete: ${last.packageName}\nCategoría: ${last.category ?: "(ninguna)"}\n" +
      "Canal: ${last.channelId ?: "(ninguno)"}\nBotones: ${last.actionTitles.joinToString { "«$it»" }.ifEmpty { "(ninguno)" }}\n" +
      "Pantalla completa: ${if (last.fullScreen) "sí" else "no"} · Insistente: ${if (last.insistent) "sí" else "no"}\n" +
      "Hora: $time\nResultado: ${last.verdict}")
    TextButton(onClick = { AlarmVoiceDiagnostics.clear(context); onChanged() }) { Text("Borrar") }
  }
  Text("Registro local", style = MaterialTheme.typography.bodyLarge)
  if (log.isEmpty()) SettingsDescription("Sin eventos. El registro queda solo en el teléfono.")
  else {
    log.forEach { SettingsDescription(it) }
    TextButton(onClick = { AlarmVoiceLog.clear(context); onChanged() }) { Text("Borrar registro") }
  }
  TextButton(onClick = onChanged) { Text("Actualizar") }
}
