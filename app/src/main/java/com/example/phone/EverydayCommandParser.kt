package com.example.phone

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Match folded command syntax while retaining the original text of every payload. */
class FoldedRequest private constructor(val raw: String, val text: String, private val offsets: List<Int>) {
  fun capture(match: MatchResult, group: Int): String {
    val range = match.groups[group]?.range ?: return ""
    if (range.isEmpty()) return ""
    return raw.substring(offsets[range.first], offsets.getOrNull(range.last + 1) ?: raw.length).trim()
  }

  internal fun withCommandHead(): FoldedRequest {
    val start = PhoneCommandParser.requestHeadStart(text)
    val body = text.substring(start)
    val head = PhoneCommandParser.leadingWord.find(body)
    val verb = head?.let { PhoneCommandParser.canonicalVerb(it.groupValues[1]) }
    if (head == null || verb == null || verb == head.value) {
      return if (start == 0) this else FoldedRequest(raw, body, offsets.drop(start))
    }
    val end = start + head.value.length
    return FoldedRequest(raw, verb + text.substring(end), List(verb.length) { offsets[start] } + offsets.drop(end))
  }

  companion object {
    private val marks = Regex("\\p{M}+")
    private val literalText = Regex("\\b(?:que diga|con el texto|diciendo)\\b|\\b(?:comparti|compartir|comparte|compartas) (?:el |este )?texto\\b")

    fun of(input: String): FoldedRequest {
      val raw = input.trim().trimStart('¿', '¡').trim()
      val text = StringBuilder()
      val offsets = mutableListOf<Int>()
      raw.forEachIndexed { index, char ->
        if (char == ' ' || char == '\t') {
          if (text.lastOrNull() != ' ') { text.append(' '); offsets.add(index) }
        } else {
          val folded = Normalizer.normalize(char.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")
          folded.forEach { text.append(it); offsets.add(index) }
        }
      }
      val courtesy = PhoneCommandParser.courtesySuffix.find(text)
      if (courtesy != null && !literalText.containsMatchIn(text)) {
        val end = courtesy.range.first
        return FoldedRequest(raw.substring(0, offsets[end]), text.substring(0, end), offsets.subList(0, end))
      }
      return FoldedRequest(raw, text.toString(), offsets)
    }
  }
}

internal class EverydayCommandParser(private val now: () -> Calendar) {
  fun parse(r: FoldedRequest): PhoneRoute? {
    fun match(pattern: String) = Regex("^$pattern$").matchEntire(r.text)
    fun local(command: PhoneCommand) = PhoneRoute.Local(command)
    fun navigate(destination: String, mode: String): PhoneRoute {
      return if (PhoneCommandParser.normalize(destination) in setOf("lugar", "el lugar", "destino", "el destino", "ahi", "alla"))
        PhoneRoute.Clarify("¿A dónde querés ir?", "navigate")
      else local(PhoneCommand.Navigate(destination.trimEnd('.', '!', '?'), travelMode(mode)))
    }
    val structural = RequestSafetyPolicy.withoutQuotedText(r.text)
    if (Regex("^(?:llevame|navega|guiame)\\b").containsMatchIn(r.text) &&
      Regex("\\b(?:sin peajes|evitando peajes|sin autopistas|evitando autopistas|pasando por|con (?:una )?parada|en avion|en moto|en tren|en subte)\\b").containsMatchIn(r.text)) {
      return PhoneRoute.Unsupported("Ese detalle de la ruta no está disponible desde Insto. Podés completarlo directamente en Maps.")
    }
    match("(?:guarda|recorda)\\s+(?:mi\\s+)?(casa|trabajo)\\s+en\\s+(.+)")?.let {
      return local(PhoneCommand.SavePlace(it.groupValues[1], r.capture(it, 2)))
    }
    match("mi\\s+(casa|trabajo)\\s+es\\s+(.+)")?.let {
      return local(PhoneCommand.SavePlace(it.groupValues[1], r.capture(it, 2)))
    }
    val travelModes = "a pie|caminando|en auto|en coche|en bici|en bicicleta|en colectivo|en transporte publico"
    match("(?:llevame|navega|guiame)\\s+(?:a|al|hasta)\\s+(.+?)\\s+($travelModes)[.!?]*")?.let {
      return navigate(r.capture(it, 1), it.groupValues[2])
    }
    match("(?:llevame|navega|guiame)\\s+(?:($travelModes)\\s+)?(?:a|al|hasta)\\s+(.+)")?.let {
      return navigate(r.capture(it, 2), it.groupValues[1])
    }
    fun platformSearch(platform: String, query: String): PhoneRoute {
      // Conditions and extra actions are syntax, unless quoted as part of a title.
      if (Regex("\\b(?:si|cuando|despues|luego|hasta que)\\b").containsMatchIn(structural)) {
        return PhoneRoute.Unsupported("Pedí la búsqueda sin condiciones; no abrí otra aplicación.")
      }
      return local(if (platform == "youtube") PhoneCommand.YouTubeSearch(query) else PhoneCommand.SpotifySearch(query))
    }
    match("(?:busca|encontra|mostra)\\s+(.+?)\\s+en\\s+(youtube|spotify)[.!?]*")?.let {
      return platformSearch(it.groupValues[2], r.capture(it, 1))
    }
    match("(?:busca|encontra|mostra)\\s+en\\s+(youtube|spotify)\\s+(.+)")?.let {
      return platformSearch(it.groupValues[1], r.capture(it, 2))
    }
    match("(?:reproduci|pone)\\s+(.+?)\\s+en\\s+(youtube|spotify)[.!?]*")?.let {
      if (r.text.startsWith("pone ") && Regex("^(?:el |la )?(?:volumen|brillo|sonido|modo)\\b").containsMatchIn(it.groupValues[1]))
        return PhoneRoute.Unsupported("Ese ajuste no se puede aplicar a una sola aplicación.")
      val app = MediaApp.entries.first { app -> app.label.equals(it.groupValues[2], ignoreCase = true) }
      val query = r.capture(it, 1).removeSurrounding("\"").removeSurrounding("“", "”").removeSurrounding("«", "»").trim()
      if (query.length !in 1..2000) return PhoneRoute.Unsupported("Indicá un título válido para reproducir en ${app.label}.")
      return local(PhoneCommand.MusicSearch(query, app))
    }
    match("(?:busca|encontra|mostra)\\s+en\\s+(?:google\\s+)?maps\\s+(.+)")?.let {
      return local(PhoneCommand.MapSearch(r.capture(it, 1)))
    }
    match("(?:busca|encontra|mostra)\\s+(.+?)\\s+en\\s+(?:google\\s+)?maps[.!?]*")?.let {
      return local(PhoneCommand.MapSearch(r.capture(it, 1)))
    }
    match("(?:busca|encontra|mostra)\\s+(.+\\s+(?:cerca(?: de mi)?|cercan[oa]s?))[.!?]*")?.let {
      return local(PhoneCommand.MapSearch(r.capture(it, 1)))
    }
    match("(?:llama|marca)\\s+(?:(?:el\\s+)?numero\\s+de\\s+|(?:a|al)\\s+)(.+)")?.let {
      return local(PhoneCommand.Dial(r.capture(it, 1).trimEnd('.', '!', '?')))
    }
    match("abri\\s+(?:la\\s+)?camara(?:\\s+(para grabar|en modo video|de video))?[.!?]*")?.let {
      return local(PhoneCommand.Camera(it.groupValues[1].isNotEmpty()))
    }
    match("(?:pone|reproduci)\\s+(?:la\\s+)?(?:musica(?: de)?|cancion|playlist)\\s+(.+)")?.let {
      return local(PhoneCommand.MusicSearch(r.capture(it, 1)))
    }
    CalendarRequestParser(now).parse(r)?.let { return it }
    val incomplete = listOf(
      "(?:llevame|navega|guiame)(?: a| al| hasta)?" to ("¿A dónde querés ir?" to "navigate"),
      "(?:llama|marca)(?: a| al)?" to ("¿A quién querés llamar? Podés indicar un nombre o número." to "dial"),
      "(?:busca|encontra|mostra) en youtube" to ("¿Qué querés buscar en YouTube?" to "youtube"),
      "(?:busca|encontra|mostra) en spotify" to ("¿Qué querés buscar en Spotify?" to "spotify"),
      "(?:busca|encontra|mostra) en (?:google )?maps" to ("¿Qué lugar querés buscar?" to "maps"),
      "(?:pone|reproduci) musica(?: de)?" to ("¿Qué música querés escuchar?" to "music"),
    )
    incomplete.forEach { (pattern, clarification) ->
      if (match("$pattern[.!?]*") != null) return PhoneRoute.Clarify(clarification.first, clarification.second)
    }
    if (Regex("^(?:llevame|navega|guiame|llama|guarda (?:mi )?(?:casa|trabajo))\\b").containsMatchIn(r.text)) {
      return PhoneRoute.Unsupported("No pude interpretar todos los detalles del pedido. Indicá el destino, contacto o dirección completos.")
    }
    return null
  }

  private fun travelMode(mode: String): TravelMode = when (mode) {
    "a pie", "caminando" -> TravelMode.WALKING
    "en bici", "en bicicleta" -> TravelMode.BICYCLING
    "en colectivo", "en transporte publico" -> TravelMode.TRANSIT
    else -> TravelMode.DRIVING
  }
}
