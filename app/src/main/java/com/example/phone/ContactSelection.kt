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
