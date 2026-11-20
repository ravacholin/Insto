package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.LearnedPhraseStore
import com.example.data.PhraseTeaching
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
    HowItWorks("Le enseñás vos", "Agregá una frase y la acción que tiene que hacer, escrita como un pedido que Insto ya entiende: «prendé la linterna», «abrí Spotify», «ejecutá la rutina Estudio».")
    HowItWorks("Con la voz", "«Cuando diga luz, prendé la linterna» · «Aprendé que modo cine es poné el volumen al 20 %»")
    HowItWorks("Para corregir", "«Corregí esta frase: abrí YouTube» cambia la acción de la última frase usada. «Olvidá esta frase» la borra.")
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
        onEdit = { editing = PhraseEditor(entry.phrase, entry.target.orEmpty(), entry) },
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
  val target: String = "",
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
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
 * The action field is optional when editing: left empty, the entry keeps its current action. The preview runs the
 * same checks the voice order uses, so what you see is what Insto will do.
 */
@Composable
private fun PhraseEditorDialog(
  editor: PhraseEditor,
  existing: List<String>,
  onDismiss: () -> Unit,
  onSave: (key: String, command: com.example.phone.PhoneCommand, target: String?) -> Unit,
) {
  var phrase by rememberSaveable { mutableStateOf(editor.phrase) }
  var target by rememberSaveable { mutableStateOf(editor.target) }
  var preview by remember { mutableStateOf<Preview>(Preview.Empty) }
  val keepsAction = editor.original != null && target.isBlank()

  LaunchedEffect(phrase, target) {
    preview = Preview.Checking
    delay(250)
    val command = if (keepsAction) editor.original!!.command else
      when (val resolved = PhraseTeaching.resolveTarget(target)) {
        is PhraseTeaching.Target.Invalid -> { preview = if (target.isBlank()) Preview.Empty else Preview.Error(resolved.message); return@LaunchedEffect }
        is PhraseTeaching.Target.Valid -> resolved.command
      }
    if (phrase.isBlank()) { preview = Preview.Action(PhoneCommandCodec.summary(command), null, null); return@LaunchedEffect }
    preview = when (val check = PhraseTeaching.checkPhrase(phrase, command, existing, editor.original?.phrase)) {
      is PhraseTeaching.Phrase.Invalid -> Preview.Error(check.message)
      is PhraseTeaching.Phrase.Valid -> Preview.Action(PhoneCommandCodec.summary(command), check, command)
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (editor.original == null) "Agregar frase" else "Editar frase") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(phrase, { phrase = it }, label = { Text("Cuando diga") }, placeholder = { Text("luz") },
          singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(target, { target = it }, label = { Text("Insto hace") },
          placeholder = { Text(if (editor.original != null) "Dejalo vacío para mantener la acción" else "prendé la linterna") },
          singleLine = true, modifier = Modifier.fillMaxWidth())
        when (val state = preview) {
          Preview.Empty -> SettingsDescription("Escribí la acción como se la pedirías a Insto.")
          Preview.Checking -> SettingsDescription("Revisando…")
          is Preview.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
          is Preview.Action -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Va a hacer: ${state.summary}", style = MaterialTheme.typography.bodyMedium)
            if (state.check?.replaces == true) SettingsDescription("Ya existe esa frase. Se reemplaza su acción.")
          }
        }
      }
    },
    confirmButton = {
      val ready = preview as? Preview.Action
      TextButton(
        enabled = ready?.check != null && ready.command != null,
        onClick = { ready?.let { onSave(it.check!!.key, it.command!!, if (keepsAction) editor.original?.target else target) } },
      ) { Text("Guardar") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}

private sealed interface Preview {
  data object Empty : Preview
  data object Checking : Preview
  data class Error(val message: String) : Preview
  data class Action(val summary: String, val check: PhraseTeaching.Phrase.Valid?, val command: com.example.phone.PhoneCommand?) : Preview
}
