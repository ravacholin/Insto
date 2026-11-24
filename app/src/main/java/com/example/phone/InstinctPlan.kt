package com.example.phone

import java.util.Calendar
import org.json.JSONObject

/** «#insto plan …» after the validator: the parsed plan, or why it was refused. */
internal data class PlanCheck(override val order: String, val spec: InstinctPlanSpec?, val why: String? = null) : ProposalCheck

/**
 * Plans of several steps (1.23.0), the pure part: the explicit grammar of «#insto plan …», the program it compiles to,
 * the plan's states and the decisions the engine takes from what is on disk. Nothing here touches Android, the
 * journal or the network: [InstinctPlanEngine] runs the program with the executors, verifiers, permissions, questions
 * and recovery that already exist. Every action of a plan is an order of the allowlist that runs without a screen,
 * validated when the plan arrives and again right before it runs. No text is ever executed: a line that is not one of
 * the forms below is refused whole. See docs/INSTINCT-PROPUESTAS.md, «Planes de varios pasos».
 */
enum class PlanSetting(val phrase: String, val label: String) {
  VOLUME("volumen", "el volumen multimedia"),
  RINGER("sonido", "el modo de sonido"),
  DND("no molestar", "No molestar");

  companion object {
    fun of(name: String?): PlanSetting? = entries.firstOrNull { it.name == name }
  }
}

/** What a plan can wait for besides a time: the triggers of 1.17.0, once each. */
enum class PlanTrigger(val phrase: String) {
  EVENT_START("al empezar el próximo evento"),
  EVENT_END("al terminar el evento"),
  HEADPHONES_ON("al conectar auriculares"),
  HEADPHONES_OFF("al desconectar auriculares"),
  UNLOCK("al desbloquear");

  val byEvent: Boolean get() = this == EVENT_START || this == EVENT_END

  fun fires(event: PhoneEvent): Boolean = when (event) {
    PhoneEvent.HEADPHONES_ON -> this == HEADPHONES_ON
    PhoneEvent.HEADPHONES_OFF -> this == HEADPHONES_OFF
    PhoneEvent.UNLOCK -> this == UNLOCK
  }

  companion object {
    fun of(name: String?): PlanTrigger? = entries.firstOrNull { it.name == name }
  }
}

/** One action of a plan, as Instinct wrote it, and the explicit alternative that runs only if it fails. */
data class PlanAction(val line: String, val fallback: String? = null) {
  val count: Int get() = if (fallback == null) 1 else 2
}

/** One step. [text] is the step as written, for messages and the log. */
internal sealed interface PlanStep {
  val text: String

  data class Act(val action: PlanAction, override val text: String) : PlanStep
  data class Wait(val millis: Long, override val text: String) : PlanStep
  data class WaitUntil(val minuteOfDay: Int, override val text: String) : PlanStep
  data class WaitFor(val trigger: PlanTrigger, override val text: String) : PlanStep
  /** «si <condiciones> entonces <acciones> [si no <acciones>]»: evaluated once, when the plan reaches it. */
  data class Branch(val conditions: List<ContextCondition>, val then: List<PlanAction>, val otherwise: List<PlanAction>,
    override val text: String) : PlanStep
  /** «decidir <texto> | <opción> => <acciones> | …»: the plan waits for a button of the existing questions. */
  data class Decide(val question: String, val options: List<String>, val branches: List<List<PlanAction>>,
    override val text: String) : PlanStep
  data class Save(val settings: Set<PlanSetting>, override val text: String) : PlanStep
  data class Restore(val settings: Set<PlanSetting>, override val text: String) : PlanStep

  /** The actions this step may run, for limits and the autonomy check. */
  val actions: List<PlanAction> get() = when (this) {
    is Act -> listOf(action)
    is Branch -> then + otherwise
    is Decide -> branches.flatten()
    else -> emptyList()
  }

  /** The actions of [branch] (0: the only one, «entonces» or the first option). */
  fun branch(branch: Int?): List<PlanAction> = when (this) {
    is Act -> listOf(action)
    is Branch -> if (branch == 1) otherwise else then
    is Decide -> branch?.let(branches::getOrNull).orEmpty()
    else -> emptyList()
  }
}

/** A validated plan: its steps and when it lapses (wall-clock millis). */
internal data class InstinctPlanSpec(val line: String, val steps: List<PlanStep>, val expiresAt: Long) {
  /** Every action line of the plan, fallbacks and every branch included. */
  val actionLines: List<String> get() = steps.flatMap { it.actions }.flatMap { listOfNotNull(it.line, it.fallback) }
  val actionCount: Int get() = steps.sumOf { step -> step.actions.sumOf { it.count } }
  val summary: String get() = "${steps.size} ${if (steps.size == 1) "paso" else "pasos"}, " +
    "$actionCount ${if (actionCount == 1) "acción" else "acciones"}"

  sealed interface Parse {
    data class Valid(val spec: InstinctPlanSpec) : Parse
    data class Invalid(val why: String) : Parse
  }

  companion object {
    const val MAX_ACTIONS = 6
    const val MAX_STEPS = 10
    const val MAX_CHARS = 500
    const val MAX_ACTIVE = 5
    const val DEFAULT_EXPIRY_MILLIS = InstinctTriggers.DEFAULT_EXPIRY_MILLIS
    const val MAX_EXPIRY_MILLIS = InstinctTriggers.MAX_EXPIRY_MILLIS
    const val MAX_WAIT_MILLIS = 12 * 60 * 60_000L
    const val FORM = "plan[ hasta las HH:MM]: <paso>; <paso>; …"
    const val INVALID = "revisá la forma del plan: pasos separados por «;», hasta $MAX_ACTIONS acciones sin interfaz."
    const val STEP_FORMS = "<acción> [si falla <acción>] · esperar N minutos|horas · esperar hasta las HH:MM · " +
      "esperar al empezar el próximo evento|al terminar el evento|al conectar auriculares|al desconectar auriculares|al desbloquear · " +
      "si <condición> [y <condición>] entonces <acción> [+ <acción>] [si no <acción> [+ <acción>]] · " +
      "decidir <texto> | <opción> => <acción> [+ <acción>] | <opción> => … · guardar volumen|sonido|no molestar|estado · " +
      "restaurar [volumen|sonido|no molestar]"
    val EXAMPLES: List<String> = listOf(
      "plan: activar no molestar; poner el volumen al 20 por ciento; pausar la música",
      "plan: esperar al empezar el próximo evento; guardar no molestar; activar no molestar; esperar al terminar el evento; restaurar no molestar",
      "plan: decidir ¿Preferís silencio o música? | Silencio => activar no molestar + pausar la música | Música => desactivar no molestar + poner el volumen al 40 por ciento",
    )

    private val head = Regex("^plan(?=\\s|:|$)", RegexOption.IGNORE_CASE)
    private val whole = Regex("^plan(?:\\s+hasta las (\\d{1,2}):(\\d{2}))?\\s*:\\s*(\\S.*)$", RegexOption.IGNORE_CASE)
    private val waitFor = Regex("^esperar (\\d{1,3}) (minutos?|horas?)$")
    private val waitUntil = Regex("^esperar hasta las (\\d{1,2}) (\\d{2})$")
    private val conditional = Regex("^si\\s+(.+?)\\s+entonces\\s+(.+?)(?:\\s+si no\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val decision = Regex("^(?:decidir|preguntar)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val save = Regex("^guardar (.+)$")
    private val restore = Regex("^restaurar(?: (.+))?$")
    private val fails = Regex("\\s+si falla\\s+", RegexOption.IGNORE_CASE)
    private val sequence = Regex("\\s*\\+\\s*")
    private val separators = Regex("\\s*(?:,|\\by\\b)\\s*")

    const val LOG_KIND = "plan"

    fun check(order: String, validate: (String) -> Boolean, now: Calendar): PlanCheck =
      when (val parsed = parse(order, now, validate)) {
        is Parse.Valid -> PlanCheck(order, parsed.spec)
        is Parse.Invalid -> PlanCheck(order, null, parsed.why)
      }

    fun isOrder(line: String): Boolean = head.containsMatchIn(line.trimStart())

    /**
     * [validate] is the validator of one action: the same as «decidir» and the context rules use
     * ([InstinctProposalValidator.backgroundAction]); null refuses it. [now] is when the plan arrived.
     */
    fun parse(line: String, now: Calendar, validate: (String) -> Boolean): Parse {
      fun invalid(why: String) = Parse.Invalid(why)
      val text = line.trim()
      if (text.length > MAX_CHARS) return invalid("el plan tiene más de $MAX_CHARS caracteres.")
      if (text.any { it.isISOControl() || it == ' ' || it == ' ' } || !InstinctAck.safe(text)) return invalid(INVALID)
      val match = whole.matchEntire(text) ?: return invalid("empezá con «plan:» y separá los pasos con «;».")
      val expiresAt = if (match.groups[1] != null) {
        InstinctTriggers.untilTime(match.groupValues[1].toInt(), match.groupValues[2].toInt(), now)
          ?: return invalid("el tope «hasta las HH:MM» tiene que ser una hora de 00:00 a 23:59.")
      } else now.timeInMillis + DEFAULT_EXPIRY_MILLIS
      val raw = match.groupValues[3].split(';').map(String::trim).let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
      if (raw.isEmpty() || raw.any(String::isEmpty)) return invalid("hay un paso vacío entre dos «;».")
      if (raw.size > MAX_STEPS) return invalid("un plan admite como mucho $MAX_STEPS pasos.")
      val steps = ArrayList<PlanStep>()
      for (step in raw) steps += when (val parsed = step(step, validate)) {
        is StepParse.Ok -> parsed.step
        is StepParse.Bad -> return invalid("paso «${InstinctAck.cut(step, 60)}»: ${parsed.why}")
      }
      val spec = InstinctPlanSpec(text, steps, expiresAt)
      if (spec.actionCount == 0) return invalid("el plan no tiene ninguna acción.")
      if (spec.actionCount > MAX_ACTIONS) return invalid("un plan admite como mucho $MAX_ACTIONS acciones, contando alternativas y opciones.")
      if (steps.count { it is PlanStep.Decide } > 1) return invalid("un plan admite una sola decisión.")
      // What is saved is restored by a later step; anything else would be left behind.
      val saved = HashSet<PlanSetting>()
      for (s in steps) when (s) {
        is PlanStep.Save -> if (!saved.addAll(s.settings)) return invalid("ese ajuste ya estaba guardado.")
        is PlanStep.Restore -> {
          val wanted = s.settings.ifEmpty { saved.toSet() }
          if (wanted.isEmpty() || !saved.containsAll(wanted)) return invalid("«restaurar» necesita un «guardar» anterior del mismo ajuste.")
          saved.removeAll(wanted)
        }
        else -> Unit
      }
      if (saved.isNotEmpty()) return invalid("lo que se guarda se restaura con un paso «restaurar» posterior.")
      val waits = steps.filterIsInstance<PlanStep.Wait>().sumOf { it.millis }
      if (now.timeInMillis + waits >= expiresAt) return invalid("las esperas superan el plazo del plan.")
      return Parse.Valid(spec)
    }

    private sealed interface StepParse {
      data class Ok(val step: PlanStep) : StepParse
      data class Bad(val why: String) : StepParse
    }

    private fun step(text: String, validate: (String) -> Boolean): StepParse {
      val folded = PhoneCommandParser.normalize(text)
      if (folded.startsWith("esperar")) return waitStep(text, folded)
      save.matchEntire(folded)?.let { m ->
        val settings = settings(m.groupValues[1]) ?: return StepParse.Bad("se pueden guardar volumen, sonido, no molestar o estado.")
        return StepParse.Ok(PlanStep.Save(settings, text))
      }
      restore.matchEntire(folded)?.let { m ->
        val target = m.groups[1]?.value?.removePrefix("el ")?.trim()
        val settings = if (target == null || target in setOf("estado", "estado anterior", "todo")) emptySet()
          else settings(target) ?: return StepParse.Bad("se puede restaurar volumen, sonido, no molestar o estado.")
        return StepParse.Ok(PlanStep.Restore(settings, text))
      }
      decision.matchEntire(text)?.let { m -> return decide(text, m.groupValues[1], validate) }
      conditional.matchEntire(text)?.let { m ->
        val conditions = ContextRuleSpec.conditions(PhoneCommandParser.normalizeForClock(m.groupValues[1]))
          ?.takeIf { list -> list.none { it.signal == ContextSignal.UNLOCK } }
          ?: return StepParse.Bad("condición desconocida; usá las de «cuando», salvo «al desbloquear».")
        val then = actions(m.groupValues[2], validate) ?: return StepParse.Bad(ACTION)
        val otherwise = m.groups[3]?.value?.let { actions(it, validate) ?: return StepParse.Bad(ACTION) }.orEmpty()
        return StepParse.Ok(PlanStep.Branch(conditions, then, otherwise, text))
      }
      if (folded.startsWith("si ")) return StepParse.Bad("una condición se escribe «si <condición> entonces <acción>».")
      val action = action(text, validate) ?: return StepParse.Bad(ACTION)
      return StepParse.Ok(PlanStep.Act(action, text))
    }

    private const val ACTION = "no es una acción permitida que se pueda hacer sin abrir una pantalla."

    private fun waitStep(text: String, folded: String): StepParse {
      waitFor.matchEntire(folded)?.let { m ->
        val millis = m.groupValues[1].toLong() * if (m.groupValues[2].startsWith("hora")) 3_600_000L else 60_000L
        if (millis !in InstinctDeferral.MIN_DELAY_MILLIS..MAX_WAIT_MILLIS) return StepParse.Bad("una espera va de 1 minuto a 12 horas.")
        return StepParse.Ok(PlanStep.Wait(millis, text))
      }
      waitUntil.matchEntire(PhoneCommandParser.normalizeForClock(text).replace(':', ' '))?.let { m ->
        val hour = m.groupValues[1].toInt(); val minute = m.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return StepParse.Bad("la hora tiene que ser de 00:00 a 23:59.")
        return StepParse.Ok(PlanStep.WaitUntil(hour * 60 + minute, text))
      }
      val trigger = PlanTrigger.entries.firstOrNull { folded == "esperar " + PhoneCommandParser.normalize(it.phrase) }
        ?: return StepParse.Bad("esperas admitidas: N minutos, N horas, hasta las HH:MM o un disparador del catálogo.")
      return StepParse.Ok(PlanStep.WaitFor(trigger, text))
    }

    private fun decide(text: String, body: String, validate: (String) -> Boolean): StepParse {
      val fields = body.split('|').map(String::trim)
      if (fields.size !in 3..4) return StepParse.Bad("una decisión tiene un texto y 2 o 3 opciones separadas por «|».")
      val question = fields.first()
      if (!field(question, InstinctQuestion.MAX_TEXT)) return StepParse.Bad("el texto de la pregunta tiene como mucho ${InstinctQuestion.MAX_TEXT} caracteres.")
      val options = ArrayList<String>()
      val branches = ArrayList<List<PlanAction>>()
      for (option in fields.drop(1)) {
        val parts = option.split("=>").map(String::trim)
        if (parts.size != 2 || !field(parts[0], InstinctQuestion.MAX_OPTION)) return StepParse.Bad("cada opción es «<opción> => <acción>».")
        options += parts[0]
        branches += actions(parts[1], validate) ?: return StepParse.Bad(ACTION)
      }
      if (options.map(PhoneCommandParser::normalize).distinct().size != options.size) return StepParse.Bad("las opciones se repiten.")
      return StepParse.Ok(PlanStep.Decide(question, options, branches, text))
    }

    private fun field(value: String, max: Int) = value.length in 1..max && InstinctAck.safe(value)

    private fun actions(text: String, validate: (String) -> Boolean): List<PlanAction>? =
      text.split(sequence).map { part -> action(part.trim(), validate) ?: return null }.takeIf { it.isNotEmpty() }

    private fun action(text: String, validate: (String) -> Boolean): PlanAction? {
      val parts = text.split(fails, limit = 2).map(String::trim)
      if (parts.any { it.isEmpty() || it.length > 200 || '|' in it || "=>" in it || isOrder(it) || ContextRuleSpec.isOrder(it) ||
          InstinctQuestion.isRelated(it) || PhoneCommandParser.normalize(it).let { f -> f.startsWith("esperar") || f.startsWith("si ") } }) return null
      if (!parts.all(validate)) return null
      return PlanAction(parts[0], parts.getOrNull(1))
    }

    private fun settings(text: String): Set<PlanSetting>? {
      val words = text.trim().split(separators).map(String::trim).filter(String::isNotEmpty)
      if (words.isEmpty()) return null
      val out = LinkedHashSet<PlanSetting>()
      for (word in words) when (word) {
        "estado", "todo" -> out += PlanSetting.entries
        else -> out += PlanSetting.entries.firstOrNull { PhoneCommandParser.normalize(it.phrase) == word } ?: return null
      }
      return out
    }
  }
}

/** The plan's lifecycle. A terminal plan never changes state again. */
enum class PlanState(val code: String) {
  /** Accepted, the autonomy not decided yet. */
  PENDING("pendiente"),
  /** Waiting for the person to approve it (the category is Confirmar, quiet hours, the brake, a late wait). */
  CONFIRM("por confirmar"),
  RUNNING("en curso"),
  /** A time, an event or a trigger of the phone. */
  WAITING("esperando"),
  /** A decision is shown with its buttons. */
  DECIDING("decidiendo"),
  /** An action may or may not have happened: it is never repeated, and the plan waits to be resumed or cancelled. */
  SUSPENDED("suspendido"),
  DONE("completado"),
  FAILED("fallido"),
  CANCELLED("cancelado"),
  EXPIRED("vencido");

  val terminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED || this == EXPIRED

  companion object {
    fun of(name: String?): PlanState? = entries.firstOrNull { it.name == name }
    val ACTIVE: Set<PlanState> = entries.filterNot { it.terminal }.toSet()
  }
}

/** Who said yes: the category alone (Solo) or the person, once, for the whole plan. */
enum class PlanApproval { NONE, AUTO, PERSON;
  companion object { fun of(name: String?): PlanApproval = entries.firstOrNull { it.name == name } ?: NONE }
}

/** One saved setting: its value before, what the plan itself left, and whether it was already restored. */
data class PlanStash(val before: Int, val applied: Int? = null, val restored: Boolean = false)

/** A calendar instance a plan waits for. */
data class PlanEvent(val trigger: PlanTrigger, val eventId: Long, val instanceId: Long, val at: Long)

/** The plan row. [block] and [sub] are the next action to run; [branch] the branch chosen for [block]. */
data class PlanRecord(
  val id: String,
  /** The execution that brought «#insto plan …»; null for a plan restored from a backup. */
  val root: String?,
  val line: String,
  val source: ProposalSource,
  /** The log's short message id of Instinct's message. */
  val message: String,
  val fingerprint: String,
  val state: PlanState,
  val block: Int = 0,
  val sub: Int = 0,
  val branch: Int? = null,
  /** Running the explicit alternative of the action at [block].[sub]. */
  val fallback: Boolean = false,
  val approval: PlanApproval = PlanApproval.NONE,
  val createdAt: Long,
  val updatedAt: Long,
  val deadline: Long,
  /** When a wait ends: a time, an event's time, or the moment a phone trigger fired. */
  val wakeAt: Long? = null,
  val trigger: PlanTrigger? = null,
  val event: PlanEvent? = null,
  /** The question (approval or decision) the plan waits for: its execution id. */
  val question: String? = null,
  val stash: Map<PlanSetting, PlanStash> = emptyMap(),
  val result: String? = null,
  val reason: String? = null,
  val owner: String = "",
  /** Final work done: restoration, the last log event and the notification. */
  val closed: Boolean = false,
  /** Whether a plan that ends early puts back what it saved (false when «Recibir órdenes» was turned off). */
  val restoreOnClose: Boolean = true,
) {
  val shortId: String get() = PlanIds.short(id)
  val position: String get() = PlanIds.position(block, sub, fallback)
}

object PlanIds {
  const val SHORT = 6
  private val SHORT_FORM = Regex("^p?([0-9a-f]{$SHORT})$")

  /** Stable per order: the same order delivered twice is the same plan. */
  fun of(execution: String): String = "p" + sha256("insto-plan\u0000$execution").take(20)
  fun short(id: String): String = id.take(SHORT + 1)
  fun parseShort(text: String): String? = SHORT_FORM.matchEntire(text.trim().lowercase())?.groupValues?.get(1)?.let { "p$it" }
  fun position(block: Int, sub: Int, fallback: Boolean = false): String = "${block + 1}.${sub + 1}" + if (fallback) "a" else ""
  /** The journal message of one action of a plan: one row per position, so it is never admitted twice. */
  fun step(plan: String, block: Int, sub: Int, fallback: Boolean): String = "plan:$plan:${position(block, sub, fallback)}"
  fun question(plan: String, block: Int, sub: Int, kind: String, attempt: Int = 0): String = "plan:$plan:$kind:${position(block, sub)}:$attempt"
}

/** One line of the plan's progress, also the log event about it. */
data class PlanStepRecord(val plan: String, val position: String, val text: String, val status: String, val detail: String,
  val executionId: String? = null, val updatedAt: Long = 0, val reported: Boolean = false)

/** What the engine does with an action whose journal row it finds. Pure, like [ExecutionRecoveryPolicy]. */
object PlanStepPolicy {
  sealed interface Next {
    /** Never touched the phone: validated again and run. */
    data object Run : Next
    /** Interrupted while setting a readable state: read back, never run again. */
    data object Verify : Next
    data object Done : Next
    data class Failed(val reason: String) : Next
    /** It may have happened: never repeated; the plan is suspended past it. */
    data class Uncertain(val reason: String) : Next
  }

  fun of(row: ExecutionRecord?, effect: ActionEffect?): Next = when (row?.state) {
    null, ExecutionState.RECEIVED, ExecutionState.VALIDATED, ExecutionState.READY, ExecutionState.WAITING -> Next.Run
    ExecutionState.DISPATCHING -> if (effect == ActionEffect.IDEMPOTENT_SET) Next.Verify else Next.Uncertain(ExecutionRecoveryPolicy.INTERRUPTED)
    ExecutionState.VERIFYING -> Next.Verify
    ExecutionState.VERIFIED, ExecutionState.HANDED_OFF -> Next.Done
    ExecutionState.UNCERTAIN -> Next.Uncertain(row.reason ?: ExecutionRecoveryPolicy.INTERRUPTED)
    ExecutionState.FAILED, ExecutionState.CANCELLED, ExecutionState.EXPIRED ->
      Next.Failed(row.reason ?: row.result ?: row.state.code)
    else -> Next.Uncertain(ExecutionRecoveryPolicy.INTERRUPTED)
  }

  /** The settings an action can change, read again after it so a restoration knows what the plan itself left. */
  fun touches(command: PhoneCommand): Set<PlanSetting> = when (command) {
    is PhoneCommand.Volume -> setOf(PlanSetting.VOLUME)
    is PhoneCommand.TemporaryMode -> when (command.kind) {
      ModeKind.DND -> setOf(PlanSetting.DND)
      ModeKind.SILENT -> setOf(PlanSetting.RINGER, PlanSetting.DND)
      else -> setOf(PlanSetting.VOLUME)
    }
    PhoneCommand.EndMode -> PlanSetting.entries.toSet()
    is PhoneCommand.Platform -> when (command.action) {
      PlatformAction.DND_ON, PlatformAction.DND_OFF -> setOf(PlanSetting.DND)
      // Android may change Do not disturb together with the ringer.
      PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON -> setOf(PlanSetting.RINGER, PlanSetting.DND)
      else -> emptySet()
    }
    else -> emptySet()
  }
}

/** Putting a saved setting back, never over a change the person made afterwards. Pure. */
object PlanRestorePolicy {
  sealed interface Decision {
    /** It already is what it was before: nothing to write. */
    data object Already : Decision
    data class Write(val value: Int) : Decision
    /** Someone changed it after the plan: left as it is. */
    data object UserChanged : Decision
    data object Unreadable : Decision
  }

  fun decide(stash: PlanStash, current: Int?): Decision = when {
    current == null -> Decision.Unreadable
    current == stash.before -> Decision.Already
    // What the plan left, or, if the plan never touched it, the value it had when it was saved.
    current == (stash.applied ?: stash.before) -> Decision.Write(stash.before)
    else -> Decision.UserChanged
  }
}

/** The text of «ver planes» and «estado de plan <id>», and the log's lines. */
object PlanText {
  fun line(plan: PlanRecord, total: Int?): String {
    val where = if (plan.state.terminal) "" else " · paso ${plan.block + 1}" + (total?.let { " de $it" } ?: "")
    val why = (plan.reason ?: plan.result)?.let { " · " + InstinctAck.cut(InstinctAck.flat(it).trimEnd('.'), 90) }.orEmpty()
    return "${plan.shortId} ${plan.state.code}$where$why"
  }

  fun list(plans: List<Pair<PlanRecord, Int?>>): String =
    if (plans.isEmpty()) "No hay planes en los últimos 7 días." else plans.joinToString(" | ") { (plan, total) -> line(plan, total) }

  fun status(plan: PlanRecord?, steps: List<PlanStepRecord>, short: String, total: Int?): String {
    plan ?: return "No hay ningún plan $short en los últimos 7 días."
    val detail = steps.joinToString("; ") { "${it.position} ${it.status}: ${InstinctAck.cut(InstinctAck.flat(it.text), 50)}" +
      it.detail.takeIf(String::isNotBlank)?.let { d -> " (${InstinctAck.cut(InstinctAck.flat(d).trimEnd('.'), 70)})" }.orEmpty() }
    return line(plan, total) + if (detail.isEmpty()) "" else " · $detail"
  }
}

/** The plan's columns that are JSON. */
internal object PlanCodec {
  fun stash(value: Map<PlanSetting, PlanStash>): String = JSONObject().apply {
    value.forEach { (setting, s) ->
      put(setting.name, JSONObject().put("before", s.before).put("restored", s.restored).apply { s.applied?.let { put("applied", it) } })
    }
  }.toString()

  fun stash(text: String?): Map<PlanSetting, PlanStash> = runCatching {
    val o = JSONObject(text ?: "{}")
    o.keys().asSequence().mapNotNull { key ->
      val setting = PlanSetting.of(key) ?: return@mapNotNull null
      val s = o.getJSONObject(key)
      setting to PlanStash(s.getInt("before"), if (s.has("applied")) s.getInt("applied") else null, s.optBoolean("restored"))
    }.toMap()
  }.getOrDefault(emptyMap())

  fun event(value: PlanEvent?): String? = value?.let {
    JSONObject().put("trigger", it.trigger.name).put("event", it.eventId).put("instance", it.instanceId).put("at", it.at).toString()
  }

  fun event(text: String?): PlanEvent? = text?.let {
    runCatching {
      val o = JSONObject(it)
      PlanEvent(PlanTrigger.of(o.getString("trigger"))!!, o.getLong("event"), o.getLong("instance"), o.getLong("at"))
    }.getOrNull()
  }
}
