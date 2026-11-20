package com.example.data

import android.content.Context
import com.example.InstoApplication
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * Process-wide, non-blocking view of the assistant preferences.
 *
 * Main-thread code reads [current] and never waits for DataStore. Only before the first load completes (a cold
 * start) is it `null`; callers then either show a neutral state or call [awaitCurrent] from a coroutine.
 * Writes still go through [AssistantPreferencesRepository]; the cache follows them through its flow.
 */
class AssistantPreferencesCache(source: Flow<AssistantPreferences>, scope: CoroutineScope) {

  /** `null` until the first value is read; afterwards always the latest value DataStore emitted. */
  val preferences: StateFlow<AssistantPreferences?> = source
    // An unreadable file behaves like an empty one, as DataStore recommends, instead of crashing the process.
    .catch { if (it is IOException) emit(AssistantPreferences()) else throw it }
    .stateIn(scope, SharingStarted.Eagerly, null)

  /** The latest loaded value, or `null` during a cold start. Never blocks. */
  val current: AssistantPreferences? get() = preferences.value

  /** The latest value; suspends (without blocking a thread) only until the first load completes. */
  suspend fun awaitCurrent(): AssistantPreferences = preferences.value ?: preferences.filterNotNull().first()

  companion object {
    @Volatile private var fallback: AssistantPreferencesCache? = null

    fun create(context: Context): AssistantPreferencesCache = AssistantPreferencesCache(
      AssistantPreferencesRepository(context.applicationContext).preferencesFlow,
      CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** The application's cache; a process-wide one only when the application is not [InstoApplication]. */
    fun of(context: Context): AssistantPreferencesCache =
      (context.applicationContext as? InstoApplication)?.preferencesCache
        ?: fallback ?: synchronized(this) { fallback ?: create(context).also { fallback = it } }
  }
}
