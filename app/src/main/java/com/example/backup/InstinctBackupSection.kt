package com.example.backup

import com.example.phone.InstinctAckChannel
import com.example.phone.InstinctAutonomy
import com.example.phone.InstinctAutonomyPolicy
import com.example.phone.ContextRuleSpec
import com.example.phone.ContextualCheck
import com.example.phone.InstinctDeferral
import com.example.phone.InstinctProposalValidator
import com.example.phone.PhoneCommandParser
import com.example.phone.InstinctLogConfig
import com.example.phone.InstinctMailboxAccept
import com.example.phone.InstinctMailboxKey
import com.example.phone.InstinctProposalProtocol
import com.example.phone.InstinctReplyMode
import com.example.phone.InstinctRoutines
import org.json.JSONArray
import org.json.JSONObject

data class InstinctBackupRule(val line: String, val expiresAt: Long, val enabled: Boolean) {
  internal val id: String? get() = ContextRuleSpec.parse(line)?.id
}

/**
 * Everything of Instinct that moves to another phone (1.20.0). Configuration: the switches as they were (restored
 * only as a request to reactivate, never on), replies, channel, autonomy, routine grants, mailbox acceptance, cap and
 * interval, the doorbell's preferences and the recurring orders' definitions. Secrets, only in a full copy: the log's
 * token and the mailbox's key. Never device-bound data: the UnifiedPush registration, the Web Push keys in the Android
 * Keystore, the endpoint and the encrypted doorbell made for old keys are generated again on the new phone.
 */
data class InstinctBackupData(
  val proposalsEnabled: Boolean = false,
  val replyMode: InstinctReplyMode? = null,
  val channel: InstinctAckChannel? = null,
  /** [InstinctAutonomyPolicy.encode]'d. */
  val autonomy: String? = null,
  /** [InstinctRoutines.encodeGrants]'d. */
  val routineGrants: String? = null,
  val mailboxEnabled: Boolean = false,
  val mailboxAccept: InstinctMailboxAccept? = null,
  val whatsappCap: InstinctAutonomy? = null,
  val mailboxInterval: Int? = null,
  val mailboxKey: String? = null,
  val logUrl: String? = null,
  val logToken: String? = null,
  val doorbellEnabled: Boolean = false,
  val doorbellWithOrder: Boolean = false,
  val doorbellDistributor: String? = null,
  /** Recurring orders as Instinct wrote them («todos los días a las 7 …»), validated again when reactivated. */
  val recurrences: List<String> = emptyList(),
  val showQuestionsOnLockScreen: Boolean? = null,
  val contextRules: List<InstinctBackupRule> = emptyList(),
) {
  val secrets: Boolean get() = mailboxKey != null || logToken != null
  val anything: Boolean get() = this != InstinctBackupData()
  override fun toString() = "InstinctBackupData(proposals=$proposalsEnabled, mailbox=$mailboxEnabled, doorbell=$doorbellEnabled, secrets=$secrets)"
}

/** The phone side: reads every store, writes them back without side effects, and asks for a reactivation. */
interface InstinctBackupAccess {
  fun read(): InstinctBackupData
  /** Writes [data] as stored settings. Switches stay as they are on this phone; nothing is armed, sent or run. */
  fun write(data: InstinctBackupData)
  /** What was on in the copy and must be turned on again only by the person, once, with «Reactivar». */
  fun requestReactivation(data: InstinctBackupData)
  fun reactivationPending(): Boolean
  /** Undoes [requestReactivation] (a rollback). */
  fun clearReactivation()
}

class InstinctSection(private val access: InstinctBackupAccess) : BackupSection {
  override val category = BackupCategory.INSTINCT

  override suspend fun export(): JSONObject? = export(secrets = true)

  override suspend fun export(secrets: Boolean): JSONObject? {
    val data = access.read().let { if (secrets) it else it.copy(mailboxKey = null, logToken = null) }
    if (!data.anything) return null
    return encode(data)
  }

  override suspend fun describeCurrent(): String? = access.read().takeIf { it.anything }?.let(::describe)

  private fun describe(d: InstinctBackupData): String = listOfNotNull(
    "recibir órdenes".takeIf { d.proposalsEnabled },
    "bitácora".takeIf { d.logUrl != null }, "buzón".takeIf { d.mailboxEnabled || d.mailboxKey != null },
    "timbre".takeIf { d.doorbellEnabled }, "autonomía".takeIf { d.autonomy != null },
    "${d.recurrences.size} recurrentes".takeIf { d.recurrences.isNotEmpty() },
    "${d.contextRules.size} reglas contextuales".takeIf { d.contextRules.isNotEmpty() },
  ).ifEmpty { listOf("ajustes") }.joinToString(", ").replaceFirstChar { it.uppercaseChar() } +
    if (d.secrets) " · con token y clave" else " · sin secretos"

  override fun validate(json: JSONObject): CategorySummary = CategorySummary(describe(decode(json)))

  override suspend fun prepare(json: JSONObject, mode: RestoreMode, options: RestoreOptions): PreparedChange {
    val incoming = decode(json)
    val current = access.read()
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val attention = mutableListOf<String>()
    fun <T> pick(name: String, now: T?, backup: T?, secret: Boolean = false): T? = when {
      backup == null || backup == now -> now
      now == null -> { changes += "Se restaura $name."; backup }
      mode == RestoreMode.REPLACE || (secret && options.replaceInstinctSecrets) -> {
        changes += "Se reemplaza $name."; warnings += "Se reemplaza $name actual por la del respaldo."; backup
      }
      else -> { attention += "Se conservó $name actual: la del respaldo es distinta."; now }
    }
    val merged = InstinctBackupData(
      proposalsEnabled = current.proposalsEnabled,
      replyMode = pick("la respuesta a Instinct", current.replyMode, incoming.replyMode),
      channel = pick("el canal de estados", current.channel, incoming.channel),
      autonomy = pick("la autonomía por categoría", current.autonomy, incoming.autonomy),
      routineGrants = pick("el permiso de rutinas para Instinct", current.routineGrants, incoming.routineGrants),
      mailboxEnabled = current.mailboxEnabled,
      mailboxAccept = pick("«Aceptar órdenes de»", current.mailboxAccept, incoming.mailboxAccept),
      whatsappCap = pick("el tope de WhatsApp sin firma", current.whatsappCap, incoming.whatsappCap),
      mailboxInterval = pick("el intervalo del buzón", current.mailboxInterval, incoming.mailboxInterval),
      mailboxKey = pick("la clave del buzón", current.mailboxKey, incoming.mailboxKey, secret = true),
      logUrl = pick("la URL de la bitácora", current.logUrl, incoming.logUrl),
      logToken = pick("el token de la bitácora", current.logToken, incoming.logToken, secret = true),
      doorbellEnabled = current.doorbellEnabled,
      doorbellWithOrder = if (mode == RestoreMode.REPLACE) incoming.doorbellWithOrder else current.doorbellWithOrder || incoming.doorbellWithOrder,
      doorbellDistributor = pick("el distribuidor del timbre", current.doorbellDistributor, incoming.doorbellDistributor),
      recurrences = current.recurrences,
      contextRules = current.contextRules,
      showQuestionsOnLockScreen = pick("la visibilidad de preguntas", current.showQuestionsOnLockScreen, incoming.showQuestionsOnLockScreen),
    )
    // Recurrences this phone already has are never scheduled twice.
    val newRecurrences = incoming.recurrences.filter { it !in current.recurrences }
    val existingRules = current.contextRules.mapTo(HashSet()) { it.id }
    val newRules = incoming.contextRules.filter { it.id !in existingRules && it.expiresAt > System.currentTimeMillis() }
    val reactivate = incoming.copy(proposalsEnabled = incoming.proposalsEnabled && !current.proposalsEnabled,
      mailboxEnabled = incoming.mailboxEnabled && !current.mailboxEnabled, doorbellEnabled = incoming.doorbellEnabled && !current.doorbellEnabled,
      recurrences = newRecurrences, contextRules = newRules)
    val asks = listOfNotNull("recibir órdenes".takeIf { reactivate.proposalsEnabled }, "buzón".takeIf { reactivate.mailboxEnabled },
      "timbre".takeIf { reactivate.doorbellEnabled }, "${newRecurrences.size} órdenes recurrentes".takeIf { newRecurrences.isNotEmpty() },
      "${newRules.size} reglas contextuales".takeIf { newRules.isNotEmpty() })
    if (asks.isNotEmpty()) attention += "Control remoto apagado en este teléfono hasta que lo reactives: ${asks.joinToString(", ")}. " +
      "Ajustes → Propuestas de Instinct → «Reactivar Instinct en este teléfono»."
    if (reactivate.doorbellEnabled) attention += "El timbre se registra de nuevo al reactivarlo: este teléfono tiene sus propias claves y su propia dirección."
    if (!incoming.secrets && (incoming.logUrl != null || incoming.mailboxEnabled)) attention += "La copia no trae el token de la bitácora ni la clave del buzón: cargalos a mano."
    return object : PreparedChange {
      override val changes = changes.ifEmpty { listOf("Sin cambios.") }
      override val warnings = warnings
      override val attention = attention
      override suspend fun snapshot(): Restorable {
        val before = access.read(); val pending = access.reactivationPending()
        return Restorable { access.write(before); if (!pending) access.clearReactivation() }
      }
      override suspend fun commit() {
        access.write(merged)
        if (asks.isNotEmpty()) access.requestReactivation(reactivate)
      }
    }
  }

  companion object {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val INTERVALS = setOf(2, 5, 15)
    const val MAX_RECURRENCES = InstinctDeferral.MAX_SCHEDULED

    fun encode(d: InstinctBackupData): JSONObject = JSONObject()
      .put("proposals", JSONObject().put("wasEnabled", d.proposalsEnabled).apply {
        d.replyMode?.let { put("replyMode", it.name) }; d.channel?.let { put("channel", it.name) }
        d.showQuestionsOnLockScreen?.let { put("showQuestionsOnLockScreen", it) }
      })
      .apply { d.autonomy?.let { put("autonomy", JSONObject(it)) }; d.routineGrants?.let { put("routineGrants", JSONObject(it)) } }
      .put("mailbox", JSONObject().put("wasEnabled", d.mailboxEnabled).apply {
        d.mailboxAccept?.let { put("accept", it.name) }; d.whatsappCap?.let { put("whatsappCap", it.name) }
        d.mailboxInterval?.let { put("interval", it) }; d.mailboxKey?.let { put("key", it) }
      })
      .put("log", JSONObject().apply { d.logUrl?.let { put("url", it) }; d.logToken?.let { put("token", it) } })
      .put("doorbell", JSONObject().put("wasEnabled", d.doorbellEnabled).put("withOrder", d.doorbellWithOrder)
        .apply { d.doorbellDistributor?.let { put("distributor", it) } })
      .put("recurrences", JSONArray(d.recurrences))
      .put("contextRules", encodeRules(d.contextRules))

    internal fun encodeRules(rules: List<InstinctBackupRule>): JSONArray = JSONArray(rules.map {
      JSONObject().put("line", it.line).put("expiresAt", it.expiresAt).put("enabled", it.enabled)
    })

    internal fun decodeRules(array: JSONArray): List<InstinctBackupRule> {
      require(array.length() <= ContextRuleSpec.MAX_RULES) { "Demasiadas reglas contextuales en el respaldo." }
      val parser = PhoneCommandParser()
      val ids = HashSet<String>()
      return List(array.length()) { index ->
        val rule = array.getJSONObject(index)
        val line = rule.getString("line")
        val check = InstinctProposalValidator.check(line, parser) as? ContextualCheck
        val end = when (val value = rule.get("expiresAt")) {
          is Long -> value
          is Int -> value.toLong()
          else -> 0L
        }
        val enabled = rule.get("enabled") as? Boolean
        require(check != null && ids.add(check.spec.id) && end > 0 && enabled != null) { "Regla contextual inválida en el respaldo." }
        InstinctBackupRule(line, end, enabled)
      }
    }

    /** Every field checked with the rules Insto applies when the person types it. Throws [IllegalArgumentException]. */
    fun decode(json: JSONObject): InstinctBackupData {
      fun bad(what: String): Nothing = throw IllegalArgumentException("$what del archivo no es válido.")
      fun <E : Enum<E>> choice(o: JSONObject?, name: String, values: Array<E>, what: String): E? =
        o?.optString(name)?.takeIf { o.has(name) }?.let { v -> values.firstOrNull { it.name == v } ?: bad(what) }
      val proposals = json.optJSONObject("proposals")
      val mailbox = json.optJSONObject("mailbox")
      val log = json.optJSONObject("log")
      val doorbell = json.optJSONObject("doorbell")
      val autonomy = json.optJSONObject("autonomy")?.toString()?.also {
        runCatching { InstinctAutonomyPolicy.decode(it) }.getOrNull() ?: bad("La autonomía")
      }?.let { InstinctAutonomyPolicy.encode(InstinctAutonomyPolicy.decode(it)) }
      val grants = json.optJSONObject("routineGrants")?.toString()?.let { InstinctRoutines.encodeGrants(InstinctRoutines.decodeGrants(it)) }
      val key = mailbox?.takeIf { it.has("key") }?.optString("key")?.also { if (!InstinctMailboxKey.valid(it)) bad("La clave del buzón") }
      val url = log?.takeIf { it.has("url") }?.optString("url")?.also { if (!InstinctLogConfig.validUrl(it)) bad("La URL de la bitácora") }
      val token = log?.takeIf { it.has("token") }?.optString("token")?.also { if (!InstinctLogConfig.validToken(it)) bad("El token de la bitácora") }
      val interval = mailbox?.takeIf { it.has("interval") }?.let { o -> (o.get("interval") as? Int)?.takeIf { it in INTERVALS } ?: bad("El intervalo del buzón") }
      val distributor = doorbell?.takeIf { it.has("distributor") }?.optString("distributor")?.also {
        if (it.length > 200 || !PACKAGE.matches(it)) bad("El distribuidor del timbre")
      }
      val lines = json.optJSONArray("recurrences") ?: JSONArray()
      if (lines.length() > MAX_RECURRENCES) bad("La lista de órdenes recurrentes")
      val recurrences = (0 until lines.length()).map { i ->
        (lines.get(i) as? String)?.takeIf { it.isNotBlank() && it.length <= 300 && !it.contains('\n') &&
          !it.trimStart().startsWith(InstinctProposalProtocol.PREFIX) } ?: bad("Una orden recurrente")
      }
      return InstinctBackupData(
        proposalsEnabled = proposals?.optBoolean("wasEnabled") == true,
        replyMode = choice(proposals, "replyMode", InstinctReplyMode.entries.toTypedArray(), "La respuesta a Instinct"),
        channel = choice(proposals, "channel", InstinctAckChannel.entries.toTypedArray(), "El canal de estados"),
        autonomy = autonomy, routineGrants = grants,
        mailboxEnabled = mailbox?.optBoolean("wasEnabled") == true,
        mailboxAccept = choice(mailbox, "accept", InstinctMailboxAccept.entries.toTypedArray(), "«Aceptar órdenes de»"),
        whatsappCap = choice(mailbox, "whatsappCap", InstinctAutonomy.entries.toTypedArray(), "El tope de WhatsApp")
          ?.also { if (it == InstinctAutonomy.NUNCA) bad("El tope de WhatsApp") },
        mailboxInterval = interval, mailboxKey = key, logUrl = url, logToken = token,
        doorbellEnabled = doorbell?.optBoolean("wasEnabled") == true, doorbellWithOrder = doorbell?.optBoolean("withOrder") == true,
        doorbellDistributor = distributor, recurrences = recurrences,
        contextRules = if (json.has("contextRules")) decodeRules(json.getJSONArray("contextRules")) else emptyList(),
        showQuestionsOnLockScreen = proposals?.takeIf { it.has("showQuestionsOnLockScreen") }?.let {
          it.get("showQuestionsOnLockScreen") as? Boolean ?: bad("La visibilidad de preguntas")
        },
      )
    }
  }
}
