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
import com.example.phone.PhrasePatterns
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
  /**
   * A structure generalized from executed model interpretations. [values] are the distinct canonical parameters
   * seen so far; it is used only with [PhrasePatterns.MIN_EXAMPLES] of them and while [enabled].
   */
  data class PatternEntry(
    val prefix: String,
    val suffix: String,
    val action: PhrasePatterns.Action,
    val values: List<String>,
    val examples: List<String>,
    val date: Long,
    val uses: Long = 0,
    val enabled: Boolean = true,
  ) {
    val template: String get() = prefix + action.slot.placeholder + suffix
    val active: Boolean get() = values.size >= PhrasePatterns.MIN_EXAMPLES
    internal val pattern get() = PhrasePatterns.Pattern(prefix, suffix, action, enabled && active)
  }
  data class Settings(
    val enabled: Boolean = true,
    val entries: List<Entry> = emptyList(),
    /** Learned structures, including those still collecting examples. */
    val patterns: List<PatternEntry> = emptyList(),
  ) {
    val activePatterns: List<PatternEntry> get() = patterns.filter { it.active }
  }

  private val settings = store.data.map { Settings(it[enabled] ?: true, entries(it), patterns(it)) }

  /**
   * Decoded and compiled once per stored value: the raw JSON is compared by identity, then by content.
   * Requests never re-validate or recompile structures; only a store update does.
   */
  private class PatternCache(val raw: String?, val entries: List<PatternEntry>) {
    val index: PhrasePatterns.Index by lazy { PhrasePatterns.Index(entries.map { it.pattern }) }
  }
  @Volatile private var patternCache = PatternCache(null, emptyList())

  init {
    // Decode and validate stored structures once, on the storage dispatcher, before the first request needs them.
    updates.launch { try { patternState(store.data.first()).index } catch (_: IOException) { } }
  }
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

  /**
   * Matches a learned structure. Runs after exact phrases and only for requests the local grammar did not resolve.
   * The same safety key as phrases applies: questions, negations, conditions, quotes and joined actions never match.
   */
  suspend fun lookupPattern(request: InterpretationRequest): PhrasePatterns.Match? {
    val phrase = key(request) ?: return null
    val prefs = try { store.data.first() } catch (_: IOException) { return null }
    if (prefs[enabled] == false) return null
    return patternState(prefs).index.match(phrase) { request.now.clone() as java.util.Calendar }
  }

  private fun patternState(prefs: Preferences): PatternCache {
    val raw = prefs[patternData]
    val cached = patternCache
    if (cached.raw === raw || cached.raw == raw) return cached
    return PatternCache(raw, decodePatterns(raw)).also { patternCache = it }
  }

  // El panel puede cerrarse como resultado de una acción exitosa.
  internal fun saveConfirmed(request: InterpretationRequest, command: PhoneCommand, result: PhoneActionResult) {
    updates.launch(start = CoroutineStart.UNDISPATCHED) {
      try { learn(request, command, confirmed = true, result = result) } catch (_: IOException) { }
      try { observePattern(request, command, result) } catch (_: IOException) { }
    }
  }

  /** A model interpretation executed without a confirmation step. Only feeds structures, never exact phrases. */
  internal fun savePatternExample(request: InterpretationRequest, command: PhoneCommand, result: PhoneActionResult) {
    updates.launch(start = CoroutineStart.UNDISPATCHED) {
      try { observePattern(request, command, result) } catch (_: IOException) { }
    }
  }

  internal fun savePatternUse(template: String) {
    updates.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        store.edit { prefs ->
          val current = patterns(prefs)
          val entry = current.firstOrNull { it.template == template } ?: return@edit
          writePatterns(prefs, current.map { if (it === entry) it.copy(uses = it.uses + 1, date = clock()) else it })
        }
      } catch (_: IOException) { }
    }
  }

  /**
   * Records one executed example. Alarms and timers are handed to the Clock app, so their handoff counts;
   * volume must complete. Anything that needed input, failed or is uncertain is ignored.
   */
  internal suspend fun observePattern(request: InterpretationRequest, command: PhoneCommand, result: PhoneActionResult) {
    val phrase = key(request) ?: return
    val action = PhrasePatterns.actionFor(command) ?: return
    val succeeded = result.outcome == PhoneActionOutcome.COMPLETED ||
      result.outcome == PhoneActionOutcome.HANDOFF && action != PhrasePatterns.Action.VOLUME
    if (!succeeded || result.retainRequest || result.confirmation != null || result.appOptions.isNotEmpty()) return
    store.edit { prefs ->
      if (prefs[enabled] == false) return@edit
      // Parsing every span happens in the store transaction, off the caller's (main) thread.
      val observation = PhrasePatterns.observe(phrase, command) { request.now.clone() as java.util.Calendar } ?: return@edit
      val current = patterns(prefs)
      val existing = current.firstOrNull { it.prefix == observation.prefix && it.suffix == observation.suffix && it.action == observation.action }
      val updated = if (existing == null) {
        PatternEntry(observation.prefix, observation.suffix, observation.action, listOf(observation.value),
          listOf(observation.example), clock())
      } else existing.copy(
        values = (existing.values + observation.value).distinct().takeLast(MAX_PATTERN_VALUES),
        examples = (existing.examples + observation.example).distinct().takeLast(MAX_PATTERN_VALUES),
        date = clock(),
      )
      writePatterns(prefs, listOf(updated) + current.filterNot { it === existing })
    }
  }

  /** Turns a structure on or off without losing its examples. A disabled structure is never re-enabled by use. */
  suspend fun setPatternEnabled(template: String, value: Boolean) {
    store.edit { prefs ->
      writePatterns(prefs, patterns(prefs).map { if (it.template == template) it.copy(enabled = value) else it })
    }
  }

  suspend fun deletePattern(template: String) {
    store.edit { prefs -> writePatterns(prefs, patterns(prefs).filterNot { it.template == template }) }
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
    store.edit { it.remove(data); it.remove(lastUsed); it.remove(patternData) }
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

  /** Stores without patterns (before version 1.12) have no key: they read as an empty list, nothing is rewritten. */
  private fun patterns(prefs: Preferences): List<PatternEntry> = patternState(prefs).entries

  /**
   * Writes run on the storage dispatcher. The cache is primed with what was written, so the next request does not
   * decode or compile on the main thread. A failed write leaves a different raw value, which simply misses the cache.
   */
  private fun writePatterns(prefs: androidx.datastore.preferences.core.MutablePreferences, entries: List<PatternEntry>) {
    val kept = trimPatterns(entries)
    val raw = encodePatterns(kept)
    prefs[patternData] = raw
    patternCache = PatternCache(raw, kept).also { it.index }
  }

  companion object {
    const val LIMIT = 200
    private const val EXPORT_LIMIT = 262_144
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enabled = booleanPreferencesKey("enabled")
    private val data = stringPreferencesKey("entries")
    private val lastUsed = stringPreferencesKey("last_used")
    private val patternData = stringPreferencesKey("patterns")
    const val PATTERN_LIMIT = 60
    private const val MAX_PATTERN_VALUES = 6
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

    /** Keeps [PATTERN_LIMIT] structures. Those still collecting examples go first, then the least recent. */
    internal fun trimPatterns(entries: List<PatternEntry>): List<PatternEntry> {
      if (entries.size <= PATTERN_LIMIT) return entries
      val result = entries.toMutableList()
      while (result.size > PATTERN_LIMIT) {
        val index = result.indexOfLast { !it.active }.takeIf { it >= 0 } ?: result.lastIndex
        result.removeAt(index)
      }
      return result
    }

    internal fun encodePatterns(entries: List<PatternEntry>): String = JSONObject().put("version", 1)
      .put("patterns", JSONArray(entries.map {
        JSONObject().put("prefix", it.prefix).put("suffix", it.suffix).put("action", it.action.id)
          .put("values", JSONArray(it.values)).put("examples", JSONArray(it.examples))
          .put("date", it.date).put("uses", it.uses).put("enabled", it.enabled)
      })).toString()

    /** Unknown versions, actions or damaged items are skipped; they never block exact phrases. */
    internal fun decodePatterns(raw: String?): List<PatternEntry> {
      if (raw.isNullOrBlank()) return emptyList()
      val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
      if (root.optInt("version", 0) != 1) return emptyList()
      val array = root.optJSONArray("patterns") ?: return emptyList()
      return (0 until array.length()).mapNotNull { i ->
        runCatching {
          val obj = array.getJSONObject(i)
          val action = PhrasePatterns.Action.of(obj.getString("action")) ?: return@runCatching null
          fun strings(name: String) = obj.optJSONArray(name)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
          PatternEntry(obj.getString("prefix"), obj.getString("suffix"), action, strings("values").distinct(),
            strings("examples"), obj.optLong("date"), obj.optLong("uses").coerceAtLeast(0), obj.optBoolean("enabled", true))
            .takeIf { validPattern(it) }
        }.getOrNull()
      }.distinctBy { it.template }
    }

    /** Re-checks the literal text: a hand-edited store cannot introduce a broader or parameterized structure. */
    private fun validPattern(entry: PatternEntry): Boolean {
      if (entry.prefix.isNotEmpty() && !entry.prefix.endsWith(" ")) return false
      if (entry.suffix.isNotEmpty() && !entry.suffix.startsWith(" ")) return false
      val example = entry.examples.firstOrNull() ?: return false
      val observed = PhrasePatterns.observe(entry.prefix + example + entry.suffix, sampleCommand(entry, example) ?: return false)
      return observed != null && observed.prefix == entry.prefix && observed.suffix == entry.suffix
    }

    private fun sampleCommand(entry: PatternEntry, example: String): PhoneCommand? =
      PhrasePatterns.Index(listOf(entry.pattern.copy(enabled = true))).match(entry.prefix + example + entry.suffix)
        .let { match ->
          when (match) {
            is PhrasePatterns.Match.Command -> match.command
            // An ambiguous hour still identifies the structure; any reading of it is a valid sample.
            is PhrasePatterns.Match.Clarify -> (PhoneCommandParser().parse("${entry.action.canonical(example)} de la manana") as? com.example.phone.PhoneRoute.Local)?.command
            null -> null
          }
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
