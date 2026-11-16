package com.example.phone

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

class StopwatchRepository(context: Context) {
  private val prefs = context.getSharedPreferences("local_stopwatch", Context.MODE_PRIVATE)
  private val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
    .getOrElse { "unknown-${UUID.randomUUID()}" }
  @Synchronized fun state(): StopwatchState = StopwatchState(
    prefs.getLong("accumulated", 0), prefs.getLong("started", -1).takeIf { it >= 0 }, prefs.getString("boot", "").orEmpty()
  ).reconcile(SystemClock.elapsedRealtime(), boot)
  @Synchronized fun apply(operation: StopwatchOp): StopwatchState = state().apply(operation, SystemClock.elapsedRealtime(), boot).also { save(it) }
  @Synchronized fun snapshot(): Pair<Long, Boolean> {
    val now = SystemClock.elapsedRealtime()
    val state = state()
    // Checkpoint while observed, retaining the last known time if the device reboots later.
    val elapsed = state.elapsed(now, boot)
    save(state.copy(accumulated = elapsed, startedAt = if (state.startedAt == null) null else now, boot = boot))
    return elapsed to (state.startedAt != null)
  }
  private fun save(state: StopwatchState) {
    prefs.edit().putLong("accumulated", state.accumulated).putLong("started", state.startedAt ?: -1)
      .putString("boot", state.boot).apply()
  }
}
