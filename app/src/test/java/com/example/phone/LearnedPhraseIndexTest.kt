package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedPhraseIndexTest {
  private fun now() = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.NOVEMBER, 20, 6, 0) }
  private fun key(text: String) = PhoneCommandParser.normalize(text)
  private fun item(text: String, command: PhoneCommand, manual: Boolean = false, generalize: Boolean = true) =
    LearnedPhraseIndex.Item(key(text), command, manual, generalize)
  private val apps = mapOf(
    "netflix" to PhoneCommand.OpenApp("netflix", "com.netflix.mediaclient"),
    "spotify" to PhoneCommand.OpenApp("spotify", "com.spotify.music"),
  )
  private fun LearnedPhraseIndex.find(text: String) = match(key(text), { apps[it] }, ::now)

  @Test fun aLearnedValueGeneralizesWithTheNativeValidation() {
    val index = LearnedPhraseIndex(listOf(item("bajá la música al 30", PhoneCommand.Volume(VolumeOp.SET, 30))))
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 50), index.find("bajá la música al 50")?.command)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 45), index.find("Bajame la música al cuarenta y cinco")?.command)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 40), index.find("che, bajar la musica al 40 porfa")?.command)
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 40), index.find("bajá la músca al 40")?.command)
    for (text in listOf("bajá la música al 150", "bajá la música al 30 mañana", "bajá la música al 30 y apagá la luz",
      "bajá la música al", "subí la música al 30", "bajá la música al treinta minutos")) {
      assertNull(text, index.find(text))
    }
    assertEquals("Bajá la música al {número}",
      LearnedPhraseIndex.describe(index.templates.values.single(), "Bajá la música al 30"))
  }

  @Test fun durationsTimesAndAppsUseTheirOwnSlots() {
    val index = LearnedPhraseIndex(listOf(
      item("poné el temporizador del fideo en 10 minutos", PhoneCommand.Timer(600), manual = true),
      item("despertame tempranito a las 6:30", PhoneCommand.Alarm(6, 30), manual = true),
      item("poneme la app de netflix", PhoneCommand.OpenApp("netflix", "com.netflix.mediaclient")),
    ))
    assertEquals(PhoneCommand.Timer(720), index.find("poné el temporizador del fideo en 12 minutos")?.command)
    assertEquals(PhoneCommand.Alarm(7, 15), index.find("despertame tempranito a las 7:15")?.command)
    assertNull("ambiguous hour is never guessed", index.find("despertame tempranito a las 7"))
    assertEquals(apps["spotify"], index.find("poneme la app de spotify")?.command)
    assertNull("unknown app", index.find("poneme la app de tiktok"))
    assertNull("not an app name", index.find("poneme la app de 2 cosas"))
  }

  @Test fun ambiguityTiesAndDisabledGeneralizationNeverExecute() {
    val collision = LearnedPhraseIndex(listOf(
      item("prendé modo foco", PhoneCommand.Torch(true)),
      item("prender modo foco", PhoneCommand.Platform(PlatformAction.SILENT_ON)),
    ))
    assertNull(collision.find("prendeme modo foco"))
    val off = LearnedPhraseIndex(listOf(item("bajá la música al 30", PhoneCommand.Volume(VolumeOp.SET, 30), generalize = false)))
    assertNull(off.find("bajá la música al 50"))
    val twoTemplates = LearnedPhraseIndex(listOf(
      item("dejalo tranqui en 20", PhoneCommand.Volume(VolumeOp.SET, 20), manual = true),
      item("dejalo tranqui en 5 minutos", PhoneCommand.Timer(300), manual = true),
    ))
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 30), twoTemplates.find("dejalo tranqui en 30")?.command)
    assertEquals(PhoneCommand.Timer(600), twoTemplates.find("dejalo tranqui en 10 minutos")?.command)
  }

  @Test fun manualTemplatesWinAndPhrasesBeatTemplates() {
    val index = LearnedPhraseIndex(listOf(
      item("modo cine al 20", PhoneCommand.Volume(VolumeOp.SET, 20), manual = false),
      item("modo cine al 30", PhoneCommand.Volume(VolumeOp.SET, 30), manual = true),
      item("prendé la luz del pasillo", PhoneCommand.Torch(true), manual = true),
    ))
    // The manual template generalizes «al {número}»; the automatic one is never consulted after it.
    assertEquals(PhoneCommand.Volume(VolumeOp.SET, 60), index.find("modo cine al 60")?.command)
    assertEquals(true, index.find("modo cine al 60")?.source?.manual)
    assertEquals(PhoneCommand.Torch(true), index.find("prendeme la luz del pasillo")?.command)
    assertEquals(true, index.hasManual(key("Prendeme la luz del pasillo porfa")))
  }
}
