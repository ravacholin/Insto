package com.example.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppShortcutTest {
  private val maps = AppShortcut(ShortcutKey("com.maps", "shared", 0), "Buscar destinos", "Mapas", manifest = true)
  private val other = maps.copy(key = ShortcutKey("com.other", "shared", 0), app = "Otro")

  @Test fun identityIncludesPackageAndProfile() {
    assertNotEquals(maps.key.token, other.key.token)
    assertNotEquals(maps.key.token, maps.key.copy(profile = 10).token)
    assertEquals(maps.command(), PhoneCommandCodec.decode(PhoneCommandCodec.encode(maps.command())))
  }

  @Test fun resolvesAliasesAndQualifiedNamesWithoutOverridingNativeCommands() {
    val catalog = listOf(maps.copy(preferences = ShortcutPreferences(alias = "Mis destinos")), other)
    assertEquals(maps.command(), (ShortcutResolver.route("mis destinos", PhoneRoute.Instinct, catalog) as PhoneRoute.Local).command)
    assertEquals(maps.command(), (ShortcutResolver.route("ejecutá el acceso directo buscar destinos de Mapas", PhoneRoute.Instinct, catalog) as PhoneRoute.Local).command)
    val native = PhoneRoute.Local(PhoneCommand.Torch(true))
    assertSame(native, ShortcutResolver.route("prendé la linterna", native, catalog))
  }

  @Test fun ambiguityNegationAndDisabledShortcutsNeverDispatch() {
    assertTrue(ShortcutResolver.route("ejecutá el acceso directo buscar destinos", PhoneRoute.Instinct, listOf(maps, other)) is PhoneRoute.Clarify)
    assertSame(PhoneRoute.Instinct, ShortcutResolver.route("no ejecutes el acceso directo buscar destinos", PhoneRoute.Instinct, listOf(maps)))
    assertNull(ShortcutResolver.match("buscar destinos", listOf(maps.copy(available = false))).singleOrNull())
    assertEquals(maps, ShortcutResolver.match("buscr destinos", listOf(maps), fuzzy = true).single())
  }

  @Test fun onlyCurrentExplicitGrantsArePublishedAndRecognized() {
    val granted = maps.copy(grant = "a".repeat(64))
    assertEquals(listOf(granted.grant), shortcutRemoteTargets(true, listOf(granted, other)))
    assertTrue(shortcutRemoteTargets(false, listOf(granted)).isEmpty())
    assertTrue(shortcutRemoteTargets(true, listOf(granted.copy(available = false))).isEmpty())
    assertNull(shortcutRemoteTarget(true, listOf(granted), maps.key.token))
  }

  @Test fun remoteCodecRejectsPublisherIdsAndUnexpectedFields() {
    val id = ShortcutResolver.CAPABILITY
    assertTrue(runCatching { InstinctCapabilities.command(id, JSONObject().put("shortcut", "shared")) }.isFailure)
    val parameters = JSONObject().put("shortcut", "a".repeat(64))
    val command = InstinctCapabilities.command(id, parameters) as PhoneCommand.Platform
    assertEquals(PlatformAction.INSTINCT_CAPABILITY, command.action)
    assertFalse(InstinctCapabilities.platform(command).first.runsLocked)
    assertTrue(runCatching { InstinctCapabilities.command(id, parameters.put("package", "com.maps")) }.isFailure)
  }

  @Test fun rediscoveryKeepsOnlyUnchangedGrants() {
    val granted = maps.copy(grant = "a".repeat(64), revision = 10)
    assertEquals(granted.grant, reconcileShortcuts(listOf(granted), listOf(maps.copy(revision = 10))).single().grant)
    assertNull(reconcileShortcuts(listOf(granted), listOf(maps.copy(revision = 11))).single().grant)
    val removed = reconcileShortcuts(listOf(granted), emptyList()).single()
    assertFalse(removed.available)
    assertNull(removed.grant)
  }

  @Test fun clarificationUsesTheOriginalOptionsAndSkipsRemovedEntries() {
    val route = ShortcutResolver.result(listOf(maps, other)) as PhoneRoute.Clarify
    assertEquals(other.command(), (ShortcutResolver.reply(route.context, "dos", listOf(other)) as PhoneRoute.Local).command)
    assertTrue(ShortcutResolver.reply(route.context, "uno", listOf(other)) is PhoneRoute.Unsupported)
  }

  @Test fun unavailableExactNameCannotFallBackToSimilarShortcut() {
    val missing = maps.copy(available = false)
    val near = other.copy(name = "Buscar destino")
    assertTrue(ShortcutResolver.route("ejecutá el acceso directo buscar destinos", PhoneRoute.Instinct, listOf(missing, near)) is PhoneRoute.Unsupported)
  }

  @Test fun genericOpenCannotReplaceAnExplicitShortcutOrder() {
    val parser = PhoneCommandParser().apply {
      shortcutRoutes = { text, native -> ShortcutResolver.route(text, native, listOf(maps)) }
    }
    assertEquals(PhoneRoute.Instinct, parser.parse("abrí el acceso directo inexistente"))
    assertTrue(parser.parse("abrí el acceso directo buscar destinos pero nunca lo ejecutes") is PhoneRoute.Unsupported)
    assertTrue(parser.parse("abrí un destino lejano en Mapas") is PhoneRoute.Unsupported)
    assertEquals(PhoneCommand.OpenApp("mapas"), (parser.parse("abrí Mapas") as PhoneRoute.Local).command)
    val quoted = "ejecutá el acceso directo «Buscar destinos» de Mapas"
    assertEquals(maps.command(), (parser.parse(quoted) as PhoneRoute.Local).command)
    assertEquals(SemanticCommandGuard.Verdict.Accepted,
      SemanticCommandGuard.check(quoted, listOf(maps.command()), java.util.Calendar.getInstance()))
  }

  @Test fun providerOnlySeesPublicShortlistedTerms() {
    val private = other.copy(name = "Llamar a Ana", manifest = false, dynamic = true)
    val choices = ShortcutResolver.candidates("destinos buscar de mapas", listOf(maps, private))
    assertEquals(listOf(maps), choices)
    assertEquals("destinos buscar de mapas", ShortcutResolver.providerInput("ejecutá el acceso directo destinos buscar de mapas", choices))
    assertNull(ShortcutResolver.providerInput("ejecutá el acceso directo buscar destinos Ana", choices))
    assertNull(ShortcutResolver.providerInput("ejecutá el acceso directo buscar destinos nunca", choices))
  }

  @Test fun backupEncodesPreferencesWithoutGrantsAndRejectsAdditionalFields() {
    val values = mapOf(maps.key.token to ShortcutPreferences("mis mapas", favorite = true, enabled = false))
    val encoded = encodeShortcutPreferences(values)
    assertEquals(values, decodeShortcutPreferences(encoded))
    encoded.getJSONObject(maps.key.token).put("grant", "a".repeat(64))
    assertTrue(runCatching { decodeShortcutPreferences(encoded) }.isFailure)
  }

  @Test fun remotePlansRequireASingleTerminalHandoffWithoutFallback() {
    val shortcut = """{"id":"s","kind":"action","capability":"execute_app_shortcut","params":{"shortcut":"TOKEN"}}"""
      .replace("TOKEN", "a".repeat(64))
    val torch = """{"id":"t","kind":"action","capability":"torch","params":{"enabled":true}}"""
    fun parse(steps: String) = InstinctStructuredPlan.parse(
      """plan-json {"version":1,"steps":[$steps]}""", java.util.Calendar.getInstance())
    assertTrue(parse("$torch,$shortcut") is InstinctPlanSpec.Parse.Valid)
    assertTrue(parse("$shortcut,$torch") is InstinctPlanSpec.Parse.Invalid)
    assertTrue(parse(shortcut.dropLast(1) + ""","fallback":{"capability":"torch","params":{"enabled":true}}}""")
      is InstinctPlanSpec.Parse.Invalid)
    assertFalse(InstinctProposalValidator.allowed(maps.command()))
  }


  @Test fun declaredAppRulesOutAConflictingShortcutInAnotherApp() {
    val conflicting = maps.copy(name = "Buscar casa de Otro")
    assertTrue(ShortcutResolver.match("Buscar casa de Otro", listOf(conflicting, other)).isEmpty())
  }

  @Test fun signedCatalogPublishesOnlyOpaqueAuthorizedTargets() {
    val token = "b".repeat(64)
    val granted = maps.copy(grant = token, preferences = ShortcutPreferences(alias = "llamar a Ana"))
    val targets = shortcutRemoteTargets(true, listOf(granted, other))
    val catalog = InstinctProtocolCatalog.json(emptyList(), instoVersion = "test",
      capabilityTargets = mapOf(ShortcutResolver.CAPABILITY to targets))
    assertTrue(catalog.canonical.contains(token))
    assertFalse(catalog.canonical.contains(granted.name))
    assertFalse(catalog.canonical.contains(granted.preferences.alias))
    assertFalse(catalog.canonical.contains(granted.key.packageName))
  }

  @Test fun coordinatorBuildsAWorkflowWithShortcutsAndDoesNotSendAnAmbiguousStep() = kotlinx.coroutines.runBlocking {
    val parser = PhoneCommandParser().apply {
      shortcutRoutes = { text, native -> ShortcutResolver.route(text, native, listOf(maps, other)) }
    }
    val router = HybridPhoneRouter({ true }, DecisionClassifier { error("A provider cannot select a workflow shortcut") })
    val coordinator = GeminiCoordinator(router, StructuredCommandExtractor { error("A provider cannot extract a shortcut") },
      parseWithShortcuts = parser::parse)
    val order = "prendé la linterna y ejecutá el acceso directo buscar destinos de Mapas"
    val plan = coordinator.interpret(InterpretationRequest(order), parser.parse(order)) as InterpretationResult.Plan
    assertEquals(listOf(PhoneCommand.Torch(true), maps.command()), plan.commands)
    val ambiguous = order.removeSuffix(" de Mapas")
    assertTrue(coordinator.interpret(InterpretationRequest(ambiguous), parser.parse(ambiguous)) is InterpretationResult.Rejected)
  }

}
