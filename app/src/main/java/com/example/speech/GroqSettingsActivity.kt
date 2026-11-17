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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GroqSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val store = GroqKeyStore(this)
    val gemini = com.example.phone.GeminiSettings(this)
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
            Text("Gemini Decisions · híbrido", style = MaterialTheme.typography.headlineSmall)
            Text("Los pedidos claros se resuelven localmente. Para pedidos no comprendidos, se envía a Google el texto escrito o transcrito. Gemini ayuda a elegir una acción o Instinct y puede proponer parámetros estructurados que se revisan antes de ejecutar; el audio original sigue destinado a Instinct cuando corresponde. No se comparte tu libreta de contactos con Gemini.")
            Text("Es opcional y requiere internet. El nivel gratuito tiene cuotas y puede usar el contenido para mejorar productos de Google según sus términos. Si falla, se conserva el pedido para reintentar.")
            Text(if (geminiConfigured) "Hay una clave de Gemini guardada y cifrada en este teléfono." else "Cargá tu clave personal de Google AI Studio para activar el híbrido.")
            OutlinedTextField(geminiValue, { geminiValue = it }, label = { Text("Clave de Gemini") }, singleLine = true,
              visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
              runCatching { gemini.saveKey(geminiValue) }.onSuccess {
                geminiValue = ""; geminiConfigured = true; hybridEnabled = true; message = "Híbrido activado."; setResult(RESULT_OK); finish()
              }.onFailure { message = "No se pudo guardar la clave de Gemini." }
            }, enabled = geminiValue.isNotBlank()) { Text("Guardar Gemini y activar") }
            if (geminiConfigured) {
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = hybridEnabled, onCheckedChange = { desired ->
                  probeJob?.cancel(); probing = false
                  runCatching { gemini.setEnabled(desired) }.onSuccess { hybridEnabled = desired }
                    .onFailure { message = "No se pudo cambiar el modo híbrido." }
                })
                Text("Usar Gemini para pedidos no comprendidos")
              }
              Button(enabled = hybridEnabled && !probing, onClick = {
                probing = true
                probeJob = scope.launch {
                  try {
                    val coordinator = GeminiCoordinator(HybridPhoneRouter(gemini::isEnabled, GeminiDecisionsClient(gemini::readKey)), StructuredCommandClient(gemini::readKey))
                    // Fixed synthetic text; interpretation only, never dispatched to an executor.
                    val result = coordinator.interpret(InterpretationRequest("ayudame a estudiar geografía"), PhoneRoute.Instinct)
                    message = when (result) {
                      is InterpretationResult.Failure -> result.message
                      is InterpretationResult.Rejected -> "Gemini respondió, pero rechazó la frase de prueba."
                      is InterpretationResult.Query -> if (result.needsConfirmation) "Conexión disponible mediante Gemini de respaldo. No se envió ninguna consulta." else "Decisions respondió correctamente. No se ejecutó ninguna acción."
                      else -> "Gemini respondió. La prueba no ejecuta ni envía acciones."
                    }
                  } catch (cancelled: CancellationException) { throw cancelled
                  } finally { probing = false; diagnostics = GeminiDiagnostics.snapshot() }
                }
              }) { Text(if (probing) "Probando Gemini…" else "Probar conexión de Gemini") }
              Text("Decisions: ${diagnostics.decisions} · extracción: ${diagnostics.extractions} · respaldo: ${diagnostics.fallbacks}\nÚltima interpretación: ${diagnostics.lastDurationMillis} ms${diagnostics.lastFailure?.let { " · ${it.label}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
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
            Text("Se envía una copia de cada grabación a Groq para transcribirla en español y decidir si es un pedido del teléfono o para Instinct. Si es para Instinct, se manda el audio original por WhatsApp.")
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
            message?.let { Text(it) }
            TextButton(onClick = { finish() }) { Text("Volver") }
          }
        }
      }
    }
  }
}
