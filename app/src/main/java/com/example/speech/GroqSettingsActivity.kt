package com.example.speech

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.AssistantPreferencesRepository
import com.example.data.LearnedPhraseStore
import com.example.ui.screens.LearnedPhrasesContent
import com.example.ui.components.SettingsDescription
import com.example.ui.components.SettingsSection
import com.example.ui.components.SettingsSwitch
import com.example.ui.theme.PingAssistTheme
import java.io.IOException
import com.example.phone.DecisionException
import com.example.phone.GeminiDecisionsClient
import com.example.phone.GeminiDiagnostics
import com.example.phone.GeminiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GroqSettingsActivity : ComponentActivity() {
  companion object {
    const val EXTRA_PROVIDER = "com.example.speech.SETUP_PROVIDER"
    const val PROVIDER_GROQ = "groq"
    const val PROVIDER_GEMINI = "gemini"
    const val SECTION_PHRASES = "phrases"
  }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val requestedProvider = intent.getStringExtra(EXTRA_PROVIDER)
      ?.takeIf { it == PROVIDER_GROQ || it == PROVIDER_GEMINI || it == SECTION_PHRASES }
    val store = GroqKeyStore(this)
    val preferences = AssistantPreferencesRepository(this)
    val gemini = GeminiSettings(this)
    val learned = LearnedPhraseStore(this)
    setContent {
      PingAssistTheme {
        var section by rememberSaveable { mutableStateOf(requestedProvider) }
        val snackbars = remember { SnackbarHostState() }
        var value by remember { mutableStateOf("") }
        var configured by remember { mutableStateOf(store.read() != null) }
        var message by remember { mutableStateOf<String?>(null) }
        var geminiValue by remember { mutableStateOf("") }
        var geminiConfigured by remember { mutableStateOf(gemini.readKey() != null) }
        var hybridEnabled by remember { mutableStateOf(gemini.isEnabled()) }
        val scope = rememberCoroutineScope()
        val assistantPrefs by preferences.preferencesFlow.collectAsState(initial = null)
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
        val back = {
          if (requestedProvider != null || section == null) finish()
          else {
            probeJob?.cancel()
            probing = false
            value = ""
            geminiValue = ""
            message = null
            section = null
          }
        }
        BackHandler(enabled = section != null && requestedProvider == null, onBack = back)
        LaunchedEffect(message) {
          message?.let { snackbars.showSnackbar(it) }
          message = null
        }

        val title = when (section) {
          PROVIDER_GROQ -> "Transcripción"
          PROVIDER_GEMINI -> "Interpretación"
          SECTION_PHRASES -> "Frases aprendidas"
          else -> "Voz e IA"
        }
        Scaffold(
          modifier = Modifier.imePadding(),
          containerColor = MaterialTheme.colorScheme.background,
          snackbarHost = { SnackbarHost(snackbars) },
          topBar = {
            TopAppBar(
              title = { Text(title) },
              colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
              navigationIcon = {
                IconButton(onClick = back) {
                  Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
              },
            )
          },
        ) { padding ->
          Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            key(section) {
              Column(
                Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
              ) {
                when (section) {
                  null -> {
                    SettingsSection("Transcripción con Groq") {
                      ConnectionStatus(if (configured) "Clave guardada" else "Sin clave", configured)
                      SettingsDescription("Convierte tus grabaciones en texto para interpretar pedidos de voz.")
                      OutlinedButton(onClick = { section = PROVIDER_GROQ }) { Text("Configurar Groq") }
                    }
                    SettingsSection("Interpretación con Gemini") {
                      ConnectionStatus(
                        label = when {
                          !geminiConfigured -> "Sin clave"
                          hybridEnabled -> "Activo"
                          else -> "En pausa"
                        },
                        active = geminiConfigured && hybridEnabled,
                      )
                      SettingsDescription("Ayuda a entender pedidos que los comandos locales no reconocen.")
                      OutlinedButton(onClick = { section = PROVIDER_GEMINI }) { Text("Configurar Gemini") }
                    }
                    SettingsSection("Frases aprendidas") {
                      SettingsDescription("Mirá, agregá y corregí las frases que Insto entiende como atajos de comandos.")
                      OutlinedButton(onClick = { section = SECTION_PHRASES }) { Text("Gestionar frases") }
                    }
                  }
                  PROVIDER_GEMINI -> SettingsSection("Gemini Decisions") {
                    SettingsDescription("Los comandos reconocidos se resuelven en el teléfono. Gemini interpreta pedidos nuevos y te pide confirmación antes de ejecutar propuestas generativas.")
                    SettingsDescription("Requiere internet. El texto se procesa según los términos de Google. PingAssist no comparte contactos, archivos ni notificaciones con Gemini.")
                    SettingsDescription(if (geminiConfigured) "Clave cifrada guardada en este teléfono." else "Agregá tu clave de Google AI Studio para activar Gemini.")
                    OutlinedTextField(geminiValue, { geminiValue = it }, label = { Text("Clave de Gemini") }, singleLine = true,
                      visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                      runCatching { gemini.saveKey(geminiValue) }.onSuccess {
                        geminiValue = ""
                        geminiConfigured = true
                        hybridEnabled = true
                        if (requestedProvider == PROVIDER_GEMINI) {
                          setResult(RESULT_OK)
                          finish()
                        } else {
                          message = "Gemini activado."
                        }
                      }.onFailure { message = "No se pudo guardar la clave de Gemini." }
                    }, enabled = geminiValue.isNotBlank()) { Text("Guardar Gemini y activar") }
                    if (geminiConfigured) {
                      SettingsSwitch("Interpretar pedidos con Gemini", hybridEnabled, onCheckedChange = { desired ->
                        probeJob?.cancel()
                        probing = false
                        runCatching { gemini.setEnabled(desired) }.onSuccess { hybridEnabled = desired }
                          .onFailure { message = "No se pudo cambiar la interpretación." }
                      })
                      OutlinedButton(enabled = hybridEnabled && !probing, onClick = {
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
                      SettingsDescription("Decisiones: ${diagnostics.decisions} · extracción: ${diagnostics.extractions} · respaldo: ${diagnostics.fallbacks}\nÚltima interpretación: ${diagnostics.lastDurationMillis} ms${diagnostics.lastFailure?.let { " · ${it.label}" } ?: ""}")
                      diagnostics.lastExtractionModel?.let {
                        SettingsDescription("Último modelo de extracción: $it")
                      }
                      TextButton(onClick = {
                        probeJob?.cancel(); probing = false
                        runCatching { gemini.clear() }.onSuccess { geminiConfigured = false; hybridEnabled = false; geminiValue = ""; message = "Clave de Gemini borrada." }
                          .onFailure { message = "No se pudo borrar la clave de Gemini." }
                      }) { Text("Borrar clave de Gemini", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))) } }) { Text("Obtener clave de Gemini") }
                    TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/docs/pricing#gemini-decision"))) } }) { Text("Cuotas, precios y uso de datos de Gemini") }
                  }
                  PROVIDER_GROQ -> {
                    SettingsSection("Clave de Groq") {
                      SettingsDescription("Groq recibe una copia de tu grabación para transcribirla. Si el pedido es para Instinct, el audio original se envía por WhatsApp. En algunos casos, Groq recibe texto para verificar una interpretación de Gemini.")
                      SettingsDescription("Requiere internet. El plan gratuito tiene cuotas. Tu clave se guarda cifrada en este teléfono.")
                      SettingsDescription(if (configured) "Clave guardada. Podés reemplazarla o borrarla." else "Agregá tu clave personal de Groq.")
                      OutlinedTextField(value, { value = it }, label = { Text("Clave de Groq") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                      Button(onClick = {
                        runCatching { store.save(value) }.onSuccess {
                          value = ""
                          configured = true
                          if (requestedProvider == PROVIDER_GROQ) {
                            setResult(RESULT_OK)
                            finish()
                          } else {
                            message = "Clave de Groq guardada."
                          }
                        }
                          .onFailure { message = "No se pudo guardar la clave. Revisá el valor e intentá otra vez." }
                      }, enabled = value.isNotBlank()) { Text(if (requestedProvider == PROVIDER_GROQ) "Guardar y volver" else "Guardar clave") }
                      if (configured) TextButton(onClick = {
                        runCatching { store.clear() }.onSuccess { configured = false; value = ""; message = "Clave borrada." }
                          .onFailure { message = "No se pudo borrar la clave." }
                      }) { Text("Borrar clave de Groq", color = MaterialTheme.colorScheme.error) }
                      TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) } }) { Text("Abrir Groq para obtener una clave") }
                    }
                    SettingsSection("Vocabulario de transcripción") {
                      SettingsSwitch(
                        "Enviar nombres y frases a Groq",
                        checked = assistantPrefs?.groqVocabularyEnabled == true,
                        enabled = assistantPrefs != null,
                        description = "Envía nombres de apps, rutinas, lugares y frases aprendidas junto con el audio.",
                        onCheckedChange = { enabled ->
                          scope.launch {
                            try { preferences.setGroqVocabularyEnabled(enabled) }
                            catch (_: IOException) { message = "No se pudo guardar el ajuste de vocabulario." }
                          }
                        },
                      )
                      HorizontalDivider()
                      SettingsSwitch(
                        "Incluir contactos fijados",
                        checked = assistantPrefs?.groqVocabularyEnabled == true &&
                          assistantPrefs?.groqPinnedContactHintsEnabled == true,
                        enabled = assistantPrefs?.groqVocabularyEnabled == true,
                        description = "Agrega los nombres del contacto fijado y del contacto para conducir.",
                        onCheckedChange = { enabled ->
                          scope.launch {
                            try { preferences.setGroqPinnedContactHintsEnabled(enabled) }
                            catch (_: IOException) { message = "No se pudo guardar el ajuste de contactos." }
                          }
                        },
                      )
                    }
                  }
                  SECTION_PHRASES -> LearnedPhrasesContent(learned, onMessage = { message = it })
                  else -> Unit
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ConnectionStatus(label: String, active: Boolean) {
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = MaterialTheme.shapes.small,
    color = if (active) colors.primaryContainer else colors.surfaceVariant,
    contentColor = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
  ) {
    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge)
  }
}
