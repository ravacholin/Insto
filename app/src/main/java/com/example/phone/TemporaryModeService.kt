package com.example.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/** Visible only while a temporary mode owns a setting. Observes settings, never microphone/content. */
class TemporaryModeService:Service() {
 private val manager by lazy {TemporaryModeManager(this)}
 private val changeReceiver=object:android.content.BroadcastReceiver() {
  override fun onReceive(context:android.content.Context,intent:Intent){runCatching{manager.observeManualChange()}}
 }
 private val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean){runCatching{manager.observeManualChange()}}}
 override fun onCreate(){
  super.onCreate()
  val nm=getSystemService(NotificationManager::class.java)
  if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel("temporary-mode","Modo temporal",NotificationManager.IMPORTANCE_LOW))
  val end=PendingIntent.getService(this,0,Intent(this,TemporaryModeService::class.java).setAction("END"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,"temporary-mode") else Notification.Builder(this)
  startForeground(43,builder.setSmallIcon(android.R.drawable.ic_lock_silent_mode).setContentTitle("Modo temporal de Insto activo").setContentText("Restauración programada · tocar Terminar para restaurar ahora").setOngoing(true).addAction(Notification.Action.Builder(null,"Terminar",end).build()).build())
  androidx.core.content.ContextCompat.registerReceiver(this,changeReceiver,android.content.IntentFilter().apply {
    addAction("android.media.VOLUME_CHANGED_ACTION");addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
  },androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
  contentResolver.registerContentObserver(Settings.System.CONTENT_URI,true,observer)
  contentResolver.registerContentObserver(Settings.Global.CONTENT_URI,true,observer)
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="END"){runCatching{manager.end(force=true)};stopSelf();return START_NOT_STICKY}
  if(intent==null)manager.loseOwnership()
  return START_STICKY
 }
 override fun onDestroy(){unregisterReceiver(changeReceiver);contentResolver.unregisterContentObserver(observer);super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null
}
