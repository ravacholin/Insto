package com.example.assist

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal fun AccessibilityNodeInfo.snapshot(): UiNode {
  val rect = Rect()
  getBoundsInScreen(rect)
  val children = buildList {
    for (index in 0 until childCount) {
      val child = getChild(index) ?: continue
      try {
        add(child.snapshot())
      } finally {
        child.recycle()
      }
    }
  }
  return UiNode(
    id = viewIdResourceName,
    className = className?.toString(),
    text = text?.toString(),
    contentDescription = contentDescription?.toString(),
    bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
    enabled = isEnabled,
    clickable = isClickable,
    focused = isFocused,
    visible = isVisibleToUser,
    editable = isEditable,
    children = children,
  )
}
