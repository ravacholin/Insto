package com.example.phone

import org.json.JSONObject

/**
 * «Timbre» (1.17.0): a push that only says «hay algo en el buzón». It never carries an order: Insto reads the sheet
 * and verifies the signature exactly as on any poll, so the security model of the signed mailbox does not change.
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
) {
  fun encode(): String = JSONObject().put("confirmed", confirmed).put("informPending", informPending).put("rings", rings).put("ringPolls", ringPolls)
    .apply {
      token?.let { put("token", it) }; distributor?.let { put("distributor", it) }; informedAt?.let { put("informedAt", it) }
      failure?.let { put("failure", it) }; lastRingAt?.let { put("lastRingAt", it) }
    }.toString()

  companion object {
    fun decode(text: String): DoorbellState = runCatching {
      require(text.length <= 8_192)
      val o = JSONObject(text)
      DoorbellState(o.optString("token").takeIf(UnifiedPush::validToken), o.optString("distributor").takeIf { it.isNotEmpty() },
        o.optBoolean("confirmed"), o.optLong("informedAt", 0L).takeIf { it > 0 }, o.optBoolean("informPending"),
        o.optString("failure").takeIf { it.isNotEmpty() }, o.optLong("lastRingAt", 0L).takeIf { it > 0 },
        o.optInt("rings"), o.optInt("ringPolls"))
    }.getOrDefault(DoorbellState())
  }
}

/** What the connector does with each broadcast from a distributor. Content never matters; only the token does. */
object InstinctDoorbellProtocol {
  sealed interface Action {
    /** A token Insto does not know, or a malformed request: ignored, as the spec says. */
    data object Ignore : Action
    /** MESSAGE: ring. [ackId] is acknowledged when the distributor sent one. The payload is never read. */
    data class Ring(val ackId: String?) : Action
    /** NEW_ENDPOINT: save it (or replace it) and tell the script. */
    data class Endpoint(val endpoint: String, val ackId: String?) : Action
    /** NEW_ENDPOINT whose endpoint Insto cannot hand to the script (not https, too long). */
    data class BadEndpoint(val ackId: String?) : Action
    /** REGISTRATION_FAILED: the next registration needs a new token. */
    data class Failed(val reason: String) : Action
    /** UNREGISTERED: delete the endpoint and tell the script. */
    data object Unregistered : Action
  }

  /**
   * [token] is the token in the broadcast, [current] the one Insto registered with. For MESSAGE, [extras] is never
   * consulted beyond the token and the id: whoever sends it and whatever it says, at most the mailbox is polled.
   */
  fun handle(action: String?, token: String?, current: DoorbellState, endpoint: String? = null, id: String? = null,
    reason: String? = null): Action {
    if (current.token == null || token != current.token) return Action.Ignore
    val ack = id?.takeIf(UnifiedPush::validToken)
    return when (action) {
      UnifiedPush.ACTION_MESSAGE -> Action.Ring(ack)
      UnifiedPush.ACTION_NEW_ENDPOINT -> when {
        endpoint.isNullOrEmpty() || endpoint.toByteArray(Charsets.UTF_8).size > UnifiedPush.MAX_ENDPOINT_BYTES -> Action.Ignore
        InstinctDoorbellEndpoint.valid(endpoint) -> Action.Endpoint(endpoint, ack)
        else -> Action.BadEndpoint(ack)
      }
      // A failure for a token that already got an endpoint is ignored.
      UnifiedPush.ACTION_REGISTRATION_FAILED -> if (current.confirmed) Action.Ignore else Action.Failed(failureLabel(reason))
      UnifiedPush.ACTION_UNREGISTERED -> Action.Unregistered
      else -> Action.Ignore
    }
  }

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
  /** A ring-triggered poll that has not finished by then lets the broadcast go (it keeps running). */
  const val POLL_TIMEOUT_MILLIS = 20_000L
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

  fun informBody(token: String, endpoint: String): String = JSONObject().put("token", token).put("action", ACTION).put("endpoint", endpoint).toString()
  fun testBody(token: String): String = JSONObject().put("token", token).put("action", TEST_ACTION).toString()

  sealed interface Answer {
    /** The script saved the endpoint ([saved]) or deleted it. */
    data class Confirmed(val saved: Boolean) : Answer
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
        o.optBoolean("ok", false) && o.optString(ACTION) == "guardado" -> Answer.Confirmed(saved = true)
        o.optBoolean("ok", false) && o.optString(ACTION) == "borrado" -> Answer.Confirmed(saved = false)
        else -> Answer.Failed(NOT_CONFIRMED, retry = false)
      }
    }
  }

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
        else -> "el script no pudo tocar el timbre" + (o.optInt("codigo", 0).takeIf { it > 0 }?.let { " (HTTP $it)" } ?: "")
      }
    }
  }
}

/** The doorbell's lines for the person and for Instinct. Never the endpoint (only its mask in Settings). */
object InstinctDoorbellText {
  const val NO_DISTRIBUTOR = "Instalá ntfy para usar el timbre"
  const val CATALOG_LINE = "Después de publicar una orden en el buzón, Insto la toma en segundos; no hace falta avisar por WhatsApp."

  /** «Endpoint ••••abc123 · avisado al script HH:MM», or what is missing. */
  fun status(endpoint: String?, state: DoorbellState, clock: (Long) -> String): String = when {
    endpoint == null -> "Esperando el endpoint del distribuidor…"
    state.informedAt != null && !state.informPending -> "Endpoint ${InstinctDoorbellEndpoint.mask(endpoint)} · avisado al script ${clock(state.informedAt)}"
    else -> "Endpoint ${InstinctDoorbellEndpoint.mask(endpoint)} · todavía no se avisó al script"
  }

  /** «Timbre: último HH:MM · timbres N · sondeos por timbre N», or why it fails. Never any content. */
  fun diagnosis(state: DoorbellState, distributors: Int, clock: (Long) -> String): String {
    val counts = "timbres ${state.rings} · sondeos por timbre ${state.ringPolls}"
    return when {
      distributors == 0 -> "Timbre: sin distribuidor (instalá ntfy) · $counts"
      state.failure != null -> "Timbre: no funciona (motivo: ${state.failure}) · $counts"
      state.lastRingAt != null -> "Timbre: último ${clock(state.lastRingAt)} · $counts"
      else -> "Timbre: todavía no sonó · $counts"
    }
  }
}
