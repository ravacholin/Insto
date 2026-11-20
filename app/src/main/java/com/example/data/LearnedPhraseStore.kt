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
      kotlin.runCatching {
        val intent = pm.getLaunchIntentForPackage(name) ?: return@runCatching false
        val activity = pm.resolveActivity(intent, 0)?.activityInfo ?: return@runCatching false
        activity.packageName == name && activity.enabled && activity.exported && activity.applicationInfo.enabled &&
          (activity.permission.isNullOrEmpty() || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
      }.getOrDefault(false)
    },
  )

  /** AUTOMATIC: learned after a confirmed model proposal. MANUAL: taught or corrected by the user. */
  enum class Source { AUTOMATIC, MANUAL }

  data class Entry(
    val phrase: String,
    val command: PhoneCommand,
    val date: Long,
    val uses: Long,
    val source: Source = Source.AUTOMATIC,
    /** The request the user wrote for a manual entry, kept to edit it later. */
    val target: String? = null,
  )
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
    if (!allows(entry)) return null
    // A taught app name is resolved by the executor like the local request it came from.
    val packageName = (command as? PhoneCommand.OpenApp)?.packageName
    if (packageName != null && !isLaunchable(packageName)) {
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
      val existing = current.firstOrNull { it.phrase == phrase }
      // What the user taught explicitly is never replaced by a model proposal.
      if (existing?.source == Source.MANUAL) return@edit
      write(prefs, listOf(Entry(phrase, saved, clock(), (existing?.uses ?: 0) + 1)) + current.filterNot { it.phrase == phrase })
      // «Olvidá esta frase» also undoes what was just learned.
      prefs[lastUsed] = phrase
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

  /**
   * Saves a phrase the user taught. [phrase] must be a key accepted by [key]; callers validate it with
   * [PhraseTeaching]. Editing passes the previous phrase in [replacing]. Returns false when the store refused it.
   */
  suspend fun teach(phrase: String, command: PhoneCommand, target: String? = null, replacing: String? = null): Boolean {
    if (!allowsManual(command) || key(InterpretationRequest(phrase)) != phrase) return false
    store.edit { prefs ->
      val current = entries(prefs)
      val previous = current.firstOrNull { it.phrase == (replacing ?: phrase) } ?: current.firstOrNull { it.phrase == phrase }
      val entry = Entry(phrase, command, clock(), previous?.uses ?: 0, Source.MANUAL, target?.trim()?.takeIf(String::isNotEmpty))
      write(prefs, listOf(entry) + current.filterNot { it.phrase == phrase || it.phrase == replacing })
      prefs[lastUsed] = phrase
    }
    return true
  }

  /** Replaces the action of the last phrase used or learned. Returns that phrase, or null when there is none. */
  suspend fun correctLast(command: PhoneCommand, target: String? = null, isCurrent: () -> Boolean = { true }): String? {
    if (!allowsManual(command)) return null
    var corrected: String? = null
    store.edit { prefs ->
      if (!isCurrent()) return@edit
      val phrase = prefs[lastUsed] ?: return@edit
      val current = entries(prefs)
      val entry = current.firstOrNull { it.phrase == phrase } ?: return@edit
      val updated = entry.copy(command = command, date = clock(), source = Source.MANUAL,
        target = target?.trim()?.takeIf(String::isNotEmpty))
      write(prefs, listOf(updated) + current.filterNot { it == entry })
      corrected = phrase
    }
    return corrected
  }

  /** A JSON copy of every entry. The store itself is excluded from Android backups. */
  suspend fun export(): String = exportJson(settings.first().entries)

  /** Merges a file made by [export]. Imported phrases replace equal ones. Returns how many were imported. */
  suspend fun import(text: String): Int {
    val imported = decodeExport(text)
    store.edit { prefs ->
      val phrases = imported.map { it.phrase }.toSet()
      write(prefs, imported + entries(prefs).filterNot { it.phrase in phrases })
    }
    return imported.size
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
        // Only a taught app name may lack a package; allows(entry) rejects the rest at lookup.
        val command = if (decoded is PhoneCommand.OpenApp) decoded.copy(packageName = obj.optString("package").takeIf(String::isNotBlank))
          else decoded
        val source = if (obj.optString("source") == "manual") Source.MANUAL else Source.AUTOMATIC
        Entry(obj.getString("phrase"), command, obj.getLong("date"), obj.getLong("uses"), source,
          obj.optString("target").takeIf { source == Source.MANUAL && it.isNotBlank() })
      }.getOrNull()
    }
  }

  private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, entries: List<Entry>) {
    prefs[data] = encode(trim(entries)).toString()
  }

  companion object {
    const val LIMIT = 200
    private const val EXPORT_LIMIT = 262_144
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

    /** Keeps [LIMIT] entries. Automatic ones are dropped first, starting with the least recent. */
    internal fun trim(entries: List<Entry>): List<Entry> {
      if (entries.size <= LIMIT) return entries
      val result = entries.toMutableList()
      while (result.size > LIMIT) {
        val index = result.indexOfLast { it.source == Source.AUTOMATIC }.takeIf { it >= 0 } ?: result.lastIndex
        result.removeAt(index)
      }
      return result
    }

    private fun encode(entries: List<Entry>) = JSONArray(entries.map {
      JSONObject().put("phrase", it.phrase).put("command", PhoneCommandCodec.encode(it.command))
        .put("date", it.date).put("uses", it.uses)
        .apply {
          (it.command as? PhoneCommand.OpenApp)?.packageName?.let { pkg -> put("package", pkg) }
          if (it.source == Source.MANUAL) put("source", "manual")
          it.target?.let { target -> put("target", target) }
        }
    })

    internal fun exportJson(entries: List<Entry>): String =
      JSONObject().put("format", "insto-learned-phrases").put("version", 1).put("phrases", encode(entries)).toString(2)

    /** Validates a whole export before anything is replaced. Throws IllegalArgumentException when it is invalid. */
    internal fun decodeExport(text: String): List<Entry> {
      require(text.length <= EXPORT_LIMIT) { "El archivo es demasiado grande." }
      val root = runCatching { JSONObject(text) }.getOrNull()
      require(root?.optString("format") == "insto-learned-phrases") { "El archivo no es una copia de frases de Insto." }
      val array = root!!.getJSONArray("phrases")
      require(array.length() <= LIMIT) { "El archivo tiene más de $LIMIT frases." }
      val entries = (0 until array.length()).map { i ->
        val entry = runCatching {
          val obj = array.getJSONObject(i)
          val decoded = PhoneCommandCodec.decode(obj.getJSONObject("command"))
          val command = if (decoded is PhoneCommand.OpenApp) decoded.copy(packageName = obj.optString("package").takeIf(String::isNotBlank))
            else decoded
          val source = if (obj.optString("source") == "manual") Source.MANUAL else Source.AUTOMATIC
          Entry(obj.getString("phrase"), command, obj.optLong("date"), obj.optLong("uses").coerceAtLeast(0), source,
            obj.optString("target").takeIf { source == Source.MANUAL && it.isNotBlank() })
        }.getOrNull()
        require(entry != null && key(InterpretationRequest(entry.phrase)) == entry.phrase && allows(entry)) {
          "La frase ${i + 1} no es válida."
        }
        entry
      }
      require(entries.map { it.phrase }.distinct().size == entries.size) { "El archivo repite frases." }
      return entries
    }

    internal fun allows(entry: Entry): Boolean = when (entry.source) {
      Source.AUTOMATIC -> allows(entry.command) &&
        (entry.command !is PhoneCommand.OpenApp || entry.command.packageName != null)
      Source.MANUAL -> allowsManual(entry.command)
    }

    /**
     * Actions a user may bind to a phrase explicitly. They come from a request the local parser understood
     * and keep their usual confirmations. Calls, messages, files and calendar changes always need the full request.
     */
    internal fun allowsManual(command: PhoneCommand): Boolean = allows(command) || when (command) {
      is PhoneCommand.Timer, is PhoneCommand.ClockList, is PhoneCommand.Navigate, is PhoneCommand.MapSearch,
      is PhoneCommand.YouTubeSearch, is PhoneCommand.SpotifySearch, is PhoneCommand.MusicSearch,
      is PhoneCommand.Settings, PhoneCommand.EndMode -> true
      is PhoneCommand.Alarm -> command.date == null
      is PhoneCommand.TemporaryMode -> command.deadlineMillis == null
      is PhoneCommand.Platform -> when (command.action) {
        PlatformAction.ROUTINE_RUN -> !command.argument.isNullOrBlank()
        PlatformAction.DND_ON, PlatformAction.DND_OFF, PlatformAction.BATTERY, PlatformAction.ROUTINE_LIST,
        PlatformAction.ALARM_SNOOZE, PlatformAction.TIMER_DISMISS, PlatformAction.CALENDAR_AGENDA,
        PlatformAction.DIALER_OPEN, PlatformAction.FILES_BROWSE, PlatformAction.RECORDER_OPEN,
        PlatformAction.DISPLAY_SETTINGS, PlatformAction.ACCESSIBILITY_SETTINGS, PlatformAction.NOTIFICATION_LIST,
        PlatformAction.BRIGHTNESS -> command.argument == null && command.content == null
        else -> false
      }
      else -> false
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
