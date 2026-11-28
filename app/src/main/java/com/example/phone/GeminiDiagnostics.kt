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
    val groqConnectionMillis: Long? = null,
    val geminiConnectionMillis: Long? = null,
    val lastFailure: GeminiFailureKind? = null,
    val lastExtractionModel: String? = null,
  )
  @Volatile internal var healthObserver: ((String, HealthCode, Long?) -> Unit)? = null

  internal fun observe(capability: String, code: HealthCode, duration: Long? = null) {
    healthObserver?.invoke(capability, code, duration)
  }

  internal suspend fun <T> measure(capability: String, block: suspend () -> T): T {
    val started = System.nanoTime()
    fun report(code: HealthCode) = observe(capability, code, (System.nanoTime() - started) / 1_000_000L)
    return try {
      block().also { report(HealthCode.VERIFIED) }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
      if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isCancelled != true) report(HealthCode.TIMEOUT)
      throw error
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (error: DecisionException) {
      if (error.kind != GeminiFailureKind.INVALID_REQUEST) report(error.operationalCode)
      throw error
    } catch (error: Exception) {
      report(HealthCode.RESPONSE)
      throw error
    }
  }

  @Volatile private var current = Snapshot()
  fun snapshot(): Snapshot = current
  @Synchronized internal fun decision() { current = current.copy(decisions = current.decisions + 1) }
  @Synchronized internal fun extraction() { current = current.copy(extractions = current.extractions + 1) }
  @Synchronized internal fun extractionModel(model: String) { current = current.copy(lastExtractionModel = model) }
  @Synchronized internal fun verification() { current = current.copy(verifications = current.verifications + 1) }
  @Synchronized internal fun fallback() { current = current.copy(fallbacks = current.fallbacks + 1) }
  @Synchronized internal fun connection(provider: GeminiTransport.Provider, millis: Long) {
    current = when (provider) {
      GeminiTransport.Provider.GEMINI -> current.copy(geminiConnectionMillis = millis)
      GeminiTransport.Provider.GROQ -> current.copy(groqConnectionMillis = millis)
    }
  }
  @Synchronized internal fun finish(durationMillis: Long, failure: GeminiFailureKind?) {
    current = current.copy(interpretations = current.interpretations + 1, lastDurationMillis = durationMillis, lastFailure = failure)
  }
}
