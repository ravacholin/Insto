package com.example.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmniremoteTest {
  @Test fun `registers provider commands and preserves 64 bit targets`() {
    val id = Long.MAX_VALUE
    val params = JSONObject().put("device", "omni:$id").put("appId", id)
    val command = InstinctCapabilities.command("omniremote_open_app", params) as PhoneCommand.Platform
    assertEquals(command, PhoneCommandCodec.decode(PhoneCommandCodec.encode(command)))
  }

  @Test fun `catalog exposes OmniRemote to Decisions`() {
    assertTrue("omniremote_key" in LocalIntentRegistry.default.choices)
    assertTrue("omniremote_sleep_at" in LocalIntentRegistry.default.choices)
  }
}

class OmniremoteRoutingTest {
  private val clock = java.util.Calendar.getInstance()
  private var time = 500L
  private val samsung = tv(7, "Samsung", "Living", 25)
  private val flow = tv(9, "FlowBox", "Dormitorio", 38)
  private var inventory = OmniremoteAdapter.Catalog(listOf(samsung, flow))
  private var prefs = OmniremotePreferences.Settings(default = 7, aliases = mapOf(9L to listOf("tele del cuarto")))
  private val route = OmniremoteRouting({ inventory }, { prefs }, { time })

  @Test fun `uses the last TV for an unqualified app and volume but honors the phone qualifier`() {
    val turn = direct("Prendé la Samsung")
    assertEquals(true, params(turn).getBoolean("on"))
    route.record(turn, PhoneActionResult("orden enviada", outcome = PhoneActionOutcome.HANDOFF))
    assertEquals(25L, params(direct("Abrí YouTube")).getLong("appId"))
    assertEquals("VOL_DOWN", params(direct("Bajá el volumen")).getString("key"))
    assertEquals(PhoneCommand.Volume(VolumeOp.DOWN), direct("Bajá el volumen del celular"))
    time += 120_001
    assertNull(route.resolve("Bajá el volumen", clock))
  }

  @Test fun `discovers a room and aliases but background requests skip conversational defaults`() {
    assertEquals("omni:9", params(direct("Apagá el dormitorio")).getString("device"))
    assertEquals("omni:9", params(direct("Poné pausa en la tele del cuarto")).getString("device"))
    assertTrue(route.resolve("Prendé la tele", clock, background = true) is InterpretationResult.Rejected)
    assertNull(route.resolve("Abrí YouTube", clock, background = true))
  }

  @Test fun `applies multiple targets and an unrelated phone action without dropping any clause`() {
    val text = "Prendé la Samsung y la FlowBox y prendé la linterna"
    val result = route.resolve(text, clock) as InterpretationResult.Plan
    assertEquals(listOf("omni:7", "omni:9"), result.commands.take(2).map { params(it).getString("device") })
    assertEquals(PhoneCommand.Torch(true), result.commands.last())
    assertEquals(result, SemanticCommandGuard.review(InterpretationRequest(text, now = clock), result))
    assertTrue(route.resolve("Prendé la Samsung y la FlowBox; indescriptible", clock) is InterpretationResult.Rejected)
  }

  @Test fun `uses the exact sleep fields and guards explicit parameters`() {
    val orders = listOf("Apagá la Samsung en 30 minutos" to "omniremote_sleep_for",
      "Apagá la Samsung a las 23:30" to "omniremote_sleep_at",
      "Poné el volumen de la FlowBox al 40%" to "omniremote_volume",
      "Escribí «Llegó el texto» en la Samsung" to "omniremote_text")
    for ((text, expected) in orders) {
      val result = route.resolve(text, clock) as InterpretationResult.Direct
      assertEquals(expected, (result.command as PhoneCommand.Platform).argument)
      assertEquals(result, SemanticCommandGuard.review(InterpretationRequest(text, now = clock), result))
    }
  }

  @Test fun `blocks explicit remote routing when the provider is absent`() {
    inventory = OmniremoteAdapter.Catalog(error = OmniremoteClient.NOT_INSTALLED)
    val result = route.resolve("Apagá la tele", clock) as InterpretationResult.Rejected
    assertEquals(OmniremoteClient.NOT_INSTALLED, result.message)
    assertNull(route.resolve("Prendé la linterna", clock))
  }

  @Test fun `requires a matching Decision and retains the explicitly named target`() {
    val text = "Quisiera apagar la Samsung"
    assertTrue(route.resolve(text, clock) is InterpretationResult.Clarification)
    assertTrue(route.resolve(text, clock, "omniremote_key") is InterpretationResult.Rejected)
    assertEquals("omni:7", params((route.resolve(text, clock, "omniremote_power") as InterpretationResult.Direct).command).getString("device"))
  }

  @Test fun `keeps request order and does not treat an unknown room as the phone`() {
    val ordered = route.resolve("Prendé la FlowBox y la Samsung", clock) as InterpretationResult.Plan
    assertEquals(listOf("omni:9", "omni:7"), ordered.commands.map { params(it).getString("device") })
    assertTrue(route.resolve("Abrí Netflix en el patio", clock) is InterpretationResult.Rejected)
  }

  @Test fun `a clearly local command never waits for Binder discovery`() {
    val offline = OmniremoteRouting({ error("no hace falta descubrir dispositivos") }, { prefs }, { time })
    assertNull(offline.resolve("Prendé la linterna", clock))
    assertNull(offline.resolve("Bajá el volumen", clock))
  }

  private fun direct(text: String) = (route.resolve(text, clock) as InterpretationResult.Direct).command
  private fun params(command: PhoneCommand) = JSONObject((command as PhoneCommand.Platform).content!!)
  private fun tv(id: Long, name: String, room: String, app: Long) = OmniremoteClient.Device(
    id, name, room, "ANDROID_TV", setOf("GET_STATE", "POWER_ON", "POWER_OFF", "KEY", "SET_VOLUME", "SET_MUTE",
      "OPEN_APP", "SLEEP_FOR", "SLEEP_AT", "TEXT"), setOf("VOL_DOWN", "PAUSE"),
    setOf("volume"), true, listOf(OmniremoteClient.App(app, "YouTube", "Video", true)))
}

class OmniremoteExecutionTest {
  @Test fun `a lost execute reply reconnects and polls the same id without repeating a toggle`() {
    val id = "execution-test-1"
    val seen = mutableListOf<Pair<String, String>>()
    var binding = 0
    val client = OmniremoteClient(open = {
      val current = ++binding
      link { method, args ->
        when (method) {
          "execute" -> {
            seen += method to (args[1] as String)
            assertEquals("KEY", args[3])
            assertEquals("MUTE", JSONObject(args[4] as String).getString("key"))
            throw OmniremoteFailure("binder murió", reconnect = true)
          }
          "getExecution" -> {
            seen += method to (args[1] as String)
            assertEquals(2, current)
            if (seen.size == 2) reply("PENDING", "ACCEPTED", id) else reply("SENT", "DISPATCHED", id)
          }
          else -> error(method)
        }
      }
    }, now = { 100L }, pause = {})
    val result = client.execute(id, 42, "KEY", JSONObject().put("key", "MUTE"))
    assertEquals(listOf("execute" to id, "getExecution" to id, "getExecution" to id), seen)
    assertFalse(result.verifiedLocally)
    assertEquals(PhoneActionOutcome.HANDOFF, result.outcome)
  }

  @Test fun `a missing execution lookup remains uncertain and is not resubmitted`() {
    var executes = 0
    var queries = 0
    val client = OmniremoteClient(open = { link { method, _ ->
      if (method == "execute") {
        executes++
        throw OmniremoteFailure("respuesta perdida", reconnect = true)
      }
      queries++
      reply("REJECTED", "UNKNOWN_EXECUTION", "execution-test-2")
    } }, now = { 100L }, pause = {})
    val result = client.execute("execution-test-2", 42, "KEY", JSONObject().put("key", "VOL_UP"))
    assertEquals(1, executes)
    assertEquals(1, queries)
    assertEquals(PhoneActionOutcome.UNCERTAIN, result.outcome)
  }

  @Test fun `partial state speaks only observed fields and preserves rounding`() {
    val data = JSONObject().put("volume", 43).put("controlReachable", false)
    val client = OmniremoteClient(open = {
      link { _, _ -> reply("VERIFIED", "DEVICE_UNREACHABLE", "execution-test-3", data) }
    }, now = { 100L }, pause = {})
    val result = client.execute("execution-test-3", 42, "GET_STATE", JSONObject())
    assertTrue(result.verifiedLocally)
    assertTrue(result.message.contains("43%"))
    assertFalse(result.message.contains("apagado"))
    assertFalse(result.message.contains("en pausa"))
  }

  @Test fun `authorization lost after admission remains uncertain`() {
    val id = "execution-test-4"
    var executions = 0
    val client = OmniremoteClient(open = { link { method, _ ->
      if (method != "execute") throw SecurityException("firma cambiada")
      executions++
      reply("PENDING", "ACCEPTED", id)
    } }, now = { 100L }, pause = {})
    val result = client.execute(id, 42, "KEY", JSONObject().put("key", "MUTE"))
    assertEquals(1, executions)
    assertEquals(PhoneActionOutcome.UNCERTAIN, result.outcome)
  }

  private fun reply(status: String, code: String, id: String, data: JSONObject? = null) =
    JSONObject().put("apiVersion", 1).put("status", status).put("code", code).put("executionId", id)
      .apply { data?.let { put("data", it) } }.toString()

  private fun link(handle: (String, Array<Any?>) -> String): OmniremoteClient.Link {
    val type = com.example.external.IRemoteCommandService::class.java
    val api = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
      handle(method.name, args ?: emptyArray())
    } as com.example.external.IRemoteCommandService
    return object : OmniremoteClient.Link {
      override fun <T> call(block: (com.example.external.IRemoteCommandService) -> T): T = block(api)
      override fun close() = Unit
    }
  }
}

class OmniremotePersistenceTest {
  private val remote = InstinctCapabilities.command("omniremote_key",
    JSONObject().put("device", "omni:7").put("key", "VOL_DOWN"))

  @Test fun `workflow recovers the original step and never retries an uncertain key`() {
    val running = WorkflowRunner()
    running.prepare(listOf(remote, PhoneCommand.Torch(true)))
    running.confirm()
    assertEquals(remote, running.next())
    val id = running.snapshot!!.remoteExecutionId()
    val recovered = WorkflowRunner(running.snapshot)
    recovered.retry()
    assertNull(recovered.next())
    assertEquals(id, recovered.snapshot!!.remoteExecutionId())
    recovered.recoverRemote(id, PhoneActionResult("enviado; efecto sin confirmar", outcome = PhoneActionOutcome.HANDOFF))
    assertEquals(StepStatus.SENT, recovered.snapshot!!.steps.first().status)
    assertEquals(PhoneCommand.Torch(true), recovered.next())
  }

  @Test fun `scheduled orders retain their original device at firing`() {
    val stored = ScheduledOrder("test-id", "en 1 minuto: bajá el volumen de la Samsung",
      "bajá el volumen de la Samsung", 60_000, "settings", null, "en 1 minuto", 1_000,
      remoteCommand = remote)
    val recovered = InstinctScheduleStore.decode(InstinctScheduleStore.encode(listOf(stored))).single()
    assertEquals(ScheduleFirePolicy.Decision.Run(remote),
      ScheduleFirePolicy.decide(recovered, true, "settings", 60_000) { error("no se debe reasignar el destino") })
  }
}
