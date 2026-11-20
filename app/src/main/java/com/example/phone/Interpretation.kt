package com.example.phone

import java.util.Calendar
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class InterpretationRequest(
  val text: String,
  val draft: DraftPlan? = null,
  val now: Calendar = Calendar.getInstance(),
  val requestId: String = UUID.randomUUID().toString(),
  val revision: Long = draft?.revision ?: 0,
  val sourceContext: com.example.context.SharedTextContext? = null,
) {
  /** Reference material is a separate data field, never appended to the executable turn. */
  fun modelInput(): String {
    if (draft == null && sourceContext == null) return text
    return JSONObject().put("turn", text).apply {
      sourceContext?.let { put("sourceContext", it.json()) }
      draft?.let { active ->
        put("draft", JSONArray(active.steps.map { it.jsonWithId() }))
        active.pendingEdit?.let { put("pendingEdit", it) }
        active.selectedStepId?.let { put("selectedStepId", it) }
        put("revision", revision)
      }
    }.toString()
  }
}

sealed interface InterpretationResult {
  data class Query(val needsConfirmation: Boolean = false) : InterpretationResult
  /**
   * [learned]: an exact learned phrase or, with [pattern], a learned structure. [contextual]: resolved or completed
   * by the brief operational context stage. Neither comes from a model, so neither teaches phrases or structures.
   */
  data class Direct(
    val command: PhoneCommand,
    val learned: Boolean = false,
    val pattern: String? = null,
    val contextual: Boolean = false,
  ) : InterpretationResult
  /** [source]: the request the local grammar understood, when it differs from the text the user wrote. */
  data class Clarification(val prompt: String, val context: String, val source: String? = null) : InterpretationResult
  data class Plan(val commands: List<PhoneCommand>, val fallback: Boolean = false, val draft: DraftPlan? = null) : InterpretationResult
  data class Incomplete(val draft: DraftPlan, val prompt: String = draft.prompt(), val fallback: Boolean = false) : InterpretationResult
  data class Rejected(val message: String) : InterpretationResult
  data class Failure(val kind: GeminiFailureKind, val message: String) : InterpretationResult
}

enum class GeminiFailureKind(val label: String) {
  AUTH("Clave o acceso"), QUOTA("Cuota agotada"), UNAVAILABLE("Servicio no disponible"),
  NETWORK("Conexión"), SERVER("Problema temporal del servicio"), INVALID_RESPONSE("Respuesta inválida"),
  INVALID_REQUEST("Pedido inválido"), TIMEOUT("Tiempo de espera agotado")
}

class DecisionException(
  message: String,
  val kind: GeminiFailureKind = GeminiFailureKind.NETWORK,
) : Exception(message)
