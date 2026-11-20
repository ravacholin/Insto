package com.example.phone

data class ContactOption(val name: String, val number: String)
sealed interface ContactResolution {
  data class Number(val value: String) : ContactResolution
  data class Choose(val options: List<ContactOption>) : ContactResolution
  data object Missing : ContactResolution
  data object Refine : ContactResolution
}

object ContactSelection {
  fun resolve(query: String, candidates: List<ContactOption>, truncated: Boolean = false): ContactResolution {
    val valid = candidates.filter { PhoneNumbers.canonical(it.number) != null }
    val exact = valid.filter { PhoneCommandParser.normalize(it.name) == PhoneCommandParser.normalize(query) }
      .distinctBy { PhoneNumbers.canonical(it.number) }
    val choices = exact.ifEmpty { valid.distinctBy { PhoneNumbers.canonical(it.number) } }
    return when {
      !truncated && exact.size == 1 -> ContactResolution.Number(checkNotNull(PhoneNumbers.canonical(exact.single().number)))
      choices.isNotEmpty() -> ContactResolution.Choose(choices.take(20))
      truncated -> ContactResolution.Refine
      else -> ContactResolution.Missing
    }
  }
}

/** «Meme Te amo»: the longest leading words that name a contact are the recipient, the rest is the message. */
object WhatsAppRecipientSplit {
  data class Split(val name: String, val body: String, val matches: List<ContactOption>)

  private const val MAX_NAME_WORDS = 5
  private val word = Regex("\\S+")
  private val leadingQue = Regex("^(?i:que)\\s+")

  /** The word the contacts are queried with; Android's filter matches it at the start of any name part. */
  fun firstWord(tail: String): String? = word.find(tail)?.value?.trimEnd(':', ',', ';', '.')?.takeIf { it.isNotEmpty() }

  fun split(tail: String, candidates: List<ContactOption>): Split? {
    val tokens = word.findAll(tail).toList()
    if (tokens.size < 2) return null
    val names = candidates.map { it to words(it.name) }
    for (count in minOf(tokens.size - 1, MAX_NAME_WORDS) downTo 1) {
      val name = tail.substring(tokens.first().range.first, tokens[count - 1].range.last + 1).trimEnd(':', ',', ';', '.', '-')
      val query = words(name)
      if (query.isEmpty()) continue
      val matches = names.filter { (_, parts) -> parts.windowed(query.size).any { it == query } }.map { it.first }
      // «escribile a Juan que llego tarde»: the «que» introduces the dictated text.
      val body = ExtendedLocalIntents.literalBody(tail.substring(tokens[count].range.first).replace(leadingQue, ""))
      if (matches.isNotEmpty() && body.isNotEmpty()) return Split(name, body, matches)
    }
    return null
  }

  /** One number for the name, or the options to choose from. Unlike calls, one partial match is enough. */
  fun resolve(split: Split, truncated: Boolean): ContactResolution {
    val numbers = split.matches.mapNotNull { PhoneNumbers.canonical(it.number) }.distinct()
    if (!truncated && numbers.size == 1) return ContactResolution.Number(numbers.single())
    return ContactSelection.resolve(split.name, split.matches, truncated)
  }

  private fun words(text: String) = PhoneCommandParser.normalize(text).split(' ').filter { it.isNotEmpty() }
}
