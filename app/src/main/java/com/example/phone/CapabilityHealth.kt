package com.example.phone

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

internal enum class HealthState(val label: String, val available: Boolean? = false) {
  VERIFIED("Operativa y verificada", true), AVAILABLE("Disponible, por verificar", true),
  LIMITED("Disponible con limitaciones", true), DEGRADED("Temporalmente degradada"),
  PERMISSION_MISSING("Permiso faltante"), INCOMPLETE("Configuración incompleta"),
  INVALID_CREDENTIALS("Credenciales rechazadas"), PROVIDER_UNREACHABLE("Proveedor inaccesible"),
  QUOTA_EXHAUSTED("Cuota agotada"), VERSION_MISMATCH("Versión incompatible"),
  UNSUPPORTED("No compatible"), SYSTEM_RESTRICTED("Restricción del sistema"),
  USER_REQUIRED("Requiere intervención"), NOT_OPERATIONAL("No operativa"), UNKNOWN("Sin comprobación vigente", null),
}

internal enum class HealthCode(
  val state: HealthState, val detail: String, val action: String? = null,
  val confirmed: Boolean = true, val transient: Boolean = false,
) {
  UNKNOWN(HealthState.UNKNOWN, "Todavía falta evidencia de funcionamiento.", confirmed = false),
  DECLARATION(HealthState.UNKNOWN, "La función está implementada. Falta observar una ejecución.", confirmed = false),
  PREFLIGHT(HealthState.AVAILABLE, "Las condiciones básicas se cumplen."),
  VERIFIED(HealthState.VERIFIED, "La comprobación observó el resultado esperado."),
  HANDOFF(HealthState.LIMITED, "Se entregó la solicitud. El resultado final queda por confirmar."),
  LIMITED(HealthState.LIMITED, "El entorno limita esta función.", "Revisá las condiciones de la función."),
  PERMISSION(HealthState.PERMISSION_MISSING, "Android indica que falta un permiso.", "Concedé el acceso en Ajustes."),
  CONFIG(HealthState.INCOMPLETE, "Falta habilitar o configurar la función.", "Revisá la configuración de Insto."),
  CREDENTIAL_MISSING(HealthState.INCOMPLETE, "Faltan credenciales.", "Revisá la configuración del proveedor."),
  CREDENTIAL_UNREADABLE(HealthState.USER_REQUIRED, "Insto no pudo leer las credenciales guardadas.", "Volvé a ingresarlas en Ajustes."),
  CREDENTIAL_REJECTED(HealthState.INVALID_CREDENTIALS, "El proveedor rechazó las credenciales.", "Revisá la clave y sus accesos."),
  OFFLINE(HealthState.PROVIDER_UNREACHABLE, "Android no informa una conexión a Internet validada.", "Revisá la conexión.", transient = true),
  JOB_MISSING(HealthState.DEGRADED, "Android no tiene programado el trabajo de sincronización.", transient = true),
  NETWORK(HealthState.PROVIDER_UNREACHABLE, "Falló la comunicación con el proveedor.", "Revisá la conexión.", confirmed = false, transient = true),
  CIRCUIT_OPEN(HealthState.DEGRADED, "El circuito suspendió temporalmente los pedidos al proveedor.", transient = true),
  RATE_LIMIT(HealthState.DEGRADED, "El proveedor limitó las solicitudes.", "Esperá antes de volver a probar.", transient = true),
  QUOTA(HealthState.QUOTA_EXHAUSTED, "El proveedor informó que se agotó la cuota.", "Revisá el límite de uso."),
  MODEL(HealthState.PROVIDER_UNREACHABLE, "El endpoint o modelo solicitado no está disponible.", "Revisá el modelo configurado."),
  PROVIDER_DOWN(HealthState.PROVIDER_UNREACHABLE, "El proveedor no respondió correctamente.", transient = true),
  TIMEOUT(HealthState.DEGRADED, "Se agotó el tiempo de espera.", confirmed = false, transient = true),
  RESPONSE(HealthState.NOT_OPERATIONAL, "La respuesta no cumple el contrato.", "Revisá la compatibilidad del proveedor."),
  INTERPRETATION(HealthState.NOT_OPERATIONAL, "Insto no pudo resolver la interpretación.", confirmed = false),
  VERSION(HealthState.VERSION_MISMATCH, "El proveedor informa una versión del contrato incompatible.", "Actualizá las aplicaciones."),
  UNSUPPORTED(HealthState.UNSUPPORTED, "El dispositivo o aplicación no ofrece esta función."),
  SYSTEM(HealthState.SYSTEM_RESTRICTED, "Android restringió el acceso a la función.", "Revisá los ajustes del sistema."),
  UNLOCK(HealthState.USER_REQUIRED, "La operación requiere el teléfono desbloqueado.", "Desbloqueá el teléfono."),
  CONSENT(HealthState.USER_REQUIRED, "La prueba requiere autorización explícita."),
  PROVIDER_MISSING(HealthState.INCOMPLETE, "El paquete de Omniremote no está disponible en este perfil.", "Instalá Omniremote en este perfil."),
  PROVIDER_UNTRUSTED(HealthState.USER_REQUIRED, "Omniremote rechazó el acceso de Insto.", "Revisá la autorización del certificado en Omniremote."),
  DEVICE_UNREACHABLE(HealthState.DEGRADED, "No se pudo observar el dispositivo en Omniremote.", "Revisá su conexión y emparejamiento.", confirmed = false, transient = true),
  DEVICE_MISSING(HealthState.INCOMPLETE, "Omniremote ya no anuncia este dispositivo."),
  MICROPHONE(HealthState.DEGRADED, "No se pudo obtener audio del micrófono.", "Cerrá otras grabaciones y revisá el acceso al micrófono.", confirmed = false, transient = true),
  MODEL_FILES(HealthState.INCOMPLETE, "Faltan archivos del modelo Vosk o están vacíos.", "Volvé a descargar el modelo."),
  MODEL_LOAD(HealthState.NOT_OPERATIONAL, "Vosk no pudo cargar el modelo.", "Revisá el modelo local.", confirmed = false),
  NO_RESULT(HealthState.DEGRADED, "No se pudo verificar el resultado.", "Comprobá el estado antes de repetir el pedido.", confirmed = false),
  REJECTED(HealthState.NOT_OPERATIONAL, "La ejecución terminó con un rechazo.", confirmed = false),
  INTERNAL(HealthState.UNKNOWN, "La comprobación terminó con un error interno.", confirmed = false),
}

internal enum class HealthEvidence { DEFINITION, PREFLIGHT, ACTIVE_TEST, EXECUTION, JOURNAL, TRANSPORT }
internal enum class HealthEffect { READ_ONLY, RESOURCE, TEMPORARY_STATE, INTERACTION, EXTERNAL }
internal enum class HealthStage(val label: String) {
  CAPTURE("captura de audio"), TRANSCRIPTION("transcripción"), INTERPRETATION("interpretación"),
  RESOLUTION("resolución de intención"), VALIDATION("validación"), PERMISSION("permisos"),
  DISPATCH("despacho"), EXECUTION("ejecución"), VERIFICATION("verificación"), RESPONSE("comunicación del resultado"),
}

internal data class HealthObservation(
  val code: HealthCode,
  val evidence: HealthEvidence = HealthEvidence.PREFLIGHT,
  val configured: Boolean? = null,
  val authorized: Boolean? = null,
)

internal data class HealthCheck(
  val id: String,
  val label: String = id,
  val ttlMillis: Long = 15 * 60_000L,
  val timeoutMillis: Long = 5_000L,
  val effect: HealthEffect = HealthEffect.READ_ONLY,
  val recovery: (suspend () -> Unit)? = null,
  val verifies: String? = null,
  val probe: suspend () -> HealthObservation,
)

internal data class HealthDiagnostic(
  val id: String,
  val observation: HealthObservation = HealthObservation(HealthCode.UNKNOWN, HealthEvidence.DEFINITION),
  val checkedAt: Long = 0,
  val expiresAt: Long = 0,
  val lastSuccess: Long? = null,
  val lastFailure: Long? = null,
  val consecutiveFailures: Int = 0,
  val latencyMillis: Long? = null,
  val successes: Long = 0,
  val failures: Long = 0,
  val timeouts: Long = 0,
  val durations: List<Long> = emptyList(),
  val recoveryAttempts: Int = 0,
  val retryAt: Long = 0,
  val recoveries: Long = 0,
) {
  val state get() = observation.code.state
  fun at(now: Long) = if (now in checkedAt until expiresAt) this
    else copy(observation = HealthObservation(HealthCode.UNKNOWN, HealthEvidence.DEFINITION))
  // Publication refreshes on evidence changes and at most once per validity window for repeated evidence.
  fun publicationKey() = Triple(observation, expiresAt != 0L,
    if (expiresAt > checkedAt) checkedAt / (expiresAt - checkedAt) else 0L)

  fun percentile(percent: Int): Long? = durations.sorted().let { it.getOrNull((it.size * percent + 99) / 100 - 1) }
}

internal data class HealthIncident(
  val id: String, val code: HealthCode, val at: Long, val stage: HealthStage? = null,
  val recovery: Boolean? = null,
  val executionRef: String? = null,
)

internal data class HealthSnapshot(
  val diagnostics: Map<String, HealthDiagnostic> = emptyMap(),
  val incidents: List<HealthIncident> = emptyList(),
)

/** The only data admitted to persistence is typed evidence. IDs must come from the registered catalog. */
internal class HealthStore(private val file: File) {
  fun load(ids: Set<String>): HealthSnapshot = runCatching {
    require(file.length() <= 512_000L)
    val root = JSONObject(file.readText())
    val records = root.getJSONArray("records")
    val incidents = root.getJSONArray("incidents")
    HealthSnapshot(
      (0 until records.length()).mapNotNull { index -> runCatching {
        val row = records.getJSONObject(index)
        val id = row.getString("id").takeIf { it in ids || DEVICE.matches(it) } ?: return@runCatching null
        val samples = row.getJSONArray("samples")
        id to HealthDiagnostic(id, HealthObservation(HealthCode.valueOf(row.getString("code")),
          HealthEvidence.valueOf(row.getString("evidence")), row.booleanOrNull("configured"), row.booleanOrNull("authorized")),
          row.getLong("at"), row.getLong("expires"), row.longOrNull("success"), row.longOrNull("failure"),
          row.getInt("streak"), row.longOrNull("latency"), row.getLong("successes"), row.getLong("failures"),
          row.getLong("timeouts"), (0 until samples.length()).toList().takeLast(32).map(samples::getLong),
          row.getInt("attempts"), row.getLong("retry"), row.getLong("recoveries"))
      }.getOrNull() }.toMap(),
      (0 until incidents.length()).toList().takeLast(64).mapNotNull { index -> runCatching {
        val row = incidents.getJSONObject(index)
        val id = row.getString("id").takeIf { it in ids || DEVICE.matches(it) } ?: return@runCatching null
        HealthIncident(id, HealthCode.valueOf(row.getString("code")), row.getLong("at"),
          row.optString("stage").takeIf(String::isNotEmpty)?.let(HealthStage::valueOf), row.booleanOrNull("recovery"),
          row.optString("execution").takeIf(REFERENCE::matches))
      }.getOrNull() })
  }.getOrDefault(HealthSnapshot())

  fun save(snapshot: HealthSnapshot) {
    val records = JSONArray(snapshot.diagnostics.values.map { row ->
      JSONObject().put("id", row.id).put("code", row.observation.code.name).put("evidence", row.observation.evidence.name)
        .put("configured", row.observation.configured).put("authorized", row.observation.authorized)
        .put("at", row.checkedAt).put("expires", row.expiresAt).put("success", row.lastSuccess).put("failure", row.lastFailure)
        .put("streak", row.consecutiveFailures).put("latency", row.latencyMillis).put("successes", row.successes)
        .put("failures", row.failures).put("timeouts", row.timeouts).put("samples", JSONArray(row.durations))
        .put("attempts", row.recoveryAttempts).put("retry", row.retryAt).put("recoveries", row.recoveries)
    })
    val incidents = JSONArray(snapshot.incidents.map { row ->
      JSONObject().put("id", row.id).put("code", row.code.name).put("at", row.at)
        .put("stage", row.stage?.name).put("recovery", row.recovery).put("execution", row.executionRef?.takeIf(REFERENCE::matches))
    })
    writeAtomically(file, JSONObject().put("records", records).put("incidents", incidents).toString(), "insto-health-")
  }

  private companion object {
    val DEVICE = Regex("omniremote\\.device\\.[0-9a-f]{20}(?:\\.read)?")
    val REFERENCE = Regex("[0-9a-f]{20}")
  }

  private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
  private fun JSONObject.booleanOrNull(key: String) = if (isNull(key)) null else getBoolean(key)
}

internal class CapabilityHealthEngine(
  private val scope: CoroutineScope,
  private val store: HealthStore,
  checks: Collection<HealthCheck>,
  private val wallClock: () -> Long = System::currentTimeMillis,
  private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
  parallelism: Int = 4,
  private val disk: CoroutineDispatcher = Dispatchers.IO,
) {
  private val registry = java.util.concurrent.ConcurrentHashMap(checks.associateBy(HealthCheck::id))
  val checks: Map<String, HealthCheck> get() = registry.toSortedMap()

  fun register(check: HealthCheck) {
    synchronized(registry) {
      if (registry.size < 256 || registry.containsKey(check.id)) registry[check.id] = check
    }
  }
  private val lock = Mutex()
  private val permits = Semaphore(parallelism)
  private val current = MutableStateFlow(HealthSnapshot())
  val snapshots = current.asStateFlow()
  private var loaded = false
  private val generations = mutableMapOf<String, Long>()
  private data class Flight(val result: Deferred<HealthDiagnostic>, var callers: Int = 0)
  private val flights = mutableMapOf<Pair<String, Boolean>, Flight>()
  private val recovering = mutableSetOf<String>()

  suspend fun load() = lock.withLock { loadLocked() }

  private suspend fun loadLocked() {
    if (loaded) return
    current.value = withContext(disk) { store.load(registry.keys) }
    loaded = true
  }

  fun result(id: String): HealthDiagnostic = (current.value.diagnostics[id] ?: HealthDiagnostic(id)).at(wallClock())

  suspend fun invalidate(ids: Set<String>) = lock.withLock {
    loadLocked()
    ids.filter(registry::containsKey).forEach {
      generations[it] = generation(it) + 1
      flights.keys.removeAll { key -> key.first == it }
    }
    val revised = current.value.diagnostics.mapValues { (id, row) -> if (id in ids) row.copy(expiresAt = 0) else row }
    if (revised != current.value.diagnostics) save(current.value.copy(diagnostics = revised))
  }

  suspend fun check(id: String, refresh: Boolean = false, authorized: Boolean = false): HealthDiagnostic {
    val spec = registry[id] ?: return HealthDiagnostic(id)
    if (spec.effect != HealthEffect.READ_ONLY && !authorized)
      return HealthDiagnostic(id, HealthObservation(HealthCode.CONSENT))
    val key = id to authorized
    val flight = lock.withLock {
      loadLocked()
      val cached = result(id)
      if (!refresh && cached.observation.code != HealthCode.UNKNOWN) return cached
      flights.getOrPut(key) {
        val generation = generation(id)
        Flight(scope.async(start = CoroutineStart.LAZY) {
          permits.withPermit { probe(spec, generation) }
        })
      }.also { it.callers++ }
    }
    return try {
      flight.result.await()
    } finally {
      withContext(NonCancellable) {
        lock.withLock {
          if (--flight.callers == 0) {
            flight.result.cancel()
            if (flights[key] === flight) flights.remove(key)
          }
        }
      }
    }
  }

  suspend fun checkAll(refresh: Boolean = true, timeoutMillis: Long = 30_000): Map<String, HealthDiagnostic> {
    val safe = registry.values.filter { it.effect == HealthEffect.READ_ONLY }
    val initial = safe.mapTo(mutableSetOf(), HealthCheck::id)
    withTimeoutOrNull(timeoutMillis) {
      coroutineScope { safe.map { async { check(it.id, refresh = refresh) } }.awaitAll() }
      val discovered = registry.values.filter { it.effect == HealthEffect.READ_ONLY && it.id !in initial }
      coroutineScope { discovered.map { async { check(it.id, refresh = refresh) } }.awaitAll() }
    }
    return registry.values.filter { it.effect == HealthEffect.READ_ONLY }.associate { it.id to result(it.id) }
  }

  private suspend fun probe(spec: HealthCheck, generation: Long): HealthDiagnostic {
    val started = monotonic()
    val observation = withTimeoutOrNull(spec.timeoutMillis) {
      try {
        spec.probe()
      } catch (error: CancellationException) {
        throw error
      } catch (_: SecurityException) {
        HealthObservation(HealthCode.SYSTEM)
      } catch (_: Exception) {
        HealthObservation(HealthCode.INTERNAL)
      }
    } ?: HealthObservation(HealthCode.TIMEOUT)
    return lock.withLock {
      if (generation != generation(spec.id)) return@withLock result(spec.id)
      val elapsed = monotonic() - started
      val result = update(spec, observation, elapsed, null, operation = false)
      spec.verifies?.let { id -> registry[id]?.let { primary ->
        generations[id] = generation(id) + 1
        update(primary, observation, elapsed, null, operation = true)
      } }
      result
    }
  }

  suspend fun record(id: String, observation: HealthObservation, duration: Long? = null, stage: HealthStage? = null,
    executionRef: String? = null) {
    val spec = registry[id] ?: return
    lock.withLock {
      loadLocked()
      generations[id] = generation(id) + 1
      flights.keys.removeAll { it.first == id }
      update(spec, observation, duration, stage, operation = true, executionRef = executionRef)
    }
  }

  private suspend fun update(spec: HealthCheck, observation: HealthObservation, duration: Long?, stage: HealthStage?,
    operation: Boolean, executionRef: String? = null): HealthDiagnostic {
    val snapshot = current.value
    val now = wallClock()
    val old = snapshot.diagnostics[spec.id] ?: HealthDiagnostic(spec.id)
    val code = observation.code
    // A successful read-only preflight cannot disprove a failed execution or renew an earlier functional proof.
    if (!operation && code in setOf(HealthCode.PREFLIGHT, HealthCode.DECLARATION) &&
      old.at(now).state !in setOf(HealthState.UNKNOWN, HealthState.AVAILABLE)) return old
    val success = code == HealthCode.VERIFIED
    val failure = code.state.available == false && code != HealthCode.CONSENT
    val elapsed = duration?.coerceAtLeast(0)
    val updated = old.copy(
      observation = observation.copy(configured = observation.configured ?: old.observation.configured,
        authorized = observation.authorized ?: old.observation.authorized),
      checkedAt = now, expiresAt = now + spec.ttlMillis,
      lastSuccess = if (success) now else old.lastSuccess,
      lastFailure = if (failure) now else old.lastFailure,
      consecutiveFailures = if (success) 0 else old.consecutiveFailures + if (failure && (operation || old.observation.code != code)) 1 else 0,
      latencyMillis = elapsed,
      successes = old.successes + if (operation && success) 1 else 0,
      failures = old.failures + if (operation && failure) 1 else 0,
      timeouts = old.timeouts + if (code == HealthCode.TIMEOUT) 1 else 0,
      durations = if (operation && elapsed != null) (old.durations + elapsed).takeLast(32) else old.durations,
      recoveryAttempts = if (success) 0 else old.recoveryAttempts,
      retryAt = if (success) 0 else old.retryAt,
    )
    val incidents = if (failure && (operation || old.observation.code != code))
      snapshot.incidents + HealthIncident(spec.id, code, now, stage, executionRef = executionRef) else snapshot.incidents
    save(HealthSnapshot(snapshot.diagnostics + (spec.id to updated), incidents.takeLast(64)))
    return updated
  }

  /** Runs only the registered safe repair, never an execution supplied by a caller. Budget is durable before repair. */
  suspend fun recover(id: String): Boolean? {
    val spec = registry[id] ?: return null
    val repair = spec.recovery ?: return null
    if (spec.effect != HealthEffect.READ_ONLY) return null
    lock.withLock {
      loadLocked()
      val row = result(id)
      val now = wallClock()
      if (!row.observation.code.transient || id in recovering || row.retryAt > now || row.recoveryAttempts >= 2) return null
      val reserved = row.copy(recoveryAttempts = row.recoveryAttempts + 1,
        retryAt = now + 60_000L * (row.recoveryAttempts + 1))
      if (!save(current.value.copy(diagnostics = current.value.diagnostics + (id to reserved)))) return null
      recovering += id
    }
    try {
      val repaired = withTimeoutOrNull(spec.timeoutMillis) {
        try {
          repair()
          true
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          false
        }
      } == true
      val verified = repaired && check(id, refresh = true).state == HealthState.VERIFIED
      lock.withLock {
        val snapshot = current.value
        val row = snapshot.diagnostics.getValue(id)
        save(snapshot.copy(diagnostics = snapshot.diagnostics + (id to row.copy(
          recoveries = row.recoveries + if (verified) 1 else 0)),
          incidents = (snapshot.incidents + HealthIncident(id, row.observation.code, wallClock(), recovery = verified)).takeLast(64)))
      }
      return verified
    } finally {
      withContext(NonCancellable) { lock.withLock { recovering -= id } }
    }
  }

  private fun generation(id: String) = generations[id] ?: 0
  private suspend fun save(value: HealthSnapshot): Boolean = withContext(disk + NonCancellable) {
    val persisted = runCatching { store.save(value) }.isSuccess
    current.value = value
    persisted
  }
}
