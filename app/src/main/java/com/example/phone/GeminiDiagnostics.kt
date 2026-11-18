package com.example.phone

/** Process-local aggregate only: no user text, credentials, request bodies or persistence. */
object GeminiDiagnostics {
  data class Snapshot(
    val interpretations: Long = 0,
    val decisions: Long = 0,
    val extractions: Long = 0,
    val fallbacks: Long = 0,
    val verifications: Long = 0,
    val lastDurationMillis: Long = 0,
    val lastFailure: GeminiFailureKind? = null,
  )
  @Volatile private var current = Snapshot()
  fun snapshot(): Snapshot = current
  @Synchronized internal fun decision() { current = current.copy(decisions = current.decisions + 1) }
  @Synchronized internal fun extraction() { current = current.copy(extractions = current.extractions + 1) }
  @Synchronized internal fun verification() { current = current.copy(verifications = current.verifications + 1) }
  @Synchronized internal fun fallback() { current = current.copy(fallbacks = current.fallbacks + 1) }
  @Synchronized internal fun finish(durationMillis: Long, failure: GeminiFailureKind?) {
    current = current.copy(interpretations = current.interpretations + 1, lastDurationMillis = durationMillis, lastFailure = failure)
  }
}
