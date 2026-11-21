package com.example.phone

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

/**
 * Reads the state a reversible command is about to change and puts it back on «Deshacer». A state the user changed
 * after the order is never overwritten: undo leaves it and says so ([UndoPolicy.decide]).
 */
internal object InstinctUndoActions {
  /** The state before a command runs. Null for commands without a reversible state. */
  class Capture(val command: PhoneCommand, val before: Int?)

  /** A seam for tests: Robolectric has no torch. Returns true when the torch took the requested state. */
  internal var torch: (Context, Boolean) -> Boolean = { context, on ->
    PhoneActionExecutor(context).execute(PhoneCommand.Torch(on)).outcome == PhoneActionOutcome.COMPLETED
  }

  private fun audio(context: Context) = context.getSystemService(AudioManager::class.java)
  private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)

  fun capture(context: Context, command: PhoneCommand): Capture? = if (!UndoPolicy.reversible(command)) null else runCatching {
    Capture(command, when (command) {
      is PhoneCommand.Volume -> audio(context).getStreamVolume(AudioManager.STREAM_MUSIC)
      is PhoneCommand.Platform -> if (command.action == PlatformAction.DND_ON || command.action == PlatformAction.DND_OFF)
        notifications(context).currentInterruptionFilter else audio(context).ringerMode
      else -> null // The torch cannot be read; a temporary mode keeps its own lease.
    })
  }.getOrNull()

  /** After a successful run: the reversal, or null when the state cannot be read back. */
  fun complete(context: Context, capture: Capture): Reversal? = runCatching {
    when (val command = capture.command) {
      is PhoneCommand.Volume -> Reversal.Volume(command, capture.before!!, audio(context).getStreamVolume(AudioManager.STREAM_MUSIC))
      is PhoneCommand.Torch -> Reversal.Torch(command, restore = !command.enabled)
      is PhoneCommand.TemporaryMode -> TemporaryModeManager(context).read()?.let { Reversal.Temporary(command, it.id) }
      is PhoneCommand.Platform -> if (command.action == PlatformAction.DND_ON || command.action == PlatformAction.DND_OFF)
        Reversal.Interruptions(command, capture.before!!, notifications(context).currentInterruptionFilter)
        else Reversal.Ringer(command, capture.before!!, audio(context).ringerMode)
      else -> null
    }
  }.getOrNull()

  /** Undoes one reversal. True: reverted. False: the user changed it after the order, or Android refused. */
  fun revert(context: Context, reversal: Reversal): Boolean = runCatching {
    when (reversal) {
      is Reversal.Volume -> when (val d = UndoPolicy.decide(reversal.previous, reversal.applied, audio(context).getStreamVolume(AudioManager.STREAM_MUSIC))) {
        is UndoPolicy.Decision.Restore -> { audio(context).setStreamVolume(AudioManager.STREAM_MUSIC, d.value, 0); true }
        UndoPolicy.Decision.ManualChange -> false
      }
      is Reversal.Ringer -> when (val d = UndoPolicy.decide(reversal.previous, reversal.applied, audio(context).ringerMode)) {
        is UndoPolicy.Decision.Restore -> { audio(context).ringerMode = d.value; audio(context).ringerMode == d.value }
        UndoPolicy.Decision.ManualChange -> false
      }
      is Reversal.Interruptions -> when (val d = UndoPolicy.decide(reversal.previous, reversal.applied, notifications(context).currentInterruptionFilter)) {
        is UndoPolicy.Decision.Restore -> { notifications(context).setInterruptionFilter(d.value); notifications(context).currentInterruptionFilter == d.value }
        UndoPolicy.Decision.ManualChange -> false
      }
      is Reversal.Torch -> torch(context, reversal.restore)
      is Reversal.Temporary -> {
        // Its own lease, still owned and unchanged: end it, restoring the previous state. Otherwise leave it.
        val manager = TemporaryModeManager(context)
        val lease = manager.read()
        if (lease == null || lease.id != reversal.leaseId || !TemporaryModePolicy.canRestore(lease, manager.current(lease.kind))) false
        else { manager.end(); true }
      }
    }
  }.getOrDefault(false)
}
