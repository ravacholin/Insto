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
) {
  /** Only the active request; no recording, address book, notification or unrelated history. */
  fun modelInput(): String = if (draft == null) text else JSONObject()
    .put("turn", text).put("draft", JSONArray(draft.steps.map { it.jsonWithId() }))
    .apply { draft.pendingEdit?.let { put("pendingEdit", it) }; draft.selectedStepId?.let { put("selectedStepId", it) } }
    .put("revision", revision).toString()
}

sealed interface InterpretationResult {
  data class Query(val needsConfirmation: Boolean = false) : InterpretationResult
  data class Direct(val command: PhoneCommand) : InterpretationResult
  data class Clarification(val prompt: String, val context: String) : InterpretationResult
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
