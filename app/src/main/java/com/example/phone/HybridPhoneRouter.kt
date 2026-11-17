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


/** Every NEW request is classified before local parameter parsing. No generative second pass. */
class HybridPhoneRouter(
  private val enabled: () -> Boolean,
  private val classifier: DecisionClassifier,
  private val registry: LocalIntentRegistry = LocalIntentRegistry.default,
) {
  fun isEnabled(): Boolean = enabled()
  fun needsDecision(text: String, local: PhoneRoute): Boolean = enabled() && local != PhoneRoute.Cancel
  suspend fun classify(request: InterpretationRequest): SemanticDecision = classifier.classify(request)
  fun resolve(choice: String, text: String): PhoneRoute = registry.resolve(choice, text)
  fun hasIntent(choice: String): Boolean = choice in registry.choices && choice !in LocalIntentRegistry.fallbackIds
  fun recoverSearch(text: String): PhoneRoute.Local? {
    val command = (PhoneCommandParser().parse(text) as? PhoneRoute.Local)?.command ?: return null
    val id = when (command) { is PhoneCommand.YouTubeSearch -> "youtube"; is PhoneCommand.SpotifySearch -> "spotify"; else -> return null }
    return if (registry.definitions.any { it.id == id } && NativeIntentSpec.forCommand(command) != null) registry.resolve(id,text) as? PhoneRoute.Local else null
  }
  suspend fun route(text: String): PhoneRoute {
    if (!enabled()) throw DecisionException("Configurá y activá Gemini Decisions en IA. El pedido se conserva.")
    val result = classifier.classify(text)
    currentCoroutineContext().ensureActive()
    val scores = listOf(result.probability, result.confidence, result.unconditional, result.safetyConfidence)
    if (scores.any { !it.isFinite() || it !in 0.0..1.0 } || result.choice !in registry.choices) {
      throw DecisionException("Respuesta inválida de Gemini Decisions. El pedido se conserva para reintentar.")
    }
    if (result.choice in LocalIntentRegistry.fallbackIds || scores.any { it < .95 }) {
      // A literal platform search must not turn into sending the original audio to Instinct.
      // Decisions still runs first; recovery is restricted to these validated, explicit searches.
      val candidate = (PhoneCommandParser().parse(text) as? PhoneRoute.Local)?.command
      val id = when (candidate) {
        is PhoneCommand.YouTubeSearch -> "youtube"
        is PhoneCommand.SpotifySearch -> "spotify"
        else -> null
      }
      if (id != null && candidate != null && registry.definitions.any { it.id == id } && NativeIntentSpec.forCommand(candidate) != null) {
        return registry.resolve(id, text)
      }
      return PhoneRoute.Instinct
    }
    return registry.resolve(result.choice, text)
  }

  /** Compatibility for callers migrating from parser-first: the candidate never authorizes execution. */
  @Suppress("UNUSED_PARAMETER")
  suspend fun route(text: String, local: PhoneRoute): PhoneRoute = route(text)

  fun reply(context: String, text: String, source: Pair<String, java.util.Calendar>? = null): PhoneRoute? =
    context.takeIf { it.startsWith("intent:") }?.removePrefix("intent:")?.let { registry.reply(it, text, source) }
}
