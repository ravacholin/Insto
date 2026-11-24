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
          if (opened.version < 2) opened.execSQL("ALTER TABLE executions ADD COLUMN question_at INTEGER")
          opened.execSQL("""CREATE TABLE IF NOT EXISTS context_rules (
            id TEXT PRIMARY KEY, line TEXT NOT NULL, expires_at INTEGER NOT NULL, enabled INTEGER NOT NULL,
            matched INTEGER NOT NULL, candidate_at INTEGER, last_at INTEGER, status TEXT NOT NULL)""")
          createPlans(opened)
          val hasExecutionData = opened.rawQuery("PRAGMA table_info(executions)", null).use { columns ->
            val name = columns.getColumnIndexOrThrow("name")
            generateSequence { if (columns.moveToNext()) columns.getString(name) else null }.any { it == "execution_data" }
          }
          if (!hasExecutionData) opened.execSQL("ALTER TABLE executions ADD COLUMN execution_data TEXT")
          opened.execSQL("""CREATE TABLE IF NOT EXISTS instinct_clock (
            id TEXT PRIMARY KEY, at INTEGER NOT NULL, elapsed_at INTEGER, boot INTEGER NOT NULL, label TEXT NOT NULL,
            state TEXT NOT NULL, updated_at INTEGER NOT NULL)""")
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

  /** 1.23.0: plans of several steps. A migration from v3 only adds these two tables; every other row stays. */
  private fun createPlans(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS plans (
      id TEXT PRIMARY KEY, root TEXT, line TEXT NOT NULL, source TEXT NOT NULL, message TEXT NOT NULL, fingerprint TEXT NOT NULL,
      state TEXT NOT NULL, block INTEGER NOT NULL, sub INTEGER NOT NULL, branch INTEGER, fallback INTEGER NOT NULL,
      approval TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deadline INTEGER NOT NULL,
      wake_at INTEGER, trigger TEXT, event TEXT, question TEXT, stash TEXT NOT NULL, result TEXT, reason TEXT,
      owner TEXT NOT NULL, closed INTEGER NOT NULL, restore_on_close INTEGER NOT NULL)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS plans_state ON plans (state)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS plan_steps (
      plan_id TEXT NOT NULL, pos TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL,
      execution_id TEXT, updated_at INTEGER NOT NULL, reported INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (plan_id, pos))""")
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
    val action = choice?.takeIf { outcome.state == ExecutionState.ANSWERED }?.let(question.actions::getOrNull)?.let { line ->
      val message = "decision:$id"
      ExecutionRecord(ExecutionIds.of(message, 0), message, 0, ExecutionTransport.DECISION, line, now, now,
        ExecutionState.RECEIVED, parent = id, deadline = question.expiresAt, owner = owner).also {
        db.insertOrThrow("executions", null, values(it))
      }
    }
    outcome.copy(action = action)
  }

  fun pendingQuestionReplies(limit: Int = 1): List<ExecutionRecord> = database().rawQuery(
    "SELECT * FROM executions WHERE reported IS NULL AND ((question_at IS NOT NULL AND state IN ('ANSWERED','EXPIRED','CANCELLED')) OR " +
      "(transport IN ('DECISION','CONTEXT') AND state IN ('VERIFIED','HANDED_OFF','FAILED','CANCELLED','UNCERTAIN','EXPIRED'))) ORDER BY updated_at, execution_id LIMIT ?", arrayOf(limit.toString())
  ).use(::records)

  fun markQuestionReplies(ids: Set<String>) = transaction { db ->
    for (id in ids) db.update("executions", ContentValues().apply { put("reported", InstinctLogEvents.RESPONSE_KIND) },
      "execution_id = ? AND (question_at IS NOT NULL OR transport IN ('DECISION','CONTEXT'))", arrayOf(id))
  }

  internal fun contextRules(): List<ContextRule> = contextRules(database())

  private fun contextRules(db: SQLiteDatabase): List<ContextRule> =
    db.rawQuery("SELECT line, expires_at, enabled, matched, candidate_at, last_at, status FROM context_rules ORDER BY id", null).use { rows ->
      buildList {
        while (rows.moveToNext()) {
          val spec = ContextRuleSpec.parse(rows.getString(0)) ?: continue
          add(ContextRule(spec, rows.getLong(1), rows.getInt(2) != 0, rows.getInt(3) != 0,
            if (rows.isNull(4)) null else rows.getLong(4), if (rows.isNull(5)) null else rows.getLong(5), rows.getString(6)))
        }
      }
    }

  internal fun saveContextRule(rule: ContextRule): Boolean = transaction { db ->
    val now = clock()
    db.delete("context_rules", "expires_at <= ?", arrayOf(now.toString()))
    if (rule.expiresAt <= now) return@transaction false
    val rules = contextRules(db)
    if (rules.any { it.id == rule.id }) return@transaction true
    if (rules.size >= ContextRuleSpec.MAX_RULES) return@transaction false
    writeContextRule(db, rule)
    true
  }

  internal fun changeContextRule(id: String, enabled: Boolean?): Boolean = transaction { db ->
    val rule = contextRules(db).singleOrNull { it.shortId == id || it.id == id } ?: return@transaction false
    if (enabled != true) cancelContextExecutions(db, rule.id)
    if (enabled == null) return@transaction db.delete("context_rules", "id = ?", arrayOf(rule.id)) == 1
    if (rule.expiresAt <= clock()) return@transaction false
    if (rule.enabled != enabled) writeContextRule(db, rule.copy(enabled = enabled, matched = false, candidateAt = null,
      status = if (enabled) "esperando condiciones" else "desactivada"))
    true
  }

  internal fun pauseContextRules() = transaction { db ->
    cancelContextExecutions(db, null)
    contextRules(db).filter { it.matched || it.candidateAt != null }.forEach {
      writeContextRule(db, it.copy(matched = false, candidateAt = null, status = "esperando condiciones"))
    }
  }

  private fun cancelContextExecutions(db: SQLiteDatabase, id: String?) {
    val selector = "transport = 'CONTEXT' AND state IN ('RECEIVED','READY','VALIDATED','WAITING')"
    db.update("executions", ContentValues().apply {
      put("state", ExecutionState.CANCELLED.name); put("updated_at", clock()); put("reason", "regla desactivada")
    }, if (id == null) selector else "$selector AND occurrence = ?", id?.let { arrayOf(it) })
  }

  private fun writeContextRule(db: SQLiteDatabase, rule: ContextRule) {
    db.insertWithOnConflict("context_rules", null, ContentValues().apply {
      put("id", rule.id); put("line", rule.spec.line); put("expires_at", rule.expiresAt)
      put("enabled", if (rule.enabled) 1 else 0); put("matched", if (rule.matched) 1 else 0)
      put("candidate_at", rule.candidateAt); put("last_at", rule.lastAt); put("status", rule.status)
    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
  }

  /** One transaction owns the edge, its persistent brake and the dispatch row. */
  internal fun evaluateContextRules(snapshot: ContextSnapshot, now: Long): List<ExecutionRecord> = transaction { db ->
    var recent = db.rawQuery("SELECT COUNT(*) FROM executions WHERE transport = 'CONTEXT' AND received_at > ?",
      arrayOf((now - ContextRuleSpec.WINDOW).toString())).use { it.moveToFirst(); it.getInt(0) }
    buildList {
      for (rule in contextRules(db)) {
        val evaluation = rule.evaluate(snapshot, now, recent < ContextRuleSpec.MAX_FIRINGS)
        if (evaluation.rule != rule) writeContextRule(db, evaluation.rule)
        if (!evaluation.fire) continue
        val message = "context:${rule.id}:$now"
        val row = ExecutionRecord(ExecutionIds.of(message, 0), message, 0, ExecutionTransport.CONTEXT, rule.spec.action,
          now, now, ExecutionState.RECEIVED, owner = owner, occurrence = rule.id,
          deadline = minOf(rule.expiresAt, now + ExecutionJournalPolicy.WAITING_TTL_MILLIS))
        db.insertOrThrow("executions", null, values(row))
        add(row)
        recent++
      }
    }
  }

  internal fun contextEnabled(row: ExecutionRecord): Boolean = contextEnabled(database(), row, clock())

  private fun contextEnabled(db: SQLiteDatabase, row: ExecutionRecord, now: Long): Boolean {
    if (now >= (row.deadline ?: 0)) return false
    return db.rawQuery("SELECT 1 FROM context_rules WHERE id = ? AND enabled = 1 AND expires_at > ?",
      arrayOf(row.occurrence.orEmpty(), now.toString())).use { it.moveToFirst() }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Plans (1.23.0)
  // ---------------------------------------------------------------------------------------------------------------

  /** Inserts [plan] unless it exists (the same order twice) or [InstinctPlanSpec.MAX_ACTIVE] plans are active. */
  internal fun insertPlan(plan: PlanRecord): PlanInsert = transaction { db ->
    if (planIn(db, plan.id) != null) return@transaction PlanInsert.EXISTS
    val active = db.rawQuery("SELECT COUNT(*) FROM plans WHERE state IN (${PlanState.ACTIVE.joinToString(",") { "'${it.name}'" }})", null)
      .use { it.moveToFirst(); it.getInt(0) }
    if (active >= InstinctPlanSpec.MAX_ACTIVE) return@transaction PlanInsert.FULL
    db.insertOrThrow("plans", null, planValues(plan))
    PlanInsert.ADDED
  }

  internal enum class PlanInsert { ADDED, EXISTS, FULL }

  internal fun plan(id: String): PlanRecord? = planIn(database(), id)

  internal fun planByShort(short: String): PlanRecord? {
    val id = PlanIds.parseShort(short) ?: return null
    return database().rawQuery("SELECT * FROM plans WHERE id LIKE ? ORDER BY created_at DESC LIMIT 1", arrayOf("$id%"))
      .use { if (it.moveToFirst()) plan(it) else null }
  }

  internal fun plans(active: Boolean? = null, limit: Int = 50): List<PlanRecord> {
    val names = PlanState.ACTIVE.joinToString(",") { "'${it.name}'" }
    val where = when (active) { true -> "WHERE state IN ($names)"; false -> "WHERE state NOT IN ($names)"; null -> "" }
    return database().rawQuery("SELECT * FROM plans $where ORDER BY updated_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
      buildList { while (c.moveToNext()) plan(c)?.let(::add) }
    }
  }

  /** Terminal plans whose final work (restoration, log, notification) a process death interrupted. */
  internal fun unclosedPlans(): List<PlanRecord> = database().rawQuery("SELECT * FROM plans WHERE closed = 0", null).use { c ->
    buildList { while (c.moveToNext()) plan(c)?.takeIf { it.state.terminal }?.let(::add) }
  }

  /**
   * Compare-and-set on the plan: [change] applies only while its state is one of [from] (any non-terminal one when
   * null). A terminal state is never left. Null when it did not apply.
   */
  internal fun updatePlan(id: String, from: Set<PlanState>? = null, change: (PlanRecord) -> PlanRecord): PlanRecord? = transaction { db ->
    val current = planIn(db, id) ?: return@transaction null
    if (current.state !in (from ?: PlanState.ACTIVE)) return@transaction null
    val next = change(current).copy(id = current.id, updatedAt = clock(), owner = owner)
    if (current.state.terminal && next.state != current.state) return@transaction null
    check(db.update("plans", planValues(next), "id = ?", arrayOf(id)) == 1)
    next
  }

  /** Marks a terminal plan's final work done. */
  internal fun closePlan(id: String) = transaction { db ->
    db.update("plans", ContentValues().apply { put("closed", 1) }, "id = ? AND state IN (${PlanState.entries.filter { it.terminal }
      .joinToString(",") { "'${it.name}'" }})", arrayOf(id))
  }

  /** Takes over a plan another process left: true for the one that wins. */
  internal fun adoptPlan(plan: PlanRecord): Boolean = runCatching {
    database().update("plans", ContentValues().apply { put("owner", owner) }, "id = ? AND owner = ?", arrayOf(plan.id, plan.owner)) == 1
  }.getOrDefault(false)

  internal fun putPlanStep(step: PlanStepRecord) = transaction { db ->
    db.insertWithOnConflict("plan_steps", null, ContentValues().apply {
      put("plan_id", step.plan); put("pos", step.position); put("text", step.text.take(MAX_TEXT)); put("status", step.status)
      put("detail", step.detail.take(MAX_TEXT)); put("execution_id", step.executionId); put("updated_at", clock())
      put("reported", if (step.reported) 1 else 0)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  internal fun planSteps(plan: String): List<PlanStepRecord> =
    database().rawQuery("SELECT * FROM plan_steps WHERE plan_id = ? ORDER BY updated_at, pos", arrayOf(plan)).use { c ->
      buildList {
        while (c.moveToNext()) add(PlanStepRecord(c.getString(c.getColumnIndexOrThrow("plan_id")), c.getString(c.getColumnIndexOrThrow("pos")),
          c.getString(c.getColumnIndexOrThrow("text")), c.getString(c.getColumnIndexOrThrow("status")),
          c.getString(c.getColumnIndexOrThrow("detail")), c.getColumnIndexOrThrow("execution_id").let { if (c.isNull(it)) null else c.getString(it) },
          c.getLong(c.getColumnIndexOrThrow("updated_at")), c.getInt(c.getColumnIndexOrThrow("reported")) != 0))
      }
    }

  internal fun markPlanStepsReported(plan: String, positions: Collection<String>) = transaction { db ->
    positions.forEach { db.update("plan_steps", ContentValues().apply { put("reported", 1) }, "plan_id = ? AND pos = ?", arrayOf(plan, it)) }
  }

  private fun planIn(db: SQLiteDatabase, id: String): PlanRecord? =
    db.rawQuery("SELECT * FROM plans WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) plan(it) else null }

  private fun planValues(p: PlanRecord) = ContentValues().apply {
    put("id", p.id); put("root", p.root); put("line", p.line.take(MAX_TEXT)); put("source", p.source.name); put("message", p.message)
    put("fingerprint", p.fingerprint); put("state", p.state.name); put("block", p.block); put("sub", p.sub); put("branch", p.branch)
    put("fallback", if (p.fallback) 1 else 0); put("approval", p.approval.name); put("created_at", p.createdAt)
    put("updated_at", p.updatedAt); put("deadline", p.deadline); put("wake_at", p.wakeAt); put("trigger", p.trigger?.name)
    put("event", PlanCodec.event(p.event)); put("question", p.question); put("stash", PlanCodec.stash(p.stash))
    put("result", p.result?.take(MAX_TEXT)); put("reason", p.reason?.take(MAX_TEXT)); put("owner", p.owner)
    put("closed", if (p.closed) 1 else 0); put("restore_on_close", if (p.restoreOnClose) 1 else 0)
  }

  private fun plan(c: Cursor): PlanRecord? {
    fun s(name: String) = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
    fun l(name: String) = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getLong(it) }
    return PlanRecord(s("id") ?: return null, s("root"), s("line").orEmpty(),
      ProposalSource.entries.firstOrNull { it.name == s("source") } ?: ProposalSource.BUZON, s("message").orEmpty(),
      s("fingerprint").orEmpty(), PlanState.of(s("state")) ?: return null, (l("block") ?: 0).toInt(), (l("sub") ?: 0).toInt(),
      l("branch")?.toInt(), l("fallback") == 1L, PlanApproval.of(s("approval")), l("created_at") ?: 0, l("updated_at") ?: 0,
      l("deadline") ?: 0, l("wake_at"), PlanTrigger.of(s("trigger")), PlanCodec.event(s("event")), s("question"),
      PlanCodec.stash(s("stash")), s("result"), s("reason"), s("owner").orEmpty(), l("closed") == 1L, l("restore_on_close") != 0L)
  }

  /**
   * Moves [executionId] to [to] if [ExecutionTransitions] allows it (and, with [from], only from those states). False
   * when not allowed or not found; throws when it cannot write.
   */
  fun transition(executionId: String, to: ExecutionState, result: String? = null, reason: String? = null,
    command: String? = null, from: Set<ExecutionState>? = null, attribution: ExecutionAttribution? = null,
    proposalId: Long? = null, occurrence: String? = null, reported: AckKind? = null, executionData: String? = null): Boolean = transaction { db ->
    val current = getIn(db, executionId) ?: return@transaction false
    if (from != null && current.state !in from) return@transaction false
    if (!ExecutionTransitions.allowed(current.state, to)) return@transaction false
    if (to == ExecutionState.DISPATCHING && current.transport == ExecutionTransport.DECISION &&
      clock() >= (current.deadline ?: 0)) return@transaction false
    if (to == ExecutionState.DISPATCHING && current.transport == ExecutionTransport.CONTEXT &&
      !contextEnabled(db, current, clock())) return@transaction false
    db.update("executions", ContentValues().apply {
      put("state", to.name); put("updated_at", clock()); put("owner", owner)
      result?.let { put("result", it.take(MAX_TEXT)) }
      executionData?.let { put("execution_data", it) }
      reason?.let { put("reason", it.take(MAX_TEXT)) }
      command?.let { put("command", it) }
      attribution?.let { put("attribution", it.name) }
      proposalId?.let { put("proposal_id", it) }
      occurrence?.let { put("occurrence", it) }
      if (current.transport !in LOCAL) reported?.let { put("reported", it.name) }
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
    runCatching { database().update("executions", ContentValues().apply { put("reported", kind.name) }, "execution_id = ? AND transport NOT IN ('DECISION','CONTEXT')", arrayOf(executionId)) }
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
        val contextWindow = clock() - ContextRuleSpec.WINDOW
        val prunable = "state IN ($terminal) AND (transport != 'CONTEXT' OR received_at <= $contextWindow) AND ((question_at IS NULL AND transport != 'DECISION') OR (reported IS NOT NULL AND (question_at IS NULL OR question_at <= ${clock() - InstinctQuestion.HOUR})))"
        db.delete("executions", "$prunable AND updated_at < ?", arrayOf((clock() - ExecutionJournalPolicy.RETENTION_MILLIS).toString()))
        db.delete("instinct_clock", "state NOT IN (?, ?) AND updated_at < ?",
          (InstinctClockState.ACTIVE.map { it.name } + (clock() - ExecutionJournalPolicy.RETENTION_MILLIS).toString()).toTypedArray())
        db.delete("claims", "at < ?", arrayOf((clock() - ExecutionJournalPolicy.CLAIM_RETENTION_MILLIS).toString()))
        val old = (clock() - ExecutionJournalPolicy.RETENTION_MILLIS).toString()
        db.delete("plan_steps", "plan_id IN (SELECT id FROM plans WHERE closed = 1 AND updated_at < ?)", arrayOf(old))
        db.delete("plans", "closed = 1 AND updated_at < ?", arrayOf(old))
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
      s("reported"), ExecutionAttribution.of(s("attribution")), l("proposal_id"), s("execution_data"))
  }

  internal fun takeCalendarUndo(id: String, expected: String): Boolean = transaction { db ->
    db.update("executions", ContentValues().apply { putNull("execution_data") },
      "execution_id = ? AND state = ? AND execution_data = ?", arrayOf(id, ExecutionState.VERIFIED.name, expected)) == 1
  }

  internal fun insertClock(clock: InstinctClockEntry): Boolean = transaction { db ->
    val active = InstinctClockState.ACTIVE
    val count = db.rawQuery("SELECT COUNT(*) FROM instinct_clock WHERE state IN (?, ?)", active.map { it.name }.toTypedArray())
      .use { it.moveToFirst(); it.getInt(0) }
    if (count >= 12) return@transaction false
    db.insertWithOnConflict("instinct_clock", null, ContentValues().apply {
      put("id", clock.id)
      put("at", clock.at)
      put("elapsed_at", clock.elapsedAt)
      put("boot", clock.boot)
      put("label", clock.label)
      put("state", clock.state.name)
      put("updated_at", this@InstinctExecutionJournal.clock())
    }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
  }

  internal fun clockEntries(id: String? = null): List<InstinctClockEntry> {
    val states = InstinctClockState.ACTIVE.map { it.name }.toTypedArray()
    val where = if (id == null) "state IN (?, ?)" else "id = ?"
    return database().rawQuery("SELECT id, at, elapsed_at, boot, label, state FROM instinct_clock WHERE $where",
      if (id == null) states else arrayOf(id)).use { cursor ->
      buildList {
        while (cursor.moveToNext()) add(InstinctClockEntry(cursor.getString(0), cursor.getLong(1),
          if (cursor.isNull(2)) null else cursor.getLong(2), cursor.getInt(3), cursor.getString(4),
          InstinctClockState.valueOf(cursor.getString(5))))
      }
    }
  }

  internal fun moveClock(id: String, from: InstinctClockState, to: InstinctClockState): Boolean = transaction { db ->
    db.update("instinct_clock", ContentValues().apply {
      put("state", to.name)
      put("updated_at", clock())
    }, "id = ? AND state = ?", arrayOf(id, from.name)) == 1
  }

  /** Tests only: closes the database so a new one can be opened on another file. */
  internal fun close() = synchronized(lock) { runCatching { db?.close() }; db = null }

  companion object {
    const val FILE = "instinct-executions.db"
    private val LOCAL = setOf(ExecutionTransport.DECISION, ExecutionTransport.CONTEXT)
    private const val VERSION = 5
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
