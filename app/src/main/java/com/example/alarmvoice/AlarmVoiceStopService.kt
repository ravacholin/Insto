package com.example.alarmvoice

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.audio.EyesFreeFeedbackManager
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Listens for the configured words while an alarm rings, foreground service of type microphone. Started only from
 * [AlarmVoiceStopActivity] while it is visible: Android does not allow opening the microphone from the background.
 * Audio is read into memory, handed to Vosk on the phone and dropped; nothing is recorded, saved or sent.
 */
class AlarmVoiceStopService : Service() {
  private var worker: Thread? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      requestStop("dejé de escuchar")
      if (worker == null) stopSelf()
      return START_NOT_STICKY
    }
    if (worker != null) return START_NOT_STICKY
    val id = intent?.getLongExtra(AlarmVoiceCoordinator.EXTRA_ALARM_ID, -1L) ?: -1L
    val test = intent?.getBooleanExtra(AlarmVoiceCoordinator.EXTRA_TEST, false) == true
    val started = runCatching {
      AlarmVoiceCoordinator.ensureChannels(this)
      val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
      ServiceCompat.startForeground(this, AlarmVoiceCoordinator.NOTIFICATION_LISTENING, listeningNotification(), type)
    }
    if (started.isFailure) {
      AlarmVoiceLog.add(this, "Android no dejó iniciar la escucha: ${started.exceptionOrNull()?.javaClass?.simpleName}")
      AlarmVoiceCoordinator.finishUi("No se pudo abrir el micrófono.")
      stopSelf()
      return START_NOT_STICKY
    }
    stopReason.set(null)
    isRunning = true
    worker = thread(name = "alarm-voice-listen") {
      try { listen(id, test) } finally {
        AlarmVolumeDucker.restore(this)
        AlarmVoiceEngine.release()
        isRunning = false
        worker = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    requestStop("servicio destruido")
    // The worker restores it too; this covers a worker that never ran.
    if (worker == null) AlarmVolumeDucker.restore(this)
    super.onDestroy()
  }

  private fun listeningNotification() = NotificationCompat.Builder(this, AlarmVoiceCoordinator.CHANNEL_LISTENING)
    .setSmallIcon(com.example.R.drawable.ic_mic_pulse)
    .setContentTitle("Escuchando para apagar la alarma")
    .setContentText("Nada se graba ni se envía.")
    .setOngoing(true)
    .setSilent(true)
    .setCategory(NotificationCompat.CATEGORY_SERVICE)
    .addAction(0, "Dejar de escuchar", PendingIntent.getService(this, 3,
      Intent(this, AlarmVoiceStopService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    .build()

  @SuppressLint("MissingPermission") // Checked right before creating the AudioRecord.
  private fun listen(id: Long, test: Boolean) {
    val settings = AlarmVoiceSettings(this)
    val keywords = settings.keywords()
    val windowMs = settings.windowSeconds() * 1000L
    val ui = AlarmVoiceCoordinator.state
    if (!test && !AlarmVoiceCoordinator.isCurrent(id)) { AlarmVoiceCoordinator.finishUi("La alarma ya no está sonando."); return }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      AlarmVoiceCoordinator.finishUi("Falta el permiso de micrófono."); return
    }
    ui.value = AlarmVoiceUiState(status = "Preparando el reconocimiento…")
    val health = com.example.phone.CapabilityHealthRuntime.current()
    val model = AlarmVoiceEngine.obtain(this)
    health?.report("vosk.load", if (model == null) com.example.phone.HealthCode.MODEL_LOAD else com.example.phone.HealthCode.VERIFIED)
    if (model == null) {
      AlarmVoiceLog.add(this, "No se pudo cargar el modelo de voz")
      AlarmVoiceCoordinator.finishUi("No se pudo cargar el modelo de voz."); return
    }
    if (!test && settings.duckEnabled()) AlarmVolumeDucker.duck(this, settings.duckPercent())

    val rate = SAMPLE_RATE
    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val frame = ShortArray(rate / 10) // 100 ms
    var record: AudioRecord? = null
    var recognizer: Recognizer? = null
    val effects = mutableListOf<AudioEffect>()
    var outcome = "tiempo agotado"
    try {
      recognizer = Recognizer(model, rate.toFloat(), KeywordMatcher.grammarJson(keywords)).apply { setWords(true) }
      record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, frame.size * 2 * 4))
      if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord sin inicializar")
      if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
      if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
      record.startRecording()
      health?.report("system.foreground", com.example.phone.HealthCode.VERIFIED)
      var captured = false
      AlarmVoiceLog.add(this, "Escuchando ${windowMs / 1000} s" + (if (test) " (prueba)" else "") +
        "; eco: ${if (effects.any { it is AcousticEchoCanceler }) "sí" else "no"}, ruido: ${if (effects.any { it is NoiseSuppressor }) "sí" else "no"}")
      val confirm = PartialConfirmation(keywords)
      val deadline = System.currentTimeMillis() + windowMs
      var heard: String? = null
      var lastSecond = -1
      while (true) {
        val now = System.currentTimeMillis()
        if (now >= deadline) break
        stopReason.get()?.let { outcome = it; break }
        if (!test && !AlarmVoiceCoordinator.isCurrent(id)) { outcome = "alarma apagada a mano"; break }
        val remaining = ((deadline - now + 999) / 1000).toInt()
        if (remaining != lastSecond) {
          lastSecond = remaining
          ui.value = AlarmVoiceUiState(status = "Escuchando…", listening = true, remainingSeconds = remaining)
        }
        val n = record.read(frame, 0, frame.size)
        if (n <= 0) continue
        if (!captured && (0 until n).any { frame[it] != 0.toShort() }) {
          captured = true
          if (!test) health?.report("alarm.listening", com.example.phone.HealthCode.VERIFIED)
        }
        heard = if (recognizer.acceptWaveForm(frame, n)) {
          confirm.reset()
          KeywordMatcher.matchWords(AlarmVoiceEngine.words(recognizer.result), keywords)
        } else confirm.onPartial(AlarmVoiceEngine.partial(recognizer.partialResult))
        if (heard != null) break
      }
      if (heard != null) {
        runCatching { record.stop() }
        outcome = if (test) {
          ui.value = AlarmVoiceUiState(status = "Te escuché: «$heard»", detail = "Era una prueba: no se apagó nada.")
          "prueba: reconoció «$heard»"
        } else {
          ui.value = AlarmVoiceUiState(status = "Apagando…", detail = "Escuché «$heard».")
          val method = AlarmVoiceCoordinator.dismiss(this)
          "apagada por voz («$heard») con $method"
        }
        EyesFreeFeedbackManager.playSentCue(this, false)
      }
    } catch (e: Throwable) {
      health?.report("alarm.listening", com.example.phone.HealthCode.MICROPHONE,
        stage = com.example.phone.HealthStage.CAPTURE)
      outcome = "error: ${e.javaClass.simpleName}"
    } finally {
      runCatching { record?.stop() }
      runCatching { record?.release() }
      effects.forEach { runCatching { it.release() } }
      runCatching { recognizer?.close() }
      AlarmVolumeDucker.restore(this)
      AlarmVoiceLog.add(this, "Fin de la escucha: $outcome")
      AlarmVoiceCoordinator.finishUi(when {
        outcome.startsWith("apagada") -> "Alarma apagada."
        outcome.startsWith("prueba") -> ui.value.status
        outcome == "tiempo agotado" -> "Se terminó el tiempo de escucha. La alarma sigue."
        outcome.startsWith("error") -> "No se pudo escuchar ($outcome)."
        else -> "Dejé de escuchar."
      })
    }
  }

  companion object {
    const val SAMPLE_RATE = 16_000
    const val ACTION_STOP = "com.example.alarmvoice.STOP_LISTENING"
    @Volatile var isRunning = false
      private set
    private val stopReason = AtomicReference<String?>(null)

    /** Stops the listening loop, if any, with [reason] for the log. Never touches the alarm. */
    fun requestStop(reason: String) { if (isRunning) stopReason.compareAndSet(null, reason) }

    /** Only from the visible [AlarmVoiceStopActivity]. */
    fun start(context: Context, id: Long, test: Boolean) {
      val intent = Intent(context, AlarmVoiceStopService::class.java)
        .putExtra(AlarmVoiceCoordinator.EXTRA_ALARM_ID, id).putExtra(AlarmVoiceCoordinator.EXTRA_TEST, test)
      runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
        .onFailure { AlarmVoiceLog.add(context, "No pude iniciar el servicio de escucha: ${it.javaClass.simpleName}") }
    }
  }
}
