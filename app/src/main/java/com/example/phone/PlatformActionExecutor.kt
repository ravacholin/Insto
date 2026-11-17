package com.example.phone

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings

/** Public Android adapters. No arbitrary components, URIs or executable model output. */
internal class PlatformActionExecutor(private val context: Context) {
  fun execute(command: PhoneCommand.Platform,confirmed:Boolean=false): PhoneActionResult {
    // Revalidate even when commands originate from disk or another caller.
    PhoneCommandCodec.decode(PhoneCommandCodec.encode(command))
    return when(command.action) {
      PlatformAction.CALL_DIRECT -> localActivity(command,CallActionActivity::class.java)
      PlatformAction.MESSAGE_SEND -> {
        if(!confirmed) PhoneActionResult("Enviar por WhatsApp a ${command.argument}:\n${command.content}",confirmation=command,outcome=PhoneActionOutcome.NEEDS_INPUT)
        else {
          val prefs=kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {com.example.data.AssistantPreferencesRepository(context).getCurrentPreferences()}
          val result=com.example.whatsapp.WhatsAppDispatcher.sendWhatsAppText(context,command.argument!!,command.content!!,prefs.preferredFlavor,
            com.example.assist.PingAutoSendAccessibilityService.isEnabled(context),recipientName=command.argument!!)
          if(result is com.example.whatsapp.DispatchResult.Error) PhoneActionResult(result.message,outcome=PhoneActionOutcome.REJECTED,
            accessSettingsAction=Settings.ACTION_ACCESSIBILITY_SETTINGS.takeIf {result.message.contains("Accesibilidad")})
          else PhoneActionResult("Solicitud de envío entregada a WhatsApp. Revisá allí el resultado.",outcome=PhoneActionOutcome.HANDOFF)
        }
      }
      PlatformAction.CAPTURE_PHOTO,PlatformAction.CAPTURE_VIDEO,PlatformAction.CAPTURE_AUDIO -> localActivity(command,CaptureActionActivity::class.java)
      PlatformAction.FILE_OPEN,PlatformAction.FILE_SEARCH,PlatformAction.FILE_SHARE,PlatformAction.FILE_RENAME,PlatformAction.FILE_COPY,PlatformAction.FILE_MOVE,PlatformAction.FILE_DELETE -> localActivity(command,FileActionActivity::class.java)
      PlatformAction.CALENDAR_QUERY,PlatformAction.CALENDAR_UPDATE,PlatformAction.CALENDAR_DELETE -> localActivity(command,CalendarActionActivity::class.java)
      PlatformAction.ROUTINE_IMPORT -> localActivity(command,RoutineImportActivity::class.java)
      PlatformAction.ROUTINE_LIST -> {
        val routines=ConfiguredRoutineStore(java.io.File(context.noBackupFilesDir,"configured-routines.json")).read()
        PhoneActionResult(if(routines.isEmpty()) "No hay rutinas configuradas. Podés importar plantillas revisadas desde un archivo JSON." else routines.joinToString("\n") {it.name})
      }
      PlatformAction.ROUTINE_RUN -> rejected("La rutina debe revisarse en el asistente antes de ejecutar sus pasos.")
      PlatformAction.BATTERY -> {
        val percent=context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if(percent in 0..100) PhoneActionResult("Batería: $percent%.") else rejected("Android no informó el nivel de batería.")
      }
      PlatformAction.BRIGHTNESS -> {
        if(!Settings.System.canWrite(context)) access("Habilitá el acceso para modificar el brillo y reintentá el pedido.",Settings.ACTION_MANAGE_WRITE_SETTINGS)
        else {
          val resolver=context.contentResolver
          val ok=Settings.System.putInt(resolver,Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
            Settings.System.putInt(resolver,Settings.System.SCREEN_BRIGHTNESS,255*command.percent!!/100)
          if(ok) PhoneActionResult("Brillo ajustado al ${command.percent}%.") else rejected("Android no permitió ajustar el brillo.")
        }
      }
      PlatformAction.SILENT_ON,PlatformAction.SILENT_OFF,PlatformAction.DND_ON,PlatformAction.DND_OFF -> mode(command.action)
      PlatformAction.CONTACT_PHONES -> contactPhones(command.argument!!)
      PlatformAction.NOTIFICATION_LIST,PlatformAction.NOTIFICATION_OPEN,PlatformAction.NOTIFICATION_DISMISS -> notifications(command)
      PlatformAction.APP_SETTINGS -> appSettings(command.argument!!)
      else -> {
        val intent=when(command.action) {
          PlatformAction.ALARM_DISMISS -> Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE,AlarmClock.ALARM_SEARCH_MODE_LABEL)
            .putExtra(AlarmClock.EXTRA_MESSAGE,command.argument!!).putExtra(AlarmClock.EXTRA_SKIP_UI,false)
          PlatformAction.ALARM_SNOOZE -> Intent(AlarmClock.ACTION_SNOOZE_ALARM)
          PlatformAction.TIMER_DISMISS -> Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI,false)
          PlatformAction.CALENDAR_AGENDA -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_CALENDAR)
          PlatformAction.DIALER_OPEN -> Intent(Intent.ACTION_DIAL)
          PlatformAction.CONTACT_SEARCH -> Intent(Intent.ACTION_SEARCH).setType(ContactsContract.Contacts.CONTENT_TYPE)
            .putExtra(SearchManager.QUERY,command.argument)
          PlatformAction.MESSAGE_OPEN,PlatformAction.MESSAGE_DRAFT -> Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",command.argument!!,null))
            .apply { command.content?.let {putExtra("sms_body",it)} }
          PlatformAction.TEXT_SHARE -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,command.content),"Compartir texto")
          PlatformAction.FILES_BROWSE -> Intent(Intent.ACTION_VIEW,
            DocumentsContract.buildRootUri("com.android.externalstorage.documents","primary"))
            .setType("vnd.android.document/root").apply {
              // setType clears data on Android; set both explicitly.
              setDataAndType(DocumentsContract.buildRootUri("com.android.externalstorage.documents","primary"),"vnd.android.document/root")
            }
          PlatformAction.RECORDER_OPEN -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
          PlatformAction.ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
          PlatformAction.DISPLAY_SETTINGS -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
          else -> return rejected("No hay un adaptador Android para esa acción.")
        }
        handoff(intent,when(command.action) {
          PlatformAction.MESSAGE_DRAFT -> "Borrador SMS preparado. Revisá el destinatario y enviá desde la aplicación de mensajes."
          PlatformAction.MESSAGE_OPEN -> "Conversación SMS preparada. No se envió ningún mensaje."
          PlatformAction.ALARM_DISMISS -> "Solicitud para descartar la alarma «${command.argument}» entregada al Reloj. Revisá allí la selección."
          PlatformAction.ALARM_SNOOZE -> "Solicitud para posponer la alarma que suena entregada al Reloj."
          PlatformAction.TIMER_DISMISS -> "Solicitud para descartar el temporizador entregada al Reloj. Revisá allí la selección."
          PlatformAction.FILES_BROWSE -> "Administrador de archivos abierto. Elegí allí el archivo y la operación."
          PlatformAction.RECORDER_OPEN -> "Grabadora abierta. Iniciá y controlá la grabación en esa aplicación."
          PlatformAction.CALENDAR_AGENDA -> "Calendario abierto. Revisá allí tu agenda."
          PlatformAction.CONTACT_SEARCH -> "Búsqueda de «${command.argument}» entregada a Contactos."
          PlatformAction.TEXT_SHARE -> "Texto entregado al selector de aplicaciones. Revisá el destino antes de compartir."
          else -> "Pantalla solicitada abierta."
        })
      }
    }
  }

  private fun mode(action: PlatformAction): PhoneActionResult {
    val manager=context.getSystemService(NotificationManager::class.java)
    if(!manager.isNotificationPolicyAccessGranted) return access("Habilitá el acceso a No molestar para aplicar este modo y reintentá.",Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    val silent=action in setOf(PlatformAction.SILENT_ON,PlatformAction.SILENT_OFF)
    val observed=if(silent) {
      val audio=context.getSystemService(AudioManager::class.java)
      val target=if(action==PlatformAction.SILENT_ON) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
      audio.ringerMode=target
      audio.ringerMode==target
    } else {
      val target=if(action==PlatformAction.DND_ON) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
      manager.setInterruptionFilter(target)
      manager.currentInterruptionFilter==target
    }
    return if(observed) PhoneActionResult(when(action) {
      PlatformAction.SILENT_ON -> "Llamadas y notificaciones en silencio."
      PlatformAction.SILENT_OFF -> "Modo de sonido normal activado."
      PlatformAction.DND_ON -> "No molestar activado."
      else -> "No molestar desactivado."
    }) else PhoneActionResult("Android recibió el cambio, pero no pude confirmar el estado. Revisá los ajustes antes de reintentar.",outcome=PhoneActionOutcome.UNCERTAIN)
  }

  private fun contactPhones(query: String): PhoneActionResult {
    if(context.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)
      return PhoneActionResult("Permití el acceso a contactos para consultar ese teléfono.",needsContacts=true,outcome=PhoneActionOutcome.NEEDS_INPUT)
    val phone=ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
    val uri=Uri.withAppendedPath(phone,Uri.encode(query))
    val values=context.contentResolver.query(uri,arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,null)?.use { cursor ->
      buildList {
        while(size<20 && cursor.moveToNext()) {
          val name=cursor.getString(0); val number=cursor.getString(1)
          if(!name.isNullOrBlank() && !number.isNullOrBlank()) add("$name: $number")
        }
      }.distinct()
    }.orEmpty()
    return if(values.isEmpty()) rejected("No encontré teléfonos para $query.") else PhoneActionResult(values.joinToString("\n"))
  }

  @Suppress("DEPRECATION")
  private fun appSettings(query: String): PhoneActionResult {
    val apps=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
      .map {AppOption(it.loadLabel(context.packageManager).toString(),it.activityInfo.packageName)}.distinctBy {it.packageName}
    val folded=PhoneCommandParser.normalize(query)
    val exact=apps.filter {it.packageName==query || PhoneCommandParser.normalize(it.label)==folded}
    val matches=exact.ifEmpty {apps.filter {PhoneCommandParser.normalize(it.label).contains(folded)}}
    if(matches.isEmpty()) return rejected("No encontré esa aplicación instalada.")
    if(matches.size>1) return PhoneActionResult("Elegí la aplicación cuyos ajustes querés abrir.",appOptions=matches.take(20),outcome=PhoneActionOutcome.NEEDS_INPUT)
    return handoff(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",matches.single().packageName,null)),"Ajustes de ${matches.single().label} abiertos.")
  }

  private fun notifications(command:PhoneCommand.Platform):PhoneActionResult {
    val service=NotificationAccessService.connected ?: return access("Habilitá «Insto · notificaciones» para este pedido. El acceso multimedia no permite leer mensajes.",Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    val active=service.activeNotifications.orEmpty().filter {it.packageName!=context.packageName}
    fun title(n:android.service.notification.StatusBarNotification)=n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
    if(command.action==PlatformAction.NOTIFICATION_LIST) return PhoneActionResult(
      if(active.isEmpty()) "No hay notificaciones activas disponibles." else active.take(20).joinToString("\n") {"${it.packageName}: ${title(it).ifBlank {"sin título"}}"})
    val query=PhoneCommandParser.normalize(command.argument!!)
    val matches=active.filter {PhoneCommandParser.normalize(title(it))==query}
    if(matches.size!=1) return rejected(if(matches.isEmpty()) "No encontré una notificación con ese título." else "Hay varias notificaciones con ese título. Indicá un título único; no se cambió ninguna.")
    val selected=matches.single()
    return if(command.action==PlatformAction.NOTIFICATION_DISMISS) {
      if(!selected.isClearable) rejected("Android no permite descartar esa notificación.") else {
        service.cancelNotification(selected.key)
        PhoneActionResult("Descarte solicitado para «${title(selected)}». Android procesa la solicitud.",outcome=PhoneActionOutcome.HANDOFF)
      }
    } else {
      val pending=selected.notification.contentIntent ?: return rejected("Esa notificación no ofrece una acción para abrirla.")
      pending.send()
      PhoneActionResult("Apertura de la notificación solicitada.",outcome=PhoneActionOutcome.HANDOFF)
    }
  }

  private fun localActivity(command:PhoneCommand.Platform,type:Class<out LocalActionActivity>):PhoneActionResult = handoff(
    Intent(context,type).putExtra(LocalActionActivity.EXTRA_COMMAND,PhoneCommandCodec.encode(command).toString()),
    "Pedido abierto en Insto para resolver permisos, seleccionar el recurso y revisar la operación. El resultado se informa allí.")

  private fun access(message:String,action:String)=PhoneActionResult(message,accessSettingsAction=action,outcome=PhoneActionOutcome.NEEDS_INPUT)
  private fun rejected(message:String)=PhoneActionResult(message,outcome=PhoneActionOutcome.REJECTED)
  private fun handoff(intent:Intent,message:String):PhoneActionResult {
    if(intent.resolveActivity(context.packageManager)==null) return rejected("No hay una aplicación compatible para completar esa acción.")
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return PhoneActionResult(message,outcome=PhoneActionOutcome.HANDOFF)
  }
}
