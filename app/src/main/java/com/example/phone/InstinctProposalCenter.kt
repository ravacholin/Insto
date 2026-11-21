package com.example.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.example.data.AssistantPreferencesCache
import com.example.whatsapp.PhoneNumberUtils
import com.example.whatsapp.WhatsAppDispatcher
import java.io.File
import org.json.JSONObject

/**
 * The opt-in switch «Recibir propuestas de Instinct» and its correlation window. Off by default, stored in
 * [Context.getNoBackupFilesDir] like Gemini's switch: never in preferences backups, configuration exports or the
 * cloud, so a restore never turns passive reading on.
 */
class InstinctProposalSettings(context: Context) {
  private val app = context.applicationContext
  private val enabled = File(app.noBackupFilesDir, "instinct-proposals-enabled")
  private val window = File(app.noBackupFilesDir, "instinct-proposals-window")

  /** Read on every posted notification: one small file, before anything of the notification is touched. */
  fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK && runCatching { enabled.readText() == "true" }.getOrDefault(false)

  fun setEnabled(value: Boolean) {
    if (value) enabled.writeText("true")
    else {
      if (enabled.exists()) enabled.delete()
      // Turning it off forgets the window and every pending proposal: nothing can run after this.
      InstinctSendCorrelation(app).clear()
      InstinctProposalCenter.discardAll(app)
    }
  }

  fun windowMinutes(): Int = InstinctProposalFilter.windowMinutes(runCatching { window.readText().trim().toInt() }.getOrNull())
  fun setWindowMinutes(minutes: Int) { window.writeText(InstinctProposalFilter.windowMinutes(minutes).toString()) }

  companion object {
    /** [Notification.EXTRA_IS_GROUP_CONVERSATION] exists from Android 9; without it a group cannot be excluded. */
    const val MIN_SDK = Build.VERSION_CODES.P
    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
  }
}

/**
 * When Insto last delivered something to Instinct with a confirmed automatic send. Only the monotonic time, the
 * boot it belongs to and a hash of the recipient's digits are kept, atomically, in [Context.getNoBackupFilesDir].
 * Recorded only while the switch is on. A reboot invalidates it.
 */
class InstinctSendCorrelation(context: Context, private val now: () -> Long = SystemClock::elapsedRealtime) {
  private val app = context.applicationContext
  private val file = AtomicFile(File(app.noBackupFilesDir, FILE_NAME))

  fun record(recipientDigits: String?) {
    if (recipientDigits.isNullOrEmpty() || !InstinctProposalSettings(app).isEnabled()) return
    val json = JSONObject().put("boot", boot()).put("at", now()).put("recipient", sha256(recipientDigits)).toString()
    synchronized(lock) {
      val stream = file.startWrite()
      try { stream.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
      catch (error: Exception) { file.failWrite(stream); throw error }
    }
  }

  /** The time of the last confirmed send to [contactPhone] in this boot, or null. */
  fun lastSendTo(contactPhone: String): Long? = runCatching {
    val json = synchronized(lock) { JSONObject(file.readFully().toString(Charsets.UTF_8)) }
    json.getLong("at").takeIf {
      json.getInt("boot") == boot() && json.getString("recipient") == sha256(PhoneNumberUtils.normalizeDigits(contactPhone))
    }
  }.getOrNull()

  fun clear() = synchronized(lock) { file.delete() }

  private fun boot(): Int = runCatching {
    android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
  }.getOrDefault(-1)

  companion object {
    const val FILE_NAME = "instinct-last-send.json"
    private val lock = Any()
  }
}

/** Reads a WhatsApp notification's extras lazily, in the order the filters ask. Nothing is copied or kept. */
internal class ExtrasProposalNotification(
  override val packageName: String,
  override val key: String,
  private val extras: () -> Bundle,
) : ProposalNotification {
  private fun latestBundle(): Bundle? = runCatching {
    @Suppress("DEPRECATION") extras().getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle
  }.getOrNull()

  override fun isGroup(): Boolean = Build.VERSION.SDK_INT < InstinctProposalSettings.MIN_SDK ||
    extras().getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

  override fun title(): String? = extras().getCharSequence(Notification.EXTRA_TITLE)?.toString()

  override fun latestMessage(): ProposalNotification.LatestMessage? {
    val message = latestBundle() ?: return null
    @Suppress("DEPRECATION")
    val person = if (Build.VERSION.SDK_INT >= 28) message.getParcelable<android.app.Person>(KEY_SENDER_PERSON)?.name else null
    return ProposalNotification.LatestMessage((person ?: message.getCharSequence(KEY_SENDER))?.toString())
  }

  override fun text(): String? = (latestBundle()?.getCharSequence(KEY_TEXT)
    ?: extras().getCharSequence(Notification.EXTRA_TEXT))?.toString()

  private companion object {
    // Keys of Notification.MessagingStyle.Message bundles (framework constants).
    const val KEY_TEXT = "text"
    const val KEY_SENDER = "sender"
    const val KEY_SENDER_PERSON = "sender_person"
  }
}

/**
 * Entry point from [NotificationAccessService]. With the switch off it returns before reading the notification. With
 * it on, the filters decide in order and every line that passes them is validated before it is offered. Logcat gets
 * stage counters only, never a title, a name or a text.
 */
object InstinctProposalIntake {
  internal val filter = InstinctProposalFilter()
  internal var log: (String) -> Unit = { if (com.example.BuildConfig.DEBUG) android.util.Log.d(TAG, it) }
  private const val TAG = "InstoProposals"

  fun onPosted(context: Context, notification: () -> ProposalNotification?) {
    if (!InstinctProposalSettings(context).isEnabled()) return
    val app = context.applicationContext
    val prefs = AssistantPreferencesCache.of(app).current ?: return
    val contact = prefs.pinnedContact ?: return
    val view = notification() ?: return
    val settings = InstinctProposalSettings(app)
    val packageName = WhatsAppDispatcher.resolveTargetPackage(app, prefs.preferredFlavor)
    val config = ProposalFilterConfig(packageName, contact.displayName,
      { InstinctSendCorrelation(app).lastSendTo(contact.phoneNumber) }, settings.windowMinutes() * 60_000L, SystemClock.elapsedRealtime())
    val outcome = filter.evaluate(view, config)
    if (outcome !is InstinctProposalFilter.Outcome.Accepted) {
      if (outcome.stage != InstinctProposalFilter.Stage.PACKAGE) log(counters())
      return
    }
    val parser = PhoneCommandParser()
    val checks = outcome.orders.map { InstinctProposalValidator.check(it, parser) }
    val fingerprint = InstinctProposalCenter.fingerprint(app) ?: return
    InstinctProposalCenter.deliver(app, checks, fingerprint)
    log(counters() + " valid=${checks.count { it is ProposalCheck.Valid }} invalid=${checks.count { it is ProposalCheck.Invalid }}")
  }

  private fun counters(): String = filter.counters().entries.sortedBy { it.key.ordinal }
    .joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" }
}

/**
 * Proposals between the notification and the user's touch. Process-wide and in memory only: a process restart loses
 * them, and their notifications can then only report that they are no longer available.
 */
object InstinctProposalCenter {
  /** The open floating assistant. Main thread only. */
  interface Listener {
    fun onProposal(proposal: ProposalBook.Proposal)
    fun onNotUnderstood()
  }

  internal var clock: () -> Long = SystemClock::elapsedRealtime
  val book = ProposalBook({ clock() })
  private val main by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var listener: Listener? = null

  const val CHANNEL_ID = "instinct-proposals"
  /** Low importance: the discreet «not understood» notice never pops up or makes a sound. */
  const val NOTICE_CHANNEL_ID = "instinct-proposal-notices"
  const val ACTION_CONFIRM = "com.example.phone.ACTION_CONFIRM_INSTINCT_PROPOSAL"
  const val ACTION_DISCARD = "com.example.phone.ACTION_DISCARD_INSTINCT_PROPOSAL"
  const val EXTRA_PROPOSAL_ID = "insto.instinct.proposal"
  const val EXTRA_PROPOSAL_TOKEN = "insto.instinct.proposal.token"
  private const val NOTIFICATION_BASE = 7_100
  private const val NOT_UNDERSTOOD_ID = 7_099

  /** The configuration proposals are validated and confirmed under; null with the switch off or no contact. */
  fun fingerprint(context: Context): String? {
    val app = context.applicationContext
    if (!InstinctProposalSettings(app).isEnabled()) return null
    val prefs = AssistantPreferencesCache.of(app).current ?: return null
    val contact = prefs.pinnedContact ?: return null
    val packageName = WhatsAppDispatcher.resolveTargetPackage(app, prefs.preferredFlavor) ?: return null
    return ProposalBook.fingerprint(contact.displayName, contact.phoneNumber, packageName)
  }

  fun attach(value: Listener) { listener = value }
  fun detach(value: Listener) { if (listener === value) listener = null }

  internal fun deliver(context: Context, checks: List<ProposalCheck>, fingerprint: String) {
    val offered = checks.filterIsInstance<ProposalCheck.Valid>().mapNotNull { book.offer(it.order, it.command, fingerprint) }
    val notUnderstood = checks.any { it is ProposalCheck.Invalid }
    val app = context.applicationContext
    val run = Runnable {
      val target = listener
      if (target != null) {
        offered.forEach(target::onProposal)
        if (notUnderstood) target.onNotUnderstood()
      } else {
        offered.forEach { notify(app, it) }
        if (notUnderstood) notifyNotUnderstood(app)
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
  }

  /**
   * Confirmation from the floating assistant or, with its [token], from the notification: the only way a proposal is
   * executed. The fingerprint is recomputed now, so a proposal confirmed after the switch, the contact or the
   * WhatsApp flavor changed is refused and can no longer run.
   */
  fun claim(context: Context, id: Long, token: String? = null, fromNotification: Boolean = false): ProposalBook.Claim {
    val claim = book.claim(id, fingerprint(context), token, requireToken = fromNotification)
    if (claim !is ProposalBook.Claim.Unknown || !fromNotification) cancelNotification(context, id)
    return claim
  }

  fun discard(context: Context, id: Long) {
    book.discard(id)
    cancelNotification(context, id)
  }

  fun discardAll(context: Context) {
    book.discardAll().forEach { cancelNotification(context, it) }
  }

  /** A proposal the floating assistant could not show before closing goes back to a notification while it is valid. */
  fun handBack(context: Context, proposal: ProposalBook.Proposal) {
    if (book.isPending(proposal.id)) notify(context.applicationContext, proposal)
  }

  private fun canNotify(context: Context, channel: String = CHANNEL_ID): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!manager.areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
      if (channel == CHANNEL_ID) NotificationChannel(CHANNEL_ID, "Insto · propuestas de Instinct", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Acciones que Instinct te propone. Ninguna se ejecuta sin que la confirmes."
      } else NotificationChannel(NOTICE_CHANNEL_ID, "Insto · avisos de propuestas", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Avisos discretos cuando Instinct propone algo que Insto no entiende."
      })
    return true
  }

  private fun builder(context: Context, channel: String = CHANNEL_ID): Notification.Builder =
    if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else @Suppress("DEPRECATION") Notification.Builder(context)

  private fun notify(context: Context, proposal: ProposalBook.Proposal) {
    if (!canNotify(context)) return
    val requestCode = proposal.id.toInt()
    val confirm = PendingIntent.getActivity(context, requestCode,
      Intent(context, com.example.assist.AssistActivity::class.java).setAction(ACTION_CONFIRM)
        .putExtra(EXTRA_PROPOSAL_ID, proposal.id).putExtra(EXTRA_PROPOSAL_TOKEN, proposal.token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val discard = PendingIntent.getBroadcast(context, requestCode,
      Intent(context, InstinctProposalReceiver::class.java).setAction(ACTION_DISCARD).putExtra(EXTRA_PROPOSAL_ID, proposal.id)
        .putExtra(EXTRA_PROPOSAL_TOKEN, proposal.token),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val remaining = (ProposalBook.TTL_MILLIS - (clock() - proposal.createdAt)).coerceAtLeast(1_000L)
    val title = "Instinct propone: ${proposal.summary}"
    val public = builder(context).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Instinct propone una acción").setContentText("Desbloqueá el teléfono para revisarla.").build()
    val notification = builder(context).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText("No se ejecuta sin tu confirmación. Vence en 10 minutos.")
      .setCategory(Notification.CATEGORY_RECOMMENDATION)
      .setPriority(@Suppress("DEPRECATION") Notification.PRIORITY_HIGH)
      .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
      .setOnlyAlertOnce(true).setAutoCancel(true)
      .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(remaining) }
      .addAction(Notification.Action.Builder(null, "Confirmar", confirm).build())
      .addAction(Notification.Action.Builder(null, "Descartar", discard).build())
      .build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + requestCode, notification) }
  }

  private fun notifyNotUnderstood(context: Context) {
    if (!canNotify(context, NOTICE_CHANNEL_ID)) return
    val notification = builder(context, NOTICE_CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(NOT_UNDERSTOOD)
      .setContentText("No se ejecutó nada.")
      .setPriority(@Suppress("DEPRECATION") Notification.PRIORITY_LOW)
      .setOnlyAlertOnce(true).setAutoCancel(true)
      .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(ProposalBook.TTL_MILLIS) }
      .build()
    runCatching { context.getSystemService(NotificationManager::class.java).notify(NOT_UNDERSTOOD_ID, notification) }
  }

  const val NOT_UNDERSTOOD = "Instinct propuso algo que no entiendo"

  fun cancelNotification(context: Context, id: Long) {
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_BASE + id.toInt()) }
  }

  fun notificationId(id: Long): Int = NOTIFICATION_BASE + id.toInt()
}

/** «Descartar» from the notification. Not exported; only Insto's own PendingIntent reaches it. */
class InstinctProposalReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != InstinctProposalCenter.ACTION_DISCARD) return
    val id = intent.getLongExtra(InstinctProposalCenter.EXTRA_PROPOSAL_ID, -1L)
    if (id > 0) InstinctProposalCenter.discard(context, id)
  }
}
