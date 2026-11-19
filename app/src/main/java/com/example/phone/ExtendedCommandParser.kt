package com.example.phone

import java.util.Calendar

internal object ExtendedCommandParser {
 fun parse(s: String, now: () -> Calendar, deadlineReply: String? = null): PhoneRoute? {
  fun local(c: PhoneCommand) = PhoneRoute.Local(c)
  if (s in listOf("termina el modo", "termina el modo y restaura ahora", "restaura ahora", "restaura el volumen", "desactiva el modo temporal")) return local(PhoneCommand.EndMode)
  Regex("^(pausa|para|detene) (?:(?:el |la )?(?:audio|musica|reproduccion) (?:de|en) |en )?(spotify|youtube)$").matchEntire(s)?.let {
   val app = MediaApp.entries.first { app -> app.label.equals(it.groupValues[2], ignoreCase = true) }
   val operation = if (it.groupValues[1] == "pausa") MediaOp.PAUSE else MediaOp.STOP
   return local(PhoneCommand.Media(operation, app.packageName))
  }
  val media = when {
   Regex("^pausa (?:la |el )?(?:musica|reproduccion|audio)$").matches(s) -> MediaOp.PAUSE
   Regex("^(?:para|detene|apaga) (?:la |el )?(?:musica|reproduccion|audio)$").matches(s) -> MediaOp.STOP
   Regex("^(?:continua|reanuda|reproduci|inicia) (?:la )?(?:musica|reproduccion)$").matches(s) -> MediaOp.PLAY
   Regex("^(?:(?:pasa|pasar|salta) (?:al |a la )?)?(?:siguiente|proximo) (?:tema|cancion)$").matches(s) -> MediaOp.NEXT
   Regex("^(?:(?:volve|pasa) (?:al |a la )?)?(?:anterior) (?:tema|cancion)$").matches(s) -> MediaOp.PREVIOUS
   s in listOf("que esta sonando", "mostra la musica", "consulta la reproduccion") -> MediaOp.STATUS
   else -> null
  }
  if (media != null) return local(PhoneCommand.Media(media))
  val timed = Regex("^(.+?) (durante|por(?! ciento\\b)|hasta)(?: (.+?))?(?: y (?:(?:despues|luego) )?restaura(?:lo)?| con restauracion)?$").matchEntire(s)
    ?: return null
  val mode = timed.groupValues[1]
  val volume = Regex("^(?:pone|fija|ajusta|subi|baja) (?:el )?volumen(?: multimedia)? (?:al? )?(.+?)(?: por ciento|%)?$").matchEntire(mode)
  val fixed = (LocalIntentRegistry.default.matchPhrase(mode) as? PhoneRoute.Local)?.command as? PhoneCommand.Platform
  val kind = when {
   volume != null -> ModeKind.MEDIA_VOLUME
   fixed?.action == PlatformAction.SILENT_ON -> ModeKind.SILENT
   fixed?.action == PlatformAction.DND_ON || mode == "no molestar" -> ModeKind.DND
   fixed?.action == PlatformAction.VIBRATE_ON -> return PhoneRoute.Unsupported("El modo vibración temporal no está disponible.")
   else -> return null
  }
  val timing = timed.groupValues[3]
  val clockDeadline = timed.groupValues[2] == "hasta"
  if (deadlineReply != null && !clockDeadline) return null
  val current = if (clockDeadline) now() else null
  val deadline = if (current != null) {
   val match = Regex("^(?:(hoy|manana)(?: (?:a )?|$))?(?:las? )?(.*?)(?<!de la)(?: (hoy|manana))?$").matchEntire(timing)
   val prefixDay = match?.groupValues?.get(1).orEmpty()
   val clock = match?.groupValues?.get(2).orEmpty()
   val suffixDay = match?.groupValues?.get(3).orEmpty()
   val resolvedClock = when {
    deadlineReply == null -> clock
    clock.isNotEmpty() && !ClockTime.shape(clock) -> return null
    ClockTime.isPeriod(deadlineReply) -> ClockTime.replacePeriod(clock, deadlineReply)
    else -> deadlineReply
   }
   val time = ClockTime.parse(resolvedClock)
   if (time == ClockTime.Ambiguous) return PhoneRoute.Clarify(
    "Indicá si la hora es de la mañana o de la tarde, o usá una hora de 24 horas como 17:30.", "temporary_deadline")
   if (time !is ClockTime.Exact || prefixDay.isNotEmpty() && suffixDay.isNotEmpty()) {
    val message = "No pude leer la hora de restauración. Probá con un formato como 12:20 pm, 12.20 pm o 23:00."
    return if ((clock.isEmpty() || ClockTime.shape(clock)) && !(prefixDay.isNotEmpty() && suffixDay.isNotEmpty()))
     PhoneRoute.Clarify(message, "temporary_deadline") else PhoneRoute.Unsupported(message)
   }
   val day = prefixDay.ifEmpty { suffixDay }
   val tomorrow = (current.clone() as Calendar).apply { add(Calendar.DATE, 1) }
   val first = time.onDate(if (day == "manana") tomorrow else current)
   val resolved = if (day.isEmpty() && first != null && first <= current.timeInMillis) time.onDate(tomorrow) else first
   resolved ?: return PhoneRoute.Unsupported("Esa hora no existe o se repite por un cambio de horario. Indicá otra hora.")
  } else null
  val duration = if (current != null) {
   val remaining = deadline?.minus(current.timeInMillis)
   if (remaining == null || remaining !in 1..86_400_000) return PhoneRoute.Unsupported(
    "Indicá una hora futura dentro de las próximas 24 horas. Por ejemplo: silenciar teléfono hasta las 11 am o hasta las 23:00."
   )
   ((remaining + 999) / 1000).toInt()
  } else {
   CommandDuration.seconds(timing)?.takeIf { it in 1..86400 }
    ?: return PhoneRoute.Clarify("Indicá una duración entre un segundo y veinticuatro horas.", "temporary_mode")
  }
  val percent = if (volume != null) PhoneCommandParser.number(volume.groupValues[1]) else 0
  if (clockDeadline && (percent == null || percent !in 0..100))
   return PhoneRoute.Unsupported("Indicá el volumen y la hora juntos. Por ejemplo: poné el volumen al 20% hasta las 23:00.")
  if (percent == null || percent !in 0..100) return PhoneRoute.Clarify("Indicá el volumen entre cero y cien por ciento.", "volume")
  return local(PhoneCommand.TemporaryMode(kind, duration, percent, deadlineMillis = deadline))
 }

}
