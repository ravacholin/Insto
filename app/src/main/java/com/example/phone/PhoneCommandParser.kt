package com.example.phone

import java.text.Normalizer
import java.util.Locale

/** Deliberately anchored grammar: mentioning a capability is not an instruction to use it. */
class PhoneCommandParser {
  fun parseReply(context: String, text: String): PhoneRoute {
    val direct = parse(text)
    if (direct != PhoneRoute.Instinct) return direct
    return when (context) {
      "timer" -> parse("temporizador de $text")
      "alarm" -> parse("alarma $text")
      "volume" -> parse("pone volumen al $text")
      else -> direct
    }
  }

  fun parse(text: String): PhoneRoute {
    val s = normalize(text).removePrefix("por favor ").removeSuffix(" por favor").trim()
    if (s in listOf("cancelar", "cancela", "olvidalo", "nada", "dejalo")) return PhoneRoute.Cancel
    if (s.isBlank() || Regex("^(no |nunca |si |que |como |por que |explica|conta|instinct)").containsMatchIn(s)) return PhoneRoute.Instinct
    val commandPrefix = "(?:(?:me pones|poneme|ponme|pone|pon|poner|quiero|necesito|configura|crea|crear|programa|programar|inicia|iniciar|arranca|activa|establece)\\s+)?(?:(?:un|una|el|la)\\s+)?"
    val timer = Regex("^${commandPrefix}(?:timer|temporizador|cuenta regresiva)\\b(.*)$").matchEntire(s)
    if (timer != null) {
      val body = timer.groupValues[1].trim().removePrefix("de ")
      val duration = body.substringBefore(" para ").substringBefore(" llamado ")
      var seconds = 0L
      var found = false
      var remaining = duration
      val chunks = Regex("([a-z0-9]+(?: y [a-z]+)?)\\s+(horas?|minutos?|segundos?)")
      for (m in chunks.findAll(duration)) {
        val amount = number(m.groupValues[1]) ?: return PhoneRoute.Clarify("¿Cuánto tiempo? Por ejemplo, diez minutos.", "timer")
        found = true
        seconds += amount.toLong() * when { m.groupValues[2].startsWith("hora") -> 3600; m.groupValues[2].startsWith("minuto") -> 60; else -> 1 }
        remaining = remaining.replace(m.value, "")
      }
      if (!found || remaining.replace(Regex("\\by\\b"), "").trim().isNotEmpty() || seconds !in 1..Int.MAX_VALUE.toLong()) {
        return PhoneRoute.Clarify("¿Cuánto tiempo? Por ejemplo, diez minutos.", "timer")
      }
      val label = when { " para " in body -> body.substringAfter(" para "); " llamado " in body -> body.substringAfter(" llamado "); else -> "" }
      return PhoneRoute.Local(PhoneCommand.Timer(seconds.toInt(), label))
    }
    val alarm = Regex("^(?:${commandPrefix}alarma\\b|despertame\\b)(.*)$").matchEntire(s)
    if (alarm != null) return alarm(alarm.groupValues[1])
    if (Regex("^(?:mostra|ver|abrir|abri|muestra) (?:las |los |mis )?(alarmas|timers|temporizadores)$").matches(s)) {
      return PhoneRoute.Local(PhoneCommand.ClockList(s.endsWith("alarmas")))
    }
    val chrono = Regex("^(?:(inicia|iniciar|arranca|arrancar|pone|pon|pausa|pausar|para|parar|detene|continua|continuar|reanuda|reinicia|reiniciar|resetea|ver|mostra|consulta) (?:el |un )?)?cronometro$").matchEntire(s)
    if (chrono != null) return PhoneRoute.Local(PhoneCommand.Stopwatch(when (chrono.groupValues[1]) {
      "pausa", "pausar", "para", "parar", "detene" -> StopwatchOp.PAUSE
      "continua", "continuar", "reanuda" -> StopwatchOp.RESUME
      "reinicia", "reiniciar", "resetea" -> StopwatchOp.RESET
      "ver", "mostra", "consulta", "" -> StopwatchOp.STATUS
      else -> StopwatchOp.START
    }))
    val torch = Regex("^(prende|prender|encende|encender|activa|activar|apaga|apagar|desactiva) (?:la )?linterna$").matchEntire(s)
    if (torch != null) return PhoneRoute.Local(PhoneCommand.Torch(torch.groupValues[1] !in listOf("apaga", "apagar", "desactiva")))
    if (Regex("^(subi|subir|sube|aumenta) (?:el )?volumen$").matches(s)) return PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.UP))
    if (Regex("^(baja|bajar) (?:el )?volumen$").matches(s)) return PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.DOWN))
    val volume = Regex("^(?:pone|pon|fija|ajusta) (?:el )?volumen (?:al? )?(.+?)(?: por ciento|%)?$").matchEntire(s)
    if (volume != null) {
      val n = number(volume.groupValues[1])
      return if (n != null && n in 0..100) PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, n))
        else PhoneRoute.Clarify("Indicá un volumen entre cero y cien por ciento.", "volume")
    }
    val setting = Regex("^(?:abri|abrir|activa|activar|desactiva|apaga|encende|prende|configura) (?:el |los |la )?(?:ajustes de )?(wifi|wi fi|bluetooth|conectividad|internet|ajustes)$").matchEntire(s)
    if (setting != null) return PhoneRoute.Local(PhoneCommand.Settings(setting.groupValues[1].replace("wi fi", "wifi")))
    val app = Regex("^(?:abri|abrir|abre) (?:la (?:app|aplicacion) )?(.+)$").matchEntire(s)
    if (app != null) return PhoneRoute.Local(PhoneCommand.OpenApp(app.groupValues[1]))
    // Recognized requests outside our executor must never silently become a message to Instinct.
    if (Regex("^(?:cancela|cancelar|elimina|borra|pausa|pausar|reinicia|desactiva|cambia|modifica|apaga|pone|pon|activa|prende|enciende|inicia|crea|programa)\\b.*\\b(?:alarmas?|timers?|temporizador(?:es)?|cronometro|linterna|volumen|wifi|bluetooth)\\b").containsMatchIn(s)) {
      return PhoneRoute.Unsupported("Ese pedido del teléfono todavía no está disponible. Podés abrir el Reloj o los ajustes para completarlo.")
    }
    return PhoneRoute.Instinct
  }

  private fun alarm(input: String): PhoneRoute {
    val body = input.trim()
    val datePart = body.replace("de la manana", "")
    if (Regex("\\b(?:manana|pasado|hoy|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\b|[0-9]+[/-][0-9]+|\\bel [0-9]+").containsMatchIn(datePart)) {
      return PhoneRoute.Unsupported("Para una fecha específica, abrí las alarmas del Reloj y completá la fecha allí.")
    }
    if (Regex("\\b(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b").containsMatchIn(body) &&
      !body.contains("todos los") && !body.contains("cada ")) {
      return PhoneRoute.Unsupported("Para una fecha específica, abrí las alarmas del Reloj y completá la fecha allí.")
    }
    val days = if ("todos los dias" in body || "cada dia" in body) (1..7).toList() else {
      if ("todos los" in body || "cada " in body) listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado").mapIndexedNotNull { i, d -> if (d in body) i + 1 else null } else emptyList()
    }
    val time = Regex("(?:a las? |^)([a-z0-9]+)(?::([0-9]{1,2})| y (media|cuarto|[a-z]+(?: y [a-z]+)?))?(?: (de la manana|de la tarde|de la noche|am|pm|horas))?(?=$| para | todos | cada | llamado )").find(body)
      ?: return PhoneRoute.Clarify("¿A qué hora? Por ejemplo, siete y media de la mañana.", "alarm")
    var hour = number(time.groupValues[1]) ?: return PhoneRoute.Clarify("¿A qué hora?", "alarm")
    val minutes = when (val m = time.groupValues[3]) { "media" -> 30; "cuarto" -> 15; "" -> time.groupValues[2].toIntOrNull() ?: 0; else -> number(m) ?: -1 }
    val period = time.groupValues[4]
    if (hour !in 0..23 || minutes !in 0..59 || (period.isNotEmpty() && period != "horas" && hour !in 1..12)) return PhoneRoute.Clarify("Indicá una hora válida, por ejemplo 07:30.", "alarm")
    if (period.isEmpty() && hour in 1..12 && !Regex("\\b0[0-9]:").containsMatchIn(body)) return PhoneRoute.Clarify("¿De la mañana o de la tarde? Repetí la hora completa.", "alarm")
    if (period in listOf("de la tarde", "de la noche", "pm")) hour = hour % 12 + 12
    if (period in listOf("de la manana", "am")) hour %= 12
    val label = if (" llamado " in body) body.substringAfter(" llamado ") else ""
    return PhoneRoute.Local(PhoneCommand.Alarm(hour, minutes, days, label))
  }

  companion object {
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
      .replace(Regex("\\p{M}+"), "").replace(Regex("[¿?¡!,.;]"), " ").replace(Regex("\\s+"), " ").trim()
    private val words = listOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho", "veintinueve")
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
