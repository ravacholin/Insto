package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.assistantDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "ping_assist_prefs")

class AssistantPreferencesRepository(private val context: Context) {

  private object Keys {
    val CONTACT_NAME = stringPreferencesKey("contact_name")
    val CONTACT_PHONE = stringPreferencesKey("contact_phone")
    val DRIVING_CONTACT_NAME = stringPreferencesKey("driving_contact_name")
    val DRIVING_CONTACT_PHONE = stringPreferencesKey("driving_contact_phone")
    val ACTIVATION_MODE = stringPreferencesKey("activation_mode")
    val ORB_HOLD_BEHAVIOR = stringPreferencesKey("orb_hold_behavior")
    val WHATSAPP_FLAVOR = stringPreferencesKey("whatsapp_flavor")
    val VOICE_RECORDING_MODE = stringPreferencesKey("voice_recording_mode")
    val VOLUME_KEY_TRIGGER = stringPreferencesKey("volume_key_trigger")
    val FLOATING_PILL_MODE = stringPreferencesKey("floating_pill_mode")
    val EYES_FREE_CUES = booleanPreferencesKey("eyes_free_cues")
    val BOOMERANG_RETURN = booleanPreferencesKey("boomerang_return")
    val MIC_PROMPTED_ONCE = booleanPreferencesKey("mic_prompted_once")
    val CAR_BT_AUTO_VOICE = booleanPreferencesKey("car_bt_auto_voice")
    val NIGHT_WINDOW_ENABLED = booleanPreferencesKey("night_window_enabled")
    val NIGHT_START_HOUR = intPreferencesKey("night_start_hour")
    val NIGHT_END_HOUR = intPreferencesKey("night_end_hour")
    val GROQ_VOCABULARY = booleanPreferencesKey("groq_vocabulary")
    val GROQ_PINNED_CONTACT_HINTS = booleanPreferencesKey("groq_pinned_contact_hints")
    val RECENT_LOGS = stringPreferencesKey("recent_logs_json")
  }

  val preferencesFlow: Flow<AssistantPreferences> =
    context.assistantDataStore.data.map { prefs -> mapPreferences(prefs) }

  suspend fun getCurrentPreferences(): AssistantPreferences = preferencesFlow.first()

  suspend fun savePinnedContact(displayName: String, phoneNumber: String) {
    val trimmedName = displayName.trim().ifEmpty { phoneNumber.trim() }
    val trimmedPhone = phoneNumber.trim()
    context.assistantDataStore.edit { prefs ->
      prefs[Keys.CONTACT_NAME] = trimmedName
      prefs[Keys.CONTACT_PHONE] = trimmedPhone
    }
  }

  suspend fun saveDrivingContact(displayName: String, phoneNumber: String) {
    val trimmedName = displayName.trim().ifEmpty { phoneNumber.trim() }
    val trimmedPhone = phoneNumber.trim()
    context.assistantDataStore.edit { prefs ->
      prefs[Keys.DRIVING_CONTACT_NAME] = trimmedName
      prefs[Keys.DRIVING_CONTACT_PHONE] = trimmedPhone
    }
  }

  suspend fun clearDrivingContact() {
    context.assistantDataStore.edit { prefs ->
      prefs.remove(Keys.DRIVING_CONTACT_NAME)
      prefs.remove(Keys.DRIVING_CONTACT_PHONE)
    }
  }

  suspend fun clearPinnedContact() {
    context.assistantDataStore.edit { prefs ->
      prefs.remove(Keys.CONTACT_NAME)
      prefs.remove(Keys.CONTACT_PHONE)
    }
  }

  suspend fun setActivationMode(mode: AssistantActivationMode) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.ACTIVATION_MODE] = mode.name }
  }

  suspend fun setOrbHoldBehavior(behavior: OrbHoldBehavior) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.ORB_HOLD_BEHAVIOR] = behavior.name }
  }

  suspend fun setPreferredFlavor(flavor: WhatsAppFlavor) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.WHATSAPP_FLAVOR] = flavor.name }
  }

  suspend fun setVoiceRecordingMode(mode: VoiceRecordingMode) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.VOICE_RECORDING_MODE] = mode.name }
  }

  suspend fun setVolumeKeyTrigger(trigger: VolumeKeyTrigger) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.VOLUME_KEY_TRIGGER] = trigger.name }
  }

  suspend fun setFloatingPillMode(mode: FloatingPillMode) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.FLOATING_PILL_MODE] = mode.name }
  }

  suspend fun setEyesFreeAudioCues(enabled: Boolean) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.EYES_FREE_CUES] = enabled }
  }

  suspend fun setBoomerangReturn(enabled: Boolean) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.BOOMERANG_RETURN] = enabled }
  }

  suspend fun markMicPermissionPrompted() {
    context.assistantDataStore.edit { prefs -> prefs[Keys.MIC_PROMPTED_ONCE] = true }
  }

  suspend fun setCarBtAutoVoice(enabled: Boolean) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.CAR_BT_AUTO_VOICE] = enabled }
  }

  suspend fun setNightWindowEnabled(enabled: Boolean) {
    context.assistantDataStore.edit { prefs -> prefs[Keys.NIGHT_WINDOW_ENABLED] = enabled }
  }

  suspend fun setGroqVocabularyEnabled(enabled: Boolean) {
    context.assistantDataStore.edit { it[Keys.GROQ_VOCABULARY] = enabled }
  }

  suspend fun setGroqPinnedContactHintsEnabled(enabled: Boolean) {
    context.assistantDataStore.edit { it[Keys.GROQ_PINNED_CONTACT_HINTS] = enabled }
  }

  suspend fun appendActionLog(entry: PingActionLog) {
    context.assistantDataStore.edit { prefs ->
      val current = decodeLogs(prefs[Keys.RECENT_LOGS])
      val updated = (listOf(entry) + current).take(MAX_LOG_ENTRIES)
      prefs[Keys.RECENT_LOGS] = encodeLogs(updated)
    }
  }

  suspend fun clearLogs() {
    context.assistantDataStore.edit { prefs -> prefs.remove(Keys.RECENT_LOGS) }
  }

  /**
   * The configuration values present now, by stable backup name (the DataStore name). Absent keys use their defaults
   * and are not listed. Logs and the microphone-prompt state are never part of a backup.
   */
  suspend fun backupValues(): Map<String, Any> {
    val prefs = context.assistantDataStore.data.first()
    return buildMap {
      for ((name, type) in BACKUP_KEYS) {
        val value: Any? = when (type) {
          BackupType.STRING -> prefs[stringPreferencesKey(name)]
          BackupType.BOOLEAN -> prefs[booleanPreferencesKey(name)]
          BackupType.INT -> prefs[intPreferencesKey(name)]
        }
        if (value != null) put(name, value)
      }
    }
  }

  /** Writes validated backup values and removes [remove] in a single DataStore transaction. */
  suspend fun restoreBackupValues(set: Map<String, Any>, remove: Set<String>) {
    require((set.keys + remove).all { it in BACKUP_KEYS })
    context.assistantDataStore.edit { prefs ->
      for (name in remove) when (BACKUP_KEYS.getValue(name)) {
        BackupType.STRING -> prefs.remove(stringPreferencesKey(name))
        BackupType.BOOLEAN -> prefs.remove(booleanPreferencesKey(name))
        BackupType.INT -> prefs.remove(intPreferencesKey(name))
      }
      for ((name, value) in set) when (BACKUP_KEYS.getValue(name)) {
        BackupType.STRING -> prefs[stringPreferencesKey(name)] = value as String
        BackupType.BOOLEAN -> prefs[booleanPreferencesKey(name)] = value as Boolean
        BackupType.INT -> prefs[intPreferencesKey(name)] = value as Int
      }
    }
  }

  enum class BackupType { STRING, BOOLEAN, INT }

  companion object {
    private const val MAX_LOG_ENTRIES = 12

    /** Stable names and types of the user configuration a backup carries. */
    val BACKUP_KEYS: Map<String, BackupType> = linkedMapOf(
      "contact_name" to BackupType.STRING, "contact_phone" to BackupType.STRING,
      "driving_contact_name" to BackupType.STRING, "driving_contact_phone" to BackupType.STRING,
      "activation_mode" to BackupType.STRING, "orb_hold_behavior" to BackupType.STRING,
      "whatsapp_flavor" to BackupType.STRING, "voice_recording_mode" to BackupType.STRING,
      "volume_key_trigger" to BackupType.STRING, "floating_pill_mode" to BackupType.STRING,
      "eyes_free_cues" to BackupType.BOOLEAN, "boomerang_return" to BackupType.BOOLEAN,
      "car_bt_auto_voice" to BackupType.BOOLEAN, "night_window_enabled" to BackupType.BOOLEAN,
      "night_start_hour" to BackupType.INT, "night_end_hour" to BackupType.INT,
      "groq_vocabulary" to BackupType.BOOLEAN, "groq_pinned_contact_hints" to BackupType.BOOLEAN,
    )
  }

  private fun mapPreferences(prefs: Preferences): AssistantPreferences {
    val name = prefs[Keys.CONTACT_NAME]
    val phone = prefs[Keys.CONTACT_PHONE]
    val contact =
      if (!phone.isNullOrBlank()) {
        PinnedContact(
          displayName = name?.takeIf { it.isNotBlank() } ?: phone,
          phoneNumber = phone,
        )
      } else {
        null
      }

    val drivingName = prefs[Keys.DRIVING_CONTACT_NAME]
    val drivingPhone = prefs[Keys.DRIVING_CONTACT_PHONE]
    val drivingContact =
      if (!drivingPhone.isNullOrBlank()) {
        PinnedContact(
          displayName = drivingName?.takeIf { it.isNotBlank() } ?: drivingPhone,
          phoneNumber = drivingPhone,
        )
      } else {
        null
      }

    val mode =
      prefs[Keys.ACTIVATION_MODE]?.let { raw ->
        runCatching { AssistantActivationMode.valueOf(raw) }.getOrNull()
      } ?: AssistantActivationMode.TAP_CHAT_HOLD_AUDIO

    val orbBehavior =
      prefs[Keys.ORB_HOLD_BEHAVIOR]?.let { raw ->
        runCatching { OrbHoldBehavior.valueOf(raw) }.getOrNull()
      } ?: OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO

    val flavor =
      prefs[Keys.WHATSAPP_FLAVOR]?.let { raw ->
        runCatching { WhatsAppFlavor.valueOf(raw) }.getOrNull()
      } ?: WhatsAppFlavor.AUTO

    val voiceMode =
      prefs[Keys.VOICE_RECORDING_MODE]?.let { raw ->
        runCatching { VoiceRecordingMode.valueOf(raw) }.getOrNull()
      } ?: VoiceRecordingMode.AUDIO_FILE_AUTO_SEND

    val volumeTrigger =
      prefs[Keys.VOLUME_KEY_TRIGGER]?.let { raw ->
        runCatching { VolumeKeyTrigger.valueOf(raw) }.getOrNull()
      } ?: VolumeKeyTrigger.DISABLED

    val pillMode =
      prefs[Keys.FLOATING_PILL_MODE]?.let { raw ->
        runCatching { FloatingPillMode.valueOf(raw) }.getOrNull()
      } ?: FloatingPillMode.OFF

    val contextRule =
      ContextRuleConfig(
        switchToVoiceInCarOrBluetooth = prefs[Keys.CAR_BT_AUTO_VOICE] ?: true,
        drivingContact = drivingContact,
        nightWindowEnabled = prefs[Keys.NIGHT_WINDOW_ENABLED] ?: false,
        nightStartHour = prefs[Keys.NIGHT_START_HOUR] ?: 21,
        nightEndHour = prefs[Keys.NIGHT_END_HOUR] ?: 7,
      )

    return AssistantPreferences(
      pinnedContact = contact,
      activationMode = mode,
      orbHoldBehavior = orbBehavior,
      preferredFlavor = flavor,
      voiceRecordingMode = voiceMode,
      volumeKeyTrigger = volumeTrigger,
      floatingPillMode = pillMode,
      eyesFreeAudioCues = prefs[Keys.EYES_FREE_CUES] ?: true,
      boomerangReturnEnabled = prefs[Keys.BOOMERANG_RETURN] ?: false,
      hasPromptedMicPermission = prefs[Keys.MIC_PROMPTED_ONCE] ?: false,
      contextRule = contextRule,
      recentLogs = decodeLogs(prefs[Keys.RECENT_LOGS]),
      groqVocabularyEnabled = prefs[Keys.GROQ_VOCABULARY] ?: true,
      groqPinnedContactHintsEnabled = prefs[Keys.GROQ_PINNED_CONTACT_HINTS] ?: false,
    )
  }

  private fun encodeLogs(logs: List<PingActionLog>): String {
    val array = JSONArray()
    logs.forEach { log ->
      val obj =
        JSONObject().apply {
          put("id", log.id)
          put("type", log.type.name)
          put("contactName", log.contactName)
          put("timestampMillis", log.timestampMillis)
          if (log.durationSeconds != null) {
            put("durationSeconds", log.durationSeconds)
          }
          put("statusLabel", log.statusLabel)
        }
      array.put(obj)
    }
    return array.toString()
  }

  private fun decodeLogs(raw: String?): List<PingActionLog> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
          for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type =
              runCatching { PingActionType.valueOf(obj.getString("type")) }.getOrNull()
                ?: continue
            add(
              PingActionLog(
                id = obj.getString("id"),
                type = type,
                contactName = obj.getString("contactName"),
                timestampMillis = obj.getLong("timestampMillis"),
                durationSeconds =
                  if (obj.has("durationSeconds") && !obj.isNull("durationSeconds")) {
                    obj.getInt("durationSeconds")
                  } else {
                    null
                  },
                statusLabel = obj.optString("statusLabel", ""),
              )
            )
          }
        }
      }
      .getOrDefault(emptyList())
  }

}
