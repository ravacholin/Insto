package com.example.phone

import com.example.context.SharedTextContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextualInterpretationTest {
  @Test fun referenceDoesNotDisableExistingRegisteredActions() = runTest {
    for ((choice, text) in listOf("battery" to "cuánta batería queda", "settings_wifi" to "abrí ajustes de wifi", "calendar_agenda" to "mostrame mi agenda")) {
      val parser = PhoneCommandParser()
      val expected = HybridPhoneRouter({ true }, DecisionClassifier { error("Unused") }).resolve(choice, text)
      assertTrue("Registry fixture $choice", expected is PhoneRoute.Local)
      val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
        SemanticDecision(choice, .99, .99, .99, .99)
      }), StructuredCommandExtractor { error("Registry action needs no extraction") })
      val result = coordinator.interpret(InterpretationRequest(text,
        sourceContext = SharedTextContext("Documento de referencia", SharedTextContext.Source.SHARED)), parser.parse(text))
      assertEquals(choice, InterpretationResult.Direct((expected as PhoneRoute.Local).command), result)
    }
  }

  @Test fun contextualCalendarExtractsReferenceInsteadOfStoppingAtLocalMissingDate() = runTest {
    val now = java.util.Calendar.getInstance().apply { set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val event = PhoneCommand.CalendarEvent("Reunión", now.timeInMillis + 86400000, now.timeInMillis + 90000000)
    var extractions = 0
    val reference = SharedTextContext("Reunión mañana a las 15 horas", SharedTextContext.Source.SELECTED)
    val extractor = object : StructuredCommandExtractor {
      override suspend fun extract(text: String): List<PhoneCommand> = error("Use typed request")
      override suspend fun interpret(request: InterpretationRequest): InterpretationResult {
        extractions++
        assertEquals(reference, request.sourceContext)
        assertEquals("Agendá la fecha que aparece acá", request.text)
        return InterpretationResult.Plan(listOf(event))
      }
    }
    val coordinator = GeminiCoordinator(HybridPhoneRouter({ true }, DecisionClassifier {
      SemanticDecision("calendar", .99, .99, .99, .99)
    }), extractor)
    val result = coordinator.interpret(InterpretationRequest("Agendá la fecha que aparece acá", now = now, sourceContext = reference),
      PhoneRoute.Clarify("¿Qué fecha?", "intent:calendar"))
    assertTrue(result is InterpretationResult.Plan)
    assertEquals(listOf(event), (result as InterpretationResult.Plan).commands)
    assertEquals(1, extractions)
  }
}
