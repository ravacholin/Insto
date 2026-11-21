package com.example.phone

/**
 * The «Órdenes para Insto» block for Instinct's prompt, generated from the code so it never drifts from the
 * grammar: the rules of the protocol, one example per allowed type ([InstinctProposalValidator.examples]), the routines
 * the user enabled (names only, never their steps), the reply prefixes and the protocol version. A copy with no
 * routines lives in docs/instinct-catalog.txt and a test checks it matches.
 */
/**
 * How Instinct reads the log, for the catalog. [url] and [token] are the real values in the copy to paste in the
 * prompt; null in the copy sent through WhatsApp, which says to ask for them instead.
 */
data class InstinctCatalogLog(val url: String?, val token: String?) {
  companion object {
    /** The chat copy never carries the address nor the token. */
    val HIDDEN = InstinctCatalogLog(null, null)
    const val ASK = "(URL y token: pedíselos a Pablo)"
  }
}

object InstinctProtocolCatalog {
  const val VERSION = 4
  const val TITLE = "Protocolo Insto v$VERSION"
  const val ROUTINE_FORM = "ejecutar la rutina"
  /** Marks example lines in a copy sent through WhatsApp, so that copy never reads as orders for Insto. */
  const val CHAT_MARK = "› "

  /**
   * [forChat] is the copy sent to Instinct through WhatsApp: every example line is preceded by [CHAT_MARK], because
   * Insto reads that chat and must never take its own message for orders. With [log] the results are in the log,
   * not in the chat, and the block says how to read it.
   */
  /**
   * [mailbox] (1.16.0), when the signed mailbox is on: how to sign and publish an order, with the log's address and
   * token in the copy to paste ([InstinctCatalogLog.HIDDEN] in the chat copy). Never the key, in any copy.
   */
  fun text(routines: List<String>, forChat: Boolean = false, log: InstinctCatalogLog? = null,
    autonomy: InstinctAutonomyPolicy = InstinctAutonomyPolicy(), mailbox: InstinctCatalogLog? = null): String {
    fun example(order: String) = (if (forChat) CHAT_MARK else "") + "${InstinctProposalProtocol.PREFIX} $order"
    return buildString {
      appendLine("Órdenes para Insto ($TITLE)")
      appendLine()
      appendLine("Insto es el asistente del teléfono de quien te escribe. Podés hacer que el teléfono haga algo concreto, por tu cuenta, cuando lo consideres claramente útil. Para eso agregá en tu mensaje de WhatsApp líneas con este formato exacto:")
      appendLine()
      appendLine(example("<orden>"))
      appendLine()
      appendLine("Insto ejecuta cada línea apenas llega, sin preguntarle a la persona, y después le avisa, salvo lo que ella eligió confirmar (ver «Qué hace Insto solo» más abajo). Usalo con criterio: solo cuando la acción sea útil ahora, y explicá en el mismo mensaje qué hiciste y por qué.")
      if (forChat) {
        appendLine()
        appendLine("En este mensaje cada ejemplo va precedido por «${CHAT_MARK.trim()}» para que Insto no lo ejecute. Vos escribí la línea sin ese signo, empezando con \"${InstinctProposalProtocol.PREFIX} \".")
      }
      appendLine()
      appendLine("Reglas:")
      appendLine("- Cada orden va sola en su propia línea y la línea empieza con \"${InstinctProposalProtocol.PREFIX} \" (en minúsculas, seguido de un espacio). Nunca pongas \"${InstinctProposalProtocol.PREFIX}\" en medio de una oración ni lo uses como ejemplo en el texto.")
      appendLine("- Como máximo ${InstinctProposalProtocol.MAX_LINES} líneas de orden por mensaje. Insto ignora las demás. Más de 6 órdenes en 10 minutos quedan esperando confirmación.")
      appendLine("- Una sola acción por línea, en español, con verbo en infinitivo y todos los datos explícitos. Sin negaciones, sin preguntas, sin \"y\" para encadenar acciones y sin otras condiciones que las del clima de más abajo.")
      appendLine("- Horas en formato de 24 horas (07:00, 17:30). Duraciones con número y unidad (25 minutos, una hora). Volumen en porcentaje (30 por ciento).")
      appendLine("- Insto solo lee esas líneas: el resto de tu mensaje no se interpreta. Si una línea no se entiende o no está permitida, se descarta.")
      appendLine("- Para más tarde, envolvé la orden: \"a las HH:MM: <orden>\" (24 horas; si esa hora ya pasó hoy, es mañana) o \"en N minutos: <orden>\" / \"en N horas: <orden>\" (de 1 minuto a 24 horas). Los dos puntos después del tiempo son obligatorios. Como máximo ${InstinctDeferral.MAX_SCHEDULED} órdenes programadas.")
      appendLine("- Solo con una hora, podés agregar una única condición del clima antes de los dos puntos: \"si llueve\", \"si no llueve\", \"si hace menos de N grados\" o \"si hace más de N grados\". «Llueve» es una probabilidad de lluvia de ${WeatherConditions.RAIN_THRESHOLD_PERCENT} % o más para las 6 horas siguientes; la temperatura es la medición actual. Sin datos del clima, la orden no se ejecuta.")
      appendLine()
      appendLine("Acciones permitidas (usá estas formas):")
      InstinctProposalValidator.examples.forEach { appendLine(example(it)) }
      appendLine()
      appendLine("Rutinas que la persona te habilitó (usá el nombre exacto; se ejecutan paso por paso, se detienen en el primer paso que falla y cuentan como una sola orden):")
      if (routines.isEmpty()) appendLine("(ninguna por ahora)")
      else routines.forEach { appendLine(example("$ROUTINE_FORM $it")) }
      appendLine()
      appendLine(InstinctAutonomyCatalog.text(autonomy))
      appendLine()
      // The state only travels through the log: without it Instinct has nothing to read.
      if (log != null) {
        appendLine("Estado del teléfono (solo lectura, si la persona configuró la bitácora):")
        appendLine("Insto publica en la bitácora un evento de tipo «${InstinctState.KIND}» cuando algo cambia (agrupado cada 30 segundos), cada 30 minutos aunque no cambie nada y después de cada orden tuya que se ejecutó. Para leer el último:")
        if (log.url != null && log.token != null) appendLine("GET ${InstinctLogConfig.stateUrl(log.url, log.token)}")
        else {
          appendLine("GET <URL>?token=<token>&action=estado")
          appendLine(InstinctCatalogLog.ASK)
        }
        appendLine("La respuesta es {ok, estado} con estado null o {recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}. ocurrido dice cuándo se tomó. detalle es una línea «clave=valor; clave=valor» con estos campos:")
        appendLine("sonido: normal, vibración o silencio. dnd: sí o no (No molestar). alarma: la hora de la próxima alarma, o ninguna. evento: «ahora <título> HH:MM–HH:MM», «próximo <título> HH:MM–HH:MM» (en las próximas 12 horas) o libre. audio: no, o sí con el tipo (auriculares o Bluetooth). bloqueado: sí o no. modo_temporal: no, o cuál y hasta qué hora. programadas: ninguna, o cuántas órdenes tuyas hay programadas y la próxima hora.")
        appendLine("Un campo puede decir «${InstinctState.NO_DATA}» o «${InstinctState.NO_PERMISSION}». El estado es una foto: puede tener hasta 30 segundos de demora.")
        appendLine("Antes de una orden que dependa del estado (silencio, No molestar, alarmas, calendario), mirá el último estado; si tiene más de 30 minutos, pedí la consulta «${ProposalCheck.QueryKind.STATE.phrase}» y esperá a que aparezca el nuevo.")
        appendLine()
      }
      if (mailbox != null) {
        appendLine(InstinctMailboxCatalog.text(mailbox, forChat))
        appendLine()
      }
      appendLine("Consultas y gestión, que no cambian nada en el teléfono (la respuesta llega solo si la persona activó las respuestas):")
      ProposalCheck.QueryKind.entries.forEach { appendLine(example(it.phrase)) }
      appendLine()
      appendLine("Nunca pidas llamadas ni mensajes (de WhatsApp, SMS ni de ningún otro tipo), ni enviar o compartir archivos, ni guardar lugares, ni navegación, ni rutinas que no estén en la lista, ni varias acciones juntas. Insto los rechaza siempre.")
      appendLine()
      if (log == null) {
        appendLine("Respuestas de Insto (si la persona las activó):")
        appendLine("Insto puede contestarte en este chat con una línea por orden, en el mismo orden:")
        appendLine("${AckKind.OK.prefix} <orden> → <resultado>: la orden se ejecutó.")
        appendLine("${AckKind.WAITING.prefix} <orden> → <motivo>: quedó esperando que la persona la confirme.")
        appendLine("${AckKind.ERROR.prefix} <orden> → <motivo>: no se ejecutó (no se entendió, no está permitida o falló).")
        appendLine("${AckKind.UNCERTAIN.prefix} <orden> → <mensaje> · no se repite: pudo haberse hecho; Insto no la repite.")
        appendLine("${AckKind.SCHEDULED.prefix} <orden> → a las HH:MM: quedó programada.")
        appendLine("Después de una espera o de una orden programada puede llegar una línea más: ${AckKind.OK.prefix}, ${AckKind.ERROR.prefix}, ${AckKind.DISCARDED.prefix} o ${AckKind.EXPIRED.prefix}. Si la persona deshace lo que hiciste, llega ${AckKind.UNDONE.prefix} <orden>.")
        append("Esas líneas son informativas: no las respondas con nuevas órdenes, salvo una sola orden corregida después de un ${AckKind.ERROR.prefix}. Nunca repitas una orden después de ${AckKind.UNCERTAIN.prefix} ni de ${AckKind.WAITING.prefix}.")
      } else {
        appendLine("Resultados de tus órdenes (si la persona activó las respuestas):")
        appendLine("Insto no los escribe en este chat: los anota en una bitácora, una planilla de Google, con un evento por orden. Para leerla hacé este pedido:")
        if (log.url != null && log.token != null) appendLine("GET ${InstinctLogConfig.readUrl(log.url, log.token)}")
        else {
          appendLine("GET <URL>?token=<token>&since=<ISO>&limit=50")
          appendLine(InstinctCatalogLog.ASK)
        }
        appendLine("since es un instante ISO-8601 con zona (por ejemplo 2026-11-21T09:00:00-03:00): devuelve los eventos recibidos desde entonces, como máximo 50. La respuesta es {ok, events:[{recibido, evento_id, ocurrido, tipo, orden, detalle, mensaje_id, protocolo}]}.")
        appendLine("tipo es uno de estos:")
        appendLine("${AckKind.OK.code}: la orden se ejecutó (también la respuesta a una consulta).")
        appendLine("${AckKind.WAITING.code}: quedó esperando que la persona la confirme.")
        appendLine("${AckKind.ERROR.code}: no se ejecutó (no se entendió, no está permitida o falló).")
        appendLine("${AckKind.UNCERTAIN.code}: pudo haberse hecho; Insto no la repite.")
        appendLine("${AckKind.SCHEDULED.code}: quedó programada.")
        appendLine("${InstinctState.KIND}: el estado del teléfono (ver «Estado del teléfono»); no es el resultado de una orden.")
        appendLine("Después de una espera o de una orden programada puede llegar otro evento de la misma orden: ${AckKind.OK.code}, ${AckKind.ERROR.code}, ${AckKind.DISCARDED.code} o ${AckKind.EXPIRED.code}, aunque haya pasado mucho tiempo. Si la persona deshace lo que hiciste, llega ${AckKind.UNDONE.code}.")
        appendLine("orden es tu orden (recortada a ${InstinctAck.MAX_ORDER_CHARS} caracteres), detalle es el resultado y mensaje_id identifica el mensaje tuyo del que salió la orden (un código, nunca el texto).")
        append("Los eventos son informativos: no los respondas con nuevas órdenes, salvo una sola orden corregida después de un ${AckKind.ERROR.code}. Nunca repitas una orden después de ${AckKind.UNCERTAIN.code} ni de ${AckKind.WAITING.code}. Antes de repetir o corregir una orden, consultá la bitácora.")
      }
    }
  }

  /** The answer to «ver catálogo»: the version and the enabled routines, not the whole catalog. */
  fun summary(routines: List<String>): String =
    "$TITLE · rutinas habilitadas: " + if (routines.isEmpty()) "ninguna." else routines.joinToString(", ") + "."
}
