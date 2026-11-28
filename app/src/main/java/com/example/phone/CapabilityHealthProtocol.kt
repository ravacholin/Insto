package com.example.phone

import org.json.JSONArray
import org.json.JSONObject

/** Additive wire representation. Absolute timestamps avoid changing the catalog hash on every read. */
internal fun HealthDiagnostic.wire(): Map<String, Any> = buildMap {
  put("estado", state.name)
  put("codigo", observation.code.name)
  put("implementada", true)
  state.available?.let { put("disponible", it) }
  observation.configured?.let { put("configurada", it) }
  observation.authorized?.let { put("autorizada", it) }
  put("transitorio", observation.code.transient)
  put("requiere_intervencion", state in setOf(HealthState.PERMISSION_MISSING, HealthState.INCOMPLETE,
    HealthState.INVALID_CREDENTIALS, HealthState.USER_REQUIRED, HealthState.VERSION_MISMATCH, HealthState.SYSTEM_RESTRICTED))
  if (checkedAt > 0 && state != HealthState.UNKNOWN) {
    put("comprobada_en", checkedAt)
    put("vigente_hasta", expiresAt)
  }
}

/** The export has no user-controlled labels, provider payloads, stack traces, order text or target IDs. */
internal fun HealthSnapshot.sanitizedReport(now: Long, allowed: Set<String>): String {
  val records = diagnostics.values.filter { it.id in allowed && !it.id.startsWith("omniremote.device.") }
    .sortedBy { it.id }
  val history = reportedIncidents.filter { it.id in allowed && !it.id.startsWith("omniremote.device.") }
  val overview = HealthOverview(records.map { it.at(now) })
  return JSONObject().put("version", 1).put("generated_at", now)
    .put("summary", JSONObject()
      .put("issues", JSONArray(overview.issues.map { issue ->
        JSONObject().put("id", issue.id).put("code", issue.code.name)
          .put("affected_capabilities", JSONArray(issue.capabilities.map(HealthDiagnostic::id)))
      }))
      .put("device_limits", JSONArray(overview.limits.map(HealthDiagnostic::id))))
    .put("capabilities", JSONArray(records.map { row ->
      JSONObject(row.at(now).wire()).put("id", row.id).put("source", row.observation.evidence.name)
        .put("last_success", row.lastSuccess).put("last_failure", row.lastFailure)
        .put("consecutive_failures", row.consecutiveFailures).put("successes", row.successes).put("failures", row.failures)
        .put("p50_ms", row.percentile(50)).put("p95_ms", row.percentile(95)).put("timeouts", row.timeouts)
        .put("recoveries", row.recoveries)
    }))
    .put("incidents", JSONArray(history.map { row ->
      JSONObject().put("id", row.id).put("code", row.code.name).put("at", row.at)
        .put("stage", row.stage?.name).put("recovered", row.recovery)
    })).toString(2)
}


/** Status 429 alone is rate limiting. A structured provider code can establish quota exhaustion. */
internal fun providerHealth(status: Int, body: String? = null): HealthCode? = when (status) {
  in 200..299 -> null
  401, 403 -> HealthCode.CREDENTIAL_REJECTED
  408 -> HealthCode.TIMEOUT
  404, 405, 501 -> HealthCode.MODEL
  429 -> {
    val error = body?.let { runCatching { JSONObject(it).optJSONObject("error") }.getOrNull() }
    val details = error?.optJSONArray("details")
    val dailyLimit = details != null && (0 until details.length()).any { index ->
      val detail = details.optJSONObject(index) ?: return@any false
      if (detail.optString("@type") != "type.googleapis.com/google.rpc.QuotaFailure") return@any false
      val violations = detail.optJSONArray("violations") ?: return@any false
      (0 until violations.length()).any { violation ->
        val quotaId = violations.optJSONObject(violation)?.optString("quotaId").orEmpty()
        quotaId.contains("PerDay", ignoreCase = true)
      }
    }
    val exhausted = dailyLimit || error?.optString("code") == "insufficient_quota" ||
      error?.optString("type") == "insufficient_quota"
    if (exhausted) HealthCode.QUOTA else HealthCode.RATE_LIMIT
  }
  in 500..599 -> HealthCode.PROVIDER_DOWN
  else -> HealthCode.RESPONSE
}

internal fun GeminiFailureKind.healthCode(): HealthCode = when (this) {
  GeminiFailureKind.AUTH -> HealthCode.CREDENTIAL_REJECTED
  GeminiFailureKind.QUOTA -> HealthCode.RATE_LIMIT
  GeminiFailureKind.UNAVAILABLE -> HealthCode.MODEL
  GeminiFailureKind.NETWORK -> HealthCode.NETWORK
  GeminiFailureKind.SERVER -> HealthCode.PROVIDER_DOWN
  GeminiFailureKind.TIMEOUT -> HealthCode.TIMEOUT
  GeminiFailureKind.INVALID_RESPONSE -> HealthCode.RESPONSE
  GeminiFailureKind.INVALID_REQUEST -> HealthCode.INTERPRETATION
}
