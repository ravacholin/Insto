package com.example.phone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface DecisionClassifier {
  suspend fun classify(text: String): SemanticDecision
  suspend fun classify(request: InterpretationRequest): SemanticDecision = classify(request.modelInput())
}

data class SemanticDecision(
  val choice: String,
  val probability: Double,
  val confidence: Double,
  val unconditional: Double,
  val safetyConfidence: Double,
)


/** Select only complete, predefined commands. Free parameters still belong to the parser. */
class HybridPhoneRouter(
  private val enabled: () -> Boolean,
  private val classifier: DecisionClassifier,
) {
  fun isEnabled(): Boolean = enabled()
  fun needsDecision(text: String, local: PhoneRoute): Boolean {
    val unresolved = local == PhoneRoute.Instinct || local is PhoneRoute.Unsupported && local.recoverable
    if (!unresolved || !enabled()) return false
    return !(local == PhoneRoute.Instinct && RequestSafetyPolicy.isExplicitQuery(text))
  }

  suspend fun route(text: String, local: PhoneRoute): PhoneRoute {
    if (!needsDecision(text, local)) return local
    val result = classifier.classify(text)
    currentCoroutineContext().ensureActive()
    val scores = listOf(result.probability, result.confidence, result.unconditional, result.safetyConfidence)
    if (scores.any { !it.isFinite() || it !in .95..1.0 }) {
      return PhoneRoute.Unsupported("No pude decidir con suficiente certeza. Reformulá el pedido; no se ejecutó ni se envió.")
    }
    return decisionRoute(result, local)
  }

  suspend fun classify(request: InterpretationRequest): SemanticDecision = classifier.classify(request)

  fun decisionRoute(result: SemanticDecision, local: PhoneRoute): PhoneRoute {
    CapabilityCatalog.fixed[result.choice]?.let { return PhoneRoute.Local(it) }
    return when (result.choice) {
      "instinct" -> if (local is PhoneRoute.Unsupported) local else PhoneRoute.Instinct
      in CapabilityCatalog.parameterChoices -> {
        val context = if (result.choice == "volume_set") "volume" else result.choice
        PhoneRoute.Clarify("Completá los datos del pedido de ${context}.", context)
      }
      else -> PhoneRoute.Unsupported("Ese pedido no está disponible o necesita más detalles. Reformulalo; no se envió a Instinct.")
    }
  }
}
