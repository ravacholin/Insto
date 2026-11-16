package com.example

import com.example.assist.ShareConfirmationPolicy
import com.example.assist.ShareSendAttempt
import org.junit.Assert.*
import org.junit.Test

class ShareConfirmationPolicyTest {
  private fun canConfirm(labels: List<String> = listOf("¿Enviar este audio a Instinct?", "OK", "Cancelar"),
    positive: String = "OK", fresh: Boolean = true, launch: Long = 8, composer: String? = null,
    file: String = "instinct_12345678.m4a") = ShareConfirmationPolicy.canConfirm(
      8, launch, fresh, file, null, composer, "Instinct", "+5491112345678", labels, positive, false)

  @Test fun okAndAcceptArePartOfTheCurrentTargetedAudioSend() {
    assertTrue(canConfirm())
    assertTrue(canConfirm(listOf("Enviar audio a Instinct", "Aceptar"), "Aceptar"))
    assertTrue(canConfirm(listOf("Send audio to Instinct", "OK")))
    assertTrue(canConfirm(listOf("Enviar audio a Instinct", "Listo"), "Listo"))
  }
  @Test fun rejectsAnotherRecipientOrUnrelatedOkDialogs() {
    assertFalse(canConfirm(listOf("Enviar audio a otra persona", "OK")))
    assertFalse(canConfirm(listOf("Instinct", "Permitir acceso al micrófono", "OK")))
    assertFalse(canConfirm(listOf("No se pudo enviar el audio a Instinct", "OK")))
    assertFalse(canConfirm(listOf("Instinct", "OK")))
    assertFalse(canConfirm(positive = "Cancelar"))
  }
  @Test fun doesNotConfirmOldWindowsOrADifferentRequestOrDraft() {
    assertFalse(canConfirm(fresh = false))
    assertFalse(canConfirm(launch = 7))
    assertFalse(canConfirm(composer = "un borrador anterior"))
    assertFalse(canConfirm(file = "report.pdf"))
  }
  @Test fun namesMustNotMatchAPrefixOfAnotherContact() {
    assertFalse(canConfirm(listOf("Enviar audio a InstinctDos", "OK")))
    assertTrue(canConfirm(listOf("Enviar audio a +54 9 11 1234-5678", "OK")))
  }
  @Test fun confirmThenSendAllowsEachStepOnce() {
    val attempt = ShareSendAttempt(8)
    assertTrue(attempt.beginConfirmation(8, 42, true))
    assertFalse(attempt.beginConfirmation(8, 42, true))
    assertFalse(attempt.beginSend(8, true, true, true))
    attempt.confirmationCompleted(8)
    assertFalse(attempt.isPostConfirmationWindow(42))
    assertTrue(attempt.isPostConfirmationWindow(43))
    assertTrue(attempt.beginSend(8, true, true, true))
    assertFalse(attempt.beginSend(8, true, true, true))
  }
  @Test fun destinationPickerDoesNotCountAsAConfirmationToThatContact() {
    assertFalse(canConfirm(listOf("Enviar a", "Instinct", "Otro contacto", "Enviar"), "Enviar"))
  }
  @Test fun audioPlayerWithExplicitRecipientDoesNotDependOnActivityOrHeaderIds() {
    assertTrue(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("Instinct", "Reproducir", "Enviar"), "Enviar", true))
    assertFalse(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("Otra persona", "Reproducir", "Enviar"), "Enviar", true))
  }
  @Test fun confirmationAndSendCanShareAWindowButCannotReuseTheOldControl() {
    val attempt = ShareSendAttempt(8)
    assertTrue(attempt.beginConfirmation(8, 42, true, "android:id/button1|OK"))
    attempt.confirmationCompleted(8)
    assertFalse(attempt.isPostConfirmationSurface(42, "android:id/button1|OK"))
    assertTrue(attempt.isPostConfirmationSurface(42, "com.whatsapp:id/send|Enviar"))
  }
  @Test fun sendThenOkRemainsOneOperationWithoutRepeatingEitherStep() {
    val attempt = ShareSendAttempt(8)
    assertTrue(attempt.beginSend(8, true, true, true, 42, "send"))
    assertFalse(attempt.beginConfirmation(8, 43, true))
    attempt.sendCompleted(8)
    assertTrue(attempt.isPostSendSurface(43, "ok"))
    assertTrue(attempt.beginConfirmation(8, 43, true))
    assertFalse(attempt.beginConfirmation(8, 43, true))
    attempt.confirmationCompleted(8)
    assertFalse(attempt.beginSend(8, true, true, true))
  }
  @Test fun confirmationAfterSendCanInheritOnlyTheVerifiedRecipient() {
    assertTrue(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("¿Confirmar el envío del audio?", "OK"), "OK", false, true))
    assertFalse(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("Enviar audio a Otra Persona", "OK"), "OK", false, true))
  }
  @Test fun inheritedRecipientDoesNotAuthorizeADestinationPicker() {
    assertFalse(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("Enviar a", "Instinct", "Otro contacto", "Enviar"), "Enviar", false, true))
  }
  @Test fun recordingFilenameCannotStandInForTheRecipientName() {
    assertFalse(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("Otra persona", "Audio", "instinct_12345678.m4a", "Enviar"), "Enviar", true))
  }
  @Test fun submissionRequiresASettledChatRatherThanADisappearingSendButton() {
    val attempt = ShareSendAttempt(8)
    attempt.beginSend(8, true, true, true, 42, "send")
    attempt.sendCompleted(8)
    assertFalse(attempt.observeSubmittedChat(43, 1000, true))
    assertFalse(attempt.observeSubmittedChat(43, 1200, true))
    assertFalse(attempt.observeSubmittedChat(44, 1300, false)) // OK preview interrupts the apparent return.
    assertFalse(attempt.observeSubmittedChat(43, 1500, true))
    assertTrue(attempt.observeSubmittedChat(43, 2000, true))
  }
  @Test fun preservesTheExactShareQuestionSupportedByPrFive() {
    assertTrue(canConfirm(listOf("Share with Instinct?", "OK")))
    assertTrue(canConfirm(listOf("¿Compartir con Instinct?", "Aceptar"), "Aceptar"))
    assertFalse(canConfirm(listOf("Share with Another Instinct?", "OK")))
    assertFalse(ShareConfirmationPolicy.canConfirm(8, 8, true, "instinct_12345678.m4a", null, null,
      "Instinct", "+5491112345678", listOf("Share with Otra Persona?", "OK"), "OK", false, true))
  }
  @Test fun sendOnlyKeepsTheExistingOneTouchBehavior() {
    val attempt = ShareSendAttempt(8)
    assertFalse(attempt.beginSend(7, true, true, true))
    assertFalse(attempt.beginSend(8, false, true, true))
    assertTrue(attempt.beginSend(8, true, true, true))
    assertFalse(attempt.beginSend(8, true, true, true))
  }
}
