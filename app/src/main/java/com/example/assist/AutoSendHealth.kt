package com.example.assist

/**
 * What the panel says about automatic WhatsApp sending. It describes evidence, never a guarantee: an enabled service
 * only means Insto may try. «Ready» needs a confirmed send with the WhatsApp version installed now.
 */
object AutoSendHealth {
  enum class State(val title: String) {
    READY("Auto-envío listo"),
    NEEDS_REVIEW("Auto-envío: necesita revisión"),
    DISABLED("Accesibilidad desactivada"),
  }

  /** [lastUncertain]: the last touch may have sent; the manual path only opens the chat to check, never resends. */
  data class Status(val state: State, val reason: String, val lastUncertain: Boolean = false)

  /** The installed WhatsApp flavor that sending would use, or null when none is installed. */
  data class Installed(val flavor: AutoSendMetrics.Flavor, val versionCode: Long, val versionName: String?)

  fun evaluate(
    enabled: Boolean,
    connected: Boolean,
    installed: Installed?,
    metrics: AutoSendMetrics.Snapshot,
  ): Status {
    if (!enabled) return Status(State.DISABLED, "Activá Accesibilidad de Insto para enviar sin tocar.")
    if (!connected) return Status(State.NEEDS_REVIEW,
      "Android indica que Accesibilidad está habilitada, pero el servicio no está conectado. Desactivalo y activalo una vez.")
    if (installed == null) return Status(State.NEEDS_REVIEW, "No encuentro WhatsApp instalado.")
    val version = installed.versionName?.let { " $it" }.orEmpty()
    val last = metrics.lastAttempt?.takeIf { it.flavor == installed.flavor }
    if (last != null && last.outcome != AutoSendMetrics.Outcome.CONFIRMED) return Status(State.NEEDS_REVIEW,
      "El último envío automático no se pudo confirmar (${last.outcome.label}). Revisá el chat; podés enviar a mano.",
      lastUncertain = last.outcome == AutoSendMetrics.Outcome.UNCERTAIN)
    val confirmed = metrics.lastConfirmed[installed.flavor]
    return when {
      confirmed == null -> Status(State.NEEDS_REVIEW,
        "Todavía no hay un envío automático confirmado con ${installed.flavor.label}$version.")
      confirmed != installed.versionCode -> Status(State.NEEDS_REVIEW,
        "${installed.flavor.label} se actualizó ($version) y todavía no hay un envío confirmado con esta versión.".replace("( ", "("))
      else -> Status(State.READY, "Último envío confirmado con ${installed.flavor.label}$version.")
    }
  }
}
