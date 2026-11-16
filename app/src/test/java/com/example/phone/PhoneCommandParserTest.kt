package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneCommandParserTest {
  private val parser = PhoneCommandParser()
  @Test fun durations() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(600, "fideos")), parser.parse("Poné un timer de diez minutos para fideos"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(300, "")), parser.parse("¿Me ponés un temporizador de cinco minutos?"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(120, "")), parser.parse("Poneme un timer de dos minutos"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(5400, "")), parser.parse("temporizador de una hora y treinta minutos"))
    assertTrue(parser.parse("poné un timer de cero minutos") is PhoneRoute.Clarify)
    assertTrue(parser.parse("poné un timer") is PhoneRoute.Clarify)
  }
  @Test fun alarms() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Alarm(7, 30)), parser.parse("despertame a las siete y media de la mañana"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Alarm(19, 15)), parser.parse("alarma a las 7:15 de la tarde"))
    assertTrue(parser.parse("alarma a las siete") is PhoneRoute.Clarify)
    assertTrue(parser.parse("alarma para el 25 de diciembre a las 7") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("alarma mañana a las siete de la mañana") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("alarma el 25/12 a las 07:00") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("alarma a las 25:80") is PhoneRoute.Clarify)
    assertEquals(PhoneRoute.Local(PhoneCommand.Alarm(7, 0)), parser.parseReply("alarm", "a las siete de la mañana"))
  }
  @Test fun alarmMustConsumeEntireRequestAndRespectNegation() {
    assertFalse(parser.parseReply("alarm", "no pongas ninguna alarma a las siete de la mañana") is PhoneRoute.Local)
    assertFalse(parser.parseReply("alarm", "explicame una alarma a las siete de la mañana") is PhoneRoute.Local)
    assertFalse(parser.parse("quiero una alarma que no suene a las siete de la mañana") is PhoneRoute.Local)
    assertTrue(parser.parse("alarma a las 07:00 para dentro de dos semanas") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("alarma a las 07:00 todos los días excepto domingo") is PhoneRoute.Unsupported)
    assertEquals(PhoneRoute.Local(PhoneCommand.Alarm(0, 0)), parser.parse("alarma a las doce de la noche"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Alarm(7, 0, listOf(2, 5))), parser.parse("alarma a las 07:00 todos los lunes y jueves"))
  }
  @Test fun generalQueriesNeverExecute() {
    for (text in listOf("explicame cómo funciona un cronómetro", "no prendas la linterna", "qué es una alarma", "Instinct, ayudame a estudiar", "si pongo un timer qué pasa")) {
      assertEquals(text, PhoneRoute.Instinct, parser.parse(text))
    }
  }
  @Test fun localActions() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Stopwatch(StopwatchOp.START)), parser.parse("iniciá el cronómetro"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(true)), parser.parse("prendé la linterna"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, 50)), parser.parse("poné el volumen al cincuenta por ciento"))
    assertEquals(PhoneRoute.Local(PhoneCommand.OpenApp("spotify")), parser.parse("abrí Spotify"))
    assertTrue(parser.parse("cancelá el timer de fideos") is PhoneRoute.Unsupported)
    assertEquals(PhoneRoute.Local(PhoneCommand.Settings("wifi")), parser.parse("activá el wifi"))
  }
  @Test fun naturalFlashlightCommandsStayLocal() {
    for (text in listOf("encender la linterna", "enciende la linterna", "encendé la linterna", "encendeme la linterna", "¿Podés encender la linterna?", "Por favor, enciende la linterna.")) {
      assertEquals(text, PhoneRoute.Local(PhoneCommand.Torch(true)), parser.parse(text))
    }
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(false)), parser.parse("¿Podés apagar la linterna?"))
    assertFalse(parser.parse("necesito que enciendas la linterna") == PhoneRoute.Instinct)
  }
  @Test fun clarificationAndCancellation() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(180, "")), parser.parseReply("timer", "tres minutos"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(false)), parser.parseReply("alarm", "apagá la linterna"))
    assertEquals(PhoneRoute.Cancel, parser.parse("cancelar"))
  }
}
