package com.example.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.phone.ConversationSession
import com.example.phone.InterpretationRequest
import com.example.phone.PhoneActionOutcome
import com.example.phone.PhoneActionResult
import com.example.phone.PhoneCommand
import com.example.phone.PhoneCommandCodec
import com.example.phone.PhoneCommandParser
import com.example.phone.PlatformAction
import com.example.phone.RequestSafetyPolicy
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.learnedPhraseDataStore by preferencesDataStore(name = "learned_phrases")

class LearnedPhraseStore internal constructor(
  private val store: DataStore<Preferences>,
  private val isLaunchable: (String) -> Boolean,
  private val clock: () -> Long = System::currentTimeMillis,
  private val updates: CoroutineScope = storageScope,
) {
  constructor(context: Context) : this(
    context.applicationContext.learnedPhraseDataStore,
    { name ->
      val pm = context.applicationContext.packageManager
      runCatching {
        val intent = pm.getLaunchIntentForPackage(name) ?: return@runCatching false
        val activity = pm.resolveActivity(intent, 0)?.activityInfo ?: return@runCatching false
        activity.packageName == name && activity.enabled && activity.exported && activity.applicationInfo.enabled &&
          (activity.permission.isNullOrEmpty() || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
      }.getOrDefault(false)
    },
  )

  data class Entry(val phrase: String, val command: PhoneCommand, val date: Long, val uses: Long)
  data class Settings(val enabled: Boolean = true, val entries: List<Entry> = emptyList())

  private val settings = store.data.map { Settings(it[enabled] ?: true, entries(it)) }
  val uiSettings = settings.catch { error ->
    if (error is IOException) emit(Settings(enabled = false)) else throw error
  }

  suspend fun lookup(request: InterpretationRequest): PhoneCommand? {
    val phrase = key(request) ?: return null
    val entry = try {
      val state = settings.first()
      if (!state.enabled) return null
      state.entries.firstOrNull { it.phrase == phrase }
    } catch (_: IOException) { return null } ?: return null
    val command = entry.command
    if (!allows(command)) return null
    if (command is PhoneCommand.OpenApp && !isLaunchable(command.packageName ?: return null)) {
      try {
        store.edit { prefs -> write(prefs, entries(prefs).filterNot { it == entry }) }
      } catch (_: IOException) { }
      return null
    }
    return command
  }

  // El panel puede cerrarse como resultado de una acción exitosa.
  internal fun saveConfirmed(request: InterpretationRequest, command: PhoneCommand, result: PhoneActionResult) {
    updates.launch(start = CoroutineStart.UNDISPATCHED) {
      try { learn(request, command, confirmed = true, result = result) } catch (_: IOException) { }
    }
  }

  internal fun saveUse(request: InterpretationRequest, command: PhoneCommand) {
    updates.launch(start = CoroutineStart.UNDISPATCHED) {
      try { recordUse(request, command) } catch (_: IOException) { }
    }
  }

  internal suspend fun learn(request: InterpretationRequest, command: PhoneCommand, confirmed: Boolean, result: PhoneActionResult) {
    val phrase = key(request) ?: return
    if (!confirmed || !allows(command) || result.retainRequest || result.confirmation != null ||
      result.appOptions.isNotEmpty() || result.needsClockAccessibility || result.accessSettingsAction != null) return
    val saved = when (command) {
      is PhoneCommand.OpenApp -> command.copy(packageName = result.launchedAppPackage ?: return)
      else -> command
    }
    if (result.outcome != PhoneActionOutcome.COMPLETED &&
      !(result.outcome == PhoneActionOutcome.HANDOFF && (saved is PhoneCommand.Camera || saved is PhoneCommand.OpenApp))) return
    store.edit { prefs ->
      if (prefs[enabled] == false) return@edit
      val current = entries(prefs)
      val uses = current.firstOrNull { it.phrase == phrase }?.uses ?: 0
      write(prefs, (listOf(Entry(phrase, saved, clock(), uses + 1)) + current.filterNot { it.phrase == phrase }).take(200))
    }
  }

  internal suspend fun recordUse(request: InterpretationRequest, command: PhoneCommand) {
    val phrase = key(request) ?: return
    store.edit { prefs ->
      if (prefs[enabled] == false) return@edit
      val current = entries(prefs)
      val entry = current.firstOrNull { it.phrase == phrase && it.command == command } ?: return@edit
      write(prefs, listOf(entry.copy(date = clock(), uses = entry.uses + 1)) + current.filterNot { it == entry })
      prefs[lastUsed] = phrase
    }
  }

  suspend fun forgetLast(isCurrent: () -> Boolean = { true }): Boolean {
    var removed = false
    store.edit { prefs ->
      if (!isCurrent()) return@edit
      val phrase = prefs.remove(lastUsed) ?: return@edit
      val current = entries(prefs)
      val remaining = current.filterNot { it.phrase == phrase }
      removed = remaining.size != current.size
      write(prefs, remaining)
    }
    return removed
  }

  suspend fun delete(phrase: String) {
    store.edit { prefs ->
      if (prefs[lastUsed] == phrase) prefs.remove(lastUsed)
      write(prefs, entries(prefs).filterNot { it.phrase == phrase })
    }
  }

  suspend fun clear() {
    store.edit { it.remove(data); it.remove(lastUsed) }
  }

  suspend fun setEnabled(value: Boolean) {
    store.edit { it[enabled] = value }
  }

  private fun entries(prefs: Preferences): List<Entry> {
    val array = runCatching { JSONArray(prefs[data] ?: "[]") }.getOrElse { return emptyList() }
    return (0 until array.length()).mapNotNull { i ->
      runCatching {
        val obj = array.getJSONObject(i)
        val decoded = PhoneCommandCodec.decode(obj.getJSONObject("command"))
        val command = if (decoded is PhoneCommand.OpenApp) decoded.copy(packageName = obj.getString("package"))
          else decoded
        Entry(obj.getString("phrase"), command, obj.getLong("date"), obj.getLong("uses"))
      }.getOrNull()
    }
  }

  private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, entries: List<Entry>) {
    prefs[data] = JSONArray(entries.map {
      JSONObject().put("phrase", it.phrase).put("command", PhoneCommandCodec.encode(it.command))
        .put("date", it.date).put("uses", it.uses)
        .apply { (it.command as? PhoneCommand.OpenApp)?.packageName?.let { pkg -> put("package", pkg) } }
    }).toString()
  }

  companion object {
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enabled = booleanPreferencesKey("enabled")
    private val data = stringPreferencesKey("entries")
    private val lastUsed = stringPreferencesKey("last_used")
    private val conditionalOrNegative = Regex(
      "\\b(?:no|nunca|jamas|tampoco|ni|sin|si|cuando|salvo|excepto|mientras|siempre que|a condicion de|en caso de)\\b",
    )
    private val questionHead = Regex("^(?:(?:me )?pod(?:es|rias|ria|emos|riamos)|se puede|es posible|sabes|seria posible)\\b")

    internal fun key(request: InterpretationRequest): String? {
      if (request.draft != null || request.sourceContext != null ||
        ConversationSession.splitSteps(request.text).size != 1 ||
        request.text.any { it == '?' || it == '¿' } ||
        RequestSafetyPolicy.isGeneralRequest(request.text) ||
        RequestSafetyPolicy.rejection(request.text) != null) return null
      return PhoneCommandParser.normalize(request.text).takeIf {
        it.isNotBlank() && it.length <= 2000 &&
          !conditionalOrNegative.containsMatchIn(it) && !questionHead.containsMatchIn(it)
      }
    }

    internal fun allows(command: PhoneCommand): Boolean = when (command) {
      is PhoneCommand.Torch, is PhoneCommand.Volume, is PhoneCommand.Camera,
      is PhoneCommand.OpenApp, is PhoneCommand.Media, is PhoneCommand.Stopwatch -> true
      is PhoneCommand.Platform -> command.action in setOf(
        PlatformAction.SILENT_ON, PlatformAction.SILENT_OFF, PlatformAction.VIBRATE_ON,
      ) && command.argument == null && command.percent == null && command.content == null
      else -> false
    }
  }
}
