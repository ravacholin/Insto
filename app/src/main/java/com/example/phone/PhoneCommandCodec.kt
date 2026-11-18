package com.example.phone

import org.json.JSONArray
import org.json.JSONObject

/** Shared disk and provider boundary. Reject coercion, unknown fields and out-of-range values. */
object PhoneCommandCodec {
 fun encode(c: PhoneCommand): JSONObject {
  fun obj(type: String, vararg fields: Pair<String, Any?>) = JSONObject().put("type", type).apply { fields.forEach { (k,v) -> if (v != null) put(k,v) } }
  return when(c) {
   is PhoneCommand.Platform -> obj("platform", "action" to c.action.name, "argument" to c.argument, "percent" to c.percent, "content" to c.content)
   is PhoneCommand.Timer -> obj("timer", "seconds" to c.seconds, "label" to c.label)
   is PhoneCommand.Alarm -> obj("alarm", "hour" to c.hour, "minute" to c.minute, "days" to JSONArray(c.days), "label" to c.label, "date" to c.date)
   is PhoneCommand.Volume -> obj("volume", "operation" to c.operation.name, "percent" to c.percent)
   is PhoneCommand.Torch -> obj("torch", "enabled" to c.enabled)
   is PhoneCommand.OpenApp -> obj("open_app", "name" to c.name)
   is PhoneCommand.Navigate -> obj("navigate", "destination" to c.destination, "mode" to c.mode.name)
   is PhoneCommand.MapSearch -> obj("maps", "query" to c.query)
   is PhoneCommand.Dial -> obj("dial", "target" to c.target)
   is PhoneCommand.CalendarEvent -> obj("calendar", "title" to c.title, "beginMillis" to c.beginMillis, "endMillis" to c.endMillis, "description" to c.description)
   is PhoneCommand.Camera -> obj("camera", "video" to c.video)
   is PhoneCommand.YouTubeSearch -> obj("youtube", "query" to c.query)
   is PhoneCommand.SpotifySearch -> obj("spotify", "query" to c.query)
   is PhoneCommand.MusicSearch -> obj("music", "query" to c.query, "app" to c.app?.name)
   is PhoneCommand.Stopwatch -> obj("stopwatch", "operation" to c.operation.name)
   is PhoneCommand.ClockList -> obj("clock_list", "alarms" to c.alarms)
   is PhoneCommand.Settings -> obj("settings", "kind" to c.kind)
   is PhoneCommand.SavePlace -> obj("save_place", "alias" to c.alias, "address" to c.address)
   is PhoneCommand.Media -> obj("media", "operation" to c.operation.name, "packageName" to c.packageName)
   is PhoneCommand.TemporaryMode -> obj("temporary_mode", "kind" to c.kind.name, "seconds" to c.seconds, "percent" to c.percent, "approximate" to c.approximate)
   PhoneCommand.EndMode -> obj("end_mode")
  }
 }
 fun decode(o: JSONObject): PhoneCommand {
  fun fields(vararg names: String) { require(o.keys().asSequence().toSet().all { it == "type" || it in names }) { "Campos no admitidos" } }
  fun str(k: String, default: String? = null): String {
   val v = if (o.has(k)) o.get(k) else default
   require(v is String && v.length <= 2000 && (v.isNotBlank() || k == "label")) { "Texto inválido: $k" }; return v
  }
  fun num(k: String, default: Long? = null): Long {
   val v = if (o.has(k)) o.get(k) else default
   require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble()) { "Número inválido: $k" }; return v.toLong()
  }
  fun integer(k: String, range: IntRange, default: Int? = null): Int {
   val v = num(k, default?.toLong()); require(v in range.first.toLong()..range.last.toLong()); return v.toInt()
  }
  fun bool(k: String, default: Boolean? = null): Boolean { val v = if(o.has(k)) o.get(k) else default; require(v is Boolean); return v }
  return when(str("type")) {
   "platform" -> {
    fields("action","argument","percent","content")
    val action = PlatformAction.valueOf(str("action"))
    val argument = if(o.has("argument")) str("argument") else null
    val content = if(o.has("content")) str("content") else null
    val percent = if(o.has("percent")) integer("percent",0..100) else null
    require((percent != null) == (action == PlatformAction.BRIGHTNESS))
    val targetActions = setOf(PlatformAction.CALL_DIRECT,PlatformAction.MESSAGE_SEND,PlatformAction.ROUTINE_RUN,PlatformAction.CALENDAR_QUERY,PlatformAction.CALENDAR_UPDATE,PlatformAction.CALENDAR_DELETE,PlatformAction.FILE_OPEN,PlatformAction.FILE_SEARCH,PlatformAction.FILE_SHARE,PlatformAction.FILE_RENAME,PlatformAction.FILE_COPY,PlatformAction.FILE_MOVE,PlatformAction.FILE_DELETE,PlatformAction.ALARM_DISMISS,PlatformAction.CONTACT_SEARCH,PlatformAction.CONTACT_PHONES,PlatformAction.APP_SETTINGS,PlatformAction.MESSAGE_OPEN,PlatformAction.MESSAGE_DRAFT,PlatformAction.NOTIFICATION_OPEN,PlatformAction.NOTIFICATION_DISMISS)
    val optionalTargets=setOf(PlatformAction.FILE_OPEN,PlatformAction.FILE_SEARCH,PlatformAction.FILE_SHARE,PlatformAction.FILE_COPY,PlatformAction.FILE_MOVE,PlatformAction.FILE_DELETE)
    require(if(action in optionalTargets) true else (argument != null) == (action in targetActions))
    val requiredContent=setOf(PlatformAction.MESSAGE_SEND,PlatformAction.MESSAGE_DRAFT,PlatformAction.TEXT_SHARE,PlatformAction.FILE_RENAME)
    val optionalContent=CalendarSelection.actions
    require(if(action in requiredContent) content != null else action in optionalContent || content == null)
    if(action==PlatformAction.FILE_RENAME) require(content !in setOf(".","..") && content!!.none {it=='/' || it=='\\' || it.code<32})
    if(action in setOf(PlatformAction.CALL_DIRECT,PlatformAction.MESSAGE_SEND,PlatformAction.MESSAGE_OPEN,PlatformAction.MESSAGE_DRAFT)) require(PhoneNumbers.canonical(argument!!) == argument)
    PhoneCommand.Platform(action,argument,percent,content).also { command ->
      if (action in CalendarSelection.actions) CalendarSelection.decode(command)
    }
   }
   "timer" -> { fields("seconds","label"); PhoneCommand.Timer(integer("seconds",1..Int.MAX_VALUE), str("label", "")) }
   "alarm" -> { fields("hour","minute","days","label","date");
    val date=if(o.has("date")) str("date").also { value ->
      require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
      require(java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).apply {isLenient=false}.parse(value)!=null)
    } else null;
    require(date==null || !o.has("days") || o.getJSONArray("days").length()==0);
 require(!o.has("days") || o.get("days") is JSONArray); val days = o.optJSONArray("days") ?: JSONArray(); require(days.length() <= 7); val values = (0 until days.length()).map { require(days.get(it) is Number); val n=days.getInt(it); require(n in 1..7 && days.getDouble(it) == n.toDouble()); n }; PhoneCommand.Alarm(integer("hour",0..23),integer("minute",0..59),values.distinct(),str("label", ""),date) }
   "volume" -> { fields("operation","percent"); val op=VolumeOp.valueOf(str("operation"));require(op!=VolumeOp.SET || o.has("percent")); PhoneCommand.Volume(op,integer("percent",0..100,0)) }
   "torch" -> { fields("enabled"); PhoneCommand.Torch(bool("enabled")) }
   "open_app" -> { fields("name"); PhoneCommand.OpenApp(str("name")) }
   "navigate" -> { fields("destination","mode"); PhoneCommand.Navigate(str("destination"),TravelMode.valueOf(str("mode","DRIVING"))) }
   "maps" -> { fields("query"); PhoneCommand.MapSearch(str("query")) }
   "dial" -> { fields("target"); PhoneCommand.Dial(str("target")) }
   "calendar" -> { fields("title","beginMillis","endMillis","description"); val begin=num("beginMillis"); val end=num("endMillis"); require(begin > 0 && end > begin && end-begin <= Int.MAX_VALUE.toLong()*1000); PhoneCommand.CalendarEvent(str("title"),begin,end,if(o.has("description")) str("description") else null) }
   "camera" -> { fields("video"); PhoneCommand.Camera(bool("video",false)) }
   "youtube" -> { fields("query"); PhoneCommand.YouTubeSearch(str("query")) }
   "spotify" -> { fields("query"); PhoneCommand.SpotifySearch(str("query")) }
   "music" -> { fields("query", "app"); PhoneCommand.MusicSearch(str("query"), if (o.has("app")) MediaApp.valueOf(str("app")) else null) }
   "stopwatch" -> { fields("operation"); PhoneCommand.Stopwatch(StopwatchOp.valueOf(str("operation"))) }
   "clock_list" -> { fields("alarms"); PhoneCommand.ClockList(bool("alarms")) }
   "settings" -> { fields("kind"); val k=str("kind"); require(k in listOf("wifi","bluetooth","internet","conectividad","ajustes")); PhoneCommand.Settings(k) }
   "save_place" -> { fields("alias","address"); val a=str("alias"); require(a in listOf("casa","trabajo")); PhoneCommand.SavePlace(a,str("address")) }
   "media" -> { fields("operation","packageName"); val pkg=if(o.has("packageName")) str("packageName") else null; require(pkg == null || Regex("[a-zA-Z][a-zA-Z0-9_.]+").matches(pkg)); PhoneCommand.Media(MediaOp.valueOf(str("operation")),pkg) }
   "temporary_mode" -> { fields("kind","seconds","percent","approximate"); val kind=ModeKind.valueOf(str("kind"));require(kind!=ModeKind.MEDIA_VOLUME || o.has("percent")); PhoneCommand.TemporaryMode(kind,integer("seconds",1..86400),integer("percent",0..100,0),bool("approximate",false)) }
   "end_mode" -> { fields(); PhoneCommand.EndMode }
   else -> throw IllegalArgumentException("Capacidad no admitida")
  }
 }
 fun summary(command: PhoneCommand): String = when(command) {
  is PhoneCommand.Platform -> {
    val calendar = if (command.action in CalendarSelection.actions) CalendarSelection.decode(command) else null
    val content = if (calendar == null) command.content else listOfNotNull(
      calendar.from,
      calendar.until?.let { "hasta $it (excluido)" },
      calendar.at,
      "por la tarde".takeIf { calendar.afternoon },
      "próximos eventos".takeIf { calendar.next },
      calendar.newDate?.let { "nueva fecha $it" },
      calendar.newTime?.let { "nueva hora $it" },
      calendar.legacyBegin?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) },
      calendar.durationSeconds?.let { "duración $it s" },
    ).joinToString(" · ").takeIf(String::isNotEmpty)
    (LocalIntentRegistry.default.definitions.firstOrNull { it.accepts(command) }?.description ?: "Acción del dispositivo") +
      (listOfNotNull(if (calendar == null) command.argument else calendar.title,command.percent?.let {"$it%"},content).takeIf {it.isNotEmpty()}?.joinToString(prefix=" · ",separator=" · ") ?: "")
  }
  is PhoneCommand.CalendarEvent -> "Calendario: ${command.title} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(command.beginMillis))} · ${(command.endMillis-command.beginMillis)/60000} min" + (command.description?.let { " · $it" } ?: "")
  is PhoneCommand.Timer -> "Temporizador: ${command.seconds / 60} min ${command.seconds % 60} s ${command.label}"
  is PhoneCommand.Media -> {
    val operation = when (command.operation) {
      MediaOp.PLAY -> "continuar"
      MediaOp.PAUSE -> "pausar"
      MediaOp.STOP -> "parar"
      MediaOp.NEXT -> "siguiente"
      MediaOp.PREVIOUS -> "anterior"
      MediaOp.STATUS -> "consultar"
    }
    val app = command.packageName?.let { pkg -> MediaApp.entries.firstOrNull { it.packageName == pkg }?.label ?: pkg }
    "Multimedia: $operation" + (app?.let { " en $it" } ?: "")
  }
  is PhoneCommand.TemporaryMode -> "${when(command.kind) {ModeKind.MEDIA_VOLUME -> "Volumen multimedia al ${command.percent}%"; ModeKind.SILENT -> "Silenciar llamadas y notificaciones"; ModeKind.DND -> "No molestar"}} durante ${command.seconds / 60} min · restauración ${if(command.approximate) "aproximada" else "programada"}"
  is PhoneCommand.OpenApp -> "Abrir ${command.name}"
  is PhoneCommand.Navigate -> "Ruta a ${command.destination} · ${when(command.mode){TravelMode.DRIVING->"en auto";TravelMode.WALKING->"a pie";TravelMode.BICYCLING->"en bicicleta";TravelMode.TRANSIT->"en transporte público"}}"
  is PhoneCommand.Volume -> "Volumen multimedia: ${when(command.operation){VolumeOp.UP->"subir un paso";VolumeOp.DOWN->"bajar un paso";VolumeOp.SET->"${command.percent}%"}}"
  is PhoneCommand.Torch -> if(command.enabled) "Encender linterna" else "Apagar linterna"
  is PhoneCommand.Alarm -> "Alarma: %02d:%02d".format(command.hour,command.minute) + (command.date?.let {" · $it"} ?: "") + if(command.days.isNotEmpty()) " · ${command.days.joinToString {listOf("", "domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado").getOrElse(it){"día inválido"}}}" else ""
  is PhoneCommand.ClockList -> if(command.alarms) "Mostrar alarmas" else "Mostrar temporizadores"
  is PhoneCommand.Stopwatch -> "Cronómetro: ${when(command.operation){StopwatchOp.START->"iniciar";StopwatchOp.PAUSE->"pausar";StopwatchOp.RESUME->"continuar";StopwatchOp.RESET->"reiniciar";StopwatchOp.STATUS->"mostrar"}}"
  is PhoneCommand.MapSearch -> "Buscar en Maps: ${command.query}"
  is PhoneCommand.SavePlace -> "Guardar ${command.alias}: ${command.address}"
  is PhoneCommand.Dial -> "Preparar llamada a ${command.target}"
  is PhoneCommand.Camera -> if(command.video) "Abrir cámara de video" else "Abrir cámara de fotos"
  is PhoneCommand.YouTubeSearch -> "Buscar en YouTube: ${command.query}"
  is PhoneCommand.SpotifySearch -> "Buscar en Spotify: ${command.query}"
  is PhoneCommand.MusicSearch -> command.app?.let { "Reproducir ${command.query} en ${it.label}" } ?: "Buscar música: ${command.query}"
  is PhoneCommand.Settings -> "Abrir ajustes de ${command.kind}"
  PhoneCommand.EndMode -> "Terminar modo temporal y restaurar ahora"
 }
}
