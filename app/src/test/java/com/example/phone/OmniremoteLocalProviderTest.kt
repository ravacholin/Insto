package com.example.phone

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.ServiceConnection
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.external.IRemoteCommandService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniremoteLocalProviderTest {
  private val remote = Remote()
  private val context = BindingContext(remote)
  private val provider = OmniremoteLocalProvider()
  private val adapter = OmniremoteAdapter(listOf(provider))

  @Test fun healthCatalogDoesNotExecuteAndADeviceFailureLeavesProviderHealthy() = offMain {
    shadowOf(context.packageManager).installPackage(PackageInfo().apply {
      packageName = OmniremoteBinding.PACKAGE
    })
    val discovered = mutableMapOf<String, HealthCheck>()
    val health = OmniremoteHealth(context, provider) { discovered[it.id] = it }
    kotlinx.coroutines.runBlocking {
      val catalog = health.checks().single { it.id == "omniremote.catalog" }
      val device = OmniremoteHealth.deviceId(TV)
      assertEquals(HealthCode.VERIFIED, catalog.probe().code)
      assertEquals(HealthCode.PREFLIGHT, discovered.getValue(device).probe().code)
      assertTrue(remote.requests.isEmpty())
      val read = discovered.getValue("$device.read")
      assertEquals(HealthEffect.RESOURCE, read.effect)
      remote.stateFailure = "DEVICE_UNREACHABLE"
      assertEquals(HealthCode.DEVICE_UNREACHABLE, read.probe().code)
      assertEquals(HealthCode.VERIFIED, catalog.probe().code)
      assertEquals(listOf("GET_STATE"), remote.requests.map { it.command })
    }
  }

  @Test fun bindsExplicitlyNegotiatesV1AndCloses() = offMain {
    remote.version = 2
    assertEquals(listOf(TV), adapter.targets(context, power))
    assertEquals("com.aistudio.omnitvremote.qvrtm.BIND_EXTERNAL_CONTROL", context.intent?.action)
    assertEquals(ComponentName("com.aistudio.omnitvremote.qvrtm", "com.example.external.RemoteCommandService"),
      context.intent?.component)
    assertEquals(Context.BIND_AUTO_CREATE, context.flags)
    assertEquals(1, remote.versionCalls)
    assertEquals(listOf(0L), remote.cursors)
    assertEquals(context.binds, context.unbinds)

    remote.version = 0
    assertTrue(adapter.blocker(context, power, null)!!.reason.contains("versión"))
    assertEquals(listOf(0L), remote.cursors)
    assertEquals(context.binds, context.unbinds)
  }

  @Test fun productionRegistryDiscoversAndExecutesThroughTheLocalProvider() = offMain {
    val discovered = InstinctDeviceCapabilities.discover(context, mute)
    assertNull(discovered.blocker)
    assertEquals(listOf(TV), discovered.targets)

    val command = InstinctCapabilities.command(mute.id, params("muted", true)) as PhoneCommand.Platform
    val result = InstinctDeviceCapabilities.execute(context, command, "registry-execution", foreground = false)
    assertTrue(result.message, result.verifiedLocally)
    assertEquals(listOf("SET_MUTE", "GET_STATE"), remote.requests.map { it.command })
    assertEquals(context.binds, context.unbinds)
  }

  @Test fun deniedCallerNeverDiscoversOrExecutes() = offMain {
    remote.authorized = false
    assertTrue(adapter.blocker(context, power, null)!!.reason.contains("autoriza a Insto"))
    assertTrue(remote.cursors.isEmpty())
    assertTrue(remote.requests.isEmpty())
    assertEquals(context.binds, context.unbinds)
  }

  @Test fun releasesTheConnectionWhenAndroidRejectsTheBind() = offMain {
    context.bindAllowed = false
    assertTrue(adapter.blocker(context, power, null)!!.reason.contains("servicio local"))
    assertEquals(0, remote.versionCalls)
    assertEquals(1, context.unbinds)
  }

  @Test fun timesOutWaitingForServiceAndUnbinds() = offMain {
    context.connect = false
    val timedAdapter = OmniremoteAdapter(listOf(OmniremoteLocalProvider(OmniremoteBinding(callTimeoutMillis = 50))))
    assertTrue(timedAdapter.blocker(context, power, null)!!.reason.contains("temporalmente"))
    assertEquals(1, context.binds)
    assertEquals(1, context.unbinds)
  }

  @Test fun paginatesWithoutLosingIdPrecisionAndOffersSupportedControls() = offMain {
    remote.pages = mapOf(0L to (listOf(ID) to ID), ID to (listOf(ID + 1, ID + 2) to null))
    remote.device = { id -> when (id) {
      ID -> device(id, "ANDROID_TV", listOf("POWER_ON", "POWER_OFF", "SET_VOLUME", "SET_MUTE"),
        listOf("power", "volume", "muted"))
      ID + 1 -> device(id, "SAMSUNG_TIZEN", listOf("POWER_ON", "POWER_OFF"), emptyList())
      else -> device(id, "MACOS", listOf("SET_VOLUME", "SET_MUTE"), listOf("volume", "muted"))
    } }
    assertEquals(listOf(TV, "omniremote:${ID + 1}"), adapter.targets(context, power))
    assertEquals(listOf(TV, "omniremote:${ID + 2}"), adapter.targets(context, volume))
    assertEquals(listOf(TV, "omniremote:${ID + 2}"), adapter.targets(context, mute))
    assertEquals(listOf(0L, ID, 0L, ID, 0L, ID), remote.cursors)
    assertTrue(remote.details.contains(ID + 1))
  }

  @Test fun mapsCommandsAndVerifiesUsingFreshState() = offMain {
    val inputs = listOf(
      Triple(power, params("on", true), "POWER_ON" to "{}"),
      Triple(power, params("on", false), "POWER_OFF" to "{}"),
      Triple(volume, params("percent", 100), "SET_VOLUME" to """{"level":100}"""),
      Triple(mute, params("muted", false), "SET_MUTE" to """{"muted":false}"""),
    )
    for ((index, input) in inputs.withIndex()) {
      val (spec, arguments, expected) = input
      val result = adapter.execute(context, spec, arguments, "operation:$index")
      assertTrue(result.message, result.verifiedLocally)
      val request = remote.requests.last { it.command != "GET_STATE" }
      assertEquals(expected, request.command to request.arguments)
      assertEquals(ID, request.deviceId)
      assertTrue(request.executionId.matches(Regex("[A-Za-z0-9_-]{12,64}")))
      val read = remote.requests.last()
      assertEquals("GET_STATE", read.command)
      assertEquals("{}", read.arguments)
      assertFalse(request.executionId == read.executionId)
    }
    assertEquals(remote.requests.size, remote.requests.map { it.executionId }.toSet().size)
    assertEquals(context.binds, context.unbinds)
  }

  @Test fun instoRoutesNamesRoomsAndSavedAliasesToTheRightDevice() = offMain {
    val living = ID + 1
    val pc = ID + 2
    remote.pages = mapOf(0L to (listOf(ID, living, pc) to null))
    remote.device = { id -> when (id) {
      ID -> defaultDevice(id).put("name", "TV Android").put("room", "Cuarto")
      living -> device(id, "SAMSUNG_TIZEN", listOf("POWER_ON", "POWER_OFF"), emptyList()).put("room", "Living")
      else -> device(id, "MACOS", listOf("GET_STATE", "SET_VOLUME", "SET_MUTE"), listOf("volume", "muted"))
        .put("name", "MacBook").put("room", "Escritorio")
    } }
    OmniremoteAliases(context).save("omniremote:$pc", "PC, Computadóra")
    val expected = listOf(
      "Encender cuarto" to Triple(ID, "POWER_ON", "{}"),
      "Apagá el cuarto" to Triple(ID, "POWER_OFF", "{}"),
      "Encendé el televisor del living" to Triple(living, "POWER_ON", "{}"),
      "Apagar living" to Triple(living, "POWER_OFF", "{}"),
      "subi volumen pc al 100" to Triple(pc, "SET_VOLUME", """{"level":100}"""),
      "Mutear computadora" to Triple(pc, "SET_MUTE", """{"muted":true}"""),
      "Silenciar PC" to Triple(pc, "SET_MUTE", """{"muted":true}"""),
      "Desmuteá la PC" to Triple(pc, "SET_MUTE", """{"muted":false}"""),
    )
    for ((index, entry) in expected.withIndex()) {
      val (text, request) = entry
      assertFalse(text, PhoneCommandParser().parse(text) is PhoneRoute.Local)
      val order = requireNotNull(OmniremoteCommands.parse(text)) { text }
      val route = OmniremoteCommands.resolve(context, order) as PhoneRoute.Local
      assertEquals(SemanticCommandGuard.Verdict.Accepted, SemanticCommandGuard.check(text, listOf(route.command), java.util.Calendar.getInstance()))
      val result = PhoneActionExecutor(context).execute(route.command, executionId = "insto-test:$index", foreground = true)
      assertEquals(text, living != request.first, result.verifiedLocally)
      val dispatched = remote.requests.last { it.command != "GET_STATE" }
      assertEquals(text, request, Triple(dispatched.deviceId, dispatched.command, dispatched.arguments))
    }
  }

  @Test fun ambiguousRoomsRequireAUniqueAliasAndNeverDispatch() = offMain {
    remote.pages = mapOf(0L to (listOf(ID, ID + 1) to null))
    remote.device = { id -> defaultDevice(id).put("name", "TV $id").put("room", "Living") }
    val order = requireNotNull(OmniremoteCommands.parse("apagá living"))
    assertTrue(OmniremoteCommands.resolve(context, order) is PhoneRoute.Unsupported)
    assertTrue(remote.requests.isEmpty())

    OmniremoteAliases(context).save(TV, "principal, pantalla")
    val named = requireNotNull(OmniremoteCommands.parse("apagá principal"))
    val route = OmniremoteCommands.resolve(context, named) as PhoneRoute.Local
    assertEquals(TV, InstinctCapabilities.platform(route.command as PhoneCommand.Platform).second.getString("device"))
  }

  @Test fun wakesARegisteredDeviceWithoutFirstOpeningItsControlChannel() = offMain {
    remote.stateFailure = "DEVICE_AUTHORIZATION_REQUIRED"
    val result = adapter.execute(context, power, params("on", true), "wake-sleeping")
    assertEquals(PhoneActionOutcome.UNCERTAIN, result.outcome)
    assertTrue(result.message.contains("enviada"))
    assertEquals(listOf("POWER_ON", "GET_STATE"), remote.requests.map { it.command })
  }

  @Test fun trustsAConfirmedPowerOffWhenTheTvCanNoLongerBeReached() = offMain {
    remote.stateFailure = "DEVICE_UNREACHABLE"
    remote.replyToWrite = { id -> reply(data = JSONObject().put("power", false), id = id) }
    val result = adapter.execute(context, power, params("on", false), "verified-off")
    assertTrue(result.message, result.verifiedLocally)
    assertEquals(listOf("POWER_OFF"), remote.requests.map { it.command })
  }

  @Test fun rejectsUnauthorizedTvAndDistinguishesUnreachableTv() = offMain {
    remote.failure = "DEVICE_AUTHORIZATION_REQUIRED"
    val denied = adapter.execute(context, power, params("on", false), "denied")
    assertEquals(PhoneActionOutcome.REJECTED, denied.outcome)
    assertTrue(denied.message.contains("autorizá", ignoreCase = true))
    assertEquals(listOf("POWER_OFF"), remote.requests.map { it.command })

    val deniedVolume = adapter.execute(context, volume, params("percent", 75), "denied-volume")
    assertEquals(PhoneActionOutcome.REJECTED, deniedVolume.outcome)
    assertTrue(deniedVolume.message.contains("autorizá", ignoreCase = true))

    remote.failure = "DEVICE_UNREACHABLE"
    val unreachable = adapter.execute(context, volume, params("percent", 75), "unreachable")
    assertEquals(PhoneActionOutcome.REJECTED, unreachable.outcome)
    assertTrue(unreachable.message.contains("temporalmente"))
    assertEquals(HealthCode.DEVICE_UNREACHABLE.name, unreachable.healthCode)
    assertFalse(unreachable.verifiedLocally)
    assertEquals("SET_VOLUME", remote.requests.last().command)
  }

  @Test fun missingOrDifferentStateIsUncertain() = offMain {
    remote.device = { device(it, "SAMSUNG_TIZEN", listOf("POWER_ON", "POWER_OFF"), emptyList()) }
    assertEquals(PhoneActionOutcome.UNCERTAIN, adapter.execute(context, power, params("on", false), "no-field").outcome)
    assertEquals(listOf("POWER_OFF"), remote.requests.map { it.command })

    remote.device = { defaultDevice(it) }
    remote.applyWrites = false
    val result = adapter.execute(context, volume, params("percent", 80), "mismatch")
    assertEquals(PhoneActionOutcome.UNCERTAIN, result.outcome)
    assertFalse(result.verifiedLocally)
  }

  @Test fun verifiesThePhysicalVolumeStepOnExecutionAndRecovery() = offMain {
    remote.applyWrites = false
    remote.reportVolume(percent = 33, level = 5, max = 15)
    val request = params("percent", 32)

    assertTrue(adapter.execute(context, volume, request, "rounded-volume").verifiedLocally)
    assertTrue(adapter.observe(context, volume, request, "rounded-volume")!!.verifiedLocally)

    remote.reportVolume(percent = 40, level = 6, max = 15)
    assertNull(adapter.observe(context, volume, request, "rounded-volume"))
    assertEquals(PhoneActionOutcome.UNCERTAIN,
      adapter.execute(context, volume, request, "different-volume").outcome)
  }

  @Test fun reconnectsAndRecoversTheSameIdWithoutExecutingAgain() = offMain {
    remote.disconnectOnWrite = { context.disconnect() }
    val result = adapter.execute(context, mute, params("muted", true), "stable-journal-id")
    assertTrue(result.message, result.verifiedLocally)
    val request = remote.requests.single { it.command == "SET_MUTE" }
    assertEquals(listOf(request.executionId), remote.lookups)
    assertTrue(remote.versionCalls >= 2)
    assertNull(adapter.observe(context, mute, params("muted", true), "another-journal-id"))
    assertTrue(adapter.observe(context, mute, params("muted", true), "stable-journal-id")!!.verifiedLocally)
    assertEquals(request.executionId, remote.lookups.last())
    assertEquals(1, remote.requests.count { it.command == "SET_MUTE" })
    assertEquals(context.binds, context.unbinds)
  }

  @Test fun pollsPendingAndRecoversAfterBinderCallTimeout() = offMain {
    remote.replyToWrite = { id ->
      if (remote.requests.last().command == "SET_MUTE") {
        reply("PENDING", "ACCEPTED", id = id)
      } else {
        Thread.sleep(10_000L)
        error("The Binder call should have timed out")
      }
    }
    val timedAdapter = OmniremoteAdapter(listOf(OmniremoteLocalProvider(OmniremoteBinding(callTimeoutMillis = 500))))
    assertTrue(timedAdapter.execute(context, mute, params("muted", true), "pending-mute").verifiedLocally)
    assertTrue(timedAdapter.execute(context, volume, params("percent", 74), "timed-volume").verifiedLocally)
    val writes = remote.requests.filter { it.command != "GET_STATE" }
    assertEquals(listOf("SET_MUTE", "SET_VOLUME"), writes.map { it.command })
    assertEquals(writes.map { it.executionId }, remote.lookups)
    assertEquals(context.binds, context.unbinds)
  }

  @Test fun losingTheLedgerAfterAdmissionLeavesTheResultUncertain() = offMain {
    remote.applyWrites = false
    remote.replyToWrite = { id ->
      remote.forget(id)
      reply("PENDING", "ACCEPTED", id = id)
    }
    val result = adapter.execute(context, mute, params("muted", true), "pending-mute")
    assertEquals(PhoneActionOutcome.UNCERTAIN, result.outcome)
    val write = remote.requests.single { it.command == "SET_MUTE" }
    assertEquals(listOf(write.executionId), remote.lookups)
  }

  @Test fun losingAuthorizationAfterAdmissionLeavesTheResultUncertain() = offMain {
    remote.replyToWrite = { id ->
      remote.authorized = false
      reply("PENDING", "ACCEPTED", id = id)
    }
    val result = adapter.execute(context, mute, params("muted", true), "pending-mute")
    assertEquals(PhoneActionOutcome.UNCERTAIN, result.outcome)
    assertTrue(result.message.contains("autoriza a Insto"))
    assertEquals(listOf("SET_MUTE"), remote.requests.map { it.command })
  }

  private class BindingContext(private val remote: Remote) :
    ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
    private var connection: ServiceConnection? = null
    var intent: Intent? = null
    var flags = 0
    var binds = 0
    var unbinds = 0
    var connect = true
    var bindAllowed = true

    override fun getApplicationContext(): Context = this

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
      intent = service
      this.flags = flags
      binds++
      connection = conn
      if (bindAllowed && connect) conn.onServiceConnected(checkNotNull(service.component), remote)
      return bindAllowed
    }

    override fun unbindService(conn: ServiceConnection) {
      assertEquals(connection, conn)
      unbinds++
      connection = null
    }

    fun disconnect() = connection!!.onServiceDisconnected(checkNotNull(intent?.component))
  }

  private data class Request(val executionId: String, val deviceId: Long, val command: String, val arguments: String)

  private class Remote : IRemoteCommandService.Stub() {
    var version = 1
    var versionCalls = 0
    var authorized = true
    var pages = mapOf(0L to (listOf(ID) to null as Long?))
    var device: (Long) -> JSONObject = ::defaultDevice
    var failure: String? = null
    var stateFailure: String? = null
    var disconnectOnWrite: (() -> Unit)? = null
    var replyToWrite: ((String) -> String)? = null
    var applyWrites = true
    val cursors = mutableListOf<Long>()
    val details = mutableListOf<Long>()
    val requests = mutableListOf<Request>()
    val lookups = mutableListOf<String>()
    private val ledger = mutableMapOf<String, String>()
    private val state = JSONObject().put("power", true).put("volume", 32).put("muted", false)

    fun reportVolume(percent: Int, level: Int, max: Int) {
      state.put("volume", percent).put("volumeLevel", level).put("volumeMax", max)
    }

    override fun getApiVersion(): Int {
      assertFalse(Looper.myLooper() == Looper.getMainLooper())
      versionCalls++
      if (!authorized) throw SecurityException()
      return version
    }

    override fun listDevices(apiVersion: Int, afterDeviceId: Long): String {
      assertEquals(1, apiVersion)
      cursors += afterDeviceId
      val (ids, next) = pages.getValue(afterDeviceId)
      return reply(data = JSONObject().put("devices", JSONArray(ids.map { JSONObject().put("deviceId", it) }))
        .put("nextAfterId", next ?: JSONObject.NULL))
    }

    override fun getDevice(apiVersion: Int, deviceId: Long): String {
      assertEquals(1, apiVersion)
      details += deviceId
      return reply(data = device(deviceId))
    }

    override fun listApps(apiVersion: Int, deviceId: Long, afterAppId: Long): String = error("unused")

    override fun execute(apiVersion: Int, executionId: String, deviceId: Long, command: String, argumentsJson: String): String {
      assertEquals(1, apiVersion)
      requests += Request(executionId, deviceId, command, argumentsJson)
      val response = if (command == "GET_STATE" && stateFailure != null) {
        reply("UNCERTAIN", stateFailure!!, id = executionId)
      } else if (failure != null) {
        reply(if (command == "GET_STATE") "UNCERTAIN" else "REJECTED", failure!!, id = executionId)
      } else if (command == "GET_STATE") {
        reply(data = state, id = executionId)
      } else {
        if (applyWrites) when (command) {
          "POWER_ON" -> state.put("power", true)
          "POWER_OFF" -> state.put("power", false)
          "SET_VOLUME" -> state.put("volume", JSONObject(argumentsJson).getInt("level"))
          "SET_MUTE" -> state.put("muted", JSONObject(argumentsJson).getBoolean("muted"))
          else -> fail("unexpected command $command")
        }
        reply("SENT", "DISPATCHED", id = executionId)
      }
      ledger[executionId] = response
      if (command != "GET_STATE") {
        disconnectOnWrite?.invoke()
        return replyToWrite?.invoke(executionId) ?: response
      }
      return response
    }

    fun forget(executionId: String) { ledger.remove(executionId) }

    override fun getExecution(apiVersion: Int, executionId: String): String {
      assertEquals(1, apiVersion)
      lookups += executionId
      if (!authorized) throw SecurityException()
      return ledger[executionId] ?: reply("REJECTED", "UNKNOWN_EXECUTION", id = executionId)
    }
  }

  private fun params(key: String, value: Any) = JSONObject().put("device", TV).put(key, value)

  private fun offMain(test: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    try {
      executor.submit(test).get(15, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }

  companion object {
    private const val ID = 9_007_199_254_740_993L
    private const val TV = "omniremote:9007199254740993"
    private val power = InstinctCapabilities.specs.getValue("omniremote_power")
    private val volume = InstinctCapabilities.specs.getValue("omniremote_volume")
    private val mute = InstinctCapabilities.specs.getValue("omniremote_mute")

    private fun defaultDevice(id: Long) = device(id, "ANDROID_TV",
      listOf("GET_STATE", "POWER_ON", "POWER_OFF", "SET_VOLUME", "SET_MUTE"), listOf("power", "volume", "muted"))

    private fun device(id: Long, type: String, commands: List<String>, fields: List<String>) = JSONObject()
      .put("deviceId", id).put("type", type).put("commands", JSONArray(commands)).put("stateFields", JSONArray(fields))

    private fun reply(status: String = "VERIFIED", code: String = "OK", data: JSONObject? = null, id: String? = null) =
      JSONObject().put("apiVersion", 1).put("status", status).put("code", code).put("data", data).put("executionId", id).toString()
  }
}
