package com.example.assist

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [UiTree] over a live accessibility window. Every node is copied into a [UiNode] and recycled immediately, so the
 * selectors never hold framework nodes. The traversal limits are those the service always used.
 */
class AccessibilityUiTree(private val root: AccessibilityNodeInfo) : UiTree {
  private var walked: List<UiNode>? = null

  override fun byId(fullId: String): List<UiNode> {
    val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(fullId) }.getOrNull().orEmpty()
    if (nodes.isNotEmpty()) return nodes.map { node -> snapshot(node).also { node.recycle() } }
    // The framework searches the whole window; when it finds nothing, the bounded walk (shared per check) cannot
    // find more in practice. It does matter for detached snapshots, such as the synthetic trees of the tests.
    return walk().filter { it.id == fullId }.toList()
  }

  /** Walked once per tree: several selectors share the same snapshot in one check. */
  override fun walk(): Sequence<UiNode> = (walked ?: collect().also { walked = it }).asSequence()

  private fun collect(): List<UiNode> {
    val result = mutableListOf<UiNode>()
    var visited = 0
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
      if (visited++ >= MAX_NODES || depth > MAX_DEPTH) return
      result += snapshot(node)
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        try { visit(child, depth + 1) } finally { child.recycle() }
      }
    }
    visit(root, 0)
    return result
  }

  companion object {
    const val MAX_NODES = 800
    const val MAX_DEPTH = 28

    fun snapshot(node: AccessibilityNodeInfo): UiNode {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      return UiNode(
        id = node.viewIdResourceName,
        text = node.text?.toString()?.take(512),
        description = node.contentDescription?.toString()?.take(512),
        className = node.className?.toString(),
        clickable = node.isClickable,
        editable = node.isEditable,
        visible = node.isVisibleToUser,
        enabled = node.isEnabled,
        showingHint = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && node.isShowingHintText,
        bounds = UiNode.Box(rect.left, rect.top, rect.right, rect.bottom),
      )
    }
  }
}
