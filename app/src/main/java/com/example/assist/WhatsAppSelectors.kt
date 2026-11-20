package com.example.assist

import java.util.Locale

/** A snapshot of one accessibility node: only what the selectors read. No message content leaves the service. */
data class UiNode(
  val id: String? = null,
  val text: String? = null,
  val description: String? = null,
  val className: String? = null,
  val clickable: Boolean = false,
  val editable: Boolean = false,
  val visible: Boolean = true,
  val enabled: Boolean = true,
  val bounds: Box = Box(0, 0, 0, 0),
  /** An empty field that reports its placeholder («Mensaje») as text. */
  val showingHint: Boolean = false,
) {
  data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2
  }

  /** Text first, like the previous inline lookups; the description when there is no text. */
  val label: String? get() = text?.takeIf { it.isNotBlank() } ?: description
  val buttonLike: Boolean get() = clickable || className?.endsWith("Button") == true
}

/**
 * The window being inspected. [byId] is the framework's direct lookup (one call, whole tree); [walk] visits nodes
 * depth-first with the same 800-node and depth-28 limits the service always used.
 */
interface UiTree {
  fun byId(fullId: String): List<UiNode>
  fun walk(): Sequence<UiNode>
}

/**
 * Ordered strategies to find each WhatsApp element. The service touches only what these return, and only after the
 * existing recipient, payload and freshness policies authorized it: selectors find, they never authorize.
 *
 * A strategy that sees more than one distinct candidate answers [Result.Ambiguous] and the search stops there; it never
 * falls through to a weaker strategy. Labels are compared exactly (case-insensitive), never by «contains»: «Enviar
 * ubicación» is not «Enviar».
 */
object WhatsAppSelectors {
  sealed interface Result {
    data class Found(val node: UiNode, val strategy: String) : Result
    data class Ambiguous(val strategy: String) : Result
    data object NotFound : Result
  }

  internal val sendLabels = listOf("Send", "Enviar", "Invia", "Envoyer", "Senden")
  internal val micLabels = listOf("Voice message", "Mensaje de voz", "Nota de voz", "Grabar mensaje de voz", "Record voice message")
  private val composerIds = listOf("entry", "message_input")
  private val sendIds = listOf("send", "send_button", "send_btn", "media_caption_send_btn")
  private val micIds = listOf("voice_note_btn", "record_button")
  private val headerIds = listOf("conversation_contact_name", "recipient_name", "toolbar_title", "action_bar_title")
  private val attachmentIds = listOf("file_name", "filename", "document_name", "audio_title")
  private fun positiveIds(pkg: String) = listOf("android:id/button1", "$pkg:id/button1", "$pkg:id/confirm", "$pkg:id/confirm_button",
    "$pkg:id/ok", "$pkg:id/ok_button", "$pkg:id/positive_button", "$pkg:id/dialog_positive_button") +
    sendIds.map { "$pkg:id/$it" }

  private fun UiNode.usable() = visible && enabled
  private fun distinct(nodes: List<UiNode>) = nodes.distinctBy { it.bounds }

  private fun decide(strategy: String, candidates: List<UiNode>): Result? = when (distinct(candidates).size) {
    0 -> null
    1 -> Result.Found(candidates.first(), strategy)
    else -> Result.Ambiguous(strategy)
  }

  private fun firstOf(vararg strategies: () -> Result?): Result {
    for (strategy in strategies) strategy()?.let { return it }
    return Result.NotFound
  }

  private fun byIds(tree: UiTree, pkg: String, ids: List<String>, prefix: String, accept: (UiNode) -> Boolean): List<() -> Result?> =
    ids.map { id -> { decide("$prefix.id.$id", tree.byId("$pkg:id/$id").filter(accept)) } }

  /** WhatsApp's Send control in the chat, the audio preview or the caption screen. */
  fun sendButton(tree: UiTree, pkg: String): Result = firstOf(
    *byIds(tree, pkg, sendIds, "send") { it.usable() }.toTypedArray(),
    { decide("send.label", tree.walk().filter { it.usable() && it.buttonLike && it.label.exactlyOneOf(sendLabels) }.toList()) },
  )

  /**
   * The message box. Among several boxes, the one already holding [expected] wins; otherwise a single one.
   * Two equal candidates are ambiguous.
   */
  fun composer(tree: UiTree, pkg: String, expected: String): Result {
    fun choose(strategy: String, nodes: List<UiNode>): Result? {
      val usable = nodes.filter { it.usable() }
      val matching = usable.filter { it.text == expected }
      return decide(strategy, matching) ?: decide(strategy, usable)
    }
    return firstOf(
      *composerIds.map { id -> { choose("composer.id.$id", tree.byId("$pkg:id/$id")) } }.toTypedArray(),
      { choose("composer.editable", tree.walk().filter { it.editable }.toList()) },
    )
  }

  /**
   * True when the composer no longer holds [sent]: empty, showing its placeholder, or with other text. WhatsApp
   * empties the field right after a send; many Android versions then report the hint («Mensaje») as its text.
   */
  fun composerCleared(composer: Result, sent: String): Boolean {
    val node = (composer as? Result.Found)?.node ?: return false
    val text = node.text
    return text.isNullOrEmpty() || node.showingHint || text.trim() != sent.trim()
  }

  /** The chat's voice-note microphone. Its description often continues with a hint, so the label must start with it. */
  fun voiceMic(tree: UiTree, pkg: String): Result = firstOf(
    *byIds(tree, pkg, micIds, "mic") { it.visible }.toTypedArray(),
    { decide("mic.label", tree.walk().filter { it.visible && it.clickable && it.description.startsWithOneOf(micLabels) }.toList()) },
  )

  /**
   * The positive button of WhatsApp's share confirmation or audio preview. An explicit confirmation («OK»,
   * «Aceptar») is preferred over «Enviar» when both are on screen, as before. IDs vary between builds, so exact
   * labels go first.
   */
  fun sharePositive(tree: UiTree, pkg: String): Result {
    val positives = tree.walk().filter {
      it.usable() && it.buttonLike && ShareConfirmationPolicy.isPositiveLabel(it.label)
    }.toList()
    val confirmations = positives.filter { ShareConfirmationPolicy.isConfirmationLabel(it.label) }
    decide("share.label.confirm", confirmations)?.let { return it }
    decide("share.label", positives)?.let { return it }
    return firstOf(*positiveIds(pkg).map { id ->
      { decide("share.id.${id.substringAfter(":id/")}", tree.byId(id).filter { it.usable() }) }
    }.toTypedArray())
  }

  /** Visible chat-header texts, compared with the configured recipient by [AutoSendGuard]. */
  fun headers(tree: UiTree, pkg: String): List<String> =
    headerIds.flatMap { id -> tree.byId("$pkg:id/$id").filter { it.visible }.mapNotNull { it.text?.trim() } }

  fun attachmentLabels(tree: UiTree, pkg: String): List<String> =
    attachmentIds.flatMap { id -> tree.byId("$pkg:id/$id").filter { it.visible }.mapNotNull { it.text } }

  private fun String?.exactlyOneOf(labels: List<String>): Boolean {
    val value = this?.trim() ?: return false
    return labels.any { it.equals(value, ignoreCase = true) }
  }

  private fun String?.startsWithOneOf(labels: List<String>): Boolean {
    val value = this?.trim()?.lowercase(Locale.ROOT) ?: return false
    return labels.any { value.startsWith(it.lowercase(Locale.ROOT)) }
  }
}

/** The node a selector found, or null when it was missing or ambiguous. */
val WhatsAppSelectors.Result.node: UiNode? get() = (this as? WhatsAppSelectors.Result.Found)?.node
