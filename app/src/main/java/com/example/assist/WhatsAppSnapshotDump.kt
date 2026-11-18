package com.example.assist

import android.content.Context
import com.example.BuildConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object WhatsAppSnapshotDump {
  private val controlNames = setOf(
    "OK", "Aceptar", "Accept", "Confirmar", "Confirm", "Listo", "Done", "Hecho",
    "Enviar", "Send", "Invia", "Envoyer", "Senden", "Cancelar", "Cancel",
    "Reproducir", "Play", "Pausar", "Pause", "Reproducir audio", "Play audio",
    "Voice message", "Mensaje de voz", "Nota de voz", "Grabar mensaje de voz", "Record voice message",
  )

  fun writeIfEnabled(context: Context, root: UiNode) {
    if (!BuildConfig.DEBUG) return
    val marker = File(context.cacheDir, "capture-whatsapp-tree")
    if (!marker.delete()) return
    runCatching {
      File(context.cacheDir, "whatsapp-tree.json").writeText(
        JSONObject().put("origin", "captured").put("root", encode(root)).toString(2),
      )
    }
  }

  private fun encode(node: UiNode): JSONObject {
    val label = if (node.visible && !node.editable && (node.clickable || node.className?.endsWith("Button") == true)) {
      controlNames.firstOrNull {
        it.equals(node.contentDescription, ignoreCase = true) || it.equals(node.text, ignoreCase = true)
      }
    } else null
    val bounds = node.bounds
    return JSONObject()
      .put("id", node.id)
      .put("className", node.className)
      .put("controlName", label)
      .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
      .put("enabled", node.enabled)
      .put("clickable", node.clickable)
      .put("focused", node.focused)
      .put("visible", node.visible)
      .put("editable", node.editable)
      .put("children", JSONArray(node.children.map(::encode)))
  }
}
