package com.example.phone

import org.json.JSONObject

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

/**
 * What the JSON catalog says about the channels (1.18.0): which are on, never their addresses, tokens, keys or
 * endpoints. [whatsapp] is false with «Solo buzón».
 */
data class InstinctCatalogChannels(
  val whatsapp: Boolean = true,
  val log: Boolean = false,
  val mailbox: Boolean = false,
  val doorbell: Boolean = false,
  val doorbellDirect: Boolean = false,
  val replyMode: InstinctReplyMode = InstinctReplyMode.NEVER,
  val ackChannel: InstinctAckChannel = InstinctAckChannel.WHATSAPP,
  val showQuestionsOnLockScreen: Boolean = false,
)

/**
 * The JSON catalog (1.18.0). [canonical] is the canonical form (keys sorted, no spaces, without `hash` nor
 * `publicado`); [hash] its SHA-256 in lowercase hex. [published] is what goes to the sheet.
 */
data class InstinctCatalogJson(val canonical: String, val hash: String) {
  /** The catalog with its hash and, when given, the instant it was published. */
  fun published(at: String? = null): String = InstinctCatalogCanonical.withFields(canonical, listOfNotNull("hash" to hash, at?.let { "publicado" to it }))
}

/** A JSON writer with one canonical form: keys sorted, no spaces, strings quoted as org.json does. Pure. */
internal object InstinctCatalogCanonical {
  fun write(value: Any?): String = buildString { write(this, value) }

  private fun write(out: StringBuilder, value: Any?) {
    when (value) {
      null -> out.append("null")
      is String -> out.append(org.json.JSONObject.quote(value))
      is Boolean, is Int, is Long -> out.append(value.toString())
      is Map<*, *> -> {
        out.append('{')
        value.entries.map { it.key as String to it.value }.sortedBy { it.first }.forEachIndexed { i, (k, v) ->
          if (i > 0) out.append(','); out.append(org.json.JSONObject.quote(k)).append(':'); write(out, v)
        }
        out.append('}')
      }
      is List<*> -> { out.append('['); value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }; out.append(']') }
      else -> throw IllegalArgumentException("tipo no admitido en el catálogo")
    }
  }

  /** The same JSON, keys sorted, indented by two spaces: for the reference copy in docs. */
  fun pretty(text: String): String = buildString { pretty(this, toKotlin(org.json.JSONObject(text)), "") }

  private fun pretty(out: StringBuilder, value: Any?, indent: String) {
    val inner = "$indent  "
    when (value) {
      is Map<*, *> -> if (value.isEmpty()) out.append("{}") else {
        out.append("{\n")
        value.entries.map { it.key as String to it.value }.sortedBy { it.first }.forEachIndexed { i, (k, v) ->
          if (i > 0) out.append(",\n"); out.append(inner).append(org.json.JSONObject.quote(k)).append(": "); pretty(out, v, inner)
        }
        out.append('\n').append(indent).append('}')
      }
      is List<*> -> if (value.isEmpty()) out.append("[]") else {
        out.append("[\n")
        value.forEachIndexed { i, v -> if (i > 0) out.append(",\n"); out.append(inner); pretty(out, v, inner) }
        out.append('\n').append(indent).append(']')
      }
      else -> write(out, value)
    }
  }

  /** [canonical] (an object) with [fields] added, keeping the keys sorted. */
  fun withFields(canonical: String, fields: List<Pair<String, String>>): String {
    val o = org.json.JSONObject(canonical)
    return write(toKotlin(o).let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) + fields })
  }

  fun toKotlin(value: Any?): Any? = when (value) {
    is org.json.JSONObject -> value.keys().asSequence().associateWith { toKotlin(value.get(it)) }
    is org.json.JSONArray -> (0 until value.length()).map { toKotlin(value.get(it)) }
    org.json.JSONObject.NULL -> null
    else -> value
  }
}

object InstinctProtocolCatalog {
  /** 1.20.0: the execution journal, as Instinct reads it. */
  const val EXECUTIONS_LINE = "«ver ejecuciones» lista las últimas órdenes con un id de 9 caracteres (e y 8 dígitos hexadecimales), su estado (recibida, validada, espera, programada, lista, despachando, verificando, verificada, entregada, incierta, fallida, cancelada, vencida, por confirmar, preguntando o respondida), la hora, de dónde llegó, la orden y el resultado; «estado de ejecución <id>» muestra una sola. El texto de las preguntas y sus resultados está disponible en Insto y por la bitácora."
  /** 1.20.0: what an «ok» says about the result. */
  const val OUTCOMES_LINE = "Cómo leer un resultado: «Verificado: …» es que Insto leyó el teléfono y quedó como pediste; «Entregado; Insto no puede comprobar el resultado final.» es que otra app (el Reloj, el calendario, una búsqueda, la música) o Android lo aceptó, pero no se puede saber si lo hizo; «No se logró: …» llega como error; «(recuperada tras reiniciarse Insto)» es un resultado que Insto informa después de un reinicio. Una orden incierta nunca se repite sola."

  const val VERSION = 8
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
    autonomy: InstinctAutonomyPolicy = InstinctAutonomyPolicy(), mailbox: InstinctCatalogLog? = null, doorbell: Boolean = false,
    doorbellDirect: Boolean = false): String {
    fun example(order: String) = (if (forChat) CHAT_MARK else "") + "${InstinctProposalProtocol.PREFIX} $order"
    return buildString {
      appendLine("Órdenes para Insto ($TITLE)")
      appendLine()
      appendLine("Insto es el asistente del teléfono de quien te escribe. Podés hacer que el teléfono haga algo concreto, por tu cuenta, cuando lo consideres claramente útil. Para eso agregá en tu mensaje de WhatsApp líneas con este formato exacto:")
      appendLine()
      appendLine(example("<orden>"))
      appendLine()
      appendLine("Insto ejecuta cada línea apenas llega, sin preguntarle a la persona, y después le avisa, salvo lo que ella eligió confirmar (ver «Qué hace Insto solo» más abajo). Usalo con criterio: solo cuando la acción sea útil ahora, y explicá en el mismo mensaje qué hiciste y por qué.")
      // The live catalog (1.18.0) lives in the log: without it there is nothing to read.
      if (log != null) {
        appendLine()
        appendLine("Reglas vigentes (catálogo vivo):")
        if (log.url != null && log.token != null) appendLine(LIVE_RULE.replace("<URL>?token=<token>", "${log.url}?token=${log.token}"))
        else { appendLine(LIVE_RULE); appendLine(InstinctCatalogLog.ASK) }
      }
      if (forChat) {
        appendLine()
        appendLine("En este mensaje cada ejemplo va precedido por «${CHAT_MARK.trim()}» para que Insto no lo ejecute. Vos escribí la línea sin ese signo, empezando con \"${InstinctProposalProtocol.PREFIX} \".")
      }
      appendLine()
      appendLine("Reglas:")
      appendLine("- Cada orden va sola en su propia línea y la línea empieza con \"${InstinctProposalProtocol.PREFIX} \" (en minúsculas, seguido de un espacio). Nunca pongas \"${InstinctProposalProtocol.PREFIX}\" en medio de una oración ni lo uses como ejemplo en el texto.")
      appendLine("- Como máximo ${InstinctProposalProtocol.MAX_LINES} líneas de orden por mensaje. Insto ignora las demás. Más de 6 órdenes en 10 minutos quedan esperando confirmación.")
      appendLine("- Una sola acción por línea, en español, con verbo en infinitivo y todos los datos explícitos. Sin «y» para encadenar acciones: para varias acciones seguidas usá «plan». Para hacer una pregunta usá «preguntar» o «decidir».")
      appendLine("- Horas en formato de 24 horas (07:00, 17:30). Duraciones con número y unidad (25 minutos, una hora). Volumen en porcentaje (30 por ciento).")
      appendLine("- Insto solo lee esas líneas: el resto de tu mensaje no se interpreta. Si una línea no se entiende o no está permitida, se descarta.")
      appendLine("- Para más tarde, envolvé la orden: \"a las HH:MM: <orden>\" (24 horas; si esa hora ya pasó hoy, es mañana) o \"en N minutos: <orden>\" / \"en N horas: <orden>\" (de 1 minuto a 24 horas). Los dos puntos después del tiempo son obligatorios. Como máximo ${InstinctDeferral.MAX_SCHEDULED} órdenes programadas.")
      appendLine("- Solo con una hora, podés agregar una única condición del clima antes de los dos puntos: \"si llueve\", \"si no llueve\", \"si hace menos de N grados\" o \"si hace más de N grados\". «Llueve» es una probabilidad de lluvia de ${WeatherConditions.RAIN_THRESHOLD_PERCENT} % o más para las 6 horas siguientes; la temperatura es la medición actual. Sin datos del clima, la orden no se ejecuta.")
      appendLine("- Disparadores, para que el teléfono haga algo solo cuando pase algo: \"al terminar el evento: <orden>\" (el evento del calendario en curso), \"al empezar el próximo evento: <orden>\" (el próximo en las 12 horas siguientes), \"al conectar auriculares: <orden>\", \"al desconectar auriculares: <orden>\" y \"al desbloquear: <orden>\". Cada uno se usa una sola vez. Vencen a las 12 horas; con \"hasta las HH:MM\" antes de los dos puntos, a esa hora (como mucho 24 horas). Un disparador que vence sin dispararse se informa como vencida. Los eventos de todo el día no cuentan.")
      appendLine("- Recurrentes: \"todos los días a las HH:MM hasta el DD/MM: <orden>\", \"los días hábiles a las HH:MM hasta el DD/MM: <orden>\" (de lunes a viernes) o \"los fines de semana a las HH:MM hasta el DD/MM: <orden>\". \"hasta el DD/MM\" es obligatorio y como mucho ${InstinctTriggers.MAX_RECURRING_DAYS} días; podés agregar una condición del clima antes de \"hasta el\". Como máximo ${InstinctTriggers.MAX_RECURRING} recurrentes, aparte de las ${InstinctDeferral.MAX_SCHEDULED} programadas. Cada ocurrencia se informa por separado.")
      appendLine("- Los disparadores de un solo uso, las órdenes por hora y las recurrentes conservan su formato. Para combinar condiciones locales usá «cuando».")
      appendLine("- $TRIGGER_RULE")
      appendLine("- ${InstinctProbe.RULE}")
      appendLine()
      appendLine("Acciones permitidas (usá estas formas):")
      InstinctProposalValidator.examples.forEach { appendLine(example(it)) }
      appendLine()
      appendLine("Preguntas con opciones:")
      appendLine("Formato: «${InstinctProposalProtocol.PREFIX} ${InstinctQuestion.FORM}».")
      appendLine(example(QUESTION_EXAMPLE))
      appendLine("Para ejecutar la opción en el mismo toque usá «${InstinctProposalProtocol.PREFIX} ${InstinctQuestion.DECISION_FORM}».")
      appendLine(example(DECISION_EXAMPLE))
      appendLine(QUESTION_USE)
      appendLine("Una pregunta por línea, de hasta ${InstinctQuestion.MAX_TEXT} caracteres y con 2 o 3 opciones de hasta ${InstinctQuestion.MAX_OPTION} caracteres cada una. Sin respuesta libre. Vence en ${InstinctQuestion.DEFAULT_HOURS} horas salvo que agregues «hasta las HH:MM» o «en N horas» al final (máximo ${InstinctQuestion.MAX_HOURS} horas). No la programes ni la envuelvas en un disparador.")
      appendLine("Hasta ${InstinctQuestion.MAX_PENDING} pendientes y ${InstinctQuestion.PER_HOUR} en una hora. Durante las horas de silencio espera y, si vence antes, se anota como vencida. La categoría Preguntas puede pedir una vista previa o rechazarla.")
      appendLine(QUESTION_RESULT)
      appendLine("Leé las respuestas en la bitácora con GET <URL>?token=<token>&since=<ISO>&limit=50. «probar preguntar …» y «probar decidir …» validan la forma sin mostrar una notificación.")
      appendLine()
      appendLine(DECISION_RULE)
      appendLine()
      appendLine("Planes de varios pasos:")
      appendLine("Formato: «${InstinctProposalProtocol.PREFIX} ${InstinctPlanSpec.FORM}».")
      appendLine("Pasos: ${InstinctPlanSpec.STEP_FORMS}.")
      InstinctPlanSpec.EXAMPLES.forEach { appendLine(example(it)) }
      appendLine(PLAN_RULE)
      appendLine(PLAN_RESULT)
      if (mailbox != null) {
        appendLine()
        appendLine(InstinctStructuredPlan.GUIDE)
        appendLine("Usá las secciones «plan_json», «capacidades» y «rutinas» de action=catalogo.")
        appendLine(example(InstinctStructuredPlan.MEETING_EXAMPLE))
      }
      appendLine()
      appendLine("Reglas contextuales locales:")
      appendLine("Formato: «${InstinctProposalProtocol.PREFIX} ${ContextRuleSpec.FORM}».")
      appendLine(example(ContextRuleSpec.EXAMPLE))
      appendLine("Condiciones: ${ContextRuleSpec.conditionForms.joinToString(" · ")}.")
      appendLine(CONTEXT_RULE)
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
        appendLine(InstinctMailboxCatalog.text(mailbox, forChat, doorbell, doorbellDirect))
        appendLine()
      }
      appendLine("Consultas y gestión de programaciones y reglas (la respuesta llega si la persona activó las respuestas). «ver programadas» muestra cada una con un id de ${InstinctTriggers.SHORT_ID_LENGTH} caracteres; con ese id, «cancelar programada <id>» cancela esa orden o la recurrente entera. «probar <orden>» valida la orden (también diferida, con disparador, recurrente o con clima) y dice qué pasaría ahora, sin ejecutar ni guardar nada: «válida · <categoría> · la haría sola», «válida · <categoría> · esperaría confirmación: <motivo>», «válida · programada: <cuándo>», «no válida: <motivo>» o «rechazada: la persona no permite esta acción»; como máximo ${InstinctProbe.PER_PERIOD} pruebas cada 10 minutos:")
      ProposalCheck.QueryKind.entries.forEach { appendLine(example(it.form)) }
      appendLine(EXECUTIONS_LINE)
      appendLine(OUTCOMES_LINE)
      appendLine()
      appendLine("Nunca pidas llamadas ni mensajes (de WhatsApp, SMS ni de ningún otro tipo), ni enviar o compartir archivos, ni guardar lugares, ni navegación, ni rutinas que no estén en la lista, ni varias acciones juntas fuera de un plan. Insto los rechaza siempre.")
      appendLine()
      if (log == null) {
        appendLine("Respuestas de Insto (si la persona las activó):")
        appendLine("Insto puede contestarte en este chat con una línea por orden, en el mismo orden:")
        appendLine("${AckKind.OK.prefix} <orden> → <resultado>: la orden se ejecutó.")
        appendLine("${AckKind.WAITING.prefix} <orden> → <motivo>: quedó esperando que la persona la confirme.")
        appendLine("${AckKind.ERROR.prefix} <orden> → <motivo>: no se ejecutó (no se entendió, no está permitida o falló).")
        appendLine("${AckKind.UNCERTAIN.prefix} <orden> → <mensaje> · no se repite: pudo haberse hecho; Insto no la repite.")
        appendLine("${AckKind.SCHEDULED.prefix} <orden> → <cuándo>: quedó programada (la hora, el disparador o la recurrencia, con su próxima ocurrencia).")
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
        appendLine("${AckKind.SCHEDULED.code}: quedó programada; detalle dice cuándo (la hora, el disparador o la recurrencia).")
        appendLine("${InstinctLogEvents.RESPONSE_KIND}: respuesta a una pregunta (ver «Preguntas con opciones»).")
        appendLine("${InstinctState.KIND}: el estado del teléfono (ver «Estado del teléfono»); no es el resultado de una orden.")
        appendLine("Después de una espera o de una orden programada puede llegar otro evento de la misma orden: ${AckKind.OK.code}, ${AckKind.ERROR.code}, ${AckKind.DISCARDED.code} o ${AckKind.EXPIRED.code}, aunque haya pasado mucho tiempo. Si la persona deshace lo que hiciste, llega ${AckKind.UNDONE.code}.")
        appendLine("En los acuses, orden es tu orden (recortada a ${InstinctAck.MAX_ORDER_CHARS} caracteres), detalle es el resultado y mensaje_id identifica el mensaje tuyo del que salió la orden (un código, nunca el texto).")
        append("Los eventos son informativos: no los respondas con nuevas órdenes, salvo una sola orden corregida después de un ${AckKind.ERROR.code}. Nunca repitas una orden después de ${AckKind.UNCERTAIN.code} ni de ${AckKind.WAITING.code}. Antes de repetir o corregir una orden, consultá la bitácora.")
      }
    }
  }

  /**
   * The answer to «ver catálogo»: the version, the enabled routines and (1.18.0) the hash of the catalog last
   * published in the log, not the whole catalog.
   */
  fun summary(routines: List<String>, publishedHash: String? = null): String =
    "$TITLE · rutinas habilitadas: " + (if (routines.isEmpty()) "ninguna." else routines.joinToString(", ") + ".") +
      (publishedHash?.let { " Catálogo publicado: hash $it." } ?: "")

  internal const val QUESTION_EXAMPLE = "preguntar ¿Preferís café o té? | Café | Té en 2 horas"
  private const val QUESTION_USE = "Preguntá cuando no esté claro qué hacer o cuando la decisión sea personal. Si ya tenés permiso y la acción es clara, hacela directamente. Si una pregunta vence o la descartan, no la repitas ni insistas."
  private const val QUESTION_RESULT = "Requiere bitácora. Las respuestas se anotan ahí aunque hayan llegado por chat y los acuses estén apagados. En la fila «respuesta», evento_id identifica la ejecución, orden contiene el texto de la pregunta y detalle contiene la opción elegida, «vencida» o «descartada»."
  internal const val DECISION_EXAMPLE = "decidir ¿Qué preferís? | Silencio => silenciar el teléfono | No molestar => activar no molestar"
  private const val DECISION_RULE = "En «decidir», cada botón autoriza una sola acción de la lista permitida que pueda ejecutarse sin abrir una pantalla. El toque vale como confirmación. Se revisan los permisos y Nunca al ejecutar. La acción deja otra fila «respuesta» en la bitácora con su resultado y el id de la pregunta. La pantalla bloqueada oculta texto y opciones por defecto."
  private const val CONTEXT_RULE = "Creá, consultá y administrá reglas por el buzón firmado. Hasta tres condiciones unidas con «y», una acción permitida sin interfaz o «avisarme» y hasta 12 reglas durante un máximo de 30 días. Se disparan al cumplirse las condiciones, según los eventos que Android entregue, incluso sin conexión con Instinct. Conservan los permisos y la autonomía del teléfono."


  const val PLAN_RULE = "Un plan es una sola orden en una línea: hasta ${InstinctPlanSpec.MAX_ACTIONS} acciones sin interfaz (contando alternativas y opciones), ${InstinctPlanSpec.MAX_STEPS} pasos, una decisión y ${InstinctPlanSpec.MAX_ACTIVE} planes activos. Insto ejecuta cada paso después de comprobar el anterior. Si una acción falla, el plan se detiene, salvo que hayas escrito «si falla <acción>». Si falta confirmar el resultado, Insto consulta el estado real. Cuando se cumple, sigue. Solo reintenta una vez el volumen absoluto cuando puede comprobar que el parlante integrado, el nivel y la escala siguen como antes. Si la duda sigue, suspende el plan y pide revisión. «reanudar plan <id>» omite el paso incierto y sus dependientes explícitos; «cancelar plan <id>» termina el plan. Lo que «guardar» guarda se restaura con «restaurar», o al cancelarse, fallar o vencer el plan, salvo que la persona lo haya cambiado después. Vence a las 12 horas, o con «hasta las HH:MM» (máximo 24 horas). La autonomía se aplica al plan entero: con Confirmar la persona lo aprueba una vez; con Nunca se rechaza."
  const val PLAN_RESULT = "El acuse de la orden dice «Plan <id> aceptado». El progreso y el resultado final van a la bitácora como eventos de tipo «plan» (orden: «plan <id> · <paso> · <texto>», detalle: estado y resultado), nunca al chat. «ver planes» y «estado de plan <id>» los muestran."

  const val TRIGGER_RULE = "Para algo que depende de un momento del día o del teléfono, preferí dejar un disparador antes que vigilar el estado y mandar la orden después."

  const val LIVE_RULE = "Las reglas vigentes están en GET <URL>?token=<token>&action=catalogo. Leelo al empezar y cada vez que una orden dé un error inesperado; si el hash cambió, las reglas de ahí mandan sobre este texto."

  /** The fixed blockers that always wait, for the JSON catalog. Same facts as [InstinctAutonomyCatalog]. */
  val FIXED_BLOCKERS: List<String> = listOf(
    "el teléfono está bloqueado o la pantalla está apagada y la acción abre una pantalla; las acciones sin interfaz pueden ejecutarse",
    "la orden abre otra app y el servicio de Accesibilidad de Insto no está activo",
    "más de 6 órdenes en 10 minutos",
    "una orden programada que se dispara con más de ${InstinctDeferral.LATE_MILLIS / 60_000} minutos de atraso",
    "la acción necesita algo de la persona: un permiso, confirmar una hora que ya pasó o elegir entre varias apps",
  )

  val FORBIDDEN: List<String> = listOf("llamadas", "mensajes de cualquier tipo", "enviar o compartir archivos", "guardar lugares",
    "navegación", "rutinas que no están habilitadas", "varias acciones juntas fuera de un plan")

  private fun level(value: InstinctAutonomy) = value.name.lowercase()

  /**
   * The catalog as JSON (1.18.0), from THE SAME sources as [text]: the allowlist and its examples
   * ([InstinctProposalValidator]), the forms, the limits, the enabled routines (names only), the autonomy policy, the
   * quiet hours, the fixed blockers, the channels that are on and the versions. Examples go without the «#insto »
   * prefix. Never an address, a token, a key nor an endpoint: nothing here receives one. [parse] gives each example's
   * category.
   */
  fun json(routines: List<String>, autonomy: InstinctAutonomyPolicy = InstinctAutonomyPolicy(),
    channels: InstinctCatalogChannels = InstinctCatalogChannels(), instoVersion: String,
    availability: Map<String, String> = emptyMap(), calendarIds: List<Long> = emptyList(),
    calendarWriteIds: List<Long> = emptyList(), calendarLabels: Map<Long, String> = emptyMap(), routineRevisions: Map<String, String> = emptyMap(),
    capabilityTargets: Map<String, List<String>> = emptyMap(),
    capabilityHealth: Map<String, Map<String, Any>> = emptyMap(),
    operationsHealth: Map<String, Map<String, Any>> = emptyMap(),
    targetHealth: Map<String, Map<String, Any>> = emptyMap(),
    parse: (String) -> PhoneRoute = PhoneCommandParser()::parse): InstinctCatalogJson {
    fun category(order: String) = (parse(order) as? PhoneRoute.Local)?.command?.let(InstinctCategory::of)?.label
    val quiet = autonomy.quiet
    val value = mapOf(
      "protocolo" to TITLE,
      "version_protocolo" to VERSION,
      "version_insto" to instoVersion,
      "prefijo" to "${InstinctProposalProtocol.PREFIX} ",
      "capacidades" to InstinctCapabilities.specs.values.filter {
        it.id != ShortcutResolver.CAPABILITY || capabilityTargets[it.id].orEmpty().isNotEmpty() &&
          autonomy.level(it.category) != InstinctAutonomy.NUNCA
      }.map { spec ->
        InstinctCatalogCanonical.toKotlin(spec.catalog(
          if (autonomy.level(spec.category) == InstinctAutonomy.NUNCA) "nunca"
          else availability[spec.id] ?: if (spec.adapter == InstinctCapabilities.Adapter.OMNIREMOTE)
            InstinctCapabilities.OMNIREMOTE_UNAVAILABLE else "consultar catálogo del teléfono",
          targets = capabilityTargets[spec.id].orEmpty().takeIf { autonomy.level(spec.category) != InstinctAutonomy.NUNCA }.orEmpty()).apply {
          if (channels.mailbox && autonomy.level(spec.category) != InstinctAutonomy.NUNCA) {
            capabilityHealth[spec.id]?.let { put("salud", JSONObject(it)) }
            val targets = capabilityTargets[spec.id].orEmpty().mapNotNull { id -> targetHealth[id]?.let { id to it } }.toMap()
            if (targets.isNotEmpty()) put("salud_destinos", JSONObject(targets))
          }
        })
      },
      "calendarios_habilitados" to calendarIds,
      "calendarios_con_escritura" to calendarWriteIds,
      "calendarios" to calendarIds.map { mapOf("id" to it, "nombre" to calendarLabels[it].orEmpty(), "escritura" to (it in calendarWriteIds)) },
      "plan_json" to InstinctStructuredPlan.contract,
      "integraciones" to mapOf(
        "omniremote" to if (capabilityTargets.keys.any { it.startsWith("omniremote_") }) "ver capacidades y destinos" else "sin interfaz local compatible",
        "appfunctions" to "sin proveedor autorizado"),
      "acciones" to InstinctProposalValidator.immediateExamples.map { mapOf("orden" to it, "categoria" to category(it)) },
      "formas" to mapOf(
        "diferidas" to InstinctProposalValidator.deferredExamples.map { mapOf("orden" to it) },
        "clima" to InstinctProposalValidator.weatherExamples.map { mapOf("orden" to it) },
        "disparadores" to InstinctProposalValidator.triggerExamples.map { mapOf("orden" to it) },
        "recurrentes" to InstinctProposalValidator.recurringExamples.map { mapOf("orden" to it) },
      ),
      "preguntas" to mapOf(
        "forma" to InstinctQuestion.FORM,
        "orden" to QUESTION_EXAMPLE,
        "categoria" to InstinctCategory.PREGUNTAS.label,
        "vencimiento" to listOf("hasta las HH:MM", "en N horas"),
        "respuesta_por" to "bitacora",
        "requiere_bitacora" to true,
        "muestra_contenido_en_pantalla_bloqueada" to channels.showQuestionsOnLockScreen,
        "espera_en_horas_de_silencio" to true,
        "reglas" to listOf(QUESTION_USE, QUESTION_RESULT),
      ),
      "decisiones" to mapOf("forma" to InstinctQuestion.DECISION_FORM, "orden" to DECISION_EXAMPLE,
        "requiere_bitacora" to true, "regla" to DECISION_RULE),
      "planes" to mapOf("forma" to InstinctPlanSpec.FORM, "pasos" to InstinctPlanSpec.STEP_FORMS,
        "ejemplos" to InstinctPlanSpec.EXAMPLES.map { mapOf("orden" to it) }, "progreso_por" to "bitacora",
        "tipo_evento" to InstinctPlanSpec.LOG_KIND, "reglas" to listOf(PLAN_RULE, PLAN_RESULT)),
      "reglas_contextuales" to mapOf("forma" to ContextRuleSpec.FORM, "orden" to ContextRuleSpec.EXAMPLE,
        "condiciones" to ContextRuleSpec.conditionForms, "regla" to CONTEXT_RULE),
      "consultas" to ProposalCheck.QueryKind.entries.map { mapOf("orden" to it.form, "solo_lectura" to it.readOnly) },
      "rutinas" to routines.map { name ->
        mapOf("nombre" to name, "orden" to "$ROUTINE_FORM $name") +
          listOfNotNull(routineRevisions[name]?.let { "revision" to it })
      },
      "limites" to mapOf(
        "lineas_por_mensaje" to InstinctProposalProtocol.MAX_LINES,
        "ordenes_automaticas_cada_10_minutos" to 6,
        "programadas" to InstinctDeferral.MAX_SCHEDULED,
        "diferida_minutos_min" to (InstinctDeferral.MIN_DELAY_MILLIS / 60_000).toInt(),
        "diferida_horas_max" to (InstinctDeferral.MAX_DELAY_MILLIS / 3_600_000).toInt(),
        "atraso_minutos_max" to (InstinctDeferral.LATE_MILLIS / 60_000).toInt(),
        "disparador_horas" to (InstinctTriggers.DEFAULT_EXPIRY_MILLIS / 3_600_000).toInt(),
        "disparador_horas_max" to (InstinctTriggers.MAX_EXPIRY_MILLIS / 3_600_000).toInt(),
        "recurrentes" to InstinctTriggers.MAX_RECURRING,
        "recurrente_dias_max" to InstinctTriggers.MAX_RECURRING_DAYS,
        "lluvia_por_ciento" to WeatherConditions.RAIN_THRESHOLD_PERCENT,
        "rutina_pasos_max" to InstinctRoutines.MAX_STEPS,
        "orden_caracteres_en_acuse" to InstinctAck.MAX_ORDER_CHARS,
        "buzon_ordenes_por_sondeo" to InstinctMailboxPoll.MAX_ORDERS,
        "buzon_antiguedad_minutos_max" to (InstinctMailboxSignature.MAX_AGE_MILLIS / 60_000).toInt(),
        "buzon_futuro_minutos_max" to (InstinctMailboxSignature.MAX_FUTURE_MILLIS / 60_000).toInt(),
        "reglas_contextuales" to ContextRuleSpec.MAX_RULES,
        "regla_dias_max" to ContextRuleSpec.MAX_DAYS,
        "regla_condiciones_max" to 3,
        "disparos_contextuales_cada_10_minutos" to ContextRuleSpec.MAX_FIRINGS,
        "preguntas_pendientes" to InstinctQuestion.MAX_PENDING,
        "preguntas_por_hora" to InstinctQuestion.PER_HOUR,
        "pregunta_caracteres_max" to InstinctQuestion.MAX_TEXT,
        "pregunta_opcion_caracteres_max" to InstinctQuestion.MAX_OPTION,
        "pregunta_opciones_min" to 2,
        "pregunta_opciones_max" to 3,
        "pregunta_horas" to InstinctQuestion.DEFAULT_HOURS,
        "pregunta_horas_max" to InstinctQuestion.MAX_HOURS,
        "avisos_por_hora" to InstinctNotice.PER_HOUR,
        "aviso_caracteres_max" to InstinctNotice.MAX_CHARS,
        "pruebas_cada_10_minutos" to InstinctProbe.PER_PERIOD,
        "plan_acciones_max" to InstinctPlanSpec.MAX_ACTIONS,
        "plan_pasos_max" to InstinctPlanSpec.MAX_STEPS,
        "planes_activos_max" to InstinctPlanSpec.MAX_ACTIVE,
        "plan_horas" to (InstinctPlanSpec.DEFAULT_EXPIRY_MILLIS / 3_600_000).toInt(),
        "plan_horas_max" to (InstinctPlanSpec.MAX_EXPIRY_MILLIS / 3_600_000).toInt(),
        "plan_espera_horas_max" to (InstinctPlanSpec.MAX_WAIT_MILLIS / 3_600_000).toInt(),
      ),
      "autonomia" to InstinctCategory.entries.associate { it.label to level(autonomy.level(it)) },
      "horas_de_silencio" to quiet?.let { mapOf("desde" to QuietHours.clock(it.start), "hasta" to QuietHours.clock(it.end),
        "permite_silenciar" to autonomy.silenceInQuiet) },
      "bloqueos_fijos" to FIXED_BLOCKERS,
      "prohibido" to FORBIDDEN,
      "canales" to mapOf(
        "whatsapp" to channels.whatsapp,
        "bitacora" to channels.log,
        "buzon" to channels.mailbox,
        "timbre" to channels.doorbell,
        "orden_en_timbre" to (channels.doorbell && channels.doorbellDirect),
        "acuses" to when (channels.replyMode) { InstinctReplyMode.NEVER -> "nunca"; InstinctReplyMode.PROBLEMS -> "errores y esperas"; InstinctReplyMode.ALWAYS -> "siempre" },
        "acuses_por" to channels.ackChannel.name.lowercase(),
      ),
      "firma_buzon" to InstinctMailboxSignature.VERSION,
      "entrega_buzon" to listOf("push_directo_si_entra", "referencia_cifrada_id_sha256", "sondeo_de_respaldo"),
      "reglas" to listOf(TRIGGER_RULE, InstinctProbe.RULE),
    )
    val extended = if (channels.mailbox && operationsHealth.isNotEmpty()) value + ("salud_operativa" to operationsHealth) else value
    val canonical = InstinctCatalogCanonical.write(extended)
    return InstinctCatalogJson(canonical, sha256(canonical))
  }
}
