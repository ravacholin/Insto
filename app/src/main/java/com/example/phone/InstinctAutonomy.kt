package com.example.phone

import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Autonomía por acción» (1.16.0): what Instinct may do alone, category by category. The pure part: categories,
 * levels, quiet hours, the policy decision, how it combines with the existing blockers and the trust ledger that
 * lowers a category after repeated undos. Nothing here touches Android. See docs/INSTINCT-PROPUESTAS.md,
 * «Autonomía por acción».
 */
enum class InstinctCategory(val label: String) {
  VOLUMEN("volumen"),
  LINTERNA("linterna"),
  SILENCIO("silencio y No molestar"),
  MUSICA("música"),
  RELOJ("alarmas y temporizadores"),
  APPS("abrir apps y búsquedas"),
  CALENDARIO("calendario"),
  RUTINAS("rutinas"),
  /** «#insto avisarme …» (fase 3): a local notice for the person, only from the signed mailbox. */
  AVISOS("avisos para vos"),
  PREGUNTAS("Preguntas"),
  ;

  companion object {
    private val silence = setOf(PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON,
      PlatformAction.DND_ON, PlatformAction.DND_OFF)

    /** The category of one allowed command; null for anything outside the allowlist. A deferred order uses its inner one. */
    fun of(command: PhoneCommand): InstinctCategory? = when (command) {
      is PhoneCommand.Volume -> VOLUMEN
      is PhoneCommand.Torch -> LINTERNA
      is PhoneCommand.TemporaryMode -> if (command.kind == ModeKind.SILENT || command.kind == ModeKind.DND) SILENCIO else null
      is PhoneCommand.Media -> MUSICA
      is PhoneCommand.Alarm, is PhoneCommand.Timer, is PhoneCommand.ClockList -> RELOJ
      is PhoneCommand.OpenApp, is PhoneCommand.MusicSearch, is PhoneCommand.SpotifySearch, is PhoneCommand.YouTubeSearch -> APPS
      is PhoneCommand.CalendarEvent -> CALENDARIO
      is PhoneCommand.Platform -> when {
        command.action == PlatformAction.INSTINCT_CAPABILITY -> InstinctCapabilities.specs[command.argument]?.category
        command.action == PlatformAction.ROUTINE_RUN -> RUTINAS
        command.action in silence -> SILENCIO
        else -> null
      }
      else -> null
    }

    /** Silence, vibrate or Do not disturb turned on: the phone gets quieter. Turning them off does not. */
    fun lowersNoise(command: PhoneCommand): Boolean = when (command) {
      is PhoneCommand.Platform -> command.action == PlatformAction.SILENT_ON || command.action == PlatformAction.VIBRATE_ON ||
        command.action == PlatformAction.DND_ON
      is PhoneCommand.TemporaryMode -> command.kind == ModeKind.SILENT || command.kind == ModeKind.DND
      else -> false
    }
  }
}

/** How much Instinct may do alone in a category. Declared from the loosest to the strictest. */
enum class InstinctAutonomy(val label: String) {
  /** Runs and tells afterwards, with «Deshacer» when it can be undone. */
  SOLO("Solo"),
  /** Waits as «Instinct propone: …». */
  CONFIRMAR("Confirmar"),
  /** Refused: «#insto-error … → la persona no permite esta acción». */
  NUNCA("Nunca"),
}

/** A daily range in minutes from midnight, [start] included, [end] excluded. Crosses midnight when start > end. */
data class QuietHours(val start: Int, val end: Int) {
  init { require(start in 0 until DAY && end in 0 until DAY) }

  fun contains(minuteOfDay: Int): Boolean = when {
    start == end -> false
    start < end -> minuteOfDay in start until end
    else -> minuteOfDay >= start || minuteOfDay < end
  }

  val label: String get() = "de ${clock(start)} a ${clock(end)}"

  companion object {
    const val DAY = 24 * 60
    fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    private val HHMM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    /** «07:00» → 420; null when it is not a 24-hour time. */
    fun parse(text: String): Int? = HHMM.matchEntire(text.trim())?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
  }
}

/** What the policy says about one order, before the existing blockers are combined with it. */
sealed interface AutonomyDecision {
  data object Run : AutonomyDecision
  data class Confirm(val reason: String) : AutonomyDecision
  data class Refuse(val reason: String) : AutonomyDecision
}

/**
 * The person's policy. A missing category is [InstinctAutonomy.SOLO], the behavior before 1.16.0. [quiet] is off
 * when null. [adjusted] is when a category was lowered automatically (wall-clock millis), for «Ajustada
 * automáticamente el DD/MM».
 */
data class InstinctAutonomyPolicy(
  val levels: Map<InstinctCategory, InstinctAutonomy> = emptyMap(),
  val quiet: QuietHours? = null,
  /** «Permitir que silencie el teléfono durante las horas de silencio». */
  val silenceInQuiet: Boolean = true,
  val adjusted: Map<InstinctCategory, Long> = emptyMap(),
) {
  fun level(category: InstinctCategory): InstinctAutonomy = levels[category] ?: InstinctAutonomy.SOLO

  /** [category] at [level]. Choosing a level by hand clears the «ajustada automáticamente» mark. */
  fun with(category: InstinctCategory, level: InstinctAutonomy, adjustedAt: Long? = null): InstinctAutonomyPolicy = copy(
    levels = if (level == InstinctAutonomy.SOLO) levels - category else levels + (category to level),
    adjusted = if (adjustedAt != null) adjusted + (category to adjustedAt) else adjusted - category)

  /**
   * The decision for one order whose [steps] are given (a routine expanded, otherwise the command itself). A routine
   * uses [InstinctCategory.RUTINAS] and also the strictest of its steps. Within [quiet] hours every [SOLO] becomes
   * [CONFIRMAR], except silencing the phone when [silenceInQuiet] allows it. [minuteOfDay] is when it runs.
   */
  fun decide(steps: List<PhoneCommand>, routine: Boolean, minuteOfDay: Int): AutonomyDecision {
    val categories = (if (routine) listOf(InstinctCategory.RUTINAS) else emptyList()) + steps.mapNotNull(InstinctCategory::of)
    if (categories.isEmpty()) return AutonomyDecision.Run
    val strictest = categories.maxBy { level(it).ordinal }
    return when (level(strictest)) {
      InstinctAutonomy.NUNCA -> AutonomyDecision.Refuse(REFUSED)
      InstinctAutonomy.CONFIRMAR -> AutonomyDecision.Confirm("la persona pide confirmación para ${strictest.label}")
      InstinctAutonomy.SOLO -> {
        val exempt = silenceInQuiet && !routine && steps.isNotEmpty() && steps.all(InstinctCategory::lowersNoise)
        if (quiet?.contains(minuteOfDay) == true && !exempt) AutonomyDecision.Confirm(QUIET) else AutonomyDecision.Run
      }
    }
  }

  /** Refused right away, whatever the hour: some category of the order is [InstinctAutonomy.NUNCA]. */
  fun refuses(steps: List<PhoneCommand>, routine: Boolean): Boolean =
    ((if (routine) listOf(InstinctCategory.RUTINAS) else emptyList()) + steps.mapNotNull(InstinctCategory::of))
      .any { level(it) == InstinctAutonomy.NUNCA }

  companion object {
    const val REFUSED = "la persona no permite esta acción"
    const val QUIET = "horas de silencio"

    /** Migration from 1.15.0: «Pedirme confirmación antes de ejecutar» on means every category starts at Confirmar. */
    fun migrated(askFirst: Boolean): InstinctAutonomyPolicy =
      if (!askFirst) InstinctAutonomyPolicy()
      else InstinctAutonomyPolicy(InstinctCategory.entries.filterNot { it == InstinctCategory.PREGUNTAS }
        .associateWith { InstinctAutonomy.CONFIRMAR })

    fun encode(policy: InstinctAutonomyPolicy): String = JSONObject()
      .put("levels", JSONObject().apply { policy.levels.toSortedMap().forEach { (k, v) -> put(k.name, v.name) } })
      .put("silenceInQuiet", policy.silenceInQuiet)
      .put("adjusted", JSONObject().apply { policy.adjusted.toSortedMap().forEach { (k, v) -> put(k.name, v) } })
      .apply { policy.quiet?.let { put("quiet", JSONObject().put("start", it.start).put("end", it.end)) } }
      .toString()

    /** Unknown categories or levels are ignored: a newer file never loosens anything here. */
    fun decode(text: String): InstinctAutonomyPolicy {
      require(text.length <= 16_384)
      val o = JSONObject(text)
      val levels = o.optJSONObject("levels")?.let { l ->
        l.keys().asSequence().mapNotNull { key ->
          val category = InstinctCategory.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
          val level = InstinctAutonomy.entries.firstOrNull { it.name == l.optString(key) } ?: return@mapNotNull null
          category to level
        }.toMap()
      }.orEmpty()
      val adjusted = o.optJSONObject("adjusted")?.let { a ->
        a.keys().asSequence().mapNotNull { key ->
          InstinctCategory.entries.firstOrNull { it.name == key }?.let { it to a.optLong(key) }
        }.filter { it.second > 0 }.toMap()
      }.orEmpty()
      val quiet = o.optJSONObject("quiet")?.let { q -> runCatching { QuietHours(q.getInt("start"), q.getInt("end")) }.getOrNull() }
      return InstinctAutonomyPolicy(levels, quiet, o.optBoolean("silenceInQuiet", true), adjusted)
    }
  }
}

/** «Las órdenes por WhatsApp (sin firma) como máximo en»: a cap that only ever makes an unsigned order stricter. */
object InstinctSourceCap {
  const val REASON = "las órdenes por WhatsApp, sin firma, esperan confirmación"

  fun apply(decision: AutonomyDecision, source: ProposalSource, whatsappCap: InstinctAutonomy): AutonomyDecision =
    if (source == ProposalSource.WHATSAPP && whatsappCap != InstinctAutonomy.SOLO && decision == AutonomyDecision.Run)
      AutonomyDecision.Confirm(REASON) else decision
}

/**
 * How the policy combines with the blockers that already existed (phone locked, another app without Accessibility,
 * a deferred order that fired late, a routine that changed). Structural on purpose: the existing blocker is decided
 * first and the policy after, so the policy can only make an order stricter, never looser. The anti-loop budget is
 * acquired last, only for an order that would really run.
 */
object InstinctAutonomyGate {
  sealed interface Gate {
    data object Run : Gate
    data class Wait(val reason: String) : Gate
    data class Refuse(val reason: String) : Gate
  }

  const val BUDGET = "Instinct pidió muchas acciones seguidas; esta espera tu confirmación."

  fun combine(structural: String?, policy: AutonomyDecision, acquire: () -> Boolean): Gate {
    // «Nunca» is stricter than waiting: it wins over everything.
    if (policy is AutonomyDecision.Refuse) return Gate.Refuse(policy.reason)
    if (structural != null) return Gate.Wait(structural)
    if (policy is AutonomyDecision.Confirm) return Gate.Wait(policy.reason)
    return if (acquire()) Gate.Run else Gate.Wait(BUDGET)
  }
}

/**
 * A photo of what the gate looks at (1.18.0), taken once: whether the phone is locked, Insto's Accessibility, the open
 * panel ([foreground]), whether the anti-loop brake has room, the policy, the minute of the day it runs, where the
 * order came from and the cap for WhatsApp. No effects: reading it changes nothing.
 */
data class GateSnapshot(
  val locked: Boolean,
  val accessibility: Boolean,
  val foreground: Boolean,
  val budgetAvailable: Boolean,
  val policy: InstinctAutonomyPolicy,
  val minuteOfDay: Int,
  val source: ProposalSource = ProposalSource.WHATSAPP,
  val whatsappCap: InstinctAutonomy = InstinctAutonomy.SOLO,
)

/**
 * The gate's decision as a pure function (1.18.0): the existing blockers first, then the policy (with the cap for
 * WhatsApp), then the brake, exactly as [InstinctAutonomyGate.combine] says. The real path ([InstinctProposalCenter.gate])
 * calls [decide] with the brake's `tryAcquire`; «probar» calls [preview], which only looks at whether the brake has room.
 * So a test and an execution can never decide differently.
 */
object InstinctGatePreview {
  /** Only for what opens a screen (1.20.0): a setting changes with the phone locked or the screen off. */
  const val LOCKED = "El teléfono estaba bloqueado y esta acción abre una pantalla."
  const val NO_ACCESSIBILITY = "Para abrir otra app sin tocar nada, Android necesita el servicio de Accesibilidad de Insto activo."
  const val ROUTINE_CHANGED = "La rutina ya no está habilitada para Instinct o cambió."

  /** Commands that open another app's screen. From the background Android only allows that while Insto is exempt. */
  fun opensActivity(command: PhoneCommand): Boolean = when (command) {
    is PhoneCommand.Volume, is PhoneCommand.Torch, is PhoneCommand.Media, is PhoneCommand.TemporaryMode -> false
    is PhoneCommand.Platform -> command.action == PlatformAction.INSTINCT_CAPABILITY && !LockScreenPolicy.runsLocked(command)
    else -> true
  }

  /**
   * The checks that existed before the autonomy policy, for one command, without the brake. Since 1.20.0 a locked
   * phone stops only what cannot happen locked ([LockScreenPolicy.runsLocked]): volume, torch, silence, vibration, Do
   * not disturb, media, temporary modes and reads run with the phone locked or the screen off, as Android allows.
   */
  fun blocker(command: PhoneCommand, snapshot: GateSnapshot): String? = when {
    !snapshot.foreground && snapshot.locked && !LockScreenPolicy.runsLocked(command) -> LOCKED
    !snapshot.foreground && opensActivity(command) && !snapshot.accessibility -> NO_ACCESSIBILITY
    else -> null
  }

  /** [steps] are the routine expanded (null: it is no longer enabled or changed) or the command itself. */
  fun decide(steps: List<PhoneCommand>?, routine: Boolean, snapshot: GateSnapshot, acquire: () -> Boolean): InstinctAutonomyGate.Gate {
    // A routine is checked as a whole before its first step: one step that cannot run leaves all of it for Confirmar.
    val structural = if (steps == null) ROUTINE_CHANGED else steps.firstNotNullOfOrNull { blocker(it, snapshot) }
    // Unsigned orders from WhatsApp may be capped at Confirmar; that cap only ever hardens the policy.
    val policy = InstinctSourceCap.apply(snapshot.policy.decide(steps ?: emptyList(), routine, snapshot.minuteOfDay),
      snapshot.source, snapshot.whatsappCap)
    return InstinctAutonomyGate.combine(structural, policy, acquire)
  }

  /** The same decision, without consuming anything. */
  fun preview(steps: List<PhoneCommand>?, routine: Boolean, snapshot: GateSnapshot): InstinctAutonomyGate.Gate =
    decide(steps, routine, snapshot) { snapshot.budgetAvailable }

  /**
   * What the action itself would ask of the person even after the gate lets it run, when it can be known without
   * running it: a temporary mode always waits for its confirmation, and an alarm for a time already past today
   * asks whether it is for tomorrow. [minuteOfDay] is now. Choosing among several apps or a permission only shows up
   * when it runs.
   */
  fun needs(command: PhoneCommand, minuteOfDay: Int): String? = when {
    // 1.20.0: a temporary mode the gate lets run is authorized: its own «¿Confirmás?» is not asked again
    // ([InstinctConfirmations]). Only a missing permission or exact alarms can still make it wait, when it runs.
    command is PhoneCommand.Alarm && command.date == null && command.days.isEmpty() && command.hour * 60 + command.minute <= minuteOfDay ->
      "esa hora ya pasó hoy; la persona tiene que confirmar que es para mañana"
    else -> null
  }
}

/**
 * What can happen with the phone locked or the screen off (1.20.0): a setting changed through a system service or a
 * read, with no screen to show. Android lets any app do these from the background with the permissions Insto already
 * asks for (and the lock screen's quick settings allow them too). Opening another app, the Clock, a search, a call or
 * a file needs the phone unlocked, so those keep waiting.
 */
object LockScreenPolicy {
  private val lockSafe = setOf(PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON,
    PlatformAction.DND_ON, PlatformAction.DND_OFF, PlatformAction.BATTERY, PlatformAction.ROUTINE_LIST)

  fun runsLocked(command: PhoneCommand): Boolean = when (command) {
    is PhoneCommand.Volume, is PhoneCommand.Torch, is PhoneCommand.Media, is PhoneCommand.TemporaryMode, PhoneCommand.EndMode -> true
    is PhoneCommand.Platform -> command.action in lockSafe || command.action == PlatformAction.INSTINCT_CAPABILITY &&
      InstinctCapabilities.specs[command.argument]?.runsLocked == true
    else -> false
  }
}

/**
 * The action's own questions that an authorized order does not ask again (1.20.0). Under «Solo» the person already
 * said yes for the category: a temporary mode's «¿Confirmás?» is that same yes. Real questions stay: an alarm for a time
 * already past, which app or contact, a missing permission, and exact alarms that are not allowed (the restoration
 * would be approximate).
 */
object InstinctConfirmations {
  fun preAuthorized(command: PhoneCommand): Boolean = command is PhoneCommand.TemporaryMode
}

/** «#insto probar <orden>» (1.18.0): read-only, with its own brake, never the anti-loop one. Pure. */
object InstinctProbe {
  const val PHRASE = "probar"
  const val PER_PERIOD = 20
  const val PERIOD_MILLIS = 10 * 60_000L
  const val TOO_MANY = "demasiadas pruebas seguidas"
  const val RULE = "Si no estás seguro de una forma o de si va a quedar esperando, probala antes con \"probar <orden>\"."

  /** What would happen NOW, in the fixed forms Instinct reads. */
  sealed interface Verdict {
    val detail: String
    data class Alone(val category: String) : Verdict { override val detail get() = "válida · $category · la haría sola" }
    data class Waits(val category: String, val reason: String) : Verdict {
      override val detail get() = "válida · $category · esperaría confirmación: ${reason.trim().trimEnd('.').replaceFirstChar { it.lowercaseChar() }}"
    }
    data class Scheduled(val label: String) : Verdict { override val detail get() = "válida · programada: $label" }
    data class Invalid(val reason: String) : Verdict { override val detail get() = "no válida: ${reason.trim().trimEnd('.')}" }
    data object Refused : Verdict { override val detail get() = "rechazada: ${InstinctAutonomyPolicy.REFUSED}" }
  }

  /** The category in words; a routine is «rutinas», a query «consulta». */
  fun category(command: PhoneCommand): String = InstinctCategory.of(command)?.label ?: "acción"

  /**
   * A valid immediate order: [gate] is [InstinctGatePreview.preview]'s decision and [need] what the action would ask
   * of the person ([InstinctGatePreview.needs]); the gate's reasons come first, as in the real path.
   */
  fun verdict(command: PhoneCommand, gate: InstinctAutonomyGate.Gate, need: String?): Verdict = when (gate) {
    is InstinctAutonomyGate.Gate.Refuse -> Verdict.Refused
    is InstinctAutonomyGate.Gate.Wait -> Verdict.Waits(category(command), gate.reason)
    InstinctAutonomyGate.Gate.Run -> if (need != null) Verdict.Waits(category(command), need) else Verdict.Alone(category(command))
  }
}

/** What the trust ledger counts. Only counters: never an order or a text. */
enum class TrustEvent(val code: String) {
  EJECUTADA("ejecutada"), DESHECHA("deshecha"), CONFIRMADA("confirmada"), DESCARTADA("descartada"), VENCIDA("vencida"),
}

/**
 * «Ajuste adaptativo»: counters per category and day, at most [RETENTION_DAYS] days. Lowers a category that keeps
 * being undone and suggests (never applies) raising one the person keeps confirming. The notification actions it
 * issues carry a random single-use token, stored here so it survives a process restart. [clock] is wall-clock
 * millis; days are local days in [zone].
 */
class InstinctTrustLedger(private val clock: () -> Long, private val zone: () -> TimeZone = TimeZone::getDefault) {
  enum class Action { RESTORE, SUGGEST }
  data class Pending(val action: Action, val category: InstinctCategory, val issuedAt: Long)
  data class Lowering(val undone: Int, val executed: Int)

  /** day → category → counts by [TrustEvent.ordinal]. */
  private val days = java.util.TreeMap<Long, MutableMap<InstinctCategory, IntArray>>()
  private val snoozed = HashMap<InstinctCategory, Long>()
  private val suggested = HashMap<InstinctCategory, Long>()
  private val tokens = LinkedHashMap<String, Pending>()

  private fun today(): Long = day(clock())
  private fun day(millis: Long): Long = Math.floorDiv(millis + zone().getOffset(millis), DAY_MILLIS)

  @Synchronized fun record(category: InstinctCategory, event: TrustEvent) {
    prune()
    days.getOrPut(today()) { HashMap() }.getOrPut(category) { IntArray(TrustEvent.entries.size) }[event.ordinal]++
  }

  /** [event]s of [category] in the last [window] days, today included. */
  @Synchronized fun count(category: InstinctCategory, event: TrustEvent, window: Int): Int {
    val from = today() - window + 1
    return days.tailMap(from, true).values.sumOf { it[category]?.get(event.ordinal) ?: 0 }
  }

  /**
   * A [InstinctAutonomy.SOLO] category to lower: in the last 7 days 3 or more undos, or at least 30 % undone with 5
   * or more executed. Null otherwise, and never for another level.
   */
  @Synchronized fun lowering(category: InstinctCategory, level: InstinctAutonomy): Lowering? {
    if (level != InstinctAutonomy.SOLO) return null
    val questions = category == InstinctCategory.PREGUNTAS
    val undone = if (questions) count(category, TrustEvent.DESCARTADA, LOWER_WINDOW_DAYS) +
      count(category, TrustEvent.VENCIDA, LOWER_WINDOW_DAYS) else count(category, TrustEvent.DESHECHA, LOWER_WINDOW_DAYS)
    val executed = count(category, TrustEvent.EJECUTADA, LOWER_WINDOW_DAYS) + if (questions) undone else 0
    val lower = undone >= LOWER_UNDONE || (executed >= LOWER_MIN_EXECUTED && undone * 100 >= LOWER_PERCENT * executed)
    return if (lower) Lowering(undone, executed) else null
  }

  /**
   * A [InstinctAutonomy.CONFIRMAR] category the person keeps confirming: 8 or more confirmations and no discards in
   * 14 days, not silenced by a «No» and not suggested in the last week. Raising is only ever suggested.
   */
  @Synchronized fun suggestsRaise(category: InstinctCategory, level: InstinctAutonomy): Boolean {
    if (level != InstinctAutonomy.CONFIRMAR) return false
    val now = clock()
    if ((snoozed[category] ?: 0L) > now) return false
    if (suggested[category]?.let { now - it in 0 until SUGGESTION_PENDING_MILLIS } == true) return false
    return count(category, TrustEvent.CONFIRMADA, RAISE_WINDOW_DAYS) >= RAISE_CONFIRMED &&
      count(category, TrustEvent.DESCARTADA, RAISE_WINDOW_DAYS) == 0
  }

  @Synchronized fun markSuggested(category: InstinctCategory) { suggested[category] = clock() }

  /** «No» to a suggestion: silenced for 30 days. */
  @Synchronized fun snooze(category: InstinctCategory) { snoozed[category] = clock() + SNOOZE_MILLIS; suggested.remove(category) }

  /** Back to Solo, from the notification or Settings: the category starts counting again. */
  @Synchronized fun reset(category: InstinctCategory) {
    days.values.forEach { it.remove(category) }
    days.entries.removeAll { it.value.isEmpty() }
    suggested.remove(category)
  }

  /** A random token for one notification action; single use, kept at most [TOKEN_TTL_MILLIS]. */
  @Synchronized fun issue(action: Action, category: InstinctCategory): String {
    prune()
    val token = java.util.UUID.randomUUID().toString()
    tokens[token] = Pending(action, category, clock())
    while (tokens.size > MAX_TOKENS) tokens.remove(tokens.keys.first())
    return token
  }

  /** The action of [token], once: a second use, an unknown or an expired token gives null. */
  @Synchronized fun redeem(token: String?, action: Action): Pending? {
    prune()
    val pending = tokens[token ?: return null] ?: return null
    if (pending.action != action) return null
    tokens.remove(token)
    return pending
  }

  private fun prune() {
    val now = clock()
    val oldest = day(now) - RETENTION_DAYS + 1
    days.headMap(oldest, false).clear()
    // A clock set back leaves nothing in the future.
    days.tailMap(day(now), false).clear()
    tokens.entries.removeAll { now - it.value.issuedAt !in 0..TOKEN_TTL_MILLIS }
    snoozed.entries.removeAll { it.value <= now }
  }

  @Synchronized fun encode(): String {
    prune()
    return JSONObject()
      .put("days", JSONObject().apply {
        days.forEach { (day, byCategory) ->
          put(day.toString(), JSONObject().apply { byCategory.toSortedMap().forEach { (c, counts) -> put(c.name, JSONArray(counts.toList())) } })
        }
      })
      .put("snoozed", JSONObject().apply { snoozed.toSortedMap().forEach { (c, v) -> put(c.name, v) } })
      .put("suggested", JSONObject().apply { suggested.toSortedMap().forEach { (c, v) -> put(c.name, v) } })
      .put("tokens", JSONObject().apply { tokens.forEach { (t, p) -> put(t, JSONObject().put("action", p.action.name).put("category", p.category.name).put("at", p.issuedAt)) } })
      .toString()
  }

  /** Replaces the state with [text]; anything malformed is skipped. */
  @Synchronized fun load(text: String) {
    require(text.length <= 262_144)
    days.clear(); snoozed.clear(); suggested.clear(); tokens.clear()
    val o = JSONObject(text)
    fun category(key: String) = InstinctCategory.entries.firstOrNull { it.name == key }
    o.optJSONObject("days")?.let { d ->
      d.keys().forEach { key ->
        val day = key.toLongOrNull() ?: return@forEach
        val byCategory = d.optJSONObject(key) ?: return@forEach
        val map = HashMap<InstinctCategory, IntArray>()
        byCategory.keys().forEach { c ->
          val cat = category(c) ?: return@forEach
          val array = byCategory.optJSONArray(c) ?: return@forEach
          map[cat] = IntArray(TrustEvent.entries.size) { i -> array.optInt(i, 0).coerceIn(0, 100_000) }
        }
        if (map.isNotEmpty()) days[day] = map
      }
    }
    o.optJSONObject("snoozed")?.let { s -> s.keys().forEach { k -> category(k)?.let { snoozed[it] = s.optLong(k) } } }
    o.optJSONObject("suggested")?.let { s -> s.keys().forEach { k -> category(k)?.let { suggested[it] = s.optLong(k) } } }
    o.optJSONObject("tokens")?.let { t ->
      t.keys().forEach { k ->
        val p = t.optJSONObject(k) ?: return@forEach
        val action = Action.entries.firstOrNull { it.name == p.optString("action") } ?: return@forEach
        val cat = category(p.optString("category")) ?: return@forEach
        tokens[k] = Pending(action, cat, p.optLong("at"))
      }
    }
    prune()
  }

  companion object {
    const val DAY_MILLIS = 24 * 60 * 60_000L
    const val RETENTION_DAYS = 30
    const val LOWER_WINDOW_DAYS = 7
    const val LOWER_UNDONE = 3
    const val LOWER_MIN_EXECUTED = 5
    const val LOWER_PERCENT = 30
    const val RAISE_WINDOW_DAYS = 14
    const val RAISE_CONFIRMED = 8
    const val SNOOZE_MILLIS = 30 * DAY_MILLIS
    const val SUGGESTION_PENDING_MILLIS = 7 * DAY_MILLIS
    const val TOKEN_TTL_MILLIS = 7 * DAY_MILLIS
    private const val MAX_TOKENS = 16
  }
}

/** The catalog section «Qué hace Insto solo y qué espera confirmación», from the policy in force. */
object InstinctAutonomyCatalog {
  fun text(policy: InstinctAutonomyPolicy): String = buildString {
    appendLine("Qué hace Insto solo y qué espera confirmación (lo eligió la persona; puede cambiar):")
    for (level in InstinctAutonomy.entries) {
      val names = InstinctCategory.entries.filter { policy.level(it) == level }.map { it.label }
      if (names.isEmpty()) continue
      appendLine(when (level) {
        InstinctAutonomy.SOLO -> "- Lo hace solo: "
        InstinctAutonomy.CONFIRMAR -> "- Espera confirmación (${AckKind.WAITING.code}): "
        InstinctAutonomy.NUNCA -> "- Lo rechaza siempre (${AckKind.ERROR.code}): "
      } + names.joinToString(", ") + ".")
    }
    appendLine("- Una rutina espera confirmación si las rutinas o alguno de sus pasos la esperan. Una orden programada se evalúa cuando se dispara. Un evento de calendario siempre muestra la vista previa antes de guardarse.")
    val quiet = policy.quiet
    if (quiet != null) appendLine("- Horas de silencio, ${quiet.label}: en ese rango todo lo que haría solo espera confirmación" +
      (if (policy.silenceInQuiet) ", salvo silenciar el teléfono, ponerlo en vibración o activar No molestar." else "."))
    appendLine("- Con el teléfono bloqueado o la pantalla apagada hace solo volumen, linterna, silencio, vibración, No molestar, música y modos temporales.")
    append("- Además siempre esperan confirmación: con el teléfono bloqueado, lo que abre una pantalla; lo que abre otra app sin la Accesibilidad de Insto, " +
      "más de 6 órdenes en 10 minutos y una orden programada que se dispara con más de ${InstinctDeferral.LATE_MILLIS / 60_000} minutos de atraso.")
  }
}

/** One file written atomically, like [ConfiguredRoutineStore]: a temporary file, synced, then renamed. */
internal fun writeAtomically(file: File, text: String, prefix: String) {
  file.parentFile?.mkdirs()
  val temporary = File.createTempFile(prefix, ".tmp", file.absoluteFile.parentFile)
  try {
    FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    check(temporary.renameTo(file)) { "No se pudo guardar ${file.name}" }
  } finally { temporary.delete() }
}
