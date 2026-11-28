package com.example.phone

/** The closed vocabulary is shared by the local registry and the offline recognizer. */
internal enum class CapabilityHealthCommand(val title: String, vararg val phrases: String) {
  STATUS("Consultar la salud de Insto", "¿Está funcionando todo?", "¿Qué problemas tenés?", "Ver salud de Insto"),
  OMNIREMOTE("Consultar Omniremote", "¿Por qué no funciona Omniremote?", "¿Está funcionando Omniremote?"),
  INSTINCT("Consultar Instinct", "¿Está conectado Instinct?", "¿Está funcionando Instinct?"),
  GEMINI("Consultar Gemini", "¿Está funcionando Gemini?", "¿Por qué no funciona Gemini?"),
  MICROPHONE("Consultar el micrófono", "Comprobá el micrófono", "Revisá el micrófono"),
  FULL("Ejecutar comprobaciones generales", "Hacé un diagnóstico completo", "Hacé un diagnóstico de Insto"),
  UNAVAILABLE("Consultar limitaciones", "¿Qué capacidades no están disponibles?", "¿Qué no funciona?"),
  RECOVER("Intentar una recuperación segura", "Intentá solucionar los problemas", "Intentá recuperar Insto"),
  LAST_FAILURE("Explicar el último fallo", "¿Por qué falló la última orden?", "¿Por qué falló el último pedido?");

  val command get() = PhoneCommand.Platform(PlatformAction.CAPABILITY_HEALTH, name)

  fun definition() = LocalIntentDefinition("health_${name.lowercase(java.util.Locale.ROOT)}", IntentCategory.DEVICE, title,
    phrases.toList(), emptyList(), emptyList(), "CapabilityHealthEngine", title, fixed = command, decisions = false)

  companion object {
    fun of(command: PhoneCommand?): CapabilityHealthCommand? {
      if (command !is PhoneCommand.Platform || command.action != PlatformAction.CAPABILITY_HEALTH) return null
      return entries.firstOrNull { it.name == command.argument }
    }
  }
}
