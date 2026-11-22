package com.example.phone

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * «Timbre» (1.17.0): a push that says «hay algo en el buzón». Since 1.18.0 it may also carry the signed order itself
 * («Incluir la orden en el timbre», off by default): it is verified exactly as on any poll (signature, time window, id
 * seen), so the security model of the signed mailbox does not change; only the signature authorizes an order.
 * The transport is UnifiedPush (spec AND_3, connector side written by hand, no library) with the ntfy app as the
 * distributor; the Apps Script rings it when it stores a new order. The pure part: the protocol's names, the rules
 * for the endpoint, the registration state, what to do when a ring arrives, the backup interval and the bodies for
 * the script. Nothing here touches Android. See docs/INSTINCT-PROPUESTAS.md, «Timbre».
 */
object UnifiedPush {
  // Distributor side: what Insto sends.
  const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
  const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
  const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
  // Connector side: what Insto receives.
  const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
  const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
  const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
  const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
  const val ACTION_TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
  const val ACTION_RAISE_TO_FOREGROUND = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

  const val EXTRA_TOKEN = "token"
  const val EXTRA_ENDPOINT = "endpoint"
  const val EXTRA_ID = "id"
  const val EXTRA_REASON = "reason"
  const val EXTRA_MESSAGE = "message"
  /** AND_3: the push's content, as bytes. Insto reads it only to look for a signed order (1.18.0); it is never logged. */
  const val EXTRA_BYTES_MESSAGE = "bytesMessage"
  /** AND_3: the PendingIntent that tells the distributor who registers, on Android older than 14. */
  const val EXTRA_PI = "pi"
  /** AND_2: the package name, required by distributors that predate AND_3. AND_3 ones identify the app by itself. */
  const val EXTRA_APPLICATION = "application"
  /** AND_3: the PendingIntent points at this package, which does not exist: it only carries Insto's identity. */
  const val DUMMY_APP = "org.unifiedpush.dummy_app"

  /** Spec limits: a token or an id of at most 100 bytes, an endpoint of at most 1000. */
  const val MAX_TOKEN_BYTES = 100
  const val MAX_ENDPOINT_BYTES = 1000

  /** What the distributor sees next to Insto when it lists the apps registered (at most 100 bytes). */
  const val DESCRIPTION = "Insto: timbre del buzón de Instinct"

  fun validToken(token: String?): Boolean = !token.isNullOrEmpty() && token.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES
}

/** The endpoint the distributor gave: where the Apps Script POSTs to ring. Never logged, never shown whole. */
object InstinctDoorbellEndpoint {
  /** What the Apps Script accepts: `https://` and at most 500 characters. */
  const val MAX_CHARS = 500

  fun valid(endpoint: String?): Boolean = endpoint != null && endpoint.length in 9..MAX_CHARS &&
    endpoint.startsWith("https://") && endpoint.none { it.isWhitespace() || it.isISOControl() }

  /** «••••» and the last 6 characters. */
  fun mask(endpoint: String?): String = if (endpoint.isNullOrEmpty()) "sin endpoint" else "••••" + endpoint.takeLast(6)
}

/**
 * The registration as Insto keeps it in `instinct-doorbell-state.json` (the endpoint itself lives in its own file).
 * [token] is the connection token of the current registration (random, one per registration); null after
 * UNREGISTER. [confirmed] is true once NEW_ENDPOINT arrived for it: a REGISTRATION_FAILED for a token that already
 * got an endpoint is ignored, as the spec says. Counters and times carry no content.
 */
data class DoorbellState(
  val token: String? = null,
  val distributor: String? = null,
  val confirmed: Boolean = false,
  /** Wall-clock millis when the script confirmed the endpoint now saved; null when it never did. */
  val informedAt: Long? = null,
  /** The endpoint must still reach the script (new, changed, or deleted when it is empty). */
  val informPending: Boolean = false,
  /** Why the doorbell does not work, in Spanish, without any content; null when it does. */
  val failure: String? = null,
  val lastRingAt: Long? = null,
  val rings: Int = 0,
  val ringPolls: Int = 0,
  /** 1.18.0: the script confirmed «Incluir la orden en el timbre» on (it answered `conOrden:true`). */
  val withOrderConfirmed: Boolean = false,
  /** Orders that came inside the doorbell and ran (their claim won). */
  val directOrders: Int = 0,
  /** Payloads that were not an order, and orders inside the doorbell with a bad signature or out of time. */
  val badPayloads: Int = 0,
  /** 1.19.0: pushes that repeated an order already taken (by a poll or another push). */
  val duplicates: Int = 0,
  /** Broadcasts let go because the receiving queue was full. */
  val queueRejected: Int = 0,
  /** Orders claimed by a process that died before handing them over: logged as «incierta», never repeated. */
  val uncertain: Int = 0,
  /** Writes that failed (the inbox, the record of seen ids). */
  val storageFailures: Int = 0,
  /** Wall-clock millis of the last order of the doorbell that was verified and handed over. */
  val lastDirectAt: Long? = null,
  /** The last transport error, in Spanish, without any content; null when none. */
  val lastError: String? = null,
  /** 1.19.0: the script saved the sealed doorbell: from now on a clear push can only poll. */
  val encryptionConfirmed: Boolean = false,
  /** The script has a relay configured for the order encrypted inside the doorbell. */
  val relayConfirmed: Boolean = false,
  /** The script rings for every new order (1.19.0): no follow-up poll is needed after a direct order. */
  val perOrderRing: Boolean = false,
  /** The endpoint comes from a fallback distributor and may change soon. */
  val temporaryEndpoint: Boolean = false,
  /** A short hash of the registration's public keys, to tell when they change. Never the keys. */
  val keysFingerprint: String? = null,
  /** Pushes that arrived encrypted and decrypted, in clear, and encrypted that could not be decrypted. */
  val encryptedPushes: Int = 0,
  val plainPushes: Int = 0,
  val decryptFailures: Int = 0,
  /** Ids of direct orders the sheet must still hear as taken (`POST tomadas`); never anything else of the order. */
  val pendingTaken: List<String> = emptyList(),
) {
  fun encode(): String = JSONObject().put("confirmed", confirmed).put("informPending", informPending).put("rings", rings).put("ringPolls", ringPolls)
    .put("withOrderConfirmed", withOrderConfirmed).put("directOrders", directOrders).put("badPayloads", badPayloads)
    .put("duplicates", duplicates).put("queueRejected", queueRejected).put("uncertain", uncertain).put("storageFailures", storageFailures)
    .put("encryptionConfirmed", encryptionConfirmed).put("relayConfirmed", relayConfirmed).put("perOrderRing", perOrderRing)
    .put("temporaryEndpoint", temporaryEndpoint).put("encryptedPushes", encryptedPushes).put("plainPushes", plainPushes)
    .put("decryptFailures", decryptFailures)
    .apply {
      token?.let { put("token", it) }; distributor?.let { put("distributor", it) }; informedAt?.let { put("informedAt", it) }
      failure?.let { put("failure", it) }; lastRingAt?.let { put("lastRingAt", it) }; lastDirectAt?.let { put("lastDirectAt", it) }
      lastError?.let { put("lastError", it) }; keysFingerprint?.let { put("keysFingerprint", it) }
      if (pendingTaken.isNotEmpty()) put("pendingTaken", JSONArray(pendingTaken))
    }.toString()

  companion object {
    const val MAX_PENDING_TAKEN = 50

    fun decode(text: String): DoorbellState = runCatching {
      require(text.length <= 16_384)
      val o = JSONObject(text)
      DoorbellState(o.optString("token").takeIf(UnifiedPush::validToken), o.optString("distributor").takeIf { it.isNotEmpty() },
        o.optBoolean("confirmed"), o.optLong("informedAt", 0L).takeIf { it > 0 }, o.optBoolean("informPending"),
        o.optString("failure").takeIf { it.isNotEmpty() }, o.optLong("lastRingAt", 0L).takeIf { it > 0 },
        o.optInt("rings"), o.optInt("ringPolls"), o.optBoolean("withOrderConfirmed"), o.optInt("directOrders"), o.optInt("badPayloads"),
        o.optInt("duplicates"), o.optInt("queueRejected"), o.optInt("uncertain"), o.optInt("storageFailures"),
        o.optLong("lastDirectAt", 0L).takeIf { it > 0 }, o.optString("lastError").takeIf { it.isNotEmpty() }?.take(200),
        o.optBoolean("encryptionConfirmed"), o.optBoolean("relayConfirmed"), o.optBoolean("perOrderRing"), o.optBoolean("temporaryEndpoint"),
        o.optString("keysFingerprint").takeIf { it.isNotEmpty() }, o.optInt("encryptedPushes"), o.optInt("plainPushes"), o.optInt("decryptFailures"),
        o.optJSONArray("pendingTaken")?.let { a -> (0 until minOf(a.length(), MAX_PENDING_TAKEN)).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty())
    }.getOrDefault(DoorbellState())
  }
}

/**
 * The doorbell's content when it carries the order (1.18.0): exactly `{"v":1,"id":…,"at":…,"lines":[…],"sig":…}`, as the
 * Apps Script writes it. A strict, pure parser: anything else (`insto-timbre` included) is no order, and the doorbell
 * then polls as before. Parsing proves nothing: the order still goes through [InstinctMailboxSignature.verify].
 */
object InstinctDoorbellPayload {
  /** What the script sends without the order. */
  const val PLAIN = "insto-timbre"
  /** ntfy turns bigger messages into attachments; the script never sends more than 3500 bytes. */
  const val MAX_BYTES = 4_096
  const val MAX_LINE_CHARS = 300
  private val KEYS = setOf("v", "id", "at", "lines", "sig")
  private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
  private val HEX = Regex("^[0-9a-f]{64}$")

  /** The signed order in [payload], or null for anything that is not exactly one. Never throws, never logs. */
  fun parse(payload: ByteArray?): MailboxOrder? = text(payload)?.let(::parse)

  fun parse(text: String): MailboxOrder? = runCatching {
    val tokener = JSONTokener(text)
    val o = tokener.nextValue() as? JSONObject ?: return null
    // Nothing after the object.
    if (tokener.nextClean() != 0.toChar()) return null
    if (o.keys().asSequence().toSet() != KEYS) return null
    val v = o.opt("v")
    if (!((v is Int && v == 1) || (v is Long && v == 1L))) return null
    val id = o.opt("id") as? String ?: return null
    val at = o.opt("at") as? String ?: return null
    val sig = o.opt("sig") as? String ?: return null
    val array = o.opt("lines") as? JSONArray ?: return null
    if (!UUID.matches(id) || !HEX.matches(sig)) return null
    if (at != at.trim() || InstinctMailboxSignature.instant(at) == null) return null
    if (array.length() !in 1..InstinctProposalProtocol.MAX_LINES) return null
    val lines = (0 until array.length()).map { array.opt(it) as? String ?: return null }
    if (lines.any { it.length > MAX_LINE_CHARS || it.contains('\n') || it.contains('\r') }) return null
    MailboxOrder(id, at, lines, sig)
  }.getOrNull()

  /** No content, or the plain «insto-timbre»: a doorbell without the order, not a malformed one. */
  fun plain(payload: ByteArray?): Boolean = payload == null || payload.isEmpty() || text(payload)?.trim() == PLAIN

  /** Strict UTF-8, at most [MAX_BYTES]; null otherwise. */
  private fun text(payload: ByteArray?): String? {
    if (payload == null || payload.isEmpty() || payload.size > MAX_BYTES) return null
    return runCatching {
      Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(payload)).toString()
    }.getOrNull()
  }
}

/** The connector's labels and the choice of distributor (1.19.0: the official library handles the broadcasts). */
object InstinctDoorbellProtocol {
  /** The spec's reasons, as the diagnosis says them. */
  fun failureLabel(reason: String?): String = when (reason) {
    "NETWORK" -> "registro fallido: el distribuidor no tenía red"
    "ACTION_REQUIRED" -> "registro fallido: ntfy necesita que hagas algo en la app"
    "VAPID_REQUIRED" -> "registro fallido: el servidor de ntfy pide VAPID"
    "INTERNAL_ERROR" -> "registro fallido: error interno del distribuidor"
    else -> "registro fallido"
  }

  /** Which distributor to use: the only one installed, or the chosen one if it is still installed; null otherwise. */
  fun pick(installed: List<String>, chosen: String?): String? = when {
    chosen != null && chosen in installed -> chosen
    installed.size == 1 -> installed.single()
    else -> null
  }
}

/**
 * When a ring arrives. The mailbox is polled right away unless a poll started less than [MIN_SPACING_MILLIS] ago
 * (then it is polled when the window ends), a poll is running (then it polls once more when it finishes, because
 * that poll may have read the sheet before the order was written) or there is no network (a job polls when there
 * is). A ring is never dropped for the spacing.
 */
class InstinctMailboxGate(private val clock: () -> Long) {
  sealed interface Ring {
    data object PollNow : Ring
    data object AfterCurrent : Ring
    data class Later(val delayMillis: Long) : Ring
    data object WhenOnline : Ring
  }

  private var running = false
  private var again = false
  private var lastStart = 0L

  /** A ring arrived. With [AfterCurrent] the running poll is already told to poll again. */
  @Synchronized fun ring(online: Boolean): Ring {
    if (!online) return Ring.WhenOnline
    if (running) { again = true; return Ring.AfterCurrent }
    val elapsed = clock() - lastStart
    if (lastStart != 0L && elapsed in 0 until MIN_SPACING_MILLIS) return Ring.Later(MIN_SPACING_MILLIS - elapsed)
    // Reserved now: a second ring before the poll begins waits for the window instead of polling twice.
    lastStart = clock()
    return Ring.PollNow
  }

  /** A poll starts (any poll: the job, «al abrir Insto» or a ring). */
  @Synchronized fun begin() { running = true; again = false; lastStart = clock() }

  /** The poll ended. True when a ring arrived meanwhile: poll once more. */
  @Synchronized fun end(): Boolean { running = false; return again.also { again = false } }

  @Synchronized fun isRunning(): Boolean = running

  /** Tests only. */
  @Synchronized internal fun reset() { running = false; again = false; lastStart = 0L }

  companion object {
    /** Between two polls for a ring; immediate polls of other kinds keep their 20 seconds. */
    const val MIN_SPACING_MILLIS = 5_000L
  }
}

object InstinctDoorbellPolicy {
  /** While the doorbell rings, the backup poll is this, whatever was chosen: it saves Apps Script quota. */
  const val BACKUP_MINUTES = 15
  /** Without any ring for this long, the backup poll goes back to the chosen interval. */
  const val QUIET_MILLIS = 24 * 60 * 60_000L
  /**
   * After an order that came inside the doorbell (1.18.0), a poll about this much later: it marks the order as taken in
   * the sheet and picks up any other pending one. It respects [InstinctMailboxGate].
   */
  const val FOLLOW_UP_MILLIS = 60_000L
  /** The rescue job waits at most this long for the polls it started (1.19.0). */
  const val JOB_WAIT_MILLIS = 60_000L
  /** «Probar timbre» waits this long for the ring. */
  const val TEST_WAIT_MILLIS = 30_000L

  /** The backup interval: 15 minutes while the doorbell is active and rang in the last 24 hours, the chosen one otherwise. */
  fun backupMinutes(chosen: Int, active: Boolean, lastRingAt: Long?, now: Long): Int =
    if (active && lastRingAt != null && now - lastRingAt in 0 until QUIET_MILLIS) maxOf(chosen, BACKUP_MINUTES) else chosen
}

/** What the Apps Script answers about the doorbell. An old script that does not know the actions confirms nothing. */
object InstinctDoorbellScript {
  const val ACTION = "timbre"
  const val TEST_ACTION = "probar-timbre"
  const val NOT_CONFIRMED = "el script no confirmó el endpoint"

  /**
   * 1.19.0: the plain doorbell sealed for Insto's keys ([timbre], base64url) and, only with the order in the doorbell,
   * the public keys the relay needs to encrypt it.
   */
  data class Sealed(val timbre: String, val p256dh: String? = null, val auth: String? = null)

  /**
   * [withOrder] (1.18.0) is always sent; a script older than 1.18.0 ignores it and does not repeat it. `cifrado`
   * (1.19.0) is always sent too: the sealed doorbell, or null to delete it. An older script ignores it.
   */
  fun informBody(token: String, endpoint: String, withOrder: Boolean = false, sealed: Sealed? = null): String =
    JSONObject().put("token", token).put("action", ACTION).put("endpoint", endpoint).put(WITH_ORDER, withOrder && endpoint.isNotEmpty())
      .put(SEALED, sealed?.takeIf { endpoint.isNotEmpty() }?.let { s ->
        JSONObject().put("timbre", s.timbre).apply { if (s.p256dh != null && s.auth != null) put("p256dh", s.p256dh).put("auth", s.auth) }
      } ?: JSONObject.NULL).toString()
  const val SEALED = "cifrado"
  const val WITH_ORDER = "conOrden"
  const val OLD_SCRIPT_WITH_ORDER = "el Apps Script no confirmó «Incluir la orden en el timbre»: pegá el instinct-bitacora.gs de 1.18.0 e implementá una versión nueva. Mientras tanto, el timbre sigue sin la orden"

  fun testBody(token: String): String = JSONObject().put("token", token).put("action", TEST_ACTION).toString()

  sealed interface Answer {
    /**
     * The script saved the endpoint ([saved]) or deleted it. [withOrder] is the `conOrden` it repeated (1.18.0), or
     * null when it did not repeat any: a script older than 1.18.0.
     */
    data class Confirmed(val saved: Boolean, val withOrder: Boolean? = null, val sealed: Boolean? = null, val relay: Boolean? = null,
      val perOrder: Boolean? = null) : Answer
    /** [retry]: a network or HTTP failure; otherwise it waits for the person (token, an old script, a bad endpoint). */
    data class Failed(val reason: String, val retry: Boolean) : Answer
  }

  /** `{ok:true, timbre:"guardado"|"borrado"}`; `{ok:true}` alone is a script older than 1.17.0. */
  fun parseInform(raw: InstinctLogClient.Raw): Answer = when (raw) {
    is InstinctLogClient.Raw.Failed -> Answer.Failed(raw.reason,
      retry = raw.failure != InstinctLogClient.Failure.TOKEN && raw.failure != InstinctLogClient.Failure.NOT_CONFIGURED)
    is InstinctLogClient.Raw.Body -> {
      val o = raw.text?.let { runCatching { JSONObject(it) }.getOrNull() }
      when {
        o == null -> Answer.Failed(InstinctLogClient.Failure.INVALID.label, retry = true)
        !o.optBoolean("ok", false) && o.optString("error") == "token" -> Answer.Failed(InstinctLogClient.Failure.TOKEN.label, retry = false)
        !o.optBoolean("ok", false) && o.optString("error") == "endpoint" -> Answer.Failed("el script rechazó el endpoint", retry = false)
        !o.optBoolean("ok", false) && o.optString("error") == SEALED -> Answer.Failed("el script rechazó el timbre cifrado", retry = false)
        o.optBoolean("ok", false) && o.optString(ACTION) == "guardado" ->
          Answer.Confirmed(saved = true, withOrder(o), o.opt(SEALED) as? Boolean, o.opt("rele") as? Boolean, o.opt("cadaOrden") as? Boolean)
        o.optBoolean("ok", false) && o.optString(ACTION) == "borrado" -> Answer.Confirmed(saved = false, withOrder(o))
        else -> Answer.Failed(NOT_CONFIRMED, retry = false)
      }
    }
  }

  private fun withOrder(o: JSONObject): Boolean? = (o.opt(WITH_ORDER) as? Boolean)

  /** `{ok:true, timbre:true, codigo:N}` when the script rang; the reason in Spanish otherwise. Null: it rang. */
  fun testFailure(raw: InstinctLogClient.Raw): String? = when (raw) {
    is InstinctLogClient.Raw.Failed -> "no se pudo hablar con el script (motivo: ${raw.reason})"
    is InstinctLogClient.Raw.Body -> {
      val o = raw.text?.let { runCatching { JSONObject(it) }.getOrNull() }
      when {
        o == null -> "respuesta inválida del script"
        o.optString("error") == "token" -> "token rechazado"
        o.optString("error") == "sin-endpoint" -> "el script no tiene el endpoint guardado"
        o.optBoolean("ok", false) && o.optBoolean(ACTION, false) -> null
        o.optBoolean("ok", false) && !o.has(ACTION) -> "el script no conoce «probar-timbre»: actualizalo"
        // -1: UrlFetchApp failed before reaching ntfy. Almost always the script lacks the authorization for it.
        o.optInt("codigo", 0) < 0 -> "el script no pudo conectarse a ntfy" + (o.optString("detalle").takeIf { it.isNotBlank() }
          ?.let { " (Google dice: ${InstinctAck.flat(it).take(160)})" } ?: "") +
          ". Si es un permiso, abrí el Apps Script, ejecutá una vez «autorizarTimbre», aceptá y volvé a implementar una versión nueva"
        else -> "el script no pudo tocar el timbre" + (o.optInt("codigo", 0).takeIf { it > 0 }?.let { " (HTTP $it)" } ?: "")
      }
    }
  }
}

/** The doorbell's lines for the person and for Instinct. Never the endpoint (only its mask in Settings). */
object InstinctDoorbellText {
  const val NO_DISTRIBUTOR = "Instalá ntfy para usar el timbre"
  const val CATALOG_LINE = "Después de publicar una orden en el buzón, Insto la toma en segundos; no hace falta avisar por WhatsApp."
  /** With «Incluir la orden en el timbre» on (1.18.0). */
  const val CATALOG_DIRECT_LINE = "La persona activó que la orden firmada viaje también dentro del timbre, para que Insto la ejecute apenas llega. Para vos no cambia nada: publicala en el buzón igual que siempre, con la misma firma."
  const val WITH_ORDER_LABEL = "Incluir la orden en el timbre"
  const val WITH_ORDER_DESCRIPTION = "Más rápido. ntfy puede ver las líneas de la orden, pero no cambiarlas: la firma lo impide. Con tu propio servidor ntfy no las ve nadie más."

  /** Under «Incluir la orden en el timbre», whether the script already knows. */
  fun withOrderStatus(state: DoorbellState): String = when {
    state.informPending -> "Avisándole al script…"
    state.withOrderConfirmed -> "El script lo confirmó: las órdenes nuevas viajan en el timbre."
    else -> InstinctDoorbellScript.OLD_SCRIPT_WITH_ORDER.replaceFirstChar { it.uppercaseChar() } + "."
  }

  /** 1.19.0: whether the doorbell travels encrypted (RFC 8291), and whether the order can travel inside it. */
  fun encryption(state: DoorbellState, hasEndpoint: Boolean, withOrder: Boolean): String = when {
    !hasEndpoint -> "Cifrado: esperando el registro con el distribuidor."
    state.informPending -> "Cifrado: avisándole al script…"
    !state.encryptionConfirmed -> "Cifrado: el script todavía no lo usa (Apps Script anterior a 1.19.0). El timbre viaja sin cifrar: modo heredado, " +
      "no conforme con UnifiedPush (RFC 8291). Pegá el instinct-bitacora.gs nuevo e implementá una versión nueva."
    withOrder && state.relayConfirmed -> "Cifrado RFC 8291: activo. La orden viaja cifrada dentro del timbre (relé configurado) y la firma la autoriza."
    withOrder -> "Cifrado RFC 8291: activo para el timbre. Sin relé configurado en el script, la orden no viaja en el timbre: Insto la lee de la planilla."
    else -> "Cifrado RFC 8291: activo. Un timbre sin cifrar ya no puede ejecutar nada: solo hace revisar el buzón."
  }

  /** «Endpoint ••••abc123 · avisado al script HH:MM», or what is missing. */
  fun status(endpoint: String?, state: DoorbellState, clock: (Long) -> String): String = when {
    endpoint == null -> "Esperando el endpoint del distribuidor…"
    state.informedAt != null && !state.informPending -> "Endpoint ${InstinctDoorbellEndpoint.mask(endpoint)} · avisado al script ${clock(state.informedAt)}"
    else -> "Endpoint ${InstinctDoorbellEndpoint.mask(endpoint)} · todavía no se avisó al script"
  }

  /**
   * «Timbre: último HH:MM · timbres N · sondeos por timbre N · órdenes directas N · payload inválido N», or why it
   * fails. Never any content: neither the payload nor the order.
   */
  fun diagnosis(state: DoorbellState, distributors: Int, clock: (Long) -> String): String {
    val counts = "timbres ${state.rings} · sondeos por timbre ${state.ringPolls} · órdenes directas ${state.directOrders} · payload inválido ${state.badPayloads}" +
      (if (state.duplicates > 0) " · duplicados descartados ${state.duplicates}" else "") +
      (if (state.uncertain > 0) " · inciertas ${state.uncertain}" else "") +
      (state.lastError?.let { " · último error: $it" } ?: "")
    return when {
      distributors == 0 -> "Timbre: sin distribuidor (instalá ntfy) · $counts"
      state.failure != null -> "Timbre: no funciona (motivo: ${state.failure}) · $counts"
      state.lastRingAt != null -> "Timbre: último ${clock(state.lastRingAt)} · $counts"
      else -> "Timbre: todavía no sonó · $counts"
    }
  }
}
