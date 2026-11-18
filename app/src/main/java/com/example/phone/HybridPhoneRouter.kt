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


/** Registry access and compatibility routing for callers outside the coordinator. */
class HybridPhoneRouter(
  private val enabled: () -> Boolean,
  private val classifier: DecisionClassifier,
  private val registry: LocalIntentRegistry = LocalIntentRegistry.default,
  internal val policy: DecisionsPolicy = DecisionsPolicy(),
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
    try {
      policy.validate(result)
      require(result.choice in registry.choices)
    } catch (_: IllegalArgumentException) {
      throw DecisionException("Respuesta inválida de Gemini Decisions. El pedido se conserva para reintentar.",
        GeminiFailureKind.INVALID_RESPONSE)
    }
    val simpleTurn = ConversationSession.splitSteps(text).size == 1 &&
      !RequestSafetyPolicy.fixedActionHasExtraParameters(text) && RequestSafetyPolicy.rejection(text) == null
    if (result.choice in LocalIntentRegistry.fallbackIds || policy.action(result, simpleTurn, text) != DecisionAction.ACCEPT) {
      return recoverSearch(text) ?: PhoneRoute.Instinct
    }
    return registry.resolve(result.choice, text)
  }

  /** Compatibility for callers migrating from parser-first: the candidate never authorizes execution. */
  @Suppress("UNUSED_PARAMETER")
  suspend fun route(text: String, local: PhoneRoute): PhoneRoute = route(text)

  fun reply(context: String, text: String, source: Pair<String, java.util.Calendar>? = null): PhoneRoute? =
    context.takeIf { it.startsWith("intent:") }?.removePrefix("intent:")?.let { registry.reply(it, text, source) }
}
