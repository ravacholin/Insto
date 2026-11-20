package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseActions
import com.example.data.PhraseTeaching
import com.example.phone.PhoneCommand
import com.example.phone.PhoneCommandCodec
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSection
import com.example.ui.components.SettingsSwitch
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IA → Frases aprendidas: what Insto learned, what you taught it, and how to change both. */
@Composable
fun LearnedPhrasesContent(store: LearnedPhraseStore, onMessage: (String) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by store.uiSettings.collectAsState(initial = null)
  var editing by remember { mutableStateOf<PhraseEditor?>(null) }
  var confirmDeleteAll by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }

  val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      try {
        val json = store.export()
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        onMessage("Copia guardada.")
      } catch (_: Exception) { onMessage("No pude guardar la copia.") }
    }
  }
  val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      onMessage(try {
        val text = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
            // One character over the limit is enough for the store to reject the file.
            val out = StringBuilder()
            val chars = CharArray(8192)
            while (out.length <= 262_144) {
              val read = reader.read(chars)
              if (read < 0) break
              out.append(chars, 0, read)
            }
            out.toString()
          }
        }
        val count = store.import(text)
        if (count == 1) "Importé una frase." else "Importé $count frases."
      } catch (error: IllegalArgumentException) {
        (error.message ?: "El archivo no es válido.") + " Se conservaron tus frases."
      } catch (_: Exception) { "No pude leer el archivo. Se conservaron tus frases." })
    }
  }

  SettingsSection("Aprendizaje") {
    SettingsSwitch(
      "Aprender y usar frases",
      checked = settings?.enabled == true,
      enabled = settings != null,
      description = "Guarda en este teléfono las frases que Insto aprende y las que le enseñás.",
      onCheckedChange = { enabled ->
        scope.launch {
          try { store.setEnabled(enabled) } catch (_: IOException) { onMessage("No pude cambiar el aprendizaje.") }
        }
      },
    )
    if (settings?.enabled == false) SettingsDescription("Apagado: las frases se conservan, pero no se usan ni se aprenden nuevas.")
  }

  SettingsSection("Cómo funciona") {
    HowItWorks("Aprende solo", "Cuando Insto necesita el modelo para entender un pedido simple, lo confirmás y sale bien, guarda esa frase. La próxima vez la resuelve en el teléfono, sin red.")
    HowItWorks("Le enseñás vos", "Tocá «Agregar frase», escribí lo que vas a decir y elegí la acción de la lista: linterna, volumen, una app, una rutina, un temporizador y más.")
    HowItWorks("Para editar", "Tocá cualquier frase de la lista para cambiar el texto o la acción. Las que aprendió sola también se pueden editar.")
    HowItWorks("También con la voz", "«Cuando diga luz, prendé la linterna» · «Corregí esta frase: abrí YouTube» · «Olvidá esta frase»")
    SettingsDescription("Los comandos que Insto ya reconoce tienen prioridad. Las llamadas, los mensajes, los archivos y los cambios de calendario no se asocian a frases. Cada acción pasa por los permisos y confirmaciones de siempre.")
  }

  SettingsSection("Tus frases") {
    val entries = settings?.entries.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = { editing = PhraseEditor() }, enabled = settings != null) { Text("Agregar frase") }
      if (entries.isNotEmpty()) {
        Text(
          "${entries.size} de ${LearnedPhraseStore.LIMIT}",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (settings != null && entries.isEmpty()) SettingsDescription("Todavía no hay frases. Agregá una o usá Insto y va a aprender las que confirmes.")
    if (entries.size > 6) {
      OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    val needle = com.example.phone.PhoneCommandParser.normalize(query)
    val visible = entries.filter { entry ->
      needle.isEmpty() || needle in entry.phrase ||
        needle in com.example.phone.PhoneCommandParser.normalize(PhoneCommandCodec.summary(entry.command))
    }
    if (entries.isNotEmpty() && visible.isEmpty()) SettingsDescription("Ninguna frase coincide con la búsqueda.")
    for (entry in visible) {
      HorizontalDivider()
      PhraseRow(
        entry,
        onEdit = { editing = PhraseEditor(entry.phrase, entry) },
        onDelete = {
          scope.launch {
            try { store.delete(entry.phrase); onMessage("Borré «${entry.phrase}».") }
            catch (_: IOException) { onMessage("No pude borrar la frase.") }
          }
        },
      )
    }
  }

  SettingsSection("Copia de seguridad") {
    SettingsDescription("Las frases no se incluyen en la copia de Android. Guardalas en un archivo para pasarlas a otro teléfono. Al importar, las frases del archivo reemplazan a las iguales.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { exporter.launch("insto-frases.json") }, enabled = !settings?.entries.isNullOrEmpty()) { Text("Exportar") }
      OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }, enabled = settings != null) { Text("Importar") }
    }
    if (!settings?.entries.isNullOrEmpty()) {
      TextButton(onClick = { confirmDeleteAll = true }) { Text("Borrar todas", color = MaterialTheme.colorScheme.error) }
    }
  }

  if (confirmDeleteAll) AlertDialog(
    onDismissRequest = { confirmDeleteAll = false },
    title = { Text("¿Borrar todas las frases?") },
    text = { Text("Se borran las aprendidas y las que agregaste. No se puede deshacer.") },
    confirmButton = {
      TextButton(onClick = {
        confirmDeleteAll = false
        scope.launch { try { store.clear() } catch (_: IOException) { onMessage("No pude borrar las frases.") } }
      }) { Text("Borrar todas", color = MaterialTheme.colorScheme.error) }
    },
    dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancelar") } },
  )

  editing?.let { editor ->
    PhraseEditorDialog(
      editor = editor,
      existing = settings?.entries.orEmpty().map { it.phrase },
      onDismiss = { editing = null },
      onSave = { key, command, target ->
        scope.launch {
          val saved = try { store.teach(key, command, target, replacing = editor.original?.phrase) } catch (_: IOException) { false }
          if (saved) {
            editing = null
            onMessage(if (editor.original == null) "Frase agregada." else "Frase actualizada.")
          } else onMessage("No pude guardar la frase.")
        }
      },
    )
  }
}

private data class PhraseEditor(
  val phrase: String = "",
  val original: LearnedPhraseStore.Entry? = null,
)

@Composable
private fun HowItWorks(title: String, body: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    SettingsDescription(body)
  }
}

@Composable
private fun PhraseRow(entry: LearnedPhraseStore.Entry, onEdit: () -> Unit, onDelete: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Editar", onClick = onEdit).padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("«${entry.phrase}»", style = MaterialTheme.typography.bodyLarge)
      Text(PhoneCommandCodec.summary(entry.command), style = MaterialTheme.typography.bodyMedium)
      val origin = if (entry.source == LearnedPhraseStore.Source.MANUAL) "La agregaste vos" else "Aprendida sola"
      val uses = when (entry.uses) { 0L -> "sin usar"; 1L -> "1 uso"; else -> "${entry.uses} usos" }
      val date = if (entry.date > 0) " · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.date)) else ""
      Text("$origin · $uses$date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = onEdit) { Text("Editar") }
    TextButton(onClick = onDelete) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
  }
}

/**
 * Phrase and action are edited by tapping: the action comes from [PhraseActions], with installed apps and
 * configured routines to choose from. Writing a request stays available as an advanced option. The preview runs
 * the same checks as the voice order, so what you see is what Insto will do.
 */
@Composable
private fun PhraseEditorDialog(
  editor: PhraseEditor,
  existing: List<String>,
  onDismiss: () -> Unit,
  onSave: (key: String, command: PhoneCommand, target: String?) -> Unit,
) {
  var phrase by rememberSaveable { mutableStateOf(editor.phrase) }
  var command by remember { mutableStateOf(editor.original?.command) }
  var target by remember { mutableStateOf(editor.original?.target) }
  var picking by remember { mutableStateOf(false) }
  var check by remember { mutableStateOf<PhraseTeaching.Phrase?>(null) }

  LaunchedEffect(phrase, command) {
    check = null
    val chosen = command ?: return@LaunchedEffect
    if (phrase.isBlank()) return@LaunchedEffect
    delay(250)
    check = PhraseTeaching.checkPhrase(phrase, chosen, existing, editor.original?.phrase)
  }

  if (picking) {
    ActionPickerDialog(
      current = command,
      onDismiss = { picking = false },
      onPick = { picked, written -> command = picked; target = written; picking = false },
    )
    return
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (editor.original == null) "Agregar frase" else "Editar frase") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(phrase, { phrase = it }, label = { Text("Cuando diga") }, placeholder = { Text("luz") },
          singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Insto hace", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
          Text(command?.let(PhoneCommandCodec::summary) ?: "Elegir acción")
        }
        if (command != null) TextButton(onClick = { picking = true }) { Text("Cambiar acción") }
        when (val state = check) {
          is PhraseTeaching.Phrase.Invalid -> Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
          is PhraseTeaching.Phrase.Valid -> if (state.replaces) SettingsDescription("Ya existe esa frase. Se reemplaza su acción.")
          null -> if (phrase.isNotBlank() && command != null) SettingsDescription("Revisando…")
        }
      }
    },
    confirmButton = {
      val valid = check as? PhraseTeaching.Phrase.Valid
      TextButton(enabled = valid != null && command != null, onClick = { onSave(valid!!.key, command!!, target) }) { Text("Guardar") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}

/** Step one lists the actions by group. Step two asks for the value an action needs. */
@Composable
private fun ActionPickerDialog(current: PhoneCommand?, onDismiss: () -> Unit, onPick: (PhoneCommand, String?) -> Unit) {
  var selected by remember { mutableStateOf(current?.let(PhraseActions::optionFor)?.takeIf { it.input != PhraseActions.Input.NONE }) }
  var writing by remember { mutableStateOf(false) }
  var value by remember {
    mutableStateOf(when (current) {
      is PhoneCommand.Volume -> current.percent.takeIf { current.operation == com.example.phone.VolumeOp.SET }?.toString().orEmpty()
      is PhoneCommand.Timer -> (current.seconds / 60).toString()
      is PhoneCommand.SpotifySearch -> current.query
      is PhoneCommand.YouTubeSearch -> current.query
      is PhoneCommand.Navigate -> current.destination
      is PhoneCommand.Platform -> current.argument.orEmpty()
      else -> ""
    })
  }
  val option = selected
  val title = when {
    writing -> "Escribir un pedido"
    option != null -> option.label
    else -> "Elegir acción"
  }
  val written = remember(value, writing) { if (writing && value.isNotBlank()) PhraseTeaching.resolveTarget(value) else null }
  val ready: Pair<PhoneCommand, String?>? = when {
    writing -> (written as? PhraseTeaching.Target.Valid)?.let { it.command to value.trim() }
    option != null && option.input != PhraseActions.Input.APP && option.input != PhraseActions.Input.ROUTINE ->
      option.build(value)?.let { it to null }
    else -> null
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
          writing -> WrittenRequest(value, { value = it }, written)
          option == null -> {
            for ((group, options) in PhraseActions.groups) {
              Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp))
              for (item in options) {
                val chosen = current != null && item.input == PhraseActions.Input.NONE && item.build() == current
                Text(
                  (if (chosen) "✓ " else "") + item.label,
                  style = MaterialTheme.typography.bodyLarge,
                  modifier = Modifier.fillMaxWidth().clickable {
                    if (item.input == PhraseActions.Input.NONE) item.build()?.let { onPick(it, null) }
                    else { value = ""; selected = item }
                  }.padding(vertical = 10.dp),
                )
              }
            }
            HorizontalDivider()
            Text("Escribir un pedido (avanzado)", style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.fillMaxWidth().clickable { value = ""; writing = true }.padding(vertical = 10.dp))
          }
          option.input == PhraseActions.Input.APP -> AppChooser { label, pkg -> onPick(PhraseActions.openApp(label, pkg), null) }
          option.input == PhraseActions.Input.ROUTINE -> RoutineChooser(value, { value = it }) { name ->
            option.build(name)?.let { onPick(it, null) }
          }
          else -> {
            val numeric = option.input == PhraseActions.Input.PERCENT || option.input == PhraseActions.Input.MINUTES
            OutlinedTextField(
              value, { value = it },
              label = { Text(when (option.input) {
                PhraseActions.Input.PERCENT -> "Porcentaje (0 a 100)"
                PhraseActions.Input.MINUTES -> "Minutos"
                PhraseActions.Input.PLACE -> "Lugar o dirección"
                else -> "Qué buscar"
              }) },
              singleLine = true,
              keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
              modifier = Modifier.fillMaxWidth(),
            )
            ready?.let { SettingsDescription("Va a hacer: ${PhoneCommandCodec.summary(it.first)}") }
          }
        }
      }
    },
    confirmButton = {
      val needsConfirm = writing || option != null && option.input !in setOf(PhraseActions.Input.APP, PhraseActions.Input.ROUTINE)
      if (needsConfirm) TextButton(enabled = ready != null, onClick = { ready?.let { onPick(it.first, it.second) } }) { Text("Listo") }
    },
    dismissButton = {
      if (writing || option != null) TextButton(onClick = { writing = false; selected = null }) { Text("Volver") }
      else TextButton(onClick = onDismiss) { Text("Cancelar") }
    },
  )
}

@Composable
private fun WrittenRequest(value: String, onValue: (String) -> Unit, resolved: PhraseTeaching.Target?) {
  OutlinedTextField(value, onValue, label = { Text("Pedido") }, placeholder = { Text("prendé la linterna") },
    singleLine = true, modifier = Modifier.fillMaxWidth())
  when (resolved) {
    null -> SettingsDescription("Escribilo como se lo pedirías a Insto.")
    is PhraseTeaching.Target.Invalid -> Text(resolved.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    is PhraseTeaching.Target.Valid -> SettingsDescription("Va a hacer: ${resolved.summary}")
  }
}

@Composable
private fun AppChooser(onPick: (String, String) -> Unit) {
  val context = LocalContext.current
  var apps by remember { mutableStateOf<List<com.example.phone.AppOption>?>(null) }
  var query by remember { mutableStateOf("") }
  LaunchedEffect(Unit) {
    apps = withContext(Dispatchers.IO) {
      runCatching { com.example.phone.launcherApps(context).filter { it.packageName != context.packageName }.sortedBy { it.label.lowercase() } }
        .getOrDefault(emptyList())
    }
  }
  OutlinedTextField(query, { query = it }, label = { Text("Buscar app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
  val list = apps
  when {
    list == null -> SettingsDescription("Cargando apps…")
    list.isEmpty() -> SettingsDescription("No encontré apps para abrir.")
    else -> {
      val needle = com.example.phone.PhoneCommandParser.normalize(query)
      val visible = list.filter { needle.isEmpty() || needle in com.example.phone.PhoneCommandParser.normalize(it.label) }
      if (visible.isEmpty()) SettingsDescription("Ninguna app coincide.")
      for (app in visible.take(80)) {
        Text(app.label, style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.fillMaxWidth().clickable { onPick(app.label, app.packageName) }.padding(vertical = 10.dp))
      }
    }
  }
}

@Composable
private fun RoutineChooser(value: String, onValue: (String) -> Unit, onPick: (String) -> Unit) {
  val context = LocalContext.current
  var routines by remember { mutableStateOf<List<String>?>(null) }
  LaunchedEffect(Unit) {
    routines = withContext(Dispatchers.IO) {
      runCatching {
        com.example.phone.ConfiguredRoutineStore(java.io.File(context.noBackupFilesDir, "configured-routines.json")).read().map { it.name }
      }.getOrDefault(emptyList())
    }
  }
  val list = routines
  when {
    list == null -> SettingsDescription("Cargando rutinas…")
    list.isEmpty() -> {
      SettingsDescription("Todavía no hay rutinas configuradas. Podés importarlas pidiéndole a Insto «importá rutinas», o escribir el nombre de una que vas a importar.")
      OutlinedTextField(value, onValue, label = { Text("Nombre de la rutina") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      TextButton(enabled = value.isNotBlank(), onClick = { onPick(value) }) { Text("Usar este nombre") }
    }
    else -> for (name in list) {
      Text((if (com.example.phone.PhoneCommandParser.normalize(name) == com.example.phone.PhoneCommandParser.normalize(value)) "✓ " else "") + name,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable { onPick(name) }.padding(vertical = 10.dp))
    }
  }
}
