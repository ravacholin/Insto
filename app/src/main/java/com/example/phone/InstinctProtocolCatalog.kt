package com.example.phone

/**
 * The «Órdenes para Insto» block for Instinct's prompt, generated from the code so it never drifts from the
 * grammar: the rules of the protocol, one example per allowed type ([InstinctProposalValidator.examples]), the routines
 * the user enabled (names only, never their steps), the reply prefixes and the protocol version. A copy with no
 * routines lives in docs/instinct-catalog.txt and a test checks it matches.
 */
object InstinctProtocolCatalog {
  const val VERSION = 2
  const val TITLE = "Protocolo Insto v$VERSION"
  const val ROUTINE_FORM = "ejecutar la rutina"
  /** Marks example lines in a copy sent through WhatsApp, so that copy never reads as orders for Insto. */
  const val CHAT_MARK = "› "

  /**
   * [forChat] is the copy sent to Instinct through WhatsApp: every example line is preceded by [CHAT_MARK], because
   * Insto reads that chat and must never take its own message for orders.
   */
  fun text(routines: List<String>, forChat: Boolean = false): String {
    fun example(order: String) = (if (forChat) CHAT_MARK else "") + "${InstinctProposalProtocol.PREFIX} $order"
    return buildString {
      appendLine("Órdenes para Insto ($TITLE)")
      appendLine()
      appendLine("Insto es el asistente del teléfono de quien te escribe. Podés hacer que el teléfono haga algo concreto, por tu cuenta, cuando lo consideres claramente útil. Para eso agregá en tu mensaje de WhatsApp líneas con este formato exacto:")
      appendLine()
      appendLine(example("<orden>"))
      appendLine()
      appendLine("Insto ejecuta cada línea apenas llega, sin preguntarle a la persona, y después le avisa. Usalo con criterio: solo cuando la acción sea útil ahora, y explicá en el mismo mensaje qué hiciste y por qué.")
      if (forChat) {
        appendLine()
        appendLine("En este mensaje cada ejemplo va precedido por «${CHAT_MARK.trim()}» para que Insto no lo ejecute. Vos escribí la línea sin ese signo, empezando con \"${InstinctProposalProtocol.PREFIX} \".")
      }
      appendLine()
      appendLine("Reglas:")
      appendLine("- Cada orden va sola en su propia línea y la línea empieza con \"${InstinctProposalProtocol.PREFIX} \" (en minúsculas, seguido de un espacio). Nunca pongas \"${InstinctProposalProtocol.PREFIX}\" en medio de una oración ni lo uses como ejemplo en el texto.")
      appendLine("- Como máximo ${InstinctProposalProtocol.MAX_LINES} líneas de orden por mensaje. Insto ignora las demás. Más de 6 órdenes en 10 minutos quedan esperando confirmación.")
      appendLine("- Una sola acción por línea, en español, con verbo en infinitivo y todos los datos explícitos. Sin condiciones, sin negaciones, sin preguntas, sin \"y\" para encadenar acciones.")
      appendLine("- Horas en formato de 24 horas (07:00, 17:30). Duraciones con número y unidad (25 minutos, una hora). Volumen en porcentaje (30 por ciento).")
      appendLine("- Insto solo lee esas líneas: el resto de tu mensaje no se interpreta. Si una línea no se entiende o no está permitida, se descarta.")
      appendLine()
      appendLine("Acciones permitidas (usá estas formas):")
      InstinctProposalValidator.examples.forEach { appendLine(example(it)) }
      appendLine()
      appendLine("Rutinas que la persona te habilitó (usá el nombre exacto; se ejecutan paso por paso, se detienen en el primer paso que falla y cuentan como una sola orden):")
      if (routines.isEmpty()) appendLine("(ninguna por ahora)")
      else routines.forEach { appendLine(example("$ROUTINE_FORM $it")) }
      appendLine()
      appendLine("Consultas que no cambian nada en el teléfono (la respuesta llega solo si la persona activó las respuestas):")
      ProposalCheck.QueryKind.entries.forEach { appendLine(example(it.phrase)) }
      appendLine()
      appendLine("Nunca pidas llamadas ni mensajes (de WhatsApp, SMS ni de ningún otro tipo), ni enviar o compartir archivos, ni guardar lugares, ni navegación, ni rutinas que no estén en la lista, ni varias acciones juntas. Insto los rechaza siempre.")
      appendLine()
      appendLine("Respuestas de Insto (si la persona las activó):")
      appendLine("Insto puede contestarte en este chat con una línea por orden, en el mismo orden:")
      appendLine("${AckKind.OK.prefix} <orden> → <resultado>: la orden se ejecutó.")
      appendLine("${AckKind.WAITING.prefix} <orden> → <motivo>: quedó esperando que la persona la confirme.")
      appendLine("${AckKind.ERROR.prefix} <orden> → <motivo>: no se ejecutó (no se entendió, no está permitida o falló).")
      appendLine("${AckKind.UNCERTAIN.prefix} <orden> → <mensaje> · no se repite: pudo haberse hecho; Insto no la repite.")
      appendLine("Después de una espera puede llegar ${AckKind.OK.prefix}, ${AckKind.DISCARDED.prefix} o ${AckKind.EXPIRED.prefix}.")
      append("Esas líneas son informativas: no las respondas con nuevas órdenes, salvo una sola orden corregida después de un ${AckKind.ERROR.prefix}. Nunca repitas una orden después de ${AckKind.UNCERTAIN.prefix} ni de ${AckKind.WAITING.prefix}.")
    }
  }

  /** The answer to «ver catálogo»: the version and the enabled routines, not the whole catalog. */
  fun summary(routines: List<String>): String =
    "$TITLE · rutinas habilitadas: " + if (routines.isEmpty()) "ninguna." else routines.joinToString(", ") + "."
}
