package com.example.phone

/** Examples describe semantic equivalence. They are not a literal matching table. */
enum class IntentCategory {
  CLOCK, CALENDAR, CALLS, MESSAGING, APPLICATIONS, ANDROID, MEDIA,
  NAVIGATION, CONTACTS, FILES, CAMERA_RECORDING, NOTIFICATIONS, DEVICE, AUTOMATION
}

data class IntentParameter(val name: String, val type: String, val required: Boolean, val description: String)

data class LocalIntentDefinition(
  val id: String,
  val category: IntentCategory,
  val description: String,
  val examples: List<String>,
  val parameters: List<IntentParameter>,
  val permissions: List<String>,
  val mechanism: String,
  val expectedResult: String,
  val parameterContext: String? = null,
  val prompt: String = "Completá los datos del pedido.",
  val fixed: PhoneCommand? = null,
  val accepts: (PhoneCommand) -> Boolean = { it == fixed },
  val unavailable: String? = null,
  val parseParameters: ((String) -> PhoneRoute?)? = null,
  val replyPrefix: String? = null,
)

class LocalIntentRegistry(val definitions: List<LocalIntentDefinition>) {
  private val byId = definitions.associateBy { it.id }
  init {
    require(definitions.size == byId.size) { "IDs de intents duplicados" }
    definitions.forEach {
      require(Regex("[a-z][a-z0-9_]*").matches(it.id) && it.id !in fallbackIds)
      require(it.description.isNotBlank() && it.examples.size >= 2 && it.examples.all(String::isNotBlank))
      require(it.mechanism.isNotBlank() && it.expectedResult.isNotBlank())
      require(it.parameters.map { p -> p.name }.distinct().size == it.parameters.size)
      require(it.fixed != null || it.parameterContext != null || it.unavailable != null || it.parseParameters != null)
    }
  }
  val choices: Map<String, String> = byId.mapValues { (_, d) ->
    "${d.category}: ${d.description} Equivalentes: ${d.examples.joinToString(" / ")}. " +
      "Campos: ${d.parameters.joinToString { "${it.name}: ${it.type}${if (it.required) " obligatorio" else " opcional"}; ${it.description}" }}. " +
      "Alcance: ${d.expectedResult}. No descartar condiciones ni detalles adicionales."
  } + mapOf(
    "instinct" to "Consulta general, explicación, mensaje para Instinct o instrucción compleja que no es una única acción del catálogo.",
    "unsupported" to "Sin coincidencia suficientemente clara con los intents del catálogo; pedido ambiguo, citado, negado, hipotético o con condiciones no representables.",
  )

  fun resolve(id: String, text: String): PhoneRoute {
    val d = byId[id] ?: throw DecisionException("Intent ajeno al catálogo. El pedido se conserva.")
    d.unavailable?.let { return PhoneRoute.Unsupported(it) }
    val normalized=PhoneCommandParser.normalize(text)
    if (d.fixed != null && (Regex("^(?:no |nunca |explica|conta|como |que es )").containsMatchIn(normalized) ||
        Regex("\\b(?:si|cuando|durante|hasta que|despues|luego|manana|hoy)\\b").containsMatchIn(normalized))) {
      return PhoneRoute.Unsupported("El pedido incluye una negación, consulta o condición que esta acción no representa. No se ejecutó.")
    }
    val parsed = d.parseParameters?.invoke(text) ?: PhoneCommandParser().parse(text)
    return when {
      parsed is PhoneRoute.Local && d.accepts(parsed.command) -> parsed
      // An explicit restriction or conflicting local interpretation cannot be erased by a fixed choice.
      parsed is PhoneRoute.Unsupported && !parsed.recoverable -> parsed
      d.fixed != null && parsed is PhoneRoute.Local && !(parsed.command is PhoneCommand.OpenApp &&
        (d.fixed as? PhoneCommand.Platform)?.action in setOf(PlatformAction.DIALER_OPEN,PlatformAction.FILES_BROWSE,PlatformAction.ACCESSIBILITY_SETTINGS,PlatformAction.DISPLAY_SETTINGS,PlatformAction.RECORDER_OPEN,PlatformAction.CALENDAR_AGENDA,PlatformAction.ROUTINE_IMPORT)) -> PhoneRoute.Unsupported("La acción reconocida no coincide con el pedido. No se ejecutó; reformulalo.")
      d.fixed != null -> PhoneRoute.Local(d.fixed)
      parsed is PhoneRoute.Clarify -> parsed.copy(context = "intent:$id")
      else -> PhoneRoute.Clarify(d.prompt, "intent:$id")
    }
  }

  /** Null means this is a NEW request, so Decisions must classify it again. */
  fun reply(id: String, text: String, source: Pair<String, java.util.Calendar>? = null): PhoneRoute? {
    val d = byId[id] ?: return null
    val normalized = PhoneCommandParser.normalize(text)
    if (normalized in listOf("cancelar", "cancela", "no", "dejalo", "olvidalo")) return PhoneRoute.Cancel
    if(text.trimStart().startsWith("¿") || Regex("^(?:por que|para que|cuant[oa]s?|cual(?:es)?|donde|quien(?:es)?|hola$|gracias$)\\b").containsMatchIn(normalized)) return null
    if(Regex("^(?:necesito|quiero|quisiera|me gustaria|podes|puedes|podrias|sabes|decime|dime|tengo|haceme|hace|haz|dame)\\b").containsMatchIn(normalized)) return null
    if(id=="brightness_set" && PhoneCommandParser.number(normalized.removeSuffix("por ciento").removeSuffix("%").trim())==null && !Regex("^[0-9]").containsMatchIn(normalized)) return null
    // Do not inherit an intent for a new verb, query, negation or condition.
    if (Regex("^(?:no |nunca |si |cuando |instinct\\b|explica|conta|ayuda|que |como |(?:abri|abre|prende|encende|apaga|busca|llama|manda|envia|redacta|prepara|comparti|pone|crea|agenda|subi|baja|activa|desactiva|ajusta|fija|cancela|descarta)\\b)").containsMatchIn(normalized)) return null
    val parser=PhoneCommandParser()
    if(id=="alarm" && source!=null) {
      val current=java.util.Calendar.getInstance()
      if(listOf(java.util.Calendar.YEAR,java.util.Calendar.DAY_OF_YEAR).any {current.get(it)!=source.second.get(it)} || current.timeZone.id!=source.second.timeZone.id)
        return PhoneRoute.Clarify("Cambió el día o la zona horaria. Repetí el pedido completo con la fecha y hora deseadas.","intent:$id")
      if(Regex("^(?:de la (?:manana|tarde|noche)|am|pm)$").matches(normalized)) {
        val combined=parser.parse("${source.first} $text")
        return if(combined is PhoneRoute.Local && d.accepts(combined.command)) combined
          else PhoneRoute.Clarify("Repetí la alarma con día y hora completos; no se creará sin conservar esos datos.","intent:$id")
      }
      if(Regex("^(?:hoy|manana)\\b").containsMatchIn(normalized)) {
        val combined=parser.parse("alarma $text")
        if(combined is PhoneRoute.Local && d.accepts(combined.command)) return combined
      }
      val sourceWithDate=Regex("\\b(?:hoy|manana)\\b").containsMatchIn(PhoneCommandParser.normalize(source.first).replace("de la manana",""))
      if(sourceWithDate) return PhoneRoute.Clarify("Conservé que pediste una fecha. Indicá día y hora completos, por ejemplo mañana a las siete de la mañana.","intent:$id")
    }
    val route = if(d.parseParameters != null && d.replyPrefix != null) d.parseParameters.invoke("${d.replyPrefix} $text")
      else d.parameterContext?.let { parser.parseReply(it, text) }
    return when {
      route is PhoneRoute.Local && d.accepts(route.command) -> route
      route is PhoneRoute.Clarify -> route.copy(context = "intent:$id")
      else -> null
    }
  }

  companion object {
    val fallbackIds = setOf("instinct", "unsupported")
    val default: LocalIntentRegistry by lazy { LocalIntentRegistry(defaultDefinitions()) }

    private fun defaultDefinitions(): List<LocalIntentDefinition> = buildList {
      fun p(name: String, type: String = "texto", required: Boolean = true, detail: String = "valor explícito del usuario") =
        IntentParameter(name, type, required, detail)
      fun fixed(id: String, cat: IntentCategory, desc: String, first: String, second: String, command: PhoneCommand,
        mechanism: String, permissions: List<String> = emptyList(), result: String = desc) {
        add(LocalIntentDefinition(id, cat, desc, listOf(first, second), emptyList(), permissions, mechanism, result, fixed = command))
      }
      fun parsed(id: String, cat: IntentCategory, desc: String, first: String, second: String,
        context: String, prompt: String, params: List<IntentParameter>, mechanism: String,
        permissions: List<String> = emptyList(), result: String = desc, accepts: (PhoneCommand) -> Boolean) {
        add(LocalIntentDefinition(id,cat,desc,listOf(first,second),params,permissions,mechanism,result,context,prompt,accepts=accepts))
      }
      fun unavailable(id: String, cat: IntentCategory, desc: String, first: String, second: String,
        params: List<IntentParameter>, mechanism: String, permissions: List<String>, reason: String) {
        add(LocalIntentDefinition(id,cat,desc,listOf(first,second),params,permissions,mechanism,
          "Se informa la limitación local; no se cambia la acción ni se afirma éxito",unavailable=reason))
      }
      val clock = listOf("com.android.alarm.permission.SET_ALARM")
      val contacts = listOf("android.permission.READ_CONTACTS para buscar nombres")
      val media = listOf("acceso especial a sesiones multimedia")
      val policy = listOf("android.permission.ACCESS_NOTIFICATION_POLICY")
      parsed("timer",IntentCategory.CLOCK,"Crear temporizador con duración y etiqueta opcional","poné un timer de diez minutos","temporizador de diez minutos", "timer","¿Cuánto tiempo? Por ejemplo, diez minutos.",
        listOf(p("seconds","entero 1..2147483647"),p("label",required=false)),"AlarmClock.ACTION_SET_TIMER",clock,"Solicitud entregada al Reloj") { it is PhoneCommand.Timer }
      parsed("alarm",IntentCategory.CLOCK,"Crear alarma con hora, período y repetición opcional; no omitir fechas", "poné una alarma a las ocho de la mañana","despertame a las ocho de la mañana", "alarm","¿A qué hora y qué día? Indicá la hora completa.",
        listOf(p("hour","entero 0..23"),p("minute","entero 0..59"),p("days","días 1..7",false),p("date","fecha local hoy/mañana sin repetición",false),p("label",required=false)),"AlarmClock.ACTION_SET_ALARM",clock,"Solicitud entregada al Reloj; fechas especiales requieren soporte explícito") { it is PhoneCommand.Alarm }
      fixed("alarms_show",IntentCategory.CLOCK,"Consultar alarmas","mostrá mis alarmas","abrí las alarmas",PhoneCommand.ClockList(true),"AlarmClock.ACTION_SHOW_ALARMS",clock)
      fixed("timers_show",IntentCategory.CLOCK,"Consultar temporizadores","mostrá los timers","ver temporizadores",PhoneCommand.ClockList(false),"AlarmClock.ACTION_SHOW_TIMERS",clock)
      fixed("alarm_snooze",IntentCategory.CLOCK,"Posponer la alarma que está sonando con la duración predeterminada del Reloj","posponé la alarma","dame unos minutos más con la alarma",PhoneCommand.Platform(PlatformAction.ALARM_SNOOZE),"AlarmClock.ACTION_SNOOZE_ALARM",clock,"Solicitud de posposición entregada al Reloj")
      fixed("timer_dismiss",IntentCategory.CLOCK,"Descartar temporizador mediante la interfaz del Reloj; sin seleccionar otro temporizador","cancelá un temporizador","descartá el timer",PhoneCommand.Platform(PlatformAction.TIMER_DISMISS),"AlarmClock.ACTION_DISMISS_TIMER",clock,"Solicitud entregada al Reloj con interfaz visible")
      for ((id, op, verb) in listOf(Triple("start",StopwatchOp.START,"iniciar"),Triple("pause",StopwatchOp.PAUSE,"pausar"),Triple("resume",StopwatchOp.RESUME,"continuar"),Triple("reset",StopwatchOp.RESET,"reiniciar"),Triple("show",StopwatchOp.STATUS,"mostrar"))) {
        fixed("stopwatch_$id",IntentCategory.CLOCK,"$verb el cronómetro","$verb el cronómetro","quiero $verb el cronómetro",PhoneCommand.Stopwatch(op),"NativeStopwatchController y accesibilidad autorizada",listOf("accesibilidad si el Reloj no exporta la acción"))
      }
      for ((id, desc) in listOf("alarm_update" to "Modificar una alarma existente", "timer_update" to "Modificar un temporizador existente", "timer_pause" to "Pausar un temporizador", "timer_resume" to "Reanudar un temporizador")) {
        unavailable(id,IntentCategory.CLOCK,desc,desc.lowercase(),"quiero ${desc.lowercase()}",listOf(p("selector"),p("changes",required=false)),"Integración específica de Reloj; Android no ofrece CRUD universal",clock,"Reconocí el pedido, pero este Reloj no tiene una integración verificada para esa operación. Podés pedir abrir las alarmas o los temporizadores.")
      }
      parsed("calendar",IntentCategory.CALENDAR,"Crear evento con título, fecha, hora y duración","agendá reunión mañana a las 17:00","creá reunión mañana a las 17:00","calendar","Indicá título, fecha y hora del evento.",listOf(p("title"),p("beginMillis","fecha futura en zona del teléfono"),p("endMillis","fecha posterior al inicio",false,"duración predeterminada de una hora si no se indica otra")),"Intent.ACTION_INSERT CalendarContract.Events",result="Borrador entregado a Calendario para revisión y guardado") { it is PhoneCommand.CalendarEvent }
      fixed("calendar_agenda",IntentCategory.CALENDAR,"Abrir la agenda actual sin filtros de fecha adicionales","abrí mi agenda","mostrá el calendario",PhoneCommand.Platform(PlatformAction.CALENDAR_AGENDA),"Intent.ACTION_MAIN/CATEGORY_APP_CALENDAR",result="Calendario abierto; no se afirma haber leído eventos")
      for ((id,desc) in listOf("calendar_query" to "Consultar eventos en un intervalo", "calendar_update" to "Modificar un evento guardado", "calendar_delete" to "Cancelar un evento guardado")) {
        unavailable(id,IntentCategory.CALENDAR,desc,desc.lowercase(),"quiero ${desc.lowercase()}",listOf(p("eventOrRange"),p("changes",required=false)),"CalendarContract con resolución de evento y permiso",listOf("android.permission.READ_CALENDAR","android.permission.WRITE_CALENDAR para cambios"),"Reconocí el pedido de agenda, pero todavía no hay acceso integrado a tus eventos guardados. No modifiqué ningún evento. Podés pedir abrir Calendario.")
      }
      parsed("dial",IntentCategory.CALLS,"Preparar llamada a contacto o número en el marcador","llamá a Ana","marcá el número de Ana","dial","¿A quién querés llamar?",listOf(p("target")),"Intent.ACTION_DIAL y ContactsContract",contacts,"Número preparado; el usuario inicia la llamada en Teléfono") { it is PhoneCommand.Dial }
      fixed("dialer_open",IntentCategory.CALLS,"Abrir marcador sin destinatario","abrí el marcador","abrí el teclado del teléfono",PhoneCommand.Platform(PlatformAction.DIALER_OPEN),"Intent.ACTION_DIAL")
      // Direct background calling is a separate integration, never inferred from preparing a dial intent.
      unavailable("call_direct",IntentCategory.CALLS,"Iniciar llamada directamente sin intervención en el marcador","iniciá la llamada directamente","llamá sin abrir el marcador",listOf(p("target")),"Intent.ACTION_CALL / Telecom con acceso explícito",listOf("android.permission.CALL_PHONE"),"Insto prepara llamadas en el marcador; no tiene habilitado el inicio directo de llamadas. No se llamó a nadie.")
      addAll(NativeActionParameters.definitions())
      parsed("app_open",IntentCategory.APPLICATIONS,"Abrir una aplicación instalada","abrí Spotify","iniciá Spotify","app","¿Qué aplicación querés abrir?",listOf(p("name")),"PackageManager: launcher exportado",result="Aplicación abierta o selección de coincidencias") { it is PhoneCommand.OpenApp }
      parsed("youtube",IntentCategory.APPLICATIONS,"Buscar contenido en YouTube","buscá jazz en YouTube","buscá en YouTube jazz","youtube","¿Qué querés buscar en YouTube?",listOf(p("query")),"Búsqueda pública de YouTube",result="Búsqueda abierta, no reproducción garantizada") { it is PhoneCommand.YouTubeSearch }
      parsed("spotify",IntentCategory.MEDIA,"Buscar contenido en Spotify","buscá jazz en Spotify","buscá en Spotify jazz","spotify","¿Qué querés buscar en Spotify?",listOf(p("query")),"ACTION_VIEW de búsqueda pública de Spotify",result="Búsqueda abierta, no reproducción garantizada") { it is PhoneCommand.SpotifySearch }
      fixed("torch_on",IntentCategory.ANDROID,"Encender linterna ahora sin duración ni condiciones","encendé la linterna","iluminá con la linterna",PhoneCommand.Torch(true),"CameraManager.setTorchMode",listOf("android.permission.CAMERA"))
      fixed("torch_off",IntentCategory.ANDROID,"Apagar linterna ahora","apagá la linterna","dejá de iluminar con la linterna",PhoneCommand.Torch(false),"CameraManager.setTorchMode",listOf("android.permission.CAMERA"))
      fixed("volume_up",IntentCategory.ANDROID,"Subir volumen multimedia un paso","subí el volumen","más fuerte el sonido",PhoneCommand.Volume(VolumeOp.UP),"AudioManager.adjustStreamVolume",permissions=listOf("android.permission.MODIFY_AUDIO_SETTINGS"))
      fixed("volume_down",IntentCategory.ANDROID,"Bajar volumen multimedia un paso","bajá el volumen","menos fuerte el sonido",PhoneCommand.Volume(VolumeOp.DOWN),"AudioManager.adjustStreamVolume",permissions=listOf("android.permission.MODIFY_AUDIO_SETTINGS"))
      parsed("volume_set",IntentCategory.ANDROID,"Fijar porcentaje del volumen multimedia","poné el volumen al 40 por ciento","ajustá el volumen al cuarenta por ciento","volume","¿Qué porcentaje de volumen multimedia?",listOf(p("percent","entero 0..100")),"AudioManager.setStreamVolume",permissions=listOf("android.permission.MODIFY_AUDIO_SETTINGS")) { it is PhoneCommand.Volume && it.operation == VolumeOp.SET }
      for ((id,kind) in listOf("settings_wifi" to "wifi","settings_bluetooth" to "bluetooth","settings_internet" to "internet","settings_open" to "ajustes")) {
        fixed(id,if(kind=="ajustes") IntentCategory.DEVICE else IntentCategory.ANDROID,"Abrir ajustes de $kind, sin prometer cambiar el estado","abrí ajustes de $kind","mostrá configuración de $kind",PhoneCommand.Settings(kind),"Settings activity/panel",result="Panel abierto para intervención del usuario")
      }
      for ((id,desc) in listOf("wifi_on" to "Activar Wi-Fi","wifi_off" to "Desactivar Wi-Fi","bluetooth_on" to "Activar Bluetooth","bluetooth_off" to "Desactivar Bluetooth")) {
        unavailable(id,IntentCategory.ANDROID,desc,desc.lowercase(),"quiero ${desc.lowercase()}",emptyList(),"Conmutación restringida a sistema/device owner en Android moderno",emptyList(),"Android no permite a esta app cambiar ese estado directamente. No cambié la conectividad. Podés pedir abrir los ajustes correspondientes.")
      }
      val ringerPermissions = policy + "android.permission.MODIFY_AUDIO_SETTINGS"
      fixed("silent_on", IntentCategory.ANDROID, "Silenciar llamadas y notificaciones", "silenciar", "poner en silenciar",
        PhoneCommand.Platform(PlatformAction.SILENT_ON), "AudioManager.ringerMode", ringerPermissions)
      fixed("vibrate_on", IntentCategory.ANDROID, "Activar el modo vibración", "modo vibrar", "poner en vibrar",
        PhoneCommand.Platform(PlatformAction.VIBRATE_ON), "AudioManager.ringerMode", ringerPermissions)
      for ((id,action,desc) in listOf(Triple("silent_off",PlatformAction.SILENT_OFF,"Desactivar silencio de llamadas"),Triple("dnd_on",PlatformAction.DND_ON,"Activar No molestar"),Triple("dnd_off",PlatformAction.DND_OFF,"Desactivar No molestar"))) {
        fixed(id,IntentCategory.ANDROID,desc,desc.lowercase(),"quiero ${desc.lowercase()}",PhoneCommand.Platform(action),"AudioManager / NotificationManager con acceso de política",if(action == PlatformAction.SILENT_OFF) ringerPermissions else policy)
      }
      for ((id,op,desc) in listOf(Triple("play",MediaOp.PLAY,"Reanudar reproducción"),Triple("pause",MediaOp.PAUSE,"Pausar reproducción"),Triple("next",MediaOp.NEXT,"Siguiente tema"),Triple("previous",MediaOp.PREVIOUS,"Tema anterior"),Triple("status",MediaOp.STATUS,"Consultar qué está sonando"))) {
        fixed("media_$id",IntentCategory.MEDIA,desc,desc.lowercase(),"quiero ${desc.lowercase()}",PhoneCommand.Media(op),"MediaSession.TransportControls",media,"Acción o estado de una sesión compatible; elegir si hay varias")
      }
      fixed("media_stop", IntentCategory.MEDIA, "Parar la reproducción actual", "parar la música", "detener la reproducción",
        PhoneCommand.Media(MediaOp.STOP), "MediaSession.TransportControls", media, "Se envía la orden de parar o pausar a la sesión elegida")
      for (app in MediaApp.entries) {
        for ((verb, operation) in listOf("pausar" to MediaOp.PAUSE, "parar" to MediaOp.STOP)) {
          val id = "media_${operation.name.lowercase()}_${app.name.lowercase()}"
          fixed(id, IntentCategory.MEDIA, "${verb.replaceFirstChar(Char::uppercaseChar)} la reproducción de ${app.label}",
            "$verb ${app.label}", "$verb la reproducción en ${app.label}", PhoneCommand.Media(operation, app.packageName),
            "MediaSession.TransportControls", media, "Se envía el control a la sesión de ${app.label}")
        }
      }
      parsed("music", IntentCategory.MEDIA, "Solicitar la reproducción por título en una aplicación compatible",
        "reproducir De música ligera en Spotify", "reproducir De música ligera en YouTube", "music", "¿Qué querés escuchar?",
        listOf(p("query"), p("app", "SPOTIFY/YOUTUBE", false)), "MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH o MediaSession",
        result = "Se solicita reproducción y, si la app elegida no admite el pedido, se abre su búsqueda") { it is PhoneCommand.MusicSearch }
      parsed("navigate",IntentCategory.NAVIGATION,"Iniciar ruta al destino con modo de viaje admitido","llevame a Córdoba","navegá a Córdoba","navigate","¿A dónde querés ir?",listOf(p("destination"),p("mode","DRIVING/WALKING/BICYCLING/TRANSIT",false)),"Maps directions",result="Ruta entregada a Maps") { it is PhoneCommand.Navigate }
      parsed("maps",IntentCategory.NAVIGATION,"Buscar un lugar en el mapa","buscá en Maps farmacias","buscá farmacias cerca de mí","maps","¿Qué lugar querés buscar?",listOf(p("query")),"Maps search") { it is PhoneCommand.MapSearch }
      parsed("place_save",IntentCategory.NAVIGATION,"Guardar dirección de casa o trabajo","guardá mi casa en Avenida Colón 123","mi casa es Avenida Colón 123","place","Indicá casa o trabajo y la dirección completa.",listOf(p("alias","casa/trabajo"),p("address")),"SavedPlacesRepository") { it is PhoneCommand.SavePlace }
      fixed("camera_photo",IntentCategory.CAMERA_RECORDING,"Abrir cámara en modo foto sin disparar","abrí la cámara","abrí la cámara de fotos",PhoneCommand.Camera(),"MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA")
      fixed("camera_video",IntentCategory.CAMERA_RECORDING,"Abrir cámara en modo video sin iniciar grabación","abrí cámara de video","abrí la cámara en modo video",PhoneCommand.Camera(true),"MediaStore.INTENT_ACTION_VIDEO_CAMERA")
      fixed("recorder_open",IntentCategory.CAMERA_RECORDING,"Abrir grabadora para iniciar grabación con intervención del usuario","abrí la grabadora","prepará una grabación de audio",PhoneCommand.Platform(PlatformAction.RECORDER_OPEN),"MediaStore.Audio.Media.RECORD_SOUND_ACTION",result="Grabadora abierta; no se afirma grabación iniciada")
      for ((id,desc) in listOf("camera_capture" to "Capturar una foto directamente", "camera_record" to "Iniciar una grabación de video", "recorder_start" to "Iniciar grabación de audio directamente", "recorder_stop" to "Detener una grabación de audio")) {
        unavailable(id,IntentCategory.CAMERA_RECORDING,desc,desc.lowercase(),"quiero ${desc.lowercase()}",emptyList(),"Sesión de captura y URI de salida controlada por Insto",listOf("CAMERA/RECORD_AUDIO según captura"),"Reconocí la captura, pero Insto no controla una sesión de grabación para esa acción. No inicié ni detuve ninguna captura. Podés pedir abrir cámara o grabadora.")
      }
      fixed("battery",IntentCategory.DEVICE,"Consultar porcentaje de batería","cuánta batería queda","decime el nivel de batería",PhoneCommand.Platform(PlatformAction.BATTERY),"BatteryManager.BATTERY_PROPERTY_CAPACITY",result="Porcentaje real de batería o error si no disponible")
      fixed("accessibility_settings",IntentCategory.DEVICE,"Abrir ajustes de accesibilidad","abrí accesibilidad","mostrá configuración de accesibilidad",PhoneCommand.Platform(PlatformAction.ACCESSIBILITY_SETTINGS),"Settings.ACTION_ACCESSIBILITY_SETTINGS")
      fixed("display_settings",IntentCategory.DEVICE,"Abrir ajustes de pantalla","abrí ajustes de pantalla","mostrá configuración de pantalla",PhoneCommand.Platform(PlatformAction.DISPLAY_SETTINGS),"Settings.ACTION_DISPLAY_SETTINGS")
      unavailable("shortcut_run",IntentCategory.DEVICE,"Ejecutar un acceso directo previamente integrado","ejecutá mi acceso directo","activá mi atajo",listOf(p("shortcutId")),"ShortcutManager/launcher autorizado o integración exportada",emptyList(),"Ese acceso directo necesita una integración registrada. No ejecuté otro componente.")
      parsed("temporary_mode",IntentCategory.AUTOMATION,"Aplicar modo temporal con duración explícita y restauración","silenciá el teléfono durante una hora","poné no molestar durante una hora","temporary_mode","Indicá el modo y su duración.",listOf(p("kind","MEDIA_VOLUME/SILENT/DND"),p("seconds","entero 1..86400"),p("percent","entero 0..100",false)),"TemporaryModeManager y restauración con propiedad de ajustes",policy+listOf("android.permission.MODIFY_AUDIO_SETTINGS para volumen/silencio","android.permission.POST_NOTIFICATIONS en Android 13+"),"Modo aplicado solo con accesos y programación disponibles") { it is PhoneCommand.TemporaryMode }
      fixed("temporary_mode_end",IntentCategory.AUTOMATION,"Terminar el modo temporal y restaurar ahora","terminá el modo y restaurá ahora","restaurá el volumen",PhoneCommand.EndMode,"TemporaryModeManager.end",result="Restauración del estado guardado si existe")
      unavailable("routine_run",IntentCategory.AUTOMATION,"Ejecutar rutina local previamente configurada","ejecutá mi rutina estudio","activá la rutina estudio",listOf(p("routineId")),"Adaptador de rutinas registradas y WorkflowRunner",emptyList(),"No hay un proveedor de rutinas configuradas registrado en esta instalación. No convertí el pedido en una secuencia nueva.")
    }.let {base ->
      val native=ExtendedLocalIntents.definitions()
      val replaced=native.map {it.id}.toSet()
      base.filterNot {it.id in replaced}+native
    }
  }
}
