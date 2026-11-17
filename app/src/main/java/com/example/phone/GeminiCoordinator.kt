package com.example.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Interpretation only. The caller owns session freshness, previews and Android side effects. */
class GeminiCoordinator(
  private val router: HybridPhoneRouter,
  private val extractor: StructuredCommandExtractor,
) {
  suspend fun interpret(
    request: InterpretationRequest,
    local: PhoneRoute,
    stage: (String) -> Unit = {},
  ): InterpretationResult {
    RequestSafetyPolicy.rejection(request.text)?.let { return InterpretationResult.Rejected(it) }
    if (!router.needsDecision(request.text, local)) return when (local) {
      is PhoneRoute.Local -> InterpretationResult.Direct(local.command)
      PhoneRoute.Instinct -> request.draft?.let { InterpretationResult.Incomplete(it) } ?: InterpretationResult.Query()
      is PhoneRoute.Clarify -> InterpretationResult.Rejected(local.prompt)
      is PhoneRoute.Unsupported -> InterpretationResult.Rejected(local.message)
      PhoneRoute.Cancel -> InterpretationResult.Rejected("Pedido cancelado.")
    }
    if (request.modelInput().length > 16000) return InterpretationResult.Failure(GeminiFailureKind.INVALID_REQUEST, "El pedido o su contexto es demasiado largo. Se conserva para editarlo.")
    val start = System.nanoTime()
    var failure: GeminiFailureKind? = null
    try {
      return withTimeout(12000) {
        stage("Decidiendo con Gemini…")
        GeminiDiagnostics.decision()
        val decision = try { withTimeout(4000) { router.classify(request) } } catch (error: DecisionException) {
          if (error.kind != GeminiFailureKind.UNAVAILABLE) throw error
          stage("Decisions no disponible; interpretando con Gemini de respaldo…")
          val alternative = extract(request, fallback = true)
          return@withTimeout if (alternative is InterpretationResult.Query && local is PhoneRoute.Unsupported)
            InterpretationResult.Rejected("Reconocí un pedido del teléfono. No se envió a Instinct; completalo o cancelalo.") else alternative
        }
        currentCoroutineContext().ensureActive()
        val scores = listOf(decision.probability, decision.confidence, decision.unconditional, decision.safetyConfidence)
        if (scores.any { !it.isFinite() || it !in .95..1.0 }) return@withTimeout InterpretationResult.Rejected("No pude decidir con suficiente certeza. El pedido se conserva; reformulalo.")
        when {
          decision.choice == "instinct" -> if (request.draft != null || local is PhoneRoute.Unsupported)
            InterpretationResult.Rejected("Hay un pedido del teléfono pendiente. Completalo o cancelalo; no se envió a Instinct.")
            else InterpretationResult.Query()
          decision.choice in CapabilityCatalog.fixed && request.draft == null -> {
            if (RequestSafetyPolicy.fixedActionHasExtraParameters(request.text)) InterpretationResult.Rejected("La acción simple no representa todos los datos del pedido. No se ejecutó ni se envió.")
            else InterpretationResult.Direct(CapabilityCatalog.fixed.getValue(decision.choice))
          }
          decision.choice in CapabilityCatalog.parameterChoices || request.draft != null && decision.choice in CapabilityCatalog.fixed -> {
            stage("Completando los datos con Gemini…")
            val result = extract(request, fallback = false)
            val commands = (result as? InterpretationResult.Plan)?.commands
            val drafts = (result as? InterpretationResult.Incomplete)?.draft?.steps
            val selectedIndex = request.draft?.let { active -> active.steps.indexOfFirst { it.id == active.selectedStepId }.takeIf { it >= 0 } }
            val consistent = if (selectedIndex != null && decision.choice != "workflow") {
              commands?.getOrNull(selectedIndex)?.let { CapabilityCatalog.accepts(decision.choice, it) }
                ?: drafts?.getOrNull(selectedIndex)?.let { it.type == (if (decision.choice == "volume_set") "volume" else decision.choice) }
                ?: false
            } else if (decision.choice == "workflow") (commands?.size ?: drafts?.size ?: 0) in 2..10 else
              commands?.let { it.size == 1 && CapabilityCatalog.accepts(decision.choice, it.single()) }
                ?: drafts?.let { it.size == 1 && it.single().type == (if (decision.choice == "volume_set") "volume" else decision.choice) }
                ?: false
            if (!consistent && result !is InterpretationResult.Rejected) InterpretationResult.Rejected("La clasificación y los datos no coinciden. No se ejecutó ni se envió; reformulá el pedido.") else result
          }
          else -> InterpretationResult.Rejected("Ese pedido no está disponible o tiene restricciones no admitidas. No se ejecutó ni se envió.")
        }
      }
    } catch (error: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      failure = GeminiFailureKind.TIMEOUT
      return InterpretationResult.Failure(failure, "Gemini tardó demasiado. El pedido se conserva para reintentar.")
    } catch (error: CancellationException) { throw error
    } catch (error: DecisionException) {
      failure = error.kind
      return InterpretationResult.Failure(error.kind, error.message ?: "Gemini no pudo interpretar. El pedido se conserva.")
    } catch (_: Exception) {
      failure = GeminiFailureKind.INVALID_RESPONSE
      return InterpretationResult.Failure(failure, "No se pudo validar la interpretación de Gemini. El pedido se conserva.")
    } finally { GeminiDiagnostics.finish((System.nanoTime() - start) / 1000000, failure) }
  }

  private suspend fun extract(request: InterpretationRequest, fallback: Boolean): InterpretationResult {
    GeminiDiagnostics.extraction(fallback)
    val result = withTimeout(8000) { extractor.interpret(request) }
    currentCoroutineContext().ensureActive()
    return when (result) {
      is InterpretationResult.Query -> if (request.draft == null) result.copy(needsConfirmation = fallback)
        else InterpretationResult.Rejected("Hay un pedido pendiente. Completalo o cancelalo antes de enviar una consulta.")
      is InterpretationResult.Plan -> {
        require(result.commands.size in 1..10)
        val plan = result.draft ?: DraftPlan.fromCommands(result.commands, request.now)
        val merged = request.draft?.merge(plan) ?: plan
        merged.result(request.now, fallback)
      }
      is InterpretationResult.Incomplete -> (request.draft?.merge(result.draft) ?: result.draft).result(request.now, fallback)
      else -> result
    }
  }
}
