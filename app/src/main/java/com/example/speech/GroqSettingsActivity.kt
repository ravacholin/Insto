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
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            HorizontalDivider()
            Text("Gemini Decisions · híbrido", style = MaterialTheme.typography.headlineSmall)
            Text("Los pedidos claros se resuelven localmente. Para pedidos no comprendidos, se envía a Google el texto escrito o transcrito. Gemini ayuda a elegir una acción o Instinct; el audio original sigue destinado a Instinct cuando corresponde. No se comparte tu libreta de contactos con Gemini.")
            Text("Es opcional y requiere internet. El nivel gratuito tiene cuotas y puede usar el contenido para mejorar productos de Google según sus términos. Si falla, se conserva el pedido para reintentar.")
            Text(if (geminiConfigured) "Hay una clave de Gemini guardada y cifrada en este teléfono." else "Cargá tu clave personal de Google AI Studio para activar el híbrido.")
            OutlinedTextField(geminiValue, { geminiValue = it }, label = { Text("Clave de Gemini") }, singleLine = true,
              visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
              runCatching { gemini.saveKey(geminiValue) }.onSuccess {
                geminiValue = ""; geminiConfigured = true; hybridEnabled = true; message = "Híbrido activado."; setResult(RESULT_OK)
              }.onFailure { message = "No se pudo guardar la clave de Gemini." }
            }, enabled = geminiValue.isNotBlank()) { Text("Guardar Gemini y activar") }
            if (geminiConfigured) {
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = hybridEnabled, onCheckedChange = { desired ->
                  runCatching { gemini.setEnabled(desired) }.onSuccess { hybridEnabled = desired }
                    .onFailure { message = "No se pudo cambiar el modo híbrido." }
                })
                Text("Usar Gemini para pedidos no comprendidos")
              }
              TextButton(onClick = {
                runCatching { gemini.clear() }.onSuccess { geminiConfigured = false; hybridEnabled = false; geminiValue = ""; message = "Clave de Gemini borrada." }
                  .onFailure { message = "No se pudo borrar la clave de Gemini." }
              }) { Text("Borrar clave de Gemini") }
            }
            TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))) } }) { Text("Obtener clave de Gemini") }
            TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/docs/pricing#gemini-decision"))) } }) { Text("Cuotas, precios y uso de datos de Gemini") }
            message?.let { Text(it) }
            TextButton(onClick = { finish() }) { Text("Volver") }
          }
        }
      }
    }
  }
}
