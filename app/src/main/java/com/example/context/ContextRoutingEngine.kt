package com.example.context

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferences
import com.example.data.PinnedContact
import java.util.Calendar

data class EffectiveContextRoute(
  val contact: PinnedContact?,
  val activationMode: AssistantActivationMode,
  val activeContextLabel: String?,
  val isCarOrBluetoothActive: Boolean,
  val connectedAudioDeviceName: String?,
)

object ContextRoutingEngine {

  fun resolveRoute(
    context: Context,
    prefs: AssistantPreferences,
    hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
  ): EffectiveContextRoute {
    val btDeviceName = findConnectedBluetoothAudioName(context)
    val inCarUiMode = isCarUiMode(context)
    val carOrBtActive = inCarUiMode || btDeviceName != null

    val rule = prefs.contextRule

    if (carOrBtActive && rule.switchToVoiceInCarOrBluetooth) {
      val resolvedContact = rule.drivingContact ?: prefs.pinnedContact
      val label =
        when {
          inCarUiMode -> "Car Mode · Voice"
          btDeviceName != null -> "BT: ${btDeviceName.take(16)} · Voice"
          else -> "Driving · Voice"
        }
      return EffectiveContextRoute(
        contact = resolvedContact,
        activationMode = AssistantActivationMode.INSTANT_RECORD_AUDIO,
        activeContextLabel = label,
        isCarOrBluetoothActive = true,
        connectedAudioDeviceName = btDeviceName,
      )
    }

    if (rule.nightWindowEnabled && isWithinHourWindow(hourOfDay, rule.nightStartHour, rule.nightEndHour)) {
      return EffectiveContextRoute(
        contact = prefs.pinnedContact,
        activationMode = AssistantActivationMode.INSTANT_RECORD_AUDIO,
        activeContextLabel = "Night Window · Voice",
        isCarOrBluetoothActive = carOrBtActive,
        connectedAudioDeviceName = btDeviceName,
      )
    }

    return EffectiveContextRoute(
      contact = prefs.pinnedContact,
      activationMode = prefs.activationMode,
      activeContextLabel = if (carOrBtActive) btDeviceName?.let { "BT: ${it.take(16)}" } else null,
      isCarOrBluetoothActive = carOrBtActive,
      connectedAudioDeviceName = btDeviceName,
    )
  }

  fun isWithinHourWindow(hour: Int, startHour: Int, endHour: Int): Boolean {
    return if (startHour <= endHour) {
      hour in startHour until endHour
    } else {
      hour >= startHour || hour < endHour
    }
  }

  private fun isCarUiMode(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
  }

  private fun findConnectedBluetoothAudioName(context: Context): String? {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val btDevice =
      outputs.firstOrNull { device ->
        when (device.type) {
          AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
          else ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
              (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
      } ?: return null

    return btDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio"
  }
}
