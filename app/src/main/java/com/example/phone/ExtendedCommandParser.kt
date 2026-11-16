package com.example.phone

internal object ExtendedCommandParser {
 fun parse(s: String): PhoneRoute? {
  fun local(c: PhoneCommand) = PhoneRoute.Local(c)
  if (s in listOf("termina el modo", "termina el modo y restaura ahora", "restaura ahora", "restaura el volumen", "desactiva el modo temporal")) return local(PhoneCommand.EndMode)
  val media = when {
   Regex("^(?:pausa|pausar|para|detene) (?:la )?(?:musica|reproduccion)$").matches(s) -> MediaOp.PAUSE
   Regex("^(?:continua|reanuda|reproduci|reproduce|inicia) (?:la )?(?:musica|reproduccion)$").matches(s) -> MediaOp.PLAY
   Regex("^(?:(?:pasa|pasar|salta) (?:al |a la )?)?(?:siguiente|proximo) (?:tema|cancion)$").matches(s) -> MediaOp.NEXT
   Regex("^(?:(?:volve|pasa) (?:al |a la )?)?(?:anterior) (?:tema|cancion)$").matches(s) -> MediaOp.PREVIOUS
   s in listOf("que esta sonando", "mostra la musica", "consulta la reproduccion") -> MediaOp.STATUS
   else -> null
  }
  if (media != null) return local(PhoneCommand.Media(media))
  val mode = Regex("^(silencia|silenciar|pone no molestar|activa no molestar|pone el volumen al (.+?)(?: por ciento|%)?) durante (.+?)(?: y despues restaura(?:lo)?| y restaura(?:lo)?)?$").matchEntire(s.removePrefix("silencia el telefono ").let { if (it != s) "silencia $it" else s })
  if (mode != null) {
   val duration = CommandDuration.seconds(mode.groupValues[3])
   if (duration == null || duration > 86400) return PhoneRoute.Clarify("Indicá una duración entre un segundo y veinticuatro horas.", "temporary_mode")
   val kind = when { mode.groupValues[1].contains("volumen") -> ModeKind.MEDIA_VOLUME; mode.groupValues[1].contains("no molestar") -> ModeKind.DND; else -> ModeKind.SILENT }
   val percent = if (kind == ModeKind.MEDIA_VOLUME) PhoneCommandParser.number(mode.groupValues[2]) else 0
   if (percent == null || percent !in 0..100) return PhoneRoute.Clarify("Indicá el volumen entre cero y cien por ciento.", "volume")
   return local(PhoneCommand.TemporaryMode(kind, duration, percent))
  }
  if (s.startsWith("silencia") || s.contains("no molestar") || s.startsWith("pone el volumen") && s.contains("durante")) return PhoneRoute.Clarify("Indicá el modo y su duración: silenciá el teléfono durante una hora.", "temporary_mode")
  return null
 }
}
