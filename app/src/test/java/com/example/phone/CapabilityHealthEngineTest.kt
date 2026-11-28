package com.example.phone

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CapabilityHealthEngineTest {
  @get:Rule val folder = TemporaryFolder()
  private val id = "microphone"
  private fun file() = File(folder.root, "health.json")

  @Test fun permissionIsNotVerificationAndSuccessExpires() = runTest {
    var time = 10_000L
    var code = HealthCode.PREFLIGHT
    val engine = CapabilityHealthEngine(backgroundScope, HealthStore(file()),
      listOf(HealthCheck(id, ttlMillis = 100) { HealthObservation(code) }), wallClock = { time }, disk = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
    assertEquals(HealthState.UNKNOWN, engine.result(id).state)
    assertEquals(HealthState.AVAILABLE, engine.check(id).state)
    engine.record(id, HealthObservation(HealthCode.VERIFIED, HealthEvidence.EXECUTION))
    assertEquals(HealthState.VERIFIED, engine.check(id, refresh = true).state)
    engine.invalidate(setOf(id))
    code = HealthCode.PERMISSION
    assertEquals(HealthState.PERMISSION_MISSING, engine.check(id).state)
    assertEquals(10_000L, engine.result(id).lastSuccess)
    time += 101
    assertEquals(HealthState.UNKNOWN, engine.result(id).state)
  }

  @Test fun coalescesAndCancelsWhenLastCallerLeaves() = runTest {
    var calls = 0
    var stopped = false
    val engine = CapabilityHealthEngine(backgroundScope, HealthStore(file()), listOf(HealthCheck(id) {
      calls++
      try { awaitCancellation() } finally { stopped = true }
    }), disk = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
    val one = async { engine.check(id) }
    val two = async { engine.check(id) }
    runCurrent()
    assertEquals(1, calls)
    one.cancel()
    runCurrent()
    assertFalse(stopped)
    two.cancel()
    runCurrent()
    assertTrue(stopped)
  }

  @Test fun recoveryBudgetSurvivesProcessDeathAndUnsafeProbesNeverRunAutomatically() = runTest {
    var calls = 0
    var time = 100_000L
    val check = HealthCheck(id, recovery = { calls++ }) { HealthObservation(HealthCode.PROVIDER_DOWN) }
    val unsafe = HealthCheck("external", effect = HealthEffect.EXTERNAL) {
      error("No se autorizó ningún efecto externo")
    }
    fun engine() = CapabilityHealthEngine(backgroundScope, HealthStore(file()), listOf(check, unsafe), wallClock = { time }, disk = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
    val first = engine()
    assertFalse("external" in first.checkAll())
    assertEquals(HealthCode.CONSENT, first.check("external").observation.code)
    assertEquals(false, first.recover(id))
    assertNull(engine().recover(id))
    time += 60_000
    assertEquals(false, engine().recover(id))
    time += 120_000
    assertNull(engine().recover(id))
    assertEquals(2, calls)
  }

  @Test fun declaredAndConfiguredRemainSeparateFromFunctionalFailure() = runTest {
    val engine = CapabilityHealthEngine(backgroundScope, HealthStore(file()), listOf(HealthCheck(id) {
      HealthObservation(HealthCode.PREFLIGHT, configured = true, authorized = true)
    }), disk = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
    engine.check(id)
    repeat(2) { engine.record(id, HealthObservation(HealthCode.MICROPHONE, HealthEvidence.EXECUTION), duration = 8) }
    val result = engine.check(id, refresh = true)
    assertEquals(HealthState.DEGRADED, result.state)
    assertEquals(true, result.observation.authorized)
    assertEquals(2, result.consecutiveFailures)
    assertEquals(2L, result.failures)
    assertEquals(8L, result.percentile(95))
  }

  @Test fun deadlineAndSuccessAfterRecoveryAreDistinct() = runTest {
    var ready = false
    val engine = CapabilityHealthEngine(backgroundScope, HealthStore(file()), listOf(
      HealthCheck(id, timeoutMillis = 30, recovery = { ready = true }) {
        if (!ready) delay(10_000)
        HealthObservation(HealthCode.VERIFIED)
      }),
      disk = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
    assertEquals(HealthCode.TIMEOUT, engine.check(id).observation.code)
    assertEquals(true, engine.recover(id))
    assertEquals(1L, engine.result(id).recoveries)
    assertEquals(0, engine.result(id).consecutiveFailures)
    assertEquals(true, engine.snapshots.value.incidents.last().recovery)
  }
}
