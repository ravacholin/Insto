package com.example.phone

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalIntentVariantsTest {
  private val parser = PhoneCommandParser()

  @Test fun shortAndConversationalSoundCommandsWorkWithoutGemini() = runTest {
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ false }, { error("Decisions is disabled") }),
      StructuredCommandExtractor { error("Fallback is disabled") })
    val commands = mapOf(
      PlatformAction.SILENT_ON to listOf("Silenciar.", "Modo Silenciar", "Poner en modo silenciar",
        "¿Podrías ponerlo en silencio, por favor?", "Quiero que silencies el celular", "Dejá el teléfono en silencio"),
      PlatformAction.SILENT_OFF to listOf("Quitar el modo silencio", "Desactivá el modo silencioso", "Modo normal", "Volvé a activar el sonido"),
      PlatformAction.VIBRATE_ON to listOf("Vibración", "Pon el móvil en vibración", "Activa el modo vibrador"),
      PlatformAction.DND_ON to listOf("No molestar", "Modo no molestar", "Activá el modo no molestar"),
      PlatformAction.DND_OFF to listOf("Sacá el no molestar", "Quitar modo no molestar"),
    )
    for ((action, phrases) in commands) for (text in phrases) {
      assertEquals(text, InterpretationResult.Direct(PhoneCommand.Platform(action)),
        coordinator.interpret(InterpretationRequest(text), parser.parse(text)))
    }
  }

  @Test fun differentIntentFamiliesAcceptNaturalVariantsAndKeepTheirArguments() {
    val cases = mapOf(
      "Por favor, ¿puedes mostrarme mis alarmas?" to PhoneCommand.ClockList(true),
      "Configúrame una cuenta atrás de cinco minutos" to PhoneCommand.Timer(300),
      "Quisiera que me despiertes a las 07:30" to PhoneCommand.Alarm(7, 30),
      "Poné en marcha el cronómetro" to PhoneCommand.Stopwatch(StopwatchOp.START),
      "¿Me mostrás el calendario?" to PhoneCommand.Platform(PlatformAction.CALENDAR_AGENDA),
      "¿Me puedes marcar a José Pérez?" to PhoneCommand.Dial("José Pérez"),
      "Abrir teclado de llamadas" to PhoneCommand.Platform(PlatformAction.DIALER_OPEN),
      "Ajustes de Bluetooth" to PhoneCommand.Settings("bluetooth"),
      "Conecta el Wi-Fi" to PhoneCommand.Settings("wifi"),
      "Lanza Spotify" to PhoneCommand.OpenApp("spotify"),
      "Más volumen" to PhoneCommand.Volume(VolumeOp.UP),
      "Enciéndeme la luz de la linterna" to PhoneCommand.Torch(true),
      "Siguiente canción, porfa" to PhoneCommand.Media(MediaOp.NEXT),
      "¿Puedes detener la música de Spotify?" to PhoneCommand.Media(MediaOp.STOP, "com.spotify.music"),
      "¿Puedes buscarme Björk en Spotify?" to PhoneCommand.SpotifySearch("Björk"),
      "Ponme «De música ligera» en Spotify" to PhoneCommand.MusicSearch("De música ligera", MediaApp.SPOTIFY),
      "Guíame hasta Córdoba en bici" to PhoneCommand.Navigate("Córdoba", TravelMode.BICYCLING),
      "Encuentra en Maps Café París" to PhoneCommand.MapSearch("Café París"),
      "Buscame Café París en Google Maps" to PhoneCommand.MapSearch("Café París"),
      "Podrías encontrar el contacto José Pérez" to PhoneCommand.Platform(PlatformAction.CONTACT_SEARCH, "José Pérez"),
      "Por favor, quiero que copies el archivo Nómina.Final.pdf" to PhoneCommand.Platform(PlatformAction.FILE_COPY, "Nómina.Final.pdf"),
      "¿Cuánto me queda de batería?" to PhoneCommand.Platform(PlatformAction.BATTERY),
      "Quiero que bajes el brillo al 30%" to PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 30),
      "Mis notificaciones" to PhoneCommand.Platform(PlatformAction.NOTIFICATION_LIST),
      "Poner la cámara en modo vídeo" to PhoneCommand.Camera(true),
      "Sácame una foto" to PhoneCommand.Platform(PlatformAction.CAPTURE_PHOTO),
      "¿Podrías ejecutar mi rutina Estudio?" to PhoneCommand.Platform(PlatformAction.ROUTINE_RUN, "Estudio"),
      "Envíame un WhatsApp al +541112345678 con el texto ¡Llegué, José!" to
        PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, "+541112345678", content = "¡Llegué, José!"),
    )
    for ((text, command) in cases) assertEquals(text, PhoneRoute.Local(command), parser.parse(text))
    assertTrue(parser.parse("Quiero editar el temporizador") is PhoneRoute.Unsupported)
  }

  @Test fun durationsNeverBecomePermanentModes() {
    assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.SILENT, 1200)),
      parser.parse("Dejá el teléfono en silencio por veinte minutos"))
    assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.DND, 3600)),
      parser.parse("Modo no molestar durante una hora"))
    assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.DND, 3600)),
      parser.parse("No molestar por una hora"))
    assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.SILENT, 600)),
      parser.parse("Modo silenciar por diez minutos y después restauralo"))
    assertTrue(parser.parse("Modo silenciar durante un rato") is PhoneRoute.Clarify)
    assertFalse(parser.parse("Modo vibración durante diez minutos") is PhoneRoute.Local)
  }

  @Test fun fixedCatalogExamplesCanRunLocallyWithCourtesyAndPunctuation() {
    for (definition in LocalIntentRegistry.default.definitions) {
      val command = definition.fixed ?: continue
      for (example in definition.examples) {
        val text = "Por favor, $example."
        assertEquals("${definition.id}: $text", PhoneRoute.Local(command), parser.parse(text))
      }
    }
  }

  @Test fun literalMessageTextKeepsItsPunctuationAndCourtesyWords() {
    val number = "+541112345678"
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, number, content = "¡Llegué, José!")),
      parser.parse("¿Puedes enviarme un WhatsApp al $number con el texto ¡Llegué, José!"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.MESSAGE_DRAFT, number, content = "Avísame, por favor.")),
      parser.parse("Preparar un SMS para $number diciendo Avísame, por favor."))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.TEXT_SHARE, content = "Por favor.")),
      parser.parse("¿Podés compartir este texto: Por favor."))
  }

  @Test fun aSearchOnlyAssumesContactsWhenTheUserOrTheSelectedIntentSpecifiesIt() {
    assertEquals(PhoneRoute.Local(PhoneCommand.SpotifySearch("a Mercedes Sosa")),
      parser.parse("Buscá a Mercedes Sosa en Spotify"))
    assertEquals(PhoneRoute.Local(PhoneCommand.SpotifySearch("Contacto")),
      parser.parse("Buscá Contacto en Spotify"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.CONTACT_SEARCH, "Mercedes Sosa")),
      LocalIntentRegistry.default.resolve("contact_search", "Buscá a Mercedes Sosa"))
  }

  @Test fun aModelChoiceCannotTurnAnUnmetConditionOrSecondCommandIntoSilence() = runTest {
    val router = HybridPhoneRouter({ true }, { SemanticDecision("silent_on", .99, .99, .99, .99) })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor { error("Unexpected extraction") })
    for (text in listOf("Quiero que no silencies el teléfono", "Modo silencio por diez minutos y encender la linterna",
      "Modo silencio a las trece", "Silenciar en diez minutos", "Silenciar cuando llegue", "¿Cómo se activa el modo silencio?",
      "«Silenciar»", "Modo silencio y sacá una foto", "Modo silencio. Modo vibración", "Silenciar y siguiente canción.",
      "Temporizador de cinco minutos para fideos y encender la linterna", "Silenciar y temporizador de cinco minutos")) {
      assertFalse(text, parser.parse(text) is PhoneRoute.Local)
      assertFalse(text, coordinator.interpret(InterpretationRequest(text), parser.parse(text)) is InterpretationResult.Direct)
    }
  }
}
