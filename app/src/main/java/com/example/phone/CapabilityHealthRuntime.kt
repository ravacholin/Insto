package com.example.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.FileObserver
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process wiring. The engine only handles typed evidence and can run independently of Android or the UI. */
internal class CapabilityHealthRuntime private constructor(private val app: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val omniremote = OmniremoteHealth(app, onDevice = ::register)
  val engine: CapabilityHealthEngine = CapabilityHealthEngine(scope, HealthStore(File(app.noBackupFilesDir, FILE)),
    listOf(AndroidHealthChecks(app), AiHealthChecks(app), InstinctHealthChecks(app), omniremote).flatMap { it.checks() },
    monotonic = SystemClock::elapsedRealtime)
  private val pending = Channel<suspend () -> Unit>(128, BufferOverflow.DROP_OLDEST)
  private val lastReactive = mutableMapOf<String, Long>()
  private val process = java.util.UUID.randomUUID().toString()

  // Watch only configuration writes. Health, journal and transport writes never feed back into invalidation.
  @Suppress("DEPRECATION")
  private val configuration = object : FileObserver(app.noBackupFilesDir.path, CLOSE_WRITE or MOVED_TO or DELETE) {
    override fun onEvent(event: Int, path: String?) {
      val ids = when (path) {
        "gemini-enabled", "gemini-key.enc" -> setOf("ai.gemini", "ai.llm", "ai.breaker")
        "groq-key.enc" -> setOf("ai.groq", "ai.transcription", "ai.fallback")
        "alarm-voice-enabled", "alarm-voice.properties" -> setOf("alarm.listening", "alarm.stop_voice", "vosk.files", "vosk.load", "vosk.installed")
        "instinct-proposals-enabled", "instinct-mailbox-enabled", "instinct-mailbox-key", "instinct-doorbell-enabled",
        "instinct-log-url", "instinct-log-token" ->
          engine.checks.keys.filter { it.startsWith("instinct.") }.toSet()
        else -> return
      }
      invalidate(ids)
    }
  }

  init {
    GeminiDiagnostics.healthObserver = { id, code, duration ->
      report(id, code, duration, HealthStage.INTERPRETATION, HealthEvidence.TRANSPORT)
    }
    scope.launch {
      engine.load()
      val bootMarker = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, "boot_count", -1) }.getOrDefault(-1)
      val file = File(app.noBackupFilesDir, BOOT_FILE)
      if (bootMarker >= 0 && runCatching { file.readText().toInt() }.getOrNull() != bootMarker) {
        engine.invalidate(engine.checks.keys)
        runCatching { writeAtomically(file, bootMarker.toString(), "insto-health-boot-") }
      }
      for (update in pending) {
        try { update() } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) {
          // Observability cannot interrupt commands or disable subsequent observations.
        }
      }
    }
    scope.launch {
      engine.snapshots.map { snapshot ->
        snapshot.diagnostics.filterKeys { it in InstinctCapabilities.specs || it in publicOperations || it.startsWith("omniremote.device.") && !it.endsWith(".read") }
          .mapValues { (_, row) -> row.publicationKey() }
      }.distinctUntilChanged().collectLatest {
        if (it.isNotEmpty()) {
          delay(5_000)
          InstinctCatalogPublisher.changed(app)
        }
      }
    }
    scope.launch { runCatching { watch(); configuration.startWatching() } }
  }

  private fun register(check: HealthCheck) { engine.register(check) }

  fun report(id: String, code: HealthCode, duration: Long? = null, stage: HealthStage? = null,
    evidence: HealthEvidence = HealthEvidence.EXECUTION, executionRef: String? = null) {
    pending.trySend {
      engine.record(id, status(code, evidence = evidence), duration, stage, executionRef)
      if (code.state.available == false && code !in setOf(HealthCode.CONSENT, HealthCode.UNLOCK)) reactive(id, code)
    }
  }

  private fun reactive(id: String, code: HealthCode) {
    val now = SystemClock.elapsedRealtime()
    val previous = lastReactive[id]
    if (previous != null && now - previous < 60_000L) return
    if (id !in engine.checks) return
    lastReactive[id] = now
    val related = when {
      id.startsWith("omniremote") && code in omniremoteProviderErrors -> setOf("omniremote.catalog", "omniremote.package")
      id == "alarm.listening" || id == "vosk.load" -> setOf("vosk.files", "microphone.permission")
      id == ShortcutResolver.CAPABILITY -> setOf(id)
      id == "microphone" -> setOf("microphone.permission")
      id == "instinct.poll" -> setOf("instinct.poll_job")
      else -> emptySet()
    }
    if (related.isEmpty() && engine.checks[id]?.recovery == null) return
    // The existing catalog job owns transport retries.
    if (id == "instinct.catalog") return
    scope.launch {
      if (related.contains("omniremote.catalog")) omniremote.invalidate()
      related.forEach { engine.check(it, refresh = true) }
      val recovery = when {
        "omniremote.catalog" in related -> "omniremote.catalog"
        "instinct.poll_job" in related -> "instinct.poll_job"
        else -> id
      }
      engine.recover(recovery)
    }
  }

  suspend fun answer(command: CapabilityHealthCommand): String {
    engine.load()
    suspend fun explain(id: String) = engine.check(id).let { row ->
      val label = engine.checks[id]?.label ?: id
      "$label. ${row.state.label}. ${row.observation.code.detail}"
    }
    fun summary(): String {
      val rows = engine.checks.values.filter { it.effect == HealthEffect.READ_ONLY }.map { engine.result(it.id) }
      val verified = rows.count { it.state == HealthState.VERIFIED }
      val issues = rows.filter { it.state.available == false }
      return "Hay $verified funciones verificadas, ${issues.size} con problemas y ${rows.size - verified - issues.size} pendientes o con limitaciones."
    }
    return when (command) {
      CapabilityHealthCommand.STATUS -> summary()
      CapabilityHealthCommand.FULL -> { engine.checkAll(); summary() }
      CapabilityHealthCommand.RECOVER -> {
        val count = recover()
        if (count == 0) "No hay recuperaciones seguras confirmadas. Revisá el Centro de salud." else "Confirmé $count recuperaciones."
      }
      CapabilityHealthCommand.UNAVAILABLE -> engine.checks.values.sortedBy { it.label }
        .filter { engine.result(it.id).state.available == false }.take(3)
        .joinToString(", ") { it.label }.takeIf(String::isNotEmpty)?.let { "Revisá $it." }
        ?: "Todavía no hay fallas vigentes registradas."
      CapabilityHealthCommand.OMNIREMOTE -> explain("omniremote.catalog")
      CapabilityHealthCommand.INSTINCT -> explain("instinct.channel")
      CapabilityHealthCommand.GEMINI -> explain("ai.gemini")
      CapabilityHealthCommand.MICROPHONE -> explain("microphone")
      CapabilityHealthCommand.LAST_FAILURE -> latestFailure()
    }
  }

  suspend fun recover(): Int = engine.checks.values.filter { it.recovery != null }.count { engine.recover(it.id) == true }

  fun localRef(session: Long): String = sha256("$process:$session").take(20)

  fun commandResult(command: PhoneCommand, result: PhoneActionResult, duration: Long, executionRef: String? = null) {
    val id = commandId(command) ?: return
    if (result.stopwatchAutomation != null || result.confirmation != null || result.contactOptions.isNotEmpty() ||
      result.appOptions.isNotEmpty()) return
    val missing = result.needsCamera || result.needsContacts || result.needsNotifications ||
      result.needsClockAccessibility || result.accessSettingsAction != null
    if (result.outcome == PhoneActionOutcome.COMPLETED && !result.verifiedLocally && result.healthCode == null &&
      (command is PhoneCommand.Volume || command is PhoneCommand.Torch || command is PhoneCommand.TemporaryMode ||
        command == PhoneCommand.EndMode || command is PhoneCommand.Platform && command.action in observedSettings)) {
      scope.launch {
        val verdict = runCatching { AndroidActionOutcomeVerifier(app).verify(command, null, result).verdict }.getOrNull()
        when (verdict) {
          OutcomeVerdict.VERIFIED -> report(id, HealthCode.VERIFIED, duration, HealthStage.VERIFICATION, executionRef = executionRef)
          OutcomeVerdict.NOT_ACHIEVED -> report(id, HealthCode.NO_RESULT, duration, HealthStage.VERIFICATION, executionRef = executionRef)
          else -> Unit
        }
      }
      return
    }
    val code = result.healthCode?.let { name -> HealthCode.entries.firstOrNull { it.name == name } } ?: when {
      missing -> HealthCode.PERMISSION
      result.effectUnverified || result.outcome == PhoneActionOutcome.UNCERTAIN -> HealthCode.NO_RESULT
      result.outcome == PhoneActionOutcome.HANDOFF -> HealthCode.HANDOFF
      result.verifiedLocally -> HealthCode.VERIFIED
      result.outcome == PhoneActionOutcome.REJECTED -> HealthCode.REJECTED
      else -> return
    }
    val stage = when (code) {
      HealthCode.PERMISSION -> HealthStage.PERMISSION
      HealthCode.NO_RESULT -> HealthStage.VERIFICATION
      HealthCode.UNLOCK -> HealthStage.DISPATCH
      else -> HealthStage.EXECUTION
    }
    if (InstinctCapabilities.isOmniremote(command) && command is PhoneCommand.Platform) {
      val target = runCatching { InstinctCapabilities.platform(command).second.getString("device") }.getOrNull()
      if (target != null) {
        val device = OmniremoteHealth.deviceId(target)
        if (device !in engine.checks) register(HealthCheck(device, "Dispositivo Omniremote") { status(HealthCode.DECLARATION) })
        if (code !in omniremoteProviderErrors) report(device, code, duration, stage, executionRef = executionRef)
      }
      if (code != HealthCode.VERIFIED && code !in omniremoteProviderErrors) return
    }
    report(id, code, duration, stage, executionRef = executionRef)
  }

  /** Keep device-specific records local unless the target is already present in the authorized catalog. */
  fun protocolOperations(): Map<String, Map<String, Any>> = publicOperations.associateWith(::protocolStatus)

  fun protocolStatus(id: String): Map<String, Any> = engine.result(id).wire()

  suspend fun latestFailure(): String {
    engine.load()
    val local = engine.snapshots.value.incidents.lastOrNull {
      it.stage != null && it.stage != HealthStage.RESPONSE && it.recovery == null &&
        it.id in engine.checks && it.id !in setOf("instinct.execution", "ai.gemini", "ai.groq", "ai.llm", "ai.breaker", "ai.fallback")
    }
    val remote = runCatching { InstinctExecutions.existingJournal(app)?.recent(24)?.firstOrNull {
      it.state in setOf(ExecutionState.FAILED, ExecutionState.UNCERTAIN)
    } }.getOrNull()
    if (remote != null && (local == null || remote.updatedAt >= local.at)) return when {
      remote.state == ExecutionState.UNCERTAIN -> "La orden de Instinct quedó sin verificar. Revisá el resultado antes de repetirla."
      remote.firstDispatchAt == null -> "La orden de Instinct falló antes del despacho. Revisá su registro en Propuestas de Instinct."
      else -> "La orden de Instinct falló después del despacho. Revisá su registro en Propuestas de Instinct."
    }
    return local?.let { "La última falla registrada fue en ${it.stage?.label}. ${it.code.detail}" }
      ?: "No tengo una falla de orden registrada."
  }

  private fun invalidate(ids: Set<String>) {
    pending.trySend { engine.invalidate(ids) }
  }

  private fun watch() {
    val permissions = engine.checks.keys.filterNot { it.startsWith("ai.") || it.startsWith("instinct.") || it.startsWith("omniremote") }.toSet()
    val network = app.getSystemService(ConnectivityManager::class.java)
    runCatching { network?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      private var validated: Boolean? = null
      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        val current = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (validated == current) return
        if (validated != null) invalidate(networkChecks)
        validated = current
      }
      override fun onLost(network: Network) {
        if (validated == true) invalidate(networkChecks)
        validated = false
      }
    }) }

    val packages = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addDataScheme("package")
    }
    registerReceiver(packages) { intent ->
      invalidate(setOf("open_app", ShortcutResolver.CAPABILITY, "voice.tts", "voice.recognizer"))
      if (intent.data?.schemeSpecificPart == OmniremoteBinding.PACKAGE) {
        omniremote.invalidate()
        invalidate(engine.checks.keys.filter { it.startsWith("omniremote") }.toSet())
      }
    }
    val system = IntentFilter().apply {
      addAction(Intent.ACTION_USER_PRESENT)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_CONFIGURATION_CHANGED)
    }
    registerReceiver(system) { intent ->
      invalidate(if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) permissions
        else setOf("system.locked", ShortcutResolver.CAPABILITY))
    }
    val settingsObserver = object : android.database.ContentObserver(null) {
      override fun onChange(selfChange: Boolean) {
        invalidate(setOf("notifications.listener", "notifications.media", "system.accessibility", "assistant.role"))
      }
    }
    listOf("enabled_notification_listeners", android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "assistant")
      .forEach { setting ->
        runCatching {
          app.contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor(setting), false, settingsObserver)
        }
      }
    // AppOps covers while-in-use microphone and permission changes while the process is alive.
    val ops = app.getSystemService(android.app.AppOpsManager::class.java)
    runCatching {
      ops?.startWatchingMode(android.app.AppOpsManager.OPSTR_RECORD_AUDIO, app.packageName) { _, _ ->
        invalidate(setOf("microphone", "microphone.permission", "microphone.test", "alarm.listening"))
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun registerReceiver(filter: IntentFilter, receive: (Intent) -> Unit) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) = receive(intent)
    }
    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else app.registerReceiver(receiver, filter)
  }

  companion object {
    const val FILE = "capability-health.json"
    const val BOOT_FILE = "capability-health-boot"
    private val publicOperations = setOf("ai.router", "ai.llm", "instinct.channel", "instinct.mailbox", "instinct.push", "instinct.sync")
    private val observedSettings = setOf(PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF,
      PlatformAction.VIBRATE_ON, PlatformAction.VIBRATE_OFF, PlatformAction.DND_ON, PlatformAction.DND_OFF)
    private val networkChecks = setOf("ai.gemini", "ai.groq", "ai.llm", "ai.transcription", "ai.fallback", "instinct.channel")
    private val omniremoteProviderErrors = setOf(HealthCode.PROVIDER_DOWN, HealthCode.PROVIDER_UNTRUSTED,
      HealthCode.RESPONSE, HealthCode.VERSION, HealthCode.PROVIDER_MISSING, HealthCode.TIMEOUT)
    @Volatile private var instance: CapabilityHealthRuntime? = null

    fun start(app: Context) = synchronized(this) {
      instance ?: CapabilityHealthRuntime(app.applicationContext).also { instance = it }
    }

    fun current(): CapabilityHealthRuntime? = instance

    private fun commandId(command: PhoneCommand): String? = when (command) {
      is PhoneCommand.Platform -> when (command.action) {
        PlatformAction.INSTINCT_CAPABILITY -> command.argument
        PlatformAction.EXECUTE_APP_SHORTCUT -> ShortcutResolver.CAPABILITY
        PlatformAction.CALENDAR_QUERY -> "calendar_query"
        PlatformAction.CALENDAR_UPDATE -> "calendar_update"
        PlatformAction.CALENDAR_DELETE -> "calendar_cancel"
        PlatformAction.CONTACT_PHONES, PlatformAction.CONTACT_SEARCH -> "contacts"
        else -> command.action.name.lowercase()
      }
      is PhoneCommand.Volume -> "volume"
      is PhoneCommand.Torch -> "torch"
      is PhoneCommand.Media -> "media"
      is PhoneCommand.TemporaryMode, PhoneCommand.EndMode -> "temporary_mode"
      is PhoneCommand.OpenApp -> "open_app"
      is PhoneCommand.CalendarEvent -> "calendar_create"
      is PhoneCommand.Timer -> "clock_timer"
      is PhoneCommand.Alarm -> "clock_alarm"
      is PhoneCommand.YouTubeSearch -> "youtube"
      is PhoneCommand.SpotifySearch -> "spotify"
      else -> null
    }
  }
}
