package com.example.phone

enum class PhoneActionOutcome { COMPLETED, HANDOFF, NEEDS_INPUT, REJECTED, UNCERTAIN }
data class AppOption(val label: String, val packageName: String)
data class PhoneActionResult(
  val message: String,
  val confirmation: PhoneCommand? = null,
  val appOptions: List<AppOption> = emptyList(),
  val needsCamera: Boolean = false,
  val needsContacts: Boolean = false,
  val needsNotifications: Boolean = false,
  val needsClockAccessibility: Boolean = false,
  val accessSettingsAction: String? = null,
  val stopwatchAutomation: StopwatchOp? = null,
  val contactOptions: List<ContactOption> = emptyList(),
  val outcome: PhoneActionOutcome = PhoneActionOutcome.COMPLETED,
  val launchedAppPackage: String? = null,
) {
  val retainRequest: Boolean get() = outcome == PhoneActionOutcome.NEEDS_INPUT || outcome == PhoneActionOutcome.REJECTED || outcome == PhoneActionOutcome.UNCERTAIN || stopwatchAutomation != null || needsCamera || needsContacts || needsNotifications || contactOptions.isNotEmpty()
}
