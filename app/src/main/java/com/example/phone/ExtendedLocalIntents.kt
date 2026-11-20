package com.example.phone

/** Public APIs that require Android selection/permission UI, rather than an open-ended model. */
internal object ExtendedLocalIntents {
  fun definitions():List<LocalIntentDefinition> = buildList {
    fun parameter(name:String,required:Boolean=true)=IntentParameter(name,"texto",required,"valor literal o selección explícita en Android")
    fun parsed(id:String,category:IntentCategory,description:String,examples:List<String>,op:PlatformAction,
      parameters:List<IntentParameter>,permissions:List<String>,mechanism:String,result:String,
      prompt:String,decisionDescription:String=description,extract:(FoldedRequest)->PhoneCommand.Platform?) {
      add(LocalIntentDefinition(id,category,description,examples,parameters,permissions,mechanism,result,prompt=prompt,decisionDescription=decisionDescription,
        accepts={it is PhoneCommand.Platform && it.action==op},replyPrefix=when(op) {
          PlatformAction.ROUTINE_RUN -> "ejecutá la rutina"
          PlatformAction.CALL_DIRECT -> "llamá directamente al"
          PlatformAction.CALENDAR_QUERY -> "consultá mi agenda"
          PlatformAction.MESSAGE_SEND -> "mandá por WhatsApp a"
          else -> null
        },parseParameters={request ->
          extract(request)?.let {PhoneRoute.Local(it)}
        }))
    }
    parsed("call_direct",IntentCategory.CALLS,"Iniciar llamada directa a un número explícito, con permiso y confirmación",listOf("llamá directamente al +541112345678","iniciá una llamada al +541112345678"),PlatformAction.CALL_DIRECT,
      listOf(parameter("number")),listOf("android.permission.CALL_PHONE"),"Intent.ACTION_CALL tras confirmación local","Android inicia la llamada solicitada; no se afirma conexión",
      "Indicá un número completo: llamá directamente al +541112345678.",
        decisionDescription = "Iniciar una llamada directa al número explícito, en vez de preparar el marcador.") {r ->
      Regex("^(?:(?:llama|marca) directamente (?:al?|a)|(?:inicia|hace) (?:una )?llamada directa? (?:al?|a)|inicia una llamada (?:al?|a)) ([+0-9 ()-]+)[.!?]*$").matchEntire(r.text)?.let {m ->
        PhoneNumbers.canonical(r.capture(m,1))?.let {PhoneCommand.Platform(PlatformAction.CALL_DIRECT,it)}
      }
    }
    parsed("message_send",IntentCategory.MESSAGING,"Enviar un mensaje por WhatsApp a un número o a un contacto por su nombre, tras revisar destinatario y contenido. Todo mensaje va por WhatsApp; no hay SMS",listOf("mandá un WhatsApp a Ana que diga Estoy llegando","whatsapp a Meme te amo","mandale un mensaje a +541112345678 que diga Hola"),PlatformAction.MESSAGE_SEND,
      listOf(parameter("recipient"),parameter("body")),listOf("WhatsApp instalado y accesibilidad conectada/autorizada","android.permission.READ_CONTACTS para destinatarios por nombre"),"WhatsAppDispatcher con guardas de destinatario y texto existentes",
      "Solicitud de envío entregada a WhatsApp; la automatización verifica destinatario y cuerpo. Un nombre se resuelve en la agenda y se confirma antes de enviar. Sin otro destinatario por defecto.",
      "Indicá destinatario y texto: WhatsApp a Ana Estoy llegando.",
        decisionDescription = "Enviar un mensaje (siempre por WhatsApp) al número o contacto indicado por su nombre.") {r -> whatsappCommand(r)}
    for((id,op,verb) in listOf(
      Triple("file_open",PlatformAction.FILE_OPEN,"abri|mostra|ver"),Triple("file_search",PlatformAction.FILE_SEARCH,"busca|encontra"),
      Triple("file_share",PlatformAction.FILE_SHARE,"comparti"),Triple("file_delete",PlatformAction.FILE_DELETE,"borra|elimina"),
      Triple("file_copy",PlatformAction.FILE_COPY,"copia"),Triple("file_move",PlatformAction.FILE_MOVE,"move"))) {
      val label=when(op) {PlatformAction.FILE_OPEN->"Abrir";PlatformAction.FILE_SEARCH->"Buscar";PlatformAction.FILE_SHARE->"Compartir";PlatformAction.FILE_DELETE->"Eliminar";PlatformAction.FILE_COPY->"Copiar";else->"Mover"}
      parsed(id,IntentCategory.FILES,"$label un archivo con selección y permisos de Android",listOf("${label.lowercase()} el archivo informe.pdf","${label.lowercase()} un archivo"),op,
        listOf(parameter("name",false)),listOf("concesión de URI del Storage Access Framework"),"ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract",
        "Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.","Indicá solo el archivo o pedí elegirlo en el selector de Android.",
        decisionDescription = "$label un archivo o documento identificado por su nombre o por selección.") { r ->
        Regex("^(?:$verb) (?:el |un )?(?:archivo|documento)(?: (.+?))?[.!?]*$").matchEntire(r.text)?.let {m ->
          val name=r.capture(m,1).trim('"').takeIf {it.isNotBlank()}
          if(name!=null && !literalTarget(name)) null else PhoneCommand.Platform(op,name)
        }
      }
    }
    parsed("file_rename",IntentCategory.FILES,"Renombrar un archivo con nombres exactos y selección Android",listOf("renombrá el archivo \"informe.pdf\" a \"final.pdf\"","renombrar archivo \"foto.jpg\" a \"viaje.jpg\""),PlatformAction.FILE_RENAME,
      listOf(parameter("name"),parameter("newName")),listOf("concesión de URI de escritura"),"ACTION_OPEN_DOCUMENT y DocumentsContract.renameDocument","Se verifica el archivo y se confirma el nombre antes de renombrar",
      "Indicá nombres entre comillas: renombrá el archivo \"informe.pdf\" a \"final.pdf\".",
        decisionDescription = "Cambiar el nombre de un archivo indicando el nombre actual y el nuevo.") {r ->
      Regex("^(?:renombra|cambia el nombre de) (?:el )?(?:archivo|documento) \"(.+?)\" (?:a|por) \"([^/\\\\]+)\"[.!?]*$").matchEntire(r.text)?.let {m ->
        PhoneCommand.Platform(PlatformAction.FILE_RENAME,r.capture(m,1),content=r.capture(m,2))
      }
    }
    for ((action, description, examples) in listOf(
      Triple(PlatformAction.CALENDAR_QUERY, "Consultar eventos de la agenda", listOf("qué tengo mañana", "mostrá mi agenda de esta semana")),
      Triple(PlatformAction.CALENDAR_UPDATE, "Modificar un evento de la agenda", listOf("editá el evento \"Reunión\"", "pasá la reunión del viernes a las 18:00")),
      Triple(PlatformAction.CALENDAR_DELETE, "Eliminar un evento de la agenda", listOf("cancelá la reunión del viernes", "borrá el turno médico de mañana")),
    )) {
      val id = action.name.lowercase(java.util.Locale.ROOT)
      val readOnly = action == PlatformAction.CALENDAR_QUERY
      val decisionDescription = when (action) {
        PlatformAction.CALENDAR_QUERY -> "Consultar eventos existentes en la agenda, por fecha, horario o título."
        PlatformAction.CALENDAR_UPDATE -> "Modificar la fecha, hora o duración de un evento existente en la agenda."
        else -> "Cancelar o eliminar un evento existente en la agenda, identificado por título u horario."
      }
      add(LocalIntentDefinition(id, IntentCategory.CALENDAR, description, examples,
        listOf(parameter("title", false), parameter("from", false), parameter("until", false), parameter("at", false)) +
          if (action == PlatformAction.CALENDAR_UPDATE) listOf(parameter("newDate", false), parameter("newTime", false), parameter("durationSeconds", false)) else emptyList(),
        listOf("android.permission.READ_CALENDAR") + if (readOnly) emptyList() else listOf("android.permission.WRITE_CALENDAR"),
        "CalendarContract.Instances y CalendarContract.Events", "Consulta local o cambio de un evento elegido y confirmado",
        decisionDescription = decisionDescription,
        prompt = "Indicá el título o el día y horario exactos del evento. Para modificarlo, indicá también el cambio.",
        accepts = { it is PhoneCommand.Platform && it.action == action },
        parseParameters = { request ->
          CalendarSelectionParser().parse(request)?.takeIf {
            it !is PhoneRoute.Local || (it.command as? PhoneCommand.Platform)?.action == action
          }
        }))
    }
    parsed("routine_run",IntentCategory.AUTOMATION,"Ejecutar una rutina local ya configurada",listOf("ejecutá la rutina Estudio","activá mi rutina Estudio"),PlatformAction.ROUTINE_RUN,
      listOf(parameter("name")),emptyList(),"ConfiguredRoutineStore y WorkflowRunner","Se revisan los pasos guardados antes de ejecutar, sin crear pasos a partir del pedido",
      "¿Qué rutina configurada querés ejecutar? Indicá su nombre.",
        decisionDescription = "Ejecutar por nombre una rutina ya configurada, sin interpretar pasos nuevos.") {r ->
      Regex("^(?:ejecuta|activa|inicia|lanza) (?:la |mi )?rutina (.+?)[.!?]*$").matchEntire(r.text)?.let {m ->
        r.capture(m,1).takeIf(::literalTarget)?.let {PhoneCommand.Platform(PlatformAction.ROUTINE_RUN,it)}
      }
    }
    val captures = listOf(
      Triple("camera_capture", PlatformAction.CAPTURE_PHOTO, "Capturar una foto"),
      Triple("camera_record", PlatformAction.CAPTURE_VIDEO, "Capturar un video"),
      Triple("recorder_start", PlatformAction.CAPTURE_AUDIO, "Capturar una grabación de audio"),
    )
    for ((id, op, desc) in captures) {
      val decisionDescription = when (op) {
        PlatformAction.CAPTURE_PHOTO -> "Capturar una foto en vez de solo abrir la cámara en modo foto."
        PlatformAction.CAPTURE_VIDEO -> "Grabar un video en vez de solo abrir la cámara en modo video."
        else -> "Grabar audio en vez de solo abrir la grabadora."
      }
      add(LocalIntentDefinition(id, IntentCategory.CAMERA_RECORDING, "$desc mediante la aplicación nativa",
        listOf(desc.lowercase(), "quiero ${desc.lowercase()}"), emptyList(),
        if (op == PlatformAction.CAPTURE_AUDIO) emptyList() else listOf("android.permission.CAMERA"),
        "ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE / RECORD_SOUND_ACTION con resultado y URI autorizada",
        "Se abre la interfaz nativa para capturar; solo se informa archivo guardado cuando la app devuelve un resultado válido. Sin filtros ni ajustes adicionales.",
        fixed = PhoneCommand.Platform(op), decisionDescription = decisionDescription))
    }
    for ((action, description, examples) in listOf(
      Triple(PlatformAction.WEATHER_TEMPERATURE, "Consultar la temperatura y la sensación térmica medidas por el SMN en la estación Buenos Aires",
        listOf("qué temperatura hace", "cuál es la sensación térmica")),
      Triple(PlatformAction.WEATHER_RAIN, "Consultar la probabilidad de precipitaciones del SMN para Capital Federal en las próximas horas",
        listOf("va a llover", "qué probabilidad de lluvia hay")),
    )) {
      val temperature = action == PlatformAction.WEATHER_TEMPERATURE
      add(LocalIntentDefinition(action.name.lowercase(java.util.Locale.ROOT), IntentCategory.WEATHER, description, examples,
        if (temperature) listOf(IntentParameter("argument", "feels_like", false, "solo si se pregunta por la sensación térmica")) else emptyList(),
        emptyList(),
        if (temperature) "Datos abiertos del SMN «Estado del tiempo presente» (zipopendata tiepre), a pedido y sin token"
          else "API ws1 del SMN con credencial anónima de la portada, a pedido; sin otros proveedores",
        if (temperature) "Valor publicado con la hora de medición; más de tres horas de antigüedad no se informa como actual"
          else "Rangos publicados de los bloques del SMN que cubren las próximas seis horas; nunca promedios ni deducciones",
        prompt = "Preguntame, por ejemplo, «¿qué temperatura hace?» o «¿va a llover?».",
        decisionDescription = if (temperature) "Consultar la temperatura o sensación térmica actuales, sin otra ciudad ni otro día."
          else "Consultar si va a llover en las próximas horas, sin otra ciudad ni otro día.",
        accepts = { it is PhoneCommand.Platform && it.action == action },
        // Local grammar only: no Gemini Decisions, no Groq classification, no Instinct.
        decisions = false,
        localSyntax = Regex("temperatura|grados|termica|llov|lluvia|precipitaci|maxima|minima"),
        parseParameters = { request ->
          WeatherIntentParser.parse(request.raw)?.takeIf { it !is PhoneRoute.Local || (it.command as? PhoneCommand.Platform)?.action == action }
        }))
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

  /** Every message goes through WhatsApp: «SMS» and «mensaje» name the same channel. */
  private const val WHATSAPP = "(?:whatsapp|whats app|wasap|wasa|wsp|sms)"
  private const val CONNECTOR = "(?:que diga|que dice|con el texto|diciendo)"
  private const val MESSAGE_HEAD = "^(?:(manda|envia|escribile|escribirle|escribi|redacta|prepara) )?" +
    "(?:((?:un |una )?(?:mensajito|mensaje)(?: (?:por|de|en) $WHATSAPP)?|(?:(?:un|por|de|en) )?$WHATSAPP) )?(?:al?|para) "
  /** «…a Ana por WhatsApp (que diga) Hola». */
  private val trailingChannel = Regex("$MESSAGE_HEAD(.+?) (?:por|en) ($WHATSAPP)(?: $CONNECTOR)? (.+)$")
  /** «…a Ana que diga Hola». */
  private val withConnector = Regex("$MESSAGE_HEAD(.+?) $CONNECTOR (.+)$")
  /** «WhatsApp a Meme Te amo»: the contacts decide where the name ends. */
  private val bare = Regex("$MESSAGE_HEAD(.+)$")
  private val leadingNumber = Regex("^\\+?[0-9][0-9 ()-]{5,}?(?=\\s+\\D)")

  private fun whatsappCommand(r:FoldedRequest):PhoneCommand.Platform? {
    trailingChannel.matchEntire(r.text)?.takeIf {it.groups[1]!=null || it.groups[2]!=null}?.let {m ->
      return whatsappRecipient(r.capture(m,3))?.let {PhoneCommand.Platform(PlatformAction.MESSAGE_SEND,it,content=literalBody(r.capture(m,5)))}
    }
    withConnector.matchEntire(r.text)?.takeIf {it.groups[1]!=null || it.groups[2]!=null}?.let {m ->
      return whatsappRecipient(r.capture(m,3))?.let {PhoneCommand.Platform(PlatformAction.MESSAGE_SEND,it,content=literalBody(r.capture(m,4)))}
    }
    // Without a connector a channel is required, so «mandá a…» alone never becomes a message.
    val m = bare.matchEntire(r.text)?.takeIf {it.groups[2]!=null || it.groups[1]?.value?.startsWith("escribi")==true} ?: return null
    val tail = r.capture(m,3)
    leadingNumber.find(tail)?.let {number ->
      val body = literalBody(tail.substring(number.range.last+1))
      return PhoneNumbers.canonical(number.value)?.takeIf {it.count(Char::isDigit)>=7 && body.isNotEmpty()}
        ?.let {PhoneCommand.Platform(PlatformAction.MESSAGE_SEND,it,content=body)}
    }
    return tail.takeIf(::isUnsplitTail)?.let(::unsplitWhatsApp)
  }

  private val quotes = listOf("“" to "”", "\"" to "\"", "«" to "»", "'" to "'")
  /** The dictated text without the quotes that only delimit it: «que diga “no voy”» sends «no voy». */
  internal fun literalBody(text:String):String {
    val body = text.trim()
    return quotes.firstOrNull {(open,close) -> body.length>2 && body.startsWith(open) && body.endsWith(close)}
      ?.let {body.substring(1,body.length-1).trim()} ?: body
  }

  /**
   * «Meme Te amo» without a connector: name and text are split later against the contacts.
   * Encoded as recipient == body, which no real message has.
   */
  internal fun unsplitWhatsApp(tail:String) = PhoneCommand.Platform(PlatformAction.MESSAGE_SEND,tail,content=tail)
  internal fun isUnsplitWhatsApp(command:PhoneCommand.Platform) = command.action==PlatformAction.MESSAGE_SEND &&
    command.argument!=null && command.argument==command.content && isUnsplitTail(command.argument)
  private fun isUnsplitTail(tail:String) = tail.length<=2000 && tail.firstOrNull()?.isLetter()==true &&
    tail.trim().contains(Regex("\\s"))

  private val recipientName = Regex("^\\p{L}[\\p{L}' .-]{0,59}$")
  private val severalRecipients = Regex("\\b(?:y|e|o|todos|todas)\\b")

  /** A canonical number, or a single contact name resolved in Android's contacts before confirmation. */
  internal fun whatsappRecipient(value: String): String? {
    val target = value.trim().trim('"', '“', '”', '«', '»').trim()
    if (target.any(Char::isDigit) || target.startsWith("+")) return PhoneNumbers.canonical(target)?.takeIf {it.count(Char::isDigit)>=7}
    return target.takeIf {recipientName.matches(it) && !severalRecipients.containsMatchIn(PhoneCommandParser.normalize(it))}
  }
}
