package com.example.phone

import android.content.Context
import android.os.SystemClock
import java.util.Calendar
import org.json.JSONObject

/** Voice resolution against the discovered inventory. Only complete, unambiguous actions are emitted. */
internal class OmniremoteRouting(
  private val catalog: () -> OmniremoteAdapter.Catalog,
  private val settings: () -> OmniremotePreferences.Settings,
  private val now: () -> Long,
) {
  private data class Recent(val ids: List<Long>, val at: Long)
  @Volatile private var recent: Recent? = null

  private sealed interface Step {
    data class Remote(val commands: List<PhoneCommand>, val ids: List<Long>) : Step
    data class Unresolved(val message: String, val mayUseDecisions: Boolean = false) : Step
  }

  data class CachedResolution(val result: InterpretationResult?, val needsDiscovery: Boolean)

  fun resolveCached(text: String, clock: Calendar, decision: String?, cached: () -> OmniremoteAdapter.Catalog?): CachedResolution {
    var needsDiscovery = false
    val result = resolve(text, clock, decision, loadCatalog = {
      cached() ?: OmniremoteAdapter.Catalog().also { needsDiscovery = true }
    })
    return CachedResolution(result, needsDiscovery)
  }

  fun resolve(text: String, clock: Calendar, decision: String? = null, background: Boolean = false,
    loadCatalog: () -> OmniremoteAdapter.Catalog = catalog): InterpretationResult? {
    if (RequestSafetyPolicy.refusal(text) != null || RequestSafetyPolicy.isGeneralRequest(text) ||
      '?' in RequestSafetyPolicy.withoutQuotedText(text)) return null
    val steps = OperationalContext.withoutDiscourseLead(ConversationSession.splitSteps(text))
    if (steps.none { actionHead.containsMatchIn(syntax(it)) }) return null
    val preferences by lazy(settings)
    val snapshot by lazy(loadCatalog)
    var inherited = if (background) emptyList() else recent?.takeIf { now() - it.at in 0..120_000 }?.ids.orEmpty()
    var remote = false
    var missing = false
    var unresolved: Step.Unresolved? = null
    val commands = mutableListOf<PhoneCommand>()
    for (step in steps) {
      val folded = syntax(step)
      val explicitPhone = phone.containsMatchIn(folded)
      val localText = if (explicitPhone) step.replace(phoneOriginal, "").trim() else step
      val local = PhoneCommandParser { clock.clone() as Calendar }.parse(localText)
      val namedTarget = explicitTargetSyntax.containsMatchIn(folded) || generic.containsMatchIn(folded)
      val knownPhone = !namedTarget && !(inherited.isNotEmpty() && followUp.containsMatchIn(folded)) &&
        local is PhoneRoute.Local && when (local.command) {
        is PhoneCommand.Volume, is PhoneCommand.Media -> inherited.isEmpty()
        is PhoneCommand.OpenApp -> inherited.isEmpty() && (background || preferences.default == null)
        else -> true
      }
      val route = if (explicitPhone || knownPhone || !actionHead.containsMatchIn(folded)) null
        else resolveStep(step, folded, snapshot, preferences, inherited, decision, background)
      when (route) {
        is Step.Remote -> {
          remote = true
          inherited = route.ids
          commands += route.commands
        }
        is Step.Unresolved -> {
          if (route.mayUseDecisions && steps.size == 1 && decision == null && !background)
            return InterpretationResult.Clarification(route.message, "omniremote_decision")
          unresolved = route
          remote = true
        }
        null -> {
          if (local is PhoneRoute.Local &&
            SemanticCommandGuard.check(step, listOf(local.command), clock) == SemanticCommandGuard.Verdict.Accepted) {
            commands += local.command
          } else missing = true
          // Explicit phone target must prevail over a previously selected TV, also for continued steps.
          if (explicitPhone || (local as? PhoneRoute.Local)?.command.let { it is PhoneCommand.Volume || it is PhoneCommand.Media || it is PhoneCommand.OpenApp })
            inherited = emptyList()
        }
      }
    }
    if (!remote) {
      if (steps.size == 1 && phone.containsMatchIn(syntax(text))) {
        val local = PhoneCommandParser { clock.clone() as Calendar }.parse(text.replace(phoneOriginal, "").trim())
        if (local is PhoneRoute.Local) return InterpretationResult.Direct(local.command)
      }
      return null
    }
    unresolved?.let { return InterpretationResult.Rejected(it.message, RejectionReason.NOT_UNDERSTOOD) }
    if (missing || commands.size !in steps.size..10 || background && commands.size != 1)
      return InterpretationResult.Rejected("Indicá cada acción y su destino. Una secuencia admite hasta diez pasos.",
        RejectionReason.NOT_UNDERSTOOD)
    return if (commands.size == 1) InterpretationResult.Direct(commands.single(), contextual = true)
      else InterpretationResult.Plan(commands)
  }

  @Synchronized fun record(command: PhoneCommand, result: PhoneActionResult, group: List<PhoneCommand> = emptyList()) {
    if (result.outcome !in setOf(PhoneActionOutcome.COMPLETED, PhoneActionOutcome.HANDOFF)) return
    if (OmniremoteCommands.isRemote(command)) {
      fun id(item: PhoneCommand) = (item as? PhoneCommand.Platform)?.takeIf(OmniremoteCommands::isRemote)
        ?.content?.let { OmniremoteCommands.targetId(JSONObject(it).getString("device")) }
      val target = id(command) ?: return
      val grouped = group.mapNotNull(::id)
      recent = Recent((grouped + target).distinct(), now())
    } else if (command is PhoneCommand.Volume || command is PhoneCommand.Media || command is PhoneCommand.OpenApp ||
      command is PhoneCommand.YouTubeSearch || command is PhoneCommand.SpotifySearch) {
      recent = null
    }
  }

  private fun resolveStep(source: String, folded: String, snapshot: OmniremoteAdapter.Catalog,
    preferences: OmniremotePreferences.Settings, inherited: List<Long>, decision: String?, background: Boolean): Step? {
    val explicit = generic.findAll(folded).toList().ifEmpty {
      if (powerHead.containsMatchIn(folded)) listOfNotNull(bareTv.find(folded)) else emptyList()
    }
    val all = allTv.containsMatchIn(folded)
    val selected = linkedSetOf<Long>()
    val targetKind = explicit.map { it.value.endsWith("mac") }.distinct()
    if (targetKind.size > 1) return Step.Unresolved("Separá las acciones para la TV y la Mac.")
    val isMac = targetKind.singleOrNull()
    val verbStart = indirectHead.find(folded)?.value?.length ?: 0
    val objectPosition = folded.indexOf(' ', verbStart) + 1
    var action = folded
    val named = snapshot.devices.filter { isMac == null || it.television != isMac }.flatMap { device ->
      val names = preferences.aliases[device.id].orEmpty() + device.name + device.room
      names.map(::label).filter { it.isNotBlank() && !reservedAlias(it) }.map { it to device.id }
    }.groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.first.length }
    val namedTargets = linkedMapOf<IntRange, Long>()
    for ((name, ids) in named) {
      val pattern = Regex("\\b(?:(?:en|de|del|a|al|y) )?(?:(?:el|la|los|las) )?(?:$tv )?(?:de(?:l)? )?${Regex.escape(name)}(?= (?:y |en |al? |por |hasta )|$)")
      for (match in pattern.findAll(folded)) {
        if (namedTargets.keys.any { match.range.first <= it.last && it.first <= match.range.last }) continue
        val leading = match.value.substringBefore(' ')
        if (match.range.first != objectPosition && leading !in setOf("en", "de", "del", "a", "al", "y") &&
          namedTargets.keys.none { it.last + 1 == match.range.first }) continue
        // An app name is extra data in an open request; an unqualified open keeps that name intact.
        if (match.range.first == objectPosition && openHead.containsMatchIn(folded) && explicit.isEmpty() &&
          !match.value.startsWith("en ")) continue
        val distinct = ids.distinct()
        if (distinct.size != 1) return Step.Unresolved("Hay varios dispositivos con el nombre o la habitación «$name». Indicá un alias único.")
        namedTargets[match.range] = distinct.single()
      }
    }
    val targetSpans = namedTargets.keys.sortedBy { it.first }
    if (targetSpans.isEmpty() && explicit.isEmpty() && !all && unknownLocation.containsMatchIn(folded) &&
      (openHead.containsMatchIn(folded) || powerHead.containsMatchIn(folded) ||
        followUp.containsMatchIn(folded) && !volumeValue.containsMatchIn(folded))) {
      return Step.Unresolved(snapshot.error ?: "OmniRemote no encontró ese destino. Indicá un dispositivo, habitación, alias o el celular.")
    }
    selected += targetSpans.map(namedTargets::getValue)
    if (selected.isEmpty()) {
      if (all) selected += snapshot.devices.filter { it.television }.map { it.id }
      else if (explicit.isNotEmpty()) {
        if (inherited.isNotEmpty() && isMac != true)
          selected += inherited.filter { id -> snapshot.devices.any { it.id == id && it.television } }
        if (selected.isEmpty()) {
          val choices = snapshot.devices.filter { it.television != (isMac == true) }
          if (!background) preferences.default?.takeIf { id -> choices.any { it.id == id } }?.let(selected::add)
          if (selected.isEmpty()) choices.singleOrNull()?.id?.let(selected::add)
        }
      } else if (followUp.containsMatchIn(folded) || barePower.matches(folded) || openHead.containsMatchIn(folded)) {
        if (!background) selected += inherited
        if (selected.isEmpty() && !background && openHead.containsMatchIn(folded)) {
          val appName = folded.substringAfter(' ').let(::label)
          val device = snapshot.devices.firstOrNull { it.id == preferences.default }
          if (device?.apps?.any { label(it.name) == appName } == true) selected += device.id
        }
      }
    }
    if (selected.isEmpty() && explicit.isEmpty() && !all) {
      if ((powerHead.containsMatchIn(folded) || explicitRemoteName.containsMatchIn(folded)) && knownTvName.containsMatchIn(folded))
        return Step.Unresolved(snapshot.error ?: "OmniRemote no encontró ese televisor. Indicá el nombre o el alias configurado.")
      return null
    }
    snapshot.error?.let { return Step.Unresolved(it) }
    if (selected.isEmpty()) return Step.Unresolved("Indicá el nombre, la habitación o el alias de un dispositivo de OmniRemote.")
    val cuts = targetSpans + explicit.filter { marker -> targetSpans.none {
      marker.range.first <= it.last && it.first <= marker.range.last
    } }.map { it.range }
    val remaining = StringBuilder(action)
    cuts.sortedByDescending { it.first }.forEach { remaining.replace(it.first, it.last + 1, " ") }
    action = remaining.toString().replace(Regex(" +"), " ").trim()
    if (all) action = action.replace(Regex("\\b(?:todos|todas|ambos|ambas)(?: los| las)?\\b"), "").trim()
    action = action.replace(Regex(" +"), " ").trim()
    val result = mutableListOf<PhoneCommand>()
    for (id in selected) {
      val device = snapshot.devices.firstOrNull { it.id == id }
        ?: return Step.Unresolved("El dispositivo anterior ya no está en OmniRemote. Indicá un destino disponible.")
      val parsed = parseAction(source, action, device, decision)
        ?: return Step.Unresolved("No pude resolver esa orden para ${device.name}. Indicá la acción y el destino con más detalle.", true)
      val (operation, params) = parsed
      if (decision != null && decision !in setOf(operation, "omniremote_mute".takeIf { operation == "omniremote_key" }))
        return Step.Unresolved("La acción interpretada no coincide con el pedido de OmniRemote.")
      val (wire, args) = OmniremoteCommands.request(operation, params)
      if (!device.supports(wire, args)) {
        val message = if (wire in setOf("SLEEP_FOR", "SLEEP_AT") && wire in device.commands && !device.exactAlarms)
          "Habilitá las alarmas exactas para OmniRemote en Ajustes de Android."
        else "OmniRemote no anuncia ese control para ${device.name}."
        return Step.Unresolved(message)
      }
      val command = runCatching { InstinctCapabilities.command(operation, params.put("device", device.target)) }.getOrNull()
        ?: return Step.Unresolved("Revisá los parámetros de la orden para ${device.name}.")
      result += command
    }
    return Step.Remote(result, selected.toList())
  }

  private fun parseAction(source: String, action: String, device: OmniremoteClient.Device, decision: String?): Pair<String, JSONObject>? {
    // Decisions can choose the intent for a less direct lead. Its output never supplies a target or parameter.
    val s = if (decision != null) action.replaceFirst(indirectHead, "") else action
    fun op(id: String, vararg values: Pair<String, Any>): Pair<String, JSONObject> =
      "omniremote_$id" to JSONObject().apply { values.forEach { (k, v) -> put(k, v) } }
    fun key(value: String) = op("key", "key" to value)
    fun matches(pattern: String) = Regex(pattern).matches(s)
    fun capture(pattern: String) = Regex(pattern).matchEntire(s)?.groupValues?.get(1)
    val powerOff = "(?:apaga|apagar|programa el apagado|programa apagado)"
    capture("$powerOff (?:en|dentro de) (.+)")?.let { duration ->
      val seconds = CommandDuration.seconds(duration)?.takeIf { it in 60..86_400 && it % 60 == 0 } ?: return null
      return op("sleep_for", "minutes" to seconds / 60)
    }
    capture("$powerOff a las? (.+)")?.let {
      val clock = ClockTime.parse(it) as? ClockTime.Exact ?: return null
      return op("sleep_at", "hour" to clock.hour, "minute" to clock.minute)
    }
    if (matches("(?:prende|prendelo|prendela|prender|encende|encender|enciende|activa)")) return op("power", "on" to true)
    if (matches("(?:apaga|apagalo|apagala|apagar)")) return op("power", "on" to false)
    if (matches("(?:mostra|consulta|deci|ver|revisa)(?: (?:el )?estado)?") && s.endsWith("estado")) return op("state")
    if (matches("(?:extende|alarga) (?:el )?(?:apagado|sleep|temporizador)")) return op("sleep_extend")
    if (matches("(?:cancela|cancele|quita) (?:el )?(?:apagado|sleep|temporizador)")) return op("sleep_cancel")
    capture("(?:pone|pon|ajusta|deja|subi|sube|baja|cambia) (?:el )?(?:volumen|sonido) (?:al?|en) (.+)")?.let {
      val value = PhoneCommandParser.number(it.removeSuffix(" por ciento").trimEnd('%').trim()) ?: return null
      return op("volume", "percent" to value)
    }
    if (matches("(?:subi|sube|aumenta) (?:el )?(?:volumen|sonido)")) return key("VOL_UP")
    if (matches("(?:baja|bajar|reduce) (?:el )?(?:volumen|sonido)")) return key("VOL_DOWN")
    if (matches("(?:silencia|silenciar|mutea|pone mute|activa el silencio)(?: (?:el )?(?:volumen|sonido))?"))
      return if ("SET_MUTE" in device.commands) op("mute", "muted" to true) else key("MUTE")
    if (matches("(?:desilencia|saca el mute|activa el sonido|quita el silencio)")) return op("mute", "muted" to false)
    if (matches("(?:pone|pon|activa) (?:en )?pausa|pausa|pausa la reproduccion|pausa el video")) return key("PAUSE")
    if (matches("(?:reproduci|reproduce|continua|reanuda|pone play|dale play)(?: la reproduccion| el video)?")) return key("PLAY")
    if (matches("(?:detene|deten|para) (?:la reproduccion|el video)")) return key("STOP")
    if (matches("(?:subi|sube|siguiente) (?:el )?canal")) return key("CH_UP")
    if (matches("(?:baja|anterior) (?:el )?canal")) return key("CH_DOWN")
    if (matches("(?:volve|regresa) al canal anterior")) return key("NUM_PRE_CH")
    if (matches("(?:abri|abre|mostra) (?:(?:el )?brillo|tvdimmer) por adb")) return op("open_brightness_adb")
    if (matches("(?:abri|abre|mostra) (?:(?:el )?brillo|tvdimmer)")) return op("open_brightness")
    if (matches("(?:abri|abre|mostra) (?:(?:el )?audio|(?:los )?altavoces) bluetooth")) return op("open_bluetooth_audio")
    if (matches("(?:mostra|lista|busca) (?:los reproductores|(?:las )?fuentes de medios)")) return op("mac_media_list")
    if (matches("(?:escribi|escribe|teclea)(?: el texto)?")) {
      val value = RequestSafetyPolicy.quotedTexts(source).singleOrNull() ?: return null
      return op("text", "text" to value)
    }
    // Explicit key/source form allows addressing any advertised key or ephemeral media source.
    val sourceId = RequestSafetyPolicy.quotedTexts(source).singleOrNull()
    if (sourceId != null) {
      if (matches("(?:consulta|mostra) (?:el estado|la reproduccion) de (?:la )?fuente"))
        return op("mac_media_state", "sourceId" to sourceId)
      if (matches("(?:mostra|trae al frente) (?:la )?fuente")) return op("mac_media_reveal", "sourceId" to sourceId)
      capture("(?:pulsa|toca) (?:la )?tecla ([a-z0-9_]+) en (?:la )?fuente")?.let {
        return op("mac_media_key", "sourceId" to sourceId, "key" to it.uppercase(java.util.Locale.ROOT))
      }
    }
    capture("(?:pulsa|toca|presiona) (?:la )?tecla ([a-z0-9_]+)")?.let { return key(it.uppercase(java.util.Locale.ROOT)) }
    capture("(?:abri|abre|abrir|inicia|iniciar) (.+)")?.let { wanted ->
      if (sourceId != null) return null
      if (device.type == "MACOS") {
        val url = Regex("https?://[^\\s<>«»”\"]+", RegexOption.IGNORE_CASE).find(source)?.value ?: return null
        return op("mac_open_link", "url" to url)
      }
      val matches = device.apps.filter { label(it.name) == label(wanted) }
      return matches.singleOrNull()?.let { op("open_app", "appId" to it.id) }
    }
    return null
  }

  companion object {
    private val powerHead = Regex("^(?:prende|prender|encende|encender|enciende|apaga|apagar) ")
    private val explicitTargetSyntax = Regex(" (?:en|del|de la) [a-z]|\\b(?:samsung|flowbox|tizen|android tv|google tv)\\b")
    private val volumeValue = Regex(" (?:volumen|sonido) en (?:[0-9]+|[a-z]+)(?: y [a-z]+)?(?: ?%| por ciento)?$")
    private val unknownLocation = Regex(" (?:en|del|de la) (?![0-9]|[a-z]+ (?:minutos?|horas?)\\b|pausa$|play$|mute$)[a-z].+$")
    private val explicitRemoteName = Regex("\\b(?:en|del|de la) (?:la |el )?(?:samsung|tizen|flowbox|android tv|google tv)$")
    private val knownTvName = Regex("\\b(?:samsung|tizen|android tv|google tv|flowbox)\\b")
    private val tv = "(?:televisores?|teles?|tv(?:s)?|pantallas?)"
    private val generic = Regex("\\b(?:(?:en|de|del|a|al) (?:(?:la|el|las|los) )?(?:$tv|mac)|(?:(?:la|el|las|los) )(?:$tv|mac))(?= |$)")
    private val barePower = Regex("^(?:prende|prendelo|prendela|encende|enciende|apaga|apagalo|apagala)$")
    private val bareTv = Regex("(?<= )$tv$")
    private val allTv = Regex("\\b(?:todos los|todas las|ambos|ambas) $tv\\b")
    private val phone = Regex("\\b(?:en|del|de mi|de este|de|al|a mi) (?:(?:el|mi|este) )?(?:celular|telefono|m[oó]vil)\\b")
    private val phoneOriginal = Regex(phone.pattern.replace("telefono", "tel[eé]fono"), RegexOption.IGNORE_CASE)
    private val openHead = Regex("^(?:abri|abre|abrir|inicia|iniciar) ")
    private val indirectSyntax = "(?:(?:necesito|quisiera|querria|deseo|me gustaria|quiero)(?: que)?|(?:me )?(?:podes|puedes|podrias|podras|podria|puede)) "
    private val indirectHead = Regex("^$indirectSyntax")
    private val courtesyHead = Regex("^(?:(?:por favor|por fa|porfa|che|bueno|a ver|ok|okey|eh) )+")
    private val actionHead = Regex("^(?:$indirectSyntax)?(?:prende|prender|encende|encender|enciende|prendelo|prendela|apaga|apagalo|apagala|apagar|abri|abre|abrir|inicia|iniciar|subi|sube|baja|bajar|reduce|aumenta|ajusta|cambia|deja|silencia|silenciar|mutea|desilencia|saca|pone|pon|activa|pausa|reproduci|reproduce|continua|reanuda|dale|detene|deten|para|mostra|consulta|revisa|deci|ver|programa|extende|alarga|cancela|quita|escribi|escribe|teclea|presiona|pulsa|toca|siguiente|anterior|volve|regresa|lista|busca|trae)\\b")
    private val followUp = Regex(
      "^(?:(?:subi|sube|baja|bajar|aumenta|reduce|pone|pon|ajusta|cambia|deja) (?:el )?(?:volumen|sonido|canal)\\b|" +
        "(?:pone|pon|activa) (?:en )?(?:pausa|play|mute|el silencio|el sonido)$|" +
        "(?:silencia|silenciar|mutea|desilencia|saca el mute|quita el silencio|pausa(?: la reproduccion| el video)?|reproduci|reproduce|continua|reanuda|dale play)$|" +
        "(?:detene|deten|para) (?:la reproduccion|el video)$)"
    )
    private fun label(text: String) = PhoneCommandParser.normalize(text).removePrefix("la ").removePrefix("el ")
    fun reservedAlias(text: String): Boolean = Regex("^(?:$tv|mac|celular|telefono|movil)$").matches(label(text))

    private fun syntax(text: String): String {
      val plain = RequestSafetyPolicy.withoutQuotedText(text)
      val normalized = PhoneCommandParser.normalizeForClock(plain)
      // Keep colloquial imperative forms instead of translating them to infinitives.
      return normalized.replaceFirst(courtesyHead, "").replace(PhoneCommandParser.courtesySuffix, "").trim()
    }

    fun android(context: Context) = OmniremoteRouting(
      { InstinctDeviceCapabilities.omniremote.catalog(context) }, OmniremotePreferences(context)::read, SystemClock::elapsedRealtime)
  }
}

internal object OmniremoteVoice {
  private var routing: OmniremoteRouting? = null
  @Synchronized fun of(context: Context): OmniremoteRouting =
    routing ?: OmniremoteRouting.android(context.applicationContext).also { routing = it }
}


internal fun InstinctProposalValidator.check(context: Context, order: String, now: Calendar,
  routines: InstinctRoutineAccess = InstinctRoutineAccess.NONE): ProposalCheck =
  check(order, { omniremoteBackgroundRoute(context, it, now) }, now, routines)

private fun omniremoteBackgroundRoute(context: Context, order: String, now: Calendar): PhoneRoute =
  when (val remote = OmniremoteVoice.of(context).resolve(order, now, background = true)) {
    is InterpretationResult.Direct -> PhoneRoute.Local(remote.command)
    is InterpretationResult.Rejected -> PhoneRoute.Unsupported(remote.message)
    null -> PhoneCommandParser { now.clone() as Calendar }.parse(order)
    else -> PhoneRoute.Unsupported("Indicá un dispositivo de OmniRemote por orden.")
  }
