package com.example.phone

import android.content.Context

/**
 * A temporary mode's restoration as an execution of its own (1.20.0): recorded once per lease in the journal,
 * dispatched, and checked against the original value. A manual change made meanwhile is kept (the existing rule of
 * [TemporaryModePolicy.canRestore]) and recorded as such, never reported as restored.
 */
object TemporaryModeRestorations {
  const val LINE = "restaurar el modo temporal"
  const val KEPT = "se conservó el cambio manual del teléfono; no se restauró."

  fun restore(context: Context, manager: TemporaryModeManager, lease: ModeLease): PhoneActionResult {
    val app = context.applicationContext
    val journal = runCatching { InstinctExecutions.journal(app) }.getOrNull()
    // Once per lease: a second expiry (a process that died while restoring) restores again only if nothing changed.
    val execution = runCatching {
      (journal?.admit("restore:${lease.id}", ExecutionTransport.RESTORE, listOf(LINE)) as? InstinctExecutionJournal.Admission.Admitted)
        ?.records?.single()?.executionId
    }.getOrNull()
    val ownedAndUnchanged = TemporaryModePolicy.canRestore(lease, runCatching { manager.current(lease.kind) }.getOrDefault(Int.MIN_VALUE))
    InstinctExecutions.begin(app, execution, PhoneCommand.EndMode)
    val result = manager.end()
    val now = runCatching { manager.current(lease.kind) }.getOrNull()
    when {
      !ownedAndUnchanged -> InstinctExecutions.move(app, execution, ExecutionState.CANCELLED, reason = KEPT)
      now == lease.original -> InstinctExecutions.move(app, execution, ExecutionState.VERIFIED, result = result.message,
        attribution = ExecutionAttribution.CAUSED)
      else -> InstinctExecutions.move(app, execution, ExecutionState.UNCERTAIN, reason = "Android no confirmó el valor restaurado.")
    }
    return result
  }
}
