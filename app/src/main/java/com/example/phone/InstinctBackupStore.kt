package com.example.phone

import android.content.Context
import android.util.AtomicFile
import com.example.backup.InstinctBackupAccess
import com.example.backup.InstinctBackupData
import com.example.backup.InstinctBackupRule
import com.example.backup.InstinctSection
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Instinct's settings for the backup (1.20.0), read and written as files, directly: restoring never calls the
 * setters that arm jobs, register the doorbell, tell the script or publish the catalog. The switches «Recibir
 * órdenes», «buzón» and «timbre» are never turned on here: what was on in the copy becomes a request that only the
 * person accepts ([InstinctReactivation]). Device-bound data is never read: the endpoint, the encrypted doorbell, the
 * doorbell's state, the UnifiedPush library's database and the Web Push keys in the Android Keystore.
 */
class InstinctBackupStore(context: Context) : InstinctBackupAccess {
  private val app = context.applicationContext
  private fun file(name: String) = File(app.noBackupFilesDir, name)
  private fun text(name: String): String? = runCatching { file(name).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
  private fun put(name: String, value: String?) {
    if (value == null) file(name).delete() else writeAtomically(file(name), value, "insto-restore-")
  }

  override fun read(): InstinctBackupData {
    val autonomy = if (file(InstinctAutonomySettings.FILE).exists() || file(InstinctAutonomySettings.LEGACY_FILE).exists())
      InstinctAutonomyPolicy.encode(InstinctAutonomySettings(app).policy()) else null
    val grants = InstinctRoutineGrants(app).read().takeIf { it.isNotEmpty() }?.let(InstinctRoutines::encodeGrants)
    val recurrences = runCatching { InstinctScheduler.pending(app) }.getOrDefault(emptyList())
      .filter { it.recurring }.distinctBy { it.series }.map { it.order }
    return InstinctBackupData(
      proposalsEnabled = InstinctProposalSettings(app).isEnabled(),
      replyMode = text(REPLY)?.let { v -> InstinctReplyMode.entries.firstOrNull { it.name == v } },
      channel = text(CHANNEL)?.let { v -> InstinctAckChannel.entries.firstOrNull { it.name == v } },
      autonomy = autonomy,
      routineGrants = grants,
      mailboxEnabled = InstinctMailboxSettings(app).enabled(),
      mailboxAccept = text(ACCEPT)?.let { v -> InstinctMailboxAccept.entries.firstOrNull { it.name == v } },
      whatsappCap = text(CAP)?.let { v -> InstinctAutonomy.entries.firstOrNull { it.name == v } },
      mailboxInterval = text(INTERVAL)?.toIntOrNull()?.takeIf { it in InstinctMailboxSettings.INTERVALS },
      mailboxKey = InstinctMailboxSettings(app).key(),
      logUrl = InstinctLogSettings(app).url(),
      logToken = InstinctLogSettings(app).token(),
      doorbellEnabled = InstinctDoorbell.settings(app).enabled(),
      doorbellWithOrder = InstinctDoorbell.settings(app).withOrder(),
      doorbellDistributor = InstinctDoorbell.settings(app).chosen(),
      recurrences = recurrences,
      contextRules = InstinctContextScheduler.rules(app).filter { it.expiresAt > InstinctExecutionJournal.clock() }
        .map { InstinctBackupRule(it.spec.line, it.expiresAt, it.enabled) },
      showQuestionsOnLockScreen = text(InstinctAutonomySettings.QUESTION_VISIBILITY)?.toBooleanStrictOrNull(),
    )
  }

  override fun write(data: InstinctBackupData) {
    val visibility = InstinctAutonomySettings(app)
    val before = visibility.showQuestionsOnLockScreen()
    put(InstinctAutonomySettings.QUESTION_VISIBILITY, data.showQuestionsOnLockScreen?.toString())
    if (before != visibility.showQuestionsOnLockScreen()) InstinctQuestionNotifications.privacyChanged(app)
    put(REPLY, data.replyMode?.takeIf { it != InstinctReplyMode.NEVER }?.name)
    put(CHANNEL, data.channel?.takeIf { it != InstinctAckChannel.WHATSAPP }?.name)
    if (data.autonomy != null) { put(InstinctAutonomySettings.FILE, data.autonomy); file(InstinctAutonomySettings.LEGACY_FILE).delete() }
    if (data.routineGrants != null) AtomicFile(file(GRANTS)).let { atomic ->
      val stream = atomic.startWrite()
      try { stream.write(data.routineGrants.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) } catch (t: Exception) { atomic.failWrite(stream); throw t }
    }
    put(ACCEPT, data.mailboxAccept?.takeIf { it != InstinctMailboxAccept.WHATSAPP_Y_BUZON }?.name)
    put(CAP, data.whatsappCap?.takeIf { it == InstinctAutonomy.CONFIRMAR }?.name)
    put(INTERVAL, data.mailboxInterval?.takeIf { it != 5 }?.toString())
    data.mailboxKey?.let { put(InstinctMailboxSettings.KEY_FILE, it) }
    data.logUrl?.let { put(URL, it) }
    data.logToken?.let { put(TOKEN, it) }
    put(WITH_ORDER, if (data.doorbellWithOrder) "true" else null)
    data.doorbellDistributor?.let { put(DISTRIBUTOR, it) }
  }

  override fun requestReactivation(data: InstinctBackupData) {
    val o = JSONObject().put("proposals", data.proposalsEnabled).put("mailbox", data.mailboxEnabled).put("doorbell", data.doorbellEnabled)
      .put("recurrences", JSONArray(data.recurrences)).put("contextRules", InstinctSection.encodeRules(data.contextRules))
      .put("at", InstinctScheduler.wallClock())
    writeAtomically(file(InstinctReactivation.FILE), o.toString(), "insto-reactivation-")
  }

  override fun reactivationPending(): Boolean = file(InstinctReactivation.FILE).exists()
  override fun clearReactivation() { file(InstinctReactivation.FILE).delete() }

  companion object {
    const val REPLY = "instinct-proposals-reply-mode"
    const val CHANNEL = "instinct-proposals-ack-channel"
    const val GRANTS = "instinct-routines.json"
    const val ACCEPT = "instinct-mailbox-accept"
    const val CAP = "instinct-whatsapp-cap"
    const val INTERVAL = "instinct-mailbox-interval"
    const val URL = "instinct-log-url"
    const val TOKEN = "instinct-log-token"
    const val WITH_ORDER = "instinct-doorbell-with-order"
    const val DISTRIBUTOR = "instinct-doorbell-distributor"
  }
}

/**
 * «Reactivar Instinct en este teléfono» (1.20.0): one authorization, after a restore, for what the copy had on. It
 * checks permissions, turns the switches on, registers UnifiedPush again (this phone's own Web Push keys, a new
 * endpoint, a new encrypted doorbell, and the script hears the endpoint), tests the log and the mailbox, schedules the
 * recurring orders again (validated again, never a consumed one-time order), rearms the jobs and publishes the live
 * catalog. Permissions Android asks to grant by hand are only listed. Blocking (network): off the main thread.
 */
object InstinctReactivation {
  const val FILE = "instinct-reactivation.json"

  data class Request(val proposals: Boolean, val mailbox: Boolean, val doorbell: Boolean, val recurrences: List<String>,
    val at: Long, val contextRules: List<InstinctBackupRule> = emptyList())
  data class Step(val name: String, val ok: Boolean, val detail: String)

  fun pending(context: Context): Request? = runCatching {
    val o = JSONObject(File(context.applicationContext.noBackupFilesDir, FILE).readText())
    val lines = o.optJSONArray("recurrences") ?: JSONArray()
    Request(o.optBoolean("proposals"), o.optBoolean("mailbox"), o.optBoolean("doorbell"), (0 until lines.length()).map { lines.getString(it) },
      o.optLong("at"), InstinctSection.decodeRules(o.optJSONArray("contextRules") ?: JSONArray()))
  }.getOrNull()

  fun dismiss(context: Context) { File(context.applicationContext.noBackupFilesDir, FILE).delete() }

  /** Tests replace the network checks. */
  internal var logTest: (Context) -> String = { InstinctLog.test(it) }
  internal var mailboxTest: (Context) -> String = { InstinctMailbox.test(it) }

  fun run(context: Context): List<Step> {
    val app = context.applicationContext
    val request = pending(app) ?: return listOf(Step("Reactivación", true, "No había nada para reactivar."))
    val steps = ArrayList<Step>()
    val missing = InstinctReadiness.missing(app).filter { it.capability != InstinctReadiness.Capability.ACCESSIBILITY }
    steps += Step("Permisos de Android", missing.isEmpty(), if (missing.isEmpty()) "todo concedido."
      else "faltan: ${missing.joinToString { it.capability.label }}. Concedelos en «Funcionar con el teléfono bloqueado».")
    var leftRecurrences = request.recurrences
    var leftRules = request.contextRules.filter { it.expiresAt > InstinctExecutionJournal.clock() }
    var proposals = false
    if (request.proposals || request.mailbox || request.doorbell || request.recurrences.isNotEmpty()) {
      val settings = InstinctProposalSettings(app)
      proposals = when {
        settings.isEnabled() -> true
        !InstinctProposalSettings.supported -> false.also { steps += Step("Recibir órdenes", false, "requiere Android 9 o posterior.") }
        InstinctProposalCenter.fingerprint(app) == null && com.example.data.AssistantPreferencesCache.of(app).current?.pinnedContact == null ->
          false.also { steps += Step("Recibir órdenes", false, "falta el contacto de Instinct: configuralo y volvé a reactivar.") }
        else -> { settings.setEnabled(true); true.also { steps += Step("Recibir órdenes", true, "encendido.") } }
      }
    }
    if (InstinctLogSettings(app).configured()) {
      InstinctLog.rearm(app)
      val result = runCatching { logTest(app) }.getOrDefault("no se pudo probar.")
      steps += Step("Bitácora", !result.startsWith("No"), result)
    } else if (request.mailbox) steps += Step("Bitácora", false, "falta la URL o el token: cargalos en «Canal de estados».")
    if (request.mailbox && proposals) {
      val box = InstinctMailboxSettings(app)
      if (!box.hasKey()) steps += Step("Buzón", false, "falta la clave del buzón.")
      else {
        box.setEnabled(true)
        val result = runCatching { mailboxTest(app) }.getOrDefault("no se pudo probar.")
        steps += Step("Buzón", result.startsWith("El buzón respondió bien"), result)
      }
    }
    if (request.doorbell && proposals) {
      val blocker = InstinctDoorbell.blocker(app)
      steps += if (blocker != null) Step("Timbre", false, blocker)
      else {
        // A new registration on this phone: its own keys and endpoint; the script hears the new endpoint.
        InstinctDoorbell.setEnabled(app, true)
        Step("Timbre", true, "registrado de nuevo con ${InstinctDoorbell.distributor(app)}: claves y dirección nuevas de este teléfono; el script recibe la dirección nueva.")
      }
    }
    if (request.recurrences.isNotEmpty() && proposals) {
      val fingerprint = InstinctProposalCenter.fingerprint(app)
      val failed = ArrayList<String>()
      var scheduled = 0
      for (line in request.recurrences) {
        val now = java.util.Calendar.getInstance().apply { timeInMillis = InstinctScheduler.wallClock() }
        val check = InstinctProposalValidator.check(line, PhoneCommandParser(), now, InstinctRoutineGrants(app).access())
        val outcome = if (check is ProposalCheck.Valid && check.deferred != null && fingerprint != null &&
          !InstinctProposalCenter.refusedByPolicy(app, check.command))
          InstinctScheduler.schedule(app, check, fingerprint, "", ProposalSource.BUZON) else null
        if (outcome is InstinctScheduler.Outcome.Scheduled) scheduled++ else failed += line
      }
      leftRecurrences = failed
      steps += Step("Órdenes recurrentes", failed.isEmpty(), "programadas $scheduled de ${request.recurrences.size}" +
        if (failed.isEmpty()) "." else "; no se pudieron programar: ${failed.joinToString(" | ") { it.take(60) }}.")
    }
    if (leftRules.isNotEmpty()) {
      val total = leftRules.size
      leftRules = leftRules.filter { rule ->
        val spec = ContextRuleSpec.parse(rule.line) ?: return@filter true
        InstinctContextScheduler.create(app, spec, rule.expiresAt, rule.enabled) != null
      }
      steps += Step("Reglas contextuales", leftRules.isEmpty(), "restauradas ${total - leftRules.size} de $total.")
    }
    runCatching { InstinctScheduler.rearm(app) }
    runCatching { InstinctMailbox.rearm(app) }
    runCatching { InstinctStatePublisher.rearm(app) }
    runCatching { InstinctCatalogPublisher.changed(app) }
    steps += Step("Programación y catálogo", true, "jobs rearmados; el catálogo vivo se publica de nuevo.")
    // Only what could not be done stays pending; recurrences already scheduled never are scheduled twice.
    val left = request.copy(proposals = request.proposals && !proposals, mailbox = request.mailbox && !InstinctMailboxSettings(app).enabled(),
      doorbell = request.doorbell && !InstinctDoorbell.settings(app).enabled(), recurrences = leftRecurrences, contextRules = leftRules)
    if (!left.proposals && !left.mailbox && !left.doorbell && left.recurrences.isEmpty() && left.contextRules.isEmpty()) dismiss(app)
    else writeAtomically(File(app.noBackupFilesDir, FILE), JSONObject().put("proposals", left.proposals).put("mailbox", left.mailbox)
      .put("doorbell", left.doorbell).put("recurrences", JSONArray(left.recurrences))
      .put("contextRules", InstinctSection.encodeRules(left.contextRules)).put("at", left.at).toString(), "insto-reactivation-")
    return steps
  }
}
