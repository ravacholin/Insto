package com.example.phone

/** A duration only: labels and additional scheduling clauses are not accepted here. */
internal object CommandDuration {
  fun seconds(text: String): Int? {
    var total = 0L
    var found = false
    var remaining = text
    val chunks = Regex("([a-z0-9]+(?: y [a-z]+)?)\\s+(horas?|minutos?|segundos?)")
    for (match in chunks.findAll(text)) {
      val amount = PhoneCommandParser.number(match.groupValues[1]) ?: return null
      total += amount.toLong() * when {
        match.groupValues[2].startsWith("hora") -> 3600
        match.groupValues[2].startsWith("minuto") -> 60
        else -> 1
      }
      found = true
      remaining = remaining.replace(match.value, "")
    }
    return if (found && remaining.replace(Regex("\\by\\b"), "").trim().isEmpty() && total in 1..Int.MAX_VALUE.toLong()) total.toInt() else null
  }
}
