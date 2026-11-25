package com.example.phone

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The push transport's pure part (1.19.0): what a push becomes once received, the durable inbox between receiving it
 * and dispatching it, and the limits of both. Receiving is not running: an entry in the inbox is only «something
 * arrived»; whether an order runs is still decided by [InstinctMailboxSignature.verify], [InstinctMailboxSeen.claimAll]
 * and the same intake as any poll. Nothing here touches Android. See docs/architecture/instinct-push-reliability.md.
 */
object InstinctPushPolicy {
  /** At most this many entries wait in the inbox; plain rings fold into one. */
  const val INBOX_CAPACITY = 32
  /** An order in the inbox lives as long as its signature's window: after that it could only be refused. */
  const val ORDER_TTL_MILLIS = InstinctMailboxSignature.MAX_AGE_MILLIS
  /** An order whose claim failed on disk is retried this many times; then it is left to the poll. */
  const val MAX_CLAIM_ATTEMPTS = 3
  /** The receiving thread (never the main one) holds the broadcast at most this long for the fast path. */
  const val FAST_PATH_BUDGET_MILLIS = 8_000L
  /** The rescue job: if the process dies with entries pending, it drains them about this much later. */
  const val RESCUE_DELAY_MILLIS = 30_000L
  /** Broadcasts waiting for the receiving thread; beyond it the broadcast is let go and counted. */
  const val RECEIVE_QUEUE = 16
}

/** What a push carried, decided before anything is stored. */
object InstinctPushMessage {
  sealed interface Kind {
    /** «Hay algo en el buzón»: poll. */
    data object Ring : Kind
    /** A signed order, still to be verified. */
    data class Order(val order: MailboxOrder) : Kind
    data class Reference(val reference: MailboxOrderReference) : Kind
  }

  /**
   * [encrypted]: it arrived encrypted (RFC 8291) and decrypted with Insto's keys. [plainLegacy]: it arrived in clear
   * while the script has not confirmed encryption (a script older than 1.19.0): accepted as in 1.18.0. [downgraded]:
   * it arrived in clear after the script confirmed encryption: it can only poll. [malformed]: neither empty,
   * «insto-timbre» nor an order. [undecryptable]: it looks like RFC 8291 content Insto could not decrypt.
   */
  data class Classified(
    val kind: Kind,
    val encrypted: Boolean,
    val plainLegacy: Boolean = false,
    val downgraded: Boolean = false,
    val malformed: Boolean = false,
    val undecryptable: Boolean = false,
  )

  /**
   * [decrypted] is what the connector says: true only when Insto's keys decrypted the content. [encryptionRequired]
   * is true once the script confirmed it sends encrypted pushes: from then on a clear message never carries an order.
   */
  fun classify(content: ByteArray?, decrypted: Boolean, encryptionRequired: Boolean): Classified {
    if (!decrypted && encryptionRequired) {
      // Anyone who knows the endpoint can send clear text: at most it asks for a poll.
      return Classified(Kind.Ring, encrypted = false, downgraded = !looksEncrypted(content), undecryptable = looksEncrypted(content))
    }
    if (decrypted) InstinctDoorbellPayload.reference(content)?.let { return Classified(Kind.Reference(it), encrypted = true) }
    val order = InstinctDoorbellPayload.parse(content)
    if (order != null) return Classified(Kind.Order(order), encrypted = decrypted, plainLegacy = !decrypted)
    val plain = InstinctDoorbellPayload.plain(content)
    val sealed = !decrypted && looksEncrypted(content)
    return Classified(Kind.Ring, encrypted = decrypted, plainLegacy = !decrypted && !sealed, malformed = !plain && !sealed, undecryptable = sealed)
  }

  /**
   * RFC 8188 `aes128gcm` header: salt (16), record size (4, at least 18), key id length 65 and an uncompressed P-256
   * point (0x04). Only for the diagnosis: it tells «no se pudo descifrar» from «llegó en claro».
   */
  fun looksEncrypted(content: ByteArray?): Boolean {
    if (content == null || content.size < 86 + 17) return false
    val rs = ((content[16].toLong() and 0xff) shl 24) or ((content[17].toLong() and 0xff) shl 16) or
      ((content[18].toLong() and 0xff) shl 8) or (content[19].toLong() and 0xff)
    return rs >= 18 && content[20].toInt() == 65 && content[21].toInt() == 0x04
  }
}

/** One signal waiting to be dispatched. [order] only for [Kind.ORDER]. [claimed]: its id was taken in `seen`. */
data class PushEntry(
  val seq: Long,
  val kind: Kind,
  val receivedAt: Long,
  val order: MailboxOrder? = null,
  val encrypted: Boolean = false,
  val claimed: Boolean = false,
  val attempts: Int = 0,
  val reference: MailboxOrderReference? = null,
) {
  enum class Kind { RING, ORDER, REFERENCE }

  fun json(): JSONObject = JSONObject().put("seq", seq).put("kind", kind.name).put("at", receivedAt).put("encrypted", encrypted)
    .put("claimed", claimed).put("attempts", attempts).apply {
      reference?.let { put("reference", JSONObject().put("v", 1).put("id", it.id).put("sha256", it.fingerprint)) }
      order?.let { put("order", JSONObject().put("id", it.id).put("at", it.at).put("lines", JSONArray(it.lines)).put("sig", it.sig)) }
    }

  companion object {
    fun from(o: JSONObject): PushEntry? = runCatching {
      val kind = Kind.valueOf(o.getString("kind"))
      val order = o.optJSONObject("order")?.let { m ->
        val lines = m.getJSONArray("lines")
        MailboxOrder(m.getString("id"), m.getString("at"), (0 until minOf(lines.length(), 16)).map { lines.getString(it) }, m.getString("sig"))
      }
      val reference = o.optJSONObject("reference")?.let {
        InstinctDoorbellPayload.reference(it.toString().toByteArray(Charsets.UTF_8))
      }
      if (kind == Kind.ORDER && order == null || kind == Kind.REFERENCE && (reference == null || !o.optBoolean("encrypted"))) return null
      PushEntry(o.getLong("seq"), kind, o.getLong("at"), order, o.optBoolean("encrypted"), o.optBoolean("claimed"),
        o.optInt("attempts"), reference)
    }.getOrNull()
  }
}

/**
 * The durable inbox (`instinct-push-inbox.json` in `noBackupFilesDir`), written atomically: a push that was received
 * survives the death of the process until it is dispatched. Every instance on one file shares one lock. Holds the
 * order's signed lines only until dispatch, at most [InstinctPushPolicy.ORDER_TTL_MILLIS]; never anything else.
 */
class InstinctPushInbox(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
  private val lock: Any = synchronized(LOCKS) { LOCKS.getOrPut(file.absolutePath) { Any() } }

  sealed interface Added {
    data class Queued(val entry: PushEntry) : Added
    /** A ring while another ring waits: one poll reads everything. */
    data object Coalesced : Added
    /** The same order id is already waiting. */
    data object Duplicate : Added
    /** The inbox is full: an order waits in the sheet for the poll, which a ring entry asks for. */
    data object Full : Added
    /** The write failed: nothing was stored. */
    data object Failed : Added
  }

  /** Every entry still useful, oldest first. Orders past their window are dropped here. */
  fun entries(): List<PushEntry> = synchronized(lock) { fresh(read()) }

  fun size(): Int = entries().size

  fun add(kind: PushEntry.Kind, order: MailboxOrder? = null, encrypted: Boolean = false,
    reference: MailboxOrderReference? = null, receivedAt: Long = clock()): Added = synchronized(lock) {
    if (kind == PushEntry.Kind.REFERENCE && (reference == null || !encrypted)) return@synchronized Added.Failed
    val all = fresh(read())
    when {
      kind == PushEntry.Kind.RING && all.any { it.kind == PushEntry.Kind.RING } -> return@synchronized Added.Coalesced
      kind == PushEntry.Kind.ORDER && order != null && all.any { it.order?.id.equals(order.id, ignoreCase = true) } ->
        return@synchronized Added.Duplicate
      reference != null && all.any { entry ->
        entry.reference?.id.equals(reference.id, ignoreCase = true) ||
          entry.order?.id.equals(reference.id, ignoreCase = true)
      } -> return@synchronized Added.Duplicate
    }
    if (all.size >= InstinctPushPolicy.INBOX_CAPACITY) {
      // Full: a ring still gets in (folded), so the poll reads whatever did not fit.
      if (kind != PushEntry.Kind.RING && all.none { it.kind == PushEntry.Kind.RING }) {
        val ring = PushEntry(nextSeq(all), PushEntry.Kind.RING, clock())
        // The oldest unclaimed order makes room: it is still in the sheet, and the ring reads it from there.
        val oldest = all.firstOrNull { !it.claimed } ?: return@synchronized Added.Full
        if (!write(all.filterNot { it.seq == oldest.seq } + ring)) return@synchronized Added.Failed
      }
      return@synchronized Added.Full
    }
    val entry = PushEntry(nextSeq(all), kind, receivedAt, order?.takeIf { kind == PushEntry.Kind.ORDER }, encrypted,
      reference = reference?.takeIf { kind == PushEntry.Kind.REFERENCE })
    if (write(all + entry)) Added.Queued(entry) else Added.Failed
  }

  /** Marks [seq] claimed. False when the write failed (the order still runs once: `seen` already holds its id). */
  fun markClaimed(seq: Long): Boolean = update(seq) { it.copy(claimed = true) }

  fun bumpAttempts(seq: Long): Boolean = update(seq) { it.copy(attempts = it.attempts + 1) }

  fun remove(seq: Long): Boolean = synchronized(lock) {
    val all = fresh(read())
    if (all.none { it.seq == seq }) true else write(all.filterNot { it.seq == seq })
  }

  private fun update(seq: Long, change: (PushEntry) -> PushEntry): Boolean = synchronized(lock) {
    val all = fresh(read())
    write(all.map { if (it.seq == seq) change(it) else it })
  }

  private fun nextSeq(all: List<PushEntry>): Long = maxOf(all.maxOfOrNull { it.seq } ?: 0L, lastSeq) + 1

  private fun read(): List<PushEntry> {
    if (!file.exists()) return emptyList()
    return runCatching {
      val o = JSONObject(file.readText().also { require(it.length <= 262_144) })
      lastSeq = maxOf(lastSeq, o.optLong("lastSeq"))
      val array = o.getJSONArray("entries")
      (0 until array.length()).mapNotNull { PushEntry.from(array.getJSONObject(it)) }.sortedBy { it.seq }
    }.getOrDefault(emptyList())
  }

  /** Orders past their window are gone; a claimed one is kept until dispatch decides (it may need «incierta»). */
  private fun fresh(all: List<PushEntry>): List<PushEntry> {
    val now = clock()
    return all.filter { it.kind == PushEntry.Kind.RING || it.claimed || now - it.receivedAt in 0..InstinctPushPolicy.ORDER_TTL_MILLIS }
  }

  private fun write(all: List<PushEntry>): Boolean {
    lastSeq = maxOf(lastSeq, all.maxOfOrNull { it.seq } ?: 0L)
    return runCatching {
      writeAtomically(file, JSONObject().put("lastSeq", lastSeq).put("entries", JSONArray(all.map { it.json() })).toString(), "insto-push-inbox-")
    }.isSuccess
  }

  /** Sequence numbers keep growing within the process even when the inbox empties. */
  private var lastSeq = 0L

  companion object {
    const val FILE = "instinct-push-inbox.json"
    private val LOCKS = HashMap<String, Any>()
  }
}
