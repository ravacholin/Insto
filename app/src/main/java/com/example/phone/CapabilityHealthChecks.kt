package com.example.phone

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.speech.SpeechRecognizer
import com.example.alarmvoice.AlarmVoiceModel
import com.example.alarmvoice.AlarmVoiceSettings
import com.example.audio.FloatingVoiceCapture
import com.example.speech.GroqKeyStore
import com.example.speech.StoredKey
import kotlinx.coroutines.runInterruptible

/** Providers contribute bounded probes using their own contracts and adapters. */
internal fun interface CapabilityHealthContributor {
  fun checks(): List<HealthCheck>
}

internal class AndroidHealthChecks(private val app: Context) : CapabilityHealthContributor {
  override fun checks(): List<HealthCheck> = buildList {
    fun add(id: String, label: String, probe: () -> HealthObservation) {
      add(HealthCheck(id, label) { probe() })
    }
    fun readiness(id: String, capability: InstinctReadiness.Capability) {
      add(id, capability.label) {
        val granted = InstinctReadiness.granted(app, capability)
        if (capability == InstinctReadiness.Capability.BATTERY && !granted) status(HealthCode.LIMITED, authorized = false)
        else permission(granted)
      }
    }
    fun permission(id: String, label: String, vararg permissions: String) {
      add(id, label) { permission(permissions.all(::granted)) }
    }
    add("assistant.role", "Asistente predeterminado") {
      configured(com.example.assist.AssistantRoleHelper.isDefaultAssistant(app))
    }
    add("assistant.activation", "Botones y accesos de activación") { status(HealthCode.DECLARATION) }
    permission("microphone.permission", "Permiso de micrófono", Manifest.permission.RECORD_AUDIO)
    add("microphone", "Acceso efectivo al micrófono") {
      when {
        !granted(Manifest.permission.RECORD_AUDIO) -> permission(false)
        !app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) -> status(HealthCode.UNSUPPORTED)
        else -> permission(true)
      }
    }
    add(HealthCheck("microphone.test", "Capturar señal del micrófono", effect = HealthEffect.TEMPORARY_STATE, verifies = "microphone") {
      if (!granted(Manifest.permission.RECORD_AUDIO)) permission(false)
      else {
        try {
          val input = FloatingVoiceCapture.openMicrophone()
          try {
            val samples = ShortArray(320)
            var signal = false
            repeat(30) {
              if (!signal) {
                val length = runInterruptible { input.read(samples, 0, samples.size) }
                signal = length > 0 && (0 until length).any { samples[it] != 0.toShort() }
              }
            }
            status(if (signal) HealthCode.VERIFIED else HealthCode.MICROPHONE, evidence = HealthEvidence.ACTIVE_TEST)
          } finally {
            try { input.stop() } finally { input.release() }
          }
        } catch (error: kotlinx.coroutines.CancellationException) {
          throw error
        } catch (_: Exception) {
          status(HealthCode.MICROPHONE, evidence = HealthEvidence.ACTIVE_TEST)
        }
      }
    })
    readiness("notifications.media", InstinctReadiness.Capability.NOTIFICATION_LISTENER)
    val listener = ComponentName(app, NotificationAccessService::class.java)
    fun listenerGranted() = if (Build.VERSION.SDK_INT >= 27) {
      app.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(listener) == true
    } else Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").orEmpty()
      .split(':').any { ComponentName.unflattenFromString(it) == listener }
    add(HealthCheck("notifications.listener", "Lectura de notificaciones", recovery = {
      check(listenerGranted())
      NotificationListenerService.requestRebind(listener)
      kotlinx.coroutines.withTimeoutOrNull(2_000L) {
        while (NotificationAccessService.connected == null) kotlinx.coroutines.delay(100)
      }
    }) {
      when {
        !listenerGranted() -> permission(false)
        NotificationAccessService.connected != null -> status(HealthCode.VERIFIED, authorized = true)
        else -> status(HealthCode.PROVIDER_DOWN, authorized = true)
      }
    })
    readiness("notifications.post", InstinctReadiness.Capability.NOTIFICATIONS)
    readiness("system.battery", InstinctReadiness.Capability.BATTERY)
    readiness("system.exact_alarms", InstinctReadiness.Capability.EXACT_ALARMS)
    readiness("system.accessibility", InstinctReadiness.Capability.ACCESSIBILITY)
    add("system.overlay", "Superposición en pantalla") { permission(Settings.canDrawOverlays(app)) }
    add("temporary_mode", "Modos temporales") { status(HealthCode.DECLARATION) }
    add("system.foreground", "Servicios en primer plano") { permission(granted(Manifest.permission.FOREGROUND_SERVICE)) }
    add("system.locked", "Ejecución con pantalla bloqueada") {
      status(if (app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
        HealthCode.LIMITED else HealthCode.PREFLIGHT)
    }
    add("notifications.fullscreen", "Notificaciones de pantalla completa") {
      permission(if (Build.VERSION.SDK_INT >= 34) app.getSystemService(NotificationManager::class.java)
        ?.canUseFullScreenIntent() == true else granted(Manifest.permission.USE_FULL_SCREEN_INTENT))
    }
    permission("contacts", "Lectura de contactos", Manifest.permission.READ_CONTACTS)
    permission("calendar.read", "Lectura de calendario", Manifest.permission.READ_CALENDAR)
    permission("calendar.write", "Escritura de calendario", Manifest.permission.WRITE_CALENDAR)
    add("open_app", "Apertura de aplicaciones") {
      val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      status(if (app.packageManager.queryIntentActivities(launcher, 0).isNotEmpty()) HealthCode.PREFLIGHT else HealthCode.LIMITED)
    }
    add(ShortcutResolver.CAPABILITY, "Accesos directos de Android") {
      when (AndroidShortcutEngine.of(app).state.value.access) {
        ShortcutAccess.GRANTED -> status(HealthCode.PREFLIGHT, authorized = true)
        ShortcutAccess.DENIED -> permission(false)
        ShortcutAccess.LOCKED -> status(HealthCode.UNLOCK)
        ShortcutAccess.ERROR -> status(HealthCode.SYSTEM)
        ShortcutAccess.CHECKING -> if (AndroidShortcutEngine.of(app).hasPermission()) permission(true) else permission(false)
      }
    }
    add("voice.recognizer", "Reconocimiento de Android") {
      status(if (SpeechRecognizer.isRecognitionAvailable(app)) HealthCode.PREFLIGHT else HealthCode.UNSUPPORTED)
    }
    add("voice.vosk_diagnostics", "Reconocimiento local para diagnóstico") { configured(modelInstalled()) }
    add("voice.tts", "Síntesis de voz") {
      val services = app.packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
      status(if (services.isEmpty()) HealthCode.UNSUPPORTED else HealthCode.PREFLIGHT)
    }
    add("voice.playback", "Reproducción de audio") {
      status(if (app.getSystemService(AudioManager::class.java) != null) HealthCode.PREFLIGHT else HealthCode.UNSUPPORTED)
    }
    val model = AlarmVoiceModel(app)
    add("vosk.installed", "Modelo Vosk instalado") { configured(model.isInstalled()) }
    add("vosk.files", "Archivos del modelo Vosk") {
      status(if (model.hasRequiredFiles()) HealthCode.PREFLIGHT else HealthCode.MODEL_FILES)
    }
    add("vosk.load", "Carga de Vosk") {
      status(if (model.hasRequiredFiles()) HealthCode.PREFLIGHT else HealthCode.MODEL_FILES)
    }
    add(HealthCheck("vosk.test", "Probar carga de Vosk", timeoutMillis = 12_000L, effect = HealthEffect.RESOURCE, verifies = "vosk.load") {
      if (!model.hasRequiredFiles()) status(HealthCode.MODEL_FILES)
      else runInterruptible {
        status(try { org.vosk.Model(model.directory.absolutePath).let { it.close(); HealthCode.VERIFIED } }
        catch (_: Exception) { HealthCode.MODEL_LOAD }, evidence = HealthEvidence.ACTIVE_TEST)
      }
    })
    LocalIntentRegistry.default.definitions.filter { it.unavailable != null }.forEach {
      val id = it.id
      add(id, it.description) { status(HealthCode.UNSUPPORTED) }
    }
    val alarm = AlarmVoiceSettings(app)
    add("alarm.listening", "Escucha durante alarmas") {
      when {
        !alarm.isEnabled() -> configured(false)
        !granted(Manifest.permission.RECORD_AUDIO) -> permission(false)
        !model.hasRequiredFiles() -> status(HealthCode.MODEL_FILES)
        else -> configured(true)
      }
    }
    add("alarm.stop_voice", "Detener alarmas con la voz") {
      status(if (alarm.isEnabled()) HealthCode.DECLARATION else HealthCode.CONFIG, configured = alarm.isEnabled())
    }
  }

  private fun modelInstalled() = AlarmVoiceModel(app).hasRequiredFiles()

  private fun granted(name: String) = app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
}

internal class AiHealthChecks(private val app: Context) : CapabilityHealthContributor {
  override fun checks() = listOf(
    HealthCheck("ai.gemini", "Gemini Decisions") {
      val settings = GeminiSettings(app)
      if (settings.enabledFlag()) credentials(settings.keyStatus()) else configured(false)
    },
    HealthCheck("ai.groq", "Groq para interpretación") { credentials(GroqKeyStore(app).read().status) },
    HealthCheck("ai.transcription", "Transcripción remota") { credentials(GroqKeyStore(app).read().status) },
    HealthCheck("ai.llm", "Interpretación con LLM") {
      val settings = GeminiSettings(app)
      if (settings.enabledFlag()) credentials(settings.keyStatus()) else configured(false)
    },
    HealthCheck("ai.breaker", "Circuito de Gemini") { status(HealthCode.DECLARATION) },
    HealthCheck("ai.fallback", "Fallback remoto") { credentials(GroqKeyStore(app).read().status) },
    HealthCheck("ai.learned_phrases", "Frases aprendidas") { configured(com.example.data.LearnedPhraseStore(app).healthEnabled()) },
    HealthCheck("ai.learned_patterns", "Patrones aprendidos") { configured(com.example.data.LearnedPhraseStore(app).healthEnabled()) },
    HealthCheck("ai.router", "Router local", ttlMillis = 60 * 60_000L) {
      val local = PhoneCommandParser().parse("poné el volumen al treinta")
      status(if (local == PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, 30))) HealthCode.VERIFIED
      else HealthCode.INTERPRETATION, evidence = HealthEvidence.ACTIVE_TEST)
    },
  )

  private fun credentials(key: StoredKey.Status) = when (key) {
    StoredKey.Status.MISSING -> status(HealthCode.CREDENTIAL_MISSING, configured = false)
    StoredKey.Status.UNREADABLE -> status(HealthCode.CREDENTIAL_UNREADABLE, configured = false)
    StoredKey.Status.PRESENT -> status(if (app.internetValidated()) HealthCode.PREFLIGHT else HealthCode.OFFLINE, configured = true)
  }
}

internal class InstinctHealthChecks(private val app: Context) : CapabilityHealthContributor {
  override fun checks(): List<HealthCheck> = buildList {
    add(HealthCheck("instinct.channel", "Comunicación con Instinct") {
      configured(InstinctProposalSettings(app).isEnabled())
    })
    add(HealthCheck("instinct.mailbox", "Buzón firmado") { configured(InstinctMailboxSettings(app).blocker() == null) })
    add(HealthCheck("instinct.signature", "Validación de firmas") {
      status(if (InstinctMailboxSettings(app).blocker() == null) HealthCode.PREFLIGHT else HealthCode.CONFIG)
    })
    add(HealthCheck("instinct.poll", "Entrega por sondeo") {
      if (InstinctMailboxSettings(app).blocker() != null) configured(false)
      else if (InstinctMailbox.status(app)?.failure != null)
        status(instinctMailboxHealth(InstinctMailbox.status(app)?.failure), evidence = HealthEvidence.TRANSPORT)
      else configured(true)
    })
    add(HealthCheck("instinct.poll_job", "Programación de la sincronización", recovery = { InstinctMailbox.rearm(app) }) {
      when {
        InstinctMailboxSettings(app).blocker() != null -> configured(false)
        InstinctMailbox.scheduled(app) -> status(HealthCode.VERIFIED)
        else -> status(HealthCode.JOB_MISSING)
      }
    })
    add(HealthCheck("instinct.push", "Entrega por push") {
      configured(InstinctDoorbell.active(app))
    })
    add(HealthCheck("instinct.relay", "Relé de Instinct") {
      configured(InstinctDoorbell.settings(app).state().relayConfirmed)
    })
    add(HealthCheck("instinct.journal", "Diario de ejecución") {
      InstinctExecutions.journal(app).recent(1)
      status(HealthCode.VERIFIED, evidence = HealthEvidence.JOURNAL)
    })
    add(HealthCheck("instinct.execution", "Ejecución local de Instinct") { status(HealthCode.DECLARATION) })
    add(HealthCheck("instinct.plans", "Planes autónomos") {
      if (!InstinctProposalSettings(app).isEnabled()) configured(false)
      else {
        InstinctExecutions.existingJournal(app)?.plans(active = true, limit = 1)
        configured(true)
      }
    })
    add(HealthCheck("instinct.workflows", "Workflows") {
      WorkflowStore(app).read()
      status(HealthCode.PREFLIGHT)
    })
    add(HealthCheck("instinct.routines", "Rutinas programadas") {
      configured(InstinctRoutineGrants(app).access().enabledNames().isNotEmpty())
    })
    add(HealthCheck("instinct.catalog", "Publicación del catálogo", recovery = {
      val result = runInterruptible { InstinctCatalogPublisher.publish(app) }
      check(result is InstinctCatalogPublisher.Outcome.Published || result == InstinctCatalogPublisher.Outcome.Unchanged)
    }) {
      if (!InstinctCatalogPublisher.active(app)) configured(false)
      else if (InstinctCatalogPublisher.last(app).failure != null) status(HealthCode.PROVIDER_DOWN)
      else if (InstinctCatalogPublisher.last(app).at?.let { System.currentTimeMillis() - it in 0..900_000L } == true)
        status(HealthCode.VERIFIED, configured = true, evidence = HealthEvidence.TRANSPORT)
      else configured(true)
    })
    add(HealthCheck("instinct.sync", "Sincronización de Instinct") {
      status(if (InstinctDoorbell.settings(app).state().informPending || InstinctPushDispatcher.inbox(app).size() > 0)
        HealthCode.LIMITED else HealthCode.DECLARATION)
    })
    add(HealthCheck("instinct.pending", "Órdenes pendientes o bloqueadas") {
      val recent = InstinctExecutions.existingJournal(app)?.recent(24).orEmpty()
      status(if (recent.any { it.state == ExecutionState.WAITING || it.state == ExecutionState.UNCERTAIN })
        HealthCode.LIMITED else HealthCode.PREFLIGHT, evidence = HealthEvidence.JOURNAL)
    })
    InstinctCapabilities.specs.values.filter { it.adapter != InstinctCapabilities.Adapter.OMNIREMOTE &&
      it.id !in setOf("open_app", ShortcutResolver.CAPABILITY) }.forEach { spec ->
      add(HealthCheck(spec.id) {
        when {
          spec.permissions.any { !InstinctDeviceCapabilities.granted(app, it) } -> permission(false)
          InstinctDeviceCapabilities.blockers(app, spec) != null -> status(HealthCode.LIMITED)
          !spec.runsLocked && app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true -> status(HealthCode.UNLOCK)
          else -> permission(true)
        }
      })
    }
  }
}

internal fun status(code: HealthCode, configured: Boolean? = null, authorized: Boolean? = null,
  evidence: HealthEvidence = HealthEvidence.PREFLIGHT) = HealthObservation(code, evidence, configured, authorized)

internal fun configured(value: Boolean) = status(if (value) HealthCode.PREFLIGHT else HealthCode.CONFIG, configured = value)
internal fun permission(value: Boolean) = status(if (value) HealthCode.PREFLIGHT else HealthCode.PERMISSION, authorized = value)

internal fun Context.internetValidated(): Boolean {
  val manager = getSystemService(ConnectivityManager::class.java) ?: return false
  return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

internal fun healthSettings(app: Context, id: String): Intent {
  val readiness = when (id) {
    "notifications.listener", "notifications.media" -> InstinctReadiness.Capability.NOTIFICATION_LISTENER
    "notifications.post" -> InstinctReadiness.Capability.NOTIFICATIONS
    "system.exact_alarms" -> InstinctReadiness.Capability.EXACT_ALARMS
    "system.accessibility" -> InstinctReadiness.Capability.ACCESSIBILITY
    "system.battery" -> InstinctReadiness.Capability.BATTERY
    else -> null
  }
  val pkg = Uri.fromParts("package", app.packageName, null)
  return readiness?.let { InstinctReadiness.settingsIntent(app, it) } ?: when {
    id == "assistant.role" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    id == "system.overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
    id == "notifications.fullscreen" && Build.VERSION.SDK_INT >= 34 -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
    id.startsWith("ai.") -> Intent(app, com.example.speech.GroqSettingsActivity::class.java)
    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
  }
}
