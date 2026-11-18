package com.example.phone

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Read-only preflight hints; actual permissions and capabilities are rechecked at dispatch. */
object CapabilityRequirements {
 fun missing(context:Context,command:PhoneCommand):List<String> {
  fun denied(permission:String)=context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED
  return buildList {
   if(command is PhoneCommand.Torch && denied(Manifest.permission.CAMERA))add("permiso de cámara pendiente")
   if(command is PhoneCommand.Dial && PhoneNumbers.canonical(command.target)==null && denied(Manifest.permission.READ_CONTACTS))add("permiso de contactos pendiente")
   if (command is PhoneCommand.Platform && command.action in CalendarSelection.actions) {
    if (denied(Manifest.permission.READ_CALENDAR)) add("permiso de lectura del calendario pendiente")
    if (command.action != PlatformAction.CALENDAR_QUERY && denied(Manifest.permission.WRITE_CALENDAR))
      add("permiso de escritura del calendario pendiente")
   }
   if(command is PhoneCommand.Media) {
    val component=ComponentName(context,MediaAccessService::class.java)
    val access=if(Build.VERSION.SDK_INT>=27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
     else Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners")?.split(':')?.contains(component.flattenToString())==true
    if(!access)add("acceso multimedia pendiente")
   }
   if(command is PhoneCommand.TemporaryMode) {
    if(Build.VERSION.SDK_INT>=33 && denied(Manifest.permission.POST_NOTIFICATIONS))add("permiso de notificaciones pendiente")
    if(command.kind!=ModeKind.MEDIA_VOLUME && !context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)add("acceso a No molestar pendiente")
   }
  }
 }
}
