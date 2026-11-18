package com.example.speech

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PingAssistTheme
import com.example.phone.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GroqSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val store = GroqKeyStore(this)
    val gemini = com.example.phone.GeminiSettings(this)
    val learned = com.example.data.LearnedPhraseStore(this)
    setContent {
      PingAssistTheme {
        var value by remember { mutableStateOf("") }
        var configured by remember { mutableStateOf(store.read() != null) }
        var message by remember { mutableStateOf<String?>(null) }
        var geminiValue by remember { mutableStateOf("") }
        var geminiConfigured by remember { mutableStateOf(gemini.readKey() != null) }
        var hybridEnabled by remember { mutableStateOf(gemini.isEnabled()) }
        val scope = rememberCoroutineScope()
        var probeJob by remember { mutableStateOf<Job?>(null) }
        var probing by remember { mutableStateOf(false) }
        var diagnostics by remember { mutableStateOf(GeminiDiagnostics.snapshot()) }
        DisposableEffect(lifecycle) {
          val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { probeJob?.cancel(); probing = false }
          }
          lifecycle.addObserver(observer)
          onDispose { probeJob?.cancel(); lifecycle.removeObserver(observer) }
        }
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Gemini Decisions · acciones locales", style = MaterialTheme.typography.headlineSmall)
            Text("Los comandos reconocidos se resuelven en el teléfono sin esperar a Gemini. Decisions ayuda con pedidos que el modo local no entiende; las propuestas generativas se revisan antes de ejecutar. Las consultas a Instinct siguen disponibles si Gemini tarda, falla o no decide con confianza. Los pedidos con datos pendientes conservan su contexto. No se comparten contactos, archivos ni notificaciones con Gemini.")
            Text("La interpretación con Gemini requiere una clave activa e internet. Sin Gemini se mantiene el modo local para pedidos reconocidos. El nivel gratuito tiene cuotas y puede usar el texto según los términos de Google. El respaldo generativo siempre requiere confirmación.")
            Text(if (geminiConfigured) "Hay una clave de Gemini guardada y cifrada en este teléfono." else "Cargá tu clave personal de Google AI Studio para activar Decisions.")
            OutlinedTextField(geminiValue, { geminiValue = it }, label = { Text("Clave de Gemini") }, singleLine = true,
              visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
              runCatching { gemini.saveKey(geminiValue) }.onSuccess {
                geminiValue = ""; geminiConfigured = true; hybridEnabled = true; message = "Decisions activado."; setResult(RESULT_OK); finish()
              }.onFailure { message = "No se pudo guardar la clave de Gemini." }
            }, enabled = geminiValue.isNotBlank()) { Text("Guardar Gemini y activar") }
            if (geminiConfigured) {
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = hybridEnabled, onCheckedChange = { desired ->
                  probeJob?.cancel(); probing = false
                  runCatching { gemini.setEnabled(desired) }.onSuccess { hybridEnabled = desired }
                    .onFailure { message = "No se pudo cambiar la clasificación." }
                })
                Text("Clasificar pedidos con Gemini Decisions")
              }
              Button(enabled = hybridEnabled && !probing, onClick = {
                probing = true
                probeJob = scope.launch {
                  try {
                    // Probe the provider itself: an offline routing fallback is not a successful connection.
                    GeminiDecisionsClient(gemini::readKey).classify("ayudame a estudiar geografía")
                    message = "Decisions respondió correctamente. No se ejecutó ni envió ninguna acción."
                  } catch (_: TimeoutCancellationException) {
                    message = "Gemini tardó demasiado. Los comandos locales y el envío a Instinct siguen disponibles."
                  } catch (failure: DecisionException) {
                    message = failure.message ?: "No se pudo conectar con Gemini."
                  } catch (cancelled: CancellationException) { throw cancelled
                  } finally { probing = false; diagnostics = GeminiDiagnostics.snapshot() }
                }
              }) { Text(if (probing) "Probando Gemini…" else "Probar conexión de Gemini") }
              Text("Decisions: ${diagnostics.decisions} · extracción: ${diagnostics.extractions} · respaldo: ${diagnostics.fallbacks}\nÚltima interpretación: ${diagnostics.lastDurationMillis} ms${diagnostics.lastFailure?.let { " · ${it.label}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
              diagnostics.lastExtractionModel?.let {
                Text("Último modelo de extracción: $it", style = MaterialTheme.typography.bodySmall)
              }
              TextButton(onClick = {
                probeJob?.cancel(); probing = false
                runCatching { gemini.clear() }.onSuccess { geminiConfigured = false; hybridEnabled = false; geminiValue = ""; message = "Clave de Gemini borrada." }
                  .onFailure { message = "No se pudo borrar la clave de Gemini." }
              }) { Text("Borrar clave de Gemini") }
            }
            TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))) } }) { Text("Obtener clave de Gemini") }
            TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/docs/pricing#gemini-decision"))) } }) { Text("Cuotas, precios y uso de datos de Gemini") }
            HorizontalDivider()
            Text("Interpretar voz con Groq", style = MaterialTheme.typography.headlineSmall)
            Text("Se envía una copia de cada grabación a Groq para transcribirla en español y decidir si es un pedido del teléfono o para Instinct. Si es para Instinct, se manda el audio original por WhatsApp. En casos excepcionales, un modelo rápido de Groq recibe solo el texto del pedido para verificar un intent de Gemini Decisions.")
            Text("Requiere internet. El plan gratuito tiene cuotas; consultá los límites de tu cuenta. Tu clave se guarda cifrada solo en este teléfono.")
            Text(if (configured) "Hay una clave guardada. Podés reemplazarla o borrarla." else "Cargá tu clave personal una sola vez.")
            OutlinedTextField(value, { value = it }, label = { Text("Clave de Groq") }, singleLine = true,
              visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
              runCatching { store.save(value) }.onSuccess { value = ""; setResult(RESULT_OK); finish() }
                .onFailure { message = "No se pudo guardar la clave. Revisá el valor e intentá otra vez." }
            }, enabled = value.isNotBlank()) { Text("Guardar y volver") }
            if (configured) TextButton(onClick = {
              runCatching { store.clear() }.onSuccess { configured = false; value = ""; message = "Clave borrada." }
                .onFailure { message = "No se pudo borrar la clave." }
            }) { Text("Borrar clave") }
            TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) } }) { Text("Abrir Groq para obtener una clave") }
            HorizontalDivider()
            val phrases by learned.uiSettings.collectAsState(initial = com.example.data.LearnedPhraseStore.Settings())
            Text("Frases aprendidas", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Switch(checked = phrases.enabled, onCheckedChange = { enabled ->
                scope.launch {
                  try { learned.setEnabled(enabled) }
                  catch (_: java.io.IOException) { message = "No pude cambiar el aprendizaje." }
                }
              })
              Text("Aprender y usar frases en este teléfono")
            }
            if (phrases.entries.isEmpty()) Text("Todavía no hay frases aprendidas.")
            else {
              var confirmDeleteAll by remember { mutableStateOf(false) }
              TextButton(onClick = { confirmDeleteAll = true }) { Text("Borrar todas") }
              if (confirmDeleteAll) AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                title = { Text("¿Borrar todas las frases?") },
                confirmButton = {
                  TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                      try { learned.clear() } catch (_: java.io.IOException) { message = "No pude borrar las frases." }
                    }
                  }) { Text("Borrar todas") }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancelar") } },
              )
              for (entry in phrases.entries) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  Column(Modifier.weight(1f)) {
                    Text(entry.phrase)
                    Text(PhoneCommandCodec.summary(entry.command), style = MaterialTheme.typography.bodySmall)
                  }
                  TextButton(onClick = {
                    scope.launch {
                      try { learned.delete(entry.phrase) }
                      catch (_: java.io.IOException) { message = "No pude borrar la frase." }
                    }
                  }) { Text("Borrar") }
                }
              }
            }
            message?.let { Text(it) }
            TextButton(onClick = { finish() }) { Text("Volver") }
          }
        }
      }
    }
  }
}
