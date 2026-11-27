package com.example.phone

import android.content.Context
import android.os.SystemClock
import com.example.phone.OmniremoteAdapter.Control
import com.example.phone.OmniremoteAdapter.Setting
import com.example.phone.OmniremoteAdapter.Television
import com.example.phone.OmniremoteAdapter.VolumeScale
import com.example.phone.OmniremoteBinding.Companion.API_VERSION
import com.example.phone.OmniremoteBinding.Session
import com.example.phone.OmniremoteBinding.Unavailable
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class OmniremoteLocalProvider(
  private val binding: OmniremoteBinding = OmniremoteBinding(),
) : OmniremoteAdapter.Provider {
  override val transport = OmniremoteAdapter.Transport.LOCAL_API

  override fun televisions(context: Context, id: String?): List<Television> = try {
    binding.open(context).use { session ->
      val deadline = deadline(CATALOG_TIMEOUT)
      val ids = if (id == null) session.listDeviceIds(deadline) else listOfNotNull(deviceId(id))
      ids.mapNotNull { deviceId ->
        session.television(deviceId, deadline)?.let { Television("$ID_PREFIX$deviceId", it.controls) }
      }
    }
  } catch (error: SecurityException) {
    throw OmniremoteAdapter.DiscoveryFailure(CALLER_DENIED)
  } catch (error: Unavailable) {
    throw OmniremoteAdapter.DiscoveryFailure(error.reason)
  } catch (error: JSONException) {
    throw OmniremoteAdapter.DiscoveryFailure(INVALID_REPLY)
  }

  override fun set(context: Context, id: String, setting: Setting, executionId: String): PhoneActionResult {
    val deviceId = deviceId(id) ?: return rejected(UNKNOWN_DEVICE)
    val control: Control
    val command: String
    val arguments = JSONObject()
    when (setting) {
      is Setting.Power -> {
        control = Control.POWER
        command = if (setting.on) "POWER_ON" else "POWER_OFF"
      }
      is Setting.Volume -> {
        if (setting.percent !in 0..100) return rejected("El volumen debe estar entre 0 y 100.")
        control = Control.VOLUME
        command = "SET_VOLUME"
        arguments.put("level", setting.percent)
      }
      is Setting.Mute -> {
        control = Control.MUTE
        command = "SET_MUTE"
        arguments.put("muted", setting.muted)
      }
    }

    return try {
      binding.open(context).use { session ->
        val television = session.television(deviceId, deadline(CATALOG_TIMEOUT)) ?: return rejected(UNKNOWN_DEVICE)
        if (control !in television.controls) return rejected(UNSUPPORTED_CONTROL)
        // Wake-on-LAN can be sent without opening the TV's control channel. Check for a known
        // authorization failure first, while allowing wake when a sleeping TV is unreachable.
        if (command == "POWER_ON") {
          val state = session.send(deviceId, newStateId(), "GET_STATE", "{}")
          if (state == null) return rejected("Omniremote no pudo comprobar el acceso al televisor.")
          if (state.status != "VERIFIED" && state.code != "DEVICE_UNREACHABLE") return rejected(state.message())
        }
        val reply = session.send(deviceId, remoteExecutionId(executionId), command, arguments.toString())
        when {
          reply == null -> uncertain()
          reply.status == "REJECTED" || reply.status == "UNSUPPORTED" -> rejected(reply.message())
          reply.status == "VERIFIED" || reply.status == "SENT" -> PhoneActionResult("Omniremote procesó el control.")
          else -> uncertain(reply.message())
        }
      }
    } catch (error: SecurityException) {
      rejected(CALLER_DENIED)
    } catch (error: Unavailable) {
      rejected(error.reason)
    } catch (error: JSONException) {
      rejected(INVALID_REPLY)
    }
  }

  override fun read(context: Context, id: String, control: Control): Setting? = readState(context, id, control, null)

  override fun recover(context: Context, id: String, control: Control, executionId: String): Setting? =
    readState(context, id, control, remoteExecutionId(executionId))

  private fun readState(context: Context, id: String, control: Control, recoveryId: String?): Setting? {
    val deviceId = deviceId(id) ?: return null
    return binding.open(context).use { session ->
      val television = session.television(deviceId, deadline(CATALOG_TIMEOUT)) ?: return null
      if (control !in television.controls) return null
      if (recoveryId != null) {
        val result = session.result(recoveryId, deadline(EXECUTION_TIMEOUT)) ?: return null
        if (result.status !in setOf("VERIFIED", "SENT", "UNCERTAIN") || result.code == "STORAGE_UNAVAILABLE") return null
      }
      if (control.field !in television.stateFields) return null

      val reply = session.send(deviceId, newStateId(), "GET_STATE", "{}") ?: return null
      val observed = reply.data?.opt(control.field).takeIf { reply.status == "VERIFIED" }
      when (control) {
        Control.POWER -> (observed as? Boolean)?.let { Setting.Power(it) }
        Control.VOLUME -> observed.integer()?.takeIf { it in 0L..100L }?.let { percent ->
          val max = reply.data?.opt("volumeMax").integer()
          val level = reply.data?.opt("volumeLevel").integer()
          val scale = if (max != null && max in 1L..Int.MAX_VALUE.toLong() && level != null && level in 0L..max) {
            VolumeScale(level.toInt(), max.toInt())
          } else null
          Setting.Volume(percent.toInt(), scale)
        }
        Control.MUTE -> (observed as? Boolean)?.let { Setting.Mute(it) }
      }
    }
  }

  private fun Session.listDeviceIds(deadline: Long): List<Long> {
    val ids = mutableListOf<Long>()
    var afterId = 0L
    while (true) {
      val reply = call(deadline) { parseReply(it.listDevices(API_VERSION, afterId)) }
      val data = reply.catalogData()
      val rows = data.getJSONArray("devices")
      for (index in 0 until rows.length()) {
        ids += rows.getJSONObject(index).get("deviceId").integer()?.takeIf { it > afterId }
          ?: throw JSONException("Invalid device ID")
      }
      val next = data.get("nextAfterId")
      if (next == JSONObject.NULL) return ids
      afterId = next.integer()?.takeIf { it > afterId } ?: throw JSONException("Invalid device cursor")
    }
  }

  private fun Session.television(deviceId: Long, deadline: Long): Device? {
    val reply = call(deadline) { parseReply(it.getDevice(API_VERSION, deviceId)) }
    if (reply.code == "UNKNOWN_DEVICE") return null
    val data = reply.catalogData()
    if (data.get("deviceId").integer() != deviceId) throw JSONException("Mismatched device ID")
    if (data.getString("type") !in setOf("ANDROID_TV", "SAMSUNG_TIZEN")) return null

    val commands = data.getJSONArray("commands").strings()
    val controls = buildSet {
      if ("POWER_ON" in commands && "POWER_OFF" in commands) add(Control.POWER)
      if ("SET_VOLUME" in commands) add(Control.VOLUME)
      if ("SET_MUTE" in commands) add(Control.MUTE)
    }
    val fields = if ("GET_STATE" in commands) data.getJSONArray("stateFields").strings() else emptySet()
    return Device(controls, fields)
  }

  private fun Session.send(deviceId: Long, id: String, command: String, arguments: String): Reply? {
    val deadline = deadline(EXECUTION_TIMEOUT)
    val initial = try {
      call(deadline) { parseReply(it.execute(API_VERSION, id, deviceId, command, arguments), id) }
    } catch (_: Unavailable) {
      null
    } catch (_: JSONException) {
      null
    }
    if (initial != null && initial.status != "PENDING") return initial
    // Only query the ledger after an ambiguous send. Even UNKNOWN_EXECUTION can race acceptance.
    return try {
      result(id, deadline, waitForAdmission = initial == null)?.takeUnless { it.code == "UNKNOWN_EXECUTION" }
    } catch (_: SecurityException) {
      // The original call may have been admitted before permission to read its receipt was lost.
      Reply("UNCERTAIN", "CALLER_NO_LONGER_AUTHORIZED", null)
    }
  }

  private fun Session.result(id: String, deadline: Long, waitForAdmission: Boolean = false): Reply? {
    while (SystemClock.elapsedRealtime() < deadline) {
      val reply = try {
        call(deadline) { parseReply(it.getExecution(API_VERSION, id), id) }
      } catch (_: Unavailable) {
        null
      } catch (_: JSONException) {
        null
      }
      if (reply != null && reply.status != "PENDING" && (!waitForAdmission || reply.code != "UNKNOWN_EXECUTION"))
        return reply
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining > 0) Thread.sleep(minOf(remaining, POLL_INTERVAL))
    }
    return null
  }

  private data class Device(val controls: Set<Control>, val stateFields: Set<String>)

  private data class Reply(val status: String, val code: String, val data: JSONObject?) {
    fun catalogData(): JSONObject = if (status == "VERIFIED") {
      data ?: throw JSONException("Missing catalog data")
    } else throw Unavailable(message())

    fun message(): String = when (code) {
      "DEVICE_AUTHORIZATION_REQUIRED" -> "Autorizá el televisor en Omniremote."
      "DEVICE_UNREACHABLE" -> "El televisor está temporalmente inaccesible en Omniremote."
      "UNKNOWN_DEVICE", "TARGET_CHANGED", "TARGET_IDENTITY_CHANGED" -> UNKNOWN_DEVICE
      "NOT_SUPPORTED", "UNKNOWN_COMMAND" -> UNSUPPORTED_CONTROL
      "CALLER_NO_LONGER_AUTHORIZED" -> CALLER_DENIED
      "DEVICE_BUSY", "SERVICE_BUSY" -> "Omniremote está ocupado con otro control."
      else -> "Omniremote no pudo completar la operación."
    }
  }

  companion object {
    private const val ID_PREFIX = "omniremote:"
    private const val CATALOG_TIMEOUT = 15_000L
    // The service allows 35 seconds for admitted work. Leave time for the Binder call and polling.
    private const val EXECUTION_TIMEOUT = 42_000L
    private const val POLL_INTERVAL = 400L
    private const val CALLER_DENIED = "Omniremote no autoriza a Insto en este perfil."
    private const val UNKNOWN_DEVICE = "El televisor ya no está disponible en Omniremote."
    private const val UNSUPPORTED_CONTROL = "El televisor no admite este control en Omniremote."
    private const val INVALID_REPLY = "Omniremote devolvió una respuesta incompatible."

    private fun deadline(timeout: Long) = SystemClock.elapsedRealtime() + timeout
    private fun deviceId(id: String) = id.removePrefix(ID_PREFIX).toLongOrNull()
      ?.takeIf { it > 0 && id == "$ID_PREFIX$it" }
    private fun remoteExecutionId(id: String) = sha256("insto-omniremote\u0000$id")
    private fun newStateId() = UUID.randomUUID().toString()

    private val Control.field: String
      get() = when (this) {
        Control.POWER -> "power"
        Control.VOLUME -> "volume"
        Control.MUTE -> "muted"
      }

    private fun Any?.integer(): Long? = when (this) {
      is Int -> toLong()
      is Long -> this
      else -> null
    }

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()

    private fun parseReply(json: String, id: String? = null): Reply {
      val value = JSONObject(json)
      if (value.getInt("apiVersion") != API_VERSION ||
        id != null && value.has("executionId") && value.getString("executionId") != id) {
        throw JSONException("Incompatible reply")
      }
      return Reply(value.getString("status"), value.getString("code"), value.optJSONObject("data"))
    }

    private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)
    private fun uncertain(message: String = "No pude recuperar el resultado de Omniremote.") =
      PhoneActionResult(message, outcome = PhoneActionOutcome.UNCERTAIN)
  }
}
