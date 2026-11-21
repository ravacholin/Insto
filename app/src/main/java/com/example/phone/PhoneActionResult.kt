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
  /** The command a chosen contact completes, when it differs from the one executed (a split WhatsApp request). */
  val pendingCommand: PhoneCommand? = null,
  val outcome: PhoneActionOutcome = PhoneActionOutcome.COMPLETED,
  val launchedAppPackage: String? = null,
  /**
   * The action may already have had its effect although [outcome] reports a failure: an automation that touched
   * controls and could not verify the result. The user sees the failure; the execution record treats it as uncertain.
   */
  val effectUnverified: Boolean = false,
) {
  val retainRequest: Boolean get() = outcome == PhoneActionOutcome.NEEDS_INPUT || outcome == PhoneActionOutcome.REJECTED || outcome == PhoneActionOutcome.UNCERTAIN || stopwatchAutomation != null || needsCamera || needsContacts || needsNotifications || contactOptions.isNotEmpty()
}
