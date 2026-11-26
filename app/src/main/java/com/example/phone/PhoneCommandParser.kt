package com.example.phone

import java.text.Normalizer
import java.util.Locale

/** Deliberately anchored grammar: mentioning a capability is not an instruction to use it. */
class PhoneCommandParser(private val calendarNow: () -> java.util.Calendar = { java.util.Calendar.getInstance() }) {
  private val alarmRequest = Regex("^(?:(?:(?:pone|configura|crea|programa|inicia|arranca|activa|establece)\\s+)?(?:(?:un|una|el|la)\\s+)?(?:alarma|despertador)\\b|despertame\\b)(.*)$")
  /** General searches require a leading command verb and a nonempty query. */
  fun chatGptSearchQuery(text: String, local: PhoneRoute = parse(text)): String? {
    val match = chatGptSearchPrefix.find(text.trimStart()) ?: return null
    val verb = normalize(match.groupValues[1])
    if (verb !in setOf("busca", "buscar", "search")) return null
    if (local is PhoneRoute.Local || local is PhoneRoute.Clarify) return null
    return text.trimStart().substring(match.range.last + 1).trim().takeIf { it.isNotEmpty() }
  }

  fun parseReply(context: String, text: String): PhoneRoute {
    val direct = parse(text)
    if (direct != PhoneRoute.Instinct) return direct
    val normalized = normalize(text)
    if (normalized in listOf("no", "nunca") || normalized.startsWith("no ") || normalized.startsWith("nunca ")) return PhoneRoute.Cancel
    if (normalized in listOf("si", "dale", "ok") || normalized.startsWith("si ")) {
      return PhoneRoute.Clarify("Indicá el dato del pedido sin condiciones, o decí cancelar.", context)
    }
    if (normalized.startsWith("instinct") || isExplicitGeneralRequest(text)) return PhoneRoute.Instinct
    if (Regex("^(?:(?:como|que|por que|conta)\\b|(?:me )?expli)").containsMatchIn(normalized)) return PhoneRoute.Instinct
    when (context) {
      "app" -> return parse("abrí $text")
      "navigate" -> return parse("llevame a $text")
      "dial" -> return parse("llamá a $text")
      "youtube" -> return parse("buscá en YouTube $text")
      "spotify" -> return parse("buscá en Spotify $text")
      "maps" -> return parse("buscá en Maps $text")
      "music" -> return parse("poné música de $text")
      "calendar" -> return PhoneRoute.Clarify("Repetí el evento con título, fecha y hora: agendá reunión mañana a las 17:00.", "calendar")
    }
    // Only parameter-shaped replies may inherit a pending action. Explanations remain queries.
    val parameter = Regex("^(?:(?:a|para) las? )?([a-z0-9]+)").find(normalized)?.groupValues?.get(1)
    if (parameter == null || number(parameter) == null) return PhoneRoute.Instinct
    return when (context) {
      "timer" -> parse("temporizador de $text")
      "alarm" -> parse("alarma $text")
      "volume" -> parse("pone volumen al $text")
      else -> direct
    }
  }

  internal var shortcutRoutes: ((String, PhoneRoute) -> PhoneRoute)? = null

  fun parse(text: String): PhoneRoute {
    val native = parseNative(text)
    return shortcutRoutes?.invoke(text, native) ?: native
  }

  private fun parseNative(text: String): PhoneRoute {
    val original = normalize(text)
    val s = canonicalRequest(original)
    if (s in listOf("cancelar", "cancela", "olvidalo", "nada", "dejalo")) return PhoneRoute.Cancel
    val registry = LocalIntentRegistry.default
    val phrase = registry.matchPhrase(s)
    if (phrase is PhoneRoute.Local) return phrase
    // SMN queries are questions with an anchored grammar (no negations, explanations or extra actions);
    // resolve them before the generic «qué/cómo/contame» rules below send them to Instinct.
    WeatherIntentParser.parse(text)?.let { return it }
    if (s.isBlank() || (!RequestSafetyPolicy.isDoNotDisturbRequest(s) &&
        Regex("^(no |nunca |si |(?:me )?expli|conta|instinct)").containsMatchIn(s))) return PhoneRoute.Instinct
    val request = FoldedRequest.of(text)
    if (RequestSafetyPolicy.hasJoinedActions(request.text))
      return PhoneRoute.Unsupported("Pedime una acción por vez para interpretar todos sus detalles.")
    val command = request.withCommandHead()
    if (Regex("^(?:borra|limpia|pone en cero) (?:el )?(?:tiempo del )?cronometro(?: para empezar de cero)?$").matches(s)) return PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.RESET))
    val clockSyntax = if (numericDot.containsMatchIn(text)) canonicalRequest(normalizeForClock(text)) else s
    ExtendedCommandParser.parse(clockSyntax, calendarNow)?.let { return it }
    if (RequestSafetyPolicy.rejection(text) == null) CalendarSelectionParser(calendarNow).parse(command)?.let { return it }
    val everyday = EverydayCommandParser(calendarNow).parse(command)
    // An explicit platform search must not be read as a contact or document search for its title.
    if (everyday is PhoneRoute.Local && (everyday.command is PhoneCommand.SpotifySearch || everyday.command is PhoneCommand.YouTubeSearch))
      return everyday
    if (RequestSafetyPolicy.rejection(text) == null) registry.matchParameters(command)?.let { return it }
    if (Regex("^(que |como |por que )").containsMatchIn(s)) return PhoneRoute.Instinct
    if (everyday != null) return everyday
    val commandPrefix = "(?:(?:pone|configura|crea|programa|inicia|arranca|activa|establece)\\s+)?(?:(?:un|una|el|la)\\s+)?"
    val timer = Regex("^${commandPrefix}(?:timer|temporizador|cuenta regresiva|cuenta atras)\\b(.*)$").matchEntire(s)
    if (timer != null) {
      val body = timer.groupValues[1].trim().removePrefix("de ")
      val duration = body.substringBefore(" para ").substringBefore(" llamado ")
      val seconds = CommandDuration.seconds(duration)
        ?: return PhoneRoute.Clarify("¿Cuánto tiempo? Por ejemplo, diez minutos.", "timer")
      val label = when { " para " in body -> body.substringAfter(" para "); " llamado " in body -> body.substringAfter(" llamado "); else -> "" }
      return PhoneRoute.Local(PhoneCommand.Timer(seconds, label))
    }
    alarmRequest.matchEntire(clockSyntax)?.let { return alarm(it.groupValues[1]) }
    if (Regex("^(?:mostra|ver|abrir|abri|muestra) (?:las |los |mis )?(alarmas|timers|temporizadores)$").matches(s)) {
      return PhoneRoute.Local(PhoneCommand.ClockList(s.endsWith("alarmas")))
    }
    val chrono = Regex("^(?:(inicia|iniciar|arranca|arrancar|pone|pon|pausa|pausar|para|parar|detene|continua|continuar|reanuda|reinicia|reiniciar|resetea|ver|mostra|consulta|abri|abre|abrir) (?:el |un )?)?cronometro$").matchEntire(s)
    if (chrono != null) return PhoneRoute.Local(PhoneCommand.Stopwatch(when (chrono.groupValues[1]) {
      "pausa", "pausar", "para", "parar", "detene" -> StopwatchOp.PAUSE
      "continua", "continuar", "reanuda" -> StopwatchOp.RESUME
      "reinicia", "reiniciar", "resetea" -> StopwatchOp.RESET
      "ver", "mostra", "consulta", "abri", "abre", "abrir", "" -> StopwatchOp.STATUS
      else -> StopwatchOp.START
    }))
    val torch = Regex("^(prende|prender|prendeme|enciende|encende|encender|encendeme|activa|activar|apaga|apagar|apagame|desactiva|desactivar) (?:la )?linterna$").matchEntire(s)
    if (torch != null) return PhoneRoute.Local(PhoneCommand.Torch(torch.groupValues[1] !in listOf("apaga", "apagar", "apagame", "desactiva", "desactivar")))
    if (Regex("^(subi|aumenta) (?:el )?volumen$").matches(s)) return PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.UP))
    if (Regex("^(baja|reduce) (?:el )?volumen$").matches(s)) return PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.DOWN))
    val volume = Regex("^(?:(?:pone|fija|ajusta|subi|baja|reduce|aumenta) (?:el )?(?:volumen(?: multimedia)?|sonido)|deja (?:el )?audio multimedia) (?:al? )?(.+?)(?: por ciento|%)?$").matchEntire(s)
    if (volume != null) {
      val n = number(volume.groupValues[1])
      return if (n != null && n in 0..100) PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, n))
        else PhoneRoute.Clarify("Indicá un volumen entre cero y cien por ciento.", "volume")
    }
    val setting = Regex("^(?:abri|activa|desactiva|apaga|enciende|prende|configura|conecta) (?:el |los |la )?(?:ajustes de )?(wifi|wi fi|wi-fi|bluetooth|conectividad|internet|ajustes)$").matchEntire(s)
    if (setting != null) return PhoneRoute.Local(PhoneCommand.Settings(setting.groupValues[1].replace("wi fi", "wifi").replace("wi-fi", "wifi")))
    if (phrase != null) return phrase
    if (Regex("^(?:abri|lanza)(?: (?:la |una )?(?:app|aplicacion))?$").matches(s))
      return PhoneRoute.Clarify("¿Qué aplicación querés abrir?", "app")
    when (s) {
      "abri musica", "abri la musica" -> return PhoneRoute.Clarify("¿Qué aplicación de música querés abrir?", "app")
      "apaga la luz", "apaga luz" -> return PhoneRoute.Clarify("Indicá la acción completa. Para la luz del teléfono, pedí apagar la linterna.", "action")
      "baja spotify" -> return PhoneRoute.Clarify("Indicá la acción completa: bajar el volumen, pausar Spotify o lo que necesites.", "action")
      "detene todo" -> return PhoneRoute.Clarify("Indicá qué querés detener.", "action")
    }
    val app = Regex("^(?:abri|lanza|inicia) (?:(?:la )?(?:app|aplicacion) )?(.+)$").matchEntire(s)
    if (app != null) return PhoneRoute.Local(PhoneCommand.OpenApp(app.groupValues[1]))
    // Recognized requests outside our executor must never silently become a message to Instinct.
    if (Regex("^(?:cancela|cancelar|elimina|borra|pausa|pausar|reinicia|desactiva|cambia|modifica|apaga|pone|pon|activa|prende|enciende|inicia|crea|programa)\\b.*\\b(?:alarmas?|timers?|temporizador(?:es)?|cronometro|linterna|volumen|wifi|bluetooth)\\b").containsMatchIn(s)) {
      return PhoneRoute.Unsupported("Ese pedido del teléfono todavía no está disponible. Podés abrir el Reloj o los ajustes para completarlo.")
    }
    if (Regex("^(?:necesito|quiero|me|pone|pon|encende|encender|encendeme|enciende|prende|prender|prendeme|apaga|apagar|apagame|activa|activar|inicia|iniciar|configura)\\b.*\\b(?:linterna|alarma|timer|temporizador|cronometro|volumen|wifi|bluetooth)\\b").containsMatchIn(s)) {
      return PhoneRoute.Unsupported("Reconocí un pedido del teléfono, pero no todos sus detalles. No se envió a Instinct. Podés reformularlo.", recoverable = true, reason = RejectionReason.NOT_UNDERSTOOD)
    }
    if (requestHeadStart(original) != 0 &&
      Regex("\\b(?:linterna|alarmas?|timers?|temporizadores?|cronometro|volumen|wifi|bluetooth)\\b").containsMatchIn(s)) {
      return PhoneRoute.Unsupported("Reconocí un pedido del teléfono, pero no todos sus detalles. No se envió a Instinct. Podés reformularlo.", recoverable = true, reason = RejectionReason.NOT_UNDERSTOOD)
    }
    return PhoneRoute.Instinct
  }

  internal fun replyToAlarm(source: String, reply: String): PhoneRoute? {
    val time = ClockTime.reply(reply)
    if (!ClockTime.isClockReply(time)) return null
    val match = alarmRequest.matchEntire(canonicalRequest(normalizeForClock(source))) ?: return null
    return alarm(match.groupValues[1], time)
  }

  internal fun replyToDeadline(source: String, reply: String): PhoneRoute? {
    val time = ClockTime.reply(reply)
    if (!ClockTime.isClockReply(time)) return null
    return ExtendedCommandParser.parse(canonicalRequest(normalizeForClock(source)), calendarNow, time)
  }

  private fun alarm(input: String, replacement: String? = null): PhoneRoute {
    var body = input.trim()
    var requestedDate: String? = null
    Regex("^(?:para )?(hoy|manana)\\b\\s*(.*)$").matchEntire(body)?.let { match ->
      val day = calendarNow().clone() as java.util.Calendar
      if (match.groupValues[1] == "manana") day.add(java.util.Calendar.DAY_OF_MONTH, 1)
      requestedDate = "%04d-%02d-%02d".format(java.util.Locale.ROOT,day.get(java.util.Calendar.YEAR),day.get(java.util.Calendar.MONTH)+1,day.get(java.util.Calendar.DAY_OF_MONTH))
      body = match.groupValues[2]
    }
    val suffix = Regex("(?:^| )(todos los dias|cada dia|(?:todos los|cada) [a-z]+(?: y [a-z]+)*)(?: (?:llamad[ao]|con nombre) (.+))?$|(?:^| )(?:llamad[ao]|con nombre) (.+)$").find(body)
    val clock = body.substring(0, suffix?.range?.first ?: body.length)
      .removePrefix("para las ").removePrefix("para la ").removePrefix("a las ").removePrefix("a la ")
      .takeUnless { it in listOf("a las", "a la", "para las", "para la") }.orEmpty()
    val datePart = clock.replace("de la manana", "")
    if (Regex("\\b(?:manana|pasado|hoy|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\b|[0-9]+[/-][0-9]+|\\bel [0-9]+").containsMatchIn(datePart)) {
      return PhoneRoute.Unsupported("Para una fecha específica, abrí las alarmas del Reloj y completá la fecha allí.")
    }
    if (Regex("\\b(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b").containsMatchIn(clock)) {
      return PhoneRoute.Unsupported("Para una fecha específica, abrí las alarmas del Reloj y completá la fecha allí.")
    }
    if (clock.isNotEmpty() && !ClockTime.shape(clock))
      return PhoneRoute.Unsupported("No pude interpretar todos los detalles de esa alarma. Indicá la hora completa; para fechas especiales, abrí las alarmas del Reloj.")
    val repeating = suffix?.groupValues?.get(1).orEmpty()
    val days = if (repeating in listOf("todos los dias", "cada dia")) (1..7).toList() else if (repeating.isEmpty()) emptyList() else {
      val names = mapOf("domingo" to 1, "domingos" to 1, "lunes" to 2, "martes" to 3, "miercoles" to 4, "jueves" to 5, "viernes" to 6, "sabado" to 7, "sabados" to 7)
      val requested = repeating.removePrefix("todos los ").removePrefix("cada ").split(" y ")
      if (requested.any { it !in names }) return PhoneRoute.Unsupported("No pude interpretar los días de repetición. Indicá los días completos.")
      requested.map { names.getValue(it) }.distinct().sorted()
    }

    if (requestedDate != null && days.isNotEmpty()) return PhoneRoute.Unsupported("Indicá una fecha o una repetición, sin combinar ambas.")
    val label = normalize(suffix?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }.orEmpty())
    val resolved = when {
      replacement == null -> clock
      ClockTime.isPeriod(replacement) -> ClockTime.replacePeriod(clock, replacement)
      else -> replacement
    }
    return when (val time = ClockTime.parse(resolved)) {
      is ClockTime.Exact -> PhoneRoute.Local(PhoneCommand.Alarm(time.hour, time.minute, days, label, requestedDate))
      ClockTime.Ambiguous -> PhoneRoute.Clarify("¿De la mañana o de la tarde? Indicá el período o una hora como 17:30.", "alarm")
      ClockTime.Invalid -> PhoneRoute.Clarify(
        if (clock.isEmpty()) "¿A qué hora? Por ejemplo, siete y media de la mañana."
        else "Indicá una hora válida, por ejemplo 07:30.", "alarm")
    }
  }

  companion object {
    private val numericDot = Regex("(?<=[0-9])\\.(?=[0-9])")
    private val punctuation = Regex("[¿?¡!,.;]")
    private val clockPunctuation = Regex("[¿?¡!,;]|(?<![0-9])\\.|\\.(?![0-9])")
    private val chatGptSearchPrefix = Regex("^(\\p{L}[\\p{L}\\p{M}]*)(?:\\s*:\\s*|\\s+)")
    private const val COURTESY = "(?:por (?:favor|fa)|porfa)"
    private val politeness = Regex("^$COURTESY[,\\s]+[¿¡]*\\s*")
    internal val courtesySuffix = Regex("[,\\s]+$COURTESY[.!?]*$", RegexOption.IGNORE_CASE)
    private val requestPrefix = Regex(
      "^(?:(?:quiero|necesito|deseo|quisiera|me gustaria)(?:\\s+que)?|(?:me\\s+)?(?:podes|puedes|podrias|podras|podria|puede))[,\\s]+[¿¡]*\\s*"
    )
    internal val leadingWord = Regex("^(?:me\\s+)?([a-z]+)\\b")
    private val requestVerbs = mapOf(
      "enciende" to "enciendas encienda enciendan encender enciende encende encendeme enciendeme",
      "prende" to "prendas prenda prendan prender prende prendeme",
      "apaga" to "apagues apague apaguen apagar apaga apagame",
      "activa" to "actives active activen activar activa",
      "desactiva" to "desactives desactive desactiven desactivar desactiva",
      "pone" to "pongas ponga pongan poner pon pone poneme ponme pones",
      "ponlo" to "ponlo ponelo ponerlo pongaslo pongalo",
      "configura" to "configures configure configurar configura configurame",
      "programa" to "programes programe programar programa",
      "crea" to "crees cree crear crea",
      "inicia" to "inicies inicie iniciar inicia",
      "arranca" to "arranques arranque arrancar arranca",
      "pausa" to "pauses pause pausar pausa",
      "para" to "pares pare parar para",
      "detene" to "detengas detenga detener detene detente deten",
      "continua" to "continues continue continuar continua",
      "reanuda" to "reanudes reanude reanudar reanuda",
      "reinicia" to "reinicies reinicie reiniciar reinicia",
      "resetea" to "resetees resetee resetear resetea",
      "subi" to "subas suba subir sube subi subile subime",
      "baja" to "bajes baje bajar baja bajale bajame",
      "aumenta" to "aumentes aumente aumentar aumenta",
      "ajusta" to "ajustes ajuste ajustar ajusta",
      "fija" to "fijes fije fijar fija",
      "abri" to "abras abra abran abrir abre abri abrime abreme",
      "mostra" to "muestres muestre mostrar muestra mostra mostrarme mostrame muestrame mostras",
      "ver" to "veas vea ver",
      "silencia" to "silenciar silencia silencies silencie silenciame",
      "deja" to "dejar deja dejes deje",
      "dejalo" to "dejarlo dejalo",
      "quita" to "quitar quita quites quite",
      "volve" to "volver volve vuelve vuelvas vuelva",
      "consulta" to "consultar consulta consultes consulte",
      "decime" to "decir dime decime digas digame",
      "despertame" to "despertame despiertame despertarme despiertes",
      "agenda" to "agendar agenda agendes agende agendame",
      "pospone" to "posponer pospone pospon aplaza aplazar",
      "cancela" to "cancelar cancela canceles cancele",
      "descarta" to "descartar descarta descartes descarte",
      "cambia" to "cambiar cambia cambies cambie",
      "edita" to "editar edita edites edite",
      "modifica" to "modificar modifica modifiques modifique",
      "reduce" to "reducir reduce reduzcas",
      "conecta" to "conectar conecta conectes conecte",
      "busca" to "buscar busca busques busque buscarme buscame",
      "encontra" to "encontrar encontra encuentra encuentres encuentre",
      "llama" to "llamar llama llames llame llamame llamala llamalo",
      "marca" to "marcar marca marques marque marcame",
      "lanza" to "lanzar lanza lances lance",
      "navega" to "navegar navega navegues navegue",
      "llevame" to "llevame llevarme lleves",
      "guiame" to "guiar guiame guiarme guies",
      "guarda" to "guardar guarda guardes guarde",
      "recorda" to "recordar recorda recuerda recuerdes",
      "reproduci" to "reproducir reproduci reproduce reproduzcas reproduzca",
      "pasa" to "pasar pasa pases pase",
      "salta" to "saltar salta saltes salte",
      "saca" to "sacame sacarme sacar saca saques saque",
      "toma" to "tomar toma tomame tomes",
      "hace" to "hacer hace haz hazme haceme hagas",
      "captura" to "capturar captura captures",
      "graba" to "grabar graba grabame grabes",
      "manda" to "mandar manda mandame mandarme mandes mande mandale mandarle",
      "envia" to "enviar envia enviame enviarme envies envie enviale enviarle",
      "redacta" to "redactar redacta redactes",
      "prepara" to "preparar prepara prepares",
      "comparti" to "compartir comparte comparti compartas",
      "borra" to "borrar borra borres borre",
      "elimina" to "eliminar elimina elimines suprimi suprimir",
      "copia" to "copiar copia copies",
      "move" to "mover mueve move muevas",
      "renombra" to "renombrar renombra renombres",
      "reprograma" to "reprogramar reprograma reprogrames",
      "ejecuta" to "ejecutar ejecuta ejecutes",
      "importa" to "importar importa importes",
      "lista" to "listar lista listes",
      "lee" to "leer lee leeme leas",
      "termina" to "terminar termina termines",
      "restaura" to "restaurar restaura restaures"
    ).flatMap { (canonical, variants) -> variants.split(' ').map { it to canonical } }.toMap()

    private val explicitGeneralRequest = Regex(
      "^(?:como se (?:prende|enciende|apaga) (?:la )?linterna$|" +
        "escrib(?:ir|i|e)(?:me)? (?:un |una )?(?:cuento|historia)\\b|investig(?:a|ar) (?:las )?ultimas noticias\\b|" +
        "prepara (?:un )?analisis\\b|organiz(?:ar|a)(?:me)? una investigacion\\b)"
    )

    internal fun isExplicitGeneralRequest(text: String): Boolean =
      explicitGeneralRequest.containsMatchIn(canonicalRequest(normalize(text)))

    internal fun requestHeadStart(text: String): Int {
      val start = politeness.find(text)?.value?.length ?: 0
      val wrapper = requestPrefix.find(text.substring(start)) ?: return start
      val afterWrapper = start + wrapper.value.length
      return afterWrapper + (politeness.find(text.substring(afterWrapper))?.value?.length ?: 0)
    }

    internal fun canonicalVerb(word: String): String? = requestVerbs[word]

    /** Fold command syntax without changing the stored text of literal parameters. */
    internal fun canonicalRequest(text: String): String {
      val request = text.substring(requestHeadStart(text)).replace(courtesySuffix, "").trim()
      val head = leadingWord.find(request) ?: return request
      val verb = canonicalVerb(head.groupValues[1]) ?: return request
      return verb + request.substring(head.value.length)
    }
    fun normalize(text: String): String = normalize(text, punctuation)
    internal fun normalizeForClock(text: String): String = normalize(text, clockPunctuation)
    private fun normalize(text: String, punctuation: Regex): String =
      Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(punctuation, " ").replace(Regex("\\s+"), " ").trim()
    private val words = listOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho", "veintinueve")
    fun choiceIndex(text: String): Int? {
      val s=normalize(text).removePrefix("el ").removePrefix("la ").removePrefix("opcion ")
      val ordinal=mapOf("primero" to 0,"primer" to 0,"primera" to 0,"segundo" to 1,"segunda" to 1,"tercero" to 2,"tercera" to 2,"cuarto" to 3,"cuarta" to 3,"quinto" to 4,"quinta" to 4)
      return ordinal[s] ?: number(s)?.takeIf {it>0}?.minus(1)
    }
    fun number(text: String): Int? {
      val s = text.trim()
      s.toIntOrNull()?.let { return it }
      if (s in listOf("un", "una")) return 1
      words.indexOf(s).takeIf { it >= 0 }?.let { return it }
      val tens = mapOf("treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90, "cien" to 100)
      tens[s]?.let { return it }
      val parts = s.split(" y ")
      if (parts.size == 2) return tens[parts[0]]?.let { t -> words.indexOf(parts[1]).takeIf { it in 1..9 }?.plus(t) }
      return null
    }
  }
}
