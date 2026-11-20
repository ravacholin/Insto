package com.example.phone

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fallback provider (Groq) answers alone when Decisions is down: its proposals go through the same final
 * resolution as Gemini's. A divergent proposal ends in a clarification or a safe rejection, never in execution.
 */
class ProviderDivergenceTest {
  private fun now(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 17, 12, 0) }

  private fun resolve(text: String, proposal: List<PhoneCommand>): InterpretationResult = runBlocking {
    val coordinator = GeminiCoordinator(
      HybridPhoneRouter({ true }, DecisionClassifier { throw DecisionException("down", GeminiFailureKind.SERVER) }),
      StructuredCommandExtractor { throw DecisionException("down", GeminiFailureKind.SERVER) },
      fallback = StructuredCommandExtractor { proposal })
    coordinator.interpret(InterpretationRequest(text, now = now()), PhoneCommandParser(::now).parse(text))
  }

  private fun assertNotExecuted(text: String, proposal: List<PhoneCommand>, why: String) {
    val result = resolve(text, proposal)
    if (result is InterpretationResult.Direct || result is InterpretationResult.Plan)
      fail("$why: «$text» + $proposal -> $result")
  }

  @Test fun divergentProposalsAreNeverExecuted() {
    assertNotExecuted("Dejá de iluminar", listOf(PhoneCommand.Torch(true)), "inverted action")
    assertNotExecuted("Suponete que apagás el wifi", listOf(PhoneCommand.Torch(true)), "hypothesis and another device")
    assertNotExecuted("Imaginate que prendés la linterna", listOf(PhoneCommand.Torch(true)), "hypothesis")
    assertNotExecuted("Supongamos que bajás el volumen", listOf(PhoneCommand.Volume(VolumeOp.DOWN)), "hypothesis")
    assertNotExecuted("No prendas la linterna", listOf(PhoneCommand.Torch(true)), "negation turned affirmative")
    assertNotExecuted("Necesito luz acá", listOf(PhoneCommand.Torch(true), PhoneCommand.Timer(600)), "added an action")
    assertNotExecuted("Avisame en diez minutos", listOf(PhoneCommand.Timer(300)), "changed an explicit parameter")
    assertNotExecuted("Che, apagá el bluetooth", listOf(PhoneCommand.Torch(false)), "another device")
    assertNotExecuted("Necesito que el wifi se apague", listOf(PhoneCommand.Volume(VolumeOp.DOWN)), "another device")
    assertNotExecuted("¿Qué es una linterna?", listOf(PhoneCommand.Torch(true)), "general query turned into an operation")
    // The local plan for both steps wins; the provider's one-step proposal is never what runs.
    val workflow = resolve("Poné música de Soda Stereo y bajá el volumen", listOf(PhoneCommand.MusicSearch("Soda Stereo")))
    assertTrue("$workflow", workflow !is InterpretationResult.Plan || workflow.commands.size == 2)
    assertNotExecuted("Prendé la linterna, después dejá de iluminar y avisame en diez minutos",
      listOf(PhoneCommand.Torch(true), PhoneCommand.Timer(600)), "lost a workflow step")
  }

  @Test fun faithfulFallbackProposalsStillWork() {
    for ((text, proposal) in listOf(
      "Necesito luz acá" to listOf(PhoneCommand.Torch(true)),
      "Dejá de iluminar" to listOf(PhoneCommand.Torch(false)),
      "Avisame en diez minutos" to listOf(PhoneCommand.Timer(600)),
      "Bajale un toque al sonido" to listOf(PhoneCommand.Volume(VolumeOp.DOWN)),
    )) {
      val result = resolve(text, proposal)
      assertTrue("«$text» -> $result", result is InterpretationResult.Plan && result.commands == proposal)
    }
  }
}
