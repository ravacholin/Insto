package com.example.phone

import android.content.Context
import org.json.JSONObject

/** Control of registered Omniremote devices through an authorized provider. */
internal class OmniremoteAdapter(private val providers: List<Provider> = emptyList()) : InstinctCapabilityAdapter {
  enum class Transport { LOCAL_API, APP_FUNCTION, ANDROID_INTENT }
  enum class Control(val capability: String, val label: String) {
    POWER("omniremote_power", "Encendido y apagado"),
    VOLUME("omniremote_volume", "Volumen"),
    MUTE("omniremote_mute", "Silencio"),
  }

  sealed interface Setting {
    data class Power(val on: Boolean) : Setting
    data class Volume(val percent: Int, val scale: VolumeScale? = null) : Setting
    data class Mute(val muted: Boolean) : Setting

    fun matches(observed: Setting?): Boolean {
      if (this is Volume && observed is Volume) {
        val scale = observed.scale ?: return observed.percent == percent
        // Android TV rounds percentages to the nearest physical step.
        return scale.level.toLong() == (percent.toLong() * scale.max + 50) / 100
      }
      return observed == this
    }
  }

  data class VolumeScale(val level: Int, val max: Int)
  data class Dispatch(val result: PhoneActionResult, val observed: Setting? = null)

  data class Device(
    val id: String,
    val controls: Set<Control>,
    val name: String = "",
    val room: String = "",
    val stateFields: Set<String> = emptySet(),
  ) {
    val label: String get() = listOf(name, room).filter(String::isNotBlank).distinct().joinToString(" · ").ifEmpty { id }
  }

  interface Provider {
    val transport: Transport
    /** Discover registered devices. The device's own control channel may require pairing. */
    fun devices(context: Context, id: String? = null): List<Device>
    /** A headless absolute operation with no activity or permission prompt. */
    fun set(context: Context, id: String, setting: Setting, executionId: String): Dispatch
    /** Compare the current state through the same authorized provider. Null means it could not be observed. */
    fun read(context: Context, id: String, control: Control): Setting?
    fun recover(context: Context, id: String, control: Control, executionId: String): Setting? = read(context, id, control)
  }

  class DiscoveryFailure(message: String, val health: HealthCode = HealthCode.PROVIDER_DOWN) : Exception(message)

  private class Candidates(
    val entries: List<Pair<Provider, Device>>,
    val reason: String,
    val health: HealthCode,
  )

  private fun candidates(context: Context, control: Control, id: String? = null): Candidates {
    var failure: DiscoveryFailure? = null
    val entries = providers.sortedBy { it.transport }.flatMap { provider ->
      try {
        provider.devices(context, id)
          .filter { device -> control in device.controls && DEVICE_ID.matches(device.id) &&
            (id == null || id == device.id) }
          .map { provider to it }
      } catch (error: DiscoveryFailure) {
        if (failure == null) failure = error
        emptyList()
      }
    }
    return Candidates(entries, failure?.message ?: "No hay un dispositivo disponible para este control en Omniremote.",
      failure?.health ?: HealthCode.DEVICE_MISSING)
  }

  override fun discover(context: Context, spec: InstinctCapabilities.Spec): InstinctDeviceCapabilities.Discovery {
    val found = candidates(context, control(spec.id))
    val targets = found.entries.map { it.second.id }.distinct().sorted()
    return InstinctDeviceCapabilities.Discovery(
      found.blockerIfEmpty(),
      targets,
    )
  }

  override fun targets(context: Context, spec: InstinctCapabilities.Spec): List<String> = discover(context, spec).targets

  override fun blocker(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject?): InstinctDeviceCapabilities.Block? =
    candidates(context, control(spec.id), params?.getString("device")).blockerIfEmpty()

  private fun Candidates.blockerIfEmpty(): InstinctDeviceCapabilities.Block? =
    InstinctDeviceCapabilities.Block(reason, pending = false).takeIf { entries.isEmpty() }

  override fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult {
    val control = control(spec.id)
    val id = params.getString("device")
    val found = candidates(context, control, id)
    val provider = found.entries.firstOrNull()?.first
      ?: return PhoneActionResult(found.reason, outcome = PhoneActionOutcome.REJECTED, healthCode = found.health.name)
    val setting = setting(control, params)
    val dispatch = runCatching { provider.set(context, id, setting, executionId) }.getOrNull()
    val result = dispatch?.result
    if (result?.outcome == PhoneActionOutcome.REJECTED || result?.outcome == PhoneActionOutcome.NEEDS_INPUT)
      return result.copy(verifiedLocally = false)
    return if (setting.matches(dispatch?.observed) ||
      setting.matches(runCatching { provider.read(context, id, control) }.getOrNull())) verified() else
      PhoneActionResult(result?.takeIf { it.outcome == PhoneActionOutcome.UNCERTAIN }?.message
        ?: "No pude comprobar el estado del dispositivo en Omniremote.", outcome = PhoneActionOutcome.UNCERTAIN, healthCode = result?.healthCode)
  }

  override fun observe(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult? {
    val control = control(spec.id)
    val id = params.getString("device")
    val provider = candidates(context, control, id).entries.firstOrNull()?.first ?: return null
    return verified().takeIf {
      setting(control, params).matches(runCatching { provider.recover(context, id, control, executionId) }.getOrNull())
    }
  }

  private fun control(id: String) = Control.entries.first { it.capability == id }

  private fun setting(control: Control, params: JSONObject): Setting = when (control) {
    Control.POWER -> Setting.Power(params.getBoolean("on"))
    Control.VOLUME -> Setting.Volume(params.getInt("percent"))
    Control.MUTE -> Setting.Mute(params.getBoolean("muted"))
  }

  private fun verified() = PhoneActionResult("Omniremote confirmó el cambio.", verifiedLocally = true)

  companion object { private val DEVICE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}") }
}
