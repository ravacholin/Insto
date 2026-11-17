package com.example.phone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface DecisionClassifier {
  suspend fun classify(text: String): SemanticDecision
}

data class SemanticDecision(
  val choice: String,
  val probability: Double,
  val confidence: Double,
  val unconditional: Double,
  val safetyConfidence: Double,
)

class DecisionException(message: String) : Exception(message)

/** Every NEW request is classified before local parameter parsing. No generative second pass. */
class HybridPhoneRouter(
  private val enabled: () -> Boolean,
  private val classifier: DecisionClassifier,
  private val registry: LocalIntentRegistry = LocalIntentRegistry.default,
) {
  suspend fun route(text: String): PhoneRoute {
    if (!enabled()) throw DecisionException("Configurá y activá Gemini Decisions en IA. El pedido se conserva.")
    val result = classifier.classify(text)
    currentCoroutineContext().ensureActive()
    val scores = listOf(result.probability, result.confidence, result.unconditional, result.safetyConfidence)
    if (scores.any { !it.isFinite() || it !in 0.0..1.0 } || result.choice !in registry.choices) {
      throw DecisionException("Respuesta inválida de Gemini Decisions. El pedido se conserva para reintentar.")
    }
    if (result.choice in LocalIntentRegistry.fallbackIds || scores.any { it < .95 }) return PhoneRoute.Instinct
    return registry.resolve(result.choice, text)
  }

  /** Compatibility for callers migrating from parser-first: the candidate never authorizes execution. */
  @Suppress("UNUSED_PARAMETER")
  suspend fun route(text: String, local: PhoneRoute): PhoneRoute = route(text)

  fun reply(context: String, text: String, source: Pair<String, java.util.Calendar>? = null): PhoneRoute? =
    context.takeIf { it.startsWith("intent:") }?.removePrefix("intent:")?.let { registry.reply(it, text, source) }
}
