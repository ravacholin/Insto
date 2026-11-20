package com.example.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalContextTest {
  private var now = 1_000L
  private val context = OperationalContext(clock = { now })
  private val spotify = MediaApp.SPOTIFY.packageName
  private val opened = PhoneActionResult("Abriendo Spotify.", outcome = PhoneActionOutcome.HANDOFF, launchedAppPackage = spotify)

  private fun music(text: String) = (context.resolve(text)?.route as? PhoneRoute.Local)?.command

  @Test fun abrirSpotifyYLuegoPonerUnArtistaUsaSpotify() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    val resolution = context.resolve("Ahora poné Soda Stereo")!!
    assertEquals(PhoneRoute.Local(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.SPOTIFY)), resolution.route)
    assertTrue(resolution.usedContext)
    assertEquals(PhoneCommand.MusicSearch("Cerati", MediaApp.SPOTIFY), music("Poneme Cerati"))
    assertEquals(PhoneCommand.MusicSearch("La Renga", MediaApp.SPOTIFY), music("Dale, poné La Renga ahí"))
    assertEquals(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.SPOTIFY), music("Ahora, poné Soda Stereo."))
    assertEquals(PhoneCommand.MusicSearch("Charly García", MediaApp.SPOTIFY), music("y ahora reproducí Charly García"))
    assertEquals(spotify, context.preferredMediaPackage())
  }

  @Test fun unaOrdenExplicitaDeMusicaSoloRecibeLaAppSiNoNombraOtra() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    assertEquals(PhoneCommand.MusicSearch("los Beatles", MediaApp.SPOTIFY), context.enrich(PhoneCommand.MusicSearch("los Beatles")))
    val youtube = PhoneCommand.MusicSearch("los Beatles", MediaApp.YOUTUBE)
    assertEquals(youtube, context.enrich(youtube))
    assertEquals(PhoneCommand.Volume(VolumeOp.DOWN), context.enrich(PhoneCommand.Volume(VolumeOp.DOWN)))
  }

  @Test fun unaReferenciaExplicitaNuevaPrevalece() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    context.record(PhoneCommand.YouTubeSearch("recitales"), PhoneActionResult("Búsqueda abierta en YouTube.", outcome = PhoneActionOutcome.HANDOFF))
    assertEquals(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.YOUTUBE), music("poné Soda Stereo"))
    // Abrir otra app que no es multimedia descarta la app multimedia anterior.
    context.record(PhoneCommand.OpenApp("whatsapp"), PhoneActionResult("Abriendo WhatsApp.", outcome = PhoneActionOutcome.HANDOFF,
      launchedAppPackage = "com.whatsapp"))
    assertNull(context.resolve("poné Soda Stereo"))
    assertNull(context.preferredMediaPackage())
    assertEquals(PhoneCommand.OpenApp("whatsapp", "com.whatsapp"), music("volvé a abrirla"))
  }

  @Test fun soloLasAperturasConPaqueteResueltoEstablecenIdentidad() {
    context.record(PhoneCommand.OpenApp("spotify"), PhoneActionResult("No encontré una aplicación llamada spotify.", outcome = PhoneActionOutcome.REJECTED))
    context.record(PhoneCommand.OpenApp("spotify"), PhoneActionResult("Encontré varias aplicaciones. Elegí una.",
      appOptions = listOf(AppOption("Spotify", spotify), AppOption("Spotify Lite", "com.spotify.lite")), outcome = PhoneActionOutcome.NEEDS_INPUT))
    // Un HANDOFF sin paquete lanzado no prueba qué app se abrió.
    context.record(PhoneCommand.OpenApp("spotify"), PhoneActionResult("Abriendo.", outcome = PhoneActionOutcome.HANDOFF))
    context.record(PhoneCommand.MusicSearch("Soda"), PhoneActionResult("Pedido de música enviado.", outcome = PhoneActionOutcome.HANDOFF))
    context.record(PhoneCommand.Media(MediaOp.PAUSE, spotify), PhoneActionResult("Sin sesión.", outcome = PhoneActionOutcome.REJECTED))
    context.record(PhoneCommand.Media(MediaOp.PAUSE, spotify), PhoneActionResult("Pudo haberse entregado.", outcome = PhoneActionOutcome.UNCERTAIN))
    assertNull(context.snapshot())
    assertNull(context.resolve("poné Soda Stereo"))
    // Un fallo posterior no borra una identidad ya comprobada.
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    context.record(PhoneCommand.OpenApp("youtube"), PhoneActionResult("No encontré.", outcome = PhoneActionOutcome.REJECTED))
    assertEquals(spotify, context.preferredMediaPackage())
  }

  @Test fun caducaALosCientoVeinteSegundos() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    now += 119_999
    assertEquals(spotify, context.preferredMediaPackage())
    now += 1
    assertNull(context.snapshot())
    assertNull(context.resolve("ahora poné Soda Stereo"))
  }

  @Test fun caducaTrasTresTurnosPertinentesYLosControlesIndependientesNoCuentan() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    repeat(5) { context.beginTurn(PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.DOWN))) }
    context.beginTurn(PhoneRoute.Local(PhoneCommand.Media(MediaOp.NEXT)))
    repeat(3) { context.beginTurn(PhoneRoute.Instinct) }
    assertEquals(spotify, context.preferredMediaPackage())
    context.beginTurn(PhoneRoute.Local(PhoneCommand.Alarm(8, 0)))
    assertNull(context.snapshot())
  }

  @Test fun usarElContextoConExitoLoRenueva() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    repeat(3) { context.beginTurn(PhoneRoute.Instinct) }
    context.record(PhoneCommand.MusicSearch("Soda Stereo", MediaApp.SPOTIFY), PhoneActionResult("Pedido de reproducción enviado a Spotify.", outcome = PhoneActionOutcome.HANDOFF))
    now += 100_000
    context.beginTurn(PhoneRoute.Instinct)
    assertEquals(spotify, context.preferredMediaPackage())
  }

  @Test fun pedidosAmbiguosNegadosOCompuestosSiguenElCircuitoNormal() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    for (text in listOf(
      "No pongas Soda Stereo", "¿Ponés Soda Stereo?", "Si llego, poné Soda Stereo", "Poné Soda Stereo y bajá el volumen",
      "Suponé que pongo Soda Stereo", "\"poné Soda Stereo\"", "poné la tele", "poné el despertador", "poné una alarma",
      "poné algo", "poné 5", "poné Soda Stereo en Netflix", "mandale un mensaje a Ana", "borralo", "llamalo",
      "abrí el archivo", "agendalo para mañana",
    )) assertNull(text, context.resolve(text)?.takeIf { it.usedContext })
  }

  @Test fun elMarcadorAhoraNoNecesitaContextoParaOrdenesCompletas() {
    assertEquals(PhoneCommand.Volume(VolumeOp.DOWN), music("Ahora bajá el volumen"))
    assertEquals(PhoneCommand.Torch(true), music("y ahora prendé la linterna"))
    val clarify = context.resolve("ahora poné una alarma a las 8")!!
    assertTrue(clarify.route is PhoneRoute.Clarify)
    assertEquals("pone una alarma a las 8", clarify.source)
    assertNull(context.resolve("ahora poné Soda Stereo"))
    assertNull(context.resolve("ahora no prendas la linterna"))
  }

  @Test fun cancelarOCerrarLimpiaElContexto() {
    context.record(PhoneCommand.OpenApp("spotify"), opened)
    context.clear()
    assertNull(context.snapshot())
    assertNull(context.resolve("volvé a abrirla"))
  }
}
