package com.example.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Interpretation only. The caller owns session freshness, previews and Android side effects. */
class GeminiCoordinator(
  private val router: HybridPhoneRouter,
  private val extractor: StructuredCommandExtractor,
  private val verifier: IntentVerifier? = null,
  private val fallback: StructuredCommandExtractor? = null,
  private val breaker: DecisionsCircuitBreaker = DecisionsCircuitBreaker(),
) {
  suspend fun interpret(
    request: InterpretationRequest,
    local: PhoneRoute,
    stage: (String) -> Unit = {},
  ): InterpretationResult {
    RequestSafetyPolicy.rejection(request.text)?.let { return InterpretationResult.Rejected(it) }
    val canDeferToInstinct = canDeferToInstinct(request, local)
    val indirectPhoneRequest = RequestSafetyPolicy.isIndirectPhoneRequest(request.text)
    val steps = ConversationSession.splitSteps(request.text)
    val explicitQuestion = '?' in request.text || '¿' in request.text || RequestSafetyPolicy.isGeneralRequest(request.text)
    if (request.draft == null && steps.size > 1 && !explicitQuestion) {
      steps.firstNotNullOfOrNull(RequestSafetyPolicy::rejection)?.let { return InterpretationResult.Rejected(it) }
      // Plan locally only when the existing conversation parser understands every step.
      // Local and model plans enter the same confirmation flow.
      when (val plan = ConversationSession { request.now }.accept(request.text, allowSemantic = router.isEnabled())) {
        is ConversationResult.Plan -> {
          // Parsing an app's name does not resolve it to an installed handler.
          if (plan.commands.none { it is PhoneCommand.OpenApp }) return InterpretationResult.Plan(plan.commands)
        }
        is ConversationResult.Prompt -> return InterpretationResult.Rejected(plan.message)
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
        is PhoneRoute.Local -> return InterpretationResult.Direct(local.command)
        is PhoneRoute.Clarify -> {
          // Missing fields may be present in the explicit reference. Otherwise retain the local fast path.
          if (request.sourceContext == null || !router.isEnabled()) {
            // A date-bearing alarm needs the registry continuation's source-date guard.
            val datedAlarm = local.context == "alarm" && Regex("\\b(?:hoy|manana)\\b")
              .containsMatchIn(PhoneCommandParser.normalize(request.text).replace("de la manana", ""))
            return InterpretationResult.Clarification(local.prompt, if (datedAlarm) "intent:alarm" else local.context)
          }
        }
        PhoneRoute.Instinct -> if (canDeferToInstinct && RequestSafetyPolicy.isExplicitQuery(request.text)) return InterpretationResult.Query()
        else -> Unit
      }
    }
    if (!router.needsDecision(request.text, local)) return when (local) {
      is PhoneRoute.Local -> if (isSingleNewTurn) InterpretationResult.Direct(local.command)
        else InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
      PhoneRoute.Instinct -> when {
        request.draft != null -> InterpretationResult.Incomplete(request.draft)
        indirectPhoneRequest -> InterpretationResult.Rejected("Indicá la acción concreta del teléfono. En IA podés activar Gemini para interpretar pedidos menos directos.")
        steps.size > 1 -> InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
        else -> InterpretationResult.Query()
      }
      is PhoneRoute.Clarify -> InterpretationResult.Clarification(local.prompt, local.context)
      is PhoneRoute.Unsupported -> InterpretationResult.Rejected(local.message)
      PhoneRoute.Cancel -> InterpretationResult.Rejected("Pedido cancelado.")
    }
    if (isSingleNewTurn && local is PhoneRoute.Unsupported && !local.recoverable)
      return InterpretationResult.Rejected(local.message)
    if (request.modelInput().length > 16000) return unavailableRoute(request, local,
      GeminiFailureKind.INVALID_REQUEST, "El pedido o su contexto es demasiado largo. Se conserva para editarlo.")
    val start = System.nanoTime()
    var failure: GeminiFailureKind? = null
    try {
      return withTimeout(12_000) {
        if (directSequence) {
          GeminiDiagnostics.extraction()
          val result = try {
            extract(request, extractor, fallback = false, timeout = 8_000)
          } catch (error: DecisionException) {
            if (error.kind in setOf(GeminiFailureKind.INVALID_REQUEST, GeminiFailureKind.INVALID_RESPONSE)) throw error
            InterpretationResult.Failure(error.kind, error.message.orEmpty())
          }
          if (result is InterpretationResult.Failure) {
            failure = result.kind
            return@withTimeout interpretWithFallback(request, local, steps, stage, result.kind)
          }
          return@withTimeout if (result is InterpretationResult.Query || !preservesSteps(request, steps, result))
            InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
          else result
        }
        val decision = try {
          classify(request)
        } catch (error: DecisionException) {
          failure = error.kind
          if (error.kind == GeminiFailureKind.INVALID_REQUEST) throw error
          null
        }
        if (decision == null) {
          return@withTimeout interpretWithFallback(request, local, steps, stage, failure)
        }
        val simpleTurn = isSingleNewTurn && request.sourceContext == null &&
          !RequestSafetyPolicy.fixedActionHasExtraParameters(request.text)
        var verifiedByGroq = false
        when (router.policy.action(decision, simpleTurn, request.text)) {
          DecisionAction.RETAIN -> {
            if (router.policy.hasAffirmativeSafety(decision) && fallback != null && !canDeferToInstinct) {
              return@withTimeout interpretWithFallback(request, local, steps, stage, GeminiFailureKind.INVALID_RESPONSE)
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
            is PhoneRoute.Unsupported -> return@withTimeout InterpretationResult.Rejected(registered.message)
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
          decision.choice == "instinct" -> if (request.draft != null || local != PhoneRoute.Instinct || indirectPhoneRequest)
            InterpretationResult.Rejected("Hay un pedido del teléfono pendiente. Completalo o cancelalo; no se envió a Instinct.")
            else InterpretationResult.Query()
          decision.choice in CapabilityCatalog.fixed && request.draft == null -> {
            if (RequestSafetyPolicy.fixedActionHasExtraParameters(request.text) || RequestSafetyPolicy.isQuestion(request.text)) InterpretationResult.Rejected("La acción simple no representa todos los datos del pedido. No se ejecutó ni se envió.")
            else {
              val command = CapabilityCatalog.fixed.getValue(decision.choice)
              if (verifiedByGroq) InterpretationResult.Plan(listOf(command)) else InterpretationResult.Direct(command)
            }
          }
          decision.choice in CapabilityCatalog.parameterChoices || decision.choice in setOf("app_open", "spotify") || request.draft != null && router.hasIntent(decision.choice) || request.draft != null && decision.choice in CapabilityCatalog.fixed -> {
            GeminiDiagnostics.extraction()
            // Leave time for a second provider inside the shared interpretation deadline.
            val result = try {
              extract(request, extractor, fallback = false, timeout = if (fallback == null) 8_000 else 4_000)
            } catch (error: DecisionException) {
              InterpretationResult.Failure(error.kind, error.message ?: error.kind.label)
            }
            if (result is InterpretationResult.Failure) {
              failure = result.kind
              if (fallback != null && result.kind != GeminiFailureKind.INVALID_REQUEST)
                return@withTimeout interpretWithFallback(request, local, steps, stage, result.kind)
              return@withTimeout unavailableRoute(request, local, result.kind, result.message)
            }
            if (!preservesSteps(request, steps, result))
              return@withTimeout InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
            val commands = (result as? InterpretationResult.Plan)?.commands
            val drafts = (result as? InterpretationResult.Incomplete)?.draft?.steps
            val selectedIndex = request.draft?.let { active -> active.steps.indexOfFirst { it.id == active.selectedStepId }.takeIf { it >= 0 } }
            val consistent = if (selectedIndex != null && decision.choice != "workflow") {
              commands?.getOrNull(selectedIndex)?.let { CapabilityCatalog.accepts(decision.choice, it) }
                ?: drafts?.getOrNull(selectedIndex)?.let { CapabilityCatalog.acceptsDraft(decision.choice, it) }
                ?: false
            } else if (decision.choice == "workflow") (commands?.size ?: drafts?.size ?: 0) in 2..10 &&
              (steps.size == 1 || (commands?.size ?: drafts?.size) == steps.size) else
              commands?.let { it.size == 1 && CapabilityCatalog.accepts(decision.choice, it.single()) }
                ?: drafts?.let { it.size == 1 && CapabilityCatalog.acceptsDraft(decision.choice, it.single()) }
                ?: false
            when {
              consistent || result is InterpretationResult.Rejected -> result
              canDeferToInstinct && !verifiedByGroq -> InterpretationResult.Query()
              else -> InterpretationResult.Rejected("La clasificación y los datos no coinciden. No se ejecutó ni se envió; reformulá el pedido.")
            }
          }
          else -> if (canDeferToInstinct && !verifiedByGroq) InterpretationResult.Query()
            else InterpretationResult.Rejected("Ese pedido no está disponible o tiene restricciones no admitidas. No se ejecutó ni se envió.")
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

  private suspend fun classify(request: InterpretationRequest): SemanticDecision? {
    val attempt = breaker.acquire() ?: return null
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

  private suspend fun interpretWithFallback(
    request: InterpretationRequest, local: PhoneRoute, steps: List<String>,
    stage: (String) -> Unit, failure: GeminiFailureKind?,
  ): InterpretationResult {
    val provider = fallback ?: return unavailableRoute(request, local,
      failure ?: GeminiFailureKind.UNAVAILABLE,
      "Gemini no pudo completar la interpretación. Reintentá o revisá el acceso en IA.")
    stage("Gemini no completó la interpretación. Probando con Groq")
    GeminiDiagnostics.fallback()
    val result = try {
      extract(request, provider, fallback = true, timeout = 6_000)
    } catch (error: DecisionException) {
      return unavailableRoute(request, local, error.kind,
        if (error.kind == GeminiFailureKind.TIMEOUT) "Groq tardó demasiado. Reintentá el pedido."
        else error.message ?: error.kind.label)
    }
    if (result is InterpretationResult.Query) {
      return if (canDeferToInstinct(request, local)) InterpretationResult.Query()
      else if (request.draft == null && local == PhoneRoute.Instinct && steps.size == 1 &&
        !RequestSafetyPolicy.isIndirectPhoneRequest(request.text))
        InterpretationResult.Query(needsConfirmation = true)
      else InterpretationResult.Rejected("Groq no identificó una acción válida del teléfono. Indicá la acción y sus datos.")
    }
    if (result is InterpretationResult.Failure) return unavailableRoute(request, local, result.kind, result.message)
    if (!preservesSteps(request, steps, result)) return InterpretationResult.Rejected("No pude interpretar todos los pasos del pedido")
    if (request.draft == null && steps.size == 1 && result is InterpretationResult.Plan &&
      result.commands.any { command ->
        command in CapabilityCatalog.fixed.values ||
          command is PhoneCommand.Platform && command.action in setOf(
            PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON,
            PlatformAction.DND_ON, PlatformAction.DND_OFF)
      } &&
      RequestSafetyPolicy.fixedActionHasExtraParameters(request.text)) {
      return InterpretationResult.Rejected("La acción propuesta por Groq omite detalles del pedido. Indicá la acción y su duración u horario.")
    }
    return result
  }

  private fun preservesSteps(request: InterpretationRequest, steps: List<String>, result: InterpretationResult): Boolean {
    if (request.draft != null || steps.size == 1) return true
    val proposed = when (result) {
      is InterpretationResult.Plan -> result.draft?.steps
      is InterpretationResult.Incomplete -> result.draft.steps
      else -> null
    }
    if (result !is InterpretationResult.Plan && result !is InterpretationResult.Incomplete) return true
    if (proposed?.size != steps.size) return false
    return steps.zip(proposed).all { (text, step) ->
      RequestSafetyPolicy.rejection(text) == null && when (val route = PhoneCommandParser { request.now }.parse(text)) {
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
        else InterpretationResult.Rejected("Hay un pedido pendiente. Completalo o cancelalo antes de enviar una consulta.")
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
