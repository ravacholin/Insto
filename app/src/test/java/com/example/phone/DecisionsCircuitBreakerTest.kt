package com.example.phone

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DecisionsCircuitBreakerTest {
  @Test fun onlyOneConcurrentCallerProbesAndCancellationReleasesTheProbe() {
    var now = 0L
    val breaker = DecisionsCircuitBreaker(failureLimit = 1, recoveryMillis = 5, clock = { now })
    breaker.complete(requireNotNull(breaker.acquire()), GeminiFailureKind.SERVER)
    assertNull(breaker.acquire())
    now = 5
    val workers = Executors.newFixedThreadPool(8)
    try {
      val start = CountDownLatch(1)
      val attempts = List(8) { workers.submit<DecisionsCircuitBreaker.Attempt?> {
        start.await()
        breaker.acquire()
      } }
      start.countDown()
      val probe = attempts.mapNotNull { it.get(5, TimeUnit.SECONDS) }.single()
      assertTrue(probe.probe)
      breaker.abandon(probe)
      val failedProbe = requireNotNull(breaker.acquire())
      breaker.complete(failedProbe, GeminiFailureKind.NETWORK)
      assertNull(breaker.acquire())
      now = 10
      breaker.complete(requireNotNull(breaker.acquire()), null)
      assertFalse(requireNotNull(breaker.acquire()).probe)
    } finally { workers.shutdownNow() }
  }

  @Test fun quotaOpensImmediatelyAndAnOlderResponseCannotCloseTheCircuit() {
    var now = 0L
    val breaker = DecisionsCircuitBreaker(recoveryMillis = 5, quotaRecoveryMillis = 60, clock = { now })
    val older = requireNotNull(breaker.acquire())
    breaker.complete(requireNotNull(breaker.acquire()), GeminiFailureKind.QUOTA)
    breaker.complete(older, null)
    now = 30
    assertNull(breaker.acquire())
    now = 60
    assertTrue(requireNotNull(breaker.acquire()).probe)
  }

  @Test fun successResetsConsecutiveFailuresAndAuthDoesNotOpenATemporaryCircuit() {
    val breaker = DecisionsCircuitBreaker()
    for (outcome in listOf(GeminiFailureKind.SERVER, null, GeminiFailureKind.SERVER,
      GeminiFailureKind.AUTH, GeminiFailureKind.SERVER)) {
      breaker.complete(requireNotNull(breaker.acquire()), outcome)
    }
    assertNotNull(breaker.acquire())
  }
}
