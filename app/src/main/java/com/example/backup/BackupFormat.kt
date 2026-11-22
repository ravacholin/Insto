package com.example.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

enum class BackupCategory(val id: String, val label: String) {
  CREDENTIALS("credentials", "Claves de API y proveedores"),
  ASSISTANT("assistant", "Contactos y ajustes del asistente"),
  PHRASES("phrases", "Frases aprendidas"),
  ROUTINES("routines", "Rutinas"),
  PLACES("places", "Lugares guardados"),
  /** 1.20.0: receiving orders, autonomy, log, mailbox, doorbell and recurring orders. Older copies do not have it. */
  INSTINCT("instinct", "Instinct: órdenes, bitácora, buzón y timbre");

  companion object {
    fun byId(id: String): BackupCategory? = entries.firstOrNull { it.id == id }
    /**
     * Restore order: least sensitive first, secrets last, so an earlier failure never touches the keys. Instinct goes
     * after the assistant (its contact) and before the API keys.
     */
    val restoreOrder = listOf(PLACES, ROUTINES, PHRASES, ASSISTANT, INSTINCT, CREDENTIALS)
  }
}

/**
 * The decrypted content of a backup: explicit, versioned, stable field names. Never a dump of internal files.
 *
 * ```
 * {"format":"insto-config","schema":1,"createdAt":"2026-11-20T15:04:05Z",
 *  "app":{"versionName":"1.12.0","versionCode":21},"includesCredentials":true,
 *  "categories":{"credentials":{"v":1,…},"assistant":{"v":1,…},"phrases":{"v":1,…},"routines":{"v":1,…},"places":{"v":1,…}}}
 * ```
 *
 * [toString] never prints category content, which may hold API keys.
 */
class BackupPayload(
  val createdAt: String,
  val appVersionName: String?,
  val appVersionCode: Long?,
  val includesCredentials: Boolean,
  val categories: Map<BackupCategory, JSONObject>,
  /** Category ids this version does not know; reported and skipped. */
  val unknownCategories: List<String> = emptyList(),
  /** A learned-phrases file from Insto 1.11, migrated to a partial backup. */
  val legacy: Boolean = false,
) {
  override fun toString() = "BackupPayload(createdAt=$createdAt, categories=${categories.keys}, legacy=$legacy)"
}

object BackupFormat {
  const val FORMAT = "insto-config"
  const val SCHEMA = 1
  private const val LEGACY_FORMAT = "insto-learned-phrases"

  enum class Kind { ENCRYPTED, LEGACY_PHRASES, UNKNOWN }

  /** Schema migrations, from version N to N + 1. Schema 1 is the first; future versions add entries here. */
  val migrations: Map<Int, (JSONObject) -> JSONObject> = emptyMap()

  fun detect(bytes: ByteArray): Kind {
    if (BackupCrypto.isEnvelope(bytes)) return Kind.ENCRYPTED
    if (bytes.size > BackupCrypto.MAX_PLAINTEXT) return Kind.UNKNOWN
    val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return Kind.UNKNOWN
    return if (root.optString("format") == LEGACY_FORMAT) Kind.LEGACY_PHRASES else Kind.UNKNOWN
  }

  fun timestamp(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

  fun encode(payload: BackupPayload): ByteArray {
    val categories = JSONObject()
    payload.categories.forEach { (category, json) -> categories.put(category.id, json) }
    return JSONObject().put("format", FORMAT).put("schema", SCHEMA).put("createdAt", payload.createdAt)
      .put("app", JSONObject().put("versionName", payload.appVersionName).put("versionCode", payload.appVersionCode))
      .put("includesCredentials", payload.includesCredentials).put("categories", categories)
      .toString().toByteArray(Charsets.UTF_8)
  }

  /** Parses and migrates the decrypted JSON. Category content is validated later by each section. */
  fun decode(plain: ByteArray, migrations: Map<Int, (JSONObject) -> JSONObject> = this.migrations): BackupPayload {
    fun invalid(message: String): Nothing = throw BackupException(BackupException.Kind.INVALID_CONTENT, message)
    if (plain.size > BackupCrypto.MAX_PLAINTEXT) throw BackupException(BackupException.Kind.TOO_LARGE, "El contenido es demasiado grande.")
    var root = runCatching { JSONObject(plain.toString(Charsets.UTF_8)) }.getOrNull() ?: invalid("El contenido de la copia está dañado.")
    if (root.optString("format") != FORMAT) invalid("El archivo no es una copia de configuración de Insto.")
    var schema = root.optInt("schema", -1)
    if (schema > SCHEMA) throw BackupException(BackupException.Kind.NEWER_VERSION,
      "La copia se creó con una versión más nueva de Insto. Actualizá Insto para restaurarla.")
    if (schema < 0) invalid("La copia no indica su versión de esquema.")
    while (schema < SCHEMA) {
      val step = migrations[schema] ?: throw BackupException(BackupException.Kind.UNSUPPORTED_VERSION, "Versión de copia no reconocida.")
      root = step(root)
      schema++
    }
    val categoriesJson = root.optJSONObject("categories") ?: invalid("La copia no tiene categorías.")
    val categories = linkedMapOf<BackupCategory, JSONObject>()
    val unknown = mutableListOf<String>()
    for (key in categoriesJson.keys()) {
      val category = BackupCategory.byId(key)
      val value = categoriesJson.optJSONObject(key) ?: invalid("La categoría «$key» está dañada.")
      if (category == null) unknown += key.take(40) else categories[category] = value
    }
    val app = root.optJSONObject("app")
    return BackupPayload(
      createdAt = root.optString("createdAt").take(40),
      appVersionName = app?.optString("versionName")?.takeIf { it.isNotBlank() }?.take(40),
      appVersionCode = app?.optLong("versionCode")?.takeIf { it > 0 },
      includesCredentials = root.optBoolean("includesCredentials", false) &&
        (BackupCategory.CREDENTIALS in categories || BackupCategory.INSTINCT in categories),
      categories = categories,
      unknownCategories = unknown,
    )
  }

  /** Migrates a learned-phrases export of Insto 1.11 (plain JSON, no keys) into a backup with one category. */
  fun decodeLegacyPhrases(bytes: ByteArray): BackupPayload {
    val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    if (root?.optString("format") != LEGACY_FORMAT) throw BackupException(BackupException.Kind.NOT_A_BACKUP, "El archivo no es una copia de frases de Insto.")
    if (root.optInt("version", 1) != 1) throw BackupException(BackupException.Kind.UNSUPPORTED_VERSION, "Versión de copia de frases no reconocida.")
    val phrases = root.optJSONArray("phrases") ?: JSONArray()
    return BackupPayload("", null, null, false, mapOf(BackupCategory.PHRASES to JSONObject().put("v", 1).put("phrases", phrases)), legacy = true)
  }
}
