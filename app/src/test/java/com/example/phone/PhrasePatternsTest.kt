package com.example.phone

import com.example.phone.PhrasePatterns.Action
import com.example.phone.PhrasePatterns.Match
import com.example.phone.PhrasePatterns.Pattern
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasePatternsTest {
  private fun now() = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, 20, 6, 0) }
  private fun key(text: String) = PhoneCommandParser.normalize(text)
  private fun observe(text: String, command: PhoneCommand) = PhrasePatterns.observe(key(text), command, ::now)
  private fun index(vararg patterns: Pattern) = PhrasePatterns.Index(patterns.toList())
  private fun match(index: PhrasePatterns.Index, text: String) = index.match(key(text), ::now)

  @Test fun generalizaTresHorasDistintasEnUnaSolaEstructura() {
    val observations = listOf(
      observe("Tirame una alarma para las 8", PhoneCommand.Alarm(8, 0)),
      observe("Tirame una alarma para las 9 de la noche", PhoneCommand.Alarm(21, 0)),
      observe("Tirame una alarma para las 7:30", PhoneCommand.Alarm(7, 30)),
    )
    observations.forEach { assertNotNull(it) }
    assertEquals(setOf("tirame una alarma para las {hora}"), observations.map { it!!.template }.toSet())
    assertEquals(listOf("08:00", "21:00", "07:30"), observations.map { it!!.value })
    assertEquals(listOf("8", "9 de la noche", "7:30"), observations.map { it!!.example })

    val learned = index(Pattern("tirame una alarma para las ", "", Action.ALARM))
    assertEquals(Match.Command(PhoneCommand.Alarm(6, 45), "tirame una alarma para las {hora}"),
      match(learned, "Tirame una alarma para las 6:45"))
    assertEquals(PhoneCommand.Alarm(22, 15), (match(learned, "tirame una alarma para las diez y cuarto de la noche") as Match.Command).command)
  }

  @Test fun unaHoraSinPeriodoPideAclaracionConLaFraseQueEntiendeElParser() {
    val learned = index(Pattern("tirame una alarma para las ", "", Action.ALARM))
    val clarify = match(learned, "tirame una alarma para las 8") as Match.Clarify
    assertEquals("alarm", clarify.context)
    assertEquals("poner una alarma a las 8", clarify.source)
    assertTrue(PhoneCommandParser(::now).parse(clarify.source) is PhoneRoute.Clarify)
  }

  @Test fun generalizaDuracionesYPorcentajes() {
    val timers = listOf(
      observe("avisame en 10 minutos", PhoneCommand.Timer(600)),
      observe("avisame en cinco minutos", PhoneCommand.Timer(300)),
      observe("avisame en una hora", PhoneCommand.Timer(3600)),
    )
    assertEquals(setOf("avisame en {duracion}"), timers.map { it!!.template }.toSet())
    val volume = listOf(
      observe("música al 40", PhoneCommand.Volume(VolumeOp.SET, 40)),
      observe("música al 55 por ciento", PhoneCommand.Volume(VolumeOp.SET, 55)),
      observe("música al 70%", PhoneCommand.Volume(VolumeOp.SET, 70)),
    )
    assertEquals(setOf("musica al {porcentaje}"), volume.map { it!!.template }.toSet())

    val learned = index(Pattern("avisame en ", "", Action.TIMER), Pattern("musica al ", "", Action.VOLUME))
    assertEquals(PhoneCommand.Timer(1500), (match(learned, "Avisame en 25 minutos") as Match.Command).command)
    assertEquals(PhoneCommand.Timer(5400), (match(learned, "avisame en una hora y 30 minutos") as Match.Command).command)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 25), (match(learned, "Música al 25") as Match.Command).command)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 0), (match(learned, "música al cero por ciento") as Match.Command).command)
  }

  @Test fun validaHorasDuracionesYPorcentajesConLosParsersExistentes() {
    val learned = index(Pattern("tirame una alarma para las ", "", Action.ALARM),
      Pattern("avisame en ", "", Action.TIMER), Pattern("musica al ", "", Action.VOLUME))
    for (text in listOf(
      "tirame una alarma para las 25", "tirame una alarma para las 8:75", "tirame una alarma para las hola",
      "tirame una alarma para las 13 de la tarde",
      "avisame en un rato", "avisame en 0 minutos", "avisame en diez",
      "música al 140", "música al máximo", "música al -5",
    )) assertNull(text, match(learned, text))
    assertTrue(PhrasePatterns.shapeAllowed(Action.ALARM, "siete y media de la manana"))
    assertFalse(PhrasePatterns.shapeAllowed(Action.ALARM, "8 manana"))
    assertTrue(PhrasePatterns.shapeAllowed(Action.TIMER, "2 horas y 5 minutos"))
    assertFalse(PhrasePatterns.shapeAllowed(Action.TIMER, "10 minutos para el horno"))
    assertTrue(PhrasePatterns.shapeAllowed(Action.VOLUME, "cincuenta por ciento"))
    assertFalse(PhrasePatterns.shapeAllowed(Action.VOLUME, "101"))
  }

  @Test fun parametrosAdicionalesNoAdmitidosNuncaActivanLaEstructura() {
    val learned = index(Pattern("tirame una alarma para las ", "", Action.ALARM), Pattern("avisame en ", "", Action.TIMER))
    for (text in listOf(
      "tirame una alarma para las 8 llamada gimnasio",
      "tirame una alarma para las 8 todos los días",
      "tirame una alarma para las 8 mañana",
      "tirame una alarma para las 8 de la mañana del lunes",
      "avisame en 10 minutos que salga",
      "avisame en 10 minutos para el horno",
      "che tirame una alarma para las 7:30",
    )) assertNull(text, match(learned, text))
  }

  @Test fun noAprendeEstructurasDemasiadoAmpliasNiValoresQueNoEstanEnElTexto() {
    assertNull(observe("a las 8", PhoneCommand.Alarm(8, 0)))
    assertNull(observe("alarma 8", PhoneCommand.Alarm(8, 0)))
    assertNull(observe("en 10 minutos", PhoneCommand.Timer(600)))
    // Gemini propuso otro valor: no se generaliza una estructura que el parser no puede comprobar.
    assertNull(observe("tirame una alarma para las 8", PhoneCommand.Alarm(9, 0)))
    assertNull(observe("avisame en 10 minutos", PhoneCommand.Timer(900)))
    // Otro número en el texto literal sería un parámetro ignorado.
    assertNull(observe("tirame 2 alarmas para las 7:30", PhoneCommand.Alarm(7, 30)))
    assertNull(observe("tirame una alarma para hoy a las 7:30", PhoneCommand.Alarm(7, 30)))
    // Etiquetas, días, fechas y acciones sensibles no se generalizan.
    assertNull(observe("tirame una alarma gimnasio a las 7:30", PhoneCommand.Alarm(7, 30, label = "gimnasio")))
    assertNull(observe("tirame una alarma a las 7:30 siempre", PhoneCommand.Alarm(7, 30, days = listOf(2))))
    assertNull(observe("avisame en 10 minutos horno", PhoneCommand.Timer(600, "horno")))
    assertNull(observe("mandale a Ana las 8", PhoneCommand.Platform(PlatformAction.MESSAGE_SEND, "Ana", content = "las 8")))
    assertNull(observe("llamá al 8", PhoneCommand.Dial("8")))
    assertNull(observe("borrá el archivo 8", PhoneCommand.Platform(PlatformAction.FILE_DELETE, "8")))
    assertNull(observe("subí un poco la música", PhoneCommand.Volume(VolumeOp.UP)))
  }

  @Test fun estructurasEnConflictoNoEjecutanNada() {
    val conflicting = index(Pattern("dejalo en ", "", Action.ALARM), Pattern("dejalo en ", "", Action.VOLUME))
    // «8» es una hora ambigua y también un porcentaje: dos lecturas distintas.
    assertNull(match(conflicting, "dejalo en 8"))
    // Solo una de las dos acepta la forma «7:30».
    assertEquals(PhoneCommand.Alarm(7, 30), (match(conflicting, "dejalo en 7:30") as Match.Command).command)
    assertNull(index(Pattern("dejalo en ", "", Action.ALARM, enabled = false)).match("dejalo en 7:30"))
  }

  @Test fun elIndiceSoloConsideraLaPrimeraPalabraYRespetaElSufijo() {
    val learned = index(Pattern("tirame una alarma para las ", " porfa", Action.ALARM))
    assertEquals(PhoneCommand.Alarm(7, 30), (match(learned, "tirame una alarma para las 7:30 porfa") as Match.Command).command)
    assertNull(match(learned, "tirame una alarma para las 7:30"))
    assertNull(match(learned, "poneme una alarma para las 7:30 porfa"))
    assertEquals(0, PhrasePatterns.Index(emptyList()).size)
  }
}
