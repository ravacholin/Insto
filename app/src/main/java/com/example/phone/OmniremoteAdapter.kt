package com.example.phone

import android.content.Context
import org.json.JSONObject

/** Insto-side television control with local authorization and independent read-back. */
internal class OmniremoteAdapter(private val providers: List<Provider> = emptyList()) : InstinctCapabilityAdapter {
  enum class Transport { LOCAL_API, APP_FUNCTION, ANDROID_INTENT }
  enum class Control { POWER, VOLUME, MUTE }

  sealed interface Setting {
    data class Power(val on: Boolean) : Setting
    data class Volume(val percent: Int, val scale: VolumeScale? = null) : Setting
    data class Mute(val muted: Boolean) : Setting
  }

  data class VolumeScale(val level: Int, val max: Int)

  data class Television(val id: String, val controls: Set<Control>)

  interface Provider {
    val transport: Transport
    /** Discover registered televisions through an authorized provider. A target's control channel may require pairing. */
    fun televisions(context: Context, id: String? = null): List<Television>
    /** A headless absolute operation with no activity or permission prompt. */
    fun set(context: Context, id: String, setting: Setting, executionId: String): PhoneActionResult
    /** Compare the current state through the same authorized provider. Null means it could not be observed. */
    fun read(context: Context, id: String, control: Control): Setting?
    fun recover(context: Context, id: String, control: Control, executionId: String): Setting? = read(context, id, control)
  }

  class DiscoveryFailure(message: String) : Exception(message)

  private class Candidates(
    val entries: List<Pair<Provider, Television>>,
    val reason: String,
  )

  private fun candidates(context: Context, control: Control, id: String? = null): Candidates {
    var failure: String? = null
    val entries = providers.sortedBy { it.transport }.flatMap { provider ->
      try {
        provider.televisions(context, id)
          .filter { television -> control in television.controls && DEVICE_ID.matches(television.id) &&
            (id == null || id == television.id) }
          .map { provider to it }
      } catch (error: DiscoveryFailure) {
        if (failure == null) failure = error.message
        emptyList()
      }
    }
    return Candidates(entries, failure ?: "No hay un televisor disponible para este control en Omniremote.")
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
      ?: return PhoneActionResult(found.reason, outcome = PhoneActionOutcome.REJECTED)
    val setting = setting(control, params)
    val result = runCatching { provider.set(context, id, setting, executionId) }.getOrNull()
    if (result?.outcome == PhoneActionOutcome.REJECTED || result?.outcome == PhoneActionOutcome.NEEDS_INPUT)
      return result.copy(verifiedLocally = false)
    return if (matches(setting, runCatching { provider.read(context, id, control) }.getOrNull())) verified() else
      PhoneActionResult(result?.takeIf { it.outcome == PhoneActionOutcome.UNCERTAIN }?.message
        ?: "No pude comprobar el estado del televisor.", outcome = PhoneActionOutcome.UNCERTAIN)
  }

  override fun observe(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult? {
    val control = control(spec.id)
    val id = params.getString("device")
    val provider = candidates(context, control, id).entries.firstOrNull()?.first ?: return null
    return verified().takeIf {
      matches(setting(control, params), runCatching { provider.recover(context, id, control, executionId) }.getOrNull())
    }
  }

  private fun matches(requested: Setting, observed: Setting?): Boolean {
    if (requested is Setting.Volume && observed is Setting.Volume) {
      val scale = observed.scale ?: return observed.percent == requested.percent
      // OMNIREMOTE rounds a percentage to the nearest physical step on Android TV.
      return scale.level.toLong() == (requested.percent.toLong() * scale.max + 50) / 100
    }
    return observed == requested
  }

  private fun control(id: String) = when (id) {
    "omniremote_power" -> Control.POWER
    "omniremote_volume" -> Control.VOLUME
    "omniremote_mute" -> Control.MUTE
    else -> error("Control de Omniremote desconocido")
  }

  private fun setting(control: Control, params: JSONObject): Setting = when (control) {
    Control.POWER -> Setting.Power(params.getBoolean("on"))
    Control.VOLUME -> Setting.Volume(params.getInt("percent"))
    Control.MUTE -> Setting.Mute(params.getBoolean("muted"))
  }

  private fun verified() = PhoneActionResult("Estado del televisor comprobado.", verifiedLocally = true)

  companion object { private val DEVICE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}") }
}
