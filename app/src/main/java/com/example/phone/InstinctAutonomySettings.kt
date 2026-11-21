package com.example.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.Calendar

/**
 * The autonomy policy on Android: `instinct-autonomy.json` in [Context.getNoBackupFilesDir], written atomically.
 * Never in backups nor restored. The first read migrates the 1.15.0 switch «Pedirme confirmación antes de ejecutar»
 * (`instinct-proposals-ask-first`): on, every category starts at Confirmar; then that file is deleted.
 */
class InstinctAutonomySettings(context: Context) {
  private val app = context.applicationContext
  private val file = File(app.noBackupFilesDir, FILE)
  private val legacy = File(app.noBackupFilesDir, LEGACY_FILE)

  fun policy(): InstinctAutonomyPolicy = synchronized(LOCK) {
    if (file.exists()) {
      val stored = runCatching { InstinctAutonomyPolicy.decode(file.readText()) }.getOrNull()
      if (legacy.exists()) legacy.delete()
      // An unreadable file fails safe: everything waits for Confirmar until the person chooses again.
      return stored ?: InstinctAutonomyPolicy.migrated(askFirst = true)
    }
    val migrated = InstinctAutonomyPolicy.migrated(runCatching { legacy.readText() == "true" }.getOrDefault(false))
    runCatching { write(migrated) }
    if (legacy.exists()) legacy.delete()
    migrated
  }

  private fun write(policy: InstinctAutonomyPolicy) = writeAtomically(file, InstinctAutonomyPolicy.encode(policy), "insto-autonomy-")

  /** Chosen by the person. Back to Solo from another level starts the category's counters again. */
  fun set(category: InstinctCategory, level: InstinctAutonomy) {
    val before = synchronized(LOCK) { policy().also { write(it.with(category, level)) } }
    if (level == InstinctAutonomy.SOLO && before.level(category) != InstinctAutonomy.SOLO) InstinctTrust.reset(app, category)
    // «Nunca» cancels whole every recurrence it now refuses (1.17.0).
    if (level == InstinctAutonomy.NUNCA) runCatching { InstinctScheduler.cancelRefused(app) }
  }

  fun setAll(level: InstinctAutonomy) = InstinctCategory.entries.forEach { set(it, level) }

  fun setQuiet(value: QuietHours?) = synchronized(LOCK) { write(policy().copy(quiet = value)) }
  fun setSilenceInQuiet(value: Boolean) = synchronized(LOCK) { write(policy().copy(silenceInQuiet = value)) }

  /** The ledger lowered [category]: Confirmar, marked «Ajustada automáticamente». */
  internal fun lower(category: InstinctCategory, at: Long) = synchronized(LOCK) {
    write(policy().with(category, InstinctAutonomy.CONFIRMAR, adjustedAt = at))
  }

  companion object {
    const val FILE = "instinct-autonomy.json"
    const val LEGACY_FILE = "instinct-proposals-ask-first"
    private val LOCK = Any()

    /** Minutes since local midnight at [millis], for the quiet hours. */
    fun minuteOfDay(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }
      .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
  }
}

/**
 * The trust ledger on Android: `instinct-trust.json` in noBackupFilesDir, counters only. Lowers a category the person
 * keeps undoing (and says so, with «Volver a automático») and suggests raising one the person keeps confirming
 * («Sí» / «No»). The actions travel in PendingIntents to [InstinctProposalReceiver], not exported, with a random
 * single-use token.
 */
object InstinctTrust {
  const val FILE = "instinct-trust.json"
  const val ACTION_RESTORE = "com.example.phone.ACTION_INSTINCT_TRUST_RESTORE"
  const val ACTION_RAISE_YES = "com.example.phone.ACTION_INSTINCT_TRUST_YES"
  const val ACTION_RAISE_NO = "com.example.phone.ACTION_INSTINCT_TRUST_NO"
  const val EXTRA_TOKEN = "insto.instinct.trust.token"
  private const val NOTIFICATION_BASE = 70_000

  internal var clock: () -> Long = { InstinctScheduler.wallClock() }
  private val lock = Any()

  private fun <T> update(context: Context, block: (InstinctTrustLedger) -> T): T = synchronized(lock) {
    val file = File(context.applicationContext.noBackupFilesDir, FILE)
    val ledger = InstinctTrustLedger(clock)
    if (file.exists()) runCatching { ledger.load(file.readText()) }
    val result = block(ledger)
    runCatching { writeAtomically(file, ledger.encode(), "insto-trust-") }
    result
  }

  /** The category an order counts under: a routine as «rutinas», anything else its own. */
  fun category(command: PhoneCommand): InstinctCategory? =
    if (command is PhoneCommand.Platform && command.action == PlatformAction.ROUTINE_RUN) InstinctCategory.RUTINAS
    else InstinctCategory.of(command)

  fun record(context: Context, command: PhoneCommand, event: TrustEvent) { category(command)?.let { record(context, it, event) } }

  fun record(context: Context, category: InstinctCategory, event: TrustEvent) {
    val app = context.applicationContext
    runCatching {
      val settings = InstinctAutonomySettings(app)
      val level = settings.policy().level(category)
      val now = clock()
      data class After(val lowering: InstinctTrustLedger.Lowering?, val token: String?, val suggest: Boolean)
      val after = update(app) { ledger ->
        ledger.record(category, event)
        val lowering = if (event == TrustEvent.EJECUTADA || event == TrustEvent.DESHECHA) ledger.lowering(category, level) else null
        when {
          lowering != null -> After(lowering, ledger.issue(InstinctTrustLedger.Action.RESTORE, category), false)
          event == TrustEvent.CONFIRMADA && ledger.suggestsRaise(category, level) -> {
            ledger.markSuggested(category)
            After(null, ledger.issue(InstinctTrustLedger.Action.SUGGEST, category), true)
          }
          else -> After(null, null, false)
        }
      }
      if (after.lowering != null && after.token != null) {
        settings.lower(category, now)
        notify(app, category, "Instinct va a pedirte confirmación para ${category.label}: deshiciste ${after.lowering.undone} de ${after.lowering.executed}",
          "Podés volver a dejarlo en automático acá o en Ajustes → Propuestas de Instinct → Autonomía.",
          listOf("Volver a automático" to Intent(ACTION_RESTORE)), after.token)
      } else if (after.suggest && after.token != null) {
        notify(app, category, "¿Dejar que Instinct haga ${category.label} solo?",
          "Confirmaste todas las veces en las últimas dos semanas. Si decís que no, no te lo pregunto por 30 días.",
          listOf("Sí" to Intent(ACTION_RAISE_YES), "No" to Intent(ACTION_RAISE_NO)), after.token)
      }
    }
  }

  /** Back to Solo: the category starts counting again. */
  fun reset(context: Context, category: InstinctCategory) { runCatching { update(context) { it.reset(category) } } }

  /** «Volver a automático», «Sí» or «No» from a notification. False when the token is unknown, used or expired. */
  fun answer(context: Context, action: String?, token: String?): Boolean {
    val app = context.applicationContext
    val kind = if (action == ACTION_RESTORE) InstinctTrustLedger.Action.RESTORE else InstinctTrustLedger.Action.SUGGEST
    val pending = runCatching { update(app) { it.redeem(token, kind) } }.getOrNull() ?: return false
    when (action) {
      ACTION_RESTORE, ACTION_RAISE_YES -> InstinctAutonomySettings(app).set(pending.category, InstinctAutonomy.SOLO)
      ACTION_RAISE_NO -> runCatching { update(app) { it.snooze(pending.category) } }
      else -> return false
    }
    runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_BASE + pending.category.ordinal) }
    return true
  }

  private fun notify(context: Context, category: InstinctCategory, title: String, text: String, actions: List<Pair<String, Intent>>, token: String) {
    if (!InstinctProposalCenter.canNotify(context, InstinctProposalCenter.ACTIONS_CHANNEL_ID)) return
    val builder = InstinctProposalCenter.builder(context, InstinctProposalCenter.ACTIONS_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
    actions.forEachIndexed { index, (label, intent) ->
      val pending = PendingIntent.getBroadcast(context, NOTIFICATION_BASE + category.ordinal * 4 + index,
        intent.setClass(context, InstinctProposalReceiver::class.java).putExtra(EXTRA_TOKEN, token),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      builder.addAction(Notification.Action.Builder(null, label, pending).build())
    }
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + category.ordinal, builder.build()) }
  }
}
