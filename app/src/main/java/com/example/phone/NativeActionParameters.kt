package com.example.phone

/** Local command patterns that extract fields from the original request. */
internal object NativeActionParameters {
  fun definitions(): List<LocalIntentDefinition> = buildList {
    fun p(name: String, type: String = "texto", required: Boolean = true) = IntentParameter(name,type,required,"dato explícito; sin completar valores ausentes")
    fun action(id: String, category: IntentCategory, desc: String, examples: List<String>, op: PlatformAction,
      parameters: List<IntentParameter> = emptyList(), permissions: List<String> = emptyList(), mechanism: String,
      expected: String, pattern: String? = null, prompt: String = "Completá los datos del pedido.",
      localSyntax: Regex? = null,
      decisionDescription: String = desc,
      builder: ((FoldedRequest,MatchResult) -> PhoneCommand.Platform?)? = null) {
      val fixed = if(parameters.isEmpty()) PhoneCommand.Platform(op) else null
      val grammar = pattern?.let { Regex("^$it[.!?]*$") }
      add(LocalIntentDefinition(id,category,desc,examples,parameters,permissions,mechanism,expected,
        prompt=prompt,fixed=fixed,decisionDescription=decisionDescription,accepts={it is PhoneCommand.Platform && it.action==op},localSyntax=localSyntax,
        replyPrefix=when(op) {
          PlatformAction.ALARM_DISMISS -> "cancelá la alarma llamada"
          PlatformAction.BRIGHTNESS -> "poné el brillo al"
          PlatformAction.CONTACT_SEARCH -> "buscá el contacto"
          PlatformAction.CONTACT_PHONES -> "qué teléfono tiene"
          PlatformAction.APP_SETTINGS -> "abrí ajustes de"
          PlatformAction.NOTIFICATION_OPEN -> "abrí la notificación"
          PlatformAction.NOTIFICATION_DISMISS -> "descartá la notificación"
          else -> null
        },
        parseParameters=if(grammar==null) null else { request ->
          grammar.matchEntire(request.text)?.let { match ->
            builder?.invoke(request, match)?.let { PhoneRoute.Local(it) }
              ?: PhoneRoute.Clarify(prompt, "intent:$id")
          }
        }))
    }
    action("alarm_dismiss",IntentCategory.CLOCK,"Descartar alarma por etiqueta explícita sin elegir otra por defecto",listOf("cancelá la alarma llamada Trabajo","descartá la alarma con nombre Trabajo"),PlatformAction.ALARM_DISMISS,
      listOf(p("label")),listOf("com.android.alarm.permission.SET_ALARM"),"AlarmClock.ACTION_DISMISS_ALARM con búsqueda por etiqueta e interfaz visible",
      "Solicitud para la etiqueta exacta entregada al Reloj; revisar allí la selección", "(?:cancela|descarta|elimina|borra) (?:la )?(?:alarma|despertador) (?:llamad[ao]|con nombre|etiquetad[ao] como) (.+?)", "¿Qué nombre tiene la alarma? Indicá: cancelá la alarma llamada Trabajo.",
        decisionDescription = "Cancelar o descartar una alarma existente por su etiqueta, sin posponerla.") { r,m ->
      r.capture(m,1).removeSurrounding("\"").takeIf(::singleTarget)?.let {PhoneCommand.Platform(PlatformAction.ALARM_DISMISS,it)}
    }
    action("brightness_set",IntentCategory.ANDROID,"Fijar porcentaje del brillo de pantalla",listOf("poné el brillo al cuarenta por ciento","ajustá el brillo al 40%"),PlatformAction.BRIGHTNESS,
      listOf(p("percent","entero 0..100")),listOf("android.permission.WRITE_SETTINGS (acceso especial)"),"Settings.System.SCREEN_BRIGHTNESS",
      "Brillo manual aplicado con acceso especial", "(?:(?:pone|ajusta|fija|baja|subi|reduce|aumenta) (?:el )?)?brillo(?: de (?:la )?pantalla)? (?:al? )?(.*?)(?: por ciento|%)?", "¿Qué porcentaje de brillo entre cero y cien?",
        decisionDescription = "Fijar el brillo de pantalla en el porcentaje indicado, sin encender la linterna.") { _,m ->
      PhoneCommandParser.number(m.groupValues[1])?.takeIf {it in 0..100}?.let {PhoneCommand.Platform(PlatformAction.BRIGHTNESS,percent=it)}
    }
    action("contact_search",IntentCategory.CONTACTS,"Buscar contacto por nombre",listOf("buscá el contacto José Pérez","encontrá el contacto José Pérez"),PlatformAction.CONTACT_SEARCH,
      listOf(p("name")),mechanism="ContactsContract / Intent.ACTION_SEARCH",expected="Búsqueda entregada a Contactos",
      pattern="(?:busca|encontra|mostra) (?:(?:el |un |mi )?contacto(?: de)?|a) (.+?)",
      prompt="¿Qué contacto querés buscar?", localSyntax=Regex("^(?:busca|encontra|mostra) (?:el |un |mi )?contacto\\b"),
        decisionDescription = "Buscar un contacto por nombre, sin pedir sus números ni iniciar una llamada.") { r,m ->
      r.capture(m,1).takeIf(::singleTarget)?.let {PhoneCommand.Platform(PlatformAction.CONTACT_SEARCH,it)}
    }
    action("contact_phones",IntentCategory.CONTACTS,"Consultar teléfonos de un contacto",listOf("qué teléfono tiene José Pérez","decime el teléfono de José Pérez"),PlatformAction.CONTACT_PHONES,
      listOf(p("name")),listOf("android.permission.READ_CONTACTS"),"ContactsContract.CommonDataKinds.Phone", "Solo números realmente encontrados; selección si hay homónimos",
      "(?:que (?:telefono|numero) tiene|(?:decime|consulta|mostra) (?:el )?(?:telefono|numero) de|cual es el (?:telefono|numero) de) (.+?)", "¿De qué contacto querés consultar el teléfono?",
        decisionDescription = "Consultar los números guardados de un contacto, sin iniciar una llamada.") { r,m ->
      r.capture(m,1).takeIf(::singleTarget)?.let {PhoneCommand.Platform(PlatformAction.CONTACT_PHONES,it)}
    }
    action("app_settings",IntentCategory.APPLICATIONS,"Abrir configuración de una aplicación",listOf("abrí ajustes de Spotify","abrí configuración de Spotify"),PlatformAction.APP_SETTINGS,
      listOf(p("app")),mechanism="Settings.ACTION_APPLICATION_DETAILS_SETTINGS",expected="Ajustes de la aplicación elegida abiertos",
      pattern="(?:(?:abri|mostra) (?:los |la )?)?(?:ajustes|configuracion) de (?:(?:la )?(?:app|aplicacion) )?(.+?)",prompt="¿De qué aplicación querés abrir los ajustes?",
        decisionDescription = "Abrir los ajustes de una aplicación concreta, en vez de abrir la aplicación.") { r,m ->
      r.capture(m,1).takeIf(::singleTarget)?.let {PhoneCommand.Platform(PlatformAction.APP_SETTINGS,it)}
    }
    action("text_share",IntentCategory.APPLICATIONS,"Compartir texto explícito con una aplicación elegida por el usuario",listOf("compartí el texto Estoy llegando","compartir texto Estoy llegando"),PlatformAction.TEXT_SHARE,
      listOf(p("text")),mechanism="Intent.ACTION_SEND text/plain con chooser",expected="Selector de aplicación abierto; contenido entregado, no envío confirmado",
      pattern="comparti (?:el |este )?texto[: ]\\s*(.+)",prompt="¿Qué texto querés compartir?",
        decisionDescription = "Compartir un texto con la aplicación que el usuario elija, sin fijar destinatario.") { r,m ->
      PhoneCommand.Platform(PlatformAction.TEXT_SHARE,content=r.capture(m,1))
    }
    action("files_browse",IntentCategory.FILES,"Abrir el administrador de archivos sin operar sobre un archivo por defecto",listOf("abrí mis archivos","abrí el administrador de archivos"),PlatformAction.FILES_BROWSE,
      mechanism="DocumentsContract root con Intent.ACTION_VIEW",expected="Administrador de archivos abierto para intervención del usuario",
        decisionDescription = "Abrir el administrador de archivos sin elegir ni operar sobre un archivo concreto.")
    action("notification_list",IntentCategory.NOTIFICATIONS,"Consultar notificaciones activas con acceso explícito",listOf("mostrá mis notificaciones","qué notificaciones tengo"),PlatformAction.NOTIFICATION_LIST,
      permissions=listOf("acceso especial a notificaciones de Insto (separado del acceso multimedia)"),mechanism="NotificationListenerService.getActiveNotifications",expected="Lista real de notificaciones activas; sin enviar su contenido a Gemini",
        decisionDescription = "Consultar las notificaciones activas, sin abrir ni descartar una notificación concreta.")
    for ((id,op,verb,result) in listOf(
      listOf("notification_open",PlatformAction.NOTIFICATION_OPEN,"abri","Se ejecuta el PendingIntent de una notificación inequívoca"),
      listOf("notification_dismiss",PlatformAction.NOTIFICATION_DISMISS,"descarta","Se descarta solamente la notificación elegida si es cancelable"))) {
      val actionOp=op as PlatformAction
      action(id as String,IntentCategory.NOTIFICATIONS,"${if(actionOp==PlatformAction.NOTIFICATION_OPEN) "Abrir" else "Descartar"} una notificación identificada por título",
        listOf("${if(verb=="abri") "abrí" else "descartá"} la notificación Reunión","quiero ${if(verb=="abri") "abrir" else "descartar"} la notificación Reunión"),actionOp,
        listOf(p("title")),listOf("acceso especial a notificaciones de Insto"),"NotificationListenerService y PendingIntent original",result as String,
        "(?:${if(verb=="abri") "abri|mostra|ver" else "descarta|borra|elimina|quita"}) (?:la )?notificacion (?:llamada )?(.+?)","Indicá el título de la notificación.") { r,m ->
        r.capture(m,1).takeIf(::singleTarget)?.let {PhoneCommand.Platform(actionOp,it)}
      }
    }
    fun unavailable(id:String,cat:IntentCategory,desc:String,params:List<IntentParameter>,permissions:List<String>,mechanism:String,reason:String) {
      add(LocalIntentDefinition(id,cat,desc,listOf(desc.lowercase(),"quiero ${desc.lowercase()}"),params,permissions,mechanism,"Se explica acceso/integración faltante, sin efecto parcial",unavailable=reason))
    }
    unavailable("message_send",IntentCategory.MESSAGING,"Enviar mensaje mediante una integración configurada",listOf(p("integration"),p("recipient"),p("body")),listOf("autorización específica de la integración"),"Proveedor de mensajería registrado","Los mensajes se envían por WhatsApp. No envié ningún mensaje. Probá: WhatsApp a Ana que diga Hola.")
    for ((id,desc) in listOf("file_open" to "Abrir un archivo identificado", "file_search" to "Buscar un archivo por nombre", "file_share" to "Compartir un archivo identificado", "file_rename" to "Renombrar un archivo", "file_copy" to "Copiar un archivo", "file_move" to "Mover un archivo", "file_delete" to "Eliminar un archivo")) {
      unavailable(id,IntentCategory.FILES,desc,listOf(p("document"),p("destination",required=false)),listOf("concesión de URI del Storage Access Framework"),"DocumentsContract y permiso de documento seleccionado","Para operar sobre ese archivo necesito una selección y un permiso de documento que esta instalación no tiene integrados. No modifiqué archivos. Podés pedir abrir el administrador de archivos.")
    }
  }
  private fun singleTarget(text:String):Boolean = text.isNotBlank() && text.length <= 2000 &&
    !Regex("\\b(?:y (?:llama|llamala|llamalo|manda|envia|abri|borra|elimina)|despues|luego|si|cuando)\\b").containsMatchIn(PhoneCommandParser.normalize(text))
}
