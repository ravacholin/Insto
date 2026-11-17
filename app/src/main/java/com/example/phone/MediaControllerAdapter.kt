package com.example.phone

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

class MediaControllerAdapter(private val context: Context) {
  fun execute(command: PhoneCommand.Media): PhoneActionResult {
    val sessions = activeSessions() ?: return PhoneActionResult(
      "Habilitá el acceso a notificaciones para controlar sesiones multimedia. Insto no lee mensajes para esta función.",
      accessSettingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
      outcome = PhoneActionOutcome.REJECTED,
    )
    val options = sessions.map { session ->
      val state = session.playbackState
      val actions = state?.actions ?: 0L
      val operations = MediaOp.entries.filter { it == MediaOp.STATUS || actions and action(it) != 0L }.toSet()
      MediaSessionOption(session.packageName, operations, state?.state == PlaybackState.STATE_PLAYING)
    }
    val resolution = MediaSessionPolicy.resolve(options, command.operation, command.packageName)
    val target = when (resolution) {
      MediaResolution.Choose -> return PhoneActionResult("¿Qué aplicación querés controlar?", appOptions = sessions.map {
        AppOption(appLabel(it.packageName), it.packageName)
      }, outcome = PhoneActionOutcome.NEEDS_INPUT)
      MediaResolution.Missing -> return PhoneActionResult("No encontré una sesión multimedia compatible activa. Abrí tu aplicación de música y volvé a intentar.", outcome = PhoneActionOutcome.REJECTED)
      MediaResolution.Ambiguous -> return PhoneActionResult("Hay varias sesiones de una misma app. Seleccioná la reproducción desde esa app antes de continuar.", outcome = PhoneActionOutcome.REJECTED)
      MediaResolution.Unsupported -> return PhoneActionResult("Esa sesión no permite esta operación.", outcome = PhoneActionOutcome.REJECTED)
      is MediaResolution.Ready -> sessions.single { it.packageName == resolution.packageName }
    }
    if (command.operation == MediaOp.STATUS) {
      val title = target.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "sin título disponible"
      val status = if (target.playbackState?.state == PlaybackState.STATE_PLAYING) "reproduciendo" else "sin reproducción activa"
      return PhoneActionResult("${target.packageName}: $title · $status.")
    }
    val controls = target.transportControls
    val verb = when (resolution.fallbackOperation ?: command.operation) {
      MediaOp.PLAY -> "reanudación" to controls::play
      MediaOp.PAUSE -> "pausa" to controls::pause
      MediaOp.STOP -> "detención" to controls::stop
      MediaOp.NEXT -> "siguiente tema" to controls::skipToNext
      MediaOp.PREVIOUS -> "tema anterior" to controls::skipToPrevious
      MediaOp.STATUS -> error("Status has no transport action")
    }
    return dispatch("Orden de ${verb.first} enviada a ${appLabel(target.packageName)}.", verb.second)
  }

  internal fun playFromSearch(app: MediaApp, query: String): PhoneActionResult? {
    val session = activeSessions()?.filter { it.packageName == app.packageName }?.singleOrNull() ?: return null
    if ((session.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH == 0L) return null
    return dispatch("Pedido de reproducción enviado a ${app.label}.") {
      session.transportControls.playFromSearch(query, null)
    }
  }

  private fun activeSessions(): List<MediaController>? = try {
    val manager = context.getSystemService(MediaSessionManager::class.java)
    manager?.getActiveSessions(ComponentName(context, MediaAccessService::class.java))
  } catch (_: SecurityException) {
    null
  }

  private fun action(op: MediaOp): Long = when (op) {
    MediaOp.PLAY -> PlaybackState.ACTION_PLAY
    MediaOp.PAUSE -> PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
    MediaOp.STOP -> PlaybackState.ACTION_STOP
    MediaOp.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
    MediaOp.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
    MediaOp.STATUS -> 0L
  }

  private fun appLabel(packageName: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
  }.getOrDefault(packageName)

  // Transport callbacks are asynchronous: report delivery, not verified playback.
  private fun dispatch(message: String, send: () -> Unit): PhoneActionResult = try {
    send()
    PhoneActionResult(message, outcome = PhoneActionOutcome.HANDOFF)
  } catch (_: Exception) {
    PhoneActionResult("El control pudo haberse entregado. Revisá la reproducción antes de continuar; no se repetirá automáticamente.", outcome = PhoneActionOutcome.UNCERTAIN)
  }
}
