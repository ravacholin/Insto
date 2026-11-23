package com.example.phone

/**
 * Acknowledgements to Instinct: the pure part. Insto answers each processed Instinct message with one WhatsApp reply
 * that has one machine-readable line per order. Nothing here sends, reads notifications, logs, persists or reaches
 * the network: the Android side hands in an opaque reply handle and sends what this returns. See
 * docs/INSTINCT-PROPUESTAS.md, «Respuestas a Instinct».
 */
enum class InstinctReplyMode { NEVER, PROBLEMS, ALWAYS }

/**
 * «Canal de estados»: where acknowledgements go. [InstinctReplyMode] decides WHAT is reported; this decides WHERE.
 * With [BITACORA] WhatsApp's «Responder» action is never read nor used.
 */
enum class InstinctAckChannel {
  WHATSAPP, BITACORA, AMBOS;

  val usesChat: Boolean get() = this != BITACORA
  val usesLog: Boolean get() = this != WHATSAPP
}

/** What happened to one order, as Instinct reads it. [prefix] is fixed: Instinct parses it. */
enum class AckKind(val prefix: String) {
  OK("#insto-ok"),
  WAITING("#insto-espera"),
  ERROR("#insto-error"),
  UNCERTAIN("#insto-incierto"),
  DISCARDED("#insto-descartada"),
  EXPIRED("#insto-vencida"),
  /** A deferred order was stored; its outcome may follow when it fires, while the reply handle is valid. */
  SCHEDULED("#insto-programada"),
  /** The user undid what the order did. */
  UNDONE("#insto-deshecho"),
  ;

  /** The kind as the log writes it: the prefix without «#insto-» (ok, espera, error…). */
  val code: String get() = prefix.removePrefix("#insto-")

  /** Kinds that end an order: after one of them nothing more is reported for it. */
  val final: Boolean get() = this != WAITING && this != SCHEDULED
}

/**
 * One acknowledged order. [message] is Insto's own result text (a [PhoneActionResult] message or a fixed reason),
 * never data of the phone beyond it. [informative] lines answer a read-only query and are sent in both active modes.
 */
data class AckEntry(val kind: AckKind, val order: String, val message: String, val informative: Boolean = false)

object InstinctAck {
  const val MAX_LINES = InstinctProposalProtocol.MAX_LINES
  const val MAX_CHARS = 600
  const val MAX_ORDER_CHARS = 80
  private const val ARROW = " → "

  /** A short Spanish sentence for each validation failure. */
  fun reason(reason: ProposalCheck.Reason): String = when (reason) {
    ProposalCheck.Reason.QUESTION -> InstinctQuestion.INVALID
    ProposalCheck.Reason.CONTEXT_RULE -> ContextRuleSpec.INVALID
    ProposalCheck.Reason.EMPTY -> "la línea no tenía ninguna orden."
    ProposalCheck.Reason.UNSAFE -> "no se aceptan preguntas ni pedidos con condiciones o datos sensibles."
    ProposalCheck.Reason.NOT_LOCAL -> "no la entendí: usá una de las formas del catálogo."
    ProposalCheck.Reason.SEMANTIC -> "la orden no coincide con lo que entendí; revisá las palabras."
    ProposalCheck.Reason.FORBIDDEN -> "esa acción no está permitida para Instinct."
  }

  fun invalid(check: ProposalCheck.Invalid): AckEntry = AckEntry(AckKind.ERROR, check.order, reason(check.reason))

  /**
   * The kind for an executed order. Anything that did not happen and needs the user (a confirmation, a choice, a
   * permission) is [AckKind.WAITING]; an uncertain effect is never reported as done nor as failed.
   */
  fun kindOf(result: PhoneActionResult): AckKind = when {
    result.outcome == PhoneActionOutcome.UNCERTAIN -> AckKind.UNCERTAIN
    result.outcome == PhoneActionOutcome.REJECTED -> AckKind.ERROR
    needsUser(result) -> AckKind.WAITING
    else -> AckKind.OK
  }

  /** The result did not happen and goes back to the user: a confirmation, input, a choice or a permission. */
  fun needsUser(result: PhoneActionResult): Boolean =
    result.confirmation != null || result.outcome == PhoneActionOutcome.NEEDS_INPUT || result.stopwatchAutomation != null ||
      result.appOptions.isNotEmpty() || result.contactOptions.isNotEmpty() || result.needsCamera ||
      result.needsNotifications || result.needsContacts

  fun entry(order: String, result: PhoneActionResult): AckEntry {
    val kind = kindOf(result)
    return AckEntry(kind, order, if (kind == AckKind.UNCERTAIN) "${result.message} · no se repite" else result.message)
  }

  /** Whether [entry] is sent under [mode]. «Solo errores y esperas» drops plain successes. */
  fun sends(entry: AckEntry, mode: InstinctReplyMode): Boolean = when (mode) {
    InstinctReplyMode.NEVER -> false
    InstinctReplyMode.ALWAYS -> true
    InstinctReplyMode.PROBLEMS -> entry.kind != AckKind.OK || entry.informative
  }

  /** One line: «<prefix> <order> → <message>», on a single line, with the order cut to [MAX_ORDER_CHARS]. */
  fun line(entry: AckEntry): String {
    val order = order(entry)
    val message = flat(entry.message)
    return buildString {
      append(entry.kind.prefix)
      if (order.isNotEmpty()) append(' ').append(order)
      if (message.isNotEmpty()) append(ARROW).append(message)
    }
  }

  /**
   * The reply for [entries], in order, filtered by [mode]; null when nothing is to be sent. At most [MAX_LINES] lines
   * and [MAX_CHARS] characters. Guaranteed never to contain a proposal line: Insto must never order itself.
   */
  fun format(entries: List<AckEntry>, mode: InstinctReplyMode): String? {
    val lines = entries.filter { sends(it, mode) }.take(MAX_LINES).map(::line)
    if (lines.isEmpty()) return null
    val budget = (MAX_CHARS - (lines.size - 1)) / lines.size
    val text = if (lines.sumOf { it.length } + lines.size - 1 <= MAX_CHARS) lines.joinToString("\n")
      else lines.joinToString("\n") { cut(it, budget) }
    return text.takeIf(::safe)
  }

  /**
   * Anti-loop guard: no line may read as an order for Insto, with the very parser that reads Instinct's messages.
   * Every line starts with a «#insto-» prefix and has no line breaks, so this holds by construction; it is still
   * checked, and a reply that failed it would not be sent.
   */
  fun safe(text: String): Boolean = InstinctProposalProtocol.orders(text).isEmpty() &&
    text.lineSequence().none { it.trimStart().startsWith("${InstinctProposalProtocol.PREFIX} ") }

  /** The order as every channel shows it: on one line and cut to [MAX_ORDER_CHARS]. */
  fun order(entry: AckEntry): String = cut(flat(entry.order), MAX_ORDER_CHARS)

  /** The result text as every channel shows it: on one line and never longer than a whole reply. */
  fun detail(entry: AckEntry): String = cut(flat(entry.message), MAX_CHARS)

  /** Line breaks, tabs and other control characters become spaces: a value can never start a line of its own. */
  internal fun flat(value: String): String = value.map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }
    .joinToString("").replace(Regex(" {2,}"), " ").trim()

  internal fun cut(value: String, max: Int): String = if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"
}

/**
 * The reply handles of accepted Instinct messages and what each order turned into, in memory only. A handle (the
 * WhatsApp «Responder» action, opaque here) is usable for [ttlMillis] after the message was accepted, like a
 * proposal; after that nothing is sent with it. Each message gets one reply once all its orders have an outcome;
 * an order that waited gets one more line when it is confirmed, discarded or expires, while the handle is valid.
 * Nothing is ever retried.
 *
 * The [channel] decides where each reply goes. On the chat, as above. On the log (1.15.0) the same entries become
 * events with no time limit: a message's lines are kept for [logTtlMillis], so a follow-up hours later (a deferred
 * order that fires, for example) is still recorded. With [InstinctAckChannel.BITACORA] no handle is ever kept.
 */
class InstinctAckLedger<R : Any>(
  private val clock: () -> Long,
  private val mode: () -> InstinctReplyMode,
  private val ttlMillis: Long = ProposalBook.TTL_MILLIS,
  private val channel: () -> InstinctAckChannel = { InstinctAckChannel.WHATSAPP },
  private val logTtlMillis: Long = LOG_TTL_MILLIS,
) {
  /**
   * What to send now. [text] goes to the chat with [handle] (a null handle means WhatsApp offered no «Responder»
   * action: it is dropped, and diagnosed); [events] go to the log, about message [message] (a short hash, never text).
   */
  data class Outgoing<R>(val handle: R?, val text: String?, val events: List<AckEntry> = emptyList(), val message: String = "")

  class Ticket internal constructor(internal val id: Long)

  private class Batch<R>(val id: Long, var handle: R?, val acceptedAt: Long, val slots: Array<AckEntry?>, val chat: Boolean, val message: String,
    /** An order from the mailbox (1.16.0): its lines always go to the log, whatever the channel, and never to the chat. */
    val logOnly: Boolean = false) {
    var sent = false
    val complete: Boolean get() = slots.all { it != null }
  }

  private var nextId = 1L
  private val batches = LinkedHashMap<Long, Batch<R>>()
  private val proposals = HashMap<Long, Pair<Long, Int>>()

  /**
   * Opens the reply of one accepted message with [orders] lines; [message] identifies it in the log. Null under
   * [InstinctReplyMode.NEVER]: nothing is kept. Without the chat channel [handle] is dropped right here.
   */
  @Synchronized fun open(handle: R?, orders: Int, message: String = "", logOnly: Boolean = false): Ticket? {
    prune()
    if (mode() == InstinctReplyMode.NEVER || orders <= 0) return null
    val chat = !logOnly && channel().usesChat
    val batch = Batch(nextId++, if (chat) handle else null, clock(), arrayOfNulls<AckEntry>(orders), chat, message, logOnly)
    batches[batch.id] = batch
    while (batches.size > CAPACITY) drop(batches.keys.first())
    return Ticket(batch.id)
  }

  /**
   * Tracks proposal [proposalId] for the log only, already past [entry] (for example a deferred order whose message
   * this process no longer remembers). False when the log is off or nothing is reported.
   */
  @Synchronized fun adopt(proposalId: Long, message: String, entry: AckEntry, logOnly: Boolean = false): Boolean {
    prune()
    if (mode() == InstinctReplyMode.NEVER || !(logOnly || channel().usesLog)) return false
    val batch = Batch<R>(nextId++, null, clock(), arrayOf(entry), chat = false, message = message, logOnly = logOnly).apply { sent = true }
    batches[batch.id] = batch
    proposals[proposalId] = batch.id to 0
    while (batches.size > CAPACITY) drop(batches.keys.first())
    return true
  }

  /** Whether proposal [proposalId] reports to the log: the channel includes it or it came from the mailbox. */
  @Synchronized fun logs(proposalId: Long): Boolean =
    channel().usesLog || proposals[proposalId]?.let { batches[it.first] }?.logOnly == true

  /** Whether proposal [proposalId] still has a line here. */
  @Synchronized fun tracks(proposalId: Long): Boolean = proposals[proposalId]?.let { batches[it.first] } != null

  /** Records the outcome of line [index] that never became a proposal (an invalid line, for example). */
  @Synchronized fun resolve(ticket: Ticket?, index: Int, entry: AckEntry): Outgoing<R>? {
    val batch = ticket?.let { batches[it.id] } ?: return null
    if (index !in batch.slots.indices || batch.slots[index] != null) return null
    batch.slots[index] = entry
    return flush(batch)
  }

  /** Line [index] became proposal [proposalId]: its outcome arrives later through [report]. */
  @Synchronized fun link(ticket: Ticket?, index: Int, proposalId: Long) {
    val batch = ticket?.let { batches[it.id] } ?: return
    if (index in batch.slots.indices) proposals[proposalId] = batch.id to index
  }

  /** A proposal that went back for a touch under a new id keeps its line. */
  @Synchronized fun relink(oldId: Long, newId: Long) { proposals[oldId]?.let { proposals[newId] = it } }

  /**
   * The outcome of proposal [proposalId]. The first one fills its line, and the reply goes out when every line has
   * one. After a [AckKind.WAITING] reply, a final outcome is sent as one follow-up line; anything later is ignored.
   */
  @Synchronized fun report(proposalId: Long, entry: AckEntry): Outgoing<R>? {
    val (batchId, index) = proposals[proposalId] ?: return null
    val batch = batches[batchId] ?: return null
    val current = batch.slots[index]
    if (!batch.sent) {
      // Before the reply: the latest outcome wins, unless the line already ended.
      if (current == null || !current.kind.final) batch.slots[index] = entry
      return flush(batch)
    }
    if (current == null || current.kind.final || !entry.kind.final) return null
    batch.slots[index] = entry
    return single(batch, entry)
  }

  /** One extra line about proposal [proposalId] (for example an undo), while its handle is valid or to the log. */
  @Synchronized fun note(proposalId: Long, entry: AckEntry): Outgoing<R>? {
    val (batchId, _) = proposals[proposalId] ?: return null
    val batch = batches[batchId] ?: return null
    return single(batch, entry)
  }

  /** Whether proposal [proposalId] still has a valid handle to answer with. */
  @Synchronized fun canAnswer(proposalId: Long): Boolean {
    val (batchId, _) = proposals[proposalId] ?: return false
    val batch = batches[batchId] ?: return false
    return chatOpen(batch) && batch.handle != null && mode() != InstinctReplyMode.NEVER
  }

  /** The newest valid handle, for sending something that is not about one order (the instructions, for example). */
  @Synchronized fun latestHandle(): R? {
    prune()
    if (mode() == InstinctReplyMode.NEVER) return null
    return batches.values.lastOrNull { it.handle != null && chatOpen(it) }?.handle
  }

  /**
   * Proposals whose line is still waiting and whose handle is about to lapse ([marginMillis] before): the caller
   * expires them, so their «vencida» line can still be sent.
   */
  @Synchronized fun waitingDue(marginMillis: Long): List<Long> {
    val now = clock()
    return proposals.entries.filter { (_, place) ->
      val batch = batches[place.first] ?: return@filter false
      batch.chat && batch.slots[place.second]?.kind == AckKind.WAITING && now >= batch.acceptedAt + ttlMillis - marginMillis
    }.map { it.key }
  }

  /** Every proposal whose line is waiting, whatever its handle. The caller checks each proposal's own age. */
  @Synchronized fun waiting(): List<Long> = proposals.entries.filter { (_, place) ->
    batches[place.first]?.slots?.get(place.second)?.kind == AckKind.WAITING
  }.map { it.key }

  /** Forgets every handle and pending reply, for example when «Recibir órdenes» is turned off. */
  @Synchronized fun clear() { batches.clear(); proposals.clear() }

  private fun valid(batch: Batch<R>): Boolean { val now = clock(); return now >= batch.acceptedAt && now - batch.acceptedAt <= ttlMillis }

  /** The chat is used for [batch]: it was opened for it, the channel still includes it and the handle is valid. */
  private fun chatOpen(batch: Batch<R>): Boolean = batch.chat && channel().usesChat && valid(batch)

  private fun flush(batch: Batch<R>): Outgoing<R>? {
    if (batch.sent || !batch.complete) return null
    batch.sent = true
    return outgoing(batch, batch.slots.filterNotNull(), mode())
  }

  /** A follow-up closes a line Instinct already saw, so «Solo errores y esperas» sends it too. */
  private fun single(batch: Batch<R>, entry: AckEntry): Outgoing<R>? {
    val current = mode()
    if (current == InstinctReplyMode.NEVER) return null
    return outgoing(batch, listOf(entry.copy(informative = true)), current)
  }

  private fun outgoing(batch: Batch<R>, entries: List<AckEntry>, mode: InstinctReplyMode): Outgoing<R>? {
    val text = if (chatOpen(batch)) InstinctAck.format(entries, mode) else null
    val events = if (channel().usesLog || batch.logOnly) entries.filter { InstinctAck.sends(it, mode) } else emptyList()
    if (text == null && events.isEmpty()) return null
    return Outgoing(if (text != null) batch.handle else null, text, events, batch.message)
  }

  private fun prune() {
    val now = clock()
    val log = channel().usesLog
    batches.values.filter { now < it.acceptedAt || now - it.acceptedAt > if (log || it.logOnly) logTtlMillis else ttlMillis }
      .map { it.id }.forEach(::drop)
    // A handle is never kept past its validity, even when the line stays for the log.
    batches.values.forEach { if (now - it.acceptedAt > ttlMillis) it.handle = null }
  }

  private fun drop(batchId: Long) {
    batches.remove(batchId)
    proposals.entries.removeAll { it.value.first == batchId }
  }

  companion object {
    private const val CAPACITY = 64
    /** A deferred order fires at most 24 hours later; its lines are kept a little longer for the log. */
    const val LOG_TTL_MILLIS = 25 * 60 * 60_000L
  }
}
