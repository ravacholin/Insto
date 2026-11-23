package com.example.phone

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * The execution journal (1.20.0): one SQLite database in `noBackupFilesDir` (`instinct-executions.db`), never in
 * backups. It holds the one record of claimed ids (migrated from `instinct-mailbox-seen.json`, which is kept as it
 * was) and one row per accepted order. Claiming an id and recording its orders happen in one transaction: an order is
 * never claimed without its row, and a row never exists without its claim. Every transition is checked by
 * [ExecutionTransitions]; [beginDispatch] is written before touching the phone and, if it cannot be written, nothing
 * runs. See docs/architecture/instinct-execution-journal.md.
 */
class InstinctExecutionJournal private constructor(private val file: File, private val legacySeen: File) {
  private val lock = Any()
  @Volatile private var db: SQLiteDatabase? = null

  sealed interface Admission {
    data class Admitted(val records: List<ExecutionRecord>) : Admission
    /** The id was already claimed (by another transport, an earlier process or 1.19.0). */
    data object Repeated : Admission
  }

  private fun database(): SQLiteDatabase = synchronized(lock) {
    db?.takeIf { it.isOpen }?.let { return it }
    file.parentFile?.mkdirs()
    val opened = SQLiteDatabase.openOrCreateDatabase(file, null)
    try {
      if (opened.version < VERSION) {
        opened.beginTransaction()
        try {
          create(opened)
          opened.execSQL("ALTER TABLE executions ADD COLUMN question_at INTEGER")
          opened.version = VERSION
          opened.setTransactionSuccessful()
        } finally { opened.endTransaction() }
      }
      migrateLegacy(opened)
    } catch (error: Throwable) { runCatching { opened.close() }; throw error }
    db = opened
    opened
  }

  private fun create(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS claims (id TEXT PRIMARY KEY, at INTEGER NOT NULL, source TEXT NOT NULL)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS executions (
      execution_id TEXT PRIMARY KEY, message_id TEXT NOT NULL, idx INTEGER NOT NULL, transport TEXT NOT NULL,
      line TEXT NOT NULL, received_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, state TEXT NOT NULL,
      command TEXT, result TEXT, reason TEXT, occurrence TEXT, parent TEXT, attempts INTEGER NOT NULL DEFAULT 0,
      log_message TEXT NOT NULL DEFAULT '', owner TEXT NOT NULL DEFAULT '', deadline INTEGER, reported TEXT,
      attribution TEXT, proposal_id INTEGER)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS executions_message ON executions (message_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS executions_state ON executions (state)")
    db.execSQL("CREATE INDEX IF NOT EXISTS executions_occurrence ON executions (occurrence)")
    db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
  }

  /**
   * 1.19.0's `instinct-mailbox-seen.json`, imported once in one transaction. The file is never emptied nor deleted. A
   * file that exists but cannot be read (not a regular file, an I/O error) stops the journal: claiming without it
   * could run an order 1.19.0 already ran. A regular file that is not JSON was already read as empty by 1.19.0; it is
   * renamed aside, kept, and the import goes on, as 1.19.0 would have.
   */
  private fun migrateLegacy(db: SQLiteDatabase) {
    if (meta(db, META_SEEN) != null) return
    val imported: Map<String, Long> = when {
      !legacySeen.exists() -> emptyMap()
      !legacySeen.isFile -> throw IllegalStateException("instinct-mailbox-seen.json no es un archivo")
      else -> {
        val text = legacySeen.readText()
        val parsed = runCatching { JSONObject(text).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrNull()
        if (parsed == null) {
          val aside = File(legacySeen.parentFile, legacySeen.name + ".ilegible-" + clock())
          if (!legacySeen.renameTo(aside)) throw IllegalStateException("no se pudo apartar instinct-mailbox-seen.json")
          setMeta(db, META_LEGACY_CORRUPT, aside.name)
          emptyMap()
        } else parsed
      }
    }
    db.beginTransaction()
    try {
      imported.forEach { (id, at) ->
        db.insertWithOnConflict("claims", null, ContentValues().apply {
          put("id", id.lowercase()); put("at", at); put("source", ExecutionTransport.LEGACY.name)
        }, SQLiteDatabase.CONFLICT_IGNORE)
      }
      setMeta(db, META_SEEN, imported.size.toString())
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  private fun meta(db: SQLiteDatabase, key: String): String? =
    db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

  private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
    db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  /** How many ids 1.19.0's file brought, or null before the migration ran. */
  fun migratedClaims(): Int? = runCatching { meta(database(), META_SEEN)?.toIntOrNull() }.getOrNull()

  /** The name the unreadable 1.19.0 file was moved to, if it was. */
  fun legacyCorrupt(): String? = runCatching { meta(database(), META_LEGACY_CORRUPT) }.getOrNull()

  // ---------------------------------------------------------------------------------------------------------------
  // Claims
  // ---------------------------------------------------------------------------------------------------------------

  fun containsClaim(id: String): Boolean {
    val db = database()
    val since = clock() - ExecutionJournalPolicy.CLAIM_RETENTION_MILLIS
    return db.rawQuery("SELECT 1 FROM claims WHERE id = ? AND at >= ?", arrayOf(id.lowercase(), since.toString())).use { it.moveToFirst() }
  }

  /** Claims [ids] without recording any order (an id that carried nothing to run). Throws when it cannot write. */
  fun claimIds(ids: Collection<String>, transport: ExecutionTransport): Set<String> = transaction { db ->
    ids.map { it.lowercase() }.distinct().filterTo(LinkedHashSet()) { claimIn(db, it, transport) }
  }

  private fun claimIn(db: SQLiteDatabase, id: String, transport: ExecutionTransport): Boolean {
    val since = clock() - ExecutionJournalPolicy.CLAIM_RETENTION_MILLIS
    val taken = db.rawQuery("SELECT at FROM claims WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else null }
    if (taken != null && taken >= since) return false
    db.insertWithOnConflict("claims", null, ContentValues().apply { put("id", id); put("at", clock()); put("source", transport.name) },
      SQLiteDatabase.CONFLICT_REPLACE)
    return true
  }

  /**
   * Claims [messageId] and records one row per order of [lines], in one transaction. [Admission.Repeated] when the id
   * was claimed before. Throws when the transaction fails: then nothing is claimed and nothing must run.
   */
  fun admit(messageId: String, transport: ExecutionTransport, lines: List<String>, logMessage: String = "",
    deadline: Long? = null, parent: String? = null, occurrence: String? = null, claim: Boolean = true): Admission = transaction { db ->
    val id = messageId.lowercase()
    if (claim && !claimIn(db, id, transport)) return@transaction Admission.Repeated
    val now = clock()
    val records = lines.mapIndexed { index, line ->
      ExecutionRecord(ExecutionIds.of(id, index), id, index, transport, line, now, now, ExecutionState.RECEIVED,
        logMessage = logMessage, owner = owner, deadline = deadline, parent = parent, occurrence = occurrence)
    }
    for (record in records) {
      val inserted = db.insertWithOnConflict("executions", null, values(record), SQLiteDatabase.CONFLICT_IGNORE)
      // Without a claim (an occurrence), an existing row means it was admitted already: never twice.
      if (inserted == -1L && !claim) return@transaction Admission.Repeated
    }
    Admission.Admitted(records)
  }

  /** One order a doorbell entry of 1.19.0 claimed and nobody recorded: kept as uncertain, never run. */
  fun recordLegacyUncertain(messageId: String, lines: List<String>, logMessage: String, reason: String): List<ExecutionRecord> = transaction { db ->
    val id = messageId.lowercase()
    claimIn(db, id, ExecutionTransport.LEGACY)
    val now = clock()
    lines.mapIndexed { index, line ->
      ExecutionRecord(ExecutionIds.of(id, index), id, index, ExecutionTransport.LEGACY, line, now, now, ExecutionState.UNCERTAIN,
        reason = reason, logMessage = logMessage, owner = owner, reported = AckKind.UNCERTAIN.name)
    }.onEach { db.insertWithOnConflict("executions", null, values(it), SQLiteDatabase.CONFLICT_IGNORE) }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Executions
  // ---------------------------------------------------------------------------------------------------------------

  fun get(executionId: String): ExecutionRecord? =
    database().rawQuery("SELECT * FROM executions WHERE execution_id = ?", arrayOf(executionId)).use { if (it.moveToFirst()) record(it) else null }

  fun forMessage(messageId: String): List<ExecutionRecord> =
    database().rawQuery("SELECT * FROM executions WHERE message_id = ? ORDER BY idx", arrayOf(messageId.lowercase())).use(::records)

  fun byOccurrence(occurrence: String): List<ExecutionRecord> =
    database().rawQuery("SELECT * FROM executions WHERE occurrence = ? ORDER BY received_at", arrayOf(occurrence)).use(::records)

  /** The row whose short id is [shortId] («e» and 8 hex digits). */
  fun byShort(shortId: String): ExecutionRecord? {
    val prefix = ExecutionIds.parseShort(shortId) ?: return null
    return database().rawQuery("SELECT * FROM executions WHERE execution_id LIKE ? ORDER BY updated_at DESC LIMIT 1", arrayOf("$prefix%"))
      .use { if (it.moveToFirst()) record(it) else null }
  }

  fun recent(limit: Int = ExecutionJournalPolicy.LIST): List<ExecutionRecord> =
    database().rawQuery("SELECT * FROM executions ORDER BY updated_at DESC, received_at DESC LIMIT ?", arrayOf(limit.toString())).use(::records)

  /** Rows not terminal: the recovery's input. */
  fun unfinished(states: Collection<ExecutionState> = ExecutionState.entries.filterNot { it.terminal }): List<ExecutionRecord> {
    val names = states.joinToString(",") { "'${it.name}'" }
    return database().rawQuery("SELECT * FROM executions WHERE state IN ($names) ORDER BY received_at", null).use(::records)
  }

  sealed interface QuestionAdmission {
    data object Accepted : QuestionAdmission
    data object Unavailable : QuestionAdmission
    data class Refused(val reason: String) : QuestionAdmission
  }

  /** Reserves one of the question slots in the same journal row, under the transition lock. */
  fun startQuestion(id: String, question: InstinctQuestion, needsPreview: Boolean): QuestionAdmission = transaction { db ->
    val current = getIn(db, id) ?: return@transaction QuestionAdmission.Unavailable
    val state = if (needsPreview) ExecutionState.QUESTION_CONFIRM else ExecutionState.ASKING
    if (current.state != ExecutionState.VALIDATED || !ExecutionTransitions.allowed(current.state, state))
      return@transaction QuestionAdmission.Unavailable
    val now = clock()
    questionLimit(db, now)?.let { return@transaction QuestionAdmission.Refused(it) }
    db.update("executions", ContentValues().apply {
      put("state", state.name); put("updated_at", now); put("owner", owner)
      put("question_at", now); put("deadline", question.expiresAt); put("command", question.encode())
    }, "execution_id = ?", arrayOf(id))
    QuestionAdmission.Accepted
  }

  fun questionLimit(): String? = questionLimit(database(), clock())

  private fun questionLimit(db: SQLiteDatabase, now: Long): String? {
    val states = InstinctQuestion.PENDING.joinToString(",") { "'${it.name}'" }
    return db.rawQuery(
      "SELECT COUNT(CASE WHEN state IN ($states) AND deadline > ? THEN 1 END), " +
        "COUNT(CASE WHEN question_at > ? THEN 1 END) FROM executions WHERE question_at IS NOT NULL",
      arrayOf(now.toString(), (now - InstinctQuestion.HOUR).toString())
    ).use { it.moveToFirst(); InstinctQuestion.limit(it.getInt(0), it.getInt(1)) }
  }

  /** Resolves against the current row and deadline, before any notification or network side effect. */
  internal fun finishQuestion(id: String, choice: Int?): QuestionOutcome? = transaction { db ->
    val current = getIn(db, id) ?: return@transaction null
    val question = InstinctQuestion.decode(current.command, current.deadline) ?: return@transaction null
    val now = clock()
    val outcome = question.outcome(current.state, now, choice) ?: return@transaction null
    if (!ExecutionTransitions.allowed(current.state, outcome.state)) return@transaction null
    db.update("executions", ContentValues().apply {
      put("state", outcome.state.name); put("updated_at", now); put("owner", owner); put("result", outcome.response)
    }, "execution_id = ?", arrayOf(id))
    outcome
  }

  fun pendingQuestionReplies(limit: Int = 1): List<ExecutionRecord> = database().rawQuery(
    "SELECT * FROM executions WHERE question_at IS NOT NULL AND reported IS NULL " +
      "AND state IN ('ANSWERED','EXPIRED','CANCELLED') ORDER BY updated_at, execution_id LIMIT ?", arrayOf(limit.toString())
  ).use(::records)

  fun markQuestionReplies(ids: Set<String>) = transaction { db ->
    for (id in ids) db.update("executions", ContentValues().apply { put("reported", InstinctLogEvents.RESPONSE_KIND) },
      "execution_id = ? AND question_at IS NOT NULL", arrayOf(id))
  }

  /**
   * Moves [executionId] to [to] if [ExecutionTransitions] allows it (and, with [from], only from those states). False
   * when not allowed or not found; throws when it cannot write.
   */
  fun transition(executionId: String, to: ExecutionState, result: String? = null, reason: String? = null,
    command: String? = null, from: Set<ExecutionState>? = null, attribution: ExecutionAttribution? = null,
    proposalId: Long? = null, occurrence: String? = null, reported: AckKind? = null): Boolean = transaction { db ->
    val current = getIn(db, executionId) ?: return@transaction false
    if (from != null && current.state !in from) return@transaction false
    if (!ExecutionTransitions.allowed(current.state, to)) return@transaction false
    db.update("executions", ContentValues().apply {
      put("state", to.name); put("updated_at", clock()); put("owner", owner)
      result?.let { put("result", it.take(MAX_TEXT)) }
      reason?.let { put("reason", it.take(MAX_TEXT)) }
      command?.let { put("command", it) }
      attribution?.let { put("attribution", it.name) }
      proposalId?.let { put("proposal_id", it) }
      occurrence?.let { put("occurrence", it) }
      reported?.let { put("reported", it.name) }
      if (to == ExecutionState.DISPATCHING) put("attempts", current.attempts + 1)
    }, "execution_id = ?", arrayOf(executionId)) == 1
  }

  /**
   * Written before touching the phone. False when the row cannot be moved to [ExecutionState.DISPATCHING] (already
   * dispatched, terminal, unknown) or the write fails: then the action must not run.
   */
  fun beginDispatch(executionId: String, command: String?): Boolean =
    runCatching { transition(executionId, ExecutionState.DISPATCHING, command = command) }.getOrDefault(false)

  /** Marks the acknowledgement sent for [executionId]. */
  fun markReported(executionId: String, kind: AckKind) {
    runCatching { database().update("executions", ContentValues().apply { put("reported", kind.name) }, "execution_id = ?", arrayOf(executionId)) }
  }

  /** Takes over a row another process left, so two recoveries never act on it. True for the one that wins. */
  fun adopt(record: ExecutionRecord): Boolean = runCatching {
    database().update("executions", ContentValues().apply { put("owner", owner); put("updated_at", clock()) },
      "execution_id = ? AND owner = ? AND state = ?", arrayOf(record.executionId, record.owner, record.state.name)) == 1
  }.getOrDefault(false)

  /** Terminal rows older than the retention, claims older than theirs and anything beyond [ExecutionJournalPolicy.MAX_ROWS]. */
  fun prune() {
    runCatching {
      transaction { db ->
        val terminal = ExecutionState.TERMINAL.joinToString(",") { "'${it.name}'" }
        val prunable = "state IN ($terminal) AND (question_at IS NULL OR (reported IS NOT NULL AND question_at <= ${clock() - InstinctQuestion.HOUR}))"
        db.delete("executions", "$prunable AND updated_at < ?", arrayOf((clock() - ExecutionJournalPolicy.RETENTION_MILLIS).toString()))
        db.delete("claims", "at < ?", arrayOf((clock() - ExecutionJournalPolicy.CLAIM_RETENTION_MILLIS).toString()))
        db.execSQL("DELETE FROM executions WHERE $prunable AND execution_id NOT IN " +
          "(SELECT execution_id FROM executions ORDER BY updated_at DESC LIMIT ${ExecutionJournalPolicy.MAX_ROWS})")
      }
    }
  }

  private fun getIn(db: SQLiteDatabase, executionId: String): ExecutionRecord? =
    db.rawQuery("SELECT * FROM executions WHERE execution_id = ?", arrayOf(executionId)).use { if (it.moveToFirst()) record(it) else null }

  private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
    val db = database()
    synchronized(lock) {
      db.beginTransaction()
      try {
        val value = block(db)
        db.setTransactionSuccessful()
        return value
      } finally { db.endTransaction() }
    }
  }

  private fun values(r: ExecutionRecord) = ContentValues().apply {
    put("execution_id", r.executionId); put("message_id", r.messageId); put("idx", r.index); put("transport", r.transport.name)
    put("line", r.line.take(MAX_TEXT)); put("received_at", r.receivedAt); put("updated_at", r.updatedAt); put("state", r.state.name)
    put("command", r.command); put("result", r.result); put("reason", r.reason); put("occurrence", r.occurrence); put("parent", r.parent)
    put("attempts", r.attempts); put("log_message", r.logMessage); put("owner", r.owner); r.deadline?.let { put("deadline", it) }
    put("reported", r.reported); put("attribution", r.attribution?.name); r.proposalId?.let { put("proposal_id", it) }
  }

  private fun records(cursor: Cursor): List<ExecutionRecord> = buildList { while (cursor.moveToNext()) record(cursor)?.let(::add) }

  private fun record(c: Cursor): ExecutionRecord? {
    fun s(name: String) = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
    fun l(name: String) = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getLong(it) }
    val state = ExecutionState.of(s("state")) ?: return null
    val transport = ExecutionTransport.of(s("transport")) ?: return null
    return ExecutionRecord(s("execution_id") ?: return null, s("message_id").orEmpty(), (l("idx") ?: 0).toInt(), transport,
      s("line").orEmpty(), l("received_at") ?: 0, l("updated_at") ?: 0, state, s("command"), s("result"), s("reason"),
      s("occurrence"), s("parent"), (l("attempts") ?: 0).toInt(), s("log_message").orEmpty(), s("owner").orEmpty(), l("deadline"),
      s("reported"), ExecutionAttribution.of(s("attribution")), l("proposal_id"))
  }

  /** Tests only: closes the database so a new one can be opened on another file. */
  internal fun close() = synchronized(lock) { runCatching { db?.close() }; db = null }

  companion object {
    const val FILE = "instinct-executions.db"
    private const val VERSION = 2
    private const val MAX_TEXT = 600
    private const val META_SEEN = "legacy_seen_imported"
    private const val META_LEGACY_CORRUPT = "legacy_seen_unreadable"

    /** This process. A non-terminal row owned by another was left by a process that died. Tests change it. */
    @Volatile internal var owner: String = UUID.randomUUID().toString()
    /** Wall-clock millis; the same clock as the mailbox's window. */
    internal var clock: () -> Long = { InstinctMailbox.clock() }

    private val instances = HashMap<String, InstinctExecutionJournal>()

    fun of(context: Context): InstinctExecutionJournal {
      val dir = context.applicationContext.noBackupFilesDir
      val file = File(dir, FILE)
      return synchronized(instances) {
        instances.getOrPut(file.absolutePath) { InstinctExecutionJournal(file, File(dir, InstinctMailboxSeen.FILE)) }
      }
    }

    /** Tests only. */
    internal fun resetAll() = synchronized(instances) { instances.values.forEach { it.close() }; instances.clear() }
  }
}

/** The journal as the one claim registry every transport shares. */
class JournalClaims(private val journal: InstinctExecutionJournal) : InstinctOrderClaims {
  override fun contains(id: String): Boolean = journal.containsClaim(id)
  override fun claimAll(ids: Collection<String>): Set<String> = journal.claimIds(ids, ExecutionTransport.POLL)

  /** Claims each order and records its lines in the same transaction, one order per transaction. */
  override fun claimOrders(orders: List<MailboxOrder>, transport: ExecutionTransport): Set<String> = orders.mapNotNullTo(LinkedHashSet()) { order ->
    val lines = InstinctProposalProtocol.orders(order.lines.joinToString("\n"))
    when (journal.admit(order.id, transport, lines, InstinctMailboxSignature.messageId(order.id),
      deadline = InstinctMailboxSignature.instant(order.at)?.plus(InstinctMailboxSignature.MAX_AGE_MILLIS))) {
      is InstinctExecutionJournal.Admission.Admitted -> order.id.lowercase()
      InstinctExecutionJournal.Admission.Repeated -> null
    }
  }
}
