package com.example.phone

enum class PhoneActionOutcome { COMPLETED, HANDOFF, NEEDS_INPUT, REJECTED }
data class AppOption(val label: String, val packageName: String)
data class PhoneActionResult(
  val message: String,
  val confirmation: PhoneCommand? = null,
  val appOptions: List<AppOption> = emptyList(),
  val needsCamera: Boolean = false,
  val needsContacts: Boolean = false,
  val contactOptions: List<ContactOption> = emptyList(),
  val outcome: PhoneActionOutcome = PhoneActionOutcome.COMPLETED,
) {
  val retainRequest: Boolean get() = outcome == PhoneActionOutcome.REJECTED || needsCamera || needsContacts || contactOptions.isNotEmpty()
}
