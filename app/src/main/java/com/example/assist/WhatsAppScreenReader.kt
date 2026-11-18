package com.example.assist

import java.util.Locale

internal class WhatsAppScreenReader(private val root: UiNode, private val pkg: String) {
  data class Recipient(val headers: List<String>) {
    fun matches(phone: String, name: String?, launchCommitted: Boolean): Boolean =
      AutoSendGuard.recipientMatches(phone, headers, name, openedConversation = launchCommitted)
  }

  data class Composer(val text: String?)
  data class Control(val bounds: UiBounds, val id: String?, val label: String?) {
    val surface: String get() = "$id|$label"
  }

  data class AudioPreview(val labels: List<String>, val hasAudioControls: Boolean)

  private fun nodes(maxDepth: Int = Int.MAX_VALUE): Sequence<UiNode> = sequence {
    suspend fun SequenceScope<UiNode>.walk(node: UiNode, depth: Int) {
      if (depth > maxDepth) return
      yield(node)
      for (child in node.children) walk(child, depth + 1)
    }
    walk(root, 0)
  }

  private fun byId(id: String) = nodes().filter { it.id == id }
  private fun nodeLabel(node: UiNode) = node.text?.takeIf(String::isNotBlank) ?: node.contentDescription
  private fun UiNode.control(label: String? = nodeLabel(this)) = Control(bounds, id, label)

  fun recipient(): Recipient = Recipient(
    listOf("conversation_contact_name", "recipient_name", "toolbar_title", "action_bar_title").flatMap { id ->
      byId("$pkg:id/$id").filter { it.visible }.mapNotNull { it.text?.trim() }.toList()
    },
  )

  fun composer(expected: String): Composer? {
    fun choose(candidates: List<UiNode>) = candidates.firstOrNull { it.text == expected } ?: candidates.singleOrNull()
    for (id in listOf("entry", "message_input")) {
      val chosen = choose(byId("$pkg:id/$id").filter { it.visible && it.enabled }.toList())
      if (chosen != null) return Composer(chosen.text)
    }
    return choose(nodes(28).take(800).filter { it.editable && it.visible && it.enabled }.toList())
      ?.let { Composer(it.text) }
  }

  fun entryText(): String? = byId("$pkg:id/entry").firstOrNull { it.visible }?.text

  fun sendControl(): Control? {
    for (id in listOf("send", "send_button", "send_btn", "media_caption_send_btn")) {
      byId("$pkg:id/$id").firstOrNull { it.visible && it.enabled }?.let { return it.control() }
    }
    for (id in listOf("send", "send_button", "media_caption_send_btn")) {
      byId("$pkg:id/$id").firstOrNull { it.clickable && it.visible }?.let { return it.control() }
    }
    val labels = listOf("Send", "Enviar", "Invia", "Envoyer", "Senden")
    for (label in labels) {
      nodes().firstOrNull {
        it.clickable && it.visible &&
          (it.contentDescription.equals(label, ignoreCase = true) || it.text.equals(label, ignoreCase = true))
      }?.let { return it.control() }
    }
    return clickableByKeywords(labels, listOf(":id/send", ":id/send_button"))?.control()
  }

  fun nativeVoiceMic(): Control? {
    for (id in listOf("voice_note_btn", "record_button")) {
      byId("$pkg:id/$id").firstOrNull { it.visible }?.let { return it.control() }
    }
    val labels = listOf("Voice message", "Mensaje de voz", "Nota de voz", "Grabar mensaje de voz", "Record voice message")
    return clickableByKeywords(labels, listOf(":id/voice_note_btn"))?.control()
  }

  fun isEmptyChat(phone: String, name: String?, requireMic: Boolean): Boolean =
    recipient().matches(phone, name, launchCommitted = true) &&
      composer("")?.let { it.text.isNullOrEmpty() } == true &&
      sendControl() == null && (!requireMic || nativeVoiceMic() != null)

  fun audioPreview(): AudioPreview {
    val labels = mutableListOf<String>()
    var hasControls = false
    for (node in nodes(28).take(800).filter { it.visible }) {
      node.text?.take(512)?.let(labels::add)
      node.contentDescription?.take(512)?.let(labels::add)
      val id = node.id.orEmpty().lowercase(Locale.ROOT)
      val label = (node.contentDescription ?: node.text).orEmpty().lowercase(Locale.ROOT).trim()
      if (listOf("audio", "waveform", "seekbar", "play_button", "play_btn").any { id.contains(it) } ||
        (node.clickable || node.className?.endsWith("Button") == true) &&
        label in listOf("reproducir", "play", "pausar", "pause", "reproducir audio", "play audio")
      ) hasControls = true
    }
    return AudioPreview(labels, hasControls)
  }

  fun attachmentLabels(fileName: String?): List<String> = buildList {
    for (id in listOf("file_name", "filename", "document_name", "audio_title")) {
      addAll(byId("$pkg:id/$id").filter { it.visible }.mapNotNull { it.text })
    }
    if (fileName?.startsWith("instinct_") == true && nodes(28).take(800).any {
        it.visible && (it.text == fileName || it.contentDescription == fileName)
      }) add(fileName)
  }

  fun sharePositiveControl(): Control? {
    val controls = nodes(28).take(800).filter {
      it.visible && it.enabled && (it.clickable || it.className?.endsWith("Button") == true)
    }.map { it.control() }.filter { ShareConfirmationPolicy.isPositiveLabel(it.label) }.toList()
    val labelled = controls.firstOrNull { ShareConfirmationPolicy.isConfirmationLabel(it.label) } ?: controls.firstOrNull()
    if (labelled != null) return labelled
    val ids = listOf("button1", "confirm", "confirm_button", "ok", "ok_button", "positive_button",
      "dialog_positive_button", "send", "send_button", "send_btn", "media_caption_send_btn")
    for (id in listOf("android:id/button1") + ids.map { "$pkg:id/$it" }) {
      val node = byId(id).firstOrNull { it.visible && it.enabled } ?: continue
      val label = node.text?.takeIf(String::isNotBlank) ?: node.contentDescription?.takeIf(String::isNotBlank)
        ?: if (id.contains("send")) "Enviar" else null
      return node.control(label)
    }
    return null
  }

  private fun clickableByKeywords(keywords: List<String>, idSuffixes: List<String>): UiNode? =
    nodes().firstOrNull { node ->
      node.clickable && node.visible && (
        keywords.any { node.contentDescription.orEmpty().contains(it, ignoreCase = true) } ||
          idSuffixes.any { node.id.orEmpty().endsWith(it) }
        )
    }
}
