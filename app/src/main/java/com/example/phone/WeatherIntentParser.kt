package com.example.phone

/**
 * Anchored grammar for the two SMN queries. A sentence that only mentions rain or temperature is not a query:
 * «lluvia de ideas», «escribí un cuento sobre la lluvia» or «explicame qué es la sensación térmica» return null.
 * Requests for another place, another day, the past or forecast extremes are recognized and answered locally
 * as unsupported, without changing what was asked.
 */
object WeatherIntentParser {
  const val FEELS_LIKE = "feels_like"
  const val TEMPERATURE_SCOPE = "Por ahora solo consulto la medición actual del SMN en la estación Buenos Aires."
  const val RAIN_SCOPE = "Por ahora solo consulto el SMN para Capital Federal y las próximas horas."

  // «si» only after a reporting verb: «decime si va a llover» asks; «si llueve» is a condition.
  private const val HEAD = "(?:(?:decime|decirme|me decis|decis|contame|sabes|saber|fijate|consulta|consultar|averigua|averiguame|mostra|mostrame|ver)\\s+(?:si\\s+)?)?"
  private const val NOW = "(?:ahora|ahora mismo|en este momento|hoy|actual|actualmente)"
  private const val PLACE = "(?:(?:en|para|de) (?:la )?(?:ciudad (?:autonoma )?(?:de )?)?(?:buenos aires|capital federal|capital|caba|la ciudad))"

  private val feelsLike = Regex("^(?:(?:la|cual es la|que|cuanto da la|cuanto es la) )?sensacion termica(?: (?:hay|hace|tenemos|es))?$")
  private val temperature = Regex(
    "^(?:(?:la )?temperatura(?: (?:actual|de ahora))?|" +
      "(?:que|cual es la|cuanta|cuanto de) temperatura(?: (?:hace|hay|tenemos|es|marca))?|" +
      "(?:a )?cuantos grados (?:hace|hay|tenemos|estamos|marca)|" +
      "como esta la temperatura)$")
  private val rain = Regex(
    "^(?:(?:la )?lluvia|" +
      "(?:va a llover|llueve|llovera|hay lluvia|vamos a tener lluvia)|" +
      "(?:(?:que|cual es la|hay) )?(?:probabilidad|probabilidades|chances?|posibilidad(?:es)?) de (?:lluvia|lluvias|precipitaciones?|que llueva)(?: (?:hay|tenemos))?|" +
      "(?:hay )?(?:pronostico|anuncio) de lluvia|" +
      "como viene (?:la lluvia|el tema de la lluvia))$")
  private val allowedTail = Regex("^(?:$NOW|$PLACE|$PLACE $NOW|$NOW $PLACE|(?:en|para) las proximas horas|(?:en|para) un rato|en las proximas horas $PLACE|$PLACE (?:en|para) las proximas horas)$")
  private val otherDay = Regex("\\b(?:manana|pasado|ayer|anoche|anteayer|lunes|martes|miercoles|jueves|viernes|sabado|domingo|semana|fin de semana|finde|mes|ano|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre|[0-9]+(?:/[0-9]+)?|esta tarde|esta noche|a la tarde|a la noche|mas tarde|mediodia)\\b")
  private val otherPlace = Regex("^(?:en|para) (?!las proximas|un rato)[a-z].*$")
  private val units = Regex("\\b(?:grados|fahrenheit|celsius|centigrados|kelvin)\\b")
  private val past = Regex("^(?:que|cual fue la|cuanta) temperatura (?:hubo|hizo|habia|hacia|tuvimos)\\b|^(?:llovio|cuanto llovio)\\b|^cuantos grados (?:hubo|hizo|hacia)\\b")
  private val extremes = Regex("^(?:(?:cual (?:es|sera) la|que|la) )?(?:temperatura (?:maxima|minima)(?: (?:de |para )?(?:hoy|manana|pasado manana))?|(?:maxima|minima) (?:de |para )?(?:hoy|manana|pasado manana))$")

  /** Null when the sentence is not an SMN query. */
  fun parse(text: String): PhoneRoute? {
    val s = body(text) ?: return null
    if (past.containsMatchIn(s)) return PhoneRoute.Unsupported(TEMPERATURE_SCOPE)
    if (extremes.matches(s)) return PhoneRoute.Unsupported(TEMPERATURE_SCOPE)
    for ((grammar, command) in listOf(
      feelsLike to PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE, FEELS_LIKE),
      temperature to PhoneCommand.Platform(PlatformAction.WEATHER_TEMPERATURE),
      rain to PhoneCommand.Platform(PlatformAction.WEATHER_RAIN),
    )) {
      val scope = if (command.action == PlatformAction.WEATHER_RAIN) RAIN_SCOPE else TEMPERATURE_SCOPE
      if (grammar.matches(s)) return PhoneRoute.Local(command)
      // The longest matching head decides; the rest must be a supported time or place, or an explicit other one.
      val words = s.split(' ')
      for (cut in words.size - 1 downTo 1) {
        val head = words.subList(0, cut).joinToString(" ")
        if (!grammar.matches(head)) continue
        val tail = words.subList(cut, words.size).joinToString(" ")
        return when {
          allowedTail.matches(tail) -> PhoneRoute.Local(command)
          otherDay.containsMatchIn(tail) -> PhoneRoute.Unsupported(scope)
          otherPlace.matches(tail) && !units.containsMatchIn(tail) -> PhoneRoute.Unsupported(scope)
          else -> null
        }
      }
    }
    return null
  }

  /** True for a complete SMN query sentence (even «decime si va a llover», which is not a condition). */
  fun isQuery(text: String): Boolean = parse(text) is PhoneRoute.Local

  /** Folded sentence without courtesy, request wrapper, interjection or reporting verb; null if empty. */
  private fun body(text: String): String? {
    if (text.length > 300) return null
    var s = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(text))
    s = s.replace(Regex("^(?:(?:che|bueno|a ver|ok|hola|dale)\\s+)+"), "")
    s = PhoneCommandParser.canonicalRequest(s)
    s = s.replace(Regex("^$HEAD"), "")
    s = s.replace(Regex("\\s+(?:por favor|porfa)$"), "").removeSuffix(" o no").trim()
    return s.takeIf { it.isNotEmpty() }
  }
}
