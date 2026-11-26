package com.example.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import com.example.external.IRemoteCommandService
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal const val OMNIREMOTE_PACKAGE = "com.aistudio.omnitvremote.qvrtm"

internal class OmniremoteFailure(val reason: String, val reconnect: Boolean = false) : Exception(reason)

internal data class OmniremoteReply(val status: String, val code: String, val data: JSONObject?) {
  companion object {
    fun read(raw: String, id: String? = null): OmniremoteReply {
      val json = JSONObject(raw)
      require(json.getInt("apiVersion") == 1)
      require(id == null || !json.has("executionId") || json.getString("executionId") == id)
      return OmniremoteReply(json.getString("status"), json.getString("code"), json.optJSONObject("data"))
    }
  }
}

/** One binding per interaction, held open across catalog pages or execution polling. */
internal class OmniremoteClient(
  private val open: () -> Link,
  private val now: () -> Long = SystemClock::elapsedRealtime,
  private val pause: (Long) -> Unit = Thread::sleep,
) {
  interface Link : Closeable {
    fun <T> call(block: (IRemoteCommandService) -> T): T
  }

  data class App(val id: Long, val name: String, val category: String, val favorite: Boolean)
  data class Device(val id: Long, val name: String, val room: String, val type: String,
    val commands: Set<String>, val keys: Set<String>, val stateFields: Set<String>,
    val exactAlarms: Boolean, val apps: List<App>) {
    val target get() = OmniremoteCommands.target(id)
    val television get() = type in setOf("ANDROID_TV", "SAMSUNG_TIZEN")
    val label get() = if (room.isBlank()) name else "$name · $room"

    fun supports(wire: String, args: JSONObject): Boolean = wire in commands && when (wire) {
      "KEY", "MAC_MEDIA_KEY" -> args.getString("key") in keys
      "OPEN_APP" -> apps.any { it.id == args.getLong("appId") }
      "SLEEP_FOR", "SLEEP_AT" -> exactAlarms
      else -> true
    }

    fun advertises(operation: OmniremoteCommands.Operation): Boolean = when (operation.id) {
      "power" -> "POWER_ON" in commands || "POWER_OFF" in commands
      "key" -> operation.wire in commands && keys.isNotEmpty()
      "open_app" -> operation.wire in commands && apps.isNotEmpty()
      "sleep_for", "sleep_at" -> operation.wire in commands && exactAlarms
      else -> operation.wire in commands
    }
  }

  fun devices(): List<Device> {
    val deadline = now() + 15_000
    return open().use { link ->
      fun query(call: (IRemoteCommandService) -> String): JSONObject {
        if (now() >= deadline) throw OmniremoteFailure("OmniRemote tardó demasiado en publicar sus dispositivos.")
        val response = OmniremoteReply.read(link.call(call))
        if (response.status != "VERIFIED" || response.data == null)
          throw OmniremoteFailure("OmniRemote no pudo publicar el catálogo (${response.code}).")
        return response.data
      }
      fun pages(rows: String, id: String, call: (IRemoteCommandService, Long) -> String): List<JSONObject> = buildList {
        var cursor = 0L
        do {
          val data = query { call(it, cursor) }
          val items = data.getJSONArray(rows)
          var last = cursor
          for (index in 0 until items.length()) {
            val row = items.getJSONObject(index)
            val current = row.exactId(id)
            require(current > last)
            add(row)
            last = current
          }
          if (data.isNull("nextAfterId")) break
          cursor = data.exactId("nextAfterId").also { require(it == last && items.length() > 0) }
        } while (true)
      }
      pages("devices", "deviceId") { api, cursor -> api.listDevices(1, cursor) }.map { summary ->
        val id = summary.exactId("deviceId")
        val detail = query { it.getDevice(1, id) }
        require(detail.exactId("deviceId") == id)
        val commands = detail.strings("commands")
        val apps = if ("OPEN_APP" in commands)
          pages("apps", "appId") { api, cursor -> api.listApps(1, id, cursor) }.map {
            App(it.exactId("appId"), it.getString("name"), it.optString("category"), it.optBoolean("favorite"))
          } else emptyList()
        Device(id, detail.getString("name"), detail.optString("room"), detail.getString("type"),
          commands, detail.strings("keys"), detail.strings("stateFields"), detail.getBoolean("canScheduleExactAlarms"), apps)
      }
    }
  }

  private fun JSONObject.strings(name: String): Set<String> = getJSONArray(name).let { values ->
    (0 until values.length()).map(values::getString).toSet()
  }

  private fun JSONObject.exactId(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long)
    return (value as Number).toLong()
  }

  /** Dispatch once. All subsequent calls, including those after reconnect, only query the original ID. */
  fun execute(id: String, deviceId: Long, command: String, args: JSONObject): PhoneActionResult {
    val deadline = now() + 42_000
    val link = try { open() }
    catch (failure: OmniremoteFailure) { return rejected(failure.reason) }
    catch (_: SecurityException) { return rejected(UNAUTHORIZED) }
    catch (_: Exception) { return rejected("No se pudo abrir la conexión local con OmniRemote.") }
    return try {
      val result = link.use {
        val raw = try { it.call { api -> api.execute(1, id, deviceId, command, args.toString()) } }
        catch (_: SecurityException) { return rejected(UNAUTHORIZED) }
        poll(it, id, OmniremoteReply.read(raw, id), deadline)
      }
      result.result(command, args = args)
    } catch (_: SecurityException) {
      uncertain(UNAUTHORIZED)
    } catch (_: Exception) {
      recover(id, command, deadline)
    }
  }

  fun observe(id: String, command: String): PhoneActionResult =
    recover(id, command, now() + 7_000)

  private fun recover(id: String, command: String, deadline: Long): PhoneActionResult {
    do {
      try {
        return open().use { link ->
          val reply = OmniremoteReply.read(link.call { it.getExecution(1, id) }, id)
          poll(link, id, reply, deadline).result(command, lookup = true)
        }
      } catch (failure: OmniremoteFailure) {
        if (!failure.reconnect) return uncertain(failure.reason)
      } catch (_: Exception) {
        return uncertain()
      }
      if (now() < deadline) pause(400)
    } while (now() < deadline)
    return uncertain()
  }

  private fun poll(link: Link, id: String, initial: OmniremoteReply, deadline: Long): OmniremoteReply {
    var reply = initial
    while (reply.status == "PENDING" && now() < deadline) {
      pause(400)
      reply = OmniremoteReply.read(link.call { it.getExecution(1, id) }, id)
    }
    return reply
  }

  companion object {
    const val NOT_INSTALLED = "OmniRemote no está instalado en este perfil de Android."
    const val UNAUTHORIZED = "OmniRemote no autorizó esta instalación de Insto. Revisá la firma configurada en OmniRemote."
    const val INCOMPATIBLE = "La versión instalada de OmniRemote no ofrece la API local compatible."

    fun android(context: Context) = OmniremoteClient({ BinderLink.open(context.applicationContext) })

    fun installed(context: Context): Boolean = try {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(OMNIREMOTE_PACKAGE, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
    private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
    private fun uncertain(reason: String? = null) = PhoneActionResult(
      listOfNotNull("OmniRemote no pudo confirmar el resultado. Revisá el dispositivo antes de repetir la orden.", reason).joinToString(" "),
      outcome = PhoneActionOutcome.UNCERTAIN)

    internal fun OmniremoteReply.result(command: String, lookup: Boolean = false, args: JSONObject? = null): PhoneActionResult {
      val reason = when (code) {
        "STORE_OPEN_REQUESTED" -> "OmniRemote solicitó abrir la ficha en Play Store. Puede requerir confirmación en la TV."
        "DEVICE_AUTHORIZATION_REQUIRED" -> "Autorizá el control de ese dispositivo en OmniRemote."
        "DEVICE_UNREACHABLE" -> "OmniRemote no pudo conectarse con el dispositivo."
        "EXACT_ALARM_PERMISSION_REQUIRED" -> "Habilitá las alarmas exactas para OmniRemote en Ajustes de Android."
        "TIMER_OTHER_DEVICE" -> "OmniRemote ya tiene un temporizador para otro dispositivo."
        "NO_ACTIVE_TIMER" -> "OmniRemote no encontró un temporizador activo para ese dispositivo."
        "DEVICE_BUSY", "SERVICE_BUSY" -> "OmniRemote está ocupado. El pedido no fue admitido."
        "CALLER_NO_LONGER_AUTHORIZED" -> UNAUTHORIZED
        "UNKNOWN_DEVICE", "UNKNOWN_APP", "UNKNOWN_SOURCE" -> "OmniRemote ya no encuentra ese destino. Actualizá el catálogo."
        else -> null
      }
      if (lookup && code in setOf("UNKNOWN_EXECUTION", "STORAGE_UNAVAILABLE")) return uncertain()
      return when (status) {
        "VERIFIED" -> PhoneActionResult(verifiedMessage(command, data, reason), verifiedLocally = true,
          executionData = data?.toString())
        "SENT" -> PhoneActionResult(reason ?: if (command == "KEY" && args?.optString("key") == "MUTE")
          "OmniRemote envió la tecla de alternar silencio. El estado del audio sigue sin confirmar."
        else "OmniRemote envió la orden. El efecto en el dispositivo sigue sin confirmar.",
          outcome = PhoneActionOutcome.HANDOFF, executionData = data?.toString())
        "REJECTED", "UNSUPPORTED" -> rejected(reason ?: "OmniRemote rechazó la operación ($code).")
        "UNCERTAIN" -> {
          val reported = reason ?: "OmniRemote informó $code."
          val observed = data?.let { observedFacts(command, it) }.orEmpty()
          val detail = if (observed.isEmpty()) reported else "$reported Observación: ${observed.joinToString(", ")}."
          uncertain(detail).copy(executionData = data?.toString())
        }
        else -> uncertain(reason)
      }
    }

    private fun verifiedMessage(command: String, data: JSONObject?, reason: String?): String {
      val facts = data?.let { observedFacts(command, it) }.orEmpty() + listOfNotNull(reason)
      return when {
        facts.isNotEmpty() -> "OmniRemote verificó: ${facts.joinToString("; ")}."
        command == "GET_STATE" || command == "MAC_MEDIA_STATE" -> "OmniRemote no informó campos de estado observables."
        else -> "OmniRemote verificó el resultado."
      }
    }

    private fun observedFacts(command: String, data: JSONObject): List<String> = buildList {
      data.opt("power")?.let { if (it is Boolean) add(if (it) "encendido" else "apagado") }
      data.opt("volume")?.let { if (it is Number) add("volumen $it%") }
      data.opt("muted")?.let { if (it is Boolean) add(if (it) "silenciado" else "con sonido") }
      data.opt("playback")?.let {
        if (it == "PLAYING" || it == "PAUSED") add(if (it == "PLAYING") "reproduciendo" else "en pausa")
      }
      data.optJSONObject("sleepTimer")?.opt("remainingSeconds")?.let { if (it is Number) add("apagado programado, quedan $it segundos") }
      if (command == "GET_STATE" && data.has("sleepTimer") && data.isNull("sleepTimer")) add("sin apagado programado")
      if (data.optBoolean("sleepPowerOffPending") == true) add("apagado en curso")
      if (data.opt("controlReachable") is Boolean) add(if (data.getBoolean("controlReachable")) "control accesible" else "control inaccesible")
      data.optJSONArray("sources")?.let { sources ->
        if (sources.length() == 0) add("sin fuentes de medios")
        for (index in 0 until minOf(sources.length(), 12)) {
          val source = sources.getJSONObject(index)
          add("${source.optString("service")} (${source.optString("sourceId")})")
        }
      }
    }
  }
}

private class BinderLink(private val context: Context) : OmniremoteClient.Link, ServiceConnection {
  private val connected = CompletableFuture<IBinder>()
  @Volatile private var binder: IBinder? = null

  override fun onServiceConnected(name: ComponentName, service: IBinder) {
    binder = service
    connected.complete(service)
  }

  override fun onServiceDisconnected(name: ComponentName) = disconnected()
  override fun onBindingDied(name: ComponentName) = disconnected()
  override fun onNullBinding(name: ComponentName) {
    connected.completeExceptionally(OmniremoteFailure(OmniremoteClient.INCOMPATIBLE))
  }

  private fun disconnected() {
    binder = null
    connected.completeExceptionally(OmniremoteFailure(DISCONNECTED, reconnect = true))
  }

  override fun <T> call(block: (IRemoteCommandService) -> T): T {
    val service = binder?.takeIf { it.isBinderAlive }
      ?: throw OmniremoteFailure(DISCONNECTED, reconnect = true)
    return bounded(4_000) { block(IRemoteCommandService.Stub.asInterface(service)) }
  }

  override fun close() { runCatching { context.unbindService(this) } }

  companion object {
    private const val DISCONNECTED = "Se interrumpió la conexión local con OmniRemote."
    private const val TIMEOUT = "Se agotó el tiempo de espera de OmniRemote."
    private val callbacks = Executors.newSingleThreadExecutor()
    private val calls = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue())

    fun open(context: Context): BinderLink {
      if (!OmniremoteClient.installed(context)) throw OmniremoteFailure(OmniremoteClient.NOT_INSTALLED)
      val link = BinderLink(context)
      val intent = Intent("$OMNIREMOTE_PACKAGE.BIND_EXTERNAL_CONTROL")
        .setComponent(ComponentName(OMNIREMOTE_PACKAGE, "com.example.external.RemoteCommandService"))
      val bound = try {
        if (android.os.Build.VERSION.SDK_INT >= 29)
          context.bindService(intent, Context.BIND_AUTO_CREATE, callbacks, link)
        else context.bindService(intent, link, Context.BIND_AUTO_CREATE)
      } catch (_: SecurityException) { throw OmniremoteFailure(OmniremoteClient.UNAUTHORIZED) }
      if (!bound) throw OmniremoteFailure(OmniremoteClient.INCOMPATIBLE)
      try {
        link.connected.get(3_500, TimeUnit.MILLISECONDS)
        if (link.call { it.apiVersion } < 1) throw OmniremoteFailure(OmniremoteClient.INCOMPATIBLE)
        return link
      } catch (error: Exception) {
        link.close()
        throw translate(error, duringHandshake = true)
      }
    }

    private fun <T> bounded(timeout: Long, call: () -> T): T {
      val future = try { calls.submit<T>(call) }
        catch (_: java.util.concurrent.RejectedExecutionException) { throw OmniremoteFailure(TIMEOUT, reconnect = true) }
      return try { future.get(timeout, TimeUnit.MILLISECONDS) }
      catch (error: Exception) { future.cancel(true); throw translate(error) }
    }

    private fun translate(error: Exception, duringHandshake: Boolean = false): Exception {
      val cause = if (error is ExecutionException) error.cause else error
      return when (cause) {
        is OmniremoteFailure -> cause
        is SecurityException -> if (duringHandshake) OmniremoteFailure(OmniremoteClient.UNAUTHORIZED) else cause
        is java.util.concurrent.TimeoutException -> OmniremoteFailure(TIMEOUT, reconnect = true)
        is android.os.DeadObjectException -> OmniremoteFailure(DISCONNECTED, reconnect = true)
        is RemoteException -> OmniremoteFailure(if (duringHandshake) OmniremoteClient.INCOMPATIBLE else DISCONNECTED,
          reconnect = !duringHandshake)
        else -> OmniremoteFailure(if (duringHandshake) OmniremoteClient.INCOMPATIBLE else DISCONNECTED)
      }
    }
  }
}
