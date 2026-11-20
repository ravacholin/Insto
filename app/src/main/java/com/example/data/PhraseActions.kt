package com.example.data

import com.example.phone.MediaOp
import com.example.phone.PhoneCommand
import com.example.phone.PlatformAction
import com.example.phone.StopwatchOp
import com.example.phone.VolumeOp

/**
 * Actions offered by the phrase editor in Settings, so a phrase can be configured by tapping instead of
 * writing a request. Every option builds a command that [LearnedPhraseStore.allowsManual] accepts.
 */
object PhraseActions {
  enum class Input { NONE, PERCENT, MINUTES, APP, ROUTINE, PLACE, SEARCH }

  data class Option(
    val id: String,
    val group: String,
    val label: String,
    val input: Input = Input.NONE,
    private val make: (String) -> PhoneCommand?,
  ) {
    /** [value] is the typed number or text. Apps use [openApp] because they need the package. */
    fun build(value: String = ""): PhoneCommand? = make(value.trim())
  }

  private fun fixed(id: String, group: String, label: String, command: PhoneCommand) =
    Option(id, group, label) { command }

  private fun platform(id: String, group: String, label: String, action: PlatformAction) =
    fixed(id, group, label, PhoneCommand.Platform(action))

  private fun text(value: String) = value.takeIf { it.isNotEmpty() && it.length <= 200 }

  val options: List<Option> = listOf(
    fixed("torch_on", "Linterna", "Encender la linterna", PhoneCommand.Torch(true)),
    fixed("torch_off", "Linterna", "Apagar la linterna", PhoneCommand.Torch(false)),
    fixed("volume_up", "Volumen", "Subir el volumen", PhoneCommand.Volume(VolumeOp.UP)),
    fixed("volume_down", "Volumen", "Bajar el volumen", PhoneCommand.Volume(VolumeOp.DOWN)),
    Option("volume_set", "Volumen", "Poner el volumen en un porcentaje", Input.PERCENT) { value ->
      value.toIntOrNull()?.takeIf { it in 0..100 }?.let { PhoneCommand.Volume(VolumeOp.SET, it) }
    },
    platform("silent_on", "Sonido del teléfono", "Modo silencio", PlatformAction.SILENT_ON),
    platform("silent_off", "Sonido del teléfono", "Activar el sonido", PlatformAction.SILENT_OFF),
    platform("vibrate_on", "Sonido del teléfono", "Modo vibración", PlatformAction.VIBRATE_ON),
    platform("dnd_on", "Sonido del teléfono", "Activar No molestar", PlatformAction.DND_ON),
    platform("dnd_off", "Sonido del teléfono", "Desactivar No molestar", PlatformAction.DND_OFF),
    fixed("media_play", "Música y video", "Reproducir o continuar", PhoneCommand.Media(MediaOp.PLAY)),
    fixed("media_pause", "Música y video", "Pausar", PhoneCommand.Media(MediaOp.PAUSE)),
    fixed("media_next", "Música y video", "Siguiente", PhoneCommand.Media(MediaOp.NEXT)),
    fixed("media_previous", "Música y video", "Anterior", PhoneCommand.Media(MediaOp.PREVIOUS)),
    Option("spotify_search", "Música y video", "Buscar en Spotify", Input.SEARCH) { value ->
      text(value)?.let { PhoneCommand.SpotifySearch(it) }
    },
    Option("youtube_search", "Música y video", "Buscar en YouTube", Input.SEARCH) { value ->
      text(value)?.let { PhoneCommand.YouTubeSearch(it) }
    },
    Option("open_app", "Apps", "Abrir una app", Input.APP) { null },
    Option("routine_run", "Rutinas", "Ejecutar una rutina", Input.ROUTINE) { value ->
      text(value)?.let { PhoneCommand.Platform(PlatformAction.ROUTINE_RUN, it) }
    },
    fixed("camera_photo", "Cámara", "Abrir la cámara para foto", PhoneCommand.Camera(false)),
    fixed("camera_video", "Cámara", "Abrir la cámara para video", PhoneCommand.Camera(true)),
    Option("timer", "Reloj", "Poner un temporizador", Input.MINUTES) { value ->
      value.toIntOrNull()?.takeIf { it in 1..1440 }?.let { PhoneCommand.Timer(it * 60) }
    },
    fixed("alarms_show", "Reloj", "Mostrar las alarmas", PhoneCommand.ClockList(true)),
    fixed("timers_show", "Reloj", "Mostrar los temporizadores", PhoneCommand.ClockList(false)),
    fixed("stopwatch_start", "Reloj", "Iniciar el cronómetro", PhoneCommand.Stopwatch(StopwatchOp.START)),
    fixed("stopwatch_pause", "Reloj", "Pausar el cronómetro", PhoneCommand.Stopwatch(StopwatchOp.PAUSE)),
    fixed("stopwatch_reset", "Reloj", "Reiniciar el cronómetro", PhoneCommand.Stopwatch(StopwatchOp.RESET)),
    Option("navigate", "Mapas", "Ir a un lugar", Input.PLACE) { value ->
      text(value)?.let { PhoneCommand.Navigate(it) }
    },
    platform("battery", "Teléfono", "Decir el nivel de batería", PlatformAction.BATTERY),
    platform("notifications", "Teléfono", "Leer las notificaciones", PlatformAction.NOTIFICATION_LIST),
  )

  val groups: List<Pair<String, List<Option>>> = options.groupBy { it.group }.toList()

  fun openApp(label: String, packageName: String): PhoneCommand = PhoneCommand.OpenApp(label, packageName)

  /** The option that produced [command], to show it selected when editing. */
  fun optionFor(command: PhoneCommand): Option? = when (command) {
    is PhoneCommand.OpenApp -> options.first { it.id == "open_app" }
    is PhoneCommand.Volume -> if (command.operation == VolumeOp.SET) options.first { it.id == "volume_set" }
      else options.firstOrNull { it.input == Input.NONE && it.build() == command }
    is PhoneCommand.Timer -> options.first { it.id == "timer" }
    is PhoneCommand.SpotifySearch -> options.first { it.id == "spotify_search" }
    is PhoneCommand.YouTubeSearch -> options.first { it.id == "youtube_search" }
    is PhoneCommand.Navigate -> options.first { it.id == "navigate" }
    is PhoneCommand.Platform -> if (command.action == PlatformAction.ROUTINE_RUN) options.first { it.id == "routine_run" }
      else options.firstOrNull { it.input == Input.NONE && it.build() == command }
    else -> options.firstOrNull { it.input == Input.NONE && it.build() == command }
  }
}
