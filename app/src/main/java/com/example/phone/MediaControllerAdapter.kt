package com.example.phone

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

class MediaControllerAdapter(private val context: Context) {
 fun execute(command: PhoneCommand.Media): PhoneActionResult {
  val manager=context.getSystemService(MediaSessionManager::class.java)
  val sessions=try { manager.getActiveSessions(ComponentName(context,MediaAccessService::class.java)) }
   catch (_: SecurityException) {return PhoneActionResult("Habilitá el acceso a notificaciones para controlar sesiones multimedia. Insto no lee mensajes para esta función.",accessSettingsAction=Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,outcome=PhoneActionOutcome.REJECTED)}
  fun action(op:MediaOp):Long=when(op){MediaOp.PLAY->PlaybackState.ACTION_PLAY;MediaOp.PAUSE->PlaybackState.ACTION_PAUSE;MediaOp.NEXT->PlaybackState.ACTION_SKIP_TO_NEXT;MediaOp.PREVIOUS->PlaybackState.ACTION_SKIP_TO_PREVIOUS;MediaOp.STATUS->0L}
  val options=sessions.map {session -> MediaSessionOption(session.packageName,MediaOp.entries.filter {it==MediaOp.STATUS || (session.playbackState?.actions?:0L) and action(it)!=0L}.toSet())}
  val resolution=MediaSessionPolicy.resolve(options,command.operation,command.packageName)
  val target=when(resolution){
   MediaResolution.Choose->return PhoneActionResult("¿Qué aplicación querés controlar?",appOptions=sessions.map {
    val label=runCatching {context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it.packageName,0)).toString()}.getOrDefault(it.packageName)
    AppOption(label,it.packageName)
   },outcome=PhoneActionOutcome.NEEDS_INPUT)
   MediaResolution.Missing->return PhoneActionResult("No encontré una sesión multimedia compatible activa. Abrí tu aplicación de música y volvé a intentar.",outcome=PhoneActionOutcome.REJECTED)
   MediaResolution.Ambiguous->return PhoneActionResult("Hay varias sesiones de una misma app. Seleccioná la reproducción desde esa app antes de continuar.",outcome=PhoneActionOutcome.REJECTED)
   MediaResolution.Unsupported->return PhoneActionResult("Esa sesión no permite esta operación.",outcome=PhoneActionOutcome.REJECTED)
   is MediaResolution.Ready->sessions.single {it.packageName==resolution.packageName}
  }
  val state=target.playbackState
  val title=target.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
  if(command.operation==MediaOp.STATUS) return PhoneActionResult("${target.packageName}: ${title ?: "sin título disponible"} · ${if(state?.state==PlaybackState.STATE_PLAYING) "reproduciendo" else "sin reproducción activa"}.")
  try { when(command.operation) {
   MediaOp.PLAY->target.transportControls.play()
   MediaOp.PAUSE->target.transportControls.pause()
   MediaOp.NEXT->target.transportControls.skipToNext()
   MediaOp.PREVIOUS->target.transportControls.skipToPrevious()
   else->Unit
  }
  } catch (_: Exception) {return PhoneActionResult("El control pudo haberse entregado. Revisá la reproducción antes de continuar; no se repetirá automáticamente.",outcome=PhoneActionOutcome.UNCERTAIN)}
  // Transport callbacks are asynchronous: report delivery, not verified playback.
  return PhoneActionResult("Control multimedia entregado a ${target.packageName}.",outcome=PhoneActionOutcome.HANDOFF)
 }
}
