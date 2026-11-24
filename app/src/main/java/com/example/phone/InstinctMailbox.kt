package com.example.phone

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Buzón firmado» (1.16.0): orders from Instinct through the same Google Sheet, signed with HMAC-SHA256 under a key
 * only Insto and Instinct know. The Apps Script only stores them; it never sees the key, so neither the log's token
 * nor access to the sheet is enough to inject an order. The pure part: the key rules, the canonical string, the
 * signature, the verification, the persistent record of seen ids and the poll's bookkeeping. Nothing here touches
 * Android. See docs/INSTINCT-PROPUESTAS.md, «Buzón firmado».
 */
enum class ProposalSource { WHATSAPP, BUZON }

/** «Aceptar órdenes de»: WhatsApp and the mailbox (the default) or only the mailbox. */
enum class InstinctMailboxAccept { WHATSAPP_Y_BUZON, SOLO_BUZON }

object InstinctMailboxKey {
  const val MIN = 32
  const val MAX = 200
  private val VALID = Regex("^[\\x21-\\x7e]{$MIN,$MAX}$")

  /** 32 to 200 printable ASCII characters, without spaces. */
  fun valid(key: String): Boolean = VALID.matches(key)

  /** 32 random bytes from [SecureRandom], base64url without padding (43 characters). */
  fun generate(random: SecureRandom = SecureRandom()): String = base64Url(ByteArray(32).also(random::nextBytes))

  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

  /**
   * Base64url (RFC 4648 §5) without padding. Written here because `java.util.Base64` needs Android 8 and Insto
   * supports Android 7; `android.util.Base64` would not run in the JVM tests.
   */
  internal fun base64Url(bytes: ByteArray): String = buildString((bytes.size * 4 + 2) / 3) {
    var i = 0
    while (i < bytes.size) {
      val b0 = bytes[i].toInt() and 0xff
      val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
      val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
      val n = (b0 shl 16) or (b1 shl 8) or b2
      append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
      if (i + 1 < bytes.size) append(ALPHABET[(n shr 6) and 63])
      if (i + 2 < bytes.size) append(ALPHABET[n and 63])
      i += 3
    }
  }

  /** «••••abcd»: never the whole key. */
  fun mask(key: String?): String = if (key.isNullOrEmpty()) "sin clave" else "••••" + key.takeLast(4)
}

/** One order as the mailbox returns it. [lines] are the «#insto …» lines exactly as signed. */
data class MailboxOrder(val id: String, val at: String, val lines: List<String>, val sig: String)

object InstinctMailboxSignature {
  const val VERSION = "insto-v4"
  const val MAX_AGE_MILLIS = 15 * 60_000L
  const val MAX_FUTURE_MILLIS = 2 * 60_000L
  private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
  private val HEX = Regex("^[0-9a-f]{64}$")

  /** `insto-v4\n<id>\n<at>\n<línea1>\n<línea2>…`: what is signed, byte for byte (UTF-8). */
  fun canonical(id: String, at: String, lines: List<String>): String = (listOf(VERSION, id, at) + lines).joinToString("\n")

  /** HMAC-SHA256 of [canonical] under [key] (its UTF-8 bytes), in lowercase hex. */
  fun sign(key: String, canonical: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
    return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  /** Compares in constant time ([MessageDigest.isEqual]); a malformed signature is never equal. */
  fun matches(expected: String, given: String): Boolean =
    HEX.matches(given) && MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), given.toByteArray(Charsets.US_ASCII))

  /** ISO-8601 with a zone («2026-11-21T09:30:00-03:00», «…Z», optional milliseconds); null otherwise. */
  fun instant(at: String): Long? {
    val text = at.trim()
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?(Z|[+-]\\d{2}:\\d{2})$").matches(text)) return null
    val pattern = if (text.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ssXXX"
    val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
    val position = ParsePosition(0)
    val date = format.parse(text, position) ?: return null
    return if (position.index == text.length) date.time else null
  }

  private fun validLines(lines: List<String>): Boolean {
    if (lines.size !in 1..InstinctProposalProtocol.MAX_LINES * 3) return false
    if (lines.any(InstinctStructuredPlan::isWireLine) && lines.size > InstinctProposalProtocol.MAX_LINES) return false
    var bytes = 0
    for (line in lines) {
      if (line.length > InstinctStructuredPlan.MAX_LINE_BYTES || line.any { it == '\n' || it == '\r' }) return false
      val size = line.toByteArray(Charsets.UTF_8).size
      val limit = if (InstinctStructuredPlan.isWireLine(line)) InstinctStructuredPlan.MAX_LINE_BYTES else 2048
      if (size > limit) return false
      bytes += size
    }
    return bytes <= InstinctStructuredPlan.MAX_ORDER_BYTES
  }

  enum class Verdict { VALID, BAD_SIGNATURE, EXPIRED, REPEATED }

  /**
   * In this order: the signature (anything malformed counts as a bad signature, so it is never answered), then the
   * time window (more than 15 minutes old or 2 minutes in the future), then an id already seen.
   */
  fun verify(key: String, order: MailboxOrder, now: Long, seen: (String) -> Boolean): Verdict {
    if (!UUID.matches(order.id) || !validLines(order.lines))
      return Verdict.BAD_SIGNATURE
    if (!matches(sign(key, canonical(order.id, order.at, order.lines)), order.sig)) return Verdict.BAD_SIGNATURE
    val at = instant(order.at) ?: return Verdict.BAD_SIGNATURE
    if (now - at > MAX_AGE_MILLIS || at - now > MAX_FUTURE_MILLIS) return Verdict.EXPIRED
    if (seen(order.id.lowercase())) return Verdict.REPEATED
    return Verdict.VALID
  }

  /** The log's short id for a mailbox order: from the SHA-256 of its id, never anything else. */
  fun messageId(orderId: String): String = InstinctLogEvents.messageId(sha256(orderId.lowercase()))
}

/**
 * The ids already taken, kept [RETENTION_MILLIS] in one file written atomically: the dedupe survives a restart.
 * [clock] is wall-clock millis. Every instance on the same file shares one lock (1.18.0), so the poll and an order
 * that came inside the doorbell can never both take the same id: [claim] checks and marks in one section.
 */
class InstinctMailboxSeen(private val file: File, private val clock: () -> Long = System::currentTimeMillis) : InstinctOrderClaims {
  private val lock: Any = synchronized(LOCKS) { LOCKS.getOrPut(file.absolutePath) { Any() } }

  fun read(): Map<String, Long> = synchronized(lock) {
    if (!file.exists()) emptyMap() else runCatching {
      val o = JSONObject(file.readText().also { require(it.length <= 262_144) })
      o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())
  }

  override fun contains(id: String): Boolean = synchronized(lock) { id.lowercase() in fresh(read()) }

  /** Marks [ids] seen on disk. Returns only after the write, so a dispatch after it never repeats an order. */
  fun markAll(ids: Collection<String>) = synchronized(lock) {
    if (ids.isEmpty()) return@synchronized
    write(fresh(read()).toMutableMap().apply { val now = clock(); ids.forEach { this[it.lowercase()] = now } })
  }

  /**
   * Takes [id] if nobody took it: checks and marks it on disk in the same section. True only for the one caller that
   * wins; whoever gets false must not run the order. A write that fails takes nothing (false): an order never runs
   * without its record.
   */
  override fun claim(id: String): Boolean = runCatching { id.lowercase() in claimAll(listOf(id)) }.getOrDefault(false)

  /**
   * [claim] for several ids at once, with one write: the ids (lowercase) this call took. Throws when the write fails,
   * like [markAll]: the poll then stops before marking anything as taken in the sheet, so nothing is lost.
   */
  override fun claimAll(ids: Collection<String>): Set<String> = synchronized(lock) {
    val all = fresh(read()).toMutableMap()
    val won = ids.map { it.lowercase() }.filterTo(LinkedHashSet()) { it !in all }
    if (won.isEmpty()) return@synchronized emptySet()
    val now = clock()
    won.forEach { all[it] = now }
    write(all)
    won
  }

  private fun write(all: Map<String, Long>) {
    val kept = all.entries.sortedByDescending { it.value }.take(CAPACITY).associate { it.key to it.value }
    writeAtomically(file, JSONObject(kept).toString(), "insto-mailbox-seen-")
  }

  private fun fresh(all: Map<String, Long>): Map<String, Long> {
    val now = clock()
    return all.filterValues { now - it in 0..RETENTION_MILLIS }
  }

  companion object {
    const val FILE = "instinct-mailbox-seen.json"
    const val RETENTION_MILLIS = 48 * 60 * 60_000L
    private const val CAPACITY = 2_000
    /** One lock per file, whoever holds an instance. */
    private val LOCKS = HashMap<String, Any>()
  }
}

/** One poll's bookkeeping, before anything is dispatched. */
object InstinctMailboxPoll {
  const val MAX_ORDERS = 10

  data class Outcome(
    /** Verified, marked seen on disk, in the order they came. */
    val valid: List<MailboxOrder>,
    val badSignatures: Int,
    val expired: Int,
    val repeated: Int,
    /** Every id read, valid or not, to mark as taken so it never comes back. */
    val taken: List<String>,
  )

  /**
   * At most [MAX_ORDERS] orders. Each is verified; the valid ones are claimed on disk ([InstinctOrderClaims.claimOrders])
   * **before** this returns, so dispatching happens only after the record exists; with the journal (1.20.0) the claim
   * also records each order's lines in the same transaction. One that an order inside the doorbell claimed first
   * (1.18.0) counts as repeated and is not returned. Two copies of one id in the same poll count once.
   */
  fun process(orders: List<MailboxOrder>, key: String, now: Long, seen: InstinctOrderClaims,
    transport: ExecutionTransport = ExecutionTransport.POLL): Outcome {
    val batch = orders.take(MAX_ORDERS)
    val verified = ArrayList<MailboxOrder>()
    var bad = 0; var expired = 0; var repeated = 0
    val known = HashSet<String>()
    for (order in batch) {
      when (InstinctMailboxSignature.verify(key, order, now) { it in known || seen.contains(it) }) {
        InstinctMailboxSignature.Verdict.VALID -> { verified += order; known += order.id.lowercase() }
        InstinctMailboxSignature.Verdict.BAD_SIGNATURE -> bad++
        InstinctMailboxSignature.Verdict.EXPIRED -> expired++
        InstinctMailboxSignature.Verdict.REPEATED -> repeated++
      }
    }
    val won = if (verified.isEmpty()) emptySet() else seen.claimOrders(verified, transport)
    val valid = verified.filter { it.id.lowercase() in won }
    repeated += verified.size - valid.size
    return Outcome(valid, bad, expired, repeated, batch.map { it.id }.filter { it.isNotBlank() }.distinct())
  }

  /** `{ok:true, ordenes:[{orden_id, at, lineas:[…], firma}]}`; null for anything else (a rejected token is [TOKEN]). */
  fun parse(text: String?): List<MailboxOrder>? {
    val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (!o.optBoolean("ok", false)) return null
    val array = o.optJSONArray("ordenes") ?: return null
    return (0 until minOf(array.length(), MAX_ORDERS)).mapNotNull { i ->
      val item = array.optJSONObject(i) ?: return@mapNotNull null
      val lines = item.optJSONArray("lineas") ?: return@mapNotNull null
      MailboxOrder(item.optString("orden_id"), item.optString("at"),
        (0 until minOf(lines.length(), 16)).map { lines.optString(it) }, item.optString("firma"))
    }
  }

  const val OLD_SCRIPT = "el Apps Script implementado es de antes de 1.16.0: no conoce action=ordenes. Pegá el instinct-bitacora.gs nuevo e implementá una versión nueva (Implementar → Gestionar implementaciones → Editar → Nueva versión)"
  const val HTML = "el Apps Script devolvió una página en lugar de JSON (un error del script o una implementación vieja). Abrí la URL con ?token=…&action=ordenes en el navegador para ver el mensaje"

  /** Why a body that is not `{ok:true, ordenes:[…]}` is invalid, in Spanish, without any content of it. */
  fun invalidReason(text: String?): String {
    val trimmed = text?.trimStart().orEmpty()
    if (trimmed.isEmpty()) return "el Apps Script respondió vacío"
    if (trimmed.startsWith("<")) return HTML
    val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: return InstinctLogClient.Failure.INVALID.label
    // An old script answers the events branch of 1.15.0 to any GET: {ok:true, events:[…]}.
    if (o.optBoolean("ok", false) && !o.has("ordenes") && (o.has("events") || o.has("accepted"))) return OLD_SCRIPT
    return InstinctLogClient.Failure.INVALID.label
  }

  fun tokenRejected(text: String?): Boolean =
    text?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { !it.optBoolean("ok", true) && it.optString("error") == "token" } == true

  /** The body Instinct POSTs to publish an order. Insto sends it only for «Probar de punta a punta» (1.19.0). */
  fun orderBody(token: String, order: MailboxOrder): String = JSONObject().put("token", token).put("action", "orden").put("id", order.id)
    .put("at", order.at).put("lines", JSONArray(order.lines)).put("sig", order.sig).toString()

  fun takenBody(token: String, ids: List<String>): String = JSONObject().put("token", token).put("action", "tomadas").put("ids", JSONArray(ids)).toString()
}

/** «#insto avisarme <texto>»: a local notice for the person, only from the mailbox. */
object InstinctNotice {
  const val PHRASE = "avisarme"
  const val MAX_CHARS = 200
  const val PER_HOUR = 3
  const val ONLY_MAILBOX = "avisarme solo por el buzón"

  /** The text of an «avisarme» order, flattened; null when the order is not one. Empty or too long is invalid. */
  fun text(order: String): String? {
    val trimmed = order.trim()
    if (!trimmed.lowercase(Locale.ROOT).let { it == PHRASE || it.startsWith("$PHRASE ") }) return null
    return InstinctAck.flat(trimmed.substring(PHRASE.length))
  }

  fun valid(text: String): Boolean = text.length in 1..MAX_CHARS
}

/** The catalog section about the mailbox: how to sign and publish. Never the key. */
object InstinctMailboxCatalog {
  const val EXAMPLE_ID = "3b241101-e2bb-4255-8caf-4136c566a962"
  const val EXAMPLE_AT = "2026-11-21T09:30:00-03:00"
  val EXAMPLE_LINES = listOf("#insto poner el volumen al 30 por ciento", "#insto avisarme Te bajé el volumen para la clase.")

  /**
   * [doorbell] (1.17.0): the doorbell is on, so Instinct does not need to announce an order through WhatsApp.
   * [doorbellDirect] (1.18.0): the order also travels inside the doorbell; nothing changes for Instinct.
   */
  fun text(log: InstinctCatalogLog?, forChat: Boolean, doorbell: Boolean = false, doorbellDirect: Boolean = false): String = buildString {
    fun example(line: String) = (if (forChat) InstinctProtocolCatalog.CHAT_MARK else "") + line
    appendLine("Buzón firmado (además de WhatsApp):")
    appendLine("También podés mandarle órdenes a Insto por la misma planilla, sin escribir en el chat. Cada orden va firmada con una clave que tenés vos y Insto; la planilla no la conoce. Sin firma válida, Insto la descarta en silencio.")
    appendLine("1. Armá la cadena canónica, con saltos de línea \\n y sin uno al final: insto-v4, un UUID nuevo, el instante ISO-8601 con zona y tus líneas (como máximo ${InstinctProposalProtocol.MAX_LINES}, cada una empezando con \"${InstinctProposalProtocol.PREFIX} \"):")
    appendLine(InstinctMailboxSignature.VERSION)
    appendLine(EXAMPLE_ID)
    appendLine(EXAMPLE_AT)
    EXAMPLE_LINES.forEach { appendLine(example(it)) }
    appendLine("2. Firmala: sig = hex(HMAC-SHA256(clave, cadena)), en minúsculas, con la clave y la cadena en UTF-8.")
    appendLine("3. Publicala con un POST JSON a la URL de la bitácora:")
    if (log?.url != null && log.token != null) appendLine("POST ${log.url}")
    else appendLine("POST <URL>")
    appendLine("{\"token\": \"<token>\", \"action\": \"orden\", \"id\": \"<UUID>\", \"at\": \"<ISO>\", \"lines\": [\"<línea 1>\", …], \"sig\": \"<hex>\"}")
    if (log?.token != null && log.url != null) appendLine("El token es el de la bitácora: ${log.token}")
    else appendLine(InstinctCatalogLog.ASK)
    appendLine("La clave nunca va en este texto: la persona te la pasa aparte.")
    appendLine("Reglas: un UUID nuevo por orden (uno repetido se descarta); at con no más de 15 minutos de antigüedad ni 2 minutos en el futuro; Insto lee como máximo ${InstinctMailboxPoll.MAX_ORDERS} órdenes por vez. Revisa el buzón cada pocos minutos (Android puede demorarlo) y apenas llega un mensaje tuyo por WhatsApp.")
    if (doorbell) appendLine(InstinctDoorbellText.CATALOG_LINE)
    if (doorbell && doorbellDirect) appendLine(InstinctDoorbellText.CATALOG_DIRECT_LINE)
    appendLine("Los resultados de las órdenes del buzón van siempre a la bitácora; su mensaje_id sale del SHA-256 del id de la orden.")
    appendLine("Solo por el buzón podés usar \"${InstinctProposalProtocol.PREFIX} ${InstinctNotice.PHRASE} <texto>\": una notificación de Insto para la persona, de 1 a ${InstinctNotice.MAX_CHARS} caracteres, texto plano, como máximo ${InstinctNotice.PER_HOUR} por hora. No es un mensaje a nadie más. Desde WhatsApp se rechaza.")
    append("Pseudocódigo: cadena = \"\\n\".join([\"insto-v4\", id, at] + lineas); sig = hmac_sha256(clave.utf8, cadena.utf8).hex()")
  }
}

/**
 * The mailbox over the log's own transport ([InstinctLogClient]): the 302 to script.googleusercontent.com, Google
 * hosts over HTTPS only, at most 2 hops and 15 seconds. Blocking.
 */
class InstinctMailboxClient(private val http: InstinctLogClient = InstinctLogClient()) {
  sealed interface Fetch {
    data class Orders(val orders: List<MailboxOrder>) : Fetch
    data class Failed(val reason: String) : Fetch
  }

  /** `GET ?token=…&action=ordenes&limit=N`: the orders not taken yet, in order. */
  fun fetch(url: String, token: String, limit: Int = InstinctMailboxPoll.MAX_ORDERS): Fetch =
    when (val raw = http.get(url, listOf("token" to token, "action" to "ordenes", "limit" to limit.coerceIn(1, InstinctMailboxPoll.MAX_ORDERS).toString()), mailbox = true)) {
      is InstinctLogClient.Raw.Failed -> Fetch.Failed(raw.reason)
      is InstinctLogClient.Raw.Body -> when {
        InstinctMailboxPoll.tokenRejected(raw.text) -> Fetch.Failed(InstinctLogClient.Failure.TOKEN.label)
        else -> InstinctMailboxPoll.parse(raw.text)?.let { Fetch.Orders(it) } ?: Fetch.Failed(InstinctMailboxPoll.invalidReason(raw.text))
      }
    }

  /** `POST {token, action:"tomadas", ids}`. False on any failure: nothing repeats anyway, the ids are already seen. */
  fun take(url: String, token: String, ids: List<String>): Boolean {
    if (ids.isEmpty()) return true
    val raw = http.post(url, InstinctMailboxPoll.takenBody(token, ids)) as? InstinctLogClient.Raw.Body ?: return false
    return runCatching { JSONObject(raw.text ?: "").optBoolean("ok", false) }.getOrDefault(false)
  }
}
