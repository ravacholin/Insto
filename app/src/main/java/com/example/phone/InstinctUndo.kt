package com.example.phone

/**
 * «Deshacer» for what Instinct did: the pure part. Only commands with a reversible state get one: the media volume,
 * the torch, silence/vibrate/Do not disturb and a temporary mode. Alarms, timers, events, apps and searches never do.
 * The previous state lives in memory only, behind a random token, for ten minutes and for one use. Nothing here
 * touches Android: the caller reads and writes the actual state.
 */
sealed interface Reversal {
  /** The step it reverts, for messages. */
  val command: PhoneCommand

  /** Media volume ([android.media.AudioManager.STREAM_MUSIC]): [previous] before, [applied] right after. */
  data class Volume(override val command: PhoneCommand, val previous: Int, val applied: Int) : Reversal
  /** The ringer mode before and after silence or vibrate. */
  data class Ringer(override val command: PhoneCommand, val previous: Int, val applied: Int) : Reversal
  /** The interruption filter before and after Do not disturb. */
  data class Interruptions(override val command: PhoneCommand, val previous: Int, val applied: Int) : Reversal
  /** The torch state cannot be read back: undoing sets the inverse of what the order set. */
  data class Torch(override val command: PhoneCommand, val restore: Boolean) : Reversal
  /** The lease [leaseId] that [TemporaryModeManager] created; undoing ends it only while it is owned and unchanged. */
  data class Temporary(override val command: PhoneCommand, val leaseId: String) : Reversal
}

object UndoPolicy {
  /** Whether [command] can get a «Deshacer» at all. */
  fun reversible(command: PhoneCommand): Boolean = when (command) {
    is PhoneCommand.Volume, is PhoneCommand.Torch -> true
    is PhoneCommand.TemporaryMode -> true
    is PhoneCommand.Platform -> command.action in setOf(PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON,
      PlatformAction.DND_ON, PlatformAction.DND_OFF)
    else -> false
  }

  sealed interface Decision {
    data class Restore(val value: Int) : Decision
    /** The user changed it after the order: undo does nothing and says so. */
    data object ManualChange : Decision
  }

  /** For a readable state: restore [previous] only while the state is still what the order left. */
  fun decide(previous: Int, applied: Int, current: Int): Decision =
    if (current == applied) Decision.Restore(previous) else Decision.ManualChange

  /** The message after undoing a whole order or routine: what was reverted and what could not be. */
  fun summary(reverted: List<PhoneCommand>, kept: List<PhoneCommand>, irreversible: List<PhoneCommand>): String = buildString {
    if (reverted.isNotEmpty()) append("Deshice: ").append(reverted.joinToString(", ") { PhoneCommandCodec.summary(it) }).append('.')
    if (kept.isNotEmpty()) {
      if (isNotEmpty()) append(' ')
      append("No toqué lo que cambiaste después: ").append(kept.joinToString(", ") { PhoneCommandCodec.summary(it) }).append('.')
    }
    if (irreversible.isNotEmpty()) {
      if (isNotEmpty()) append(' ')
      append("No se pueden deshacer: ").append(irreversible.joinToString(", ") { PhoneCommandCodec.summary(it) }).append('.')
    }
    if (isEmpty()) append("No había nada para deshacer.")
  }
}

/**
 * Pending undos, in memory only, like [ProposalBook]: an id, a random token carried only by Insto's own
 * PendingIntent, ten minutes of validity and a single use.
 */
class UndoBook(private val clock: () -> Long, private val ttlMillis: Long = ProposalBook.TTL_MILLIS) {
  data class Entry(
    val id: Long,
    /** The proposal it belongs to, to tell Instinct. */
    val proposalId: Long,
    val order: String,
    /** In the order they were applied; undone in reverse. */
    val reversals: List<Reversal>,
    /** Steps of a routine that had an effect but cannot be undone. */
    val irreversible: List<PhoneCommand>,
    val createdAt: Long,
    val token: String = java.util.UUID.randomUUID().toString(),
  )

  sealed interface Claim {
    data class Granted(val entry: Entry) : Claim
    data object Expired : Claim
    data object Used : Claim
    data object Unknown : Claim
  }

  private var nextId = 1L
  private val entries = LinkedHashMap<Long, Pair<Entry, Boolean>>()

  /** Null when nothing is reversible: no «Deshacer» is offered. */
  @Synchronized fun offer(proposalId: Long, order: String, reversals: List<Reversal>, irreversible: List<PhoneCommand> = emptyList()): Entry? {
    if (reversals.isEmpty()) return null
    val entry = Entry(nextId++, proposalId, order, reversals, irreversible, clock())
    entries[entry.id] = entry to false
    while (entries.size > 32) entries.remove(entries.keys.first())
    return entry
  }

  @Synchronized fun claim(id: Long, token: String?): Claim {
    val (entry, used) = entries[id] ?: return Claim.Unknown
    if (token != entry.token) return Claim.Unknown
    if (used) return Claim.Used
    val now = clock()
    if (now < entry.createdAt || now - entry.createdAt > ttlMillis) { entries[id] = entry to true; return Claim.Expired }
    entries[id] = entry to true
    return Claim.Granted(entry)
  }

  @Synchronized fun clear() = entries.clear()
}
