package com.example.phone

internal enum class DecisionAction { ACCEPT, VERIFY, RETAIN }

class DecisionsPolicy(
  private val simpleThreshold: Double = .92,
  private val verificationThreshold: Double = .85,
) {
  init {
    require(verificationThreshold in 0.0..simpleThreshold && simpleThreshold <= HIGH)
  }

  internal fun action(decision: SemanticDecision, simpleTurn: Boolean, text: String): DecisionAction {
    // The declared confidences describe the same evaluations as their probabilities.
    // In particular, confident rejection can have a very low affirmative probability.
    val category = minOf(decision.probability, decision.confidence)
    if (!hasAffirmativeSafety(decision)) return DecisionAction.RETAIN
    return when {
      category >= HIGH -> DecisionAction.ACCEPT
      category >= simpleThreshold && simpleTurn && lowRisk[decision.choice]?.matches(PhoneCommandParser.normalize(text)) == true ->
        DecisionAction.ACCEPT
      category >= verificationThreshold && decision.choice !in LocalIntentRegistry.fallbackIds ->
        DecisionAction.VERIFY
      else -> DecisionAction.RETAIN
    }
  }

  internal fun hasAffirmativeSafety(decision: SemanticDecision): Boolean =
    decision.unconditional >= HIGH && decision.safetyConfidence >= HIGH

  internal fun validate(decision: SemanticDecision) {
    require(decision.choice in CapabilityCatalog.choices &&
      listOf(decision.probability, decision.confidence, decision.unconditional, decision.safetyConfidence)
        .all { it in 0.0..1.0 })
  }

  private companion object {
    const val HIGH = .95
    // At the experimental threshold, require independent evidence of the direction
    // and scope. The full local parser still handles its own commands first.
    val lowRisk = mapOf(
      "torch_on" to Regex("(?:ilumina|(?:necesito|quiero)(?: mas| un poco de)? luz)(?: aca| aqui)?"),
      "torch_off" to Regex("deja de iluminar"),
      "volume_up" to Regex("(?:un poquito|un poco|algo) mas de (?:sonido|volumen)"),
      "volume_down" to Regex("(?:un poquito|un poco|algo) menos de (?:sonido|volumen)"),
    )
  }
}
