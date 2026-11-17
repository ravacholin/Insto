package com.example.phone

import java.util.Calendar
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiCorrectionRegressionTest {
  private fun providerResult(command: String): InterpretationResult {
    val wire = """{"kind":"plan","steps":[{"id":"s1","command":$command}]}"""
    val body = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
      .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", wire)))))).toString()
    return StructuredCommandClient.parseInterpretation(body)
  }
  @Test fun omittedLabelAndTravelModeArePreservedBeforeApplyingDefaults() = runTest {
    for ((choice, original, correction, expected) in listOf(
      listOf("timer", PhoneCommand.Timer(600, "Fideos"), """{"type":"timer","seconds":1200}""", PhoneCommand.Timer(1200, "Fideos")),
      listOf("navigate", PhoneCommand.Navigate("Retiro", TravelMode.WALKING), """{"type":"navigate","destination":"Constitución"}""", PhoneCommand.Navigate("Constitución", TravelMode.WALKING)),
      listOf("alarm", PhoneCommand.Alarm(7, 30, listOf(2, 3)), """{"type":"alarm","hour":8,"period":"AM"}""", PhoneCommand.Alarm(8, 30, listOf(2, 3))),
    )) {
      val result = providerResult(correction as String)
      val extractor = object : StructuredCommandExtractor {
        override suspend fun extract(text: String): List<PhoneCommand> = error("Typed result expected")
        override suspend fun interpret(request: InterpretationRequest): InterpretationResult = result
      }
      val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision(choice as String, .99, .99, .99, .99) }), extractor)
      val interpreted = c.interpret(InterpretationRequest("mejor cambiá ese dato", DraftPlan.fromCommands(listOf(original as PhoneCommand))), PhoneRoute.Instinct)
      assertEquals(listOf(expected), (interpreted as InterpretationResult.Plan).commands)
    }
  }
  @Test fun unresolvedOrdinalCorrectionRetainsTheOriginalEditAndItsTarget() {
    val original = DraftPlan.fromCommands(listOf(PhoneCommand.Timer(600, "uno"), PhoneCommand.Timer(900, "dos")))
    val choose = original.reply("cambiale el nombre a Té", Calendar.getInstance()) as InterpretationResult.Incomplete
    val selected = choose.draft.reply("el segundo", Calendar.getInstance())
    assertTrue("Selection must retain the unresolved edit for Gemini", selected is InterpretationResult.Incomplete)
    val input = InterpretationRequest("el segundo", (selected as InterpretationResult.Incomplete).draft).modelInput()
    assertTrue(input, input.contains("cambiale el nombre a Té"))
    assertTrue(input, JSONObject(input).getString("selectedStepId") == "s2")
  }
  @Test fun selectedStepCorrectionPreservesOtherStepsAndUsesItsCapability() = runTest {
    val now = Calendar.getInstance()
    val original = DraftPlan.fromCommands(listOf(PhoneCommand.Timer(600, "uno"), PhoneCommand.Timer(900, "dos")))
    val choose = original.reply("cambiale el nombre a Té", now) as InterpretationResult.Incomplete
    val selected = (choose.draft.reply("el segundo", now) as InterpretationResult.Incomplete).draft
    val proposal = DraftPlan(listOf(
      CommandDraft("s1", JSONObject("""{"type":"timer","seconds":600,"label":"uno"}""")),
      CommandDraft("s2", JSONObject("""{"type":"timer","seconds":900,"label":"Té"}"""))))
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Typed result expected")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult = proposal.result(now)
    }
    val c = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier { SemanticDecision("timer", .99, .99, .99, .99) }), extractor)
    val result = c.interpret(InterpretationRequest("el segundo", selected, now), PhoneRoute.Instinct) as InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Timer(600, "uno"), PhoneCommand.Timer(900, "Té")), result.commands)
    val forged = DraftPlan(listOf(CommandDraft("s1", JSONObject("""{"type":"timer","seconds":1,"label":"otro"}""")), proposal.steps[1]))
    assertThrows(Exception::class.java) { selected.merge(forged) }
  }
}
