package com.example.phone

import java.util.UUID

enum class StepStatus { PENDING, EXECUTING, COMPLETED, HANDED_OFF, WAITING_INPUT, FAILED, UNCERTAIN, CANCELLED }
data class WorkflowStep(val command: PhoneCommand, val status: StepStatus = StepStatus.PENDING, val message: String = "")
data class WorkflowSnapshot(val id: String = UUID.randomUUID().toString(), val steps: List<WorkflowStep>, val cursor: Int = 0, val confirmed: Boolean = false, val cancelled: Boolean = false) {
  val finished: Boolean get() = cancelled || cursor >= steps.size
}

/** The pre-dispatch write is synchronous. Persistence failure prevents the side effect. */
class WorkflowRunner(initial: WorkflowSnapshot? = null, private val save: (WorkflowSnapshot?) -> Unit = {}) {
  var snapshot: WorkflowSnapshot? = initial?.let { old -> old.copy(steps = old.steps.map {
    if (it.status == StepStatus.EXECUTING) it.copy(status = StepStatus.UNCERTAIN, message = "La ejecución se interrumpió. Revisá la app antes de continuar.") else it
  }) }; private set
  init { if (initial != null) save(snapshot) }
  private fun update(value: WorkflowSnapshot?) { save(value); snapshot = value }
  fun prepare(commands: List<PhoneCommand>) {
    require(commands.size in 1..10)
    // SMN queries are asynchronous answers, not steps with an Android result.
    require(commands.none(::isWeatherQuery)) { "Las consultas al SMN no forman parte de secuencias" }
    update(WorkflowSnapshot(steps = commands.map { WorkflowStep(it) }))
  }
  fun invalidatePreview() {if(snapshot?.confirmed == false) clear()}
  fun confirm() { snapshot?.let { update(it.copy(confirmed = true)) } }
  fun next(): PhoneCommand? {
    val s = snapshot ?: return null
    if (!s.confirmed || s.finished) return null
    val step = s.steps[s.cursor]
    if (step.status != StepStatus.PENDING) return null
    replace(step.copy(status = StepStatus.EXECUTING))
    return step.command
  }
  fun beginReplacement(command: PhoneCommand) {
    val s = snapshot ?: return
    if (!s.finished && s.confirmed) replace(s.steps[s.cursor].copy(command = command, status = StepStatus.EXECUTING))
  }
  fun accept(result: PhoneActionResult) {
    val s = snapshot ?: return
    if (s.finished) return
    val step = s.steps[s.cursor]
    val status = when {
      result.stopwatchAutomation != null -> StepStatus.EXECUTING
      result.outcome == PhoneActionOutcome.NEEDS_INPUT -> StepStatus.WAITING_INPUT
      result.outcome == PhoneActionOutcome.UNCERTAIN -> StepStatus.UNCERTAIN
      result.outcome == PhoneActionOutcome.REJECTED -> StepStatus.FAILED
      result.outcome == PhoneActionOutcome.HANDOFF -> StepStatus.HANDED_OFF
      else -> StepStatus.COMPLETED
    }
    replace(step.copy(status = status, message = result.message), advance = status == StepStatus.COMPLETED)
  }
  fun resumeHandoff() {
    val s = snapshot ?: return
    if (!s.finished && s.steps[s.cursor].status == StepStatus.HANDED_OFF) update(s.copy(cursor = s.cursor + 1))
  }
  fun retry() {
    val s = snapshot ?: return
    if (!s.finished && s.steps[s.cursor].status in listOf(StepStatus.FAILED, StepStatus.WAITING_INPUT))
      replace(s.steps[s.cursor].copy(status = StepStatus.PENDING))
  }
  /** Explicitly acknowledge an uncertain effect; never replay it. */
  fun acknowledgeUncertain() {
    val s = snapshot ?: return
    if (!s.finished && s.steps[s.cursor].status == StepStatus.UNCERTAIN) update(s.copy(cursor = s.cursor + 1))
  }
  fun markUncertainInMemory() {
    val s=snapshot?:return
    if(!s.finished) snapshot=s.copy(steps=s.steps.mapIndexed {i,step -> if(i==s.cursor)step.copy(status=StepStatus.UNCERTAIN,message="No se pudo guardar el resultado; revisá la app.") else step})
  }
  fun cancel() { snapshot?.let { original ->
    val cancelled=original.copy(cancelled = true,steps=original.steps.map {step ->
      when(step.status){StepStatus.PENDING,StepStatus.FAILED,StepStatus.WAITING_INPUT -> step.copy(status=StepStatus.CANCELLED);StepStatus.EXECUTING -> step.copy(status=StepStatus.UNCERTAIN);else -> step}
    })
    snapshot=cancelled
    save(cancelled)
  } }
  fun clear() { update(null) }
  private fun replace(step: WorkflowStep, advance: Boolean = false) {
    val s = snapshot ?: return
    update(s.copy(steps = s.steps.mapIndexed { i, old -> if (i == s.cursor) step else old }, cursor = s.cursor + if (advance) 1 else 0))
  }

  companion object {
    fun isWeatherQuery(command: PhoneCommand): Boolean = command is PhoneCommand.Platform &&
      command.action in setOf(PlatformAction.WEATHER_TEMPERATURE, PlatformAction.WEATHER_RAIN)
  }
}
