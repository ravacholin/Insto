package com.example.phone

/** Conservative checks on command syntax outside quoted payloads, independent of model output. */
object RequestSafetyPolicy {
  private val quotes = Regex("\"[^\"]*\"|“[^”]*”|«[^»]*»")
  private val separators = Regex("\\s+(?:y(?: despues| luego)?|despues|luego)\\s+|[,;\\n]\\s*|\\.\\s+")
  private val parameterizedHead = Regex(
    "^(?:(?:un |una |el |la )?(?:timer|temporizador|cuenta regresiva|cuenta atras|alarma|despertador|brillo)|" +
      "mi (?:casa|trabajo) es|modo (?:de )?(?:silencio|silenciar|silencioso|vibrar|vibracion|vibrador|no molestar))\\b"
  )
  private val stopTarget = Regex("^para (?:el |la )?(?:musica|audio|reproduccion|temporizador|timer|cronometro|(?:en )?(?:spotify|youtube))\\b")
  private val restoreClause = Regex("restaura(?:lo)?[.!?]*")
  private val durationUnits = Regex("\\b(?:segundos?|minutos?|horas?)\\b")
  private val fixedModifiers = Regex("\\b(?:durante|dentro de|a partir de|hasta|por ciento|por (?:un|una|[0-9]+))\\b|%")
  private val duration = Regex("\\b(?:por|en) [a-z0-9 ]{1,40} (?:segundos?|minutos?|horas?)\\b")
  private val scheduledTime = Regex("\\ba las? ([a-z0-9]+)")

  internal fun isDoNotDisturbRequest(syntax: String): Boolean =
    syntax == "no molestar" || syntax.startsWith("no molestar durante ") || syntax.startsWith("no molestar por ")

  internal fun withoutQuotedText(text: String): String = text.replace(quotes, " ")

  internal fun hasJoinedActions(folded: String): Boolean {
    val syntax = withoutQuotedText(folded)
    return separators.findAll(syntax).any { separator ->
      val first = syntax.substring(0, separator.range.first)
      val next = syntax.substring(separator.range.last + 1)
      val restoresTimedMode = separator.value.trim().startsWith("y") && restoreClause.matches(next) &&
        durationUnits.containsMatchIn(first)
      !restoresTimedMode && startsAction(first) && (startsAction(next) || restoreClause.matches(next))
    }
  }

  private fun startsAction(text: String): Boolean {
    val syntax = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(text))
    if (LocalIntentRegistry.default.matchPhrase(syntax) != null) return true
    val head = PhoneCommandParser.leadingWord.find(syntax)?.groupValues?.get(1)
    return when (head) {
      "para" -> stopTarget.containsMatchIn(syntax)
      else -> head?.let(PhoneCommandParser::canonicalVerb) != null || parameterizedHead.containsMatchIn(syntax)
    }
  }

  fun isExplicitQuery(text: String): Boolean = Regex("^(?:instinct\\b|(?:me )?explica(?:me)?\\b|contame\\b|que es\\b|como funciona\\b)")
    .containsMatchIn(PhoneCommandParser.normalize(text))
  // A question may contain «cuando» or «si». It still goes through intent selection:
  // for example «cuánta batería queda» has a native answer.
  fun isQuestion(text: String): Boolean {
    val normalized = PhoneCommandParser.normalize(text)
    if (Regex("^(?:que|como|donde|quien(?:es)?|cual(?:es)?|cuant[oa]s?|por que|para que)\\b").containsMatchIn(normalized)) return true
    if (!normalized.startsWith("cuando ")) return false
    // Preserve the written interrogative before normalization removes its accent/punctuation.
    if (text.trimStart().startsWith("¿") || Regex("^cuándo\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trimStart())) return true
    // Without an interrogative marker, a second clause can be a conditional action:
    // «Cuando termina el partido, apagá la música». Keep it subject to the command guard.
    if (text.contains(',')) return false
    return Regex("^cuando (?:es|son|sera|seran|fue|fueron|juega|juegan|empieza|empiezan|comienza|comienzan|termina|terminan|hay|puedo|podemos|se juega|va a ser)\\b")
      .containsMatchIn(normalized)
  }
  fun fixedActionHasExtraParameters(text: String): Boolean {
    val s = PhoneCommandParser.normalize(withoutQuotedText(text))
    return ConversationSession.splitSteps(text).size > 1 ||
      fixedModifiers.containsMatchIn(s) || duration.containsMatchIn(s) ||
      scheduledTime.findAll(s).any { PhoneCommandParser.number(it.groupValues[1]) != null }
  }
  fun rejection(text: String): String? {
    if (isExplicitQuery(text) || isQuestion(text)) return null
    val s = PhoneCommandParser.normalize(withoutQuotedText(text))
    val command = PhoneCommandParser.canonicalRequest(s)
    if ((!isDoNotDisturbRequest(command) && Regex("^(?:no|nunca)\\b").containsMatchIn(command)) ||
      Regex("\\b(?:cuando|hasta que|a menos que|siempre que)\\b|(?:^|\\s)si\\s+(?!confirmo\\b)").containsMatchIn(s))
      return "El pedido contiene una negación o condición. No se ejecutó ni se envió; indicá qué querés hacer ahora."
    if (Regex("\\b(?:sin peajes|evitando peajes|sin autopistas|evitando autopistas|pasando por|con una parada)\\b").containsMatchIn(s))
      return "Esa restricción de ruta no está disponible. No se omitió ni se ejecutó el pedido."
    return null
  }
}
