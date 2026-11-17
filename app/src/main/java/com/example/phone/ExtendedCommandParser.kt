package com.example.phone

internal object ExtendedCommandParser {
 fun parse(s: String): PhoneRoute? {
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
  val timed = Regex("^(.+?) (?:durante|por(?! ciento\\b)) (.+?)(?: y (?:(?:despues|luego) )?restaura(?:lo)?)?$").matchEntire(s)
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
  val duration = CommandDuration.seconds(timed.groupValues[2])
  if (duration == null || duration > 86400) return PhoneRoute.Clarify("Indicá una duración entre un segundo y veinticuatro horas.", "temporary_mode")
  val percent = if (volume != null) PhoneCommandParser.number(volume.groupValues[1]) else 0
  if (percent == null || percent !in 0..100) return PhoneRoute.Clarify("Indicá el volumen entre cero y cien por ciento.", "volume")
  return local(PhoneCommand.TemporaryMode(kind, duration, percent))
 }
}
