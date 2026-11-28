package com.example.phone

import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityHealthProtocolTest {
  @Test fun providerFailuresRemainDistinct() {
    assertEquals(HealthCode.CREDENTIAL_REJECTED, providerHealth(401))
    assertEquals(HealthCode.RATE_LIMIT, providerHealth(429))
    assertEquals(HealthCode.QUOTA, providerHealth(429, """{"error":{"code":"insufficient_quota"}}"""))
    assertEquals(HealthCode.PROVIDER_DOWN, providerHealth(503))
    assertEquals(HealthCode.MODEL, providerHealth(404))
    assertEquals(HealthCode.TIMEOUT, GeminiFailureKind.TIMEOUT.healthCode())
    assertEquals(HealthCode.NETWORK, GeminiFailureKind.NETWORK.healthCode())
  }

  @Test fun diagnosticPhrasesAreLocalWithDecisionsEnabledAndNeverRemoteControls() {
    val parser = PhoneCommandParser()
    val router = HybridPhoneRouter({ true }, { error("El diagnóstico no usa Gemini") })
    for (entry in CapabilityHealthCommand.entries) {
      for (phrase in entry.phrases) {
        val route = parser.parse(phrase)
        assertEquals(phrase, PhoneRoute.Local(entry.command), route)
        assertFalse(router.needsDecision(phrase, route))
        assertTrue(InstinctProposalValidator.check(phrase, parser, Calendar.getInstance()) is ProposalCheck.Invalid)
      }
    }
    assertFalse(parser.parse("no quiero que hagas un diagnóstico completo") is PhoneRoute.Local)
    assertTrue(InstinctProposalValidator.check("ver salud operativa", parser, Calendar.getInstance()) is ProposalCheck.Query)
  }

  @Test fun availabilityFieldsRemainCompatibleAndHealthRespectsExistingAuthorization() {
    val good = HealthDiagnostic("volume", statusOf(HealthCode.VERIFIED), checkedAt = 10, expiresAt = 100)
    val bad = good.copy(observation = statusOf(HealthCode.PROVIDER_DOWN))
    val policy = InstinctAutonomyPolicy().with(InstinctCategory.LINTERNA, InstinctAutonomy.NUNCA)
    fun catalog(row: HealthDiagnostic, mailbox: Boolean = true) = InstinctProtocolCatalog.json(
      emptyList(), policy, InstinctCatalogChannels(mailbox = mailbox), "1.26.0",
      availability = mapOf("volume" to "disponible"), capabilityHealth = mapOf("volume" to row.wire(), "torch" to bad.wire()))
    val first = catalog(good)
    assertEquals(first.hash, catalog(good).hash)
    assertNotEquals(first.hash, catalog(bad).hash)
    val root = JSONObject(first.canonical)
    assertEquals(8, root.getInt("version_protocolo"))
    val capabilities = root.getJSONArray("capacidades")
    val entries = (0 until capabilities.length()).map(capabilities::getJSONObject).associateBy { it.getString("id") }
    assertEquals("disponible", entries.getValue("volume").getString("disponibilidad"))
    assertEquals("VERIFIED", entries.getValue("volume").getJSONObject("salud").getString("estado"))
    assertFalse(entries.getValue("torch").has("salud"))
    assertFalse(catalog(good, mailbox = false).canonical.contains("\"salud\""))
    assertFalse(good.at(100).wire().containsKey("disponible"))
    assertFalse(good.copy(observation = good.observation.copy(authorized = true)).at(100).wire().containsKey("autorizada"))
    assertEquals(good.publicationKey(), good.copy(checkedAt = 20, expiresAt = 110).publicationKey())
    assertNotEquals(good.publicationKey(), good.copy(checkedAt = 105, expiresAt = 195).publicationKey())
  }

  @Test fun reportHasNoPrivateIdentifiersOrPayloads() {
    val records = listOf(
      HealthDiagnostic("microphone", statusOf(HealthCode.VERIFIED)),
      HealthDiagnostic("secret-token"), HealthDiagnostic("omniremote.device.abc"),
    ).associateBy(HealthDiagnostic::id)
    val report = HealthSnapshot(records, listOf(HealthIncident("rutina de Ana", HealthCode.INTERNAL, 10)))
      .sanitizedReport(100, setOf("microphone", "omniremote.device.abc"))
    assertTrue(report.contains("microphone"))
    assertFalse(report.contains("secret-token"))
    assertFalse(report.contains("Ana"))
    assertFalse(report.contains("omniremote.device"))
  }

  private fun statusOf(code: HealthCode) = HealthObservation(code, HealthEvidence.EXECUTION)
}
