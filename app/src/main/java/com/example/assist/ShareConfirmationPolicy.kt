package com.example.assist

import java.text.Normalizer
import java.util.Locale
import com.example.whatsapp.PhoneNumberUtils

/** UI evidence for the audio share we launched, including WhatsApp's OK/Accept variant. */
object ShareConfirmationPolicy {
  private val confirmLabels = setOf("ok", "aceptar", "accept", "confirmar", "confirm", "listo", "done", "hecho")
  private val sendLabels = setOf("enviar", "send", "invia", "envoyer", "senden")
  fun isConfirmationLabel(label: String?) = clean(label.orEmpty()) in confirmLabels
  fun isPositiveLabel(label: String?) = clean(label.orEmpty()) in confirmLabels + sendLabels

  fun canConfirm(request: Long, launchRequest: Long, freshWindow: Boolean, fileName: String?,
    caption: String?, composer: String?, expectedName: String?, expectedPhone: String,
    labels: List<String>, positiveLabel: String?, hasAudioControls: Boolean, recipientPreviouslyVerified: Boolean = false): Boolean {
    if (request != launchRequest || !freshWindow || !isPositiveLabel(positiveLabel) ||
      fileName?.startsWith("instinct_") != true || !fileName.endsWith(".m4a") ||
      caption != null || !composer.isNullOrBlank()) return false
    val body = labels.map(::clean).filter { it.isNotBlank() && !isPositiveLabel(it) }
    val pickerHeading = Regex("^(?:enviar a|send to|compartir (?:con|a)|share (?:with|to))[ :?.…]*$")
    if (body.any { pickerHeading.matches(it) }) return false
    val ownedFile = clean(fileName.orEmpty())
    val ownedStem = ownedFile.substringBeforeLast('.')
    val negative = Regex("\\b(?:error|failed|cannot|permission|permiso|acceso|bloqueado)\\b|no se pudo|no se puede|no puedes|no es compatible|no son compatibles|archivo no valido|unsupported|unable|could not|can't|couldn't")
    if (body.any { negative.containsMatchIn(it) }) return false
    val name = clean(expectedName.orEmpty())
    val phone = PhoneNumberUtils.normalizeDigits(expectedPhone)
    // Keep PR #5's exact recipient check for WhatsApp's native Share with …? dialog.
    val nativeShareQuestion = Regex("^(?:share with|compartir con) (.+)$")
    val nativeRecipients = body.mapNotNull { nativeShareQuestion.matchEntire(it.removePrefix("¿").removeSuffix("?"))?.groupValues?.get(1) }
    if (nativeRecipients.any { !AutoSendGuard.recipientMatches(expectedPhone, listOf(it), name, openedConversation = true) }) return false
    val namePattern = name.takeIf { it.any(Char::isLetter) }?.let {
      Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])")
    }
    fun hasRecipient(label: String): Boolean {
      val recipientText = label.replace(ownedFile, "").replace(ownedStem, "")
      if (namePattern?.containsMatchIn(recipientText) == true) return true
      return phone.length in 6..15 && Regex("\\+?[0-9][0-9 ()-]{5,}[0-9]").findAll(recipientText)
        .any { PhoneNumberUtils.normalizeDigits(it.value) == phone }
    }
    val purpose = Regex("\\b(?:enviar|envio|send|sending|audio|compartir|share)\\b|mensaje de voz|nota de voz|voice message")
    // A destination list alone cannot authorize sending: recipient and purpose must share a
    // statement, or the surface must be an audio player with an explicit recipient.
    val explicitDestination = Regex("\\b(?:enviar|send|compartir|share)\\b.{0,80}\\b(?:a|to)\\s+\\S")
    if (body.any { explicitDestination.containsMatchIn(it) && !hasRecipient(it) }) return false
    return body.any { hasRecipient(it) && purpose.containsMatchIn(it) } ||
      (hasAudioControls && body.any(::hasRecipient)) ||
      (recipientPreviouslyVerified && body.any { purpose.containsMatchIn(it) })
  }

  private fun clean(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("[\\u200e\\u200f]"), "")
    .replace(Regex("\\s+"), " ").trim()
}
