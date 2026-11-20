package com.example.backup

import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

enum class RestoreMode { MERGE, REPLACE }

/** Choices that need explicit consent. Existing keys are never replaced by a merge without them. */
data class RestoreOptions(val replaceGroqKey: Boolean = false, val replaceGeminiKey: Boolean = false)

/** What a category holds, for the preview. Never contains a secret. */
data class CategorySummary(val description: String)

/** A category ready to be written: computed from the current state, nothing written yet. */
interface PreparedChange {
  /** What will happen, e.g. «Se agregan 3 frases». */
  val changes: List<String>
  /** What will be overwritten or removed. Shown before confirming. */
  val warnings: List<String>
  /** What needs the user's attention afterwards, e.g. a key kept because it differed. */
  val attention: List<String>
  /** Captures the current state of this category, in memory. */
  suspend fun snapshot(): Restorable
  suspend fun commit()
}

fun interface Restorable { suspend fun restore() }

/** One store's part of a backup. Implementations reuse the store's own validation. */
interface BackupSection {
  val category: BackupCategory
  /** Current version of this category's content. */
  val version: Int get() = 1
  /** The category content, or null when there is nothing to save. */
  suspend fun export(): JSONObject?
  /** Describes what exists now, for the export dialog, or null when empty. */
  suspend fun describeCurrent(): String?
  /** Full validation without writing. Throws [IllegalArgumentException] with a message for the user. */
  fun validate(json: JSONObject): CategorySummary
  /** Brings older category content to [version]. */
  fun migrate(json: JSONObject, from: Int): JSONObject =
    throw IllegalArgumentException("La categoría usa una versión que esta versión de Insto no sabe leer.")
  suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange
}

/** Marks a restore in progress, without data, so an interrupted one is reported on the next start. */
interface RestoreMarker {
  fun begin(categories: List<BackupCategory>)
  fun end()
  /** Category labels of an interrupted restore, or null. */
  fun pending(): List<String>?
}

class FileRestoreMarker(private val file: File) : RestoreMarker {
  override fun begin(categories: List<BackupCategory>) {
    val temporary = File(file.parentFile, file.name + ".tmp")
    temporary.writeText(JSONObject().put("categories", org.json.JSONArray(categories.map { it.id }))
      .put("at", System.currentTimeMillis()).toString())
    check(temporary.renameTo(file)) { "No se pudo iniciar la restauración" }
  }
  override fun end() { if (file.exists()) file.delete() }
  override fun pending(): List<String>? {
    if (!file.exists()) return null
    val ids = runCatching { JSONObject(file.readText()).getJSONArray("categories") }.getOrNull() ?: return listOf("configuración")
    return (0 until ids.length()).mapNotNull { BackupCategory.byId(ids.optString(it))?.label }
  }
}

/** A validated backup ready to preview. [toString] never prints content. */
class RestorePlan internal constructor(
  val createdAt: String,
  val appVersionName: String?,
  val includesCredentials: Boolean,
  val legacy: Boolean,
  internal val content: Map<BackupCategory, JSONObject>,
  val summaries: Map<BackupCategory, CategorySummary>,
  /** Category label (or id) → why it cannot be restored. */
  val unavailable: Map<String, String>,
) {
  val categories: Set<BackupCategory> get() = summaries.keys
  override fun toString() = "RestorePlan(categories=$categories, unavailable=${unavailable.keys})"
}

sealed interface OpenResult {
  data object NeedsPassword : OpenResult
  data class Invalid(val message: String) : OpenResult
  data class Ready(val plan: RestorePlan) : OpenResult
}

data class CategoryPreview(val changes: List<String>, val warnings: List<String>, val attention: List<String>, val error: String?)

data class RestoreReport(
  val success: Boolean,
  val restored: List<String>,
  val attention: List<String>,
  val failure: String? = null,
  /** Categories written before the failure and returned to their previous state. */
  val rolledBack: List<BackupCategory> = emptyList(),
  /** Categories that could not be returned to their previous state. */
  val notRolledBack: List<BackupCategory> = emptyList(),
)

/**
 * Exports and imports Insto's configuration through [BackupSection]s. Local only: no network, no providers.
 * Reading a backup never executes commands, arms sending or starts services: sections only write configuration.
 *
 * Restoring is staged: every chosen category is validated and prepared against the current state first; nothing is
 * written if any fails. Then the current state of those categories is captured in memory, a marker without data is
 * written, and categories are committed in [BackupCategory.restoreOrder]. If one fails, the ones already written are
 * restored from the captures. This is not globally atomic: if the process dies between categories, the captures are
 * lost (persisting them would put decrypted keys on disk); the marker then reports the interrupted restore.
 */
class ConfigurationBackupManager(
  private val sections: List<BackupSection>,
  private val appVersionName: String,
  private val appVersionCode: Long,
  private val marker: RestoreMarker,
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: SecureRandom = SecureRandom(),
  private val iterations: Int = BackupCrypto.ITERATIONS,
) {
  private fun section(category: BackupCategory) = sections.first { it.category == category }
  val categories: List<BackupCategory> get() = sections.map { it.category }

  suspend fun describeCurrent(): Map<BackupCategory, String?> = sections.associate { it.category to it.describeCurrent() }

  fun interruptedRestore(): List<String>? = marker.pending()
  fun dismissInterruptedRestore() = marker.end()

  /** Encrypts the selected categories. API keys exist in clear only in memory, inside this call. */
  suspend fun export(selection: Set<BackupCategory>, password: CharArray): ByteArray {
    BackupCrypto.checkPassword(password)
    val categories = linkedMapOf<BackupCategory, JSONObject>()
    for (section in sections) {
      if (section.category !in selection) continue
      section.export()?.let { categories[section.category] = it.put("v", section.version) }
    }
    val payload = BackupPayload(BackupFormat.timestamp(clock()), appVersionName, appVersionCode,
      BackupCategory.CREDENTIALS in categories, categories)
    val plain = BackupFormat.encode(payload)
    try {
      return BackupCrypto.encrypt(plain, password, random, iterations)
    } finally { plain.fill(0) }
  }

  /** Detects, decrypts, migrates and validates every category. Writes nothing. Slow: run off the main thread. */
  fun open(bytes: ByteArray, password: CharArray?): OpenResult {
    val payload = try {
      when (BackupFormat.detect(bytes)) {
        BackupFormat.Kind.ENCRYPTED -> {
          if (password == null || password.isEmpty()) return OpenResult.NeedsPassword
          val plain = BackupCrypto.decrypt(bytes, password)
          try { BackupFormat.decode(plain) } finally { plain.fill(0) }
        }
        BackupFormat.Kind.LEGACY_PHRASES -> BackupFormat.decodeLegacyPhrases(bytes)
        BackupFormat.Kind.UNKNOWN -> return OpenResult.Invalid(
          if (bytes.size > BackupCrypto.MAX_FILE) "El archivo es demasiado grande para ser una copia de Insto."
          else "El archivo no es una copia de seguridad de Insto.")
      }
    } catch (error: BackupException) { return OpenResult.Invalid(error.message ?: "No se pudo abrir la copia.") }
    val content = linkedMapOf<BackupCategory, JSONObject>()
    val summaries = linkedMapOf<BackupCategory, CategorySummary>()
    val unavailable = linkedMapOf<String, String>()
    payload.unknownCategories.forEach { unavailable[it] = "Categoría desconocida para esta versión de Insto. Se omite." }
    for ((category, raw) in payload.categories) {
      val section = sections.firstOrNull { it.category == category } ?: continue
      try {
        val version = raw.optInt("v", -1)
        require(version >= 0) { "La categoría no indica su versión." }
        if (version > section.version) throw IllegalArgumentException("Se creó con una versión más nueva de Insto.")
        val json = if (version < section.version) section.migrate(raw, version) else raw
        summaries[category] = section.validate(json)
        content[category] = json
      } catch (error: IllegalArgumentException) {
        unavailable[category.label] = error.message ?: "Contenido no válido."
      } catch (error: org.json.JSONException) {
        unavailable[category.label] = "Contenido dañado."
      }
    }
    if (summaries.isEmpty()) return OpenResult.Invalid(
      unavailable.entries.firstOrNull()?.let { "${it.key}: ${it.value}" } ?: "La copia no tiene configuración para restaurar.")
    return OpenResult.Ready(RestorePlan(payload.createdAt, payload.appVersionName, payload.includesCredentials,
      payload.legacy, content, summaries, unavailable))
  }

  /** What restoring with these choices would do, computed against the current state. Writes nothing. */
  suspend fun preview(plan: RestorePlan, choices: Map<BackupCategory, RestoreMode>, options: RestoreOptions): Map<BackupCategory, CategoryPreview> =
    choices.filterKeys { it in plan.content }.mapValues { (category, mode) ->
      try {
        val prepared = section(category).prepare(plan.content.getValue(category), mode, options)
        CategoryPreview(prepared.changes, prepared.warnings, prepared.attention, null)
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { CategoryPreview(emptyList(), emptyList(), emptyList(), message(error)) }
    }

  suspend fun restore(plan: RestorePlan, choices: Map<BackupCategory, RestoreMode>, options: RestoreOptions): RestoreReport {
    val chosen = BackupCategory.restoreOrder.filter { it in choices && it in plan.content }
    if (chosen.isEmpty()) return RestoreReport(false, emptyList(), emptyList(), "No elegiste ninguna categoría para restaurar.")
    // 1. Validate and prepare everything before writing anything.
    val prepared = mutableListOf<Pair<BackupCategory, PreparedChange>>()
    for (category in chosen) {
      try {
        prepared += category to section(category).prepare(plan.content.getValue(category), choices.getValue(category), options)
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        return RestoreReport(false, emptyList(), emptyList(), "${category.label}: ${message(error)} No se cambió nada.")
      }
    }
    // 2. Capture the current state of the chosen categories, in memory.
    val captures = try {
      prepared.map { (category, change) -> category to change.snapshot() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
      return RestoreReport(false, emptyList(), emptyList(), "No se pudo leer la configuración actual. No se cambió nada.")
    }
    try { marker.begin(chosen) } catch (_: Exception) {
      return RestoreReport(false, emptyList(), emptyList(), "No se pudo iniciar la restauración. No se cambió nada.")
    }
    // 3. Commit in order; roll back what was written if a later category fails.
    val committed = mutableListOf<BackupCategory>()
    for ((category, change) in prepared) {
      try {
        change.commit()
        committed += category
      } catch (error: Exception) {
        val rolledBack = mutableListOf<BackupCategory>()
        val notRolledBack = mutableListOf<BackupCategory>()
        // The failed category may be partly written; it is restored too.
        for (done in (committed + category).asReversed()) {
          val capture = captures.first { it.first == done }.second
          try { capture.restore(); rolledBack += done } catch (_: Exception) { notRolledBack += done }
        }
        if (notRolledBack.isEmpty()) marker.end()
        val tail = if (notRolledBack.isEmpty()) " Se volvió a la configuración anterior."
          else " No se pudo volver al estado anterior de: ${notRolledBack.joinToString { it.label }}. Revisalas."
        return RestoreReport(false, emptyList(), emptyList(), "${category.label}: ${message(error)}$tail",
          rolledBack - notRolledBack.toSet(), notRolledBack)
      }
    }
    marker.end()
    return RestoreReport(true, prepared.flatMap { (category, change) -> change.changes.map { "${category.label}: $it" } },
      prepared.flatMap { it.second.attention })
  }

  private fun message(error: Exception): String = when (error) {
    is IllegalArgumentException, is IllegalStateException, is BackupException -> error.message ?: "Contenido no válido."
    is org.json.JSONException -> "Contenido dañado."
    else -> "No se pudo escribir la configuración."
  }
}
