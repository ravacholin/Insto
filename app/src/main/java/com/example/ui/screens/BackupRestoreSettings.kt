package com.example.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.backup.AndroidBackup
import com.example.backup.BackupCategory
import com.example.backup.BackupCrypto
import com.example.backup.BackupException
import com.example.backup.BackupFormat
import com.example.backup.CategoryPreview
import com.example.backup.OpenResult
import com.example.backup.RestoreMode
import com.example.backup.RestoreOptions
import com.example.backup.RestorePlan
import com.example.backup.RestoreReport
import com.example.data.LearnedPhraseStore
import com.example.ui.components.SettingsDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuración → Copia de seguridad y restauración. Everything is local: the file goes where the user picks it
 * through the system's document picker; no storage permission, no network.
 */
@Composable
fun BackupRestoreSettings(onImported: () -> Unit = {}) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val phrases = remember { LearnedPhraseStore(context) }
  val manager = remember { AndroidBackup.manager(context, phrases) }
  var revision by remember { mutableStateOf(0) }
  var current by remember { mutableStateOf<Map<BackupCategory, String?>>(emptyMap()) }
  var interrupted by remember { mutableStateOf(manager.interruptedRestore()) }
  var message by remember { mutableStateOf<String?>(null) }
  var exportOpen by remember { mutableStateOf(false) }
  var pendingFile by remember { mutableStateOf<ByteArray?>(null) }
  var importBytes by remember { mutableStateOf<ByteArray?>(null) }
  var plan by remember { mutableStateOf<RestorePlan?>(null) }
  var report by remember { mutableStateOf<Pair<RestoreReport, List<String>>?>(null) }

  LaunchedEffect(revision) { current = withContext(Dispatchers.IO) { runCatching { manager.describeCurrent() }.getOrDefault(emptyMap()) } }

  val creator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
    val bytes = pendingFile
    pendingFile = null
    if (uri == null || bytes == null) { message = "Exportación cancelada. No se guardó ningún archivo."; return@rememberLauncherForActivityResult }
    scope.launch {
      message = withContext(Dispatchers.IO) {
        try {
          val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("sin flujo")
          stream.use { it.write(bytes); it.flush() }
          "Copia creada (${(bytes.size + 1023) / 1024} KB). Guardala junto con su contraseña en un lugar seguro."
        } catch (_: Exception) {
          val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
          "No se pudo escribir la copia en la ubicación elegida." +
            if (removed) " Se borró el archivo incompleto." else " Si quedó un archivo, borralo: está incompleto."
        }
      }
    }
  }
  val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult // Cancelled: nothing changes.
    scope.launch {
      val bytes = withContext(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16_384)
            while (true) {
              val read = input.read(chunk)
              if (read < 0) break
              buffer.write(chunk, 0, read)
              if (buffer.size() > BackupCrypto.MAX_FILE) break
            }
            buffer.toByteArray()
          }
        }.getOrNull()
      }
      when {
        bytes == null -> message = "No se pudo leer el archivo elegido."
        bytes.size > BackupCrypto.MAX_FILE -> message = "El archivo es demasiado grande para ser una copia de Insto."
        BackupFormat.detect(bytes) == BackupFormat.Kind.ENCRYPTED -> importBytes = bytes
        else -> when (val opened = withContext(Dispatchers.Default) { manager.open(bytes, null) }) {
          is OpenResult.Ready -> plan = opened.plan
          is OpenResult.Invalid -> message = opened.message
          OpenResult.NeedsPassword -> importBytes = bytes
        }
      }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("backup_settings")) {
    SettingsDescription("Guardá toda tu configuración en un archivo cifrado con una contraseña y restaurala en este u otro " +
      "teléfono. Incluye las claves de API, contactos, ajustes, frases aprendidas, rutinas y lugares. No incluye audios, " +
      "mensajes ni historial. Todo ocurre en el teléfono, sin servidores.")
    val available = current.filterValues { it != null }
    if (available.isNotEmpty()) SettingsDescription(available.entries.joinToString(" · ") { (category, text) -> "${category.label}: $text" })
    interrupted?.let { categories ->
      Text("La última restauración se interrumpió. Revisá: ${categories.joinToString()}. Podés volver a importar la copia.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = { manager.dismissInterruptedRestore(); interrupted = null }) { Text("Entendido") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { exportOpen = true }, modifier = Modifier.testTag("backup_export")) { Text("Exportar configuración") }
      OutlinedButton(onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("backup_import")) { Text("Importar configuración") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("backup_message")) }
  }

  if (exportOpen) ExportDialog(current,
    onDismiss = { exportOpen = false },
    onExport = { selection, password ->
      scope.launch {
        val result = withContext(Dispatchers.Default) {
          try { Result.success(manager.export(selection, password)) }
          catch (error: BackupException) { Result.failure(error) }
          catch (error: Exception) { Result.failure(IllegalStateException("No se pudo preparar la copia.")) }
          finally { password.fill('\u0000') }
        }
        result.onSuccess { bytes ->
          exportOpen = false
          pendingFile = bytes
          creator.launch(AndroidBackup.fileName(System.currentTimeMillis()))
        }.onFailure { message = it.message; exportOpen = false }
      }
    })

  importBytes?.let { bytes ->
    PasswordDialog(
      onDismiss = { importBytes = null },
      onOpen = { password, onError ->
        scope.launch {
          val opened = withContext(Dispatchers.Default) { try { manager.open(bytes, password) } finally { password.fill('\u0000') } }
          when (opened) {
            is OpenResult.Ready -> { importBytes = null; plan = opened.plan }
            is OpenResult.Invalid -> onError(opened.message)
            OpenResult.NeedsPassword -> onError("Escribí la contraseña de la copia.")
          }
        }
      })
  }

  plan?.let { ready ->
    RestoreDialog(ready, manager = manager,
      onDismiss = { plan = null },
      onRestore = { choices, options ->
        scope.launch {
          val result = withContext(Dispatchers.IO) { manager.restore(ready, choices, options) }
          val attention = if (result.success) withContext(Dispatchers.IO) {
            runCatching { AndroidBackup.pendingAttention(context, phrases) }.getOrDefault(emptyList())
          } else emptyList()
          plan = null
          report = result to attention
          interrupted = manager.interruptedRestore()
          revision++
          if (result.success) onImported()
        }
      })
  }

  report?.let { (result, permissions) ->
    AlertDialog(
      onDismissRequest = { report = null },
      title = { Text(if (result.success) "Configuración restaurada" else "No se restauró") },
      text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          result.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          result.restored.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
          val attention = result.attention + permissions
          if (attention.isNotEmpty()) {
            Text("Necesita atención", style = MaterialTheme.typography.titleSmall)
            attention.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
          }
          if (result.success) SettingsDescription("Los permisos y accesos de Android no se copian: hay que activarlos en cada teléfono.")
        }
      },
      confirmButton = { TextButton(onClick = { report = null }) { Text("Listo") } },
    )
  }
}

@Composable
private fun ExportDialog(current: Map<BackupCategory, String?>, onDismiss: () -> Unit, onExport: (Set<BackupCategory>, CharArray) -> Unit) {
  var selection by remember { mutableStateOf(BackupCategory.entries.filter { current[it] != null }.toSet().ifEmpty { BackupCategory.entries.toSet() }) }
  var password by remember { mutableStateOf("") }
  var confirm by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  val valid = password.length >= BackupCrypto.MIN_PASSWORD && password == confirm && selection.isNotEmpty()
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("Exportar configuración") },
    text = {
      Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (category in BackupCategory.entries) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = category in selection, onCheckedChange = { selection = if (it) selection + category else selection - category })
            Column {
              Text(category.label, style = MaterialTheme.typography.bodyMedium)
              Text(current[category] ?: "Sin datos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        if (BackupCategory.CREDENTIALS in selection)
          SettingsDescription("Las claves se incluyen cifradas dentro del archivo. Nunca se muestran.")
        OutlinedTextField(password, { password = it }, label = { Text("Contraseña (mínimo ${BackupCrypto.MIN_PASSWORD} caracteres)") },
          singleLine = true, visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("backup_password"))
        OutlinedTextField(confirm, { confirm = it }, label = { Text("Repetí la contraseña") }, singleLine = true,
          visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          isError = confirm.isNotEmpty() && confirm != password, modifier = Modifier.fillMaxWidth().testTag("backup_password_confirm"))
        Text("Si perdés la contraseña, no hay forma de recuperar esta copia. Insto no la guarda.",
          color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (busy) SettingsDescription("Cifrando…")
      }
    },
    confirmButton = {
      Column(horizontalAlignment = Alignment.End) {
        TextButton(enabled = valid && !busy, onClick = { busy = true; onExport(selection, password.toCharArray()) },
          modifier = Modifier.testTag("backup_create")) { Text("Crear copia de seguridad") }
        TextButton(enabled = valid && !busy && (selection - BackupCategory.CREDENTIALS).isNotEmpty(),
          onClick = { busy = true; onExport(selection - BackupCategory.CREDENTIALS, password.toCharArray()) }) {
          Text("Exportar sin claves")
        }
        SettingsDescription("«Exportar sin claves» no restaura Groq ni Gemini: habrá que volver a cargarlas.")
      }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
  )
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onOpen: (CharArray, (String) -> Unit) -> Unit) {
  var password by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("Contraseña de la copia") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(password, { password = it; error = null }, label = { Text("Contraseña") }, singleLine = true,
          visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth().testTag("backup_open_password"))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (busy) SettingsDescription("Verificando…")
        SettingsDescription("Todavía no se cambia nada: primero se revisa el archivo completo.")
      }
    },
    confirmButton = {
      TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
        busy = true
        onOpen(password.toCharArray()) { busy = false; error = it }
      }) { Text("Abrir") }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
  )
}

@Composable
private fun RestoreDialog(
  plan: RestorePlan,
  manager: com.example.backup.ConfigurationBackupManager,
  onDismiss: () -> Unit,
  onRestore: (Map<BackupCategory, RestoreMode>, RestoreOptions) -> Unit,
) {
  var choices by remember { mutableStateOf(plan.categories.associateWith { RestoreMode.MERGE }) }
  var options by remember { mutableStateOf(RestoreOptions()) }
  var preview by remember { mutableStateOf<Map<BackupCategory, CategoryPreview>>(emptyMap()) }
  var confirming by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  LaunchedEffect(choices, options) { preview = withContext(Dispatchers.IO) { manager.preview(plan, choices, options) } }
  val warnings = preview.values.flatMap { it.warnings }
  val blocked = preview.values.any { it.error != null }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(if (confirming) "¿Restaurar la configuración?" else "Importar configuración") },
    text = {
      Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (confirming) {
          if (warnings.isEmpty()) Text("No se sobrescribe nada de lo que ya tenés.")
          warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
          SettingsDescription("La restauración se aplica por partes. Si una falla, se vuelve a la configuración anterior.")
          return@Column
        }
        val origin = when {
          plan.legacy -> "Copia de frases de una versión anterior de Insto, sin claves."
          else -> "Creada ${plan.createdAt.take(10)}" + (plan.appVersionName?.let { " con Insto $it" } ?: "") +
            if (plan.includesCredentials) " · incluye claves" else " · sin claves"
        }
        SettingsDescription(origin)
        for (category in plan.categories) {
          val chosen = category in choices
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = chosen, onCheckedChange = {
              choices = if (it) choices + (category to RestoreMode.MERGE) else choices - category
            })
            Column {
              Text(category.label, style = MaterialTheme.typography.bodyMedium)
              Text(plan.summaries.getValue(category).description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          if (chosen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = choices[category] == RestoreMode.MERGE, onClick = { choices = choices + (category to RestoreMode.MERGE) },
                label = { Text("Fusionar") })
              FilterChip(selected = choices[category] == RestoreMode.REPLACE, onClick = { choices = choices + (category to RestoreMode.REPLACE) },
                label = { Text("Reemplazar") })
            }
            if (category == BackupCategory.CREDENTIALS && choices[category] == RestoreMode.MERGE) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(options.replaceGroqKey, { options = options.copy(replaceGroqKey = it) })
                Text("Reemplazar la clave de Groq actual si es distinta", style = MaterialTheme.typography.bodySmall)
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(options.replaceGeminiKey, { options = options.copy(replaceGeminiKey = it) })
                Text("Reemplazar la clave de Gemini actual si es distinta", style = MaterialTheme.typography.bodySmall)
              }
            }
            preview[category]?.let { p ->
              p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
              (p.changes + p.warnings + p.attention).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
          }
        }
        plan.unavailable.forEach { (name, reason) ->
          Text("$name: $reason", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        SettingsDescription("Fusionar conserva lo que ya tenés y agrega lo que falta. Reemplazar usa lo del respaldo solo en las " +
          "categorías elegidas. Las demás no se tocan. Restaurar no ejecuta ninguna acción ni envía mensajes.")
      }
    },
    confirmButton = {
      if (confirming) TextButton(enabled = !busy, onClick = { busy = true; onRestore(choices, options) },
        modifier = Modifier.testTag("backup_restore_confirm")) { Text(if (busy) "Restaurando…" else "Restaurar") }
      else TextButton(enabled = choices.isNotEmpty() && !blocked, onClick = { confirming = true },
        modifier = Modifier.testTag("backup_restore")) { Text("Continuar") }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = { if (confirming) confirming = false else onDismiss() }) { Text(if (confirming) "Volver" else "Cancelar") } },
  )
}
