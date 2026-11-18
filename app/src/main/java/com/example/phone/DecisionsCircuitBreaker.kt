package com.example.phone

/** In-memory circuit for Decisions. Epochs prevent late calls from resetting a newer circuit. */
class DecisionsCircuitBreaker(
  private val failureLimit: Int = 2,
  private val recoveryMillis: Long = 30_000,
  private val quotaRecoveryMillis: Long = 60_000,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  init {
    require(failureLimit > 0 && recoveryMillis > 0 && quotaRecoveryMillis > 0)
  }

  internal data class Attempt(val epoch: Long, val probe: Boolean)
  private var epoch = 0L
  private var failures = 0
  private var retryAt: Long? = null
  private var probing = false

  @Synchronized internal fun acquire(): Attempt? {
    val deadline = retryAt ?: return Attempt(epoch, false)
    if (clock() < deadline || probing) return null
    probing = true
    return Attempt(epoch, true)
  }

  @Synchronized internal fun complete(attempt: Attempt, failure: GeminiFailureKind?) {
    if (attempt.epoch != epoch) return
    probing = false
    when (failure) {
      GeminiFailureKind.QUOTA -> open(quotaRecoveryMillis)
      GeminiFailureKind.UNAVAILABLE -> open(recoveryMillis)
      GeminiFailureKind.NETWORK, GeminiFailureKind.SERVER, GeminiFailureKind.TIMEOUT,
      GeminiFailureKind.INVALID_RESPONSE -> if (attempt.probe || ++failures >= failureLimit) open(recoveryMillis)
      else -> {
        failures = 0
        retryAt = null
        if (attempt.probe) epoch++
      }
    }
  }

  @Synchronized internal fun abandon(attempt: Attempt) {
    if (attempt.epoch == epoch && attempt.probe) probing = false
  }

  private fun open(delay: Long) {
    epoch++
    retryAt = clock() + delay
  }
}
