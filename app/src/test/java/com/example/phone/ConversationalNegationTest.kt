package com.example.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prohibitions stay prohibitions; only a delimited «no» followed by an affirmative order is a rectification. */
class ConversationalNegationTest {
  private val parser = PhoneCommandParser()

  @Test fun unaProhibicionNoEsUnaRectificacion() {
    for (text in listOf(
      "No prendas la linterna",
      "No quiero que prendas la linterna",
      "No, no prendas la linterna",
      "No. No prendas la linterna",
      "No molestes",
      "No envíes ese mensaje",
      "No lo hagas",
      "Dale, no, luz del pasillo no",
      "No, prendé la linterna si empieza a llover",
      "No, ¿qué hora es?",
      "No prendé la linterna", // without a delimiter there is not enough syntactic evidence
      "\"No, prendé la linterna\"",
    )) {
      assertNull(text, RequestSafetyPolicy.rectification(text))
      assertNotNull(text, RequestSafetyPolicy.rejection(text))
    }
    // «no» must be a word of its own.
    assertNull(RequestSafetyPolicy.rectification("Nosotros, prendé la linterna"))
  }

  @Test fun unNoAisladoSeguidoDeUnaOrdenAfirmativaEsUnaRectificacion() {
    assertEquals(RequestSafetyPolicy.Rectification("prendé la linterna", anaphoric = false),
      RequestSafetyPolicy.rectification("No, prendé la linterna"))
    assertEquals("bajá el volumen al 20", RequestSafetyPolicy.rectification("No, bajá el volumen al 20")?.order)
    assertEquals("Prendé la linterna", RequestSafetyPolicy.rectification("Bueno, no. Prendé la linterna")?.order)
    assertEquals("apagá la linterna", RequestSafetyPolicy.rectification("No, mejor apagá la linterna")?.order)
    assertEquals("enviá el mensaje a Juan", RequestSafetyPolicy.rectification("No, enviá el mensaje a Juan")?.order)
    assertEquals("Prendé la linterna.", RequestSafetyPolicy.rectification("No… Prendé la linterna.")?.order)
    // The order is parsed like any other: the «no» is gone, the data is intact.
    assertEquals(PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, 20)),
      parser.parse(RequestSafetyPolicy.rectification("No, bajá el volumen al 20")!!.order))
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(true)), parser.parse(RequestSafetyPolicy.rectification("No, prendé la linterna")!!.order))
  }

  @Test fun unaRectificacionAnaforicaSoloUsaUnContextoInequivoco() {
    val rectification = RequestSafetyPolicy.rectification("No, mejor ponelo al 30")
    assertEquals(RequestSafetyPolicy.Rectification("ponelo al 30", anaphoric = true), rectification)
    val order = rectification!!.order
    assertEquals("poné el volumen al 30 %", RequestSafetyPolicy.resolveAnaphora(order, listOf(PhoneCommand.Volume(VolumeOp.SET, 50))))
    assertEquals("poné el brillo al 30 %", RequestSafetyPolicy.resolveAnaphora(order, listOf(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 70))))
    assertEquals("poné el volumen al 30 % durante 1200 segundos",
      RequestSafetyPolicy.resolveAnaphora(order, listOf(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 50))))
    // Nothing to refer to, two candidates or a value that is not a level: ask instead of guessing.
    assertNull(RequestSafetyPolicy.resolveAnaphora(order, emptyList()))
    assertNull(RequestSafetyPolicy.resolveAnaphora(order, listOf(PhoneCommand.Torch(true))))
    assertNull(RequestSafetyPolicy.resolveAnaphora(order, listOf(PhoneCommand.Volume(VolumeOp.SET, 50),
      PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 70))))
    assertNull(RequestSafetyPolicy.resolveAnaphora("ponelo al 300", listOf(PhoneCommand.Volume(VolumeOp.SET, 50))))
    // The rewritten order is a complete local command, still checked by the final semantic guard.
    assertEquals(PhoneRoute.Local(PhoneCommand.Volume(VolumeOp.SET, 30)), parser.parse("poné el volumen al 30 %"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent = 30)), parser.parse("poné el brillo al 30 %"))
    assertEquals(PhoneRoute.Local(PhoneCommand.TemporaryMode(ModeKind.MEDIA_VOLUME, 1200, 30)),
      parser.parse("poné el volumen al 30 % durante 1200 segundos"))
  }

  @Test fun noLoHagasCancelaLoPendienteYNoMolestarSigueFuncionando() {
    for (text in listOf("No lo hagas", "No envíes ese mensaje", "No lo mandes", "Mejor no", "No, cancelá", "No hagas nada"))
      assertTrue(text, RequestSafetyPolicy.cancelsPending(text))
    for (text in listOf("No prendas la linterna", "No molestes", "No, prendé la linterna", "Activá No molestar"))
      assertFalse(text, RequestSafetyPolicy.cancelsPending(text))
    // «No molestes» is a negation, never a request to turn on Do Not Disturb.
    assertNotNull(RequestSafetyPolicy.rejection("No molestes"))
    assertEquals(PhoneRoute.Instinct, parser.parse("No molestes"))
    assertNull(RequestSafetyPolicy.rejection("Activá No molestar"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.DND_ON)), parser.parse("Activá No molestar"))
  }

  /**
   * Fixed corpus. «Executes» means the text, or its rectified order, passes the safety rules and goes on to
   * interpretation. Before: [RequestSafetyPolicy.rejection] alone, which this change does not modify.
   */
  @Test fun negationMetrics() {
    val mustNotExecute = listOf(
      "No prendas la linterna", "No quiero que prendas la linterna", "No, no prendas la linterna", "No molestes",
      "No envíes ese mensaje", "No lo hagas", "Ni se te ocurra prender la linterna", "Nunca apagues la alarma",
      "No bajes el volumen", "Che, no prendas la linterna", "Dale, no, luz del pasillo no", "No, apagá la linterna si llueve",
      "No prendé la linterna", "Prendé la linterna no", "Tampoco prendas la linterna", "No. No subas el volumen",
      "Jamás pongas una alarma", "No quiero la linterna", "No, ¿qué hora es?", "No, no, no",
    )
    val mustExecute = listOf(
      "No, prendé la linterna", "No, bajá el volumen al 20", "No. Subí el volumen", "Bueno, no, apagá la linterna",
      "No, mejor poné un temporizador de cinco minutos", "No… prendé la linterna", "No, enviá el mensaje a Juan",
      "No, abrí Spotify", "Perdón, no, poné una alarma a las 7:30", "Activá No molestar",
      "Mandá un mensaje a Juan que diga “no voy a ir”", "Prendé la linterna",
    )
    fun before(text: String) = RequestSafetyPolicy.rejection(text) == null
    fun after(text: String) = RequestSafetyPolicy.rejection(text) == null || RequestSafetyPolicy.rectification(text) != null
    val counts = listOf(::before, ::after).map { rule ->
      mustNotExecute.count { rule(it) } to mustExecute.count { !rule(it) }
    }
    println("NEGATION_METRICS cases=%d before_false_positives=%d before_false_negatives=%d after_false_positives=%d after_false_negatives=%d"
      .format(mustNotExecute.size + mustExecute.size, counts[0].first, counts[0].second, counts[1].first, counts[1].second))
    assertEquals(mustNotExecute.filter(::after).toString(), 0, counts[1].first)
    assertEquals(mustExecute.filterNot(::after).toString(), 0, counts[1].second)
  }

  @Test fun elMensajeCitadoConNegacionNoEsUnaProhibicionNiUnaRectificacion() {
    val text = "Mandá un mensaje a Juan que diga “no voy a ir”"
    assertNull(RequestSafetyPolicy.rejection(text))
    assertNull(RequestSafetyPolicy.rectification(text))
    assertFalse(RequestSafetyPolicy.cancelsPending(text))
  }
}
