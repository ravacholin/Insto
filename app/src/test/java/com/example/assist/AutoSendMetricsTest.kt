package com.example.assist

import com.example.assist.AutoSendHealth.State
import com.example.assist.AutoSendMetrics.Flavor
import com.example.assist.AutoSendMetrics.Kind
import com.example.assist.AutoSendMetrics.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSendMetricsTest {
  private val installed = AutoSendHealth.Installed(Flavor.WHATSAPP, 262000, "2.26.20")

  @Test fun countsByStrategyAndKeepsTheLastConfirmedVersion() {
    var s = AutoSendMetrics.Snapshot()
    s = AutoSendMetrics.record(s, Flavor.WHATSAPP, 261000, Kind.TEXT, "send.id.send", Outcome.CONFIRMED, 1)
    s = AutoSendMetrics.record(s, Flavor.WHATSAPP, 261000, Kind.TEXT, "send.id.send", Outcome.CONFIRMED, 2)
    s = AutoSendMetrics.record(s, Flavor.WHATSAPP, 262000, Kind.AUDIO, "send.label", Outcome.UNCERTAIN, 3)
    assertEquals(2, s.confirmed)
    assertEquals(1, s.failed)
    assertEquals(261000L, s.lastConfirmed[Flavor.WHATSAPP])
    assertEquals(Outcome.UNCERTAIN, s.lastAttempt?.outcome)
    assertEquals(s, AutoSendMetrics.decode(AutoSendMetrics.encode(s)))
  }

  @Test fun strategyIsAClosedShapeAndDamagedFilesReadEmpty() {
    val s = AutoSendMetrics.record(AutoSendMetrics.Snapshot(), Flavor.BUSINESS, 1, Kind.TEXT, "Hola +44 7700 900123", Outcome.TIMEOUT, 1)
    assertEquals(AutoSendMetrics.NO_STRATEGY, s.rows.single().strategy)
    for (raw in listOf(null, "", "{", "{\"version\":9}", "x".repeat(70_000))) assertEquals(AutoSendMetrics.Snapshot(), AutoSendMetrics.decode(raw))
  }

  @Test fun theFileNeverContainsMessageContentOrContacts() {
    var s = AutoSendMetrics.Snapshot()
    repeat(150) { i ->
      s = AutoSendMetrics.record(s, Flavor.WHATSAPP, 262000L + i, Kind.entries[i % 3], "send.id.send", Outcome.entries[i % Outcome.entries.size], i.toLong())
    }
    assertEquals(AutoSendMetrics.MAX_ROWS, s.rows.size)
    val json = AutoSendMetrics.encode(s)
    for (secret in listOf(WhatsAppSelectorFixtures.DRAFT, "Contacto Prueba", "447700900", "instinct_", ".m4a")) {
      assertFalse(secret, json.contains(secret))
    }
  }

  @Test fun healthIsReadyWhenEnabledAndOnlyRecentFailuresAskForReview() {
    val empty = AutoSendMetrics.Snapshot()
    assertEquals(State.DISABLED, AutoSendHealth.evaluate(false, false, installed, empty, 10).state)
    assertEquals(State.NEEDS_REVIEW, AutoSendHealth.evaluate(true, false, installed, empty, 10).state)
    assertEquals(State.NEEDS_REVIEW, AutoSendHealth.evaluate(true, true, null, empty, 10).state)
    // No confirmed history is not a problem: an enabled, connected service is ready.
    assertEquals(State.READY, AutoSendHealth.evaluate(true, true, installed, empty, 10).state)

    val confirmed = AutoSendMetrics.record(empty, Flavor.WHATSAPP, 262000, Kind.TEXT, "send.id.send", Outcome.CONFIRMED, 1)
    assertEquals(State.READY, AutoSendHealth.evaluate(true, true, installed, confirmed, 10).state)
    // A WhatsApp update does not ask for review either.
    assertEquals(State.READY,
      AutoSendHealth.evaluate(true, true, installed.copy(versionCode = 263000, versionName = "2.26.30"), confirmed, 10).state)
    val failedAfter = AutoSendMetrics.record(confirmed, Flavor.WHATSAPP, 262000, Kind.AUDIO, "send.label", Outcome.NOT_FOUND, 2)
    assertEquals(State.NEEDS_REVIEW, AutoSendHealth.evaluate(true, true, installed, failedAfter, 10).state)
    // An old failure is no longer shown.
    assertEquals(State.READY,
      AutoSendHealth.evaluate(true, true, installed, failedAfter, 2 + AutoSendHealth.FAILURE_RELEVANCE_MILLIS).state)
    // A failure with the other flavor does not change this one.
    val otherFlavor = AutoSendMetrics.record(confirmed, Flavor.BUSINESS, 5, Kind.TEXT, null, Outcome.TIMEOUT, 3)
    assertEquals(State.READY, AutoSendHealth.evaluate(true, true, installed, otherFlavor, 10).state)
  }
}
