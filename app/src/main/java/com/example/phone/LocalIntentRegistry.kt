package com.example.phone

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
  val parseParameters: ((FoldedRequest) -> PhoneRoute?)? = null,
  val replyPrefix: String? = null,
  val localSyntax: Regex? = null,
  val decisionDescription: String = description,
  val decisionParameters: List<String> = parameters.filter { it.required }.map { it.name },
)

class LocalIntentRegistry(val definitions: List<LocalIntentDefinition>) {
  private val byId = definitions.associateBy { it.id }
  private val parameterDefinitions = definitions.filter { it.parseParameters != null }
  private val wifiSpelling = Regex("\\bwi[ -]fi\\b")
  private val phrases = buildMap {
    for (definition in definitions) {
      if (definition.fixed == null && definition.unavailable == null) continue
      for (phrase in definition.examples + LocalIntentPhrases.alternatives[definition.id].orEmpty()) {
        val syntax = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(phrase)).replace(wifiSpelling, "wifi")
        val previous = put(syntax, definition)
        require(previous == null || previous.id == definition.id ||
          previous.fixed != null && previous.fixed == definition.fixed) { "Variante ambigua: $phrase" }
      }
    }
  }
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
  val choices: Map<String, String> = byId.mapValues { (_, definition) ->
    val required = definition.decisionParameters.joinToString()
    if (required.isEmpty()) definition.decisionDescription else "${definition.decisionDescription} Obligatorios: $required."
  } + mapOf(
    "instinct" to "Consultas generales, explicaciones, redacción o investigación sin una operación del teléfono.",
    "unsupported" to "Pedido ambiguo, fuera del catálogo o con condiciones y detalles que la acción no puede representar.",
  )

  internal fun verificationCriterion(id: String): String? = byId[id]?.let { definition ->
    val examples = (definition.examples + LocalIntentPhrases.alternatives[id].orEmpty().take(2)).distinct()
    val fields = definition.parameters.joinToString { parameter ->
      "${parameter.name}: ${parameter.type}${if (parameter.required) " obligatorio" else " opcional"}; ${parameter.description}"
    }
    "${definition.category}: ${definition.description} Equivalentes: ${examples.joinToString(" / ")}. " +
      "Campos: $fields. Alcance: ${definition.expectedResult}. No descartar condiciones ni detalles adicionales."
  }

  internal fun matchPhrase(syntax: String): PhoneRoute? {
    val definition = phrases[syntax] ?: phrases[syntax.replace(wifiSpelling, "wifi")] ?: return null
    return definition.fixed?.let { PhoneRoute.Local(it) }
      ?: definition.unavailable?.let { PhoneRoute.Unsupported(it) }
  }

  internal fun matchParameters(request: FoldedRequest): PhoneRoute? =
    parameterDefinitions.firstNotNullOfOrNull { definition ->
      if (definition.localSyntax?.containsMatchIn(request.text) == false) null
      else definition.parseParameters?.invoke(request)
    }

  fun resolve(id: String, text: String): PhoneRoute {
    val d = byId[id] ?: throw DecisionException("Intent ajeno al catálogo. El pedido se conserva.")
    d.unavailable?.let { return PhoneRoute.Unsupported(it) }
    RequestSafetyPolicy.rejection(text)?.let { return PhoneRoute.Unsupported(it) }
    val request = FoldedRequest.of(text)
    val syntax = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(text))
    if (matchPhrase(syntax) == null && RequestSafetyPolicy.hasJoinedActions(request.text))
      return PhoneRoute.Unsupported("Pedime una acción por vez para interpretar todos sus detalles.")
    val parsed = if (d.parseParameters != null) d.parseParameters.invoke(request.withCommandHead())
      else PhoneCommandParser().parse(text)
    if (d.fixed != null && (RequestSafetyPolicy.fixedActionHasExtraParameters(syntax) ||
        Regex("\\b(?:despues|luego|manana|hoy)\\b").containsMatchIn(syntax) ||
        (parsed !is PhoneRoute.Local && (text.trimStart().firstOrNull() in listOf('¿', '"', '“', '«') || RequestSafetyPolicy.isQuestion(syntax) ||
          RequestSafetyPolicy.isExplicitQuery(syntax) || Regex("^(?:no |nunca |explica|conta|como |que es )").containsMatchIn(syntax))))) {
      return PhoneRoute.Unsupported("El pedido incluye datos que esta acción no representa. No se ejecutó.")
    }
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
      when (val reply = parser.replyToAlarm(source.first, text)) {
        is PhoneRoute.Local -> return reply
        is PhoneRoute.Clarify -> return reply.copy(context = "intent:$id")
        is PhoneRoute.Unsupported -> return reply
        else -> Unit
      }
      if(Regex("^(?:hoy|manana)\\b").containsMatchIn(normalized)) {
        val combined=parser.parse("alarma $text")
        if(combined is PhoneRoute.Local && d.accepts(combined.command)) return combined
      }
      val sourceWithDate=Regex("\\b(?:hoy|manana)\\b").containsMatchIn(PhoneCommandParser.normalize(source.first).replace("de la manana",""))
      if(sourceWithDate) return PhoneRoute.Clarify("Conservé que pediste una fecha. Indicá día y hora completos, por ejemplo mañana a las siete de la mañana.","intent:$id")
    }
    if (id in CalendarSelection.types && source != null) {
      val current = java.util.Calendar.getInstance()
      if (CalendarSelection.iso(current) != CalendarSelection.iso(source.second) || current.timeZone != source.second.timeZone)
        return PhoneRoute.Clarify("Cambió el día o la zona horaria. Repetí el pedido con la fecha y hora deseadas.", "intent:$id")
      val action = PlatformAction.valueOf(id.uppercase(java.util.Locale.ROOT))
      val request = FoldedRequest.of(source.first).withCommandHead()
      CalendarSelectionParser { current }.reply(request, text, action)?.let { return it }
      return PhoneRoute.Clarify(d.prompt, "intent:$id")
    }
    val route = if (d.parseParameters != null && d.replyPrefix != null) {
      d.parseParameters.invoke(FoldedRequest.of("${d.replyPrefix} $text").withCommandHead())
        ?: PhoneRoute.Clarify(d.prompt, "intent:$id")
    } else d.parameterContext?.let { parser.parseReply(it, text) }
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
        mechanism: String, permissions: List<String> = emptyList(), result: String = desc, decisionDescription: String = desc) {
        add(LocalIntentDefinition(id, cat, desc, listOf(first, second), emptyList(), permissions, mechanism, result,
          fixed = command, decisionDescription = decisionDescription))
      }
      fun parsed(id: String, cat: IntentCategory, desc: String, first: String, second: String,
        context: String, prompt: String, params: List<IntentParameter>, mechanism: String,
        permissions: List<String> = emptyList(), result: String = desc, decisionDescription: String = desc,
        decisionParameters: List<String> = params.filter { it.required }.map { it.name }, accepts: (PhoneCommand) -> Boolean) {
        add(LocalIntentDefinition(id,cat,desc,listOf(first,second),params,permissions,mechanism,result,context,prompt,accepts=accepts,
          decisionDescription = decisionDescription, decisionParameters = decisionParameters))
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
        listOf(p("seconds","entero 1..2147483647"),p("label",required=false)),"AlarmClock.ACTION_SET_TIMER",clock,"Solicitud entregada al Reloj",
        decisionDescription = "Iniciar una cuenta regresiva nueva con la duración pedida y conservar su etiqueta si se indica.") { it is PhoneCommand.Timer }
      parsed("alarm",IntentCategory.CLOCK,"Crear alarma con hora, período y repetición opcional; no omitir fechas", "poné una alarma a las ocho de la mañana","despertame a las ocho de la mañana", "alarm","¿A qué hora y qué día? Indicá la hora completa.",
        listOf(p("hour","entero 0..23"),p("minute","entero 0..59"),p("days","días 1..7",false),p("date","fecha local hoy/mañana sin repetición",false),p("label",required=false)),"AlarmClock.ACTION_SET_ALARM",clock,"Solicitud entregada al Reloj; fechas especiales requieren soporte explícito",
        decisionDescription = "Crear una alarma nueva para una hora, con la fecha, el período o la repetición indicados.") { it is PhoneCommand.Alarm }
      fixed("alarms_show",IntentCategory.CLOCK,"Consultar alarmas","mostrá mis alarmas","abrí las alarmas",PhoneCommand.ClockList(true),"AlarmClock.ACTION_SHOW_ALARMS",clock,
        decisionDescription = "Mostrar la lista de alarmas existentes, sin crear ni modificar una alarma.")
      fixed("timers_show",IntentCategory.CLOCK,"Consultar temporizadores","mostrá los timers","ver temporizadores",PhoneCommand.ClockList(false),"AlarmClock.ACTION_SHOW_TIMERS",clock,
        decisionDescription = "Mostrar la lista de cuentas regresivas existentes, sin iniciar un temporizador.")
      fixed("alarm_snooze",IntentCategory.CLOCK,"Posponer la alarma que está sonando con la duración predeterminada del Reloj","posponé la alarma","dame unos minutos más con la alarma",PhoneCommand.Platform(PlatformAction.ALARM_SNOOZE),"AlarmClock.ACTION_SNOOZE_ALARM",clock,"Solicitud de posposición entregada al Reloj",
        decisionDescription = "Posponer la alarma que está sonando por el intervalo predeterminado.")
      fixed("timer_dismiss",IntentCategory.CLOCK,"Descartar temporizador mediante la interfaz del Reloj; sin seleccionar otro temporizador","cancelá un temporizador","descartá el timer",PhoneCommand.Platform(PlatformAction.TIMER_DISMISS),"AlarmClock.ACTION_DISMISS_TIMER",clock,"Solicitud entregada al Reloj con interfaz visible",
        decisionDescription = "Cancelar o descartar un temporizador existente, en vez de pausarlo o modificarlo.")
      for ((id, op, verb) in listOf(Triple("start",StopwatchOp.START,"iniciar"),Triple("pause",StopwatchOp.PAUSE,"pausar"),Triple("resume",StopwatchOp.RESUME,"continuar"),Triple("reset",StopwatchOp.RESET,"reiniciar"),Triple("show",StopwatchOp.STATUS,"mostrar"))) {
        fixed("stopwatch_$id",IntentCategory.CLOCK,"$verb el cronómetro","$verb el cronómetro","quiero $verb el cronómetro",PhoneCommand.Stopwatch(op),"NativeStopwatchController y accesibilidad autorizada",listOf("accesibilidad si el Reloj no exporta la acción"))
      }
      for ((id, desc) in listOf("alarm_update" to "Modificar una alarma existente", "timer_update" to "Modificar un temporizador existente", "timer_pause" to "Pausar un temporizador", "timer_resume" to "Reanudar un temporizador")) {
        unavailable(id,IntentCategory.CLOCK,desc,desc.lowercase(),"quiero ${desc.lowercase()}",listOf(p("selector"),p("changes",required=false)),"Integración específica de Reloj; Android no ofrece CRUD universal",clock,"Reconocí el pedido, pero este Reloj no tiene una integración verificada para esa operación. Podés pedir abrir las alarmas o los temporizadores.")
      }
      parsed("calendar",IntentCategory.CALENDAR,"Crear evento con título, fecha, hora y duración","agendá reunión mañana a las 17:00","creá reunión mañana a las 17:00","calendar","Indicá título, fecha y hora del evento.",listOf(p("title"),p("beginMillis","fecha futura en zona del teléfono"),p("endMillis","fecha posterior al inicio",false,"duración predeterminada de una hora si no se indica otra")),"Intent.ACTION_INSERT CalendarContract.Events",result="Borrador entregado a Calendario para revisión y guardado",
        decisionDescription = "Crear un evento nuevo en el calendario con el título, fecha, hora y duración indicados.",
        decisionParameters = listOf("title", "date", "time")) { it is PhoneCommand.CalendarEvent }
      fixed("calendar_agenda",IntentCategory.CALENDAR,"Abrir la agenda actual sin filtros de fecha adicionales","abrí mi agenda","mostrá el calendario",PhoneCommand.Platform(PlatformAction.CALENDAR_AGENDA),"Intent.ACTION_MAIN/CATEGORY_APP_CALENDAR",result="Calendario abierto; no se afirma haber leído eventos",
        decisionDescription = "Abrir la vista general de la agenda, sin consultar ni filtrar sus eventos.")
      parsed("dial",IntentCategory.CALLS,"Preparar llamada a contacto o número en el marcador","llamá a Ana","marcá el número de Ana","dial","¿A quién querés llamar?",listOf(p("target")),"Intent.ACTION_DIAL y ContactsContract",contacts,"Número preparado; el usuario inicia la llamada en Teléfono",
        decisionDescription = "Preparar en el marcador una llamada a un contacto o número, sin iniciarla.") { it is PhoneCommand.Dial }
      fixed("dialer_open",IntentCategory.CALLS,"Abrir marcador sin destinatario","abrí el marcador","abrí el teclado del teléfono",PhoneCommand.Platform(PlatformAction.DIALER_OPEN),"Intent.ACTION_DIAL",
        decisionDescription = "Abrir el teclado de llamadas sin preparar un destinatario ni iniciar la llamada.")
      // Direct background calling is a separate integration, never inferred from preparing a dial intent.
      unavailable("call_direct",IntentCategory.CALLS,"Iniciar llamada directamente sin intervención en el marcador","iniciá la llamada directamente","llamá sin abrir el marcador",listOf(p("target")),"Intent.ACTION_CALL / Telecom con acceso explícito",listOf("android.permission.CALL_PHONE"),"Insto prepara llamadas en el marcador; no tiene habilitado el inicio directo de llamadas. No se llamó a nadie.")
      addAll(NativeActionParameters.definitions())
      parsed("app_open",IntentCategory.APPLICATIONS,"Abrir una aplicación instalada","abrí Spotify","iniciá Spotify","app","¿Qué aplicación querés abrir?",listOf(p("name")),"PackageManager: launcher exportado",result="Aplicación abierta o selección de coincidencias",
        decisionDescription = "Abrir la aplicación pedida por su nombre, sin abrir sus ajustes ni pedir una acción en ella.") { it is PhoneCommand.OpenApp }
      parsed("youtube",IntentCategory.APPLICATIONS,"Buscar contenido en YouTube","buscá jazz en YouTube","buscá en YouTube jazz","youtube","¿Qué querés buscar en YouTube?",listOf(p("query")),"Búsqueda pública de YouTube",result="Búsqueda abierta, no reproducción garantizada",
        decisionDescription = "Buscar contenido en YouTube, sin pedir que se reproduzca un título.") { it is PhoneCommand.YouTubeSearch }
      parsed("spotify",IntentCategory.MEDIA,"Buscar contenido en Spotify","buscá jazz en Spotify","buscá en Spotify jazz","spotify","¿Qué querés buscar en Spotify?",listOf(p("query")),"ACTION_VIEW de búsqueda pública de Spotify",result="Búsqueda abierta, no reproducción garantizada",
        decisionDescription = "Buscar contenido en Spotify, sin pedir que se reproduzca un título.") { it is PhoneCommand.SpotifySearch }
      fixed("torch_on",IntentCategory.ANDROID,"Encender linterna ahora sin duración ni condiciones","encendé la linterna","iluminá con la linterna",PhoneCommand.Torch(true),"CameraManager.setTorchMode",listOf("android.permission.CAMERA"),
        decisionDescription = "Encender la linterna del teléfono. Ejemplo: «Dame un poco de luz».")
      fixed("torch_off",IntentCategory.ANDROID,"Apagar linterna ahora","apagá la linterna","dejá de iluminar con la linterna",PhoneCommand.Torch(false),"CameraManager.setTorchMode",listOf("android.permission.CAMERA"),
        decisionDescription = "Apagar ahora la linterna del teléfono. Una luz sin identificar no basta.")
      fixed("volume_up",IntentCategory.ANDROID,"Subir volumen multimedia un paso","subí el volumen","más fuerte el sonido",PhoneCommand.Volume(VolumeOp.UP),"AudioManager.adjustStreamVolume",permissions=listOf("android.permission.MODIFY_AUDIO_SETTINGS"),
        decisionDescription = "Subir un paso el volumen multimedia, sin fijar un porcentaje ni cambiar el modo de sonido.")
      fixed("volume_down",IntentCategory.ANDROID,"Bajar volumen multimedia un paso","bajá el volumen","menos fuerte el sonido",PhoneCommand.Volume(VolumeOp.DOWN),"AudioManager.adjustStreamVolume",permissions=listOf("android.permission.MODIFY_AUDIO_SETTINGS"),
        decisionDescription = "Bajar un paso el volumen multimedia, sin fijar un porcentaje ni cambiar el modo de sonido.")
      parsed("volume_set",IntentCategory.ANDROID,"Fijar porcentaje del volumen multimedia","poné el volumen al 40 por ciento","ajustá el volumen al cuarenta por ciento","volume","¿Qué porcentaje de volumen multimedia?",listOf(p("percent","entero 0..100")),"AudioManager.setStreamVolume",permissions=listOf("android.permission.MODIFY_AUDIO_SETTINGS"),
        decisionDescription = "Fijar el volumen multimedia en el porcentaje indicado.") { it is PhoneCommand.Volume && it.operation == VolumeOp.SET }
      for ((id,kind) in listOf("settings_wifi" to "wifi","settings_bluetooth" to "bluetooth","settings_internet" to "internet","settings_open" to "ajustes")) {
        fixed(id,if(kind=="ajustes") IntentCategory.DEVICE else IntentCategory.ANDROID,"Abrir ajustes de $kind, sin prometer cambiar el estado","abrí ajustes de $kind","mostrá configuración de $kind",PhoneCommand.Settings(kind),"Settings activity/panel",result="Panel abierto para intervención del usuario",
          decisionDescription = "Abrir el panel de ajustes de $kind, sin cambiar el estado ni ajustar una aplicación.")
      }
      for ((id,desc) in listOf("wifi_on" to "Activar Wi-Fi","wifi_off" to "Desactivar Wi-Fi","bluetooth_on" to "Activar Bluetooth","bluetooth_off" to "Desactivar Bluetooth")) {
        unavailable(id,IntentCategory.ANDROID,desc,desc.lowercase(),"quiero ${desc.lowercase()}",emptyList(),"Conmutación restringida a sistema/device owner en Android moderno",emptyList(),"Android no permite a esta app cambiar ese estado directamente. No cambié la conectividad. Podés pedir abrir los ajustes correspondientes.")
      }
      val ringerPermissions = policy + "android.permission.MODIFY_AUDIO_SETTINGS"
      fixed("silent_on", IntentCategory.ANDROID, "Silenciar llamadas y notificaciones", "silenciar", "poner en silenciar",
        PhoneCommand.Platform(PlatformAction.SILENT_ON), "AudioManager.ringerMode", ringerPermissions,
        decisionDescription = "Activar el modo silencio para llamadas y notificaciones, sin cambiar el volumen multimedia.")
      fixed("vibrate_on", IntentCategory.ANDROID, "Activar el modo vibración", "modo vibrar", "poner en vibrar",
        PhoneCommand.Platform(PlatformAction.VIBRATE_ON), "AudioManager.ringerMode", ringerPermissions,
        decisionDescription = "Activar el modo vibración del teléfono para llamadas y notificaciones.")
      val soundModes = listOf(
        Triple("silent_off", PlatformAction.SILENT_OFF, "Desactivar silencio de llamadas"),
        Triple("dnd_on", PlatformAction.DND_ON, "Activar No molestar"),
        Triple("dnd_off", PlatformAction.DND_OFF, "Desactivar No molestar"),
      )
      for ((id, action, desc) in soundModes) {
        val decisionDescription = when (action) {
          PlatformAction.SILENT_OFF -> "Salir del modo silencio o vibración para llamadas y notificaciones."
          PlatformAction.DND_ON -> "Activar el modo No molestar, distinto de silencio o vibración."
          else -> "Salir del modo No molestar, sin cambiar el volumen multimedia."
        }
        fixed(id, IntentCategory.ANDROID, desc, desc.lowercase(), "quiero ${desc.lowercase()}", PhoneCommand.Platform(action),
          "AudioManager / NotificationManager con acceso de política", if (action == PlatformAction.SILENT_OFF) ringerPermissions else policy,
          decisionDescription = decisionDescription)
      }
      val mediaControls = listOf(
        Triple("play", MediaOp.PLAY, "Reanudar reproducción"),
        Triple("pause", MediaOp.PAUSE, "Pausar reproducción"),
        Triple("next", MediaOp.NEXT, "Siguiente tema"),
        Triple("previous", MediaOp.PREVIOUS, "Tema anterior"),
        Triple("status", MediaOp.STATUS, "Consultar qué está sonando"),
      )
      for ((id, op, desc) in mediaControls) {
        val decisionDescription = when (op) {
          MediaOp.PLAY -> "Reanudar lo que ya estaba sonando en la sesión actual, sin elegir una canción nueva."
          MediaOp.PAUSE -> "Pausar la reproducción actual para poder retomarla, sin seleccionar una aplicación específica."
          MediaOp.NEXT -> "Pasar a la pista siguiente de la sesión de reproducción actual."
          MediaOp.PREVIOUS -> "Volver a la pista anterior de la sesión de reproducción actual."
          else -> "Consultar qué pista está sonando en la sesión actual, sin controlar la reproducción."
        }
        fixed("media_$id", IntentCategory.MEDIA, desc, desc.lowercase(), "quiero ${desc.lowercase()}", PhoneCommand.Media(op),
          "MediaSession.TransportControls", media, "Acción o estado de una sesión compatible; elegir si hay varias", decisionDescription)
      }
      fixed("media_stop", IntentCategory.MEDIA, "Parar la reproducción actual", "parar la música", "detener la reproducción",
        PhoneCommand.Media(MediaOp.STOP), "MediaSession.TransportControls", media, "Se envía la orden de parar o pausar a la sesión elegida",
        decisionDescription = "Detener la reproducción actual, sin seleccionar una aplicación específica.")
      for (app in MediaApp.entries) {
        for ((verb, operation) in listOf("pausar" to MediaOp.PAUSE, "parar" to MediaOp.STOP)) {
          val id = "media_${operation.name.lowercase(java.util.Locale.ROOT)}_${app.name.lowercase(java.util.Locale.ROOT)}"
          fixed(id, IntentCategory.MEDIA, "${verb.replaceFirstChar(Char::uppercaseChar)} la reproducción de ${app.label}",
            "$verb ${app.label}", "$verb la reproducción en ${app.label}", PhoneCommand.Media(operation, app.packageName),
            "MediaSession.TransportControls", media, "Se envía el control a la sesión de ${app.label}")
        }
      }
      parsed("music", IntentCategory.MEDIA, "Solicitar la reproducción por título en una aplicación compatible",
        "reproducir De música ligera en Spotify", "reproducir De música ligera en YouTube", "music", "¿Qué querés escuchar?",
        listOf(p("query"), p("app", "SPOTIFY/YOUTUBE", false)), "MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH o MediaSession",
        result = "Se solicita reproducción y, si la app elegida no admite el pedido, se abre su búsqueda",
        decisionDescription = "Reproducir canción, artista o lista con la app indicada, si la hay. Ej.: «Poné algo de los Beatles».") { it is PhoneCommand.MusicSearch }
      parsed("navigate",IntentCategory.NAVIGATION,"Iniciar ruta al destino con modo de viaje admitido","llevame a Córdoba","navegá a Córdoba","navigate","¿A dónde querés ir?",listOf(p("destination"),p("mode","DRIVING/WALKING/BICYCLING/TRANSIT",false)),"Maps directions",result="Ruta entregada a Maps",
        decisionDescription = "Iniciar una ruta al destino indicado y conservar el modo de viaje si se especifica.") { it is PhoneCommand.Navigate }
      parsed("maps",IntentCategory.NAVIGATION,"Buscar un lugar en el mapa","buscá en Maps farmacias","buscá farmacias cerca de mí","maps","¿Qué lugar querés buscar?",listOf(p("query")),"Maps search",
        decisionDescription = "Buscar un lugar en el mapa sin iniciar la navegación hasta él.") { it is PhoneCommand.MapSearch }
      parsed("place_save",IntentCategory.NAVIGATION,"Guardar dirección de casa o trabajo","guardá mi casa en Avenida Colón 123","mi casa es Avenida Colón 123","place","Indicá casa o trabajo y la dirección completa.",listOf(p("alias","casa/trabajo"),p("address")),"SavedPlacesRepository",
        decisionDescription = "Guardar una dirección explícita como casa o trabajo para usarla más adelante.") { it is PhoneCommand.SavePlace }
      fixed("camera_photo",IntentCategory.CAMERA_RECORDING,"Abrir cámara en modo foto sin disparar","abrí la cámara","abrí la cámara de fotos",PhoneCommand.Camera(),"MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA",
        decisionDescription = "Abrir la cámara en modo foto, sin pedir la captura de una foto.")
      fixed("camera_video",IntentCategory.CAMERA_RECORDING,"Abrir cámara en modo video sin iniciar grabación","abrí cámara de video","abrí la cámara en modo video",PhoneCommand.Camera(true),"MediaStore.INTENT_ACTION_VIDEO_CAMERA",
        decisionDescription = "Abrir la cámara en modo video, sin pedir que se grabe un video.")
      fixed("recorder_open",IntentCategory.CAMERA_RECORDING,"Abrir grabadora para iniciar grabación con intervención del usuario","abrí la grabadora","prepará una grabación de audio",PhoneCommand.Platform(PlatformAction.RECORDER_OPEN),"MediaStore.Audio.Media.RECORD_SOUND_ACTION",result="Grabadora abierta; no se afirma grabación iniciada",
        decisionDescription = "Abrir la grabadora de audio, sin pedir que se inicie una grabación.")
      for ((id,desc) in listOf("camera_capture" to "Capturar una foto directamente", "camera_record" to "Iniciar una grabación de video", "recorder_start" to "Iniciar grabación de audio directamente", "recorder_stop" to "Detener una grabación de audio")) {
        unavailable(id,IntentCategory.CAMERA_RECORDING,desc,desc.lowercase(),"quiero ${desc.lowercase()}",emptyList(),"Sesión de captura y URI de salida controlada por Insto",listOf("CAMERA/RECORD_AUDIO según captura"),"Reconocí la captura, pero Insto no controla una sesión de grabación para esa acción. No inicié ni detuve ninguna captura. Podés pedir abrir cámara o grabadora.")
      }
      fixed("battery",IntentCategory.DEVICE,"Consultar porcentaje de batería","cuánta batería queda","decime el nivel de batería",PhoneCommand.Platform(PlatformAction.BATTERY),"BatteryManager.BATTERY_PROPERTY_CAPACITY",result="Porcentaje real de batería o error si no disponible",
        decisionDescription = "Consultar cuánta batería le queda al teléfono, sin cambiar sus ajustes.")
      fixed("accessibility_settings",IntentCategory.DEVICE,"Abrir ajustes de accesibilidad","abrí accesibilidad","mostrá configuración de accesibilidad",PhoneCommand.Platform(PlatformAction.ACCESSIBILITY_SETTINGS),"Settings.ACTION_ACCESSIBILITY_SETTINGS")
      fixed("display_settings",IntentCategory.DEVICE,"Abrir ajustes de pantalla","abrí ajustes de pantalla","mostrá configuración de pantalla",PhoneCommand.Platform(PlatformAction.DISPLAY_SETTINGS),"Settings.ACTION_DISPLAY_SETTINGS")
      unavailable("shortcut_run",IntentCategory.DEVICE,"Ejecutar un acceso directo previamente integrado","ejecutá mi acceso directo","activá mi atajo",listOf(p("shortcutId")),"ShortcutManager/launcher autorizado o integración exportada",emptyList(),"Ese acceso directo necesita una integración registrada. No ejecuté otro componente.")
      parsed("temporary_mode",IntentCategory.AUTOMATION,"Aplicar modo temporal con duración explícita y restauración","silenciá el teléfono durante una hora","poné no molestar durante una hora","temporary_mode","Indicá el modo y su duración.",listOf(p("kind","MEDIA_VOLUME/SILENT/DND"),p("seconds","entero 1..86400"),p("percent","entero 0..100",false)),"TemporaryModeManager y restauración con propiedad de ajustes",policy+listOf("android.permission.MODIFY_AUDIO_SETTINGS para volumen/silencio","android.permission.POST_NOTIFICATIONS en Android 13+"),"Modo aplicado solo con accesos y programación disponibles",
        decisionDescription = "Aplicar volumen, silencio o No molestar por una duración y restaurar. Para volumen: percent.") { it is PhoneCommand.TemporaryMode }
      fixed("temporary_mode_end",IntentCategory.AUTOMATION,"Terminar el modo temporal y restaurar ahora","terminá el modo y restaurá ahora","restaurá el volumen",PhoneCommand.EndMode,"TemporaryModeManager.end",result="Restauración del estado guardado si existe",
        decisionDescription = "Terminar ahora un modo temporal activo y restaurar el estado anterior.")
      unavailable("routine_run",IntentCategory.AUTOMATION,"Ejecutar rutina local previamente configurada","ejecutá mi rutina estudio","activá la rutina estudio",listOf(p("routineId")),"Adaptador de rutinas registradas y WorkflowRunner",emptyList(),"No hay un proveedor de rutinas configuradas registrado en esta instalación. No convertí el pedido en una secuencia nueva.")
    }.let {base ->
      val native=ExtendedLocalIntents.definitions()
      val replaced=native.map {it.id}.toSet()
      base.filterNot {it.id in replaced}+native
    }
  }
}
