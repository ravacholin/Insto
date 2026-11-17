package com.example.phone

import java.util.Calendar

sealed interface ConversationResult {
  data object Pass : ConversationResult
  data class Prompt(val message: String) : ConversationResult
  data class Plan(val commands: List<PhoneCommand>) : ConversationResult
  data class Semantic(val draft: DraftPlan? = null) : ConversationResult
}

/** Session-only drafts. Nothing here performs Android side effects. */
class ConversationSession(private val now: () -> Calendar = { Calendar.getInstance() }) {
  private val parser = PhoneCommandParser(now)
  private var calendarDraft: CalendarDraft? = null
  private var pending: String? = null
  private var lastPlan: List<PhoneCommand>? = null
  private var pendingDraft: DraftPlan? = null
  fun clear() { calendarDraft = null; pending = null; lastPlan = null; pendingDraft = null }

  fun accept(text: String, allowSemantic: Boolean = false): ConversationResult {
    val normalized = PhoneCommandParser.normalize(text)
    if (normalized in listOf("cancelar", "cancela", "no", "dejalo", "olvidalo")) {
      val active = calendarDraft != null || pending != null || lastPlan != null || pendingDraft != null
      clear()
      return if (active) ConversationResult.Prompt("Pedido cancelado.") else ConversationResult.Pass
    }
    if (normalized in listOf("si", "dale", "ok", "confirmo", "confirmar") && (calendarDraft != null || pending != null))
      return ConversationResult.Prompt("Todavía hay datos pendientes. Completá o corregí el pedido antes de confirmarlo.")
    if (Regex("\\b(?:si|cuando|hasta que|a menos que)\\b").containsMatchIn(PhoneCommandParser.normalize(maskQuotes(text))) && looksLikeRequest(normalized)) {
      return ConversationResult.Prompt("Todavía no ejecuto condiciones. Pedime los pasos para hacer ahora.")
    }
    val parts = splitSteps(text)
    if (parts.size > 1) {
      clear()
      if (parts.size > 10) return ConversationResult.Prompt("Podés pedir hasta diez pasos por vez.")
      val commands = parts.map { part -> (parser.parse(part) as? PhoneRoute.Local)?.command }
      if (commands.any { it == null }) {
        val routes = parts.map(parser::parse)
        if (allowSemantic && routes.none { it is PhoneRoute.Unsupported && !it.recoverable }) return ConversationResult.Semantic()
        return ConversationResult.Prompt("No entendí todos los pasos. Completá cada acción; todavía no ejecuté ninguna.")
      }
      val plan = commands.filterNotNull()
      lastPlan = plan
      return ConversationResult.Plan(plan)
    }
    val isCalendar = Regex("^(?:agenda|agendar|agendame|programa|crea (?:un )?evento)\\b").containsMatchIn(normalized)
    val correcting = Regex("^(?:mejor|cambia|cambialo|que dure|durante|a las?|de la|en vez|para |el |hoy|manana|pasado manana)").containsMatchIn(normalized)
    if (isCalendar || calendarDraft != null && correcting) return calendar(text, isCalendar)
    if (calendarDraft != null && allowSemantic && parser.parse(text) == PhoneRoute.Instinct) {
      val known = calendarDraft!!
      val fields = org.json.JSONObject().put("type", "calendar")
      if (known.title.isNotBlank()) fields.put("title", known.title)
      if (known.date.isNotBlank()) fields.put("date", known.date)
      if (known.time.isNotBlank()) fields.put("time", known.time)
      CommandDuration.seconds(known.duration)?.let { fields.put("durationSeconds", it) }
      return ConversationResult.Semantic(DraftPlan(listOf(CommandDraft("s1", fields))))
    }
    if (normalized.startsWith("mejor ") && lastPlan?.size == 1) {
      val command = lastPlan!!.single()
      val reply = normalized.removePrefix("mejor ")
      val context = when (command) {
        is PhoneCommand.Navigate -> "navigate"
        is PhoneCommand.Volume -> "volume"
        is PhoneCommand.Timer -> "timer"
        else -> null
      }
      if (context != null) {
        val route = if (command is PhoneCommand.Navigate && reply in listOf("a pie", "en auto", "en bici", "en transporte publico"))
          parser.parse("llevame a ${command.destination} $reply") else parser.parseReply(context, reply)
        if (route is PhoneRoute.Local) return ConversationResult.Plan(listOf(route.command)).also { lastPlan = it.commands }
        return ConversationResult.Prompt("Indicá la corrección completa de ese paso.")
      }
    }
    pending?.let { context ->
      pendingDraft?.let { draft ->
        if (parser.parse(text) == PhoneRoute.Instinct) {
          val result = try { draft.reply(text, now()) } catch (_: Exception) {
            return ConversationResult.Prompt("Ese dato no es válido. Conservé el pedido; corregilo antes de confirmar.")
          }
          when (result) {
            is InterpretationResult.Plan -> { clear(); return ConversationResult.Plan(result.commands).also { lastPlan = it.commands } }
            is InterpretationResult.Incomplete -> { pendingDraft = result.draft; return ConversationResult.Prompt(result.prompt) }
            is InterpretationResult.Rejected -> return ConversationResult.Prompt(result.message)
            null -> if (allowSemantic) return ConversationResult.Semantic(draft)
            else -> Unit
          }
        }
      }
      when (val route = parser.parseReply(context, text)) {
        is PhoneRoute.Local -> { pending = null; return ConversationResult.Plan(listOf(route.command)).also { lastPlan = it.commands } }
        is PhoneRoute.Clarify -> return ConversationResult.Prompt(route.prompt)
        is PhoneRoute.Unsupported -> return ConversationResult.Prompt(route.message)
        else -> Unit
      }
    }
    when (val route = parser.parse(text)) {
      is PhoneRoute.Clarify -> {
        pending = route.context
        pendingDraft = seedDraft(text, route.context)
        return ConversationResult.Prompt(route.prompt)
      }
      is PhoneRoute.Local -> {
        clear()
        val event=route.command as? PhoneCommand.CalendarEvent
        if(event!=null) {
          val begin=Calendar.getInstance().apply {timeInMillis=event.beginMillis}
          calendarDraft=CalendarDraft(event.title,"%02d/%02d/%04d".format(begin.get(Calendar.DAY_OF_MONTH),begin.get(Calendar.MONTH)+1,begin.get(Calendar.YEAR)),"%02d:%02d".format(begin.get(Calendar.HOUR_OF_DAY),begin.get(Calendar.MINUTE)),"${(event.endMillis-event.beginMillis)/1000} segundos")
          return ConversationResult.Plan(listOf(event)).also {lastPlan=it.commands}
        }
        return ConversationResult.Pass
      }
      else -> Unit
    }
    return ConversationResult.Pass
  }

  private data class CalendarDraft(var title: String = "", var date: String = "", var time: String = "", var duration: String = "una hora")
  private fun calendar(input: String, fresh: Boolean): ConversationResult {
    val raw = input.trim().trimStart('¿', '¡').trim().trimEnd('.', '!', '?')
    val folded = FoldedRequest.of(raw)
    if (fresh) { clear(); calendarDraft = CalendarDraft() }
    val draft = calendarDraft ?: return ConversationResult.Pass
    val syntax = if(fresh) FoldedRequest.of(maskQuotes(raw)).text else folded.text
    val date = Regex("\\b(pasado manana|manana|hoy|(?:el )?[0-9]{1,2}/[0-9]{1,2}(?:/[0-9]{4})?)\\b").findAll(syntax).firstOrNull { !syntax.substring(0,it.range.first).endsWith("de la ") }
    if (date != null) {
      draft.date = if(date.value in listOf("hoy","manana","pasado manana")) {
        val anchored=now().clone() as Calendar
        anchored.add(Calendar.DAY_OF_MONTH,when(date.value){"manana"->1;"pasado manana"->2;else->0})
        "%02d/%02d/%04d".format(java.util.Locale.ROOT,anchored.get(Calendar.DAY_OF_MONTH),anchored.get(Calendar.MONTH)+1,anchored.get(Calendar.YEAR))
      } else date.value
    }
    if (fresh) {
      val head = Regex("^(?:agenda|agendar|agendame|programa|crea (?:un )?evento)\\s+").find(folded.text)
      if (head != null) {
        val timeStart=Regex("\\ba las?\\b").find(syntax)?.range?.first
        val end = listOfNotNull(date?.range?.first,timeStart).minOrNull() ?: folded.text.length
        draft.title = raw.substring(head.range.last + 1, end).trim().trim('"', '“', '”')
      }
    }
    val duration = Regex("(?:que dure |durante |y (?:que dure )?)(media hora|[a-z0-9]+(?: y [a-z]+)? (?:horas?|minutos?))$").find(syntax)
    if (duration != null) draft.duration = if (duration.groupValues[1] == "media hora") "treinta minutos" else duration.groupValues[1]
    val time = Regex("(?:a las? |^mejor (?:a las? )?)([a-z0-9]+(?::[0-9]{2}| y (?:media|cuarto|(?!que\\b)[a-z]+))?(?: de la (?:manana|tarde|noche)| am| pm| horas)?)").find(syntax)
    if (time != null) {
      val replacement=time.groupValues[1]
      val oldPeriod=Regex("(?:de la (?:manana|tarde|noche)|am|pm)$").find(draft.time)?.value
      draft.time=if(!fresh && oldPeriod!=null && !Regex(":|de la| am| pm|horas").containsMatchIn(replacement)) "$replacement $oldPeriod" else replacement
    }
    if (time == null && Regex("^de la (?:manana|tarde|noche)$").matches(folded.text) && draft.time.isNotBlank()) {
      draft.time = draft.time.substringBefore(" de la ") + " " + folded.text
    }
    val consumed=BooleanArray(folded.text.length)
    fun consume(range:IntRange) {range.forEach {if(it in consumed.indices)consumed[it]=true}}
    if(fresh && date!=null)consume(0 until date.range.first)
    date?.let {consume(it.range)}
    time?.let {consume(it.range)}
    duration?.let {consume(it.range)}
    val residue=folded.text.filterIndexed {index,_ -> !consumed[index]}
      .replace(Regex("^(?:mejor|cambia|cambialo)\\s+"), "")
      .replace(Regex("^[,\\s]*(?:y\\s+)?(?:que dure|durante)\\s*$"), "")
      .trim(' ', ',', '.', '!', '?')
    val periodOnly=time==null && Regex("^de la (?:manana|tarde|noche)$").matches(folded.text)
    if(residue.isNotBlank() && !periodOnly && !(fresh && date==null))
      return ConversationResult.Prompt("Hay detalles del evento que todavía no puedo representar. Conservé el borrador; indicá fecha, hora y duración sin condiciones adicionales.")
    if (draft.title.isBlank()) return ConversationResult.Prompt("¿Qué título le pongo al evento?")
    if (draft.date.isBlank()) return ConversationResult.Prompt("¿Qué día? Podés decir mañana o una fecha completa.")
    if (draft.time.isBlank()) return ConversationResult.Prompt("¿A qué hora? Conservé el título y la fecha.")
    // CalendarRequestParser validates the full date/time, DST and duration before a plan is offered.
    val route = parser.parse("agendá \"${draft.title}\" ${draft.date} a las ${draft.time} durante ${draft.duration}")
    return when (route) {
      is PhoneRoute.Local -> ConversationResult.Plan(listOf((route.command as PhoneCommand.CalendarEvent).copy(title = draft.title))).also { lastPlan = it.commands }
      is PhoneRoute.Clarify -> ConversationResult.Prompt("¿De la mañana o de la tarde? Conservé los demás datos.")
      is PhoneRoute.Unsupported -> ConversationResult.Prompt(route.message)
      else -> ConversationResult.Prompt("Completá la fecha y hora del evento.")
    }
  }

  companion object {
    private fun seedDraft(text: String, context: String): DraftPlan? {
      val type = if (context == "volume") "volume" else context
      if (type !in CapabilityCatalog.specs || type == "calendar") return null
      val fields = org.json.JSONObject().put("type", type)
      val normalized = PhoneCommandParser.normalize(text)
      if (type == "volume") fields.put("operation", "SET")
      if (type == "temporary_mode") {
        when {
          "no molestar" in normalized -> fields.put("kind", "DND")
          "volumen" in normalized -> {
            fields.put("kind", "MEDIA_VOLUME")
            Regex("volumen al? (.+?)(?: por ciento|%| durante|$)").find(normalized)?.let {
              PhoneCommandParser.number(it.groupValues[1])?.takeIf { value -> value in 0..100 }?.let { value -> fields.put("percent", value) }
            }
          }
          normalized.startsWith("silencia") -> fields.put("kind", "SILENT")
        }
      }
      if (type == "timer") Regex(" (?:para|llamado) (.+)$", RegexOption.IGNORE_CASE).find(text)?.let { fields.put("label", it.groupValues[1]) }
      if (type == "alarm") {
        val alarmBody = normalized.substringAfter("alarma", normalized.removePrefix("despertame")).trim()
        Regex("^(?:a las? )?([a-z0-9]+)(?::([0-9]{2}))?").find(alarmBody)?.let {
          PhoneCommandParser.number(it.groupValues[1])?.takeIf { hour -> hour in 0..23 }?.let { hour ->
            // Parse the whole source with a provisional period, then remove that period.
            // This obtains minutes/days/label without inventing the missing AM/PM answer.
            val suffix = Regex(" (?:todos los|cada |llamad[ao] |con nombre )").find(normalized)?.range?.first ?: normalized.length
            val completed = normalized.substring(0,suffix) + " de la manana" + normalized.substring(suffix)
            val candidate = (PhoneCommandParser().parse(completed) as? PhoneRoute.Local)?.command as? PhoneCommand.Alarm
            if (candidate != null) {
              val known = PhoneCommandCodec.encode(candidate)
              known.keys().forEach { key -> fields.put(key, known.get(key)) }
            }
            fields.put("hour", hour)
            if (!fields.has("minute") && it.groupValues[2].isNotEmpty()) fields.put("minute", it.groupValues[2].toInt())
            if (it.groupValues[2].isNotEmpty() && Regex("^(?:a las? )?0[0-9]:").containsMatchIn(alarmBody)) fields.put("period", "24H")
          }
        }
      }
      return runCatching { DraftPlan(listOf(CommandDraft("s1", fields))) }.getOrNull()
    }
    private fun maskQuotes(input:String):String {
      var quoted=false
      return input.map {c ->
        if(c in "\"“”") quoted=!quoted
        if(quoted || c in "\"“”") '_' else c
      }.joinToString("")
    }
    private fun looksLikeRequest(s: String) = Regex("\\b(?:prende|apaga|pone|baja|subi|abri|agenda|llama|lleva|inicia)").containsMatchIn(s)
    /** Split only structural separators outside quotes. An unknown second step remains visible and rejects the plan. */
    fun splitSteps(input: String): List<String> {
      val masked = StringBuilder(input)
      var quoted = false
      input.forEachIndexed { i, c ->
        if (c in "\"“”") quoted = !quoted
        if (quoted || c in "\"“”") masked.setCharAt(i, '_')
      }
      val syntax = masked.toString()
      val separators = Regex("\\s*[,;]\\s*|\\s+(?:y después|y despues|y luego|después|despues|luego|y)\\s+", RegexOption.IGNORE_CASE)
      // 'y' within quantities, titles and a time is not a separator unless followed by a command-like verb.
      val verbs = Regex("^(?:pone|poneme|pon|baja|subi|abri|prende|enciende|encende|apaga|inicia|pausa|continua|reinicia|lleva|llevame|llama|agenda|busca|reproduci|compra|envia|borra|activa|desactiva|silencia|mostra|cancela)\\b")
      val boundaries = separators.findAll(input).filter { m ->
        ';' in m.value || verbs.containsMatchIn(PhoneCommandParser.normalize(input.substring(m.range.last + 1)))
      }.filter { m -> syntax.getOrNull(m.range.first) != '_' }.toList()
      if (boundaries.isEmpty()) return listOf(input)
      val result = mutableListOf<String>()
      var start = 0
      for (m in boundaries) { result += input.substring(start, m.range.first).trim(); start = m.range.last + 1 }
      result += input.substring(start).trim()
      return result
    }
  }
}
