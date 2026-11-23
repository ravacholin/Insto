package com.example.phone

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Bitácora» (1.15.0): acknowledgements to Instinct written to a Google Sheet through the user's own Apps Script
 * instead of the WhatsApp chat. The pure part: the event, the configuration rules, the outbox file and the HTTP
 * client. Nothing here reads notifications, logs or touches Android. See docs/INSTINCT-PROPUESTAS.md, «Bitácora».
 *
 * What leaves the phone is exactly an [InstinctLogEvent]: the order cut to 80 characters, Insto's own result text,
 * a short hash of Instinct's message (never its text) and fixed labels. Never data of the phone beyond that.
 */
data class InstinctLogEvent(
  val id: String,
  val at: String,
  val kind: String,
  val order: String,
  val detail: String,
  val message: String,
  val protocol: String,
) {
  fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("kind", kind).put("order", order)
    .put("detail", detail).put("message", message).put("protocol", protocol)

  /** The fields, in order. Every one must pass the anti-loop guard. */
  val fields: List<String> get() = listOf(id, at, kind, order, detail, message, protocol)

  companion object {
    private val KEYS = setOf("id", "at", "kind", "order", "detail", "message", "protocol")

    fun from(o: JSONObject): InstinctLogEvent {
      require(o.keys().asSequence().toSet() == KEYS)
      return InstinctLogEvent(o.getString("id"), o.getString("at"), o.getString("kind"), o.getString("order"),
        o.getString("detail"), o.getString("message"), o.getString("protocol"))
    }
  }
}

object InstinctLogEvents {
  const val TEST_KIND = "prueba"
  const val RESPONSE_KIND = "respuesta"

  /** The journal's id doubles as the event id, including after a lost HTTP response. */
  fun questionResponse(record: ExecutionRecord): InstinctLogEvent? {
    val question = InstinctQuestion.decode(record.command, record.deadline) ?: return null
    val answer = record.result ?: return null
    return InstinctLogEvent(record.executionId, iso(record.updatedAt, TimeZone.getTimeZone("UTC")),
      RESPONSE_KIND, question.text, answer, "", "").takeIf(::safe)
  }
  /** Length of the message id: the first hex digits of the SHA-256 the dedupe already computes. */
  const val MESSAGE_ID_CHARS = 12

  /** «2026-11-21T09:30:00-03:00»: an instant with its offset. SimpleDateFormat's «XXX» exists from Android 7. */
  fun iso(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = zone }.format(Date(millis))

  /** The short id of an Instinct message from the dedupe's hash; empty when unknown. Never derived from anything else. */
  fun messageId(hash: String): String = hash.take(MESSAGE_ID_CHARS).takeIf { it.matches(Regex("[0-9a-f]{$MESSAGE_ID_CHARS}")) }.orEmpty()

  /**
   * The event for [entry]: the same order and result text, cuts and flattening as the chat reply. Null when any field
   * would read as an order for Insto: such an event is never written.
   */
  fun of(entry: AckEntry, message: String, atMillis: Long, zone: TimeZone = TimeZone.getDefault(),
    id: String = UUID.randomUUID().toString()): InstinctLogEvent? =
    InstinctLogEvent(id, iso(atMillis, zone), entry.kind.code, InstinctAck.order(entry), InstinctAck.detail(entry),
      messageId(message), InstinctProtocolCatalog.TITLE).takeIf(::safe)

  /** The event «Probar bitácora» writes. */
  fun test(atMillis: Long, zone: TimeZone = TimeZone.getDefault(), id: String = UUID.randomUUID().toString()): InstinctLogEvent =
    InstinctLogEvent(id, iso(atMillis, zone), TEST_KIND, "", "Prueba de la bitácora desde Insto.", "", InstinctProtocolCatalog.TITLE)

  /**
   * The phone's state (1.16.0): kind «estado», no order, the compact `clave=valor` line as detail, no message. Null
   * when it would not pass the anti-loop guard.
   */
  fun state(snapshot: InstinctStateSnapshot, atMillis: Long, zone: TimeZone = TimeZone.getDefault(),
    id: String = UUID.randomUUID().toString()): InstinctLogEvent? =
    InstinctLogEvent(id, iso(atMillis, zone), InstinctState.KIND, "", snapshot.detail(), "", InstinctProtocolCatalog.TITLE).takeIf(::safe)

  /** Anti-loop guard, field by field: no field may start with «#insto » nor hold a line Insto would read as an order. */
  fun safe(event: InstinctLogEvent): Boolean = event.fields.all { field ->
    InstinctAck.safe(field) && !field.trimStart().startsWith("${InstinctProposalProtocol.PREFIX} ") &&
      field.none { it == '\n' || it == '\r' }
  }
}

/** The Apps Script address and token. Both live in noBackupFilesDir; the token is never shown whole nor logged. */
object InstinctLogConfig {
  const val URL_PREFIX = "https://script.google.com/macros/s/"
  const val URL_SUFFIX = "/exec"
  private val URL = Regex("^https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]{10,200}/exec$")
  private val TOKEN = Regex("^[\\x21-\\x7e]{8,200}$")

  /** Starts with [URL_PREFIX], ends with [URL_SUFFIX], one deployment id in between, nothing else. */
  fun validUrl(url: String): Boolean = URL.matches(url.trim())

  /** Printable ASCII without spaces, 8 to 200 characters. */
  fun validToken(token: String): Boolean = TOKEN.matches(token)

  /** «••••abcd»: the last four characters only. */
  fun mask(token: String?): String = if (token.isNullOrEmpty()) "sin token" else "••••" + token.takeLast(4)

  /** How Instinct reads the log, with the real values or placeholders. */
  fun readUrl(url: String, token: String): String = "$url?token=$token&since=<ISO>&limit=50"

  /** How Instinct reads the last state (1.16.0). */
  fun stateUrl(url: String, token: String): String = "$url?token=$token&action=estado"
}

/**
 * Events waiting to be written, in one file with an atomic write like [ConfiguredRoutineStore]. At most [CAPACITY]:
 * when full the oldest is dropped and counted, for the diagnosis. Adding an id that is already there keeps one copy;
 * only the ids the script accepted are ever removed.
 */
class InstinctLogOutbox(private val file: File) {
  data class Snapshot(val events: List<InstinctLogEvent>, val dropped: Int)

  @Synchronized fun read(): Snapshot = if (!file.exists()) Snapshot(emptyList(), 0)
    else runCatching { decode(file.readText()) }.getOrDefault(Snapshot(emptyList(), 0))

  @Synchronized fun size(): Int = read().events.size
  @Synchronized fun dropped(): Int = read().dropped

  /** Adds [events] after the pending ones. Returns how many old events had to be dropped to fit. */
  @Synchronized fun add(events: List<InstinctLogEvent>): Int {
    if (events.isEmpty()) return 0
    val current = read()
    val known = current.events.mapTo(HashSet()) { it.id }
    val merged = current.events + events.filter { it.id !in known && InstinctLogEvents.safe(it) }.distinctBy { it.id }
    val overflow = (merged.size - CAPACITY).coerceAtLeast(0)
    write(Snapshot(merged.drop(overflow), current.dropped + overflow))
    return overflow
  }

  /** The oldest [max] events, for one POST. */
  @Synchronized fun batch(max: Int = BATCH): List<InstinctLogEvent> = read().events.take(max)

  /** Removes only [ids]: what the script confirmed. Anything else stays for the next attempt. */
  @Synchronized fun remove(ids: Set<String>) {
    if (ids.isEmpty()) return
    val current = read()
    val left = current.events.filterNot { it.id in ids }
    if (left.size != current.events.size) write(current.copy(events = left))
  }

  /** The diagnosis said it: the count of dropped events starts again. */
  @Synchronized fun clearDropped() { val current = read(); if (current.dropped != 0) write(current.copy(dropped = 0)) }

  private fun write(value: Snapshot) {
    if (value.events.isEmpty() && value.dropped == 0) { file.delete(); return }
    val data = encode(value)
    file.parentFile?.mkdirs()
    val temporary = File.createTempFile("insto-outbox-", ".tmp", file.absoluteFile.parentFile)
    try {
      FileOutputStream(temporary).use { it.write(data.toByteArray(Charsets.UTF_8)); it.fd.sync() }
      check(temporary.renameTo(file)) { "No se pudo guardar la bandeja de la bitácora" }
    } finally { temporary.delete() }
  }

  companion object {
    const val CAPACITY = 200
    const val BATCH = 50
    const val FILE = "instinct-outbox.json"

    fun encode(value: Snapshot): String = JSONObject().put("dropped", value.dropped)
      .put("events", JSONArray(value.events.map(InstinctLogEvent::json))).toString()

    fun decode(text: String): Snapshot {
      require(text.length <= 1_048_576)
      val o = JSONObject(text)
      val array = o.getJSONArray("events")
      val events = (0 until minOf(array.length(), CAPACITY)).map { InstinctLogEvent.from(array.getJSONObject(it)) }
      return Snapshot(events, o.optInt("dropped", 0).coerceAtLeast(0))
    }
  }
}

/**
 * One POST per batch, {token, events}, to the Apps Script URL. Apps Script answers with a 302 to
 * script.googleusercontent.com: it is followed with a GET (only to Google's hosts, over HTTPS) and the final JSON
 * {ok, accepted:[ids]} is read. 15 seconds for the whole exchange. Blocking: called from a job's own thread.
 */
class InstinctLogClient(base: OkHttpClient = OkHttpClient()) {
  enum class Failure(val label: String) {
    NETWORK("sin red"), TOKEN("token rechazado"), INVALID("respuesta inválida"), HTTP("HTTP"), NOT_CONFIGURED("falta la URL o el token"),
    /** Google sent the request to its sign-in page: the deployment is not «Quién tiene acceso: cualquier usuario». */
    LOGIN("Google pidió iniciar sesión: en la implementación, «Quién tiene acceso» tiene que ser «Cualquier usuario»"),
  }

  sealed interface Result {
    /** The ids the script confirmed, new or repeated. Only ids that were sent. */
    data class Accepted(val ids: Set<String>) : Result
    data class Failed(val failure: Failure, val code: Int = 0) : Result {
      val reason: String get() = if (failure == Failure.HTTP) "HTTP $code" else failure.label
    }
  }

  private val http = base.newBuilder().followRedirects(false).followSslRedirects(false)
    .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

  fun send(url: String, token: String, events: List<InstinctLogEvent>): Result {
    if (!InstinctLogConfig.validUrl(url) || !InstinctLogConfig.validToken(token)) return Result.Failed(Failure.NOT_CONFIGURED)
    if (events.isEmpty()) return Result.Accepted(emptySet())
    val body = JSONObject().put("token", token).put("events", JSONArray(events.map(InstinctLogEvent::json))).toString()
    return when (val raw = post(url, body)) {
      is Raw.Body -> parse(raw.text, events.mapTo(HashSet()) { it.id })
      is Raw.Failed -> Result.Failed(raw.failure, raw.code)
    }
  }

  /** The JSON body of an exchange, or why there is none. Shared by the log and the mailbox (1.16.0). */
  sealed interface Raw {
    data class Body(val text: String?) : Raw
    data class Failed(val failure: Failure, val code: Int = 0) : Raw {
      val reason: String get() = if (failure == Failure.HTTP) "HTTP $code" else failure.label
    }
  }

  /** A POST of [json] to the Apps Script, following its redirect. Only a valid script URL. */
  fun post(url: String, json: String): Raw {
    if (!InstinctLogConfig.validUrl(url)) return Raw.Failed(Failure.NOT_CONFIGURED)
    val first = Request.Builder().url(url.trim()).post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    return try { exchange(first) } catch (_: IOException) { Raw.Failed(Failure.NETWORK) }
  }

  /** A GET of the Apps Script with [query] parameters (encoded), following its redirect. */
  fun get(url: String, query: List<Pair<String, String>>): Raw {
    if (!InstinctLogConfig.validUrl(url)) return Raw.Failed(Failure.NOT_CONFIGURED)
    val target = url.trim().toHttpUrlOrNull()?.newBuilder()?.apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
      ?: return Raw.Failed(Failure.NOT_CONFIGURED)
    return try { exchange(Request.Builder().url(target).get().build()) } catch (_: IOException) { Raw.Failed(Failure.NETWORK) }
  }

  /** The request, then at most [MAX_HOPS] redirects, each one a GET to a Google host over HTTPS. */
  private fun exchange(first: Request): Raw {
    var request = first
    repeat(MAX_HOPS + 1) {
      http.newCall(request).execute().use { response ->
        val code = response.code
        if (code !in REDIRECTS) return when {
          code == 401 || code == 403 -> Raw.Failed(Failure.TOKEN)
          code !in 200..299 -> Raw.Failed(Failure.HTTP, code)
          else -> Raw.Body(response.body?.let { readLimited(it.byteStream()) })
        }
        val location = response.header("Location")?.let { response.request.url.resolve(it) }
        if (location != null && location.host in LOGIN_HOSTS) return Raw.Failed(Failure.LOGIN)
        if (location == null || !location.isHttps || location.host !in REDIRECT_HOSTS) return Raw.Failed(Failure.INVALID)
        request = Request.Builder().url(location).get().build()
      }
    }
    return Raw.Failed(Failure.INVALID)
  }

  private fun readLimited(stream: java.io.InputStream): String? = runCatching {
    stream.readBounded(MAX_RESPONSE_BYTES) { IOException("Respuesta demasiado grande") }.toString(Charsets.UTF_8)
  }.getOrNull()

  companion object {
    const val TIMEOUT_SECONDS = 15L
    private const val MAX_HOPS = 2
    private const val MAX_RESPONSE_BYTES = 65_536
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private val REDIRECT_HOSTS = setOf("script.googleusercontent.com", "script.google.com")
    /** Never followed: a redirect there only says the deployment asks to sign in. */
    private val LOGIN_HOSTS = setOf("accounts.google.com")

    /** {ok:true, accepted:[ids]}; {ok:false, error:"token"} is a rejected token; anything else is invalid. */
    fun parse(text: String?, sent: Set<String>): Result {
      val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Result.Failed(Failure.INVALID)
      if (!o.optBoolean("ok", false)) {
        return if (o.optString("error") == "token") Result.Failed(Failure.TOKEN) else Result.Failed(Failure.INVALID)
      }
      val accepted = o.optJSONArray("accepted") ?: return Result.Failed(Failure.INVALID)
      val ids = (0 until accepted.length()).mapNotNull { accepted.opt(it) as? String }.filterTo(HashSet()) { it in sent }
      return Result.Accepted(ids)
    }
  }
}
