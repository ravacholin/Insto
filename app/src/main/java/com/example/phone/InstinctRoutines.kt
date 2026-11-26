package com.example.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Routines as macros for Instinct: the pure part. The user enables, routine by routine, which configured routines
 * Instinct may run with «#insto ejecutar la rutina <nombre>». A grant stores the hash of the routine's steps when it
 * was enabled: a routine that changed (a re-import) no longer matches and stays off until the user turns it on
 * again. Free plans are never built from Instinct's text. See docs/INSTINCT-PROPUESTAS.md, «Rutinas».
 */
object InstinctRoutines {
  const val MAX_STEPS = 6

  sealed interface Eligibility {
    data object Allowed : Eligibility
    data class TooManySteps(val steps: Int) : Eligibility
    /** [index] is 1-based, as the user reads it. */
    data class ForbiddenStep(val index: Int, val summary: String) : Eligibility
  }

  /** Every step must pass the single-command allowlist, and there are at most [MAX_STEPS]. */
  fun eligibility(routine: ConfiguredRoutine): Eligibility {
    if (routine.commands.size > MAX_STEPS) return Eligibility.TooManySteps(routine.commands.size)
    routine.commands.forEachIndexed { index, command ->
      if (!InstinctProposalValidator.allowed(command)) return Eligibility.ForbiddenStep(index + 1, PhoneCommandCodec.summary(command))
    }
    return Eligibility.Allowed
  }

  /** Why the switch is disabled, in Spanish; null when the routine can be enabled. */
  fun blocker(routine: ConfiguredRoutine): String? = when (val e = eligibility(routine)) {
    Eligibility.Allowed -> null
    is Eligibility.TooManySteps -> "Tiene ${e.steps} pasos; Instinct solo puede ejecutar rutinas de hasta $MAX_STEPS."
    is Eligibility.ForbiddenStep -> "El paso ${e.index} («${e.summary}») no está permitido para Instinct."
  }

  /** The name as `routine_run` compares it. */
  fun key(name: String): String = PhoneCommandParser.normalize(name)

  /** SHA-256 of the steps, with keys sorted so the same steps always give the same hash. */
  fun hash(routine: ConfiguredRoutine): String =
    sha256(routine.commands.joinToString("\n") { canonical(PhoneCommandCodec.encode(it)) })

  private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
    is String -> JSONObject.quote(value)
    null, JSONObject.NULL -> "null"
    else -> value.toString()
  }

  /** Grants as stored: normalized name → hash of the steps when enabled. */
  fun encodeGrants(grants: Map<String, String>): String = JSONObject().apply { grants.toSortedMap().forEach { (k, v) -> put(k, v) } }.toString()

  fun decodeGrants(text: String): Map<String, String> = runCatching {
    val o = JSONObject(text)
    o.keys().asSequence().take(64).associateWith { o.getString(it) }.filterValues { it.matches(Regex("[0-9a-f]{64}")) }
  }.getOrDefault(emptyMap())
}

/** The routines Instinct may run right now: configured, enabled for Instinct, unchanged since and still eligible. */
class InstinctRoutineAccess(private val grants: Map<String, String>, private val routines: () -> List<ConfiguredRoutine>) {
  fun resolve(name: String): ConfiguredRoutine? {
    val key = InstinctRoutines.key(name)
    val routine = runCatching(routines).getOrDefault(emptyList()).singleOrNull { InstinctRoutines.key(it.name) == key } ?: return null
    return routine.takeIf { grants[key] == InstinctRoutines.hash(it) && InstinctRoutines.eligibility(it) == InstinctRoutines.Eligibility.Allowed }
  }

  /** Names only, never the steps: what the catalog and «ver catálogo» show. */
  fun enabledNames(): List<String> = runCatching(routines).getOrDefault(emptyList()).filter { resolve(it.name) != null }.map { it.name }

  companion object { val NONE = InstinctRoutineAccess(emptyMap()) { emptyList() } }
}

/**
 * Runs a routine Instinct asked for, in the background, on a [WorkflowRunner] whose pre-dispatch write is
 * synchronous. Stops at the first step that fails, needs input, is blocked or is uncertain: the remaining steps are
 * CANCELLED and never retried by themselves. A step handed to another app counts as done.
 */
class InstinctRoutineRun(
  private val runner: WorkflowRunner,
  /** Null when the step may run; otherwise why not (the same checks as a single order, without the budget). */
  private val blocker: (PhoneCommand) -> String?,
  private val execute: (PhoneCommand) -> PhoneActionResult,
) {
  data class Step(val command: PhoneCommand, val result: PhoneActionResult)

  /** [statuses] are the steps as the runner left them: after a stop, the remaining ones are CANCELLED. */
  data class Report(val name: String, val total: Int, val completed: List<Step>, val stoppedAt: Int?, val reason: String?,
    val uncertain: Boolean, val statuses: List<StepStatus> = emptyList()) {
    val finished: Boolean get() = stoppedAt == null
    val kind: AckKind get() = when { finished -> AckKind.OK; uncertain -> AckKind.UNCERTAIN; else -> AckKind.ERROR }
    val summary: String get() = if (finished) {
      val sent = statuses.count { it == StepStatus.SENT }
      "Instinct ejecutó la rutina $name: ${completed.size} de $total pasos." +
        if (sent > 0) " $sent enviados por OmniRemote, con efecto sin confirmar." else ""
    }
      else "Instinct ejecutó la rutina $name: se detuvo en el paso $stoppedAt: $reason" + if (uncertain) " · no se repite" else ""
  }

  fun run(routine: ConfiguredRoutine): Report {
    val total = routine.commands.size
    val done = ArrayList<Step>()
    fun stop(index: Int, reason: String, uncertain: Boolean = false): Report {
      runCatching { runner.cancel() }
      val statuses = runner.snapshot?.steps?.map { it.status }.orEmpty()
      // Nothing is left to resume: a stopped routine never continues or retries by itself.
      runCatching { runner.clear() }
      return Report(routine.name, total, done, index + 1, reason.trim(), uncertain, statuses)
    }
    try {
      runner.prepare(routine.commands)
      runner.confirm()
    } catch (_: Exception) { return stop(0, "no se pudo guardar el progreso; no se ejecutó ningún paso.") }
    for (index in routine.commands.indices) {
      val planned = routine.commands[index]
      if (!InstinctProposalValidator.allowed(planned)) return stop(index, "ese paso no está permitido para Instinct.")
      blocker(planned)?.let { return stop(index, it) }
      val command = try { runner.next() } catch (_: Exception) { null } ?: return stop(index, "no se pudo guardar el progreso; ese paso no se ejecutó.")
      val result = try { execute(command) } catch (_: Exception) {
        PhoneActionResult("Android no confirmó el resultado.", outcome = PhoneActionOutcome.UNCERTAIN)
      }
      try { runner.accept(result) } catch (_: Exception) {
        runner.markUncertainInMemory(); return stop(index, "no se pudo guardar el resultado.", uncertain = true)
      }
      val status = runner.snapshot?.steps?.getOrNull(index)?.status
      when (status) {
        StepStatus.COMPLETED, StepStatus.SENT -> done += Step(command, result)
        StepStatus.HANDED_OFF -> { done += Step(command, result); runCatching { runner.resumeHandoff() } }
        StepStatus.UNCERTAIN -> return stop(index, result.message, uncertain = true)
        else -> return stop(index, result.message)
      }
    }
    val statuses = runner.snapshot?.steps?.map { it.status }.orEmpty()
    runCatching { runner.clear() }
    return Report(routine.name, total, done, null, null, uncertain = false, statuses = statuses)
  }
}
