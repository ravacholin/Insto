package com.example.assist

import org.json.JSONArray
import org.json.JSONObject

/**
 * Local counters of automatic WhatsApp sends. Only closed enumerations and WhatsApp's version code are kept: never
 * text, audio, file names, transcriptions, phone numbers or contact names. Pure, so it is tested on the JVM.
 */
object AutoSendMetrics {
  enum class Flavor(val id: String, val label: String, val packageName: String) {
    WHATSAPP("whatsapp", "WhatsApp", "com.whatsapp"),
    BUSINESS("business", "WhatsApp Business", "com.whatsapp.w4b");
    companion object {
      fun of(packageName: String?): Flavor? = entries.firstOrNull { it.packageName == packageName }
      fun byId(id: String?): Flavor? = entries.firstOrNull { it.id == id }
    }
  }

  enum class Kind(val id: String, val label: String) {
    TEXT("text", "texto"), AUDIO("audio", "audio"), FILE("file", "archivo");
    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } }
  }

  enum class Outcome(val id: String, val label: String) {
    CONFIRMED("confirmed", "confirmado"),
    NOT_FOUND("not_found", "no apareció el control esperado"),
    AMBIGUOUS("ambiguous", "había más de un control posible"),
    RECIPIENT_MISMATCH("recipient_mismatch", "el chat no coincidía con el contacto"),
    UNVERIFIED_CONTENT("unverified_content", "no se pudo verificar el contenido"),
    UNCERTAIN("uncertain", "se tocó Enviar sin confirmación"),
    TIMEOUT("timeout", "WhatsApp no respondió a tiempo");
    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } }
  }

  data class Row(
    val flavor: Flavor,
    val versionCode: Long,
    val kind: Kind,
    val strategy: String,
    val outcome: Outcome,
    val count: Int,
    val lastAt: Long,
  )

  data class Attempt(val flavor: Flavor, val versionCode: Long, val outcome: Outcome, val at: Long)

  data class Snapshot(
    val rows: List<Row> = emptyList(),
    val lastConfirmed: Map<Flavor, Long> = emptyMap(),
    val lastAttempt: Attempt? = null,
  ) {
    val confirmed: Int get() = rows.filter { it.outcome == Outcome.CONFIRMED }.sumOf { it.count }
    val failed: Int get() = rows.filter { it.outcome != Outcome.CONFIRMED }.sumOf { it.count }
  }

  const val MAX_ROWS = 100
  const val NO_STRATEGY = "none"
  private val strategyShape = Regex("^[a-z0-9_.]{1,48}$")

  /** Strategy ids come from [WhatsAppSelectors]; anything else is stored as [NO_STRATEGY]. */
  fun cleanStrategy(strategy: String?): String = strategy?.takeIf { strategyShape.matches(it) } ?: NO_STRATEGY

  fun record(
    snapshot: Snapshot,
    flavor: Flavor,
    versionCode: Long,
    kind: Kind,
    strategy: String?,
    outcome: Outcome,
    now: Long,
  ): Snapshot {
    val clean = cleanStrategy(strategy)
    val existing = snapshot.rows.firstOrNull {
      it.flavor == flavor && it.versionCode == versionCode && it.kind == kind && it.strategy == clean && it.outcome == outcome
    }
    val updated = existing?.copy(count = existing.count + 1, lastAt = now) ?: Row(flavor, versionCode, kind, clean, outcome, 1, now)
    val rows = (listOf(updated) + snapshot.rows.filterNot { it === existing }).sortedByDescending { it.lastAt }.take(MAX_ROWS)
    val confirmed = if (outcome == Outcome.CONFIRMED) snapshot.lastConfirmed + (flavor to versionCode) else snapshot.lastConfirmed
    return Snapshot(rows, confirmed, Attempt(flavor, versionCode, outcome, now))
  }

  fun encode(snapshot: Snapshot): String = JSONObject().put("version", 1)
    .put("rows", JSONArray(snapshot.rows.map {
      JSONObject().put("flavor", it.flavor.id).put("versionCode", it.versionCode).put("kind", it.kind.id)
        .put("strategy", it.strategy).put("outcome", it.outcome.id).put("count", it.count).put("lastAt", it.lastAt)
    }))
    .put("lastConfirmed", JSONObject().apply { snapshot.lastConfirmed.forEach { (flavor, code) -> put(flavor.id, code) } })
    .apply {
      snapshot.lastAttempt?.let {
        put("lastAttempt", JSONObject().put("flavor", it.flavor.id).put("versionCode", it.versionCode)
          .put("outcome", it.outcome.id).put("at", it.at))
      }
    }.toString()

  /** Damaged or unknown content reads as empty: metrics never block sending. */
  fun decode(raw: String?): Snapshot {
    if (raw.isNullOrBlank() || raw.length > 64_000) return Snapshot()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return Snapshot()
    if (root.optInt("version") != 1) return Snapshot()
    val rows = root.optJSONArray("rows")?.let { array ->
      (0 until minOf(array.length(), MAX_ROWS)).mapNotNull { i ->
        runCatching {
          val o = array.getJSONObject(i)
          Row(Flavor.byId(o.getString("flavor"))!!, o.getLong("versionCode"), Kind.byId(o.getString("kind"))!!,
            cleanStrategy(o.getString("strategy")), Outcome.byId(o.getString("outcome"))!!,
            o.getInt("count").coerceAtLeast(0), o.getLong("lastAt"))
        }.getOrNull()
      }
    }.orEmpty()
    val confirmed = root.optJSONObject("lastConfirmed")?.let { o ->
      Flavor.entries.mapNotNull { flavor -> if (o.has(flavor.id)) flavor to o.optLong(flavor.id) else null }.toMap()
    }.orEmpty()
    val attempt = root.optJSONObject("lastAttempt")?.let { o ->
      runCatching {
        Attempt(Flavor.byId(o.getString("flavor"))!!, o.getLong("versionCode"), Outcome.byId(o.getString("outcome"))!!, o.getLong("at"))
      }.getOrNull()
    }
    return Snapshot(rows, confirmed, attempt)
  }
}
