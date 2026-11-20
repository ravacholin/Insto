package com.example.assist

/**
 * Synthetic accessibility trees of WhatsApp and WhatsApp Business. No real data: the contact is «Contacto Prueba»,
 * numbers are in Ofcom's drama range (+44 7700 900xxx) and messages are invented.
 */
object WhatsAppSelectorFixtures {
  class Node(val node: UiNode, val children: List<Node> = emptyList())

  /** In-memory [UiTree] with the same traversal limits as the accessibility adapter. */
  class Tree(private val root: Node) : UiTree {
    private fun all(): Sequence<UiNode> = sequence {
      var visited = 0
      suspend fun SequenceScope<UiNode>.visit(n: Node, depth: Int) {
        if (visited++ >= 800 || depth > 28) return
        yield(n.node)
        for (child in n.children) visit(child, depth + 1)
      }
      visit(root, 0)
    }
    override fun byId(fullId: String): List<UiNode> = all().filter { it.id == fullId }.toList()
    override fun walk(): Sequence<UiNode> = all()
  }

  private fun n(node: UiNode, vararg children: Node) = Node(node, children.toList())
  private fun box(l: Int, t: Int, r: Int, b: Int) = UiNode.Box(l, t, r, b)
  private fun button(pkg: String?, id: String?, text: String? = null, desc: String? = null, bounds: UiNode.Box) =
    n(UiNode(id = id?.let { "$pkg:id/$it" }, text = text, description = desc, className = "android.widget.ImageButton",
      clickable = true, bounds = bounds))
  private fun label(text: String, id: String? = null, pkg: String? = null, bounds: UiNode.Box = box(0, 0, 10, 10)) =
    n(UiNode(id = id?.let { "$pkg:id/$it" }, text = text, className = "android.widget.TextView", bounds = bounds))
  private fun field(pkg: String?, id: String?, text: String?, bounds: UiNode.Box) =
    n(UiNode(id = id?.let { "$pkg:id/$it" }, text = text, className = "android.widget.EditText", editable = true,
      clickable = true, bounds = bounds))

  const val WA = "com.whatsapp"
  const val WAB = "com.whatsapp.w4b"
  const val DRAFT = "Hola, esto es una prueba"

  data class Fixture(
    val name: String,
    val pkg: String,
    val tree: Tree,
    val send: Expectation,
    val composer: Expectation,
    val mic: Expectation = Expectation.None,
    val share: Expectation = Expectation.None,
    val header: String? = "Contacto Prueba",
  )

  sealed interface Expectation {
    data class At(val bounds: UiNode.Box, val strategy: String) : Expectation
    data class Ambiguous(val strategy: String) : Expectation
    data object None : Expectation
  }

  private val sendBox = box(980, 2200, 1060, 2280)
  private val entryBox = box(120, 2200, 820, 2280)

  /** WhatsApp, classic IDs, Spanish. */
  val waA = Fixture("WA_A", WA, Tree(n(UiNode(className = "android.widget.FrameLayout", bounds = box(0, 0, 1080, 2400)),
    label("Contacto Prueba", "conversation_contact_name", WA),
    label("¿Nos vemos mañana?"),
    field(WA, "entry", DRAFT, entryBox),
    button(WA, "send", desc = "Enviar", bounds = sendBox),
  )), send = Expectation.At(sendBox, "send.id.send"), composer = Expectation.At(entryBox, "composer.id.entry"))

  /** WhatsApp, renamed IDs and English labels. */
  val waB = Fixture("WA_B", WA, Tree(n(UiNode(bounds = box(0, 0, 1080, 2400)),
    label("Contacto Prueba", "toolbar_title", WA),
    field(WA, "message_input", DRAFT, entryBox),
    button(WA, "send_btn", desc = "Send", bounds = sendBox),
    n(UiNode(id = "$WA:id/voice_note_btn", description = "Voice message", clickable = true, bounds = box(980, 2300, 1060, 2380))),
  )), send = Expectation.At(sendBox, "send.id.send_btn"), composer = Expectation.At(entryBox, "composer.id.message_input"),
    mic = Expectation.At(box(980, 2300, 1060, 2380), "mic.id.voice_note_btn"))

  /** WhatsApp without useful IDs: exact labels only, with «Enviar ubicación» as a distractor that must not match. */
  val waC = Fixture("WA_C", WA, Tree(n(UiNode(bounds = box(0, 0, 1080, 2400)),
    label("Contacto Prueba", "action_bar_title", WA),
    button(null, null, text = "Enviar ubicación", bounds = box(100, 900, 900, 1000)),
    field(null, null, DRAFT, entryBox),
    button(null, null, desc = "Enviar", bounds = sendBox),
    n(UiNode(description = "Mensaje de voz, mantené presionado para grabar", clickable = true, bounds = box(980, 2300, 1060, 2380))),
  )), send = Expectation.At(sendBox, "send.label"), composer = Expectation.At(entryBox, "composer.editable"),
    mic = Expectation.At(box(980, 2300, 1060, 2380), "mic.label"), header = "Contacto Prueba")

  /** WhatsApp Business, classic IDs. */
  val wabA = Fixture("WAB_A", WAB, Tree(n(UiNode(bounds = box(0, 0, 1080, 2400)),
    label("Contacto Prueba", "conversation_contact_name", WAB),
    field(WAB, "entry", DRAFT, entryBox),
    button(WAB, "send", desc = "Enviar", bounds = sendBox),
  )), send = Expectation.At(sendBox, "send.id.send"), composer = Expectation.At(entryBox, "composer.id.entry"))

  private val okBox = box(700, 1300, 900, 1400)
  /** WhatsApp Business share confirmation with a build-specific ID, over the audio preview. */
  val wabB = Fixture("WAB_B", WAB, Tree(n(UiNode(bounds = box(0, 0, 1080, 2400)),
    label("¿Compartir con Contacto Prueba?"),
    n(UiNode(id = "$WAB:id/new_dialog_negative", text = "Cancelar", className = "android.widget.Button", clickable = true,
      bounds = box(400, 1300, 650, 1400))),
    n(UiNode(id = "$WAB:id/new_dialog_positive", text = "Aceptar", className = "android.widget.Button", clickable = true, bounds = okBox)),
    n(UiNode(id = "$WAB:id/media_caption_send_btn", description = "Enviar", clickable = true, bounds = sendBox)),
  )), send = Expectation.At(sendBox, "send.id.media_caption_send_btn"), composer = Expectation.None,
    share = Expectation.At(okBox, "share.label.confirm"), header = null)

  /** WhatsApp Business with two visible «Enviar» buttons and no ID: ambiguous, nothing may be touched. */
  val wabC = Fixture("WAB_C", WAB, Tree(n(UiNode(bounds = box(0, 0, 1080, 2400)),
    label("Contacto Prueba", "conversation_contact_name", WAB),
    field(WAB, "entry", DRAFT, entryBox),
    button(null, null, desc = "Enviar", bounds = sendBox),
    button(null, null, desc = "Enviar", bounds = box(980, 1200, 1060, 1280)),
  )), send = Expectation.Ambiguous("send.label"), composer = Expectation.At(entryBox, "composer.id.entry"),
    share = Expectation.Ambiguous("share.label"))

  val all = listOf(waA, waB, waC, wabA, wabB, wabC)
}
