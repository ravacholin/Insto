package com.example.phone

import java.security.MessageDigest
import java.util.Calendar

/**
 * «Instinct con manos»: Instinct may answer in WhatsApp with fixed-format proposal lines. This file holds the pure,
 * Android-free part: the line protocol, the validation every line goes through, the notification filters and the
 * in-memory book of proposals awaiting a user's touch or «dale». Nothing here executes anything, reads
 * notifications by itself, logs, persists or reaches the network. See docs/INSTINCT-PROPUESTAS.md.
 */
object InstinctProposalProtocol {
  const val PREFIX = "#insto"
  const val MAX_LINES = 3

  /**
   * The orders of the first [MAX_LINES] proposal lines: lines that start with «#insto» and a space. An «#insto»
   * inside a sentence is ordinary text. A proposal line without an order is returned empty, so it is reported as
   * not understood instead of being silently dropped. Everything else in the message is never interpreted.
   */
  fun orders(message: String): List<String> = message.lineSequence()
    .map { it.trim() }
    .filter { it == PREFIX || it.startsWith("$PREFIX ") || it.startsWith("$PREFIX\t") }
    .take(MAX_LINES)
    .map { it.removePrefix(PREFIX).trim() }
    .toList()
}

/** The result of checking one proposal line. Only [Valid] can ever reach the user as something to confirm. */
sealed interface ProposalCheck {
  val order: String
  /** With [deferred], [command] is the inner order, checked now and again when it fires. */
  data class Valid(override val order: String, val command: PhoneCommand, val deferred: Deferred? = null) : ProposalCheck
  data class Invalid(override val order: String, val reason: Reason) : ProposalCheck
  /** An administrative order or a query about the protocol. */
  data class Query(override val order: String, val kind: QueryKind, val argument: String? = null) : ProposalCheck
  /** «avisarme <texto>» (1.16.0): a local notice for the person, allowed only from the signed mailbox. */
  data class Notice(override val order: String, val text: String) : ProposalCheck
  data class Question(override val order: String, val question: InstinctQuestion) : ProposalCheck
  enum class Reason { EMPTY, UNSAFE, NOT_LOCAL, SEMANTIC, FORBIDDEN, QUESTION }
  /**
   * [readOnly] queries change nothing on the phone; «cancelar programadas» and «cancelar programada <id>» only
   * cancel Insto's own pending orders. None consumes the brake. [form] is how the catalog shows it.
   */
  enum class QueryKind(val phrase: String, val readOnly: Boolean = true, val form: String = phrase) {
    CATALOG("ver catálogo"), SCHEDULED("ver programadas"), CANCEL_SCHEDULED("cancelar programadas", readOnly = false),
    /** Publishes the phone's state in the log now (1.16.0). Changes nothing on the phone. */
    STATE("ver estado"),
    /** One scheduled order, or a whole recurrence, by the short id of «ver programadas» (1.17.0). */
    CANCEL_ONE("cancelar programada", readOnly = false, form = "cancelar programada <id>"),
    /**
     * «probar <orden>» (1.18.0): validates the order with the same validator and says what would happen now, without
     * running, saving, scheduling, telling the person or consuming the brake. [ProposalCheck.Query.argument] is the order.
     */
    PROBE("probar", form = "probar <orden>"),
    /** The journal's last executions (1.20.0): id, state, when, transport, order and result. Read-only. */
    EXECUTIONS("ver ejecuciones"),
    /** One execution by the short id «ver ejecuciones» gives (1.20.0). Read-only. */
    EXECUTION_STATUS("estado de ejecución", form = "estado de ejecución <id>"),
  }
}

/**
 * The same checks as any order, with the explicit grammar only: [RequestSafetyPolicy], [PhoneCommandParser] (which
 * never consults learned phrases, structures, the operational context or a model), [SemanticCommandGuard] with the
 * line as the request text and, last, the v1 allowlist. A line that does not resolve to exactly one
 * [PhoneRoute.Local] is discarded.
 */
object InstinctProposalValidator {
  private val cancelOne = Regex("^cancelar programada ([0-9a-f]{${InstinctTriggers.SHORT_ID_LENGTH}})$")
  private val executionStatus = Regex("^estado de ejecucion (e?[0-9a-f]{${ExecutionIds.SHORT}})$")
  private val silenceActions = setOf(PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON,
    PlatformAction.DND_ON, PlatformAction.DND_OFF)

  /** [routines] are the routines the user let Instinct run; with none, every routine is forbidden. */
  fun check(order: String, parser: PhoneCommandParser, now: Calendar = Calendar.getInstance(),
    routines: InstinctRoutineAccess = InstinctRoutineAccess.NONE): ProposalCheck =
    check(order, parser::parse, now, routines)

  /** [parse] is the explicit grammar; a seam for tests that the semantic guard still reviews its output. */
  internal fun check(order: String, parse: (String) -> PhoneRoute, now: Calendar,
    routines: InstinctRoutineAccess = InstinctRoutineAccess.NONE): ProposalCheck {
    if (order.isBlank()) return ProposalCheck.Invalid(order, ProposalCheck.Reason.EMPTY)
    // Read-only questions about the protocol: exact phrases, before the grammar.
    val folded = PhoneCommandParser.normalize(order).trimEnd('.', '!')
    ProposalCheck.QueryKind.entries.firstOrNull { it != ProposalCheck.QueryKind.CANCEL_ONE && it != ProposalCheck.QueryKind.PROBE &&
      it != ProposalCheck.QueryKind.EXECUTION_STATUS && PhoneCommandParser.normalize(it.phrase) == folded }?.let { return ProposalCheck.Query(order, it) }
    executionStatus.matchEntire(folded)?.let { return ProposalCheck.Query(order, ProposalCheck.QueryKind.EXECUTION_STATUS, it.groupValues[1]) }
    // «probar <orden>»: the order is kept as written and checked by whoever answers, with this same validator.
    probe(order)?.let { inner ->
      return if (inner.isBlank()) ProposalCheck.Invalid(order, ProposalCheck.Reason.EMPTY) else ProposalCheck.Query(order, ProposalCheck.QueryKind.PROBE, inner)
    }
    if (InstinctQuestion.isOrder(order)) {
      val question = InstinctQuestion.parse(order, now) ?: return ProposalCheck.Invalid(order, ProposalCheck.Reason.QUESTION)
      if (question.actions.any { backgroundAction(it, parse, now, routines) == null })
        return ProposalCheck.Invalid(order, ProposalCheck.Reason.QUESTION)
      return ProposalCheck.Question(order, question)
    }
    cancelOne.matchEntire(folded)?.let { return ProposalCheck.Query(order, ProposalCheck.QueryKind.CANCEL_ONE, it.groupValues[1]) }
    // A notice for the person: fixed form, plain text. Where it may come from is decided by the caller.
    InstinctNotice.text(order)?.let { text ->
      return if (InstinctNotice.valid(text)) ProposalCheck.Notice(order, text) else ProposalCheck.Invalid(order, ProposalCheck.Reason.EMPTY)
    }
    // A deferred order: only a wrapper. Its inner order goes through every check below, untouched, and cannot be
    // another wrapper or a query.
    when (val wrapper = InstinctDeferral.parse(order, now)) {
      is InstinctDeferral.Parse.Rejected -> return ProposalCheck.Invalid(order, ProposalCheck.Reason.NOT_LOCAL)
      is InstinctDeferral.Parse.Wrapped -> {
        val inner = check(wrapper.deferred.inner, parse, now, routines)
        return when {
          inner is ProposalCheck.Valid && inner.deferred == null -> ProposalCheck.Valid(order, inner.command, wrapper.deferred)
          inner is ProposalCheck.Invalid -> ProposalCheck.Invalid(order, inner.reason)
          else -> ProposalCheck.Invalid(order, ProposalCheck.Reason.FORBIDDEN)
        }
      }
      InstinctDeferral.Parse.None -> Unit
    }
    if (RequestSafetyPolicy.refusal(order) != null || RequestSafetyPolicy.isQuestion(order))
      return ProposalCheck.Invalid(order, ProposalCheck.Reason.UNSAFE)
    val command = (parse(order) as? PhoneRoute.Local)?.command
      ?: return ProposalCheck.Invalid(order, ProposalCheck.Reason.NOT_LOCAL)
    val reviewed = SemanticCommandGuard.review(InterpretationRequest(order, now = now), InterpretationResult.Direct(command))
    if (reviewed !is InterpretationResult.Direct || reviewed.command != command)
      return ProposalCheck.Invalid(order, ProposalCheck.Reason.SEMANTIC)
    if (!allowed(command) && !allowedRoutine(command, routines)) return ProposalCheck.Invalid(order, ProposalCheck.Reason.FORBIDDEN)
    return ProposalCheck.Valid(order, command)
  }

  internal fun backgroundAction(order: String, parser: PhoneCommandParser, now: Calendar,
    routines: InstinctRoutineAccess = InstinctRoutineAccess.NONE): PhoneCommand? =
    backgroundAction(order, parser::parse, now, routines)

  private fun backgroundAction(order: String, parse: (String) -> PhoneRoute, now: Calendar,
    routines: InstinctRoutineAccess): PhoneCommand? {
    if (InstinctQuestion.isRelated(order) || '|' in order || order.length > 200) return null
    val command = (check(order, parse, now, routines) as? ProposalCheck.Valid)
      ?.takeIf { it.deferred == null }?.command ?: return null
    return command.takeIf(LockScreenPolicy::runsLocked)
  }

  /** The order after «probar», as written (maybe empty); null when the line is not a probe. */
  fun probe(order: String): String? {
    val trimmed = order.trim()
    val head = trimmed.substringBefore(' ')
    if (PhoneCommandParser.normalize(head) != InstinctProbe.PHRASE) return null
    return trimmed.substring(head.length).trim()
  }

  /**
   * A routine passes only when its name matches exactly (normalized as `routine_run` does) a routine the user
   * enabled for Instinct, whose steps have not changed since. No other routine, and never a plan built from text.
   */
  fun allowedRoutine(command: PhoneCommand, routines: InstinctRoutineAccess): Boolean =
    command is PhoneCommand.Platform && command.action == PlatformAction.ROUTINE_RUN &&
      command.argument?.let(routines::resolve) != null

  /**
   * One canonical form per allowed type: the single source of the examples in the generated catalog, text and JSON
   * ([InstinctProtocolCatalog]). A test checks that each one is [ProposalCheck.Valid]. Grouped by form (1.18.0) so the
   * JSON catalog can say which is which; [examples] keeps them all, in the same order.
   */
  val immediateExamples: List<String> = listOf(
    "poner una alarma a las 07:00",
    "poner una alarma a las 07:00 todos los días",
    "poner un temporizador de 25 minutos",
    "poner el volumen al 30 por ciento",
    "subir el volumen",
    "bajar el volumen",
    "prender la linterna",
    "apagar la linterna",
    "silenciar el teléfono",
    "silenciar el teléfono durante una hora",
    "activar no molestar",
    "desactivar no molestar",
    "poner en vibración",
    "pausar la música",
    "siguiente canción",
    "abrir spotify",
    "mostrar las alarmas",
    "mostrar los temporizadores",
    "poner música de Soda Stereo",
    "buscar jazz en YouTube",
    "buscar Cerati en Spotify",
    "agendar reunión mañana a las 17:00 durante una hora",
  )
  /** Deferred forms (1.14.0): the wrapper, then any order above. */
  val deferredExamples: List<String> = listOf(
    "a las 22:00: poner el volumen al 10 por ciento",
    "en 30 minutos: apagar la linterna",
    "en 2 horas: desactivar no molestar",
  )
  /** One weather condition, only with a time (1.14.0). */
  val weatherExamples: List<String> = listOf(
    "a las 06:30 si llueve: poner una alarma a las 06:45",
    "a las 06:30 si no llueve: poner música de Soda Stereo",
    "a las 07:00 si hace menos de 10 grados: poner un temporizador de 10 minutos",
    "a las 14:00 si hace más de 30 grados: activar no molestar",
  )
  /** Triggers by state (1.17.0): once each, at most 12 hours (24 with «hasta las HH:MM»). */
  val triggerExamples: List<String> = listOf(
    "al terminar el evento: desactivar no molestar",
    "al empezar el próximo evento: activar no molestar",
    "al conectar auriculares hasta las 20:00: abrir spotify",
    "al desconectar auriculares: pausar la música",
    "al desbloquear: mostrar las alarmas",
  )
  /** Recurring orders (1.17.0): «hasta el DD/MM» is required, at most 30 days. The tests check them on 21/11. */
  val recurringExamples: List<String> = listOf(
    "todos los días a las 07:30 hasta el 15/12: poner el volumen al 20 por ciento",
    "los días hábiles a las 08:00 hasta el 15/12: activar no molestar",
    "los fines de semana a las 10:00 hasta el 13/12: poner música de Soda Stereo",
    "todos los días a las 06:30 si llueve hasta el 15/12: poner una alarma a las 06:45",
  )
  val examples: List<String> = immediateExamples + deferredExamples + weatherExamples + triggerExamples + recurringExamples

  /**
   * Allowlist v1 for single commands and routine steps. Everything not named is forbidden, in particular calls
   * ([PhoneCommand.Dial] and every [PlatformAction.CALL_DIRECT]), messages, files and sharing, [PhoneCommand.SavePlace]
   * and workflows. A routine is never allowed here: only [allowedRoutine] lets an enabled one through. A proposal is
   * always one command: plans of several steps are never built from proposals.
   */
  fun allowed(command: PhoneCommand): Boolean = when (command) {
    is PhoneCommand.Alarm, is PhoneCommand.Timer, is PhoneCommand.Volume, is PhoneCommand.Torch,
    is PhoneCommand.Media, is PhoneCommand.OpenApp, is PhoneCommand.ClockList,
    is PhoneCommand.MusicSearch, is PhoneCommand.SpotifySearch, is PhoneCommand.YouTubeSearch,
    is PhoneCommand.CalendarEvent -> true
    // Silence, vibrate and Do not disturb, with or without a restoration time. A temporary media volume is not.
    is PhoneCommand.TemporaryMode -> command.kind == ModeKind.SILENT || command.kind == ModeKind.DND
    // The grammar expresses silence, vibrate and Do not disturb without a duration as platform actions.
    is PhoneCommand.Platform -> command.action in silenceActions
    else -> false
  }
}

/**
 * A notification as the filters see it. Every accessor reads only what it names, and the filters call them in order,
 * stopping at the first that fails, so a notification from another app is never read beyond its package.
 */
interface ProposalNotification {
  val packageName: String
  val key: String
  fun isGroup(): Boolean
  /** The notification title. */
  fun title(): String?
  /** Whether the notification carries MessagingStyle messages, and the sender of the newest one (null: the user). */
  fun latestMessage(): LatestMessage?
  /** The text of the newest message, or the notification text without MessagingStyle. Content: read last. */
  fun text(): String?

  data class LatestMessage(val sender: String?, val sentAt: Long? = null)
}

data class ProposalFilterConfig(
  /** The package of the configured WhatsApp flavor, resolved as Insto sends; null when none is installed. */
  val expectedPackage: String?,
  /** The configured contact name, compared exactly. */
  val contactName: String?,
  val now: Long,
)

/**
 * Filters, in order, stopping at the first that fails: package, not a group, exact contact name, a message never seen
 * before. Instinct may write on its own initiative: no previous message from Insto is required. A message that fails is dropped: nothing about
 * it is kept, not even its hash. Only stage counters exist, for diagnostics. An accepted message carries the first
 * hex digits of its dedupe hash ([Outcome.Accepted.message]): the only way the log names it, never its text.
 */
class InstinctProposalFilter(private val dedupe: MessageDedupe = MessageDedupe()) {
  enum class Stage { PACKAGE, GROUP, CONTACT, DUPLICATE, NO_PROPOSAL, ACCEPTED }

  sealed interface Outcome {
    val stage: Stage
    data class Dropped(override val stage: Stage) : Outcome
    data class Accepted(val orders: List<String>, val message: String = "") : Outcome { override val stage get() = Stage.ACCEPTED }
  }

  private val counters = java.util.EnumMap<Stage, Int>(Stage::class.java)
  @Synchronized fun counters(): Map<Stage, Int> = HashMap(counters)

  fun evaluate(notification: ProposalNotification, config: ProposalFilterConfig): Outcome {
    val outcome = decide(notification, config)
    synchronized(this) { counters[outcome.stage] = (counters[outcome.stage] ?: 0) + 1 }
    return outcome
  }

  private fun decide(n: ProposalNotification, config: ProposalFilterConfig): Outcome {
    if (config.expectedPackage == null || n.packageName != config.expectedPackage) return Outcome.Dropped(Stage.PACKAGE)
    if (n.isGroup()) return Outcome.Dropped(Stage.GROUP)
    val contact = config.contactName?.takeIf { it.isNotBlank() } ?: return Outcome.Dropped(Stage.CONTACT)
    // With MessagingStyle the newest message must come from the contact; without it, the title must be the contact.
    val latest = n.latestMessage()
    val fromContact = if (latest != null) latest.sender == contact else n.title() == contact
    if (!fromContact) return Outcome.Dropped(Stage.CONTACT)
    val text = n.text()?.takeIf { it.isNotBlank() } ?: return Outcome.Dropped(Stage.NO_PROPOSAL)
    val orders = InstinctProposalProtocol.orders(text)
    if (orders.isEmpty()) return Outcome.Dropped(Stage.NO_PROPOSAL)
    if (!dedupe.firstTime(n.key, text, config.now)) return Outcome.Dropped(Stage.DUPLICATE)
    return Outcome.Accepted(orders, InstinctLogEvents.messageId(MessageDedupe.id(n.key, text)))
  }
}

/**
 * Remembers which proposal messages were already processed, by notification key and a hash of the text, in memory
 * only and for a bounded time. A WhatsApp update of the same notification with the same newest message is a
 * duplicate; a new message from the contact is not. The text itself is never kept.
 */
class MessageDedupe(private val retentionMillis: Long = 6 * 60 * 60_000L, private val capacity: Int = 256) {
  private val seen = LinkedHashMap<String, Long>()

  @Synchronized fun firstTime(key: String, text: String, now: Long): Boolean {
    seen.entries.removeAll { now - it.value > retentionMillis || now < it.value }
    val id = id(key, text)
    if (id in seen) return false
    seen[id] = now
    while (seen.size > capacity) seen.remove(seen.keys.first())
    return true
  }

  @Synchronized fun clear() = seen.clear()

  companion object {
    /** The hash a message is remembered by: notification key and text. */
    fun id(key: String, text: String): String = sha256("$key\u0000$text")
  }
}

internal fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Proposals awaiting the user, in memory only. Each one can be executed at most once: a claim moves it out of the
 * pending state atomically, so a second Confirmar, a repeated «dale» or a notification action delivered twice gets
 * [Claim.Used]. A proposal expires [ttlMillis] after it arrived. [fingerprint] identifies the configuration the
 * proposal was validated under (switch, contact, WhatsApp package); a claim under another configuration fails and
 * the proposal can no longer run.
 */
class ProposalBook(private val clock: () -> Long, private val ttlMillis: Long = TTL_MILLIS) {
  data class Proposal(
    val id: Long,
    val order: String,
    val command: PhoneCommand,
    val createdAt: Long,
    val fingerprint: String,
    /** Random, carried only by Insto's own notification action: an id alone, which another app could guess, confirms nothing. */
    val token: String = java.util.UUID.randomUUID().toString(),
    /** Where it came from (1.16.0): WhatsApp, unsigned, or the signed mailbox. */
    val source: ProposalSource = ProposalSource.WHATSAPP,
    /** Its row in the execution journal (1.20.0); null for a proposal made outside it. */
    val executionId: String? = null,
  ) {
    val summary: String get() = PhoneCommandCodec.summary(command)
  }

  sealed interface Claim {
    data class Granted(val proposal: Proposal) : Claim
    data object Expired : Claim
    data object Used : Claim
    data object Changed : Claim
    data object Unknown : Claim
  }

  private enum class State { PENDING, CLAIMED, DISCARDED, EXPIRED }
  private class Entry(val proposal: Proposal, var state: State)

  private var nextId = 1L
  private val entries = LinkedHashMap<Long, Entry>()

  /**
   * Adds a validated proposal. Null when the same execution (1.20.0), or, for a proposal without one, the same command,
   * is already pending: the same proposal is never offered twice. Two orders of different messages with the same text
   * have different executions and are both offered.
   */
  @Synchronized fun offer(order: String, command: PhoneCommand, fingerprint: String, source: ProposalSource = ProposalSource.WHATSAPP,
    executionId: String? = null, createdAt: Long = clock()): Proposal? {
    expire()
    if (entries.values.any { it.state == State.PENDING &&
        if (executionId != null) it.proposal.executionId == executionId else it.proposal.executionId == null && it.proposal.command == command }) return null
    // [createdAt] is only ever earlier than now (a confirmation recovered with the time it had left), never later.
    val proposal = Proposal(nextId++, order, command, minOf(createdAt, clock()), fingerprint, source = source, executionId = executionId)
    entries[proposal.id] = Entry(proposal, State.PENDING)
    while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    return proposal
  }

  /** [token] is required when the claim comes from outside the process-local panel (the notification action). */
  @Synchronized fun claim(id: Long, fingerprint: String?, token: String? = null, requireToken: Boolean = false): Claim {
    expire()
    val entry = entries[id] ?: return Claim.Unknown
    if (requireToken && token != entry.proposal.token) return Claim.Unknown
    return when (entry.state) {
      State.EXPIRED -> Claim.Expired
      State.CLAIMED, State.DISCARDED -> Claim.Used
      State.PENDING -> if (fingerprint == null || fingerprint != entry.proposal.fingerprint) {
        entry.state = State.DISCARDED; Claim.Changed
      } else { entry.state = State.CLAIMED; Claim.Granted(entry.proposal) }
    }
  }

  /** True when [id] was pending; a discarded proposal can never be claimed. */
  @Synchronized fun discard(id: Long): Boolean {
    val entry = entries[id] ?: return false
    if (entry.state != State.PENDING) return false
    entry.state = State.DISCARDED
    return true
  }

  /**
   * Expires [id] now, before its time: its reply handle is about to lapse and Instinct must still hear that it
   * expired. Expiring early only ever runs less. True when it was pending.
   */
  @Synchronized fun expireNow(id: Long): Boolean {
    expire()
    val entry = entries[id] ?: return false
    if (entry.state != State.PENDING) return false
    entry.state = State.EXPIRED
    return true
  }

  /** The proposal [id] whatever its state, to report on it; never to run it (only [claim] does). */
  @Synchronized fun find(id: Long): Proposal? = entries[id]?.proposal

  @Synchronized fun isPending(id: Long): Boolean { expire(); return entries[id]?.state == State.PENDING }

  @Synchronized fun pending(): List<Proposal> { expire(); return entries.values.filter { it.state == State.PENDING }.map { it.proposal } }

  /** Discards every pending proposal, for example when the switch is turned off. Returns the ids discarded. */
  @Synchronized fun discardAll(): List<Long> {
    val ids = entries.values.filter { it.state == State.PENDING }.map { it.proposal.id }
    ids.forEach { entries.getValue(it).state = State.DISCARDED }
    return ids
  }

  private fun expire() {
    val now = clock()
    entries.values.forEach { if (it.state == State.PENDING && (now - it.proposal.createdAt > ttlMillis || now < it.proposal.createdAt)) it.state = State.EXPIRED }
  }

  companion object {
    const val TTL_MILLIS = 10 * 60_000L
    private const val CAPACITY = 64

    /** The configuration a proposal is valid under. Hashed: neither the name nor the number is kept in clear. */
    fun fingerprint(contactName: String, contactPhone: String, packageName: String): String =
      sha256("$contactName\u0000$contactPhone\u0000$packageName")
  }
}

/**
 * Anti-loop brake for automatic execution: at most [max] executions per [periodMillis], in memory. Beyond that a
 * proposal falls back to Confirmar/Descartar.
 */
class AutoRunBudget(private val clock: () -> Long, private val max: Int = 6, private val periodMillis: Long = 10 * 60_000L) {
  private val runs = ArrayDeque<Long>()
  /** Whether [tryAcquire] would succeed now, without taking anything (1.18.0, for «probar»). */
  @Synchronized fun available(): Boolean { prune(clock()); return runs.size < max }
  private fun prune(now: Long) {
    while (runs.isNotEmpty() && (now - runs.first() >= periodMillis || now < runs.first())) runs.removeFirst()
  }
  @Synchronized fun tryAcquire(): Boolean {
    val now = clock()
    prune(now)
    if (runs.size >= max) return false
    runs.addLast(now)
    return true
  }
  @Synchronized fun reset() = runs.clear()
}
