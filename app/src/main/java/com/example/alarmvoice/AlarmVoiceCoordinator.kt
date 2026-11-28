package com.example.alarmvoice

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.assist.AssistantRoleHelper
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the listening screen shows. */
data class AlarmVoiceUiState(
  val status: String = "Preparando…",
  val detail: String? = null,
  val listening: Boolean = false,
  val remainingSeconds: Int? = null,
  val finished: Boolean = false,
)

/**
 * The ringing alarm Insto may silence, kept in memory only: the notification's key and its «Detener» action. Nothing
 * of it is written to disk but the structural diagnostic and the local event log.
 */
data class ActiveAlarm(
  val id: Long,
  val key: String,
  val packageName: String,
  val kind: AlarmKind,
  val stop: PendingIntent?,
  val stopTitle: String?,
  val postedAt: Long,
)

object AlarmVoiceCoordinator {
  const val CHANNEL_ALERT = "alarm_voice_alert"
  const val CHANNEL_LISTENING = "alarm_voice_listening"
  const val NOTIFICATION_ALERT = 7_251
  const val NOTIFICATION_LISTENING = 7_252
  const val EXTRA_ALARM_ID = "com.example.alarmvoice.ALARM_ID"
  const val EXTRA_STRATEGY = "com.example.alarmvoice.STRATEGY"
  const val EXTRA_TEST = "com.example.alarmvoice.TEST"
  const val STRATEGY_ASSISTANT = "asistente predeterminado"
  const val STRATEGY_FULL_SCREEN = "pantalla completa"
  const val STRATEGY_NOTIFICATION = "notificación «Escuchar»"
  const val STRATEGY_TEST = "prueba desde Ajustes"
  private const val LAUNCH_CHECK_MS = 1_500L
  private const val REPOST_GUARD_MS = 20_000L

  private val main = Handler(Looper.getMainLooper())
  private val ids = AtomicLong(System.currentTimeMillis())
  private val handled = LinkedHashMap<String, Long>()

  @Volatile var active: ActiveAlarm? = null
    private set
  @Volatile private var shownFor: Long = -1
  /** Set while Insto itself is dismissing, so the removal that follows is not logged as manual. */
  @Volatile private var dismissingKey: String? = null

  internal val state = MutableStateFlow(AlarmVoiceUiState())
  val uiState: StateFlow<AlarmVoiceUiState> = state

  /**
   * Called by [com.example.phone.NotificationAccessService] only when the feature is on. The package is checked
   * first; for any other app nothing more of the notification is touched.
   */
  fun onPosted(context: Context, sbn: StatusBarNotification) {
    val pkg = sbn.packageName ?: return
    val settings = AlarmVoiceSettings(context)
    val known = ClockPackages.isKnown(pkg)
    if (!known && !(settings.diagnostics() && ClockPackages.looksLikeClock(pkg))) return
    val n = sbn.notification ?: return
    val actions = n.actions.orEmpty()
    val titles = actions.map { it.title?.toString() }
    val shape = ClockNotificationShape(
      packageName = pkg,
      category = n.category,
      actionTitles = titles,
      hasFullScreenIntent = n.fullScreenIntent != null,
      insistent = n.flags and Notification.FLAG_INSISTENT != 0,
      channelId = if (Build.VERSION.SDK_INT >= 26) n.channelId else null,
    )
    val kind = AlarmNotificationDetector.classify(shape)
    val stopIndex = StopActionPicker.pick(titles)
    AlarmVoiceDiagnostics.record(context, ClockDiagnostic(System.currentTimeMillis(), pkg, n.category, shape.channelId,
      titles.map { it.orEmpty() }, shape.hasFullScreenIntent, shape.insistent,
      when {
        !known -> "reloj no reconocido (agregalo a la lista)"
        kind == null -> "no es una alarma sonando"
        else -> (if (kind == AlarmKind.TIMER) "temporizador cumplido" else "alarma sonando") +
          (stopIndex?.let { " · acción «${titles[it]}»" } ?: " · sin acción de detener")
      }))
    if (kind == null) return
    val stop = stopIndex?.let { actions[it].actionIntent }
    val stopTitle = stopIndex?.let { titles[it] }
    synchronized(this) {
      val current = active
      if (current?.key == sbn.key) { active = current.copy(stop = stop ?: current.stop, stopTitle = stopTitle ?: current.stopTitle); return }
      val now = System.currentTimeMillis()
      handled.entries.removeAll { now - it.value > REPOST_GUARD_MS }
      if (handled.containsKey(sbn.key)) return
      active = ActiveAlarm(ids.incrementAndGet(), sbn.key, pkg, kind, stop, stopTitle, now)
    }
    val alarm = active ?: return
    AlarmVoiceLog.add(context, "Detectada: ${if (kind == AlarmKind.TIMER) "temporizador" else "alarma"} de $pkg; " +
      (stopTitle?.let { "acción «$it»" } ?: "sin acción de detener (se usará el intent del sistema)"))
    state.value = AlarmVoiceUiState()
    // The model is loaded while the screen comes up, so listening starts sooner.
    AlarmVoiceEngine.preload(context)
    launch(context.applicationContext, alarm)
  }

  /** The person turned the alarm off by hand (or Insto did): listening stops. */
  fun onRemoved(context: Context, key: String) {
    val alarm = active ?: return
    if (alarm.key != key) return
    val byInsto = dismissingKey == key
    synchronized(this) { active = null; handled[key] = System.currentTimeMillis() }
    cancelAlert(context)
    if (!byInsto) {
      AlarmVoiceLog.add(context, "La alarma se apagó desde el Reloj; dejo de escuchar")
      AlarmVoiceStopService.requestStop("alarma apagada a mano")
      finishUi("La alarma ya se apagó.")
    }
    if (!AlarmVoiceStopService.isRunning) { AlarmVolumeDucker.restore(context); AlarmVoiceEngine.release() }
  }

  /** Switch off, or the listener disconnected: nothing stays pending. */
  fun cancelAll(context: Context, reason: String) {
    synchronized(this) { active = null }
    cancelAlert(context)
    AlarmVoiceStopService.requestStop(reason)
    if (!AlarmVoiceStopService.isRunning) { AlarmVolumeDucker.restore(context); AlarmVoiceEngine.release() }
  }

  fun isCurrent(id: Long): Boolean = active?.id == id

  /** The listening screen is visible for [id]: the launch worked. */
  fun activityShown(context: Context, id: Long, strategy: String?) {
    if (id < 0 || shownFor == id) return
    shownFor = id
    cancelAlert(context)
    AlarmVoiceLog.add(context, "Pantalla de escucha visible (estrategia: ${strategy ?: "desconocida"})")
  }

  fun finishUi(message: String) {
    state.value = state.value.copy(status = message, listening = false, remainingSeconds = null, finished = true)
  }

  // ---- Launch -------------------------------------------------------------------------------------------------

  private fun launch(context: Context, alarm: ActiveAlarm) {
    if (AssistantRoleHelper.isDefaultAssistant(context)) {
      val started = runCatching { context.startActivity(activityIntent(context, alarm.id, STRATEGY_ASSISTANT)) }.isSuccess
      // startActivity does not report a blocked background start: the screen tells whether it came up.
      main.postDelayed({
        if (isCurrent(alarm.id) && shownFor != alarm.id) {
          AlarmVoiceLog.add(context, "El sistema no dejó abrir la pantalla directamente" + if (started) "" else " (error)")
          postAlert(context, alarm)
        }
      }, LAUNCH_CHECK_MS)
      return
    }
    postAlert(context, alarm)
  }

  fun activityIntent(context: Context, id: Long, strategy: String, test: Boolean = false): Intent =
    Intent(context, AlarmVoiceStopActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra(EXTRA_ALARM_ID, id).putExtra(EXTRA_STRATEGY, strategy).putExtra(EXTRA_TEST, test)

  fun canUseFullScreenIntent(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

  fun canPostNotifications(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun postAlert(context: Context, alarm: ActiveAlarm) {
    if (!canPostNotifications(context)) {
      AlarmVoiceLog.add(context, "Sin permiso de notificaciones: no pude ofrecer la escucha")
      return
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    ensureChannels(context)
    val fullScreen = canUseFullScreenIntent(context)
    val strategy = if (fullScreen) STRATEGY_FULL_SCREEN else STRATEGY_NOTIFICATION
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val listen = PendingIntent.getActivity(context, 1, activityIntent(context, alarm.id, STRATEGY_NOTIFICATION), flags)
    val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
      .setSmallIcon(com.example.R.drawable.ic_mic_pulse)
      .setContentTitle(if (alarm.kind == AlarmKind.TIMER) "Temporizador sonando" else "Alarma sonando")
      .setContentText("Tocá «Escuchar» y decí la palabra para apagarla.")
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setAutoCancel(true)
      .setTimeoutAfter(5 * 60_000L)
      .setContentIntent(listen)
      .addAction(0, "Escuchar", listen)
    if (fullScreen) {
      val fsi = PendingIntent.getActivity(context, 2, activityIntent(context, alarm.id, STRATEGY_FULL_SCREEN), flags)
      builder.setFullScreenIntent(fsi, true)
    }
    runCatching { manager.notify(NOTIFICATION_ALERT, builder.build()) }
      .onSuccess { AlarmVoiceLog.add(context, "Ofrecida por $strategy" + if (fullScreen) "" else " (falta el permiso de pantalla completa)") }
      .onFailure { AlarmVoiceLog.add(context, "No pude publicar el aviso: ${it.javaClass.simpleName}") }
  }

  fun cancelAlert(context: Context) {
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ALERT) }
  }

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Insto · callar alarma con la voz",
      NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Ofrece escuchar la palabra para apagar una alarma que está sonando."
      setSound(null, null)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    })
    manager.createNotificationChannel(NotificationChannel(CHANNEL_LISTENING, "Insto · escuchando para apagar la alarma",
      NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
  }

  // ---- Dismiss ------------------------------------------------------------------------------------------------

  /**
   * Turns the alarm off: the saved «Detener» action first; when there is none, it fails, or the alarm is still there
   * after [verifyMs], the system's dismiss intent. Blocking: called from the listening thread.
   */
  fun dismiss(context: Context, verifyMs: Long = 2_500L): String {
    val alarm = active ?: return "la alarma ya no estaba sonando"
    dismissingKey = alarm.key
    var method: String? = null
    alarm.stop?.let { pi ->
      val sent = runCatching { pi.send(context, 0, null, null, null, null, sendOptions()) }
      if (sent.isSuccess) method = "acción «${alarm.stopTitle}»"
      else AlarmVoiceLog.add(context, "La acción «${alarm.stopTitle}» falló: ${sent.exceptionOrNull()?.javaClass?.simpleName}")
    }
    if (method != null && waitRemoved(alarm, verifyMs)) {
      com.example.phone.CapabilityHealthRuntime.current()?.report("alarm.stop_voice", com.example.phone.HealthCode.VERIFIED)
      return method!!
    }
    if (method != null) AlarmVoiceLog.add(context, "La alarma siguió sonando tras la acción; uso el intent del sistema")
    val fallback = fallbackIntent(context, alarm)
    val started = runCatching { context.startActivity(fallback) }
    if (started.isFailure) {
      dismissingKey = null
      com.example.phone.CapabilityHealthRuntime.current()?.report("alarm.stop_voice", com.example.phone.HealthCode.REJECTED)
      return "no se pudo apagar (${started.exceptionOrNull()?.javaClass?.simpleName})"
    }
    val label = if (alarm.kind == AlarmKind.TIMER) "ACTION_DISMISS_TIMER" else "ACTION_DISMISS_ALARM"
    val verified = waitRemoved(alarm, verifyMs)
    com.example.phone.CapabilityHealthRuntime.current()?.report("alarm.stop_voice",
      if (verified) com.example.phone.HealthCode.VERIFIED else com.example.phone.HealthCode.NO_RESULT)
    return if (verified) label else "$label enviado, sin confirmación"
  }

  private fun waitRemoved(alarm: ActiveAlarm, ms: Long): Boolean {
    val deadline = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < deadline) {
      if (active?.id != alarm.id) return true
      Thread.sleep(100)
    }
    return active?.id != alarm.id
  }

  private fun fallbackIntent(context: Context, alarm: ActiveAlarm): Intent {
    val intent = if (alarm.kind == AlarmKind.TIMER) Intent(AlarmClock.ACTION_DISMISS_TIMER)
    else Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val targeted = Intent(intent).setPackage(alarm.packageName)
    return if (targeted.resolveActivity(context.packageManager) != null) targeted else intent
  }

  /** Android 14+ asks the sender to allow a background start for activity PendingIntents; Insto is visible then. */
  private fun sendOptions(): Bundle? {
    if (Build.VERSION.SDK_INT < 34) return null
    val options = ActivityOptions.makeBasic()
    if (Build.VERSION.SDK_INT >= 36) options.setPendingIntentBackgroundActivityStartMode(
      ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
    else @Suppress("DEPRECATION") options.setPendingIntentBackgroundActivityStartMode(
      ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    return options.toBundle()
  }
}

/**
 * Lowers STREAM_ALARM while Insto listens, so the microphone hears the person over the alarm. The original volume is
 * written to a file before changing anything: if the process dies, the next start of Insto or of the listener
 * restores it ([restore] is idempotent).
 */
object AlarmVolumeDucker {
  private const val FILE = "alarm-voice-volume-original"

  fun duck(context: Context, percent: Int) {
    runCatching {
      val audio = context.getSystemService(AudioManager::class.java) ?: return
      val file = File(context.applicationContext.noBackupFilesDir, FILE)
      val original = if (file.exists()) file.readText().trim().toInt() else audio.getStreamVolume(AudioManager.STREAM_ALARM)
      if (!file.exists()) file.writeText(original.toString())
      val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_ALARM) else 1
      audio.setStreamVolume(AudioManager.STREAM_ALARM, duckedVolume(original, percent, min), 0)
    }.onFailure { AlarmVoiceLog.add(context, "No pude bajar el volumen: ${it.javaClass.simpleName}") }
  }

  fun restore(context: Context) {
    val file = File(context.applicationContext.noBackupFilesDir, FILE)
    if (!file.exists()) return
    runCatching {
      val original = file.readText().trim().toInt()
      context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
    }
    file.delete()
  }

  /** On the next start of the process: nothing listens yet, so a saved volume is a leftover. */
  fun restoreLeftover(context: Context) { if (!AlarmVoiceStopService.isRunning) restore(context) }
}
