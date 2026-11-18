package com.example.ui

import android.app.Application
import com.example.assist.PingAutoSendAccessibilityService
import com.example.phone.AppOption
import com.example.phone.PhoneActionExecutor
import com.example.phone.PhoneActionOutcome
import com.example.phone.PhoneActionResult
import com.example.phone.PhoneCommand
import com.example.phone.StopwatchOp

internal class PhoneCommandRunner(application: Application) {
  private val executor = PhoneActionExecutor(application)

  fun matchingApps(name: String): List<AppOption> = executor.matchingApps(name)

  fun runPhoneCommand(command: PhoneCommand, confirmed: Boolean): PhoneActionResult =
    executor.execute(command, confirmed)

  fun startStopwatch(operation: StopwatchOp, onResult: (PhoneActionResult) -> Unit): () -> Unit =
    PingAutoSendAccessibilityService.requestStopwatch(operation, onResult)

  fun applyPhoneActionResult(
    data: FloatingSessionData,
    command: PhoneCommand,
    confirmed: Boolean,
    result: PhoneActionResult,
    inWorkflow: Boolean,
  ): FloatingSessionData {
    val screen = data.ui
    val nextMode = when {
      result.retainRequest && data.audio != null -> FloatingMode.AUDIO_READY
      result.retainRequest && screen.text.isNotBlank() -> FloatingMode.TYPING
      else -> FloatingMode.IDLE
    }
    val rejected = result.outcome == PhoneActionOutcome.REJECTED
    val completion = result.message.takeIf {
      !inWorkflow && (result.outcome == PhoneActionOutcome.COMPLETED || result.outcome == PhoneActionOutcome.HANDOFF)
    }
    val permission = PendingPermission(command, data.id)
    return data.copy(
      confirmation = result.confirmation,
      cameraCommand = if (result.needsCamera) permission else data.cameraCommand,
      contactsCommand = if (result.needsContacts) permission else data.contactsCommand,
      notificationsCommand = if (result.needsNotifications) permission else data.notificationsCommand,
      appChoiceCommand = command.takeIf { result.appOptions.isNotEmpty() },
      retainLocalAudio = result.retainRequest,
      retryLocalCommand = if (result.retainRequest) command to confirmed else null,
      retryLocalText = if (result.retainRequest) data.routedRequestText else null,
      ui = screen.copy(
        mode = nextMode,
        text = if (result.retainRequest) screen.text else "",
        error = result.message.takeIf { rejected },
        status = result.message.takeUnless { rejected },
        needsWhatsAppAccessibility = false,
        needsConfirmation = result.confirmation != null,
        needsClockAccessibility = result.needsClockAccessibility,
        accessSettingsAction = result.accessSettingsAction,
        appOptions = result.appOptions,
        needsCamera = result.needsCamera,
        needsGroqKey = false,
        needsContacts = result.needsContacts,
        needsNotifications = result.needsNotifications,
        contactOptions = result.contactOptions,
        phoneActionCompletion = completion,
      ),
    )
  }
}
