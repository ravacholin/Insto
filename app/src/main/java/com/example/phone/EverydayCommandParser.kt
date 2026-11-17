package com.example.phone

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Match folded command syntax while retaining the original text of every payload. */
internal class FoldedRequest private constructor(val raw: String, val text: String, private val offsets: List<Int>) {
  fun capture(match: MatchResult, group: Int): String {
    val range = match.groups[group]?.range ?: return ""
    if (range.isEmpty()) return ""
    return raw.substring(offsets[range.first], offsets.getOrNull(range.last + 1) ?: raw.length).trim()
  }
  companion object {
    fun of(input: String): FoldedRequest {
      val raw = input.trim().trimStart('¿', '¡').trim()
        .replace(Regex("\\s+por favor[.!?]*$", RegexOption.IGNORE_CASE), "")
      val text = StringBuilder()
      val offsets = mutableListOf<Int>()
      raw.forEachIndexed { index, char ->
        val folded = Normalizer.normalize(char.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        folded.forEach { text.append(it); offsets.add(index) }
      }
      return FoldedRequest(raw, text.toString(), offsets)
    }
  }
}

internal class EverydayCommandParser(private val now: () -> Calendar) {
  fun parse(input: String): PhoneRoute? {
    val r = FoldedRequest.of(input)
    fun match(pattern: String) = Regex("^$PREFIX$pattern$").matchEntire(r.text)
    fun local(command: PhoneCommand) = PhoneRoute.Local(command)
    val verbs = "llevame|lleves|llevarme|navega|navegues|navegar|llama|llames|llamar|marca|marques|agenda|agendes|agendar|programa|programes|guarda|guardes|guardar|recorda|pone|pongas|poneme|abri|abras|abre|abrir|busca|busques|buscame|buscar|reproduci|reproduce|reproduzcas|prende|prendas|apaga|apagues|enciende|encende|inicia|inicies|reinicia|reinicies|pausa|pauses|activa|actives|desactiva|desactives|subi|baja|mostra|muestres"
    val structural = r.text.replace(Regex("[\"]([^\"]*)[\"]|“[^”]*”"), "")
    if (Regex("^$PREFIX(?:(?:$verbs)\\b|mi (?:casa|trabajo) es\\b)").containsMatchIn(structural) &&
      Regex("(?:\\s+(?:(?:y\\s+)?(?:despues|luego)|y)\\s+|[;\\n]\\s*|\\.\\s+)$PREFIX(?:$verbs)\\b").containsMatchIn(structural)) {
      return PhoneRoute.Unsupported("Pedime una acción por vez para interpretar todos sus detalles.")
    }
    if (Regex("^$PREFIX(?:llevame|lleves|llevarme|navega|navegues|navegar)\\b").containsMatchIn(r.text) &&
      Regex("\\b(?:sin peajes|evitando peajes|sin autopistas|evitando autopistas|pasando por|con (?:una )?parada|en avion|en moto|en tren|en subte)\\b").containsMatchIn(r.text)) {
      return PhoneRoute.Unsupported("Ese detalle de la ruta no está disponible desde Insto. Podés completarlo directamente en Maps.")
    }
    match("(?:guarda|guardar|guardes|recorda)\\s+(?:mi\\s+)?(casa|trabajo)\\s+en\\s+(.+)")?.let {
      return local(PhoneCommand.SavePlace(it.groupValues[1], r.capture(it, 2)))
    }
    match("mi\\s+(casa|trabajo)\\s+es\\s+(.+)")?.let {
      return local(PhoneCommand.SavePlace(it.groupValues[1], r.capture(it, 2)))
    }
    match("(?:llevame|llevarme|lleves|navega|navegar|navegues)\\s+(?:a|al)\\s+(.+?)\\s+(a pie|en auto|en coche|en bici|en bicicleta|en colectivo|en transporte publico)")?.let {
      return local(PhoneCommand.Navigate(r.capture(it, 1), travelMode(it.groupValues[2])))
    }
    match("(?:llevame|llevarme|lleves|navega|navegar|navegues)\\s+(?:(a pie|en auto|en coche|en bici|en bicicleta|en colectivo|en transporte publico)\\s+)?(?:a|al)\\s+(.+)")?.let {
      return local(PhoneCommand.Navigate(r.capture(it, 2), travelMode(it.groupValues[1])))
    }
    fun platformSearch(platform: String, query: String): PhoneRoute {
      // Conditions and extra actions are syntax, unless quoted as part of a title.
      if (Regex("\\b(?:si|cuando|despues|luego|hasta que)\\b").containsMatchIn(structural)) {
        return PhoneRoute.Unsupported("Pedí la búsqueda sin condiciones; no abrí otra aplicación.")
      }
      return local(if (platform == "youtube") PhoneCommand.YouTubeSearch(query) else PhoneCommand.SpotifySearch(query))
    }
    match("(?:busca|buscar|busques|buscame|mostra|mostrar|muestres)\\s+(.+?)\\s+en\\s+(youtube|spotify)[.!?]*")?.let {
      return platformSearch(it.groupValues[2], r.capture(it, 1))
    }
    match("(?:busca|buscar|busques|buscame|mostra|mostrar|muestres)\\s+en\\s+(youtube|spotify)\\s+(.+)")?.let {
      return platformSearch(it.groupValues[1], r.capture(it, 2))
    }
    match("(?:busca|buscar|busques|buscame)\\s+en\\s+(?:google\\s+)?maps\\s+(.+)")?.let {
      return local(PhoneCommand.MapSearch(r.capture(it, 1)))
    }
    match("(?:busca|buscar|busques|buscame)\\s+(.+\\s+(?:cerca(?: de mi)?|cercan[oa]s?))")?.let {
      return local(PhoneCommand.MapSearch(r.capture(it, 1)))
    }
    match("(?:llama|llamar|llames|llamame|marca|marcar|marques)\\s+(?:a|al)\\s+(.+)")?.let {
      return local(PhoneCommand.Dial(r.capture(it, 1).trimEnd('.', '!', '?')))
    }
    match("(?:abri|abrir|abras|abre)\\s+(?:la\\s+)?camara(?:\\s+(para grabar|en modo video|de video))?")?.let {
      return local(PhoneCommand.Camera(it.groupValues[1].isNotEmpty()))
    }
    match("(?:pone|poneme|pongas|reproduci|reproduce|reproduzcas)\\s+(?:la\\s+)?(?:musica(?: de)?|cancion|playlist)\\s+(.+)")?.let {
      return local(PhoneCommand.MusicSearch(r.capture(it, 1)))
    }
    CalendarRequestParser(now).parse(r, PREFIX)?.let { return it }
    val incomplete = listOf(
      "(?:llevame|navega|lleves)(?: a| al)?" to ("¿A dónde querés ir?" to "navigate"),
      "(?:llama|llames|marca)(?: a| al)?" to ("¿A quién querés llamar? Podés indicar un nombre o número." to "dial"),
      "(?:busca|buscar|busques|buscame) en youtube[.!?]*" to ("¿Qué querés buscar en YouTube?" to "youtube"),
      "(?:busca|buscar|busques|buscame) en spotify[.!?]*" to ("¿Qué querés buscar en Spotify?" to "spotify"),
      "(?:busca|busques) en (?:google )?maps" to ("¿Qué lugar querés buscar?" to "maps"),
      "(?:pone|pongas|reproduci|reproduce) musica(?: de)?" to ("¿Qué música querés escuchar?" to "music"),
    )
    incomplete.forEach { (pattern, clarification) ->
      if (match(pattern) != null) return PhoneRoute.Clarify(clarification.first, clarification.second)
    }
    if (Regex("^$PREFIX(?:llevame|lleves|navega|llama|llames|guarda (?:mi )?(?:casa|trabajo))\\b").containsMatchIn(r.text)) {
      return PhoneRoute.Unsupported("No pude interpretar todos los detalles del pedido. Indicá el destino, contacto o dirección completos.")
    }
    return null
  }

  private fun travelMode(mode: String): TravelMode = when (mode) {
    "a pie" -> TravelMode.WALKING
    "en bici", "en bicicleta" -> TravelMode.BICYCLING
    "en colectivo", "en transporte publico" -> TravelMode.TRANSIT
    else -> TravelMode.DRIVING
  }

  companion object {
    const val PREFIX = "(?:por favor\\s+)?(?:(?:(?:quiero|necesito|quisiera|me gustaria)(?:\\s+que)?|(?:me\\s+)?(?:podes|puedes|podrias))\\s+)?(?:me\\s+)?"
  }
}
