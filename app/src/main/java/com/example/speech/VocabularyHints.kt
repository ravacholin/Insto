package com.example.speech

import com.example.phone.PhoneCommandParser

object VocabularyHints {
  private const val MAX_LENGTH = 600
  private const val INTRO = "Podés escuchar pedidos con estas palabras y nombres. "
  private val fixed = listOf(
    "Instinct", "WhatsApp", "Spotify", "YouTube", "linterna", "cronómetro", "temporizador",
    "alarma", "No molestar",
  )
  private val unsafeWhitespace = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\s]+")
  private val digits = Regex("\\p{Nd}")

  fun build(
    routines: List<String> = emptyList(),
    places: List<String> = emptyList(),
    learnedPhrases: List<String> = emptyList(),
    apps: List<String> = emptyList(),
    contacts: List<String> = emptyList(),
    includeContacts: Boolean = false,
    fixedTerms: List<String> = fixed,
  ): String? {
    val terms = sequenceOf(fixedTerms, routines, places, learnedPhrases, apps,
      if (includeContacts) contacts else emptyList()).flatten()
    val seen = mutableSetOf<String>()
    val prompt = StringBuilder(INTRO)
    for (raw in terms) {
      val term = unsafeWhitespace.replace(raw, " ").trim()
      if (digits.findAll(term).take(5).count() == 5) continue
      val normalized = PhoneCommandParser.normalize(term)
      if (normalized.isBlank() || !seen.add(normalized)) continue
      val separator = if (prompt.length == INTRO.length) "" else ", "
      if (term.length > MAX_LENGTH - prompt.length - separator.length) break
      prompt.append(separator).append(term)
    }
    return prompt.takeIf { it.length > INTRO.length }?.toString()
  }
}
