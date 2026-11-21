package com.example.phone

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the fallback provider (Groq) is asked in parallel with Decisions, before knowing it is needed.
 * [ALWAYS] was the original behaviour; [WHEN_DEGRADED] keeps it only after recent Decisions failures, or after
 * recent, repeated failures of Gemini extraction, where the fallback is likely. See docs/metrics/reliability-before-after.md for the replay that chose the default.
 */
enum class FallbackSpeculation { ALWAYS, WHEN_DEGRADED, NEVER }

/** Interpretation only. The caller owns session freshness, previews and Android side effects. */
class GeminiCoordinator(
  private val router: HybridPhoneRouter,
  private val extractor: StructuredCommandExtractor,
  private val verifier: IntentVerifier? = null,
  private val fallback: StructuredCommandExtractor? = null,
  private val breaker: DecisionsCircuitBreaker = DecisionsCircuitBreaker(),
  private val lookupLearnedPhrase: suspend (InterpretationRequest) -> PhoneCommand? = { null },
  /** Learned structures with a typed slot. Consulted after exact phrases, before any provider. */
  private val lookupLearnedPattern: suspend (InterpretationRequest) -> PhrasePatterns.Match? = { null },
  /** Brief operational context. Never added to provider input. */
  private val operationalContext: OperationalContext? = null,
  private val speculation: FallbackSpeculation = FallbackSpeculation.WHEN_DEGRADED,
  /** Observes Gemini extraction only to decide speculation; it never skips an extraction. */
  private val extractionHealth: DecisionsCircuitBreaker = DecisionsCircuitBreaker(),
) {
  /**
   * Every interpretation, whatever produced it, passes the final [SemanticCommandGuard] here, once, before the
   * caller can preview or execute it. Confirmed plans run exactly the commands that passed this check.
   */
  suspend fun interpret(
    request: InterpretationRequest,
    local: PhoneRoute,
    stage: (String) -> Unit = {},
  ): InterpretationResult = SemanticCommandGuard.review(request, interpretUnchecked(request, local, stage))

  private suspend fun interpretUnchecked(
    request: InterpretationRequest,
    local: PhoneRoute,
    stage: (String) -> Unit,
  ): InterpretationResult {
    RequestSafetyPolicy.refusal(request.text)?.let { return it.asResult() }
    val canDeferToInstinct = canDeferToInstinct(request, local)
    val indirectPhoneRequest = RequestSafetyPolicy.isIndirectPhoneRequest(request.text)
    val steps = ConversationSession.splitSteps(request.text)
      .let { if (operationalContext != null) OperationalContext.withoutDiscourseLead(it) else it }
    val explicitQuestion = '?' in request.text || '¿' in request.text || RequestSafetyPolicy.isGeneralRequest(request.text)
    if (request.draft == null && steps.size > 1 && !explicitQuestion) {
      steps.firstNotNullOfOrNull(RequestSafetyPolicy::refusal)?.let { return it.asResult() }
      // Plan locally only when the existing conversation parser understands every step.
      // Local and model plans enter the same confirmation flow.
      when (val plan = ConversationSession { request.now }.accept(request.text, allowSemantic = router.isEnabled())) {
        is ConversationResult.Plan -> {
          // Parsing an app's name does not resolve it to an installed handler.
          if (plan.commands.none { it is PhoneCommand.OpenApp }) return InterpretationResult.Plan(plan.commands)
        }
        is ConversationResult.Prompt -> return InterpretationResult.Rejected(plan.message, plan.reason)
        else -> Unit
      }
    }
    val directSequence = request.draft == null && steps.size in 2..10 &&
      !explicitQuestion &&
      steps.all { step ->
        val head = PhoneCommandParser.leadingWord.find(PhoneCommandParser.normalize(step))?.groupValues?.get(1)
        val explicit = head?.let(PhoneCommandParser::canonicalVerb) != null
        explicit && when (val route = PhoneCommandParser { request.now }.parse(step)) {
          is PhoneRoute.Local -> true
          is PhoneRoute.Clarify -> route.context in setOf("alarm", "timer", "volume", "app")
          else -> false
        }
      }
    val isSingleNewTurn = request.draft == null && steps.size == 1
    if (isSingleNewTurn) {
      when (local) {
        is PhoneRoute.Local -> {
          // Context only fills the media app of a music request that named none; the parsed order prevails.
          val command = operationalContext?.enrich(local.command) ?: local.command
          return InterpretationResult.Direct(command, contextual = command != local.command)
        }
        is PhoneRoute.Clarify -> {
          // Missing fields may be present in the explicit reference. Otherwise retain the local fast path.
          if (request.sourceContext == null || !router.isEnabled()) {
            // A date-bearing alarm needs the registry continuation's source-date guard.
            val datedAlarm = local.context == "alarm" && Regex("\\b(?:hoy|manana)\\b")
              .containsMatchIn(PhoneCommandParser.normalize(request.text).replace("de la manana", ""))
            return InterpretationResult.Clarification(local.prompt, if (datedAlarm) "intent:alarm" else local.context)
          }
        }
        PhoneRoute.Instinct -> if (PhoneCommandParser.isExplicitGeneralRequest(request.text) ||
          canDeferToInstinct && RequestSafetyPolicy.isExplicitQuery(request.text)) return InterpretationResult.Query()
        else -> Unit
      }
    }
    if (local != PhoneRoute.Cancel && local !is PhoneRoute.Local && local !is PhoneRoute.Clarify &&
      !(local is PhoneRoute.Unsupported && !local.recoverable)) {
      lookupLearnedPhrase(request)?.let {
        currentCoroutineContext().ensureActive()
        return InterpretationResult.Direct(it, learned = true)
      }
      if (isSingleNewTurn && request.sourceContext == null) {
        lookupLearnedPattern(request)?.let { match ->
          currentCoroutineContext().ensureActive()
          return when (match) {
            is PhrasePatterns.Match.Command -> InterpretationResult.Direct(match.command, learned = true, pattern = match.template)
            // The structure is known but the value is not complete: the usual clarification, never a guess.
            is PhrasePatterns.Match.Clarify -> InterpretationResult.Clarification(match.prompt, match.context, source = match.source)
          }
        }
        operationalContext?.resolve(request.text) { request.now.clone() as java.util.Calendar }?.let { resolution ->
          when (val route = resolution.route) {
            // Local readings, never model proposals: marked so they teach neither phrases nor structures.
            is PhoneRoute.Local -> return InterpretationResult.Direct(route.command, contextual = true)
            is PhoneRoute.Clarify -> return InterpretationResult.Clarification(route.prompt, route.context, source = resolution.source)
            else -> Unit
          }
        }
      }
    }
    if (!router.needsDecision(request.text, local)) return when (local) {
      is PhoneRoute.Local -> if (isSingleNewTurn) InterpretationResult.Direct(local.command)
        else InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
      PhoneRoute.Instinct -> when {
        request.draft != null -> InterpretationResult.Incomplete(request.draft)
        indirectPhoneRequest -> InterpretationResult.Rejected("Indicá la acción concreta del teléfono. En IA podés activar Gemini para interpretar pedidos menos directos.", RejectionReason.NOT_UNDERSTOOD)
        steps.size > 1 -> InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
        else -> InterpretationResult.Query()
      }
      is PhoneRoute.Clarify -> InterpretationResult.Clarification(local.prompt, local.context)
      is PhoneRoute.Unsupported -> InterpretationResult.Rejected(local.message, local.reason)
      PhoneRoute.Cancel -> InterpretationResult.Rejected("Pedido cancelado.", RejectionReason.UNSAFE_OR_HYPOTHETICAL)
    }
    if (isSingleNewTurn && local is PhoneRoute.Unsupported && !local.recoverable)
      return InterpretationResult.Rejected(local.message, local.reason)
    if (request.modelInput().length > 16000) return unavailableRoute(request, local,
      GeminiFailureKind.INVALID_REQUEST, "El pedido o su contexto es demasiado largo. Se conserva para editarlo.")
    val start = System.nanoTime()
    var failure: GeminiFailureKind? = null
    try {
      return withTimeout(12_000) {
        if (request.draft != null || directSequence || isSingleNewTurn && (
            local is PhoneRoute.Unsupported && local.recoverable ||
              local is PhoneRoute.Clarify && request.sourceContext != null
          )) {
          return@withTimeout resolveStructured(request, local, steps, stage)
        }
        val attempt = breaker.acquire()
        val speculate = attempt != null && fallback != null && when (speculation) {
          FallbackSpeculation.ALWAYS -> true
          FallbackSpeculation.WHEN_DEGRADED -> attempt.probe || breaker.degraded() || extractionHealth.openRecently()
          FallbackSpeculation.NEVER -> false
        }
        val speculative: Deferred<Result<InterpretationResult>>? = if (speculate) async {
          try {
            Result.success(extract(request, fallback, fallback = true, timeout = 6_000))
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            Result.failure(error)
          }
        } else null
        try {
          val decision = try {
            attempt?.let { classify(request, it) }
          } catch (error: DecisionException) {
            failure = error.kind
            if (error.kind == GeminiFailureKind.INVALID_REQUEST) throw error
            null
          }
          if (decision == null) {
            return@withTimeout interpretWithFallback(request, local, steps, stage, failure, speculative)
          }
          val simpleTurn = isSingleNewTurn && request.sourceContext == null &&
            !RequestSafetyPolicy.fixedActionHasExtraParameters(request.text)
          var verifiedByGroq = false
          when (router.policy.action(decision, simpleTurn, request.text)) {
            DecisionAction.RETAIN -> {
              if (router.policy.hasAffirmativeSafety(decision) && fallback != null && !canDeferToInstinct) {
                return@withTimeout interpretWithFallback(request, local, steps, stage, GeminiFailureKind.INVALID_RESPONSE, speculative)
              }
              return@withTimeout unavailableRoute(request, local, GeminiFailureKind.INVALID_RESPONSE,
                "Gemini no pudo validar la intención o sus condiciones. Revisá el pedido e indicá la acción y sus datos.")
            }
            DecisionAction.VERIFY -> {
              val confirmed = try {
                verifier?.let {
                  GeminiDiagnostics.verification()
                  withTimeoutOrNull(1500) { it.confirm(request, decision.choice) }
                }
              } catch (error: CancellationException) {
                throw error
              } catch (_: Exception) {
                false
              }
              if (confirmed != true) return@withTimeout unavailableRoute(
                request, local, GeminiFailureKind.INVALID_RESPONSE,
                "Groq no confirmó la interpretación. Revisá la acción y sus datos o comprobá la clave de Groq en IA.")
              verifiedByGroq = true
            }
            DecisionAction.ACCEPT -> Unit
          }
          currentCoroutineContext().ensureActive()
          if (request.draft == null && decision.choice in setOf("instinct", "unsupported"))
            router.recoverSearch(request.text)?.let { return@withTimeout InterpretationResult.Direct(it.command) }
          if (request.draft == null && steps.size > 1 && decision.choice != "workflow")
            return@withTimeout InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
          if (request.draft == null && router.hasIntent(decision.choice)) {
            when (val registered = router.resolve(decision.choice, request.text)) {
              is PhoneRoute.Local -> return@withTimeout if (verifiedByGroq)
                InterpretationResult.Plan(listOf(registered.command)) else InterpretationResult.Direct(registered.command)
              is PhoneRoute.Unsupported -> return@withTimeout InterpretationResult.Rejected(registered.message, registered.reason)
              is PhoneRoute.Clarify -> {
                val type = CapabilityCatalog.extractionType(decision.choice)
                if (type !in CapabilityCatalog.specs || local is PhoneRoute.Clarify && request.sourceContext == null)
                  return@withTimeout InterpretationResult.Clarification(registered.prompt, registered.context)
              }
              else -> Unit
            }
          }
          when {
            decision.choice == "unsupported" && canDeferToInstinct -> InterpretationResult.Query()
            decision.choice == "unsupported" && fallback != null ->
              interpretWithFallback(request, local, steps, stage, GeminiFailureKind.INVALID_RESPONSE, speculative)
            decision.choice == "instinct" -> if (request.draft != null || local != PhoneRoute.Instinct || indirectPhoneRequest)
              InterpretationResult.Rejected("Hay un pedido del teléfono pendiente. Completalo o cancelalo; no se envió a Instinct.", RejectionReason.BLOCKING_CONDITION)
              else InterpretationResult.Query()
            decision.choice in CapabilityCatalog.fixed && request.draft == null -> {
              if (RequestSafetyPolicy.fixedActionHasExtraParameters(request.text) || RequestSafetyPolicy.isQuestion(request.text)) InterpretationResult.Rejected("La acción simple no representa todos los datos del pedido. No se ejecutó ni se envió.")
              else {
                val command = CapabilityCatalog.fixed.getValue(decision.choice)
                if (verifiedByGroq) InterpretationResult.Plan(listOf(command)) else InterpretationResult.Direct(command)
              }
            }
            decision.choice in CapabilityCatalog.parameterChoices || decision.choice in setOf("app_open", "spotify") -> {
              resolveStructured(request, local, steps, stage, decision.choice, speculative)
            }
            else -> if (canDeferToInstinct && !verifiedByGroq) InterpretationResult.Query()
              else InterpretationResult.Rejected("Ese pedido no está disponible o tiene restricciones no admitidas. No se ejecutó ni se envió.", RejectionReason.UNAVAILABLE)
          }
        } finally {
          speculative?.cancel()
        }
      }
    } catch (error: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      failure = GeminiFailureKind.TIMEOUT
      return unavailableRoute(request, local, failure, "La interpretación tardó demasiado. Reintentá el pedido.")
    } catch (error: CancellationException) { throw error
    } catch (error: DecisionException) {
      failure = error.kind
      return unavailableRoute(request, local, error.kind, error.message ?: error.kind.label)
    } catch (_: Exception) {
      failure = GeminiFailureKind.INVALID_RESPONSE
      return unavailableRoute(request, local, failure, "La respuesta del intérprete fue inválida. Revisá el pedido y reintentá.")
    } finally { GeminiDiagnostics.finish((System.nanoTime() - start) / 1000000, failure) }
  }

  private suspend fun classify(request: InterpretationRequest, attempt: DecisionsCircuitBreaker.Attempt): SemanticDecision {
    GeminiDiagnostics.decision()
    try {
      val result = withTimeoutOrNull(4_000) { router.classify(request) }
        ?: throw DecisionException("Decisions timeout", GeminiFailureKind.TIMEOUT)
      currentCoroutineContext().ensureActive()
      try {
        router.policy.validate(result)
      } catch (_: IllegalArgumentException) {
        throw DecisionException("Invalid Decisions response", GeminiFailureKind.INVALID_RESPONSE)
      }
      breaker.complete(attempt, null)
      return result
    } catch (error: DecisionException) {
      breaker.complete(attempt, error.kind)
      throw error
    } finally {
      breaker.abandon(attempt)
    }
  }

  private suspend fun resolveStructured(
    request: InterpretationRequest, local: PhoneRoute, steps: List<String>,
    stage: (String) -> Unit, choice: String? = null,
    speculative: Deferred<Result<InterpretationResult>>? = null,
  ): InterpretationResult {
    GeminiDiagnostics.extraction()
    val proposal = try {
      extract(request, extractor, fallback = false, timeout = if (fallback == null) 8_000 else 4_000)
    } catch (error: DecisionException) {
      InterpretationResult.Failure(error.kind, error.message ?: error.kind.label)
    } catch (_: IllegalArgumentException) {
      InterpretationResult.Failure(GeminiFailureKind.INVALID_RESPONSE, "Gemini devolvió datos inválidos.")
    }
    extractionHealth.record((proposal as? InterpretationResult.Failure)?.kind)
    val result = validateProposal(request, local, steps, proposal, choice)
    if (result is InterpretationResult.Failure) {
      if (fallback != null && result.kind != GeminiFailureKind.INVALID_REQUEST)
        return interpretWithFallback(request, local, steps, stage, result.kind, speculative, choice)
      return unavailableRoute(request, local, result.kind, result.message)
    }
    if (result is InterpretationResult.Rejected && proposal !is InterpretationResult.Rejected && fallback != null)
      return interpretWithFallback(request, local, steps, stage, GeminiFailureKind.INVALID_RESPONSE, speculative, choice)
    return result
  }

  private suspend fun interpretWithFallback(
    request: InterpretationRequest, local: PhoneRoute, steps: List<String>,
    stage: (String) -> Unit, failure: GeminiFailureKind?,
    speculative: Deferred<Result<InterpretationResult>>? = null, choice: String? = null,
  ): InterpretationResult {
    val provider = fallback ?: return unavailableRoute(request, local,
      failure ?: GeminiFailureKind.UNAVAILABLE,
      "Gemini no pudo completar la interpretación. Reintentá o revisá el acceso en IA.")
    stage("Gemini no completó la interpretación. Probando con Groq")
    GeminiDiagnostics.fallback()
    val result = try {
      speculative?.await()?.getOrThrow() ?: extract(request, provider, fallback = true, timeout = 6_000)
    } catch (error: DecisionException) {
      return unavailableRoute(request, local, error.kind,
        if (error.kind == GeminiFailureKind.TIMEOUT) "Groq tardó demasiado. Reintentá el pedido."
        else error.message ?: error.kind.label)
    }
    if (result is InterpretationResult.Failure) return unavailableRoute(request, local, result.kind, result.message)
    if (result is InterpretationResult.Query && choice == null && request.draft == null &&
      local == PhoneRoute.Instinct && steps.size == 1 && !RequestSafetyPolicy.isIndirectPhoneRequest(request.text) &&
      !canDeferToInstinct(request, local)) return InterpretationResult.Query(needsConfirmation = true)
    return validateProposal(request, local, steps, result, choice)
  }

  /** Provider output never defines how many explicit actions there were or overwrites locally known fields. */
  private fun validateProposal(
    request: InterpretationRequest, local: PhoneRoute, steps: List<String>,
    result: InterpretationResult, choice: String?,
  ): InterpretationResult {
    if (result is InterpretationResult.Query) return if (canDeferToInstinct(request, local))
      InterpretationResult.Query()
    else InterpretationResult.Rejected("El intérprete no identificó una acción válida del teléfono. Revisá la acción y los datos.")
    val commands = (result as? InterpretationResult.Plan)?.commands
    val drafts = when (result) {
      is InterpretationResult.Plan -> result.draft?.steps
      is InterpretationResult.Incomplete -> result.draft.steps
      else -> null
    }
    val count = commands?.size ?: drafts?.size ?: return result
    val expectedCount = request.draft?.steps?.size ?: steps.size
    val inferredWorkflow = choice == "workflow" && request.draft == null && steps.size == 1 && count in 2..10
    if (count != expectedCount && !inferredWorkflow)
      return InterpretationResult.Rejected("La propuesta cambió la cantidad de pasos. Revisá las acciones del pedido.")
    if (!preservesSteps(request, local, steps, result))
      return InterpretationResult.Rejected("La propuesta cambió datos o pasos ya reconocidos. Revisá el pedido.")
    if (request.draft == null && steps.size == 1 && commands != null &&
      RequestSafetyPolicy.fixedActionHasExtraParameters(request.text) &&
      commands.any(router::isFixedCommand)) {
      return InterpretationResult.Rejected("La acción propuesta omite detalles del pedido. Indicá la acción y su duración u horario.")
    }
    if (choice == null || choice == "workflow") return result
    val index = request.draft?.let { active -> active.steps.indexOfFirst { it.id == active.selectedStepId } }
      ?.takeIf { it >= 0 } ?: 0
    val matches = commands?.getOrNull(index)?.let { CapabilityCatalog.accepts(choice, it) }
      ?: drafts?.getOrNull(index)?.let { CapabilityCatalog.acceptsDraft(choice, it) } ?: false
    return if (matches) result
    else if (canDeferToInstinct(request, local)) InterpretationResult.Query()
    else InterpretationResult.Rejected("La clasificación y los datos no coinciden. Revisá el pedido.")
  }

  private fun preservesSteps(request: InterpretationRequest, local: PhoneRoute, steps: List<String>, result: InterpretationResult): Boolean {
    if (request.draft != null || steps.size == 1 && local !is PhoneRoute.Clarify) return true
    val proposed = when (result) {
      is InterpretationResult.Plan -> result.draft?.steps
      is InterpretationResult.Incomplete -> result.draft.steps
      else -> null
    }
    if (result !is InterpretationResult.Plan && result !is InterpretationResult.Incomplete) return true
    if (proposed?.size != steps.size) return false
    return steps.zip(proposed).all { (text, step) ->
      RequestSafetyPolicy.rejection(text) == null && when (val route = if (steps.size == 1) local else PhoneCommandParser { request.now }.parse(text)) {
        is PhoneRoute.Local -> {
          val completed = step.complete(request.now)
          if (completed is PhoneCommand.Platform && route.command is PhoneCommand.Platform &&
            completed.action == route.command.action && completed.action in CalendarSelection.actions)
            CalendarSelection.decode(completed) == CalendarSelection.decode(route.command)
          else if (completed is PhoneCommand.OpenApp && route.command is PhoneCommand.OpenApp)
            PhoneCommandParser.normalize(completed.name) == PhoneCommandParser.normalize(route.command.name)
          else completed == route.command
        }
        is PhoneRoute.Clarify -> {
          val expected = when (route.context.removePrefix("intent:")) {
            "app", "app_open" -> "open_app"
            "volume_set" -> "volume"
            else -> route.context.removePrefix("intent:")
          }
          val known = ConversationSession.seedDraft(text, expected)?.steps?.single()?.json()
          val proposedFields = step.json()
          val fieldsPreserved = known?.keys()?.asSequence()?.all { key ->
            val value = known.get(key).toString()
            proposedFields.opt(key)?.toString() == value ||
              !proposedFields.has(key) && (value.isEmpty() || value == "[]")
          } ?: true
          step.type == expected && fieldsPreserved && (request.sourceContext != null || step.complete(request.now) == null)
        }
        is PhoneRoute.Unsupported -> route.recoverable
        else -> true
      }
    }
  }

  private fun canDeferToInstinct(request: InterpretationRequest, local: PhoneRoute): Boolean =
    request.draft == null && local == PhoneRoute.Instinct &&
      RequestSafetyPolicy.isGeneralRequest(request.text) &&
      !RequestSafetyPolicy.hasJoinedActions(PhoneCommandParser.normalize(request.text)) &&
      ConversationSession.splitSteps(request.text).size == 1

  private fun unavailableRoute(request: InterpretationRequest, local: PhoneRoute, kind: GeminiFailureKind, message: String): InterpretationResult =
    if (canDeferToInstinct(request, local)) InterpretationResult.Query()
    else if (request.draft != null) InterpretationResult.Incomplete(request.draft, message)
    else InterpretationResult.Failure(kind, message)

  private suspend fun extract(
    request: InterpretationRequest, provider: StructuredCommandExtractor, fallback: Boolean, timeout: Long,
  ): InterpretationResult {
    val result = try {
      withTimeoutOrNull(timeout) { provider.interpret(request) }
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      null
    } ?: throw DecisionException("Interpretation timeout", GeminiFailureKind.TIMEOUT)
    currentCoroutineContext().ensureActive()
    return when (result) {
      is InterpretationResult.Query -> if (request.draft == null) InterpretationResult.Query()
        else InterpretationResult.Rejected("Hay un pedido pendiente. Completalo o cancelalo antes de enviar una consulta.", RejectionReason.BLOCKING_CONDITION)
      is InterpretationResult.Plan -> {
        require(result.commands.size in 1..10)
        // Registry-backed proposals were already decoded locally and may have no structured draft schema.
        if (fallback && result.fallback && request.draft == null && result.draft == null) return result
        val plan = result.draft ?: DraftPlan.fromCommands(result.commands, request.now)
        val merged = request.draft?.merge(plan) ?: plan
        merged.result(request.now, fallback)
      }
      is InterpretationResult.Incomplete -> (request.draft?.merge(result.draft) ?: result.draft).result(request.now, fallback)
      else -> result
    }
  }
}
