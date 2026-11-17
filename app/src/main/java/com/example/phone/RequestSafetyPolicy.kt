package com.example.phone

/** Conservative checks on command syntax outside quoted payloads, independent of model output. */
object RequestSafetyPolicy {
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
    val s = PhoneCommandParser.normalize(text)
    return ConversationSession.splitSteps(text).size > 1 ||
      Regex("\\b(?:durante|dentro de|por ciento|por (?:un|una|[0-9]+))\\b|\\ba las? (?:[0-9]|una?\\b|dos\\b|tres\\b|cuatro\\b|cinco\\b|seis\\b|siete\\b|ocho\\b|nueve\\b|diez\\b|once\\b|doce\\b)|%").containsMatchIn(s)
  }
  fun rejection(text: String): String? {
    if (isExplicitQuery(text) || isQuestion(text)) return null
    var quoted = false
    val syntax = text.map { char ->
      if (char in "\"“”") quoted = !quoted
      if (quoted || char in "\"“”") ' ' else char
    }.joinToString("")
    val s = PhoneCommandParser.normalize(syntax)
    if (Regex("^(?:no|nunca)\\b|\\b(?:cuando|hasta que|a menos que|siempre que)\\b|(?:^|\\s)si\\s+(?!confirmo\\b)").containsMatchIn(s))
      return "El pedido contiene una negación o condición. No se ejecutó ni se envió; indicá qué querés hacer ahora."
    if (Regex("\\b(?:sin peajes|evitando peajes|sin autopistas|evitando autopistas|pasando por|con una parada)\\b").containsMatchIn(s))
      return "Esa restricción de ruta no está disponible. No se omitió ni se ejecutó el pedido."
    return null
  }
}
