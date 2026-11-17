package com.example.phone

import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

/** Public APIs that require Android selection/permission UI, rather than an open-ended model. */
internal object ExtendedLocalIntents {
  fun definitions():List<LocalIntentDefinition> = buildList {
    fun parameter(name:String,required:Boolean=true)=IntentParameter(name,"texto",required,"valor literal o selección explícita en Android")
    fun parsed(id:String,category:IntentCategory,description:String,examples:List<String>,op:PlatformAction,
      parameters:List<IntentParameter>,permissions:List<String>,mechanism:String,result:String,
      prompt:String,extract:(FoldedRequest)->PhoneCommand.Platform?) {
      add(LocalIntentDefinition(id,category,description,examples,parameters,permissions,mechanism,result,prompt=prompt,
        accepts={it is PhoneCommand.Platform && it.action==op},replyPrefix=when(op) {
          PlatformAction.ROUTINE_RUN -> "ejecutá la rutina"
          PlatformAction.CALL_DIRECT -> "llamá directamente al"
          PlatformAction.CALENDAR_QUERY -> "consultá mi agenda"
          PlatformAction.MESSAGE_SEND -> "mandá por WhatsApp a"
          else -> null
        },parseParameters={text ->
          extract(FoldedRequest.of(text))?.let {PhoneRoute.Local(it)} ?: PhoneRoute.Clarify(prompt,"intent:$id")
        }))
    }
    parsed("call_direct",IntentCategory.CALLS,"Iniciar llamada directa a un número explícito, con permiso y confirmación",listOf("llamá directamente al +541112345678","iniciá una llamada al +541112345678"),PlatformAction.CALL_DIRECT,
      listOf(parameter("number")),listOf("android.permission.CALL_PHONE"),"Intent.ACTION_CALL tras confirmación local","Android inicia la llamada solicitada; no se afirma conexión",
      "Indicá un número completo: llamá directamente al +541112345678.") {r ->
      Regex("^(?:llama directamente (?:al?|a)|inicia una llamada (?:al?|a)) ([+0-9 ()-]+)[.!?]*$").matchEntire(r.text)?.let {m ->
        PhoneNumbers.canonical(r.capture(m,1))?.let {PhoneCommand.Platform(PlatformAction.CALL_DIRECT,it)}
      }
    }
    parsed("message_send",IntentCategory.MESSAGING,"Enviar texto por la integración de WhatsApp a un número explícito, tras revisar destinatario y contenido",listOf("mandá por WhatsApp a +541112345678 que diga Estoy llegando","enviá por WhatsApp a +541112345678 que diga Hola José"),PlatformAction.MESSAGE_SEND,
      listOf(parameter("number"),parameter("body")),listOf("WhatsApp instalado y accesibilidad conectada/autorizada"),"WhatsAppDispatcher con guardas de destinatario y texto existentes",
      "Solicitud de envío entregada a WhatsApp; la automatización verifica destinatario y cuerpo. Sin otro destinatario por defecto.",
      "Indicá número y texto: mandá por WhatsApp a +541112345678 que diga Estoy llegando.") {r ->
      Regex("^(?:manda|envia) por whatsapp a ([+0-9 ()-]+) que diga (.+)$").matchEntire(r.text)?.let {m ->
        PhoneNumbers.canonical(r.capture(m,1))?.takeIf {it.count(Char::isDigit)>=7}?.let {PhoneCommand.Platform(PlatformAction.MESSAGE_SEND,it,content=r.capture(m,2))}
      }
    }
    for((id,op,verb) in listOf(
      Triple("file_open",PlatformAction.FILE_OPEN,"abri|abre|abrir"),Triple("file_search",PlatformAction.FILE_SEARCH,"busca|buscar"),
      Triple("file_share",PlatformAction.FILE_SHARE,"comparti|compartir"),Triple("file_delete",PlatformAction.FILE_DELETE,"borra|elimina|eliminar"),
      Triple("file_copy",PlatformAction.FILE_COPY,"copia|copiar"),Triple("file_move",PlatformAction.FILE_MOVE,"move|mover"))) {
      val label=when(op) {PlatformAction.FILE_OPEN->"Abrir";PlatformAction.FILE_SEARCH->"Buscar";PlatformAction.FILE_SHARE->"Compartir";PlatformAction.FILE_DELETE->"Eliminar";PlatformAction.FILE_COPY->"Copiar";else->"Mover"}
      parsed(id,IntentCategory.FILES,"$label un archivo con selección y permisos de Android",listOf("${label.lowercase()} el archivo informe.pdf","${label.lowercase()} un archivo"),op,
        listOf(parameter("name",false)),listOf("concesión de URI del Storage Access Framework"),"ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract",
        "Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.","Indicá solo el archivo o pedí elegirlo en el selector de Android.") { r ->
        Regex("^(?:$verb) (?:el |un )?archivo(?: (.+?))?[.!?]*$").matchEntire(r.text)?.let {m ->
          val name=r.capture(m,1).trim('"').takeIf {it.isNotBlank()}
          if(name!=null && !literalTarget(name)) null else PhoneCommand.Platform(op,name)
        }
      }
    }
    parsed("file_rename",IntentCategory.FILES,"Renombrar un archivo con nombres exactos y selección Android",listOf("renombrá el archivo \"informe.pdf\" a \"final.pdf\"","renombrar archivo \"foto.jpg\" a \"viaje.jpg\""),PlatformAction.FILE_RENAME,
      listOf(parameter("name"),parameter("newName")),listOf("concesión de URI de escritura"),"ACTION_OPEN_DOCUMENT y DocumentsContract.renameDocument","Se verifica el archivo y se confirma el nombre antes de renombrar",
      "Indicá nombres entre comillas: renombrá el archivo \"informe.pdf\" a \"final.pdf\".") {r ->
      Regex("^(?:renombra|renombrar) (?:el )?archivo \"(.+?)\" a \"([^/\\\\]+)\"[.!?]*$").matchEntire(r.text)?.let {m ->
        PhoneCommand.Platform(PlatformAction.FILE_RENAME,r.capture(m,1),content=r.capture(m,2))
      }
    }
    parsed("calendar_query",IntentCategory.CALENDAR,"Consultar eventos de un día exacto de la agenda",listOf("qué tengo mañana","consultá mi agenda hoy"),PlatformAction.CALENDAR_QUERY,
      listOf(parameter("day")),listOf("android.permission.READ_CALENDAR"),"CalendarContract.Instances con intervalo de día local","Eventos reales del día solicitado, solo con permiso",
      "¿Qué día? Podés decir: consultá mi agenda mañana o una fecha dd/mm/aaaa.") {r ->
      Regex("^(?:que tengo|consulta (?:mi |la )?agenda|mostra (?:mi |la )?agenda) (hoy|manana|(?:el )?[0-9]{1,2}/[0-9]{1,2}/[0-9]{4})[.!?]*$").matchEntire(r.text)?.let {m ->
        calendarDay(m.groupValues[1])?.let {PhoneCommand.Platform(PlatformAction.CALENDAR_QUERY,it)}
      }
    }
    parsed("calendar_delete",IntentCategory.CALENDAR,"Cancelar un evento guardado con título exacto, selección y confirmación",listOf("cancelá el evento \"Reunión\"","eliminá el evento \"Reunión\""),PlatformAction.CALENDAR_DELETE,
      listOf(parameter("title")),listOf("android.permission.READ_CALENDAR","android.permission.WRITE_CALENDAR"),"CalendarContract.Events y selección explícita; recurrencias se revisan en Calendario",
      "Solo se elimina el evento elegido y confirmado; nunca una serie recurrente por deducción",
      "Indicá el título exacto entre comillas: cancelá el evento \"Reunión\".") {r ->
      Regex("^(?:cancela|elimina|borra) (?:el )?evento \"(.+?)\"[.!?]*$").matchEntire(r.text)?.let {m ->PhoneCommand.Platform(PlatformAction.CALENDAR_DELETE,r.capture(m,1))}
    }
    parsed("calendar_update",IntentCategory.CALENDAR,"Modificar un evento elegido, o moverlo a fecha y hora completas",listOf("editá el evento \"Reunión\"","mové el evento \"Reunión\" a mañana a las 17:00"),PlatformAction.CALENDAR_UPDATE,
      listOf(parameter("title"),parameter("newTime",false)),listOf("android.permission.READ_CALENDAR"),"CalendarContract.Events para selección e Intent.ACTION_EDIT con cambios tipados",
      "Editor de Calendario con evento exacto; conserva duración si no se indicó otra, requiere guardado en Calendario",
      "Indicá: editá el evento \"Reunión\", o mové el evento \"Reunión\" a mañana a las 17:00.") {r ->
      val edit=Regex("^(?:edita|modifica) (?:el )?evento \"(.+?)\"[.!?]*$").matchEntire(r.text)
      if(edit!=null) PhoneCommand.Platform(PlatformAction.CALENDAR_UPDATE,r.capture(edit,1)) else {
        val move=Regex("^(?:move|reprograma) (?:el )?evento \"(.+?)\" (?:a|para) (hoy|manana|[0-9/]+) a las? (.+)$").matchEntire(r.text)
        move?.let {m ->
          val event=(PhoneCommandParser().parse("agendá evento ${m.groupValues[2]} a las ${m.groupValues[3]}") as? PhoneRoute.Local)?.command as? PhoneCommand.CalendarEvent
          event?.let {
            val changes=JSONObject().put("beginMillis",it.beginMillis)
            if(m.groupValues[3].contains(" durante ")) changes.put("durationSeconds",(it.endMillis-it.beginMillis)/1000)
            PhoneCommand.Platform(PlatformAction.CALENDAR_UPDATE,r.capture(m,1),content=changes.toString())
          }
        }
      }
    }
    parsed("routine_run",IntentCategory.AUTOMATION,"Ejecutar una rutina local ya configurada",listOf("ejecutá la rutina Estudio","activá mi rutina Estudio"),PlatformAction.ROUTINE_RUN,
      listOf(parameter("name")),emptyList(),"ConfiguredRoutineStore y WorkflowRunner","Se revisan los pasos guardados antes de ejecutar, sin crear pasos a partir del pedido",
      "¿Qué rutina configurada querés ejecutar? Indicá su nombre.") {r ->
      Regex("^(?:ejecuta|activa|inicia) (?:la |mi )?rutina (.+?)[.!?]*$").matchEntire(r.text)?.let {m ->
        r.capture(m,1).takeIf(::literalTarget)?.let {PhoneCommand.Platform(PlatformAction.ROUTINE_RUN,it)}
      }
    }
    for((id,op,desc) in listOf(Triple("camera_capture",PlatformAction.CAPTURE_PHOTO,"Capturar una foto"),Triple("camera_record",PlatformAction.CAPTURE_VIDEO,"Capturar un video"),Triple("recorder_start",PlatformAction.CAPTURE_AUDIO,"Capturar una grabación de audio"))) {
      add(LocalIntentDefinition(id,IntentCategory.CAMERA_RECORDING,desc+" mediante la aplicación nativa",listOf(desc.lowercase(),"quiero ${desc.lowercase()}"),emptyList(),
        if(op==PlatformAction.CAPTURE_AUDIO) emptyList() else listOf("android.permission.CAMERA"),
        "ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE / RECORD_SOUND_ACTION con resultado y URI autorizada",
        "Se abre la interfaz nativa para capturar; solo se informa archivo guardado cuando la app devuelve un resultado válido. Sin filtros ni ajustes adicionales.",fixed=PhoneCommand.Platform(op)))
    }
    for((id,op,desc,examples) in listOf(
      listOf("routine_list",PlatformAction.ROUTINE_LIST,"Listar rutinas locales configuradas",listOf("mostrá mis rutinas","qué rutinas tengo")),
      listOf("routine_import",PlatformAction.ROUTINE_IMPORT,"Importar plantillas de rutinas revisadas desde JSON",listOf("importá rutinas","configurá rutinas desde un archivo")))) {
      @Suppress("UNCHECKED_CAST")
      add(LocalIntentDefinition(id as String,IntentCategory.AUTOMATION,desc as String,examples as List<String>,emptyList(),emptyList(),
        "ConfiguredRoutineStore con selección SAF e importación confirmada","Listar no ejecuta; importar reemplaza solo después de revisar las plantillas",fixed=PhoneCommand.Platform(op as PlatformAction)))
    }
  }
  private fun literalTarget(text:String)=text.isNotBlank() && text.length<=2000 && !Regex("\\b(?:y (?:abri|llama|manda|elimina|borra)|si|cuando|despues|luego)\\b").containsMatchIn(PhoneCommandParser.normalize(text))
  private fun calendarDay(text:String):String? = runCatching {
    val day=Calendar.getInstance()
    when(text) {"hoy"->Unit;"manana"->day.add(Calendar.DAY_OF_MONTH,1);else -> {
      val parts=text.removePrefix("el ").split('/').map(String::toInt)
      day.clear();day.isLenient=false;day.set(parts[2],parts[1]-1,parts[0]);day.timeInMillis
    }}
    "%04d-%02d-%02d".format(Locale.ROOT,day.get(Calendar.YEAR),day.get(Calendar.MONTH)+1,day.get(Calendar.DAY_OF_MONTH))
  }.getOrNull()
}
