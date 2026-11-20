package com.example.speech

import android.content.Context
import com.example.data.AssistantPreferences
import com.example.data.LearnedPhraseStore
import com.example.phone.ConfiguredRoutineStore
import com.example.phone.SavedPlacesRepository
import com.example.phone.launcherApps
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class VocabularyHintProvider(
  private val context: Context,
  private val learnedPhrases: LearnedPhraseStore,
) {
  private var refreshJob: Job? = null
  private var lastOptions: Options? = null
  @Volatile private var cachedPrompt: String? = null

  private data class Options(val enabled: Boolean, val includeContacts: Boolean, val contacts: List<String>)

  fun cached(): String? = cachedPrompt

  /** Forces the next [refresh] to rebuild, after routines, places or phrases changed outside the preferences. */
  fun invalidate() {
    refreshJob?.cancel()
    lastOptions = null
    cachedPrompt = null
  }

  // Called on the main thread. Only the completed snapshot is read by the upload worker.
  fun refresh(scope: CoroutineScope, prefs: AssistantPreferences) {
    val includeContacts = prefs.groqVocabularyEnabled && prefs.groqPinnedContactHintsEnabled
    val contacts = if (includeContacts) {
      listOfNotNull(prefs.pinnedContact, prefs.contextRule.drivingContact)
        .mapNotNull { contact -> contact.displayName.takeUnless { it == contact.phoneNumber } }
    } else emptyList()
    val options = Options(prefs.groqVocabularyEnabled, includeContacts, contacts)
    if (options != lastOptions) {
      // Drop any snapshot made with a previous selection before rebuilding.
      cachedPrompt = null
      refreshJob?.cancel()
      lastOptions = options
    } else if (refreshJob?.isActive == true) return
    if (!options.enabled) return

    refreshJob = scope.launch {
      cachedPrompt = withContext(Dispatchers.IO) {
        val routines = runCatching {
          ConfiguredRoutineStore(File(context.noBackupFilesDir, "configured-routines.json")).read().map { it.name }
        }.getOrDefault(emptyList())
        val places = runCatching { SavedPlacesRepository(context).aliases() }.getOrDefault(emptyList())
        val learned = learnedPhrases.uiSettings.first()
        val phrases = if (learned.enabled) {
          learned.entries.sortedByDescending { it.uses }.map { it.phrase }
        } else emptyList()
        val apps = runCatching { launcherApps(context).map { it.label }.sorted() }.getOrDefault(emptyList())
        VocabularyHints.build(routines, places, phrases, apps, options.contacts, options.includeContacts)
      }
    }
  }
}
