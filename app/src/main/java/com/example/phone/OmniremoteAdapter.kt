package com.example.phone

import android.content.Context
import org.json.JSONObject

/**
 * Insto-side contract. A provider implementation needs documented operations, local authorization and read-back.
 * The production registry has no binding until Omniremote provides a compatible interface.
 */
internal class OmniremoteAdapter(private val providers: List<Provider> = emptyList()) : InstinctCapabilityAdapter {
  enum class Transport { LOCAL_API, APP_FUNCTION, ANDROID_INTENT }
  enum class Control { POWER, VOLUME, MUTE }

  sealed interface Setting {
    data class Power(val on: Boolean) : Setting
    data class Volume(val percent: Int) : Setting
    data class Mute(val muted: Boolean) : Setting
  }

  data class Television(val id: String, val controls: Set<Control>)

  interface Provider {
    val transport: Transport
    /** Must confirm the OS/provider access is present, including AppFunctions access when applicable. */
    fun authorized(context: Context): Boolean
    /** Return only locally authorized televisions, with stable provider-qualified IDs. */
    fun televisions(context: Context): List<Television>
    /** A headless absolute operation with no activity or permission prompt. */
    fun set(context: Context, id: String, setting: Setting, executionId: String): PhoneActionResult
    /** Compare the current state through the same authorized provider. Null means it could not be observed. */
    fun read(context: Context, id: String, control: Control): Setting?
  }

  private fun candidates(context: Context, control: Control): List<Pair<Provider, Television>> =
    providers.sortedBy { it.transport }.flatMap { provider ->
      if (!provider.authorized(context)) emptyList() else provider.televisions(context)
        .filter { control in it.controls && DEVICE_ID.matches(it.id) }
        .map { provider to it }
    }

  override fun targets(context: Context, spec: InstinctCapabilities.Spec): List<String> =
    candidates(context, control(spec.id)).map { it.second.id }.distinct().sorted()

  override fun blocker(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject?): InstinctDeviceCapabilities.Block? {
    if (providers.isEmpty()) return unavailableOmniremote()
    val targets = targets(context, spec)
    return if (targets.isEmpty() || params != null && params.getString("device") !in targets)
      InstinctDeviceCapabilities.Block("No hay un televisor autorizado para este control.", false) else null
  }

  override fun execute(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult {
    val control = control(spec.id)
    val id = params.getString("device")
    val provider = candidates(context, control).firstOrNull { it.second.id == id }?.first
      ?: return PhoneActionResult("No hay un televisor autorizado para este control.", outcome = PhoneActionOutcome.REJECTED)
    val setting = setting(control, params)
    val result = runCatching { provider.set(context, id, setting, executionId) }.getOrNull()
    if (result?.outcome == PhoneActionOutcome.REJECTED || result?.outcome == PhoneActionOutcome.NEEDS_INPUT)
      return result.copy(verifiedLocally = false)
    return if (runCatching { provider.read(context, id, control) }.getOrNull() == setting) verified() else
      PhoneActionResult("No pude comprobar el estado del televisor.", outcome = PhoneActionOutcome.UNCERTAIN)
  }

  override fun observe(context: Context, spec: InstinctCapabilities.Spec, params: JSONObject, executionId: String): PhoneActionResult? {
    val control = control(spec.id)
    val id = params.getString("device")
    val provider = candidates(context, control).firstOrNull { it.second.id == id }?.first ?: return null
    return verified().takeIf { provider.read(context, id, control) == setting(control, params) }
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
