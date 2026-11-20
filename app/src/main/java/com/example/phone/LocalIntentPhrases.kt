package com.example.phone

/** Complete phrases for actions that need no additional parameters. */
internal object LocalIntentPhrases {
  private val silent = listOf("silencio", "silenciar", "silencioso")
  private val vibration = listOf("vibrar", "vibración", "vibrador")

  val alternatives = mapOf(
    "alarms_show" to listOf("mis alarmas", "ver alarmas", "consultar las alarmas", "ver mis despertadores"),
    "timers_show" to listOf("mis temporizadores", "ver mis timers", "mostrar las cuentas regresivas", "mostrame mis cuentas regresivas"),
    "alarm_snooze" to listOf("posponer alarma", "aplazar la alarma", "posponer el despertador"),
    "timer_dismiss" to listOf("cancelar temporizador", "detener el timer", "parar el temporizador", "cancelar la cuenta atrás"),
    "stopwatch_start" to listOf("poner en marcha el cronómetro", "activar el cronómetro", "empezá a contar el tiempo transcurrido"),
    "stopwatch_pause" to listOf("detener el cronómetro", "parar cronómetro", "detené el conteo del cronómetro"),
    "stopwatch_resume" to listOf("seguir con el cronómetro", "reanudar el cronómetro"),
    "stopwatch_reset" to listOf("poner a cero el cronómetro", "resetear cronómetro"),
    "stopwatch_show" to listOf("ver cronómetro", "consultar el cronómetro", "mostrame cuánto va el cronómetro"),
    "alarm_update" to listOf("editar la alarma", "cambiar el despertador"),
    "timer_update" to listOf("editar el temporizador", "cambiar el timer"),
    "timer_pause" to listOf("pausar el temporizador", "pausar el timer"),
    "timer_resume" to listOf("continuar el temporizador", "reanudar el timer"),
    "calendar_agenda" to listOf("mi agenda", "ver calendario", "mostrar el calendario", "mostrar mi agenda", "abrir calendario"),
    "dialer_open" to listOf("abrir teclado de llamadas", "abrir el teclado de llamadas", "abrir marcador", "mostrar el marcador"),
    "files_browse" to listOf("mis archivos", "ver archivos", "abrir el explorador de archivos", "mostrar mis archivos"),
    "notification_list" to listOf("mis notificaciones", "ver notificaciones", "leer mis notificaciones", "mostrar notificaciones", "qué notificaciones hay"),
    "torch_on" to listOf("linterna", "linterna encendida", "enciende la luz de la linterna", "poner la linterna"),
    "torch_off" to listOf("linterna apagada", "apaga la luz de la linterna", "quitar la linterna"),
    "volume_up" to listOf("más volumen", "volumen arriba", "subir el sonido", "subir el volumen multimedia", "un poquito más de sonido",
      "subí un poco el volumen", "subí un poquito el volumen", "subí un poco el sonido"),
    "volume_down" to listOf("menos volumen", "volumen abajo", "bajar el sonido", "bajar el volumen multimedia", "un poquito menos de sonido", "bajá un poquito el sonido",
      "bajá un poco el volumen", "bajá un poquito el volumen", "bajá un poco el sonido"),
    "settings_wifi" to settings("wifi") + listOf("conectar el wifi", "mostrame la configuración del wifi"),
    "settings_bluetooth" to settings("bluetooth") + listOf("conectar el bluetooth"),
    "settings_internet" to settings("internet") + listOf("ajustes de red", "abrir ajustes de red"),
    "settings_open" to listOf("ajustes", "configuración", "abrir configuración", "configuración del teléfono"),
    "silent_on" to modePhrases(silent) + listOf(
      "silenciar teléfono", "silenciar el teléfono", "silenciar el celular", "silenciar el móvil", "silenciar el celu",
      "dejar el teléfono en silencio", "dejar el celular en silencio", "dejarlo en silencio", "en silencio", "sin sonido"),
    "silent_off" to listOf("modo normal", "modo sonido", "quitar el silencio", "activar el sonido",
      "volver a activar el sonido", "poner en modo normal", "poner el teléfono en modo normal", "desilenciar") +
      leaveModePhrases(silent + vibration),
    "vibrate_on" to modePhrases(vibration) + listOf("ponerlo a vibrar", "poner el teléfono a vibrar"),
    "dnd_on" to modePhrases(listOf("no molestar")),
    "dnd_off" to listOf("desactivar el no molestar", "quitar el no molestar", "sacar el no molestar") +
      leaveModePhrases(listOf("no molestar")),
    "media_play" to listOf("seguir escuchando", "continuar la música", "reanudar música", "dale play", "play", "seguí con lo que estaba escuchando"),
    "media_pause" to listOf("pausar música", "pausar la canción", "pausar audio", "pausa"),
    "media_stop" to listOf("parar música", "detener el audio", "dejar de reproducir", "stop"),
    "media_next" to listOf("siguiente canción", "próxima pista", "salta esta canción", "pasá al tema que sigue"),
    "media_previous" to listOf("canción anterior", "pista anterior", "volver a la canción anterior", "volvé a la canción previa"),
    "media_status" to listOf("qué está sonando", "qué canción está sonando", "qué canción suena", "qué estoy escuchando", "decime qué canción está puesta"),
    "camera_photo" to listOf("abrir cámara", "mostrar la cámara", "cámara de fotos"),
    "camera_video" to listOf("cámara de vídeo", "abrir cámara para grabar", "poner la cámara en modo vídeo", "mostrame la cámara en video"),
    "recorder_open" to listOf("grabadora", "abrir grabadora de voz", "mostrar la grabadora"),
    "camera_capture" to listOf("sacar una foto", "tomar una foto", "hacer una foto", "capturar foto"),
    "camera_record" to listOf("grabar un vídeo", "empezar a grabar un vídeo", "iniciar grabación de vídeo"),
    "recorder_start" to listOf("grabar audio", "grabar una nota de voz", "iniciar grabación de audio"),
    "recorder_stop" to listOf("detener la grabación", "parar la grabación de audio"),
    "battery" to listOf("batería", "nivel de batería", "porcentaje de batería", "cuánto me queda de batería", "cuánta batería tengo"),
    "accessibility_settings" to listOf("ajustes de accesibilidad", "configuración de accesibilidad", "abrir ajustes de accesibilidad"),
    "display_settings" to listOf("ajustes de pantalla", "configuración de pantalla", "abrir configuración de pantalla"),
    "temporary_mode_end" to listOf("terminar el modo temporal", "restaurar ahora", "restaurar el sonido", "volver al volumen anterior"),
    "routine_list" to listOf("mis rutinas", "listar rutinas", "ver rutinas"),
    "routine_import" to listOf("importar una rutina", "cargar rutinas desde un archivo"),
  )

  private fun settings(name: String) = listOf(
    "ajustes de $name", "configuración de $name", "abrir los ajustes de $name",
    "abrir configuración de $name", "mostrar ajustes de $name", "mostrar la configuración de $name",
  )

  private fun modePhrases(names: List<String>): List<String> = names.flatMap { name ->
    listOf(name, "modo $name", "modo de $name", "activar $name", "activar modo $name",
      "activar el modo $name", "activar el modo de $name", "poner $name") +
      listOf("poner", "ponerlo", "poner el teléfono", "poner el celular", "poner el móvil").flatMap { verb ->
        listOf("$verb en $name", "$verb en modo $name")
      }
  }

  private fun leaveModePhrases(names: List<String>) = names.flatMap { name ->
    listOf("desactivar modo", "desactivar el modo", "quitar modo", "quitar el modo", "salir del modo").flatMap { action ->
      listOf("$action $name", "$action de $name")
    }
  }
}
