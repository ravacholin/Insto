package com.example.phone

import org.json.JSONArray
import org.json.JSONObject

/** Semantic catalogue only. Android executors remain the authority for permissions and effects. */
object CapabilityCatalog {
  data class Field(val kind: String, val values: List<String> = emptyList(), val min: Long? = null, val max: Long? = null) {
    fun schema(): JSONObject = JSONObject().put("type", kind).apply {
      if (values.isNotEmpty()) put("enum", JSONArray(values))
      if (min != null) put("minimum", min)
      if (max != null) put("maximum", max)
      if (kind == "string") put("maxLength", 2000)
      if (kind == "array") put("items", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7)).put("maxItems", 7)
    }
  }
  data class Spec(val type: String, val fields: Map<String, Field>, val required: Set<String>) {
    fun schema(partial: Boolean = false): JSONObject {
      val properties = JSONObject().put("type", Field("string", listOf(type)).schema())
      fields.forEach { (name, field) -> properties.put(name, field.schema()) }
      return JSONObject().put("type", "object").put("properties", properties)
        .put("required", JSONArray(listOf("type") + if (partial) emptyList() else required.toList()))
        .put("additionalProperties", false)
    }
  }
  private fun text(vararg values: String) = Field("string", values.toList())
  private fun integer(min: Long = 0, max: Long = Int.MAX_VALUE.toLong()) = Field("integer", min = min, max = max)
  private fun spec(type: String, required: String, vararg fields: Pair<String, Field>) =
    Spec(type, fields.toMap(), required.split(' ').filter { it.isNotEmpty() }.toSet())

  val specs = listOf(
    spec("timer", "seconds", "seconds" to integer(1), "label" to text()),
    spec("alarm", "hour minute", "hour" to integer(0, 23), "minute" to integer(0, 59), "period" to text("AM", "PM", "24H"), "days" to Field("array"), "label" to text()),
    spec("volume", "operation", "operation" to text("UP", "DOWN", "SET"), "percent" to integer(0, 100)),
    spec("torch", "enabled", "enabled" to Field("boolean")),
    spec("open_app", "name", "name" to text()),
    spec("navigate", "destination", "destination" to text(), "mode" to text("DRIVING", "WALKING", "BICYCLING", "TRANSIT")),
    spec("maps", "query", "query" to text()),
    spec("dial", "target", "target" to text()),
    spec("calendar", "title date time", "title" to text(), "date" to text(), "time" to text(), "durationSeconds" to integer(1)),
    spec("camera", "video", "video" to Field("boolean")),
    spec("youtube", "query", "query" to text()),
    spec("spotify", "query", "query" to text()),
    spec("music", "query", "query" to text()),
    spec("stopwatch", "operation", "operation" to text("START", "PAUSE", "RESUME", "RESET", "STATUS")),
    spec("clock_list", "alarms", "alarms" to Field("boolean")),
    spec("settings", "kind", "kind" to text("wifi", "bluetooth", "internet", "conectividad", "ajustes")),
    spec("media", "operation", "operation" to text("PLAY", "PAUSE", "NEXT", "PREVIOUS", "STATUS")),
    spec("temporary_mode", "kind seconds", "kind" to text("MEDIA_VOLUME", "SILENT", "DND"), "seconds" to integer(1, 86400), "percent" to integer(0, 100)),
    spec("end_mode", ""),
  ).associateBy { it.type }

  val fixed: Map<String, PhoneCommand> = linkedMapOf(
    "torch_on" to PhoneCommand.Torch(true), "torch_off" to PhoneCommand.Torch(false),
    "volume_up" to PhoneCommand.Volume(VolumeOp.UP), "volume_down" to PhoneCommand.Volume(VolumeOp.DOWN),
    "camera_photo" to PhoneCommand.Camera(), "camera_video" to PhoneCommand.Camera(true),
    "stopwatch_start" to PhoneCommand.Stopwatch(StopwatchOp.START), "stopwatch_pause" to PhoneCommand.Stopwatch(StopwatchOp.PAUSE),
    "stopwatch_resume" to PhoneCommand.Stopwatch(StopwatchOp.RESUME), "stopwatch_reset" to PhoneCommand.Stopwatch(StopwatchOp.RESET),
    "stopwatch_show" to PhoneCommand.Stopwatch(StopwatchOp.STATUS),
    "alarms_show" to PhoneCommand.ClockList(true), "timers_show" to PhoneCommand.ClockList(false),
    "media_play" to PhoneCommand.Media(MediaOp.PLAY), "media_pause" to PhoneCommand.Media(MediaOp.PAUSE),
    "media_next" to PhoneCommand.Media(MediaOp.NEXT), "media_previous" to PhoneCommand.Media(MediaOp.PREVIOUS),
    "media_status" to PhoneCommand.Media(MediaOp.STATUS), "end_mode" to PhoneCommand.EndMode,
  )
  val parameterChoices = linkedMapOf(
    "timer" to "Crear temporizador; duración y etiqueta si se indica.",
    "alarm" to "Crear alarma; hora y período explícitos, repetición opcional; sin fecha especial.",
    "navigate" to "Navegar a destino en Maps; modo opcional, sin restricciones de ruta.",
    "maps" to "Buscar lugares en Maps.", "dial" to "Preparar llamada a nombre o número, sin llamar automáticamente.",
    "calendar" to "Preparar evento con título, fecha, hora y duración.",
    "youtube" to "Buscar en YouTube.", "music" to "Buscar música; no confundir con controlar reproducción actual.",
    "volume_set" to "Ajustar volumen multimedia a porcentaje explícito.",
    "open_app" to "Abrir aplicación por su nombre.", "settings" to "Abrir ajustes de conectividad; no cambiar conectividad automáticamente.",
    "temporary_mode" to "Aplicar volumen multimedia/silencio/No molestar por duración explícita y restaurar.",
    "workflow" to "Secuencia explícita de dos a diez acciones admitidas para hacer ahora; sin condiciones ni pasos omitidos.",
  )
  val choices: Map<String, String> = LinkedHashMap(LocalIntentRegistry.default.choices).apply {
    fixed.forEach { (key, command) -> put(key, PhoneCommandCodec.summary(command) + "; sin parámetros ni condiciones adicionales.") }
    putAll(parameterChoices)
    put("unsupported", "Fuera del catálogo, negación, condición o detalles no representables. Un dato faltante conserva la categoría de acción para pedir aclaración.")
  }

  fun accepts(choice: String, command: PhoneCommand): Boolean = when {
    choice == "workflow" -> true
    choice in fixed -> fixed[choice] == command
    choice == "volume_set" -> command is PhoneCommand.Volume && command.operation == VolumeOp.SET
    else -> LocalIntentRegistry.default.definitions.firstOrNull { it.id == choice }?.accepts?.invoke(command)
      ?: (PhoneCommandCodec.encode(command).getString("type") == choice)
  }

  fun commandSchema(partial: Boolean = false): JSONObject {
    val variants = specs.values.flatMap { spec -> when (spec.type) {
      "volume" -> listOf(
        spec.copy(fields = spec.fields + ("operation" to text("UP", "DOWN"))),
        spec.copy(fields = spec.fields + ("operation" to text("SET")), required = spec.required + "percent"))
      "temporary_mode" -> listOf(
        spec.copy(fields = spec.fields + ("kind" to text("SILENT", "DND"))),
        spec.copy(fields = spec.fields + ("kind" to text("MEDIA_VOLUME")), required = spec.required + "percent"))
      else -> listOf(spec)
    } }
    return JSONObject().put("anyOf", JSONArray(variants.map { it.schema(partial) }))
  }
}
