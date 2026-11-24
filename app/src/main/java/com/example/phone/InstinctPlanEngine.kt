package com.example.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Runs plans of several steps (1.23.0) on what already exists, without a second infrastructure:
 *
 * - every action is a row of the execution journal ([ExecutionTransport.PLAN]): «despachando» is written before
 *   touching the phone, the action runs through [PhoneActionExecutor] with the authorization of 1.20.0, and its effect
 *   is read back by [InstinctProposalCenter.outcomeVerifier], exactly like an order that arrives alone;
 * - autonomy, quiet hours, the anti-loop brake and «Nunca» come from [InstinctGatePreview] and
 *   [InstinctProposalCenter]: a plan is one order for the brake and, when its categories ask for it, one confirmation
 *   for the whole plan. Granted permissions are never asked again and nothing that needs a screen is ever run;
 * - an approval and a «decidir» are rows of the existing questions ([InstinctQuestionNotifications]), with their
 *   buttons, privacy on the lock screen, quiet hours and recovery;
 * - waits for an event or a trigger reuse [InstinctTriggers] and the calendar reader of [InstinctScheduler];
 * - saving and restoring a setting reuse [InstinctUndoActions]: a value someone changed afterwards is never overwritten;
 * - progress goes to the log ([InstinctLog]) as events of kind «plan», never to the WhatsApp chat.
 *
 * The plan's row in SQLite is the only truth: a process death resumes from it. An action that may have happened is
 * never run again: a readable setting is read back and anything else suspends the plan as uncertain.
 */
object InstinctPlanEngine {
  const val ACTION_TICK = "com.example.phone.ACTION_INSTINCT_PLAN_TICK"
  const val LOG_KIND = InstinctPlanSpec.LOG_KIND
  const val APPROVE = "Ejecutar"
  const val DECLINE = "Cancelar"
  const val SWITCHED_OFF = "se apagó «Recibir órdenes»; no se restauró nada."
  const val NEEDS_QUESTIONS = "necesita preguntarte y la categoría Preguntas está en Nunca."
  const val CHANGED = "cambió la configuración de Insto (contacto o WhatsApp); no se sigue."
  private const val MAX_ITERATIONS = 64

  /** Runs the plans off the caller; tests run them inline. */
  internal var background: (Runnable) -> Unit = { executor.execute(it) }
  private val executor by lazy { Executors.newSingleThreadExecutor { Thread(it, "insto-planes") } }
  /** A seam for tests; arms nothing when replaced. */
  internal var alarms: (Context, Long?) -> Unit = ::arm
  /** Runs one validated action, already authorized (1.20.0): its own «¿Confirmás?» is not asked again. */
  internal var execute: (Context, PhoneCommand) -> PhoneActionResult = { context, command ->
    PhoneActionExecutor(context).execute(command, confirmed = InstinctConfirmations.preAuthorized(command))
  }
  /** The phone now, for a «si …» step. */
  internal var snapshot: (Context, Set<ContextSignal>) -> ContextSnapshot = { context, signals ->
    InstinctContextScheduler.snapshotNow(context.applicationContext, signals)
  }

  private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val again: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val specs = ConcurrentHashMap<String, InstinctPlanSpec>()

  private fun journal(context: Context) = InstinctExecutions.journal(context)
  private fun now() = InstinctExecutionJournal.clock()
  private fun calendarAt(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }

  // ---------------------------------------------------------------------------------------------------------------
  // Grammar and admission
  // ---------------------------------------------------------------------------------------------------------------

  /** Every action of [spec], validated again now; null when one is no longer valid. */
  private fun commands(spec: InstinctPlanSpec): List<PhoneCommand>? {
    val parser = PhoneCommandParser()
    val now = calendarAt(now())
    return spec.actionLines.map { InstinctProposalValidator.backgroundAction(it, parser, now) ?: return null }
  }

  /** What a plan would need from the policy now: Refuse, Wait (one approval) or Run. Nothing is consumed. */
  private fun refusal(context: Context, spec: InstinctPlanSpec, commands: List<PhoneCommand>): String? {
    val policy = InstinctAutonomySettings(context).policy()
    if (policy.refuses(commands, routine = false)) return InstinctAutonomyPolicy.REFUSED
    if (spec.steps.any { it is PlanStep.Decide } && policy.level(InstinctCategory.PREGUNTAS) == InstinctAutonomy.NUNCA)
      return NEEDS_QUESTIONS
    return null
  }

  internal data class Admission(val entry: AckEntry, val plan: String? = null)

  /**
   * «#insto plan …» accepted: refused at once under «Nunca», recorded as a plan otherwise. The order's own row ends
   * as accepted; the plan lives in its own row and runs with [start]. The same order twice is the same plan.
   */
  internal fun admit(context: Context, check: PlanCheck, execution: String?, source: ProposalSource, message: String,
    fingerprint: String): Admission {
    val app = context.applicationContext
    val spec = check.spec ?: return Admission(AckEntry(AckKind.ERROR, check.order, "plan no válido: ${check.why ?: InstinctPlanSpec.INVALID}"))
    val commands = commands(spec) ?: return Admission(AckEntry(AckKind.ERROR, check.order, InstinctPlanSpec.INVALID))
    refusal(app, spec, commands)?.let { return Admission(AckEntry(AckKind.ERROR, check.order, it)) }
    if (execution == null || fingerprint.isEmpty() || !InstinctExecutions.begin(app, execution, null))
      return Admission(AckEntry(AckKind.ERROR, check.order, InstinctProposalCenter.NOT_RECORDED))
    val id = PlanIds.of(execution)
    val now = now()
    val plan = PlanRecord(id, execution, spec.line, source, InstinctLogEvents.messageId(message), fingerprint, PlanState.PENDING,
      createdAt = now, updatedAt = now, deadline = spec.expiresAt, owner = InstinctExecutionJournal.owner)
    return when (runCatching { journal(app).insertPlan(plan) }.getOrNull()) {
      InstinctExecutionJournal.PlanInsert.ADDED, InstinctExecutionJournal.PlanInsert.EXISTS -> {
        specs[id] = spec
        Admission(AckEntry(AckKind.OK, check.order, "Plan ${plan.shortId} aceptado: ${spec.summary}. El progreso y el resultado van a la bitácora.",
          informative = true), id)
      }
      InstinctExecutionJournal.PlanInsert.FULL ->
        Admission(AckEntry(AckKind.ERROR, check.order, "ya hay ${InstinctPlanSpec.MAX_ACTIVE} planes activos; cancelá alguno antes."))
      null -> Admission(AckEntry(AckKind.ERROR, check.order, InstinctProposalCenter.NOT_RECORDED))
    }
  }

  /** «probar plan: …»: the same checks and the same gate, consuming nothing. */
  internal fun probe(context: Context, check: PlanCheck, source: ProposalSource): InstinctProbe.Verdict {
    val app = context.applicationContext
    val spec = check.spec ?: return InstinctProbe.Verdict.Invalid("plan no válido: ${check.why ?: InstinctPlanSpec.INVALID}")
    val commands = commands(spec) ?: return InstinctProbe.Verdict.Invalid(InstinctPlanSpec.INVALID)
    refusal(app, spec, commands)?.let {
      return if (it == InstinctAutonomyPolicy.REFUSED) InstinctProbe.Verdict.Refused else InstinctProbe.Verdict.Invalid(it)
    }
    val label = "plan de ${spec.summary}"
    return when (val gate = InstinctGatePreview.preview(commands, false, InstinctProposalCenter.snapshot(app, foreground = false, source))) {
      InstinctAutonomyGate.Gate.Run -> InstinctProbe.Verdict.Alone(label)
      is InstinctAutonomyGate.Gate.Wait -> InstinctProbe.Verdict.Waits(label, gate.reason)
      is InstinctAutonomyGate.Gate.Refuse -> InstinctProbe.Verdict.Refused
    }
  }

  /** A plan from a backup, on «Reactivar» (1.23.0): parsed again now, never later than its original deadline. */
  internal fun restore(context: Context, line: String, deadline: Long): String? {
    val app = context.applicationContext
    val now = now()
    if (deadline <= now) return "ya venció."
    val parser = PhoneCommandParser()
    val calendar = calendarAt(now)
    val spec = (InstinctPlanSpec.parse(line, calendar) { InstinctProposalValidator.backgroundAction(it, parser, calendar) != null }
      as? InstinctPlanSpec.Parse.Valid)?.spec ?: return InstinctPlanSpec.INVALID
    val commands = commands(spec) ?: return InstinctPlanSpec.INVALID
    refusal(app, spec, commands)?.let { return it }
    val fingerprint = InstinctProposalCenter.fingerprint(app) ?: return InstinctProposalIntake.NO_CONTACT
    val id = PlanIds.of("restore:" + sha256("$line\u0000$deadline"))
    val plan = PlanRecord(id, null, spec.line, ProposalSource.BUZON, "", fingerprint, PlanState.PENDING, createdAt = now,
      updatedAt = now, deadline = minOf(deadline, spec.expiresAt), owner = InstinctExecutionJournal.owner)
    return when (runCatching { journal(app).insertPlan(plan) }.getOrNull()) {
      InstinctExecutionJournal.PlanInsert.ADDED, InstinctExecutionJournal.PlanInsert.EXISTS -> { start(app, id); null }
      InstinctExecutionJournal.PlanInsert.FULL -> "ya hay ${InstinctPlanSpec.MAX_ACTIVE} planes activos."
      null -> InstinctProposalCenter.NOT_RECORDED
    }
  }

  /** Plans that did not run anything yet: what a backup may carry (their line and deadline). */
  internal fun portable(context: Context): List<Pair<String, Long>> {
    val journal = InstinctExecutions.existingJournal(context) ?: return emptyList()
    return runCatching {
      journal.plans(active = true).filter {
        it.state in setOf(PlanState.PENDING, PlanState.CONFIRM, PlanState.WAITING) && it.block == 0 && it.sub == 0 && it.stash.isEmpty() &&
          journal.byOccurrence(it.id).none { row -> row.state != ExecutionState.RECEIVED && !InstinctQuestion.isRelated(row.line) }
      }.map { it.line to it.deadline }
    }.getOrDefault(emptyList())
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Running
  // ---------------------------------------------------------------------------------------------------------------

  /** Continues [id] off the caller. Safe to call any number of times: one runner per plan, the rest coalesce. */
  fun start(context: Context, id: String) {
    val app = context.applicationContext
    background(Runnable { advance(app, id) })
  }

  internal fun advance(app: Context, id: String) {
    if (!running.add(id)) { again += id; return }
    try {
      do {
        again.remove(id)
        runCatching { loop(app, id) }
      } while (again.remove(id))
    } finally { running.remove(id) }
    if (again.remove(id)) advance(app, id)
    rearm(app)
  }

  private fun spec(plan: PlanRecord): InstinctPlanSpec? = specs[plan.id] ?: (InstinctPlanSpec.parse(plan.line, calendarAt(plan.createdAt)) { true }
    as? InstinctPlanSpec.Parse.Valid)?.spec?.let { parsed -> parsed.copy(expiresAt = plan.deadline).also { specs[plan.id] = it } }

  private fun loop(app: Context, id: String) {
    val journal = journal(app)
    repeat(MAX_ITERATIONS) {
      var plan = journal.plan(id) ?: return
      if (plan.state.terminal) { close(app, plan); return }
      if (plan.owner != InstinctExecutionJournal.owner) {
        if (!journal.adoptPlan(plan)) return
        plan = journal.plan(id) ?: return
      }
      if (!InstinctProposalSettings(app).isEnabled()) { end(app, plan, PlanState.CANCELLED, SWITCHED_OFF, restore = false); return@repeat }
      if (now() >= plan.deadline) { end(app, plan, PlanState.EXPIRED, "venció antes de terminar."); return@repeat }
      val fingerprint = InstinctProposalCenter.awaitFingerprint(app)
      if (fingerprint == null) {
        // A cold start without preferences yet: nothing is lost, recovery comes back.
        if (com.example.data.AssistantPreferencesCache.of(app).current == null) { InstinctExecutionRecoveryManager.later(app); return }
        end(app, plan, PlanState.CANCELLED, InstinctProposalIntake.NO_CONTACT); return@repeat
      }
      if (fingerprint != plan.fingerprint) { end(app, plan, PlanState.CANCELLED, CHANGED); return@repeat }
      val spec = spec(plan) ?: run { end(app, plan, PlanState.FAILED, InstinctPlanSpec.INVALID); return@repeat }
      val proceed = when (plan.state) {
        PlanState.PENDING -> gate(app, plan, spec)
        PlanState.CONFIRM -> approval(app, plan)
        PlanState.DECIDING -> decision(app, plan, spec)
        PlanState.WAITING -> waiting(app, plan)
        PlanState.RUNNING -> run(app, plan, spec)
        else -> false
      }
      if (!proceed) return
    }
  }

  /** The plan as one order: «Nunca» refuses it, Confirmar or the brake ask once, Solo takes one unit of the brake. */
  private fun gate(app: Context, plan: PlanRecord, spec: InstinctPlanSpec): Boolean {
    val commands = commands(spec) ?: return end(app, plan, PlanState.FAILED, InstinctPlanSpec.INVALID)
    refusal(app, spec, commands)?.let { return end(app, plan, PlanState.FAILED, it) }
    val snapshot = InstinctProposalCenter.snapshot(app, foreground = false, plan.source)
    return when (val gate = InstinctGatePreview.decide(commands, false, snapshot) { InstinctProposalCenter.budget.tryAcquire() }) {
      is InstinctAutonomyGate.Gate.Refuse -> end(app, plan, PlanState.FAILED, gate.reason)
      is InstinctAutonomyGate.Gate.Wait -> askApproval(app, plan, spec, gate.reason)
      InstinctAutonomyGate.Gate.Run -> journal(app).updatePlan(plan.id, setOf(PlanState.PENDING)) {
        it.copy(state = PlanState.RUNNING, approval = PlanApproval.AUTO)
      } != null
    }
  }

  private fun run(app: Context, plan: PlanRecord, spec: InstinctPlanSpec): Boolean {
    if (plan.block >= spec.steps.size) return end(app, plan, PlanState.DONE, "completado.")
    val step = spec.steps[plan.block]
    return when (step) {
      is PlanStep.Act, is PlanStep.Branch, is PlanStep.Decide -> {
        if (step is PlanStep.Branch && plan.branch == null) return choose(app, plan, step)
        if (step is PlanStep.Decide && plan.branch == null) return askDecision(app, plan, step)
        val actions = step.branch(plan.branch)
        if (plan.sub >= actions.size) next(app, plan) else action(app, plan, actions[plan.sub])
      }
      is PlanStep.Wait -> startWait(app, plan, step, now() + step.millis, null, null)
      is PlanStep.WaitUntil -> {
        val at = InstinctTriggers.untilTime(step.minuteOfDay / 60, step.minuteOfDay % 60, calendarAt(now()))
          ?: return end(app, plan, PlanState.FAILED, "no se pudo calcular la hora de la espera.")
        startWait(app, plan, step, at, null, null)
      }
      is PlanStep.WaitFor -> waitFor(app, plan, step)
      is PlanStep.Save -> save(app, plan, step)
      is PlanStep.Restore -> {
        val targets = step.settings.ifEmpty { plan.stash.filterValues { !it.restored }.keys }
        val outcome = restoreSettings(app, plan, targets)
        record(app, plan, plan.position, step.text, "restaurado", outcome.summary)
        val moved = journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) {
          it.copy(stash = outcome.stash, block = it.block + 1, sub = 0, branch = null, fallback = false)
        }
        moved != null
      }
    }
  }

  private fun next(app: Context, plan: PlanRecord): Boolean = journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) {
    it.copy(block = it.block + 1, sub = 0, branch = null, fallback = false)
  } != null

  /** One action of the plan, with its journal row. */
  private fun action(app: Context, plan: PlanRecord, action: PlanAction): Boolean {
    val journal = journal(app)
    val message = PlanIds.step(plan.id, plan.block, plan.sub, plan.fallback)
    val line = if (plan.fallback) action.fallback ?: return end(app, plan, PlanState.FAILED, InstinctPlanSpec.INVALID) else action.line
    val rowId = ExecutionIds.of(message, 0)
    val row = runCatching { journal.get(rowId) }.getOrElse { return end(app, plan, PlanState.FAILED, InstinctPlanSpec.INVALID) }
    return when (val next = PlanStepPolicy.of(row, decode(row)?.let(ActionEffect::of))) {
      PlanStepPolicy.Next.Run -> runFresh(app, plan, action, line, message, row)
      PlanStepPolicy.Next.Verify -> readBack(app, plan, row!!)
      PlanStepPolicy.Next.Done -> done(app, plan, line, row!!)
      is PlanStepPolicy.Next.Failed -> failed(app, plan, action, line, next.reason)
      is PlanStepPolicy.Next.Uncertain -> {
        // Found «despachando» after a process death: the row says so too, and it is never dispatched again.
        if (row != null && !row.state.terminal) InstinctExecutions.move(app, row.executionId, ExecutionState.UNCERTAIN, reason = next.reason)
        uncertain(app, plan, line, next.reason)
      }
    }
  }

  private fun decode(row: ExecutionRecord?): PhoneCommand? =
    row?.command?.let { runCatching { PhoneCommandCodec.decode(JSONObject(it)) }.getOrNull() }

  private fun runFresh(app: Context, plan: PlanRecord, action: PlanAction, line: String, message: String, existing: ExecutionRecord?): Boolean {
    val journal = journal(app)
    val command = InstinctProposalValidator.backgroundAction(line, PhoneCommandParser(), calendarAt(now()))
      ?: return failed(app, plan, action, line, "la acción ya no es válida o necesita una pantalla.")
    val row = existing ?: when (val admitted = runCatching {
      journal.admit(message, ExecutionTransport.PLAN, listOf(line), plan.message, deadline = plan.deadline, occurrence = plan.id, claim = false)
    }.getOrNull()) {
      is InstinctExecutionJournal.Admission.Admitted -> admitted.records.single()
      // Admitted by another runner a moment ago: read it again on the next turn.
      InstinctExecutionJournal.Admission.Repeated -> return true
      null -> return end(app, plan, PlanState.FAILED, InstinctProposalCenter.NOT_RECORDED)
    }
    InstinctExecutions.move(app, row.executionId, ExecutionState.VALIDATED, from = setOf(ExecutionState.RECEIVED))
    if (InstinctProposalCenter.refusedByPolicy(app, command)) {
      InstinctExecutions.move(app, row.executionId, ExecutionState.CANCELLED, reason = InstinctAutonomyPolicy.REFUSED)
      return failed(app, plan, action, line, InstinctAutonomyPolicy.REFUSED)
    }
    // Authorized by Solo when the plan started: a category moved to Confirmar since then, or quiet hours, ask now.
    if (plan.approval != PlanApproval.PERSON) when (val decision = InstinctProposalCenter.policy(app, command)) {
      is AutonomyDecision.Confirm -> return askApproval(app, plan, spec(plan) ?: return false, decision.reason)
      is AutonomyDecision.Refuse -> return failed(app, plan, action, line, decision.reason)
      AutonomyDecision.Run -> Unit
    }
    InstinctProposalCenter.blocker(app, command, foreground = false)?.let { return failed(app, plan, action, line, it) }
    InstinctExecutions.move(app, row.executionId, ExecutionState.READY, from = setOf(ExecutionState.VALIDATED, ExecutionState.RECEIVED))
    // «despachando» on disk before touching the phone; without it nothing runs.
    if (!InstinctExecutions.begin(app, row.executionId, command)) return end(app, plan, PlanState.FAILED, InstinctProposalCenter.NOT_RECORDED)
    val verifier = InstinctProposalCenter.outcomeVerifier(app)
    val before = runCatching { verifier.read(command) }.getOrNull()
    val result = runCatching { execute(app, command) }.getOrElse {
      PhoneActionResult("La acción se interrumpió.", outcome = PhoneActionOutcome.UNCERTAIN)
    }
    when {
      result.outcome == PhoneActionOutcome.UNCERTAIN -> {
        InstinctExecutions.move(app, row.executionId, ExecutionState.UNCERTAIN, reason = result.message)
        return uncertain(app, plan, line, result.message)
      }
      result.outcome == PhoneActionOutcome.REJECTED || InstinctAck.needsUser(result) -> {
        // A missing permission is noted for Settings; nothing is asked in the background, nothing is retried.
        InstinctReadiness.noteMissing(app, result)
        InstinctExecutions.move(app, row.executionId, ExecutionState.FAILED, reason = result.message)
        return failed(app, plan, action, line, result.message)
      }
    }
    InstinctExecutions.move(app, row.executionId, ExecutionState.VERIFYING)
    val observation = runCatching { verifier.verify(command, before, result) }
      .getOrDefault(Observation(OutcomeVerdict.NOT_OBSERVABLE, detail = ActionOutcomeRules.HANDED_OFF))
    val entry = ActionOutcomeRules.ack(line, result, observation)
    val state = ActionOutcomeRules.state(observation)
    InstinctExecutions.move(app, row.executionId, state, result = entry.message,
      reason = observation.detail.takeIf { state == ExecutionState.FAILED || state == ExecutionState.UNCERTAIN },
      attribution = observation.attribution.takeIf { state == ExecutionState.VERIFIED })
    return when (state) {
      ExecutionState.VERIFIED, ExecutionState.HANDED_OFF -> {
        InstinctTrust.record(app, command, if (plan.approval == PlanApproval.PERSON) TrustEvent.CONFIRMADA else TrustEvent.EJECUTADA)
        done(app, plan, line, journal.get(row.executionId) ?: row)
      }
      ExecutionState.UNCERTAIN -> uncertain(app, plan, line, entry.message)
      else -> failed(app, plan, action, line, entry.message)
    }
  }

  /** Interrupted while it ran: read back, never run again (1.20.0's rule). */
  private fun readBack(app: Context, plan: PlanRecord, row: ExecutionRecord): Boolean {
    val command = decode(row)
    val verdict = command?.let { c ->
      runCatching { InstinctProposalCenter.outcomeVerifier(app).verify(c, null, PhoneActionResult("", outcome = PhoneActionOutcome.COMPLETED)) }.getOrNull()?.verdict
    } ?: OutcomeVerdict.INDETERMINATE
    val line = row.line
    return when (val state = ExecutionRecoveryPolicy.afterVerify(row, verdict)) {
      ExecutionState.VERIFIED, ExecutionState.HANDED_OFF -> {
        InstinctExecutions.move(app, row.executionId, state, result = if (state == ExecutionState.VERIFIED) ExecutionRecoveryPolicy.SATISFIED
          else ActionOutcomeRules.HANDED_OFF, attribution = ExecutionAttribution.SATISFIED.takeIf { state == ExecutionState.VERIFIED })
        done(app, plan, line, journal(app).get(row.executionId) ?: row)
      }
      else -> {
        InstinctExecutions.move(app, row.executionId, ExecutionState.UNCERTAIN, reason = ExecutionRecoveryPolicy.INTERRUPTED)
        uncertain(app, plan, line, ExecutionRecoveryPolicy.INTERRUPTED)
      }
    }
  }

  private fun done(app: Context, plan: PlanRecord, line: String, row: ExecutionRecord): Boolean {
    // What the plan itself left in a saved setting: a restoration compares against it.
    val touched = decode(row)?.let(PlanStepPolicy::touches).orEmpty()
    val stash = plan.stash.mapValues { (setting, s) ->
      if (setting in touched && !s.restored) readSetting(app, setting)?.let { s.copy(applied = it) } ?: s else s
    }
    record(app, plan, plan.position, line, "ok", row.result ?: row.state.code, row.executionId)
    return journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) {
      it.copy(sub = it.sub + 1, fallback = false, stash = stash)
    } != null
  }

  /** Not done: the explicit alternative, if there is one and it was not the alternative already; otherwise the plan stops. */
  private fun failed(app: Context, plan: PlanRecord, action: PlanAction, line: String, reason: String): Boolean {
    record(app, plan, plan.position, line, "fallida", reason)
    if (!plan.fallback && action.fallback != null)
      return journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) { it.copy(fallback = true) } != null
    return end(app, plan, PlanState.FAILED, "el paso ${plan.position} no se hizo: ${reason.trimEnd('.')}.")
  }

  /** Maybe it happened: never repeated. The plan waits past it until it is resumed or cancelled. */
  private fun uncertain(app: Context, plan: PlanRecord, line: String, reason: String): Boolean {
    record(app, plan, plan.position, line, "incierta", reason)
    val why = "el paso ${plan.position} quedó incierto (${InstinctAck.flat(reason).trimEnd('.')}); no se repite. Reanudá o cancelá el plan."
    val suspended = journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) {
      it.copy(state = PlanState.SUSPENDED, sub = it.sub + 1, fallback = false, reason = why)
    } ?: return true
    record(app, suspended, "estado", "plan", PlanState.SUSPENDED.code, why)
    flushLog(app, suspended.id)
    InstinctProposalCenter.notifyPlan(app, suspended.id, "Plan ${suspended.shortId} suspendido", InstinctProposalCenter.sentence(why))
    return false
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Conditions, questions, waits
  // ---------------------------------------------------------------------------------------------------------------

  private fun choose(app: Context, plan: PlanRecord, step: PlanStep.Branch): Boolean {
    val state = runCatching { snapshot(app, step.conditions.mapTo(HashSet()) { it.signal }) }.getOrNull()
    val matches = step.conditions.map { condition -> state?.let(condition::matches) }
    // Without data or permission nothing is guessed.
    if (null in matches) return end(app, plan, PlanState.FAILED, "una condición del paso ${plan.block + 1} no tiene datos o falta un permiso; no se adivina.")
    val branch = if (matches.all { it == true }) 0 else 1
    record(app, plan, "${plan.block + 1}", step.text, "condición", if (branch == 0) "se cumple" else "no se cumple")
    return journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) { it.copy(branch = branch, sub = 0) } != null
  }

  /** One question row of the existing questions, with a deterministic id: asking twice reuses it. */
  private fun ask(app: Context, plan: PlanRecord, kind: String, line: String, question: InstinctQuestion, preview: Boolean): String? {
    val journal = journal(app)
    val message = PlanIds.question(plan.id, plan.block, plan.sub, kind)
    val rowId = ExecutionIds.of(message, 0)
    var row = journal.get(rowId) ?: (runCatching {
      journal.admit(message, ExecutionTransport.PLAN, listOf(line), plan.message, deadline = question.expiresAt, occurrence = plan.id, claim = false)
    }.getOrNull() as? InstinctExecutionJournal.Admission.Admitted)?.records?.single() ?: return null
    // Created by a process that died before showing it: it is shown now, once.
    if (row.state == ExecutionState.RECEIVED) {
      InstinctExecutions.move(app, row.executionId, ExecutionState.VALIDATED, from = setOf(ExecutionState.RECEIVED))
      row = journal.get(rowId) ?: return null
    }
    if (row.state == ExecutionState.VALIDATED) {
      when (val started = runCatching { journal.startQuestion(row.executionId, question, preview) }.getOrNull()) {
        InstinctExecutionJournal.QuestionAdmission.Accepted -> Unit
        is InstinctExecutionJournal.QuestionAdmission.Refused -> {
          InstinctExecutions.move(app, row.executionId, ExecutionState.FAILED, reason = started.reason)
          end(app, plan, PlanState.FAILED, "no se pudo preguntar: ${started.reason}.")
          return null
        }
        else -> { end(app, plan, PlanState.FAILED, InstinctProposalCenter.NOT_RECORDED); return null }
      }
    }
    return rowId
  }

  private fun askApproval(app: Context, plan: PlanRecord, spec: InstinctPlanSpec, reason: String): Boolean {
    if (InstinctAutonomySettings(app).policy().level(InstinctCategory.PREGUNTAS) == InstinctAutonomy.NUNCA)
      return end(app, plan, PlanState.FAILED, "esperaría tu confirmación (${reason.trimEnd('.')}), pero $NEEDS_QUESTIONS")
    val left = spec.steps.drop(plan.block).joinToString("; ") { it.text }
    val text = InstinctAck.cut(InstinctAck.flat("Instinct quiere seguir con el plan ${plan.shortId} (${reason.trimEnd('.')}): $left"), 300)
    val expires = minOf(plan.deadline, now() + ProposalBook.TTL_MILLIS)
    val question = InstinctQuestion(text, listOf(APPROVE, DECLINE), expires)
    val id = ask(app, plan, "a", "preguntar ¿Ejecutar el plan ${plan.shortId}? | $APPROVE | $DECLINE", question, preview = false) ?: return false
    journal(app).updatePlan(plan.id, setOf(PlanState.PENDING, PlanState.RUNNING, PlanState.WAITING)) {
      it.copy(state = PlanState.CONFIRM, question = id, reason = reason)
    } ?: return true
    record(app, plan, "${plan.block + 1}", "confirmación", PlanState.CONFIRM.code, reason)
    InstinctQuestionNotifications.refresh(app)
    return false
  }

  private fun askDecision(app: Context, plan: PlanRecord, step: PlanStep.Decide): Boolean {
    val policy = InstinctAutonomySettings(app).policy()
    if (policy.level(InstinctCategory.PREGUNTAS) == InstinctAutonomy.NUNCA) return end(app, plan, PlanState.FAILED, NEEDS_QUESTIONS)
    val preview = policy.level(InstinctCategory.PREGUNTAS) == InstinctAutonomy.CONFIRMAR ||
      InstinctSourceCap.apply(AutonomyDecision.Run, plan.source, InstinctMailboxSettings(app).whatsappCap()) is AutonomyDecision.Confirm
    val expires = minOf(plan.deadline, now() + InstinctQuestion.DEFAULT_HOURS * InstinctQuestion.HOUR)
    val question = InstinctQuestion(step.question, step.options, expires)
    val id = ask(app, plan, "d", "decidir ${step.question} | ${step.options.joinToString(" | ")}", question, preview) ?: return false
    journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) { it.copy(state = PlanState.DECIDING, question = id) } ?: return true
    record(app, plan, "${plan.block + 1}", step.text, PlanState.DECIDING.code, "esperando tu respuesta")
    InstinctQuestionNotifications.refresh(app)
    return false
  }

  private fun approval(app: Context, plan: PlanRecord): Boolean {
    val row = plan.question?.let { journal(app).get(it) } ?: return end(app, plan, PlanState.FAILED, "se perdió la confirmación.")
    return when (row.state) {
      ExecutionState.ANSWERED -> if (row.result == APPROVE) {
        record(app, plan, "${plan.block + 1}", "confirmación", "aprobado", "lo aprobaste")
        journal(app).updatePlan(plan.id, setOf(PlanState.CONFIRM)) {
          it.copy(state = PlanState.RUNNING, approval = PlanApproval.PERSON, question = null, reason = null)
        } != null
      } else end(app, plan, PlanState.CANCELLED, "no lo aprobaste.")
      ExecutionState.EXPIRED -> end(app, plan, PlanState.EXPIRED, "venció sin que se confirmara.")
      ExecutionState.CANCELLED -> end(app, plan, PlanState.CANCELLED, "descartaste la confirmación.")
      in InstinctQuestion.PENDING -> false
      else -> end(app, plan, PlanState.FAILED, row.reason ?: "no se pudo confirmar.")
    }
  }

  private fun decision(app: Context, plan: PlanRecord, spec: InstinctPlanSpec): Boolean {
    val step = spec.steps.getOrNull(plan.block) as? PlanStep.Decide ?: return end(app, plan, PlanState.FAILED, InstinctPlanSpec.INVALID)
    val row = plan.question?.let { journal(app).get(it) } ?: return end(app, plan, PlanState.FAILED, "se perdió la pregunta.")
    return when (row.state) {
      ExecutionState.ANSWERED -> {
        val branch = step.options.indexOf(row.result)
        if (branch < 0) return end(app, plan, PlanState.FAILED, "respuesta desconocida.")
        record(app, plan, "${plan.block + 1}", step.text, "respondida", step.options[branch])
        journal(app).updatePlan(plan.id, setOf(PlanState.DECIDING)) {
          it.copy(state = PlanState.RUNNING, branch = branch, sub = 0, question = null)
        } != null
      }
      ExecutionState.EXPIRED -> end(app, plan, PlanState.EXPIRED, "la pregunta venció sin respuesta.")
      ExecutionState.CANCELLED -> end(app, plan, PlanState.CANCELLED, "descartaste la pregunta.")
      in InstinctQuestion.PENDING -> false
      else -> end(app, plan, PlanState.FAILED, row.reason ?: "no se pudo preguntar.")
    }
  }

  private fun startWait(app: Context, plan: PlanRecord, step: PlanStep, at: Long?, trigger: PlanTrigger?, event: PlanEvent?): Boolean {
    val moved = journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) {
      it.copy(state = PlanState.WAITING, wakeAt = at, trigger = trigger, event = event ?: it.event)
    } ?: return true
    val until = at?.let { "hasta las ${InstinctDeferral.clock(it, calendarAt(it))}" } ?: trigger?.phrase.orEmpty()
    record(app, moved, plan.position, step.text, PlanState.WAITING.code, until)
    return false
  }

  private fun waitFor(app: Context, plan: PlanRecord, step: PlanStep.WaitFor): Boolean {
    val trigger = step.trigger
    if (!trigger.byEvent) return startWait(app, plan, step, null, trigger, null)
    val now = now()
    val events = runCatching { InstinctScheduler.calendar(app, now) }.getOrNull()
      ?: return end(app, plan, PlanState.FAILED, "Insto no tiene permiso para leer el calendario.")
    // «al terminar» right after «al empezar»: the same event that started, if it is in progress.
    val same = plan.event?.takeIf { trigger == PlanTrigger.EVENT_END && it.trigger == PlanTrigger.EVENT_START }?.let { started ->
      events.firstOrNull { it.eventId == started.eventId && !it.allDay && it.begin <= now && it.end > now }
    }
    if (same != null) return startWait(app, plan, step, same.end, trigger, PlanEvent(trigger, same.eventId, same.instanceId, same.end))
    val wanted = if (trigger == PlanTrigger.EVENT_END) Trigger.EventEnd() else Trigger.EventStart()
    return when (val pick = InstinctTriggers.pickEvent(wanted, events, now, plan.deadline)) {
      is InstinctTriggers.Pick.None -> end(app, plan, PlanState.FAILED, pick.why)
      is InstinctTriggers.Pick.Found -> {
        val (eventId, instanceId) = when (val t = pick.trigger) {
          is Trigger.EventEnd -> t.eventId to t.instanceId
          is Trigger.EventStart -> t.eventId to t.instanceId
          else -> 0L to 0L
        }
        startWait(app, plan, step, pick.fireAt, trigger, PlanEvent(trigger, eventId, instanceId, pick.fireAt))
      }
    }
  }

  private fun waiting(app: Context, plan: PlanRecord): Boolean {
    val wake = plan.wakeAt ?: return false
    val now = now()
    if (now < wake) return false
    val event = plan.event?.takeIf { plan.trigger?.byEvent == true && it.trigger == plan.trigger }
    if (event != null) {
      val candidates = runCatching { InstinctScheduler.calendar(app, now) }.getOrNull()
        ?: return end(app, plan, PlanState.FAILED, "Insto ya no puede leer el calendario.")
      val stored = if (event.trigger == PlanTrigger.EVENT_END) Trigger.EventEnd(event.eventId, event.instanceId, event.at)
        else Trigger.EventStart(event.eventId, event.instanceId, event.at)
      when (val check = InstinctTriggers.matchEvent(stored, candidates)) {
        InstinctTriggers.EventCheck.Deleted -> return end(app, plan, PlanState.FAILED, "el evento se borró.")
        is InstinctTriggers.EventCheck.Moved -> if (check.at > now) {
          journal(app).updatePlan(plan.id, setOf(PlanState.WAITING)) { it.copy(wakeAt = check.at, event = event.copy(at = check.at)) }
          return false
        }
        InstinctTriggers.EventCheck.Same -> Unit
      }
    }
    // A wait the phone delivered late (Doze, the phone off) does not go on alone: as a late deferred order, it asks.
    val late = plan.trigger?.let { !it.byEvent } != true && now - wake > InstinctDeferral.LATE_MILLIS && plan.approval != PlanApproval.PERSON
    val moved = journal(app).updatePlan(plan.id, setOf(PlanState.WAITING)) {
      it.copy(state = PlanState.RUNNING, block = it.block + 1, sub = 0, branch = null, fallback = false, wakeAt = null, trigger = null)
    } ?: return true
    record(app, moved, plan.position, "espera", "cumplida", if (late) "llegó tarde" else "")
    if (late) return askApproval(app, moved, spec(moved) ?: return false, "la espera terminó con más de ${InstinctDeferral.LATE_MILLIS / 60_000} minutos de atraso")
    return true
  }

  /** «Insto · notificaciones» saw the phone do something: plans waiting for it go on. */
  fun onPhoneEvent(context: Context, event: PhoneEvent) {
    val app = context.applicationContext
    val journal = InstinctExecutions.existingJournal(app) ?: return
    background(Runnable {
      val waiting = runCatching { journal.plans(active = true) }.getOrDefault(emptyList())
        .filter { it.state == PlanState.WAITING && it.wakeAt == null && it.trigger?.fires(event) == true }
      waiting.forEach { plan ->
        journal.updatePlan(plan.id, setOf(PlanState.WAITING)) { it.copy(wakeAt = now()) }
        advance(app, plan.id)
      }
    })
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Saving and restoring settings
  // ---------------------------------------------------------------------------------------------------------------

  private fun probeCommand(setting: PlanSetting): PhoneCommand = when (setting) {
    PlanSetting.VOLUME -> PhoneCommand.Volume(VolumeOp.SET, 0)
    PlanSetting.RINGER -> PhoneCommand.Platform(PlatformAction.SILENT_ON)
    PlanSetting.DND -> PhoneCommand.Platform(PlatformAction.DND_ON)
  }

  /** The value of [setting] now, with the same reads as «Deshacer». */
  internal fun readSetting(context: Context, setting: PlanSetting): Int? =
    InstinctUndoActions.capture(context.applicationContext, probeCommand(setting))?.before

  private fun save(app: Context, plan: PlanRecord, step: PlanStep.Save): Boolean {
    val values = step.settings.associateWith { readSetting(app, it) }
    val unreadable = values.filterValues { it == null }.keys
    if (unreadable.isNotEmpty())
      return end(app, plan, PlanState.FAILED, "no se pudo leer ${unreadable.joinToString(" ni ") { it.label }}; no se guardó nada.")
    record(app, plan, plan.position, step.text, "guardado", step.settings.joinToString(", ") { it.label })
    return journal(app).updatePlan(plan.id, setOf(PlanState.RUNNING)) {
      it.copy(stash = it.stash + values.mapValues { (_, v) -> PlanStash(v!!) }, block = it.block + 1, sub = 0, branch = null)
    } != null
  }

  internal data class RestoreOutcome(val stash: Map<PlanSetting, PlanStash>, val summary: String)

  /**
   * Puts [targets] back with [InstinctUndoActions.revert]: only while the setting still is what the plan left (or what
   * it was when saved). A change made afterwards is left as it is and said. Safe to repeat after a process death.
   */
  internal fun restoreSettings(context: Context, plan: PlanRecord, targets: Set<PlanSetting>): RestoreOutcome {
    val app = context.applicationContext
    val stash = plan.stash.toMutableMap()
    val restored = ArrayList<String>(); val kept = ArrayList<String>(); val refused = ArrayList<String>()
    for (setting in targets) {
      val saved = stash[setting]?.takeIf { !it.restored } ?: continue
      when (val decision = PlanRestorePolicy.decide(saved, readSetting(app, setting))) {
        PlanRestorePolicy.Decision.Already -> restored += setting.label
        is PlanRestorePolicy.Decision.Write -> {
          val expected = saved.applied ?: saved.before
          val command = probeCommand(setting)
          val reversal = when (setting) {
            PlanSetting.VOLUME -> Reversal.Volume(command, decision.value, expected)
            PlanSetting.RINGER -> Reversal.Ringer(command, decision.value, expected)
            PlanSetting.DND -> Reversal.Interruptions(command, decision.value, expected)
          }
          if (InstinctUndoActions.revert(app, reversal) && readSetting(app, setting) == decision.value) restored += setting.label
          else refused += setting.label
        }
        PlanRestorePolicy.Decision.UserChanged -> kept += setting.label
        PlanRestorePolicy.Decision.Unreadable -> refused += setting.label
      }
      stash[setting] = saved.copy(restored = true)
    }
    val summary = listOfNotNull(
      restored.takeIf { it.isNotEmpty() }?.let { "restauré ${it.joinToString(", ")}" },
      kept.takeIf { it.isNotEmpty() }?.let { "no toqué lo que cambiaste después: ${it.joinToString(", ")}" },
      refused.takeIf { it.isNotEmpty() }?.let { "Android no permitió restaurar ${it.joinToString(", ")}" },
    ).joinToString("; ").ifEmpty { "no había nada para restaurar" }
    return RestoreOutcome(stash, summary)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Ending, the log and the notification
  // ---------------------------------------------------------------------------------------------------------------

  /** Ends [plan] as [state] (if nothing else ended it first) and does its final work. Returns true to re-read it. */
  private fun end(app: Context, plan: PlanRecord, state: PlanState, why: String, restore: Boolean = true): Boolean {
    val ended = journal(app).updatePlan(plan.id) {
      it.copy(state = state, reason = why.takeIf { state != PlanState.DONE }, result = why.takeIf { state == PlanState.DONE },
        restoreOnClose = it.restoreOnClose && restore)
    }
    if (ended != null) close(app, ended)
    return true
  }

  /** Final work of a terminal plan, once: the pending question, what it saved, the last log event, the notification. */
  private fun close(app: Context, plan: PlanRecord) {
    if (plan.closed || !plan.state.terminal) return
    val journal = journal(app)
    plan.question?.let { id ->
      if (journal.get(id)?.state in InstinctQuestion.PENDING) runCatching { journal.finishQuestion(id, InstinctQuestion.DISCARD) }
      InstinctQuestionNotifications.refresh(app)
    }
    var summary = ""
    val pending = plan.stash.filterValues { !it.restored }.keys
    if (pending.isNotEmpty() && plan.restoreOnClose) {
      val outcome = restoreSettings(app, plan, pending)
      journal.updatePlan(plan.id, setOf(plan.state)) { it.copy(stash = outcome.stash) }
      summary = outcome.summary
    }
    val text = listOfNotNull((plan.result ?: plan.reason)?.trimEnd('.'), summary.takeIf(String::isNotEmpty)).joinToString("; ")
    record(app, plan, "fin", "plan", plan.state.code, text)
    flushLog(app, plan.id)
    val title = when (plan.state) {
      PlanState.DONE -> "Instinct completó el plan ${plan.shortId}"
      else -> "Plan ${plan.shortId} ${plan.state.code}"
    }
    InstinctProposalCenter.notifyPlan(app, plan.id, title, InstinctProposalCenter.sentence(text.ifEmpty { plan.state.code }))
    InstinctStatePublisher.afterOrder(app)
    runCatching { journal.closePlan(plan.id) }
    specs.remove(plan.id)
  }

  private fun record(app: Context, plan: PlanRecord, position: String, text: String, status: String, detail: String, execution: String? = null) {
    runCatching { journal(app).putPlanStep(PlanStepRecord(plan.id, position, text, status, detail, execution)) }
    flushLog(app, plan.id)
  }

  /**
   * Progress to the log: one event of kind «plan» per step that changed, with a stable id (the script confirms a
   * repeated id without writing it again). Only with the log configured; never to the WhatsApp chat.
   */
  internal fun flushLog(app: Context, id: String) {
    if (!InstinctLogSettings(app).configured()) return
    val journal = journal(app)
    val plan = runCatching { journal.plan(id) }.getOrNull() ?: return
    val steps = runCatching { journal.planSteps(id) }.getOrDefault(emptyList()).filterNot { it.reported }
    if (steps.isEmpty()) return
    val events = steps.mapNotNull { step ->
      InstinctLogEvent(sha256("${plan.id}\u0000${step.position}\u0000${step.status}\u0000${step.updatedAt}").take(32),
        InstinctLogEvents.iso(step.updatedAt), LOG_KIND,
        InstinctAck.cut(InstinctAck.flat("plan ${plan.shortId} · ${step.position} · ${step.text}"), InstinctAck.MAX_ORDER_CHARS),
        InstinctAck.cut(InstinctAck.flat(listOf(step.status, step.detail).filter(String::isNotBlank).joinToString(" · ")), InstinctAck.MAX_CHARS),
        plan.message, InstinctProtocolCatalog.TITLE).takeIf(InstinctLogEvents::safe)
    }
    InstinctLog.enqueueEvents(app, events)
    runCatching { journal.markPlanStepsReported(id, steps.map { it.position }) }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Queries and management
  // ---------------------------------------------------------------------------------------------------------------

  private fun total(plan: PlanRecord): Int? = spec(plan)?.steps?.size

  fun describe(context: Context): String {
    val journal = InstinctExecutions.existingJournal(context) ?: return PlanText.list(emptyList())
    return PlanText.list(journal.plans(limit = ExecutionJournalPolicy.LIST).map { it to total(it) })
  }

  fun status(context: Context, short: String): String {
    val journal = InstinctExecutions.existingJournal(context) ?: return PlanText.status(null, emptyList(), short, null)
    val plan = journal.planByShort(short)
    return PlanText.status(plan, plan?.let { journal.planSteps(it.id) }.orEmpty(), short, plan?.let(::total))
  }

  fun plans(context: Context): List<PlanRecord> =
    runCatching { InstinctExecutions.existingJournal(context)?.plans(limit = 20).orEmpty() }.getOrDefault(emptyList())

  /** «cancelar plan <id>» and Settings: ends it now, between two actions; what it saved is put back. */
  fun cancel(context: Context, short: String): String {
    val app = context.applicationContext
    val journal = InstinctExecutions.existingJournal(app) ?: return "No hay ningún plan $short."
    val plan = journal.planByShort(short) ?: return "No hay ningún plan $short."
    if (plan.state.terminal) return "El plan ${plan.shortId} ya terminó: ${plan.state.code}."
    journal.updatePlan(plan.id) { it.copy(state = PlanState.CANCELLED, reason = "lo cancelaron.") }
      ?: return "El plan ${plan.shortId} ya terminó."
    start(app, plan.id)
    return "Se canceló el plan ${plan.shortId}. Lo que guardó se restaura si nadie lo cambió."
  }

  /** «reanudar plan <id>»: a suspended plan goes on after its uncertain step, which is never repeated. */
  fun resume(context: Context, short: String): String {
    val app = context.applicationContext
    val journal = InstinctExecutions.existingJournal(app) ?: return "No hay ningún plan $short."
    val plan = journal.planByShort(short) ?: return "No hay ningún plan $short."
    val resumed = journal.updatePlan(plan.id, setOf(PlanState.SUSPENDED)) { it.copy(state = PlanState.RUNNING, reason = null) }
      ?: return "El plan ${plan.shortId} no está suspendido (${plan.state.code}); no hay nada para reanudar."
    record(app, resumed, "estado", "plan", "reanudado", "sigue en el paso ${resumed.position}")
    start(app, plan.id)
    return "Se reanudó el plan ${plan.shortId}: sigue en el paso ${resumed.position}; el paso incierto no se repite."
  }

  /** «Recibir órdenes» turned off: every plan ends now, without touching the phone again. */
  fun stopAll(context: Context) {
    val app = context.applicationContext
    val journal = InstinctExecutions.existingJournal(app) ?: return
    runCatching { journal.plans(active = true) }.getOrDefault(emptyList()).forEach { plan ->
      journal.updatePlan(plan.id) { it.copy(state = PlanState.CANCELLED, reason = SWITCHED_OFF, restoreOnClose = false) }
      start(app, plan.id)
    }
  }

  /** The question [id] ended (answered, discarded, expired): its plan goes on. */
  internal fun questionSettled(context: Context, id: String) {
    val app = context.applicationContext
    val row = runCatching { InstinctExecutions.existingJournal(app)?.get(id) }.getOrNull() ?: return
    if (row.transport != ExecutionTransport.PLAN) return
    row.occurrence?.let { start(app, it) }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Recovery and the alarm
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * After a process death, a boot or an alarm: every active plan continues from its row (a dead process's plan is
   * adopted first), unfinished final work is finished, and progress the log did not get yet is queued again.
   */
  fun recover(context: Context) {
    val app = context.applicationContext
    val journal = InstinctExecutions.existingJournal(app) ?: return
    runCatching { journal.unclosedPlans() }.getOrDefault(emptyList()).forEach { start(app, it.id) }
    runCatching { journal.plans(active = true) }.getOrDefault(emptyList()).forEach { start(app, it.id) }
    background(Runnable { rearm(app) })
  }

  /** One shared alarm: the next wait or deadline of any active plan. */
  fun rearm(context: Context) {
    val app = context.applicationContext
    val journal = InstinctExecutions.existingJournal(app) ?: return
    val now = now()
    val next = runCatching { journal.plans(active = true) }.getOrDefault(emptyList())
      .flatMap { listOfNotNull(it.deadline, it.wakeAt?.takeIf { _ -> it.state == PlanState.WAITING }) }
      .map { maxOf(it, now + 1_000) }.minOrNull()
    runCatching { alarms(app, next) }
  }

  private fun arm(app: Context, at: Long?) {
    val manager = app.getSystemService(AlarmManager::class.java) ?: return
    val pending = PendingIntent.getBroadcast(app, 0, Intent(app, InstinctScheduleReceiver::class.java).setAction(ACTION_TICK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    if (at == null) { manager.cancel(pending); return }
    if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
      try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); return }
      catch (_: SecurityException) { /* The permission can change after the check. */ }
    }
    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
  }

  /** Tests only. */
  internal fun reset() { running.clear(); again.clear(); specs.clear() }
}
