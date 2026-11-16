package com.example.phone

/** Monotonic timestamps are meaningful only within the same boot. */
data class StopwatchState(val accumulated: Long = 0, val startedAt: Long? = null, val boot: String = "") {
  fun elapsed(now: Long, currentBoot: String): Long = accumulated.coerceAtLeast(0) +
    if (startedAt != null && currentBoot == boot) (now - startedAt).coerceAtLeast(0) else 0
  fun reconcile(now: Long, currentBoot: String): StopwatchState =
    if (startedAt != null && (boot != currentBoot || now < startedAt)) copy(startedAt = null, boot = currentBoot) else this
  fun apply(operation: StopwatchOp, now: Long, currentBoot: String): StopwatchState {
    val safe = reconcile(now, currentBoot)
    return when (operation) {
      StopwatchOp.START, StopwatchOp.RESUME -> if (safe.startedAt != null) safe else safe.copy(startedAt = now, boot = currentBoot)
      StopwatchOp.PAUSE -> StopwatchState(safe.elapsed(now, currentBoot), null, currentBoot)
      StopwatchOp.RESET -> StopwatchState(0, null, currentBoot)
      StopwatchOp.STATUS -> safe
    }
  }
}
