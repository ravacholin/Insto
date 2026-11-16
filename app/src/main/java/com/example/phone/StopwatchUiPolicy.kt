package com.example.phone

import java.text.Normalizer

internal data class StopwatchControl(
  val label: String,
  val resource: String = "",
  val selected: Boolean = false,
  val actionable: Boolean = true,
) {
  fun matches(vararg values: String): Boolean = label.split('|').any { label ->
    val normalized = Normalizer.normalize(label, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
    normalized in values
  }
  val isTab: Boolean get() = matches("cronometro", "stopwatch")
}

internal sealed interface StopwatchUiStep {
  data class Select(val index: Int) : StopwatchUiStep
  data class Click(val index: Int) : StopwatchUiStep
  data object Wait : StopwatchUiStep
  data object Done : StopwatchUiStep
}

/** One explicit request, one click per transition. A stale window never toggles twice. */
internal class StopwatchUiPolicy(private val operation: StopwatchOp) {
  private val attempted = mutableSetOf<String>()

  fun next(controls: List<StopwatchControl>): StopwatchUiStep {
    val onStopwatch = controls.any { it.isTab && it.selected || it.resource.substringAfter(":id/") in setOf("stopwatch_time_text", "stopwatch_time", "stopwatch_laps_list") }
    if (!onStopwatch) {
      val tabs = controls.indices.filter { controls[it].isTab && controls[it].actionable }
      return if (tabs.size == 1 && attempted.add("tab")) StopwatchUiStep.Select(tabs.single()) else StopwatchUiStep.Wait
    }
    if (operation == StopwatchOp.STATUS) return StopwatchUiStep.Done
    fun matching(vararg labels: String) = controls.indices.filter { controls[it].actionable && controls[it].matches(*labels) }
    val start = matching("iniciar", "iniciar cronometro", "iniciar el cronometro", "start", "start stopwatch")
    val resume = matching("continuar", "reanudar", "continuar cronometro", "reanudar cronometro", "resume", "resume stopwatch")
    val pause = matching("pausar", "detener", "pausar cronometro", "pausar el cronometro", "pause", "stop", "pause stopwatch", "stop stopwatch")
    val reset = matching("restablecer", "reiniciar", "restablecer cronometro", "reiniciar cronometro", "reset", "reset stopwatch")
    fun clickOnce(key: String, candidates: List<Int>): StopwatchUiStep =
      if (candidates.size == 1 && attempted.add(key)) StopwatchUiStep.Click(candidates.single()) else StopwatchUiStep.Wait
    return when (operation) {
      StopwatchOp.START, StopwatchOp.RESUME -> when {
        pause.size == 1 && start.isEmpty() && resume.isEmpty() -> StopwatchUiStep.Done
        pause.isNotEmpty() -> StopwatchUiStep.Wait
        else -> clickOnce("start", start + resume)
      }
      StopwatchOp.PAUSE -> when {
        pause.isEmpty() && (start + resume).size == 1 -> StopwatchUiStep.Done
        start.isNotEmpty() || resume.isNotEmpty() -> StopwatchUiStep.Wait
        else -> clickOnce("pause", pause)
      }
      StopwatchOp.RESET -> when {
        pause.isNotEmpty() && start.isEmpty() && resume.isEmpty() -> clickOnce("pause", pause)
        pause.isNotEmpty() -> StopwatchUiStep.Wait
        reset.isNotEmpty() -> clickOnce("reset", reset)
        start.size == 1 && resume.isEmpty() -> StopwatchUiStep.Done
        else -> StopwatchUiStep.Wait
      }
      StopwatchOp.STATUS -> StopwatchUiStep.Done
    }
  }
}
