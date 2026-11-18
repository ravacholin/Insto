package com.example.assist

internal data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  fun touchArea(screen: UiBounds): UiBounds? {
    val clipped = UiBounds(
      maxOf(left, screen.left), maxOf(top, screen.top),
      minOf(right, screen.right), minOf(bottom, screen.bottom),
    )
    return clipped.takeIf { it.right - it.left >= 8 && it.bottom - it.top >= 8 }
  }
}

internal class UiNode(
  val id: String? = null,
  val className: String? = null,
  val text: String? = null,
  val contentDescription: String? = null,
  val bounds: UiBounds = UiBounds(0, 0, 0, 0),
  val enabled: Boolean = true,
  val clickable: Boolean = false,
  val focused: Boolean = false,
  val visible: Boolean = true,
  val editable: Boolean = false,
  children: List<UiNode> = emptyList(),
) {
  val children: List<UiNode> = java.util.Collections.unmodifiableList(children.toList())
}
