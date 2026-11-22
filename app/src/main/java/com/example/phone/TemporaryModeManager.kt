package com.example.phone

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** One lease at a time. Writes precede changes; expiry restores only an owned, unchanged value. */
class TemporaryModeManager(private val context: Context) {
 private val file=AtomicFile(File(context.noBackupFilesDir,"temporary-phone-mode.json"))
 private val audio=context.getSystemService(AudioManager::class.java)
 private val notifications=context.getSystemService(NotificationManager::class.java)
 private val alarms=context.getSystemService(AlarmManager::class.java)
 private fun pending()=PendingIntent.getBroadcast(context,42,Intent(context,TemporaryModeReceiver::class.java).setAction(EXPIRE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 @Synchronized fun read(): ModeLease? {
  if(!file.baseFile.exists())return null
  val o=JSONObject(file.openRead().bufferedReader().use {it.readText()})
  return ModeLease(o.getString("id"),ModeKind.valueOf(o.getString("kind")),o.getInt("original"),o.getInt("applied"),o.getLong("expiresAt"),o.getBoolean("owned"),o.optBoolean("exact",false))
 }
 @Synchronized private fun write(lease:ModeLease?) {
  if(lease==null){file.delete();return}
  val o=JSONObject().put("id",lease.id).put("kind",lease.kind.name).put("original",lease.original).put("applied",lease.applied).put("expiresAt",lease.expiresAt).put("owned",lease.owned).put("exact",lease.exact)
  val stream=file.startWrite()
  try {stream.write(o.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}catch(t:Exception){file.failWrite(stream);throw t}
 }
 fun current(kind:ModeKind):Int=when(kind){ModeKind.MEDIA_VOLUME->audio.getStreamVolume(AudioManager.STREAM_MUSIC);ModeKind.SILENT->audio.ringerMode;ModeKind.DND->notifications.currentInterruptionFilter}
 private fun apply(kind:ModeKind,value:Int){when(kind){ModeKind.MEDIA_VOLUME->audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);ModeKind.SILENT->audio.ringerMode=value;ModeKind.DND->notifications.setInterruptionFilter(value)}}
 private var observed=true
 fun execute(command:PhoneCommand.TemporaryMode,confirmed:Boolean):PhoneActionResult {
  val now = System.currentTimeMillis()
  val expiresAt = command.deadlineMillis ?: (now + command.seconds.toLong() * 1000)
  if (expiresAt - now !in 1..86_400_000) return PhoneActionResult(
   "La hora de restauración ya pasó o queda fuera de las próximas 24 horas. Indicá otra hora.",
   outcome = PhoneActionOutcome.REJECTED
  )
  if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return PhoneActionResult("Permití las notificaciones para mostrar el modo temporal y su botón de restauración.",needsNotifications=true,outcome=PhoneActionOutcome.NEEDS_INPUT)
  if(read()!=null) return PhoneActionResult("Ya hay un modo temporal activo. Podés terminarlo y restaurar antes de iniciar otro.",outcome=PhoneActionOutcome.REJECTED)
  if(command.kind!=ModeKind.MEDIA_VOLUME && !notifications.isNotificationPolicyAccessGranted) return PhoneActionResult("Para silenciar llamadas/notificaciones o usar No molestar, habilitá el acceso correspondiente.",accessSettingsAction=Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,outcome=PhoneActionOutcome.REJECTED)
  if(command.kind==ModeKind.MEDIA_VOLUME && audio.isVolumeFixed) return PhoneActionResult("Este dispositivo tiene volumen fijo.",outcome=PhoneActionOutcome.REJECTED)
  val exact=Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()
  if(!exact && !command.approximate) {
   val approximate = command.copy(approximate = true)
   return PhoneActionResult(
    "${PhoneCommandCodec.summary(approximate)}. Android puede demorar la restauración con el ahorro de batería. ¿Aceptás?",
    confirmation = approximate, outcome = PhoneActionOutcome.NEEDS_INPUT
   )
  }
  if(!confirmed) return PhoneActionResult("${PhoneCommandCodec.summary(command)}. Las alarmas y la conectividad no se modifican. ¿Confirmás?",confirmation=command,outcome=PhoneActionOutcome.NEEDS_INPUT)
  val applied=when(command.kind){ModeKind.MEDIA_VOLUME->audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*command.percent/100;ModeKind.SILENT->AudioManager.RINGER_MODE_SILENT;ModeKind.DND->NotificationManager.INTERRUPTION_FILTER_PRIORITY}
  val lease=ModeLease(UUID.randomUUID().toString(),command.kind,current(command.kind),applied,expiresAt,true,exact && !command.approximate)
  write(lease)
  try {
   // Starting from a user action permits the visible foreground observer.
   val service=Intent(context,TemporaryModeService::class.java)
   // From the background Android 12+ may refuse a foreground service (1.20.0): the mode still applies and restores
   // by its alarm; only the visible «Terminar» and the watch for manual changes are missing until Insto opens.
   observed=runCatching {if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service) else context.startService(service)}.isSuccess
   schedule(lease,exact && !command.approximate)
   apply(lease.kind,lease.applied)
   if(current(lease.kind)!=lease.applied) {write(null);alarms.cancel(pending());context.stopService(service);return PhoneActionResult("El sistema no confirmó el ajuste. Revisá el teléfono.",outcome=PhoneActionOutcome.REJECTED)}
  } catch(t:Exception) {
   if(current(lease.kind)==lease.applied) runCatching {apply(lease.kind,lease.original)}
   write(null);alarms.cancel(pending());context.stopService(Intent(context,TemporaryModeService::class.java));throw t
  }
  return PhoneActionResult("Modo temporal aplicado. La restauración será ${if(command.approximate) "aproximada" else "programada"}." +
   (if(observed) "" else " Android no permitió mostrar su aviso desde segundo plano; igual se restaura a su hora.") +
   " Podés decir: terminá el modo y restaurá ahora.")
 }
 private fun schedule(lease:ModeLease,exact:Boolean){if(exact)alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,lease.expiresAt,pending()) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,lease.expiresAt,pending())}
 fun observeManualChange(){synchronized(LOCK){val lease=read()?:return;if(lease.owned && current(lease.kind)!=lease.applied)write(lease.copy(owned=false))}}
 fun end(force:Boolean=false):PhoneActionResult=synchronized(LOCK){
  val lease=read()?:return@synchronized PhoneActionResult("No hay un modo temporal activo.")
  val restore=force || TemporaryModePolicy.canRestore(lease,current(lease.kind))
  if(restore)apply(lease.kind,lease.original)
  write(null);alarms.cancel(pending());context.stopService(Intent(context,TemporaryModeService::class.java))
  PhoneActionResult(if(restore)"Modo terminado y estado anterior restaurado." else "Modo terminado. Conservé el cambio manual del teléfono.")
 }
 fun reconcile(boot:Boolean=false){synchronized(LOCK){
  val lease=read()?:return
  if(boot) {
   // Boot/process gaps cannot prove ownership. Never overwrite a possible manual change.
   write(lease.copy(owned=false))
  }
  if(TemporaryModePolicy.expired(lease,System.currentTimeMillis()))TemporaryModeRestorations.restore(context,this,lease)
  else {
   val remaining=read()?:return
   schedule(remaining,remaining.exact && (Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()))
  }
 }}
 fun loseOwnership(){synchronized(LOCK){read()?.let {write(it.copy(owned=false))}}}
 /**
  * A new process (1.20.0): nobody watched for manual changes in the gap, but the value Insto applied is still there
  * if nobody touched it, and [TemporaryModePolicy.canRestore] checks exactly that at expiry. Ownership is kept; a
  * value that differs from the applied one when it expires is a manual change and is never overwritten.
  */
 fun markUnobserved(){synchronized(LOCK){observeManualChange()}}
 companion object {private val LOCK=Any();const val EXPIRE="com.example.phone.EXPIRE_TEMPORARY_MODE"}
}
