package com.example.phone

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Reads the fixed fields of [InstinctStateSnapshot]. A field that fails to read is «sin datos»; without
 * READ_CALENDAR the event is «sin permiso» (never asked for from here). Never a name, a label or an identifier.
 */
class InstinctStateReader(context: Context) {
  private val app = context.applicationContext

  fun read(now: Long): InstinctStateSnapshot {
    val zone = Calendar.getInstance()
    fun clock(millis: Long) = InstinctDeferral.clock(millis, zone)
    val audio = runCatching { app.getSystemService(AudioManager::class.java) }.getOrNull()
    val notifications = runCatching { app.getSystemService(NotificationManager::class.java) }.getOrNull()
    val dnd = runCatching {
      val filter = notifications!!.currentInterruptionFilter
      filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }.getOrNull()
    val alarm = runCatching {
      val next = app.getSystemService(AlarmManager::class.java)!!.nextAlarmClock?.triggerTime
      when {
        next == null -> "ninguna"
        next - now in 0 until 24 * 60 * 60_000L -> clock(next)
        else -> java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(next))
      }
    }.getOrDefault(InstinctState.NO_DATA)
    val devices = runCatching { audio!!.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrNull()
    val temporary = runCatching {
      TemporaryModeManager(app).read()?.let { lease ->
        val kind = when (lease.kind) { ModeKind.SILENT -> "silencio"; ModeKind.DND -> "No molestar"; ModeKind.MEDIA_VOLUME -> "multimedia" }
        "$kind hasta ${clock(lease.expiresAt)}"
      } ?: "no"
    }.getOrDefault(InstinctState.NO_DATA)
    val scheduled = runCatching {
      val pending = InstinctScheduler.pending(app)
      InstinctState.scheduled(pending.size, pending.firstOrNull()?.let { clock(it.fireAt) })
    }.getOrDefault(InstinctState.NO_DATA)
    return InstinctStateSnapshot(
      sonido = InstinctState.ringer(runCatching { audio!!.ringerMode }.getOrNull()),
      dnd = InstinctState.yesNo(dnd),
      alarma = alarm,
      evento = runCatching { event(now, ::clock) }.getOrDefault(InstinctState.NO_DATA),
      audio = if (devices == null) InstinctState.NO_DATA
        else InstinctState.audio(devices.any { it in InstinctAudioDevices.WIRED }, devices.any { it in InstinctAudioDevices.BLUETOOTH }),
      bloqueado = InstinctState.yesNo(runCatching { InstinctProposalCenter.keyguardLocked(app) }.getOrNull()),
      modoTemporal = temporary,
      programadas = scheduled,
    )
  }

  /** The event in progress and the next one in the next 12 hours, from [CalendarContract.Instances]. All-day events are left out. */
  private fun event(now: Long, clock: (Long) -> String): String {
    if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return InstinctState.NO_PERMISSION
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
      android.content.ContentUris.appendId(it, now - 24 * 60 * 60_000L)
      android.content.ContentUris.appendId(it, now + InstinctState.LOOKAHEAD_MILLIS)
    }.build()
    val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
      CalendarContract.Instances.ALL_DAY)
    val events = ArrayList<InstinctStateEvent>()
    app.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
      while (c.moveToNext() && events.size < 200) {
        if (c.getInt(3) != 0) continue
        events += InstinctStateEvent(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2))
      }
    } ?: return InstinctState.NO_DATA
    val (current, next) = InstinctState.pick(events, now)
    return InstinctState.events(current, next, clock)
  }

}

/** The output types that count as headphones: wired or Bluetooth. Types only, never names. */
internal object InstinctAudioDevices {
  // Inlined constants: on an older Android those device types simply never appear, so nothing breaks.
  @android.annotation.SuppressLint("InlinedApi")
  val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
  @android.annotation.SuppressLint("InlinedApi")
  val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER)
  fun headphones(type: Int): Boolean = type in WIRED || type in BLUETOOTH
}

/**
 * The calendar for event triggers (1.17.0): ids and times of the instances from a day before to a day and a half
 * after [now], from [CalendarContract.Instances]. Never a title. Null without READ_CALENDAR.
 */
class InstinctCalendarReader(context: Context) {
  private val app = context.applicationContext

  fun instances(now: Long, until: Long = now + 36 * 60 * 60_000L): List<CalendarInstance>? {
    if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
      android.content.ContentUris.appendId(it, now - 24 * 60 * 60_000L)
      android.content.ContentUris.appendId(it, until)
    }.build()
    val projection = arrayOf(CalendarContract.Instances._ID, CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
    val found = ArrayList<CalendarInstance>()
    app.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
      while (c.moveToNext() && found.size < 500) found += CalendarInstance(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0)
    } ?: return null
    return found
  }
}

/**
 * Publishes the snapshot in the log, always on while the log is configured and «Recibir órdenes de Instinct» is on:
 * when something changes (grouped 30 seconds), every 30 minutes as a heartbeat, after each order of Instinct that
 * ran, and for «#insto ver estado». Only through the log, never WhatsApp. Same outbox, sending and retries.
 */
object InstinctStatePublisher {
  const val LAST_FILE = "instinct-state-last.json"
  private const val JOB_ID = 7_316
  private const val AFTER_ORDER_DELAY_MILLIS = 1_500L

  enum class Outcome { PUBLISHED, UNCHANGED, NOT_CONFIGURED, DISABLED }

  internal var clock: () -> Long = { InstinctScheduler.wallClock() }
  /** The reader; tests replace it. */
  internal var reader: (Context, Long) -> InstinctStateSnapshot = { context, now -> InstinctStateReader(context).read(now) }
  /** Runs publications off the main thread (the calendar is a query); tests run them inline. */
  internal var background: (Runnable) -> Unit = { executor.execute(it) }
  internal var heartbeat: (Context, Boolean) -> Unit = ::scheduleHeartbeat
  private val executor by lazy { Executors.newSingleThreadExecutor { Thread(it, "insto-estado") } }
  private val main by lazy { Handler(Looper.getMainLooper()) }
  internal val debouncer = InstinctStateDebouncer({ SystemClock.elapsedRealtime() })
  private val lock = Any()

  private fun lastFile(context: Context) = File(context.applicationContext.noBackupFilesDir, LAST_FILE)

  /** The last published snapshot and its wall-clock time. */
  fun last(context: Context): Pair<InstinctStateSnapshot, Long>? =
    runCatching { InstinctState.decodeLast(lastFile(context).readText()) }.getOrNull()

  /** Reads, decides and, if due, writes one «estado» event to the outbox. Blocking: call it off the main thread. */
  fun publish(context: Context, reason: InstinctState.Reason): Outcome {
    val app = context.applicationContext
    if (!InstinctProposalSettings(app).isEnabled()) return Outcome.DISABLED
    if (!InstinctLogSettings(app).configured()) return Outcome.NOT_CONFIGURED
    return synchronized(lock) {
      val now = clock()
      val last = last(app)
      val snapshot = runCatching { reader(app, now) }.getOrElse { unknown() }
      if (!InstinctState.shouldPublish(reason, last?.first, last?.second, snapshot, now)) return Outcome.UNCHANGED
      val event = InstinctLogEvents.state(snapshot, now) ?: return Outcome.UNCHANGED
      InstinctLog.enqueueEvents(app, listOf(event))
      runCatching { writeAtomically(lastFile(app), InstinctState.encodeLast(snapshot, now), "insto-state-") }
      Outcome.PUBLISHED
    }
  }

  private fun unknown() = InstinctState.NO_DATA.let { InstinctStateSnapshot(it, it, it, it, it, it, it, it) }

  fun publishAsync(context: Context, reason: InstinctState.Reason) {
    val app = context.applicationContext
    background(Runnable { runCatching { publish(app, reason) } })
  }

  /** A setting changed: one publication 30 seconds after the first change of a burst. */
  fun changed(context: Context) {
    val app = context.applicationContext
    if (!InstinctProposalSettings(app).isEnabled()) return
    val delay = debouncer.signal() ?: return
    main.postDelayed({ if (debouncer.due()) publishAsync(app, InstinctState.Reason.CHANGE) }, delay)
  }

  /** After an order of Instinct ran: a fresh snapshot shortly after, so Instinct sees the effect. */
  fun afterOrder(context: Context) {
    val app = context.applicationContext
    main.postDelayed({ publishAsync(app, InstinctState.Reason.AFTER_ORDER) }, AFTER_ORDER_DELAY_MILLIS)
  }

  /** The heartbeat job, armed while the state can be published; cancelled otherwise. */
  fun rearm(context: Context) {
    val app = context.applicationContext
    heartbeat(app, InstinctProposalSettings(app).isEnabled() && InstinctLogSettings(app).configured())
  }

  /** «Estado para Instinct: último envío HH:MM», or why it is not published. */
  fun diagnosis(context: Context): String {
    if (!InstinctLogSettings(context).configured()) return "Estado para Instinct: no se publica, porque la bitácora no está configurada."
    val last = last(context) ?: return "Estado para Instinct: todavía no se publicó."
    return "Estado para Instinct: último envío ${InstinctDeferral.clock(last.second, Calendar.getInstance())}."
  }

  private fun scheduleHeartbeat(context: Context, on: Boolean) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    if (!on) { runCatching { scheduler.cancel(JOB_ID) }; return }
    if (runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull() != null) return
    val job = JobInfo.Builder(JOB_ID, ComponentName(context, InstinctStateJobService::class.java))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setPeriodic(InstinctState.HEARTBEAT_MILLIS)
      .build()
    runCatching { scheduler.schedule(job) }
  }
}

/** The 30-minute heartbeat. Not exported: only the system's JobScheduler binds it. */
class InstinctStateJobService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    Thread({
      runCatching { InstinctStatePublisher.publish(applicationContext, InstinctState.Reason.HEARTBEAT) }
      // The outbox drains through its own job, armed by the enqueue.
      jobFinished(params, false)
    }, "insto-estado-latido").start()
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean = false
}

/**
 * The system broadcasts and callbacks that change a field of the snapshot, registered at runtime from the
 * notification listener (already alive). Each one signals [InstinctStatePublisher.changed]; since 1.17.0 headphones and
 * unlocking also fire the state triggers ([InstinctScheduler.onPhoneEvent]). No receiver of its own: triggers by state
 * only work while «Insto · notificaciones» is alive.
 */
internal class InstinctStateWatcher(private val context: Context) {
  private val handler = Handler(Looper.getMainLooper())
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action != Intent.ACTION_BATTERY_CHANGED && intent.action != Intent.ACTION_SCREEN_ON)
        InstinctStatePublisher.changed(context)
      // Triggers by state (1.17.0). The sticky headset broadcast delivered on registering is the current state, not a change.
      if (isInitialStickyBroadcast) return
      if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT)
        InstinctStatePublisher.background(Runnable { InstinctQuestionNotifications.refreshExisting(context) })
      if (intent.action != Intent.ACTION_USER_PRESENT) InstinctContextScheduler.signal(context)
      when (intent.action) {
        Intent.ACTION_USER_PRESENT -> InstinctScheduler.onPhoneEvent(context, PhoneEvent.UNLOCK)
        AudioManager.ACTION_HEADSET_PLUG -> when (intent.getIntExtra("state", -1)) {
          1 -> InstinctScheduler.onPhoneEvent(context, PhoneEvent.HEADPHONES_ON)
          0 -> InstinctScheduler.onPhoneEvent(context, PhoneEvent.HEADPHONES_OFF)
        }
      }
    }
  }
  /** Headphones connected now, by device id: the callback's first call (the devices already there) fires nothing. */
  private val headphones = HashSet<Int>()
  private val devices = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
      InstinctStatePublisher.changed(context)
      InstinctContextScheduler.signal(context)
      val new = added.orEmpty().filter { InstinctAudioDevices.headphones(it.type) && headphones.add(it.id) }
      if (new.isNotEmpty()) InstinctScheduler.onPhoneEvent(context, PhoneEvent.HEADPHONES_ON)
    }
    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
      InstinctStatePublisher.changed(context)
      InstinctContextScheduler.signal(context)
      val gone = removed.orEmpty().filter { InstinctAudioDevices.headphones(it.type) && headphones.remove(it.id) }
      if (gone.isNotEmpty()) InstinctScheduler.onPhoneEvent(context, PhoneEvent.HEADPHONES_OFF)
    }
  }
  private val calendar = object : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) {
      InstinctStatePublisher.changed(context)
      InstinctContextScheduler.signal(context)
    }
  }
  private val network = object : android.net.ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
      InstinctContextScheduler.signal(context)
    }
    override fun onLost(network: android.net.Network) { InstinctContextScheduler.signal(context) }
  }
  private var stopPlayback: (() -> Unit)? = null

  @androidx.annotation.RequiresApi(26)
  private fun watchPlayback(audio: AudioManager): () -> Unit {
    val callback = object : AudioManager.AudioPlaybackCallback() {
      override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
        InstinctContextScheduler.signal(context)
      }
    }
    audio.registerAudioPlaybackCallback(callback, handler)
    return { audio.unregisterAudioPlaybackCallback(callback) }
  }
  private var started = false

  fun start() {
    if (started) return
    started = true
    val filter = IntentFilter().apply {
      addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
      addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
      addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
      addAction(AudioManager.ACTION_HEADSET_PLUG)
      addAction(Intent.ACTION_USER_PRESENT)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_BATTERY_CHANGED)
    }
    runCatching {
      if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      else context.registerReceiver(receiver, filter)
    }
    runCatching {
      val audio = context.getSystemService(AudioManager::class.java)
      headphones.clear()
      audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { InstinctAudioDevices.headphones(it.type) }?.forEach { headphones += it.id }
      audio?.registerAudioDeviceCallback(devices, handler)
      if (android.os.Build.VERSION.SDK_INT >= 26 && audio != null) stopPlayback = watchPlayback(audio)
    }
    runCatching { context.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(network) }
    watchCalendar()
    InstinctContextScheduler.signal(context)
    InstinctStatePublisher.rearm(context)
  }

  fun watchCalendar() {
    if (!started) return
    runCatching { context.contentResolver.unregisterContentObserver(calendar) }
    // The calendar provider, Instances included; only with the permission, otherwise the field is «sin permiso».
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
      runCatching { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendar) }
  }

  fun stop() {
    if (!started) return
    started = false
    runCatching { context.unregisterReceiver(receiver) }
    runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(devices) }
    runCatching { stopPlayback?.invoke() }
    stopPlayback = null
    runCatching { context.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(network) }
    runCatching { context.contentResolver.unregisterContentObserver(calendar) }
    InstinctStatePublisher.debouncer.cancel()
  }
}
