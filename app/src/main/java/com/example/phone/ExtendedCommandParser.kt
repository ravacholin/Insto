package com.example.phone

import java.util.Calendar

internal object ExtendedCommandParser {
 fun parse(s: String, now: () -> Calendar): PhoneRoute? {
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
  val timed = Regex("^(.+?) (durante|por(?! ciento\\b)|hasta)(?: (.+?))?(?: y (?:(?:despues|luego) )?restaura(?:lo)?)?$").matchEntire(s)
    ?: return null
  val mode = timed.groupValues[1]
  val volume = Regex("^(?:pone|fija|ajusta|subi|baja) (?:el )?volumen(?: multimedia)? (?:al? )?(.+?)(?: por ciento|%)?$").matchEntire(mode)
  val fixed = (LocalIntentRegistry.default.matchPhrase(mode) as? PhoneRoute.Local)?.command as? PhoneCommand.Platform
  val kind = when {
   volume != null -> ModeKind.MEDIA_VOLUME
   fixed?.action == PlatformAction.SILENT_ON -> ModeKind.SILENT
   fixed?.action == PlatformAction.DND_ON -> ModeKind.DND
   fixed?.action == PlatformAction.VIBRATE_ON -> return PhoneRoute.Unsupported("El modo vibración temporal no está disponible.")
   else -> return null
  }
  val timing = timed.groupValues[3]
  val clockDeadline = timed.groupValues[2] == "hasta"
  val current = if (clockDeadline) now() else null
  val deadline = current?.let { deadlineMillis(timing, it) }
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

 private fun deadlineMillis(text: String, now: Calendar): Long? {
  val time = Regex(
   "^(?:(hoy|manana) (?:a )?)?(?:las? )?([a-z0-9]+)(?::([0-9]{2})| y (media|cuarto))?" +
    "(?: ?(a ?m|p ?m|de la manana|de la tarde|de la noche|horas))?(?: (hoy|manana))?$"
  ).matchEntire(text) ?: return null
  val (prefixDay, hourText, numericMinute, spokenMinute, period, suffixDay) = time.destructured
  if (prefixDay.isNotEmpty() && suffixDay.isNotEmpty()) return null
  val day = prefixDay.ifEmpty { suffixDay }
  var hour = PhoneCommandParser.number(hourText) ?: return null
  val minute = when (spokenMinute) {
   "media" -> 30
   "cuarto" -> 15
   else -> numericMinute.toIntOrNull() ?: 0
  }
  if (hour !in 0..23 || minute !in 0..59) return null
  if (period.isNotEmpty() && period != "horas") {
   if (hour !in 1..12) return null
   hour = when (period) {
    "am", "a m", "de la manana" -> hour % 12
    "de la noche" -> if (hour == 12) 0 else hour + 12
    else -> hour % 12 + 12
   }
  } else if (period.isEmpty() && numericMinute.isEmpty() && hour in 1..12) return null

  fun onDay(offset: Int): Long? {
   val target = (now.clone() as Calendar).apply {
    add(Calendar.DAY_OF_MONTH, offset)
    isLenient = false
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
   }
   val instant = runCatching { target.timeInMillis }.getOrNull() ?: return null
   val zone = target.timeZone
   val currentOffset = zone.getOffset(instant)
   // Reject a wall-clock time that occurs twice when clocks move backward.
   for (nearby in listOf(instant - 86_400_000, instant + 86_400_000)) {
    val difference = currentOffset - zone.getOffset(nearby)
    if (difference != 0 && zone.getOffset(instant + difference) == currentOffset - difference) return null
   }
   return instant
  }
  val first = onDay(if (day == "manana") 1 else 0) ?: return null
  return if (day.isEmpty() && first <= now.timeInMillis) onDay(1) else first
 }
}
