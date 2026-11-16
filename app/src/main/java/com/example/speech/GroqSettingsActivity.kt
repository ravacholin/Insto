package com.example.speech

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
    setContent {
      PingAssistTheme {
        var value by remember { mutableStateOf("") }
        var configured by remember { mutableStateOf(store.read() != null) }
        var message by remember { mutableStateOf<String?>(null) }
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Interpretar voz con Groq", style = MaterialTheme.typography.headlineSmall)
            Text("Se envía una copia de cada grabación a Groq para transcribirla en español y decidir si es un pedido del teléfono o para Instinct. Si es para Instinct, se manda el audio original por WhatsApp.")
            Text("Requiere internet. El plan gratuito tiene cuotas; consultá los límites de tu cuenta. Tu clave se guarda cifrada solo en este teléfono.")
            Text(if (configured) "Hay una clave guardada. Podés reemplazarla o borrarla." else "Cargá tu clave personal una sola vez.")
            OutlinedTextField(value, { value = it }, label = { Text("Clave de Groq") }, singleLine = true,
              visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
              runCatching { store.save(value) }.onSuccess { value = ""; finish() }
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
